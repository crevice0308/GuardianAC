#!/usr/bin/env python3
"""No-server source-packaging safety regressions; no dependency/build execution."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

SOURCE_ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('package_source', SOURCE_ROOT / 'tools/package_source.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


def archive_bytes():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('public-source.txt', 'Synthetic public upstream-source fixture.')
    return buffer.getvalue()


class SourcePackagingSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guardianac-package-safety-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.upstream = self.root / 'third-party/sources'
        self.upstream.mkdir(parents=True)
        self.data = archive_bytes()
        self.records = []
        for i in range(21):
            name = f'component-{i:02d}-sources.zip'
            (self.upstream / name).write_bytes(self.data)
            self.records.append({'file': name, 'sha256': hashlib.sha256(self.data).hexdigest()})
        self.write_inventory()
        self.put('gradle/wrapper/gradle-wrapper.jar', self.data)
        self.put('src/main/java/example/Example.java', b'// Synthetic source.')

    def write_inventory(self):
        (self.upstream / 'SOURCE-INVENTORY.json').write_text(json.dumps({'archives': self.records}))

    def put(self, relative, data=b'Synthetic, non-sensitive fixture.'):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def assert_rejected(self):
        with self.assertRaises((ValueError, OSError)):
            list(package.sources(self.root))

    def test_current_legitimate_source_tree_is_accepted(self):
        entries = list(package.sources(SOURCE_ROOT))
        paths = {relative.as_posix() for _, relative in entries}
        self.assertIn('gradle/wrapper/gradle-wrapper.jar', paths)
        self.assertIn('LICENSE.txt', paths)
        self.assertEqual(21, len(paths & set(package.archive_allowlist(SOURCE_ROOT))))
        self.assertFalse(any('/build/' in '/' + value + '/' for value in paths))

    def test_reviewed_archives_and_wrapper_are_accepted(self):
        self.assertEqual(24, len(list(package.sources(self.root))))

    def test_private_file_extensions_and_environment_variants_are_rejected(self):
        for name in ['.env', '.env.local', '.envrc', 'production.env', 'x.env.backup',
                     '.ENV.PROD', 'secret.db', 'secret.DB', 'secret.db-wal', 'secret.db.bak',
                     'secret.sqlite', 'secret.sqlite3', 'secret.sqlite3-shm', 'secret.db3',
                     'ip-keys.bin', 'discord-webhook.txt', 'private.key', 'private.PEM',
                     'cert.p12', 'cert.pfx', 'secret.jks', 'secret.keystore', 'latest.log',
                     'level.dat', 'level.dat_old', 'r.0.0.mca', 'r.0.0.mcr', 'session.lock',
                     'server.properties', 'eula.txt', 'banned-ips.json', 'banned-players.json',
                     'usercache.json', 'ops.json', 'whitelist.json']:
            with self.subTest(name=name):
                path = self.put('docs/' + name)
                self.assert_rejected()
                path.unlink()

    def test_runtime_directories_are_rejected_even_when_empty(self):
        for name in sorted(package.RUNTIME_PARTS):
            with self.subTest(name=name):
                path = self.root / name
                path.mkdir()
                self.assert_rejected()
                path.rmdir()
        path = self.root / 'docs/World'
        path.mkdir(parents=True)
        self.assert_rejected()

    def test_cached_outputs_remain_excluded(self):
        self.put('build/private.db')
        self.put('.gradle/cache.key')
        self.put('.git/private.pem')
        self.put('tools/__pycache__/cache.pyc')
        self.assertEqual(24, len(list(package.sources(self.root))))

    def test_symlink_file_directory_and_inventory_are_rejected(self):
        for name, target in [('link.txt', 'src/main/java/example/Example.java'),
                             ('linked-source', 'src')]:
            with self.subTest(name=name):
                path = self.root / name
                path.symlink_to(target)
                self.assert_rejected()
                path.unlink()
        manifest = self.upstream / 'SOURCE-INVENTORY.json'
        original = manifest.read_bytes()
        manifest.unlink()
        self.put('manifest-copy.json', original)
        manifest.symlink_to('../../manifest-copy.json')
        self.assert_rejected()

    def test_unreviewed_archives_and_disguised_archive_are_rejected(self):
        for name in ['backup.zip', 'backup.tar.gz', 'backup.7z', 'plugins.jar',
                     'gradle/wrapper/other.jar', 'docs/source-copy.zip', 'docs/innocent.txt']:
            with self.subTest(name=name):
                path = self.put(name, self.data)
                self.assert_rejected()
                path.unlink()
        path = self.put('docs/backup.txt', b'\x1f\x8b' + b'synthetic-gzip-signature')
        self.assert_rejected()
        path.unlink()

    def test_disguised_database_is_rejected(self):
        self.put('docs/innocent.txt', b'SQLite format 3\x00' + b'Synthetic header only.')
        self.assert_rejected()

    def test_hard_linked_regular_files_are_rejected(self):
        source = self.root / 'src/main/java/example/Example.java'
        os.link(source, self.root / 'hard-linked-source.txt')
        self.assert_rejected()

    def test_private_key_pem_headers_in_renamed_flat_files_are_rejected(self):
        for kind in ['', 'RSA ', 'EC ', 'DSA ', 'OPENSSH ', 'ENCRYPTED ']:
            with self.subTest(kind=kind):
                data = ('Synthetic header-only negative test.\n-----BEGIN ' + kind
                        + 'PRIVATE KEY-----\nNo usable key material.\n').encode()
                path = self.put('docs/innocent.txt', data)
                self.assert_rejected()
                path.unlink()

    def test_reviewed_archive_hash_and_path_are_binding(self):
        name = self.records[0]['file']
        path = self.upstream / name
        path.write_bytes(self.data + b'changed')
        self.assert_rejected()
        path.write_bytes(self.data)
        path.rename(self.root / name)
        self.assert_rejected()

    def test_missing_reviewed_archive_is_rejected(self):
        (self.upstream / self.records[0]['file']).unlink()
        self.assert_rejected()

    def test_invalid_inventory_paths_duplicates_counts_and_hashes_are_rejected(self):
        original = json.loads(json.dumps(self.records))
        for name in ['../private.zip', '/private.zip', 'dir/private.zip',
                     r'..\private.zip', 'C:private.zip', 'unsafe\nname.zip']:
            with self.subTest(name=name):
                self.records = json.loads(json.dumps(original))
                self.records[0]['file'] = name
                self.write_inventory()
                self.assert_rejected()
        for records in [original[:-1], [original[0]] * 21,
                        [{'file': row['file'], 'sha256': 'bad'} for row in original]]:
            self.records = records
            self.write_inventory()
            self.assert_rejected()

    def test_unsafe_platform_paths_are_rejected(self):
        for name in [r'docs\secret.txt', 'C:secret.txt', 'newline\nsecret.txt']:
            with self.subTest(name=name):
                path = self.put(name)
                self.assert_rejected()
                path.unlink()


if __name__ == '__main__':
    unittest.main()
