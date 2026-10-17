#!/usr/bin/env python3
"""Inert source-handoff correspondence negative controls; no builds or network."""
import io
import json
from pathlib import Path
import stat
import struct
import unittest
import warnings
import zipfile

import verify_source_handoff as handoff


def fixture_zip(entries, mutate=None):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        for relative, content in entries:
            info = zipfile.ZipInfo(handoff.PREFIX + relative, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            mode = 0o755 if relative == 'gradlew' or relative.endswith('.sh') else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            if mutate:
                mutate(info)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                archive.writestr(info, content)
    stream.seek(0)
    return zipfile.ZipFile(stream)


class HandoffVerificationTest(unittest.TestCase):
    def setUp(self):
        self.expected = {'README.md': b'Synthetic source.', 'gradlew': b'Synthetic wrapper.'}

    def verify(self, entries=None, mutate=None):
        with fixture_zip(list(self.expected.items()) if entries is None else entries, mutate) as archive:
            return handoff.verify_entries(archive, self.expected)

    def test_exact_all_members_hashes_and_executable_wrapper(self):
        rows = self.verify()
        self.assertEqual(sorted(self.expected), [row['path'] for row in rows])
        self.assertEqual(handoff.digest(self.expected['README.md']), rows[0]['sha256'])

    def test_duplicate_member_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.verify(list(self.expected.items()) + [('README.md', b'Synthetic source.')])

    def test_reordered_members_rejected(self):
        with self.assertRaisesRegex(ValueError, 'order'):
            self.verify(list(reversed(list(self.expected.items()))))

    def test_missing_extra_and_unsafe_members_rejected(self):
        for entries in [[], [('README.md', b'Synthetic source.')],
                        list(self.expected.items()) + [('../extra', b'x')],
                        list(self.expected.items()) + [('extra.txt', b'x')]]:
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                self.verify(entries)

    def test_same_length_tamper_and_changed_length_rejected(self):
        for value in [b'Synthetic sourcE.', b'changed']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.verify([('README.md', value), ('gradlew', self.expected['gradlew'])])

    def test_symlink_executable_permission_timestamp_and_storage_rejected(self):
        for change in [lambda i: setattr(i, 'external_attr', (stat.S_IFLNK | 0o644) << 16),
                       lambda i: setattr(i, 'external_attr', (stat.S_IFREG | 0o777) << 16),
                       lambda i: setattr(i, 'date_time', (2026, 1, 1, 0, 0, 0)),
                       lambda i: setattr(i, 'compress_type', zipfile.ZIP_STORED),
                       lambda i: setattr(i, 'create_system', 0)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(mutate=change)

    def test_undeclared_central_metadata_rejected(self):
        for change in [lambda i: setattr(i, 'internal_attr', 1),
                       lambda i: setattr(i, 'external_attr', i.external_attr | 1),
                       lambda i: setattr(i, 'create_version', 21),
                       lambda i: setattr(i, 'extract_version', 21),
                       lambda i: setattr(i, 'reserved', 1)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(mutate=change)
        with fixture_zip(list(self.expected.items())) as archive:
            directory = archive.start_dir
            archive.fp.seek(0)
            raw = bytearray(archive.fp.read())
        # ZipFile's writer hardcodes the disk-start field to zero.
        struct.pack_into('<H', raw, directory + 34, 1)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive, self.assertRaises(ValueError):
            handoff.verify_entries(archive, self.expected)

    def test_empty_selection_is_not_a_pass(self):
        with fixture_zip([]) as archive, self.assertRaises(ValueError):
            handoff.verify_entries(archive, {})

    def test_comments_extra_fields_prefix_and_trailer_rejected(self):
        for change in [lambda i: setattr(i, 'comment', b'undeclared metadata'),
                       lambda i: setattr(i, 'extra', b'\xfe\xca\x00\x00')]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(mutate=change)
        with fixture_zip(list(self.expected.items())) as archive:
            archive.fp.seek(0)
            raw = archive.fp.read()
        for modified in [b'undeclared prefix' + raw, raw + b'undeclared trailer']:
            with self.subTest(modified=modified[:18]), zipfile.ZipFile(io.BytesIO(modified)) as archive:
                with self.assertRaises(ValueError):
                    handoff.verify_entries(archive, self.expected)
        buffer = io.BytesIO(raw)
        with zipfile.ZipFile(buffer, 'a') as archive:
            archive.comment = b'undeclared archive metadata'
        buffer.seek(0)
        with zipfile.ZipFile(buffer) as archive, self.assertRaises(ValueError):
            handoff.verify_entries(archive, self.expected)

    def test_unused_payload_inside_declared_deflate_span_rejected(self):
        expected = {'README.md': b'Synthetic source.'}
        with fixture_zip(list(expected.items())) as archive:
            info = archive.infolist()[0]
            directory = archive.start_dir
            archive.fp.seek(0)
            raw = bytearray(archive.fp.read())
        payload = b'undeclared synthetic trailer'
        # Insert after raw-deflate EOF, then consistently update local/central
        # sizes and directory offset. Standard ZipFile still decodes it.
        raw[directory:directory] = payload
        new_directory = directory + len(payload)
        struct.pack_into('<L', raw, 18, info.compress_size + len(payload))
        struct.pack_into('<L', raw, new_directory + 20, info.compress_size + len(payload))
        struct.pack_into('<L', raw, len(raw) - 6, new_directory)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            self.assertEqual(expected['README.md'], archive.read(handoff.PREFIX + 'README.md'))
            with self.assertRaisesRegex(ValueError, 'compressed'):
                handoff.verify_entries(archive, expected)

    def build_fixture(self, rows=None, jar_hash=None):
        data = b'Synthetic artifact bytes.'
        source = b'Synthetic production source.'
        rows = rows if rows is not None else [{'path': 'src/Example.java', 'bytes': len(source),
                                               'sha256': handoff.digest(source)}]
        manifest = {'plugin_sha256': jar_hash or handoff.digest(data), 'files': rows}
        return {'src/Example.java': source, 'docs/reports/build-inputs.json': json.dumps(manifest).encode()}, data

    def test_exact_build_input_correspondence(self):
        expected, jar = self.build_fixture()
        self.assertEqual((handoff.digest(jar), 1), handoff.verify_build_inputs(expected, jar))

    def test_wrong_artifact_and_changed_production_input_rejected(self):
        expected, jar = self.build_fixture(jar_hash='0' * 64)
        with self.assertRaises(ValueError):
            handoff.verify_build_inputs(expected, jar)
        expected, jar = self.build_fixture()
        expected['src/Example.java'] = b'Changed production source.'
        with self.assertRaises(ValueError):
            handoff.verify_build_inputs(expected, jar)

    def test_empty_missing_duplicate_boolean_and_wrong_hash_inputs_rejected(self):
        expected, jar = self.build_fixture()
        row = json.loads(expected['docs/reports/build-inputs.json'])['files'][0]
        for rows in [[], [row, row], [{**row, 'path': 'absent'}],
                     [{**row, 'bytes': True}], [{**row, 'sha256': '0' * 64}]]:
            expected, jar = self.build_fixture(rows)
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                handoff.verify_build_inputs(expected, jar)


if __name__ == '__main__':
    unittest.main()
