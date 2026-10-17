"""No network/server/build work; fail-closed result oracle regression tests."""
import unittest
import tempfile
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'runtime'))
from observations import *

class ObservationTests(unittest.TestCase):
    def test_exact_source_range(self):
        self.assertEqual(len(set(source_address(i) for i in range(64))), 64)
        self.assertEqual(source_address(0), '127.0.0.2')
        self.assertEqual(source_address(63), '127.0.0.65')
        for invalid in (-1, 64, 1000, True, '0'):
            with self.assertRaises(ValueError): source_address(invalid)
    def test_status_counter_must_exist_once(self):
        self.assertEqual(denied_joins('GuardianAC | denied joins 20'), 20)
        for invalid in ('', 'denied joins 20', '| denied joins 20 | denied joins 21'):
            with self.assertRaises(ValueError): denied_joins(invalid)
    def test_rate_denial_requires_exact_wire_text(self):
        for reason in ('Connection rate limit. Please retry shortly.',
                       '接続頻度の上限に達しました。少し待ってから再接続してください。'):
            raw = reason.encode('utf-8').hex()
            self.assertEqual(burst_outcome({'disconnect': raw}), 'rate-denied')
        for reason in ('Connection throttled!', 'Server full!', 'Security database is busy.',
                       '接続頻度の上限', 'セキュリティデータベースが混み合っています。少し待ってから再接続してください。'):
            self.assertEqual(burst_outcome({'disconnect': reason.encode().hex()}), 'unaccounted')
        self.assertFalse(has_rate_denial({'disconnect': 'not-hex'}))
    def test_accepted_must_still_be_connected(self):
        self.assertEqual(burst_outcome({'joined': True, 'closed': False}), 'accepted')
        self.assertEqual(burst_outcome({'joined': True, 'closed': True}), 'unaccounted')
        self.assertEqual(burst_outcome({'state': 'game'}), 'unaccounted')
    def test_cleanup_must_be_complete(self):
        good = {'players': [], **{k: 0 for k in CLEANUP_FIELDS}}
        self.assertTrue(all_cleaned(good))
        for key in CLEANUP_FIELDS:
            bad = good.copy(); bad.pop(key)
            self.assertFalse(all_cleaned(bad))
            bad[key] = 1
            self.assertFalse(all_cleaned(bad))
    def test_ore_coverage_no_vacuous_or_truncated_success(self):
        good = {'a': {'retained_all': True, 'states': [1, 1]}}
        self.assertTrue(all_masked(good, 1, 1))
        self.assertFalse(all_masked({}, 0, 1))
        self.assertFalse(all_masked(good, 64, 1))
        self.assertFalse(all_masked({'a': {'retained_all': True, 'states': []}}, 1, 1))
        self.assertFalse(all_masked({'a': {'retained_all': False, 'states': [1]}}, 1, 1))
        self.assertFalse(all_masked({'a': {'retained_all': True, 'states': [1, 2]}}, 1, 1))
    def test_helper_errors_and_empty_assertions_fail(self):
        good = {'run_completed': True, 'server_exit': 0,
                'server_exit_lifecycle': {'state': 'normal_stop', 'normal_stop': True, 'termination_observed': True,
                    'terminal': True, 'reason': 'requested_stop', 'child_returncode': 0, 'reported_returncode': 0},
                'assertions': [{'test': 'completed_check', 'passed': True}]}
        self.assertTrue(successful(good))
        for override in ({'error': 'helper failed'}, {'error': None}, {'shutdown_error': ''}, {'server_exit': 1},
                         {'server_exit': False}, {'server_exit': None}, {'server_exit': '0'}, {'server_exit': 0.0},
                         {'server_exit_lifecycle': None}, {'run_completed': False}, {'assertions': []},
                         {'assertions': [{'passed': False}]}, {'assertions': [{}]}):
            self.assertFalse(successful({**good, **override}))

    def test_database_backup_does_not_ambiguate_primary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'bans/backups').mkdir(parents=True)
            primary = root / 'bans/bans.db'; primary.write_bytes(b'primary fixture')
            (root / 'bans/backups/bans-backup.db').write_bytes(b'backup fixture')
            self.assertEqual(primary_database(root), primary)
    def test_database_backup_cannot_replace_missing_primary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'bans/backups').mkdir(parents=True)
            (root / 'bans/backups/bans-backup.db').write_bytes(b'backup fixture')
            with self.assertRaises(RuntimeError): primary_database(root)
    def test_database_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'bans').mkdir()
            other = root / 'other.db'; other.write_bytes(b'fixture')
            (root / 'bans/bans.db').symlink_to(other)
            with self.assertRaises(RuntimeError): primary_database(root)

if __name__ == '__main__': unittest.main()
