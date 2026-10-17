"""Lightweight offline report/safety regression tests; never starts Java or a server."""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
for key in ('GUARDIAN_PROJECT_ROOT', 'JAVA_HOME', 'GUARDIAN_PAPER_BASELINE', 'GUARDIAN_PURPUR_BASELINE'):
    os.environ.setdefault(key, str(HERE / 'unused-no-server'))
spec = importlib.util.spec_from_file_location('ip_workflows_under_test', HERE / 'run_ip_workflows.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
from terminal_gates import IP_CASE_ASSERTIONS
module.IP_CASE_ASSERTIONS = IP_CASE_ASSERTIONS


class BoundedFixtureTests(unittest.TestCase):
    def test_native_expiry_matches_at_seconds_resolution(self):
        self.assertTrue(module.native_expiry_matches({'expires': '2026-10-14 02:00:00 +0000'}, 1791943200999))
        self.assertFalse(module.native_expiry_matches({'expires': 'forever'}, 1791943200000))
        self.assertFalse(module.native_expiry_matches({'expires': '2026-10-14 02:00:00 +0000'}, 1791943201000))

    def test_main_rejects_wrong_artifact(self):
        with patch.object(module.sys, 'argv', ['run_ip_workflows.py', 'fresh']), patch.dict(os.environ, {'GUARDIAN_PLUGIN_SHA256': '0' * 64}):
            with self.assertRaisesRegex(RuntimeError, 'exact reviewed immutable artifact'):
                module.main()

    def test_main_rejects_historical_unsafe_artifact(self):
        with patch.object(module.sys, 'argv', ['run_ip_workflows.py', 'fresh']), patch.object(module, 'EXPECTED', module.HISTORICAL_UNSAFE), patch.dict(os.environ, {'GUARDIAN_PLUGIN_SHA256': module.HISTORICAL_UNSAFE}):
            with self.assertRaisesRegex(RuntimeError, 'exact reviewed immutable artifact'):
                module.main()

    def test_no_unbounded_requested_ban_type(self):
        with self.assertRaisesRegex(ValueError, 'Only bounded offline IP and DUAL'):
            module.run_case('fresh', 'PERMANENT')

    def test_main_rejects_path_traversal(self):
        with patch.object(module.sys, 'argv', ['run_ip_workflows.py', '../overwrite']):
            with self.assertRaises(ValueError):
                module.main()

    def test_prior_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(module, 'RUNTIME_ROOT', Path(temp)):
            (Path(temp) / 'existing-ip').mkdir()
            with self.assertRaisesRegex(RuntimeError, 'Preserve previous evidence'):
                module.run_case('existing', 'IP')

    def test_failed_or_blocked_first_case_prevents_second_case(self):
        expected = 'f' * 64
        for failure in ({'error': 'PermissionError: server launch blocked'}, {}):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / 'fresh'
                output.mkdir()
                case = {'case': 'offline-ip-uuid-fallback', 'passed': False,
                        'assertions': [{'test': 'first_case_failed', 'passed': False}], **failure}
                with patch.object(module.sys, 'argv', ['run_ip_workflows.py', 'fresh']), patch.object(module, 'EXPECTED', expected), patch.dict(os.environ, {'GUARDIAN_PLUGIN_SHA256': expected, 'GUARDIAN_IP_OUTPUT': temp}), patch.object(module, 'run_case', return_value=case) as run_case, patch('builtins.print'):
                    self.assertEqual(module.main(), 1)
                run_case.assert_called_once_with('fresh', 'IP')
                summary = json.loads((output / 'summary.json').read_text())
                self.assertFalse(summary['passed'])
                self.assertFalse(summary['case_pair_completed'])
                self.assertTrue(summary['second_case_skipped_after_failure'])
                self.assertEqual(len(summary['cases']), 1)

    def test_successful_pair_runs_both_cases(self):
        expected = 'f' * 64
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'fresh'
            output.mkdir()
            cases = [{'case': 'offline-' + kind.lower() + '-uuid-fallback', 'passed': True,
                      'run_completed': True, 'initial_server_exit': 0, 'restart_server_exit': 0,
                      **{key + '_lifecycle': {'state': 'normal_stop', 'normal_stop': True, 'termination_observed': True,
                          'terminal': True, 'reason': 'requested_stop', 'child_returncode': 0, 'reported_returncode': 0}
                         for key in ('initial_server_exit', 'restart_server_exit')},
                      'assertions': [{'test': name, 'passed': True} for name in module.IP_CASE_ASSERTIONS]} for kind in ('IP', 'DUAL')]
            with patch.object(module.sys, 'argv', ['run_ip_workflows.py', 'fresh']), patch.object(module, 'EXPECTED', expected), patch.dict(os.environ, {'GUARDIAN_PLUGIN_SHA256': expected, 'GUARDIAN_IP_OUTPUT': temp}), patch.object(module, 'run_case', side_effect=cases) as run_case, patch('builtins.print'):
                self.assertEqual(module.main(), 0)
            self.assertEqual(run_case.call_args_list, [(('fresh', 'IP'),), (('fresh', 'DUAL'),)])
            summary = json.loads((output / 'summary.json').read_text())
            self.assertTrue(summary['passed'])
            self.assertTrue(summary['case_pair_completed'])
            self.assertFalse(summary['second_case_skipped_after_failure'])
            self.assertEqual(len(summary['cases']), 2)

    def test_readonly_storage_report_integrity_and_redaction(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            database = root / 'plugins/GuardianAC/bans/bans.db'
            database.parent.mkdir(parents=True)
            for filename in ('banned-players.json', 'banned-ips.json'):
                (root / filename).write_text('[]')
            con = sqlite3.connect(database)
            con.executescript('''CREATE TABLE bans(case_id TEXT, sync_token TEXT, ip_encrypted TEXT, ip_lookup TEXT, created_at INTEGER);
CREATE TABLE evidence(id TEXT, payload TEXT, sha256 TEXT, payload_bytes INTEGER, recorded_at INTEGER);
CREATE TABLE ip_observations(player_uuid TEXT, trusted INTEGER, ip_lookup TEXT);''')
            payload = '{"families":2}'
            con.execute('INSERT INTO bans VALUES (?,?,?,?,?)', ('GAC-TEST', 'token', 'secret-ciphertext', 'secret-lookup', 1))
            con.execute('INSERT INTO evidence VALUES (?,?,?,?,?)', ('evidence', payload, hashlib.sha256(payload.encode()).hexdigest(), len(payload), 1))
            con.execute('INSERT INTO ip_observations VALUES (?,?,?)', ('synthetic', 1, 'f' * 64))
            con.commit(); con.close()
            before = hashlib.sha256(database.read_bytes()).hexdigest()
            report = module.inspect_storage(root)
            self.assertEqual(report['integrity'], ['ok'])
            self.assertEqual(report['foreign_key_violations'], [])
            self.assertTrue(report['evidence'][0]['hash_matches'])
            self.assertTrue(report['evidence'][0]['payload_bytes_match'])
            self.assertEqual(report['bans'][0]['ownership_marker'], 'GuardianAC:GAC-TEST:token')
            self.assertTrue(report['bans'][0]['protected_ip_present'])
            self.assertNotIn('secret-ciphertext', json.dumps(report))
            self.assertNotIn('secret-lookup', json.dumps(report))
            self.assertEqual(report['observations'][0]['lookup_length'], 64)
            self.assertEqual(hashlib.sha256(database.read_bytes()).hexdigest(), before)


if __name__ == '__main__':
    unittest.main()
