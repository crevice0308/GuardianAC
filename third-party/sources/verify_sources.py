#!/usr/bin/env python3
"""Inspect the dependency source handoff locally. Never executes upstream code."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import tarfile
import zipfile

MAX_EXPANDED = 128 * 1024 * 1024
MAX_ARCHIVE = 32 * 1024 * 1024
MAX_MEMBERS = 30000


def check(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_name(name):
    path = PurePosixPath(name)
    check(not path.is_absolute() and '..' not in path.parts and '\\' not in name,
          'Unsafe archive path: ' + name)


def inspect(path, record):
    check(path.stat().st_size == record['bytes'] <= MAX_ARCHIVE,
          'Archive size mismatch: ' + path.name)
    check(digest(path.read_bytes()) == record['sha256'],
          'Archive SHA-256 mismatch: ' + path.name)
    data = {}
    if path.name.endswith('.tar.gz'):
        with tarfile.open(path) as archive:
            members = archive.getmembers()
            check(len(members) < MAX_MEMBERS, 'Too many tar members')
            check(sum(m.size for m in members) < MAX_EXPANDED, 'Tar expands too far')
            check(len({m.name for m in members}) == len(members), 'Duplicate tar name')
            for member in members:
                safe_name(member.name)
                check(member.isfile() or member.isdir(), 'Non-file tar member')
                if member.isfile():
                    data[member.name] = archive.extractfile(member).read()
            expected = record.get('git_commit_from_official_archive_pax_header')
            check(not expected or archive.pax_headers.get('comment') == expected,
                  'Official archive commit header mismatch')
    else:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            check(len(members) < MAX_MEMBERS, 'Too many ZIP members')
            check(sum(m.file_size for m in members) < MAX_EXPANDED, 'ZIP expands too far')
            check(len({m.filename for m in members}) == len(members), 'Duplicate ZIP name')
            for member in members:
                safe_name(member.filename)
                check(not member.flag_bits & 1, 'Encrypted ZIP member')
                check(not stat.S_ISLNK(member.external_attr >> 16), 'Symlink in ZIP')
                if not member.is_dir():
                    data[member.filename] = archive.read(member)  # Verifies CRC.
    check(len(members) == record['member_count'], 'Member count mismatch')
    check(sum(map(len, data.values())) == record['expanded_file_bytes'],
          'Expanded byte count mismatch')
    return data


def validate_overlay(root, archives, overlay, classes):
    """Check preferred editable source against the pristine archive, without building."""
    def local_file(name):
        safe_name(name)
        path = root / name
        check(path.resolve().is_relative_to(root.resolve()) and path.is_file(),
              'Missing or out-of-tree local source input: ' + name)
        return path.read_bytes()

    replacement = overlay['source_replacement']
    original = archives[replacement['upstream_archive']][replacement['upstream_source_member']]
    modified = local_file(replacement['editable_source'])
    patch = local_file(replacement['unified_diff'])
    check(digest(original) == replacement['upstream_source_sha256'], 'Pristine builder source changed')
    check(digest(modified) == replacement['editable_source_sha256'], 'Local builder source hash changed')
    check(digest(patch) == replacement['unified_diff_sha256'], 'Local builder diff hash changed')
    transformed = original.decode('utf-8')
    for change in replacement['exact_text_transformations']:
        check(transformed.count(change['before']) == 1, 'Local patch must apply exactly once')
        transformed = transformed.replace(change['before'], change['after'])
    check(transformed.encode('utf-8') == modified, 'Local source differs from reviewed minimal patch')
    expected_diff = ''.join(difflib.unified_diff(
        original.decode('utf-8').splitlines(True), modified.decode('utf-8').splitlines(True),
        fromfile=replacement['diff_fromfile'], tofile=replacement['diff_tofile'])).encode('utf-8')
    check(patch == expected_diff, 'Supplied diff does not describe editable source')
    build = local_file(overlay['build_control_file']).decode('utf-8')
    entry = local_file(overlay['applied_from']).decode('utf-8')
    check(overlay['build_control_file'] in entry, 'Local patch build control is not applied')
    for required in ['verifyEmbeddedPacketEventsSource', 'compileEmbeddedPacketEvents',
                     'embeddedPacketEventsClasses', 'src/compileOnly/java/org/bukkit/Bukkit.java',
                     'io/github/retrooper/packetevents/bstats/**']:
        check(required in build, 'Local patch build wiring missing: ' + required)
    for auxiliary in overlay.get('auxiliary_build_sources', []):
        check(digest(local_file(auxiliary['path'])) == auxiliary['sha256'],
              'Auxiliary compile-only source hash changed: ' + auxiliary['path'])
    indexed = {row['guardianac_class_path'] for row in classes}
    local = {row['guardianac_class_path'] for row in overlay['local_rebuilt_classes']}
    removed = {row['guardianac_class_path'] for row in overlay['removed_upstream_classes']}
    check(len(local) == 2 and len(removed) == 14 and not local & removed,
          'Unexpected reviewed local/removed class set')
    check((local | removed).issubset(indexed), 'Overlay class is absent from pristine index')
    return {'patch_id': overlay['patch_id'], 'editable_source_sha256': digest(modified),
            'exact_diff_verified': True, 'locally_rebuilt_classes': len(local),
            'removed_upstream_classes': len(removed),
            'verified_auxiliary_build_sources': len(overlay.get('auxiliary_build_sources', []))}


def validate_plugin(path, classes, report, overlay):
    removed = {row['guardianac_class_path'] for row in overlay['removed_upstream_classes']}
    local = {row['guardianac_class_path'] for row in overlay['local_rebuilt_classes']}
    expected = {item['guardianac_class_path'] for item in classes} - removed - local
    unknown = []
    own = 0
    covered = 0
    rebuilt = 0
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        check(len(names) == len(set(names)), 'Duplicate member in plugin JAR')
        for auxiliary in overlay.get('auxiliary_build_sources', []):
            check(auxiliary['forbidden_runtime_class'] not in names,
                  'Compiler-only stub leaked into runtime: ' + auxiliary['forbidden_runtime_class'])
        check(local.issubset(names), 'Missing locally rebuilt PacketEvents builder classes')
        builder_prefix = 'dev/guardianac/libs/pe/impl/packetevents/factory/spigot/SpigotPacketEventsBuilder'
        check({n for n in names if n.startswith(builder_prefix) and n.endswith('.class')} == local,
              'Unexpected PacketEvents builder family in final JAR')
        forbidden = [token.encode('ascii') for token in overlay['forbidden_runtime_class_tokens_ascii_case_insensitive']]
        for name in names:
            if not name.endswith('.class'):
                continue
            check(not any(name.startswith(prefix) for prefix in overlay['forbidden_runtime_class_prefixes']),
                  'Forbidden bStats class remains in patched plugin: ' + name)
            data = archive.read(name).lower()
            check(not any(token in data for token in forbidden),
                  'Forbidden bStats class reference/endpoint remains: ' + name)
            if name in local:
                rebuilt += 1
            elif name.startswith('dev/guardianac/') and not name.startswith('dev/guardianac/libs/'):
                own += 1
            elif name in expected:
                covered += 1
            else:
                unknown.append(name)
        check(not unknown, 'Unmapped plugin classes: ' + ', '.join(unknown))
        natives = {n for n in names if n.endswith(('.so', '.dll', '.dylib'))}
        wanted = {r['runtime_member'] for r in report['sqlite_native_comparisons']}
        check(natives == wanted, 'Plugin native member set differs from audited input')
        for row in report['sqlite_native_comparisons']:
            data = archive.read(row['runtime_member'])
            check(len(data) == row['bytes'] and digest(data) == row['sha256'],
                  'Plugin native bytes changed: ' + row['runtime_member'])
    return {'filename': path.name, 'sha256': digest(path.read_bytes()),
            'upstream_source_mapped_dependency_class_paths': covered,
            'local_rebuilt_builder_class_paths': rebuilt,
            'local_patch_id': overlay['patch_id'],
            'bstats_classes_and_references_absent': True,
            'compiler_only_stubs_absent': True,
            'guardianac_classes_outside_dependency_scope': own,
            'unmapped_dependency_classes': unknown, 'matching_sqlite_natives': len(wanted)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin-jar', type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    inventory = json.loads((here / 'SOURCE-INVENTORY.json').read_text())
    report = json.loads((here / 'SOURCE-VALIDATION.json').read_text())
    index = json.loads((here / 'CLASS-SOURCE-INDEX.json').read_text())
    overlay = json.loads((here / 'RUNTIME-SOURCE-OVERLAY.json').read_text())
    archives = {row['file']: inspect(here / row['file'], row) for row in inventory['archives']}
    expected_sums = ''.join(f"{r['sha256']}  {r['file']}\n" for r in inventory['archives'])
    check((here / 'SHA256SUMS').read_text() == expected_sums, 'SHA256SUMS differs from inventory')
    coordinates = {row['coordinate'] for row in json.loads((here.parent / 'dependency-inventory.json').read_text())}
    mapped = {c for component in inventory['components'] for c in component['runtime_coordinates']}
    check(coordinates == mapped, 'Runtime coordinate coverage differs')
    check(index['classfile_count_in_dependency_artifacts'] == len(index['classes']), 'Class count differs')
    for row in index['classes']:
        check(row['coordinate'] in coordinates and bool(row['sources']), 'Unmapped class coordinate')
        for source in row['sources']:
            check(source['member'] in archives[source['archive']], 'Indexed source member missing')
    normal = lambda value: value.replace(b'\r\n', b'\n')
    for comparison in report['published_source_to_tag_comparison']:
        tagged = archives[comparison['tag_archive']]
        published = archives[comparison['published_sources']]
        special = {r['published_path']: r for r in comparison['verified_generated_sources'] +
                   comparison['verified_multi_release_path_mappings']}
        matches = 0
        for name, content in published.items():
            if not name.endswith(('.java', '.c')):
                continue
            if name in special:
                source = tagged[special[name]['tag_path']]
                if 'transformation' in special[name]:
                    check(name == 'com/google/gson/internal/GsonBuildConfig.java', 'Unknown generation rule')
                    source = source.replace(b'${project.version}', b'2.14.0')
                check(normal(source) == normal(content), 'Generated/multirelease source differs')
            else:
                candidates = [value for path, value in tagged.items() if path.endswith('/' + name)]
                check(any(normal(value) == normal(content) for value in candidates),
                      'Published source differs from tag: ' + name)
                matches += 1
        check(matches == comparison['exact_text_matches_after_crlf_normalization'], 'Source-match count differs')
        check(not comparison['unresolved'], 'Unresolved source comparison')
    source_id = inventory['sqlite_engine']['source_id']
    check(archives['sqlite-src-3530400.zip']['sqlite-src-3530400/manifest.uuid'].strip().decode() == source_id.split()[-1],
          'SQLite full source identity differs')
    check(source_id.encode() in archives['sqlite-amalgamation-3530400.zip']['sqlite-amalgamation-3530400/sqlite3.h'],
          'SQLite amalgamation identity differs')
    for row in report['sqlite_native_comparisons']:
        data = archives[row['tag_archive']][row['tag_member']]
        check(len(data) == row['bytes'] and digest(data) == row['sha256'], 'Xerial native reference differs')
        check(source_id.encode() in data, 'Native SQLite source ID differs')
    notice = report['xerial_notice']
    check(digest((here / notice['retained_copy']).read_bytes()) == notice['sha256'], 'Xerial NOTICE changed')
    check((here.parent / 'licenses/option-license.txt').read_bytes() == archives['option-v1.1.0.tar.gz']['option-1.1.0/license.txt'],
          'Option license differs')
    check((here.parent / 'licenses/bstats-LICENSE.txt').read_bytes() == archives['bstats-metrics-v3.1.0.tar.gz']['bstats-metrics-3.1.0/LICENSE'],
          'bStats license differs')
    local_result = validate_overlay(here.parent.parent, archives, overlay, index['classes'])
    print(f"PASS: {len(archives)} source archives; {len(coordinates)} pristine input artifacts; "
          f"{len(index['classes'])} indexed dependency classfiles; "
          f"{len(report['sqlite_native_comparisons'])} native source-identity matches.")
    print(json.dumps(local_result, indent=2))
    if args.plugin_jar:
        print(json.dumps(validate_plugin(args.plugin_jar, index['classes'], report, overlay), indent=2))
    print('Static inspection only; no upstream code executed and no independent rebuild claimed.')


if __name__ == '__main__':
    main()
