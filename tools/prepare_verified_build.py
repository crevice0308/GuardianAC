#!/usr/bin/env python3
"""Prepare an isolated strict-verification build copy, entirely offline.

Usage: python3 tools/prepare_verified_build.py --destination /safe/fresh-directory
Then run ./gradlew --dependency-verification=strict ... inside that copy.

The distribution is opened read-only. Its pinned 155-input manifest and original
verification metadata must match before any output is created. Only reviewed
POM/module checksums from tools/verification-supplement.json are inserted into the
copy. No Gradle invocation, download, repository change or output copy-back occurs.
The JSON result contains aggregate file counts and hashes, never machine/user IDs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import xml.etree.ElementTree as ET

# Running this helper must not create bytecode caches in the distribution.
sys.dont_write_bytecode = True
import package_source

BASE_METADATA_SHA256 = '241b33da7a4ee138d1429dd4de30476bf1bee7691a00b6611a1ff6063aef37c1'
BUILD_MANIFEST_SHA256 = '58cdb74ba1a28ef16bd8506fb7858ecb8e3cbfa9eb2c2150745da689de95e1b7'
PLUGIN_SHA256 = 'fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1'
BUILD_INPUT_COUNT = 155
METADATA_PATH = 'gradle/verification-metadata.xml'
MANIFEST_PATH = 'docs/reports/build-inputs.json'
SUPPLEMENT_PATH = 'tools/verification-supplement.json'
MAX_SUPPLEMENT_ARTIFACTS = 256
MAX_JSON_BYTES = 1024 * 1024
NAMESPACE = 'https://schema.gradle.org/dependency-verification'
TAG = '{' + NAMESPACE + '}'
SCHEMA_ATTRIBUTE = '{http://www.w3.org/2001/XMLSchema-instance}schemaLocation'
SCHEMA_LOCATION = NAMESPACE + ' ' + NAMESPACE + '/dependency-verification-1.4.xsd'
REPOSITORIES = ('https://repo.maven.apache.org/maven2/', 'https://repo1.maven.org/maven2/')
DISALLOWED_PARTS = package_source.EXCLUDED_PARTS | {
    '.hg', '.svn', '.bzr', '_darcs', '.cache', '.mypy_cache', '.pytest_cache', '.ruff_cache',
}
HEX256 = re.compile(r'[0-9a-f]{64}\Z')
TOKEN = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_.+\-]*\Z')
GROUP = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_\-]*(?:\.[A-Za-z0-9_][A-Za-z0-9_\-]*)*\Z')
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError('Non-finite JSON number')

    if not isinstance(data, bytes) or len(data) > MAX_JSON_BYTES:
        raise ValueError('Invalid JSON size or encoding')
    try:
        return json.loads(data.decode('utf-8'), object_pairs_hook=pairs,
                          parse_constant=invalid_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError('Malformed UTF-8 JSON') from error


def fields(value, expected, label):
    if not isinstance(value, dict) or set(value) != set(expected):
        raise ValueError('Unexpected ' + label + ' fields')


def checksum(value):
    if not isinstance(value, str) or not HEX256.fullmatch(value):
        raise ValueError('Invalid lowercase SHA-256 checksum')
    return value


def coordinate(group, name, version):
    if (not isinstance(group, str) or not GROUP.fullmatch(group)
            or any(not isinstance(item, str) or not TOKEN.fullmatch(item)
                   or '..' in item for item in (name, version))):
        raise ValueError('Invalid Maven coordinate')
    return group, name, version


def relative_path(value):
    if (not isinstance(value, str) or not value or len(value) > 4096
            or value.startswith('/') or '\\' in value or ':' in value
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or any(part in ('', '.', '..') for part in value.split('/'))):
        raise ValueError('Noncanonical relative path')
    path = PurePosixPath(value)
    if any(part.casefold() in DISALLOWED_PARTS for part in path.parts):
        raise ValueError('VCS/cache/build paths are forbidden in the copy')
    return path


def absolute_path(value):
    raw = os.fspath(value)
    if (not isinstance(raw, str) or not raw or '\\' in raw or ':' in raw
            or any(ord(c) < 32 or ord(c) == 127 for c in raw)
            or any(part in ('', '.', '..') for part in raw.lstrip('/').split('/'))
            or raw.startswith('//')):
        raise ValueError('Noncanonical filesystem path')
    path = Path(raw)
    return path if path.is_absolute() else Path.cwd() / path


def open_directory(path, safe_parent=False):
    """Walk from / using O_NOFOLLOW; never resolve a symbolic link."""
    descriptor = os.open('/', DIRECTORY_FLAGS)
    try:
        for part in path.parts[1:]:
            child = os.open(part, DIRECTORY_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        info = os.fstat(descriptor)
        if safe_parent and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o022):
            raise ValueError('Destination parent must be owned by this user and not group/world writable')
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def walk_parent(root_fd, relative):
    descriptor = os.dup(root_fd)
    try:
        for part in relative.parts[:-1]:
            child = os.open(part, DIRECTORY_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def read_file(root_fd, relative):
    """Read a regular, singly linked file via no-follow directory descriptors."""
    relative = relative_path(relative)
    parent_fd = walk_parent(root_fd, relative)
    try:
        descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                             dir_fd=parent_fd)
    finally:
        os.close(parent_fd)
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or stat.S_IMODE(before.st_mode) & 0o7000):
            raise ValueError('Only regular, singly linked files without special mode bits are allowed')
        data = stream.read()
        after = os.fstat(stream.fileno())
        if ((before.st_size, before.st_mtime_ns, before.st_ctime_ns, before.st_nlink)
                != (after.st_size, after.st_mtime_ns, after.st_ctime_ns, after.st_nlink)
                or len(data) != before.st_size):
            raise ValueError('Source changed while it was read')
        return data, stat.S_IMODE(before.st_mode)


def select_source(root, root_fd):
    selected = {}
    directories = {}
    for path, relative in package_source.sources(root):
        name = relative.as_posix()
        relative = relative_path(name)
        if name in selected:
            raise ValueError('Duplicate selected source path')
        selected[name] = read_file(root_fd, name)
        for parent in relative.parents:
            if parent == PurePosixPath('.') or parent.as_posix() in directories:
                continue
            descriptor = walk_parent(root_fd, parent / 'unused')
            try:
                mode = stat.S_IMODE(os.fstat(descriptor).st_mode)
                if mode & 0o7000:
                    raise ValueError('Special directory mode bits are forbidden')
                directories[parent.as_posix()] = mode
            finally:
                os.close(descriptor)
    if not selected:
        raise ValueError('Empty selected source tree')
    validate_snapshot(selected)
    return selected, directories



class SnapshotFile:
    """Read-only facade lets the packager validate already secured bytes."""
    st_nlink = 1  # read_file already requires a single regular-file link.

    def __init__(self, relative, data):
        self.name, self.suffix = relative.name, relative.suffix
        self.data = data

    def stat(self):
        return self

    def read_bytes(self):
        return self.data


def validate_snapshot(selected):
    """Revalidate content policy after path-based selection, closing read races.

    In particular, a changed non-build file cannot gain archive/database/private
    key contents between the packager's initial inspection and secure snapshot.
    The archive hashes are taken from that same securely read inventory.
    """
    inventory_path = package_source.INVENTORY_PATH.as_posix()
    if inventory_path not in selected:
        raise ValueError('Missing reviewed source inventory')
    inventory = strict_json(selected[inventory_path][0])
    archives = inventory.get('archives') if isinstance(inventory, dict) else None
    if not isinstance(archives, list) or len(archives) != package_source.EXPECTED_UPSTREAM_ARCHIVES:
        raise ValueError('Expected exactly 21 reviewed upstream source archives')
    allowed = {}
    for row in archives:
        if not isinstance(row, dict):
            raise ValueError('Invalid upstream archive inventory record')
        name = row.get('file')
        relative = relative_path(name)
        if len(relative.parts) != 1 or not name.endswith(('.zip', '.tar.gz')):
            raise ValueError('Invalid upstream archive inventory filename')
        name = 'third-party/sources/' + name
        if name in allowed:
            raise ValueError('Duplicate upstream source inventory entry')
        allowed[name] = checksum(row.get('sha256'))
    if not set(allowed).issubset(selected):
        raise ValueError('Missing reviewed upstream source archive')
    for name, (data, mode) in selected.items():
        relative = relative_path(name)
        package_source.validate_file(SnapshotFile(relative, data), relative, allowed)


def verify_build_inputs(selected):
    if MANIFEST_PATH not in selected or METADATA_PATH not in selected:
        raise ValueError('Missing original build manifest or metadata')
    raw_manifest = selected[MANIFEST_PATH][0]
    if digest(raw_manifest) != BUILD_MANIFEST_SHA256:
        raise ValueError('Original build-input manifest SHA-256 mismatch')
    manifest = strict_json(raw_manifest)
    fields(manifest, ('plugin_sha256', 'scope', 'files'), 'build manifest')
    if (manifest['plugin_sha256'] != PLUGIN_SHA256 or not isinstance(manifest['scope'], str)
            or not isinstance(manifest['files'], list)
            or len(manifest['files']) != BUILD_INPUT_COUNT):
        raise ValueError('Original 155-input build manifest differs')
    seen = set()
    for row in manifest['files']:
        fields(row, ('path', 'bytes', 'sha256'), 'build input')
        relative_path(row['path'])
        name = row['path']
        if name in seen or name not in selected:
            raise ValueError('Duplicate or missing original build input')
        seen.add(name)
        data = selected[name][0]
        if (type(row['bytes']) is not int or row['bytes'] < 0 or row['bytes'] != len(data)
                or checksum(row['sha256']) != digest(data)):
            raise ValueError('Original build input bytes/SHA-256 differ: ' + name)
    if METADATA_PATH not in seen or digest(selected[METADATA_PATH][0]) != BASE_METADATA_SHA256:
        raise ValueError('Original verification metadata SHA-256 mismatch')
    return seen


def parse_metadata(data):
    if (not isinstance(data, bytes) or len(data) > 4 * 1024 * 1024
            or b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper()):
        raise ValueError('DTD/entity declarations and oversized metadata are forbidden')
    try:
        text = data.decode('utf-8')
        root = ET.fromstring(text, parser=ET.XMLParser(
            target=ET.TreeBuilder(insert_comments=True, insert_pis=True)))
    except (UnicodeDecodeError, ET.ParseError) as error:
        raise ValueError('Malformed UTF-8 verification XML') from error
    if (root.tag != TAG + 'verification-metadata'
            or root.attrib != {SCHEMA_ATTRIBUTE: SCHEMA_LOCATION}
            or [child.tag for child in root] != [TAG + 'configuration', TAG + 'components']):
        raise ValueError('Unexpected verification XML structure')
    config, components = root
    if (config.attrib or components.attrib
            or [child.tag for child in config] != [TAG + 'verify-metadata', TAG + 'verify-signatures']
            or config[0].text != 'true' or config[1].text != 'false'
            or any(child.attrib or list(child) for child in config)):
        raise ValueError('Strict metadata verification settings must remain unchanged; no trust rules')
    records = {}
    for component in components:
        if component.tag != TAG + 'component':
            raise ValueError('Unexpected verification component element')
        fields(component.attrib, ('group', 'name', 'version'), 'component')
        identity = coordinate(**component.attrib)
        if identity in records or not list(component):
            raise ValueError('Duplicate or empty verification component')
        artifacts = {}
        for artifact in component:
            if artifact.tag != TAG + 'artifact':
                raise ValueError('Unexpected verification artifact element')
            fields(artifact.attrib, ('name',), 'artifact')
            name = artifact.attrib['name']
            if (not TOKEN.fullmatch(name) or '..' in name or name in artifacts
                    or len(artifact) != 1 or artifact[0].tag != TAG + 'sha256'):
                raise ValueError('Duplicate artifact or unsupported verification checksum')
            hashed = artifact[0]
            fields(hashed.attrib, ('value', 'origin'), 'SHA-256')
            if list(hashed) or not hashed.attrib['origin']:
                raise ValueError('Unexpected SHA-256 metadata')
            artifacts[name] = checksum(hashed.attrib['value'])
        records[identity] = artifacts
    return records


def parse_supplement(data):
    supplement = strict_json(data)
    fields(supplement, ('schema', 'base_metadata_sha256', 'artifacts'), 'supplement')
    if (type(supplement['schema']) is not int or supplement['schema'] != 1
            or supplement['base_metadata_sha256'] != BASE_METADATA_SHA256
            or not isinstance(supplement['artifacts'], list)
            or not 1 <= len(supplement['artifacts']) <= MAX_SUPPLEMENT_ARTIFACTS):
        raise ValueError('Invalid supplement schema, base hash or artifact count')
    seen = set()
    for row in supplement['artifacts']:
        fields(row, ('group', 'name', 'version', 'artifact', 'sha256', 'source_urls'), 'supplement artifact')
        identity = coordinate(row['group'], row['name'], row['version'])
        artifact = row['artifact']
        if (not isinstance(artifact, str)
                or artifact not in (row['name'] + '-' + row['version'] + '.pom',
                                    row['name'] + '-' + row['version'] + '.module')):
            raise ValueError('Only exact POM/module metadata artifacts may be supplemented')
        key = identity + (artifact,)
        if key in seen:
            raise ValueError('Duplicate supplement artifact')
        seen.add(key)
        checksum(row['sha256'])
        urls = row['source_urls']
        expected_path = '/'.join((row['group'].replace('.', '/'), row['name'], row['version'], artifact))
        allowed_urls = {repository + expected_path for repository in REPOSITORIES}
        if (not isinstance(urls, list) or not 1 <= len(urls) <= len(REPOSITORIES)
                or any(not isinstance(url, str) or url not in allowed_urls for url in urls)
                or len(urls) != len(set(urls))):
            raise ValueError('Source URLs must be exact HTTPS Maven Central artifact paths')
    return supplement['artifacts']


def supplemented_metadata(base, supplement_data):
    if digest(base) != BASE_METADATA_SHA256:
        raise ValueError('Original verification metadata SHA-256 mismatch')
    original = parse_metadata(base)
    rows = parse_supplement(supplement_data)
    grouped = {}
    for row in rows:
        identity = row['group'], row['name'], row['version']
        if row['artifact'] in original.get(identity, {}):
            raise ValueError('Supplement must not replace or duplicate an existing artifact')
        grouped.setdefault(identity, []).append(row)
    additions = {}
    new_components = []
    for identity, artifacts in sorted(grouped.items()):
        opening = ('      <component group="%s" name="%s" version="%s">\n' % identity).encode()
        body = b''.join(('         <artifact name="%s">\n'
                         '            <sha256 value="%s" origin="Reviewed metadata supplement"/>\n'
                         '         </artifact>\n' % (row['artifact'], row['sha256'])).encode()
                        for row in sorted(artifacts, key=lambda item: item['artifact']))
        closing = b'      </component>\n'
        if identity in original:
            if base.count(opening) != 1:
                raise ValueError('Original component has unsupported lexical formatting')
            start = base.index(opening) + len(opening)
            position = base.find(closing, start)
            if position < 0:
                raise ValueError('Missing original component closing tag')
            additions[position] = body
        else:
            new_components.append(opening + body + closing)
    closing = b'   </components>\n'
    if base.count(closing) != 1:
        raise ValueError('Original metadata has unsupported lexical formatting')
    if new_components:
        additions[base.index(closing)] = b''.join(new_components)
    result = base
    for position, inserted in sorted(additions.items(), reverse=True):
        result = result[:position] + inserted + result[position:]
    expected = {identity: dict(artifacts) for identity, artifacts in original.items()}
    for row in rows:
        expected.setdefault((row['group'], row['name'], row['version']), {})[row['artifact']] = row['sha256']
    if parse_metadata(result) != expected:
        raise ValueError('Supplement changed unrelated verification entries')
    return result, len(rows)


def write_file(root_fd, name, data, mode):
    relative = relative_path(name)
    parent_fd = walk_parent(root_fd, relative)
    try:
        descriptor = os.open(relative.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=parent_fd)
    finally:
        os.close(parent_fd)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fchmod(stream.fileno(), mode)


def tree_digest(selected):
    hasher = hashlib.sha256()
    for name, (data, mode) in sorted(selected.items()):
        encoded = name.encode('utf-8')
        hasher.update(len(encoded).to_bytes(4, 'big') + encoded)
        hasher.update(mode.to_bytes(4, 'big') + len(data).to_bytes(8, 'big'))
        hasher.update(hashlib.sha256(data).digest())
    return hasher.hexdigest()



def destination_matches(parent_fd, name, output_fd):
    if output_fd is None:
        return False
    try:
        current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        return False
    owned = os.fstat(output_fd)
    return stat.S_ISDIR(current.st_mode) and (current.st_dev, current.st_ino) == (owned.st_dev, owned.st_ino)


def prepare(root, destination):
    root, destination = absolute_path(root), absolute_path(destination)
    if destination == root or destination.is_relative_to(root):
        raise ValueError('Destination must be outside the original source tree')
    source_fd = parent_fd = output_fd = None
    created = False
    try:
        source_fd = open_directory(root)
        root_mode = stat.S_IMODE(os.fstat(source_fd).st_mode)
        if root_mode & 0o7000:
            raise ValueError('Special source-root mode bits are forbidden')
        parent_fd = open_directory(destination.parent, safe_parent=True)
        try:
            os.stat(destination.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise ValueError('Destination already exists; choose a fresh directory')
        selected, directories = select_source(root, source_fd)
        original_inputs = verify_build_inputs(selected)
        if SUPPLEMENT_PATH not in selected:
            raise ValueError('Missing reviewed verification supplement')
        prepared_metadata, count = supplemented_metadata(selected[METADATA_PATH][0],
                                                          selected[SUPPLEMENT_PATH][0])
        prepared = dict(selected)
        prepared[METADATA_PATH] = prepared_metadata, selected[METADATA_PATH][1]
        os.mkdir(destination.name, 0o700, dir_fd=parent_fd)
        created = True
        output_fd = os.open(destination.name, DIRECTORY_FLAGS, dir_fd=parent_fd)
        for name in sorted(directories, key=lambda path: (path.count('/'), path)):
            relative = relative_path(name)
            directory_fd = walk_parent(output_fd, relative)
            try:
                os.mkdir(relative.name, 0o700, dir_fd=directory_fd)
            finally:
                os.close(directory_fd)
        for name, (data, mode) in sorted(prepared.items()):
            write_file(output_fd, name, data, mode)
        for name, content in selected.items():
            if read_file(source_fd, name) != content:
                raise ValueError('Original source changed during preparation')
            if read_file(output_fd, name) != prepared[name]:
                raise ValueError('Prepared source copy verification failed')
        if not destination_matches(parent_fd, destination.name, output_fd):
            raise ValueError('Destination was replaced during preparation')
        # Mode restoration is last, so restrictive source directory modes cannot
        # cause partial writes. Files are separate inodes, never links.
        for name in sorted(directories, key=lambda path: (-path.count('/'), path)):
            descriptor = walk_parent(output_fd, relative_path(name) / 'unused')
            try:
                os.fchmod(descriptor, directories[name])
            finally:
                os.close(descriptor)
        os.fchmod(output_fd, root_mode)
        if not destination_matches(parent_fd, destination.name, output_fd):
            raise ValueError('Destination was replaced during preparation')
        return {
            'status': 'prepared', 'source_file_count': len(selected),
            'copied_file_count': len(prepared), 'original_build_input_count': len(original_inputs),
            'copied_unchanged_build_input_count': len(original_inputs) - 1,
            'supplemented_artifact_count': count,
            'build_manifest_sha256': BUILD_MANIFEST_SHA256,
            'base_metadata_sha256': BASE_METADATA_SHA256,
            'prepared_metadata_sha256': digest(prepared_metadata),
            'supplement_sha256': digest(selected[SUPPLEMENT_PATH][0]),
            'source_tree_sha256': tree_digest(selected),
            'prepared_tree_sha256': tree_digest(prepared),
        }
    except BaseException:
        if created and destination_matches(parent_fd, destination.name, output_fd):
            # Never remove an unrelated directory installed at the original name.
            # The destination parent is private; fd-based rmtree also rejects
            # symlink substitutions during its own traversal.
            shutil.rmtree(destination.name, dir_fd=parent_fd)
        raise
    finally:
        for descriptor in (output_fd, parent_fd, source_fd):
            if descriptor is not None:
                os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True,
                        help='Nonexistent copy path under an existing private, user-owned parent')
    args = parser.parse_args()
    root = Path(__file__).absolute().parent.parent
    print(json.dumps(prepare(root, args.destination), sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
