#!/usr/bin/env python3
"""Inert fixtures for isolated build preparation; no Gradle, network or servers."""
from contextlib import redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
import prepare_verified_build as prepare


BASE = b'''<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components>
      <component group="org.example" name="example" version="1.0">
         <artifact name="example-1.0.jar">
            <sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" origin="Synthetic fixture"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
'''


def artifact(group='org.example', name='example', version='1.0', suffix='pom'):
    filename = name + '-' + version + '.' + suffix
    return {
        'group': group, 'name': name, 'version': version, 'artifact': filename,
        'sha256': prepare.digest(b'Inert synthetic metadata ' + filename.encode()),
        'source_urls': ['https://repo.maven.apache.org/maven2/' + group.replace('.', '/')
                        + '/' + name + '/' + version + '/' + filename],
    }


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def snapshot(root):
    return {path.relative_to(root).as_posix(): (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
            for path in root.rglob('*') if path.is_file()}


class PrepareVerifiedBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='guardianac-inert-prepare-')
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name)
        self.source = self.parent / 'source'
        self.source.mkdir(mode=0o750)
        self.destination = self.parent / 'prepared'
        self.base_patch = mock.patch.object(prepare, 'BASE_METADATA_SHA256', prepare.digest(BASE))
        self.base_patch.start()
        self.addCleanup(self.base_patch.stop)
        inputs = {
            prepare.METADATA_PATH: BASE,
            'build.gradle': b'// Inert build fixture. Never executed.\n',
            'gradlew': b'#!/bin/false\n# Inert wrapper. Never executed.\n',
        }
        for index in range(152):
            inputs['src/input%03d.txt' % index] = ('Synthetic input %03d.\n' % index).encode()
        self.manifest = {
            'plugin_sha256': prepare.PLUGIN_SHA256,
            'scope': 'Inert synthetic 155-file unit-test fixture.',
            'files': [{'path': name, 'bytes': len(data), 'sha256': prepare.digest(data)}
                      for name, data in sorted(inputs.items())],
        }
        for name, data in inputs.items():
            self.put(name, data, 0o751 if name == 'gradlew' else 0o640)
        self.put('README.md', b'Inert handoff fixture.\n')
        archives = []
        for index in range(prepare.package_source.EXPECTED_UPSTREAM_ARCHIVES):
            name = 'synthetic-%02d.zip' % index
            data = b'PK\x05\x06' + b'\x00' * 18
            self.put('third-party/sources/' + name, data)
            archives.append({'file': name, 'sha256': prepare.digest(data)})
        self.put(prepare.package_source.INVENTORY_PATH.as_posix(), json_bytes({'archives': archives}))
        self.supplement = {
            'schema': 1, 'base_metadata_sha256': prepare.BASE_METADATA_SHA256,
            'artifacts': [artifact(), artifact(name='new-parent'), artifact(name='new-parent', suffix='module')],
        }
        self.put(prepare.SUPPLEMENT_PATH, json_bytes(self.supplement))
        self.put(prepare.MANIFEST_PATH, json_bytes(self.manifest))
        self.manifest_patch = mock.patch.object(prepare, 'BUILD_MANIFEST_SHA256',
                                               prepare.digest(json_bytes(self.manifest)))
        self.manifest_patch.start()
        self.addCleanup(self.manifest_patch.stop)

    def put(self, name, data, mode=0o644):
        path = self.source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(mode)

    def repin_manifest(self):
        data = json_bytes(self.manifest)
        self.put(prepare.MANIFEST_PATH, data)
        prepare.BUILD_MANIFEST_SHA256 = prepare.digest(data)

    def prepare(self):
        return prepare.prepare(self.source, self.destination)

    def assert_supplement_rejected(self, value):
        with self.assertRaises((ValueError, OSError)):
            prepare.parse_supplement(json_bytes(value))

    def test_success_exact_originals_modes_separate_inodes_and_aggregate_report(self):
        before = snapshot(self.source)
        directory_modes = {path.relative_to(self.source): stat.S_IMODE(path.stat().st_mode)
                           for path in self.source.rglob('*') if path.is_dir()}
        result = self.prepare()
        self.assertEqual(before, snapshot(self.source))
        after = snapshot(self.destination)
        self.assertEqual(set(before), set(after))
        for name, content in before.items():
            if name != prepare.METADATA_PATH:
                self.assertEqual(content, after[name], name)
            self.assertNotEqual((self.source / name).stat().st_ino,
                                (self.destination / name).stat().st_ino)
            self.assertEqual(1, (self.destination / name).stat().st_nlink)
        for path, mode in directory_modes.items():
            self.assertEqual(mode, stat.S_IMODE((self.destination / path).stat().st_mode))
        self.assertEqual(stat.S_IMODE(self.source.stat().st_mode),
                         stat.S_IMODE(self.destination.stat().st_mode))
        self.assertEqual(0o751, stat.S_IMODE((self.destination / 'gradlew').stat().st_mode))
        self.assertEqual(155, result['original_build_input_count'])
        self.assertEqual(154, result['copied_unchanged_build_input_count'])
        self.assertEqual(3, result['supplemented_artifact_count'])
        self.assertEqual(len(before), result['source_file_count'])
        self.assertNotEqual(result['source_tree_sha256'], result['prepared_tree_sha256'])
        self.assertNotIn(str(self.parent), json.dumps(result))
        for key, value in result.items():
            self.assertTrue(key == 'status' or key.endswith(('_count', '_sha256')))
            if key.endswith('_sha256'):
                self.assertRegex(value, r'^[0-9a-f]{64}$')

    def test_xml_inserts_only_new_entries_and_preserves_every_original_byte(self):
        result, count = prepare.supplemented_metadata(BASE, json_bytes(self.supplement))
        self.assertEqual(3, count)
        stripped = re.sub(rb'         <artifact name="[^"]+">\n'
                          rb'            <sha256 value="[^"]+" origin="Reviewed metadata supplement"/>\n'
                          rb'         </artifact>\n', b'', result)
        stripped = stripped.replace(b'      <component group="org.example" name="new-parent" version="1.0">\n'
                                    b'      </component>\n', b'')
        self.assertEqual(BASE, stripped)
        old, new = prepare.parse_metadata(BASE), prepare.parse_metadata(result)
        for identity, rows in old.items():
            for name, checksum in rows.items():
                self.assertEqual(checksum, new[identity][name])

    def test_output_deterministic_despite_supplement_order(self):
        first, _ = prepare.supplemented_metadata(BASE, json_bytes(self.supplement))
        self.supplement['artifacts'].reverse()
        second, _ = prepare.supplemented_metadata(BASE, json_bytes(self.supplement))
        self.assertEqual(first, second)

    def test_original_manifest_byte_tamper_is_rejected_without_output(self):
        self.put(prepare.MANIFEST_PATH, json_bytes(self.manifest) + b' ')
        with self.assertRaisesRegex(ValueError, 'manifest SHA'):
            self.prepare()
        self.assertFalse(self.destination.exists())

    def test_same_length_and_different_length_build_input_tamper_rejected(self):
        path = self.source / 'build.gradle'
        original = path.read_bytes()
        for changed in (original.replace(b'Inert', b'INERT'), b'changed'):
            with self.subTest(changed=changed):
                path.write_bytes(changed)
                with self.assertRaisesRegex(ValueError, 'build input bytes'):
                    self.prepare()
                self.assertFalse(self.destination.exists())
        path.write_bytes(original)

    def test_build_manifest_shape_count_duplicate_escape_boolean_and_digest_rejected(self):
        original = copy.deepcopy(self.manifest)
        changes = [lambda d: d.update(extra=True),
                   lambda d: d.update(plugin_sha256='0' * 64),
                   lambda d: d.update(scope=True),
                   lambda d: d['files'].pop(),
                   lambda d: d['files'].__setitem__(1, d['files'][0]),
                   lambda d: d['files'][0].update(path='../escape'),
                   lambda d: d['files'][0].update(path='/absolute'),
                   lambda d: d['files'][0].update(path='src//input000.txt'),
                   lambda d: d['files'][0].update(path='absent.txt'),
                   lambda d: d['files'][0].update(bytes=True),
                   lambda d: d['files'][0].update(sha256='0' * 64),
                   lambda d: d['files'][0].update(sha256='A' * 64)]
        for change in changes:
            self.manifest = copy.deepcopy(original)
            change(self.manifest)
            self.repin_manifest()
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.destination.exists())

    def test_manifest_repin_does_not_bypass_pinned_base_metadata(self):
        data = BASE.replace(b'Synthetic fixture', b'Changed fixture!')
        self.put(prepare.METADATA_PATH, data)
        row = next(row for row in self.manifest['files'] if row['path'] == prepare.METADATA_PATH)
        row.update(bytes=len(data), sha256=prepare.digest(data))
        self.repin_manifest()
        with self.assertRaisesRegex(ValueError, 'metadata SHA'):
            self.prepare()

    def test_missing_supplement_rejected_without_output(self):
        (self.source / prepare.SUPPLEMENT_PATH).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing reviewed'):
            self.prepare()
        self.assertFalse(self.destination.exists())

    def test_json_duplicate_keys_nested_duplicates_encoding_nan_and_limits(self):
        for data in (b'{"schema":1,"schema":1}', b'{"a":{"x":1,"x":2}}',
                     b'\xff', b'{', b'{"a":NaN}', b'{"a":Infinity}',
                     b' ' * (prepare.MAX_JSON_BYTES + 1)):
            with self.subTest(data=data[:50]), self.assertRaises(ValueError):
                prepare.strict_json(data)

    def test_supplement_fields_types_and_empty_or_oversized_list(self):
        for change in (lambda d: d.update(schema=True), lambda d: d.update(schema=2),
                       lambda d: d.update(base_metadata_sha256='0' * 64),
                       lambda d: d.update(artifacts=[]), lambda d: d.update(artifacts={}),
                       lambda d: d.update(artifacts=[artifact()] * (prepare.MAX_SUPPLEMENT_ARTIFACTS + 1)),
                       lambda d: d.update(extra='unexpected'), lambda d: d.pop('schema'),
                       lambda d: d['artifacts'][0].update(extra='unexpected'),
                       lambda d: d['artifacts'][0].pop('sha256'),
                       lambda d: d['artifacts'].__setitem__(0, False)):
            data = copy.deepcopy(self.supplement)
            change(data)
            with self.subTest(change=change):
                self.assert_supplement_rejected(data)
        for data in (None, [], True, 'text'):
            self.assert_supplement_rejected(data)

    def test_coordinates_and_extensions_cannot_allow_jars_or_path_escapes(self):
        for key, values in {'group': ['org..example', 'org/example', '*', None, 1],
                            'name': ['../example', 'example/child', '*', '', True, 'x..y'],
                            'version': ['1/0', '..', '1%2F0', '*', None],
                            'artifact': ['example-1.0.jar', 'evil.pom', 'example-1.0.jar.pom',
                                         '../example-1.0.pom', 'example-1.0.POM', False]}.items():
            for value in values:
                data = copy.deepcopy(self.supplement)
                data['artifacts'][0][key] = value
                with self.subTest(key=key, value=value):
                    self.assert_supplement_rejected(data)

    def test_sha256_is_exact_lowercase_hex(self):
        for value in ('a' * 63, 'a' * 65, 'A' * 64, 'g' * 64, 'a' * 64 + '\n', 1, None, True):
            data = copy.deepcopy(self.supplement)
            data['artifacts'][0]['sha256'] = value
            with self.subTest(value=value):
                self.assert_supplement_rejected(data)

    def test_source_urls_exact_origins_and_paths_no_transport_relaxation(self):
        good = self.supplement['artifacts'][0]['source_urls'][0]
        invalid = [[], good, [good, good], [None], [good, 1],
                   [good.replace('https:', 'http:')], [good + '?download=1'], [good + '#fragment'],
                   [good.replace('repo.maven.apache.org', 'evil.example')],
                   [good.replace('repo.maven.apache.org', 'repo.maven.apache.org.evil.example')],
                   [good.replace('repo.maven.apache.org', 'user@repo.maven.apache.org')],
                   [good.replace('repo.maven.apache.org', 'repo.maven.apache.org:443')],
                   [good.replace('/org/', '/%6frg/')], [good.replace('/maven2/', '/maven2/../maven2/')],
                   [good.replace('.pom', '.jar')], [good + '.sha256']]
        for value in invalid:
            data = copy.deepcopy(self.supplement)
            data['artifacts'][0]['source_urls'] = value
            with self.subTest(value=value):
                self.assert_supplement_rejected(data)
        self.supplement['artifacts'][0]['source_urls'].append(good.replace('repo.maven.apache.org', 'repo1.maven.org'))
        self.assertEqual(3, len(prepare.parse_supplement(json_bytes(self.supplement))))

    def test_duplicate_supplement_artifacts_rejected_even_with_same_hash(self):
        for changed in (False, True):
            data = copy.deepcopy(self.supplement)
            duplicate = copy.deepcopy(data['artifacts'][0])
            if changed:
                duplicate['sha256'] = '0' * 64
            data['artifacts'].append(duplicate)
            with self.subTest(changed=changed):
                self.assert_supplement_rejected(data)

    def test_existing_metadata_artifact_cannot_be_replaced_or_duplicated(self):
        base = BASE.replace(b'example-1.0.jar', b'example-1.0.pom')
        with mock.patch.object(prepare, 'BASE_METADATA_SHA256', prepare.digest(base)):
            for value in ('a' * 64, 'b' * 64):
                data = copy.deepcopy(self.supplement)
                data['base_metadata_sha256'] = prepare.digest(base)
                data['artifacts'][0]['sha256'] = value
                with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'existing artifact'):
                    prepare.supplemented_metadata(base, json_bytes(data))

    def test_bad_or_unsafe_xml_cannot_be_supplemented_even_with_test_pin(self):
        variants = [b'not XML', BASE[:-20], b'\xff' + BASE,
                    BASE.replace(b'   <configuration>', b'<!DOCTYPE x [<!ENTITY e "x">]>\n   <configuration>'),
                    BASE.replace(b'<verify-metadata>true', b'<verify-metadata>false'),
                    BASE.replace(b'<verify-signatures>false', b'<verify-signatures>true'),
                    BASE.replace(b'   </configuration>', b'      <trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts>\n   </configuration>'),
                    BASE.replace(b'<sha256 value=', b'<sha1 value=').replace(b'origin="Synthetic fixture"/>', b'origin="Synthetic fixture"/>'),
                    BASE.replace(b'group="org.example"', b'group="*"'),
                    BASE.replace(b'example-1.0.jar', b'../example-1.0.jar'),
                    BASE.replace(b'      <verify-metadata>', b'      <!-- extra -->\n      <verify-metadata>'),
                    BASE.replace(b'   <components>', b'   <components extra="true">')]
        for base in variants:
            with self.subTest(base=base[:80]), mock.patch.object(prepare, 'BASE_METADATA_SHA256', prepare.digest(base)):
                data = copy.deepcopy(self.supplement)
                data['base_metadata_sha256'] = prepare.digest(base)
                with self.assertRaises(ValueError):
                    prepare.supplemented_metadata(base, json_bytes(data))

    def test_duplicate_existing_xml_components_and_artifacts_rejected(self):
        component = re.search(rb'      <component.*?      </component>\n', BASE, re.S).group()
        entry = re.search(rb'         <artifact.*?         </artifact>\n', BASE, re.S).group()
        for base in (BASE.replace(component, component + component), BASE.replace(entry, entry + entry)):
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                prepare.parse_metadata(base)

    def test_existing_destination_file_directory_and_symlink_never_overwritten(self):
        self.destination.write_bytes(b'Keep me.')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.prepare()
        self.assertEqual(b'Keep me.', self.destination.read_bytes())
        self.destination.unlink()
        self.destination.mkdir()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.prepare()
        self.destination.rmdir()
        self.destination.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.prepare()
        self.assertTrue(self.destination.is_symlink())

    def test_destination_inside_source_same_source_and_missing_parent_rejected(self):
        for destination in (self.source, self.source / 'copy', self.parent / 'missing' / 'copy'):
            with self.subTest(destination=destination), self.assertRaises((ValueError, OSError)):
                prepare.prepare(self.source, destination)
        self.assertFalse((self.parent / 'missing').exists())

    def test_destination_parent_must_be_private_owned_and_not_symlink(self):
        unsafe = self.parent / 'unsafe'
        unsafe.mkdir(mode=0o777)
        unsafe.chmod(0o777)
        with self.assertRaisesRegex(ValueError, 'parent must'):
            prepare.prepare(self.source, unsafe / 'copy')
        alias = self.parent / 'alias'
        alias.symlink_to(self.parent, target_is_directory=True)
        with self.assertRaises(OSError):
            prepare.prepare(self.source, alias / 'copy')
        self.assertFalse((self.parent / 'copy').exists())

    def test_noncanonical_destination_spellings_are_rejected(self):
        for destination in ('./copy', '../copy', 'copy/', 'a//b', '//tmp/copy', 'a/./b',
                            str(self.parent) + '/./copy', 'a\\b', 'a:b', 'a\nb'):
            with self.subTest(destination=destination), self.assertRaises(ValueError):
                prepare.prepare(self.source, destination)

    def test_source_file_and_directory_symlinks_and_hardlinks_rejected(self):
        path = self.source / 'README.md'
        target = self.parent / 'inert-target.txt'
        target.write_bytes(b'Outside the source fixture.')
        path.unlink()
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symbolic links'):
            self.prepare()
        path.unlink()
        os.link(target, path)
        with self.assertRaisesRegex(ValueError, 'hard-linked'):
            self.prepare()
        path.unlink()
        (self.source / 'linked-directory').symlink_to(self.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symbolic links'):
            self.prepare()
        self.assertEqual(b'Outside the source fixture.', target.read_bytes())
        self.assertFalse(self.destination.exists())

    def test_source_root_symlink_and_symlink_ancestor_rejected(self):
        alias = self.parent / 'alias'
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(OSError):
            prepare.prepare(alias, self.destination)
        alias.unlink()
        alias.symlink_to(self.parent, target_is_directory=True)
        with self.assertRaises(OSError):
            prepare.prepare(alias / 'source', self.destination)

    def test_source_special_file_bits_rejected(self):
        path = self.source / 'README.md'
        path.chmod(0o4644)
        try:
            with self.assertRaisesRegex(ValueError, 'special mode bits'):
                self.prepare()
        finally:
            path.chmod(0o644)

    def test_vcs_cache_build_outputs_are_excluded_and_other_vcs_is_rejected(self):
        for folder in ('.git', '.gradle', 'build', '__pycache__'):
            self.put(folder + '/generated.txt', b'Never copied.')
        result = self.prepare()
        for folder in ('.git', '.gradle', 'build', '__pycache__'):
            self.assertFalse((self.destination / folder).exists())
        self.assertEqual(len(snapshot(self.destination)), result['copied_file_count'])
        self.put('.hg/store.txt', b'Other VCS state is forbidden.')
        with self.assertRaisesRegex(ValueError, 'VCS/cache/build'):
            prepare.prepare(self.source, self.parent / 'another-copy')

    def test_partial_write_error_and_interrupt_leave_original_untouched(self):
        original_write = prepare.write_file
        before = snapshot(self.source)
        for error in (OSError('Synthetic write failure.'), KeyboardInterrupt()):
            def fail_after_write(*args):
                original_write(*args)
                raise error
            with self.subTest(error=type(error).__name__), mock.patch.object(prepare, 'write_file', fail_after_write):
                with self.assertRaises(type(error)):
                    self.prepare()
            self.assertEqual(before, snapshot(self.source))
            self.assertFalse(self.destination.exists())

    def test_destination_replacement_is_never_reported_ready_or_deleted(self):
        original_write = prepare.write_file
        before = snapshot(self.source)
        for interrupt in (False, True):
            moved = self.parent / ('owned-copy-' + str(interrupt))
            replaced = False
            def replace_after_write(*args):
                nonlocal replaced
                original_write(*args)
                if not replaced:
                    replaced = True
                    self.destination.rename(moved)
                    self.destination.mkdir()
                    (self.destination / 'keep.txt').write_bytes(b'Unrelated replacement data.')
                    if interrupt:
                        raise KeyboardInterrupt()
            with self.subTest(interrupt=interrupt), mock.patch.object(prepare, 'write_file', replace_after_write):
                with self.assertRaises(KeyboardInterrupt if interrupt else ValueError):
                    self.prepare()
            self.assertEqual(b'Unrelated replacement data.', (self.destination / 'keep.txt').read_bytes())
            self.assertEqual(before, snapshot(self.source))
            (self.destination / 'keep.txt').unlink()
            self.destination.rmdir()

    def test_readback_difference_removes_failed_copy_and_preserves_original(self):
        original_read = prepare.read_file
        before = snapshot(self.source)
        calls = 0
        def changed_read(*args):
            nonlocal calls
            calls += 1
            data, mode = original_read(*args)
            if calls > len(before):
                return data + b'Synthetic read inconsistency.', mode
            return data, mode
        with mock.patch.object(prepare, 'read_file', changed_read):
            with self.assertRaisesRegex(ValueError, 'changed during'):
                self.prepare()
        self.assertEqual(before, snapshot(self.source))
        self.assertFalse(self.destination.exists())

    def test_secure_snapshot_rejects_content_changed_after_initial_selection(self):
        original_read = prepare.read_file
        for content in (b'SQLite format 3\x00inert fixture', b'PK\x03\x04inert fixture',
                        b'-----BEGIN ' + b'PRIVATE KEY-----\ninert fixture'):
            def changed_read(root_fd, name):
                data, mode = original_read(root_fd, name)
                return (content, mode) if name == 'README.md' else (data, mode)
            with self.subTest(content=content), mock.patch.object(prepare, 'read_file', changed_read):
                with self.assertRaises(ValueError):
                    self.prepare()
            self.assertFalse(self.destination.exists())

    def test_bad_supplement_leaves_source_and_destination_unchanged(self):
        self.supplement['artifacts'][0]['artifact'] = 'example-1.0.jar'
        self.put(prepare.SUPPLEMENT_PATH, json_bytes(self.supplement))
        before = snapshot(self.source)
        with self.assertRaisesRegex(ValueError, 'POM/module'):
            self.prepare()
        self.assertEqual(before, snapshot(self.source))
        self.assertFalse(self.destination.exists())

    def test_cli_passes_only_destination_and_emits_plain_aggregate_json(self):
        output = io.StringIO()
        with mock.patch.object(prepare, 'prepare', return_value={'source_file_count': 180}) as action:
            with mock.patch('sys.argv', ['prepare_verified_build.py', '--destination', str(self.destination)]):
                with redirect_stdout(output):
                    prepare.main()
        self.assertEqual({'source_file_count': 180}, json.loads(output.getvalue()))
        self.assertEqual(str(self.destination), action.call_args.args[1])


if __name__ == '__main__':
    unittest.main()
