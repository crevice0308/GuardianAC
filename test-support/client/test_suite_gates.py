"""No server, socket or JVM: regression checks for safe suite sequencing."""
import ast
import copy
from pathlib import Path
import unittest

from suite_gates import (FIRST_PHASE_ASSERTIONS, COMPLETE_SUITE_ASSERTIONS,
                         first_phase_allows_restart, complete_suite_passed)


class SuiteRestartGateTests(unittest.TestCase):
    def good(self):
        return {
            'server_exit': 0,
            'assertions': [{'test': name, 'passed': True}
                           for name in sorted(FIRST_PHASE_ASSERTIONS)],
            'ban_records': [{'player_name': 'GacRealBad', 'evidence_id': 'synthetic-evidence'}],
        }

    def test_complete_successful_first_phase_may_restart(self):
        self.assertEqual(17, len(FIRST_PHASE_ASSERTIONS))
        self.assertTrue(first_phase_allows_restart(self.good()))

    def test_any_caught_or_shutdown_error_blocks_even_with_saved_ban(self):
        for field in ('error', 'traceback', 'shutdown_error'):
            for value in ('synthetic failure', '', None):
                with self.subTest(field=field, value=value):
                    self.assertFalse(first_phase_allows_restart(self.good() | {field: value}))

    def test_only_explicit_normal_exit_is_accepted(self):
        for value in (1, -15, None, False, '0'):
            with self.subTest(value=value):
                self.assertFalse(first_phase_allows_restart(self.good() | {'server_exit': value}))
        result = self.good(); result.pop('server_exit')
        self.assertFalse(first_phase_allows_restart(result))

    def test_each_required_assertion_must_be_present_once_and_true(self):
        for index in range(17):
            for action in ('missing', 'failed', 'duplicate', 'renamed', 'truthy_integer'):
                result = self.good()
                if action == 'missing': result['assertions'].pop(index)
                elif action == 'failed': result['assertions'][index]['passed'] = False
                elif action == 'duplicate': result['assertions'][index] = copy.deepcopy(result['assertions'][(index + 1) % 17])
                elif action == 'renamed': result['assertions'][index]['test'] = 'unreviewed_check'
                else: result['assertions'][index]['passed'] = 1
                with self.subTest(index=index, action=action):
                    self.assertFalse(first_phase_allows_restart(result))

    def test_empty_malformed_or_extra_assertions_block(self):
        for assertions in (None, [], {}, [None] * 17,
                           self.good()['assertions'] + [{'test': 'extra', 'passed': True}]):
            with self.subTest(assertions=assertions):
                self.assertFalse(first_phase_allows_restart(self.good() | {'assertions': assertions}))

    def test_expected_evidence_backed_actor_is_required(self):
        for records in (None, [], [None], {},
                        [{'player_name': 'GacRealBad'}],
                        [{'player_name': 'GacRealBad', 'evidence_id': None}],
                        [{'player_name': 'GacRealClean', 'evidence_id': 'synthetic-evidence'}]):
            with self.subTest(records=records):
                self.assertFalse(first_phase_allows_restart(self.good() | {'ban_records': records}))

    def test_guard_does_not_mutate_failure_evidence(self):
        result = self.good() | {'error': 'synthetic failure'}
        original = copy.deepcopy(result)
        self.assertFalse(first_phase_allows_restart(result))
        self.assertEqual(original, result)

    def test_actual_restart_call_is_nested_under_the_guard(self):
        # Parse only: importing run_suite would execute a real fixture.
        tree = ast.parse(Path(__file__).with_name('run_suite.py').read_text())
        guards = [node for node in ast.walk(tree) if isinstance(node, ast.If)
                  and isinstance(node.test, ast.BoolOp)
                  and any(isinstance(part, ast.Call) and isinstance(part.func, ast.Name)
                          and part.func.id == 'first_phase_allows_restart' for part in node.test.values)]
        self.assertEqual(1, len(guards))
        self.assertEqual('r', ast.unparse(guards[0].test.values[0].args[0]))
        restarts = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                    and isinstance(node.func, ast.Name) and node.func.id == 'Server'
                    and any(isinstance(arg, ast.Constant) and arg.value == 'real-client-restart'
                            for arg in node.args)]
        self.assertEqual(1, len(restarts))
        approved_branch = [node for statement in guards[0].body for node in ast.walk(statement)]
        self.assertIn(restarts[0], approved_branch)

    def complete(self):
        return self.good() | {
            'restart_exit': 0,
            'assertions': [{'test': name, 'passed': True}
                           for name in sorted(COMPLETE_SUITE_ASSERTIONS)],
        }

    def test_complete_successful_suite_is_success(self):
        self.assertEqual(19, len(COMPLETE_SUITE_ASSERTIONS))
        self.assertTrue(complete_suite_passed(self.complete()))
        self.assertFalse(complete_suite_passed(self.good()))

    def test_complete_suite_rejects_missing_failed_duplicate_or_extra_checks(self):
        for index in range(19):
            for action in ('missing', 'failed', 'duplicate'):
                result = self.complete()
                if action == 'missing': result['assertions'].pop(index)
                elif action == 'failed': result['assertions'][index]['passed'] = False
                else: result['assertions'][index] = copy.deepcopy(result['assertions'][(index + 1) % 19])
                with self.subTest(index=index, action=action):
                    self.assertFalse(complete_suite_passed(result))
        result = self.complete(); result['assertions'].append({'test': 'extra', 'passed': True})
        self.assertFalse(complete_suite_passed(result))

    def test_complete_suite_requires_both_explicit_normal_stops(self):
        for field in ('server_exit', 'restart_exit'):
            for value in (None, False, 1, -15, '0'):
                with self.subTest(field=field, value=value):
                    self.assertFalse(complete_suite_passed(self.complete() | {field: value}))
            result = self.complete(); result.pop(field)
            self.assertFalse(complete_suite_passed(result))

    def test_complete_suite_rejects_errors_or_skipped_restart_without_mutation(self):
        for field in ('error', 'traceback', 'shutdown_error', 'restart_skipped'):
            result = self.complete() | {field: 'synthetic failure'}
            original = copy.deepcopy(result)
            with self.subTest(field=field):
                self.assertFalse(complete_suite_passed(result))
                self.assertEqual(original, result)

    def test_actual_exit_uses_complete_gate_after_report_write(self):
        tree = ast.parse(Path(__file__).with_name('run_suite.py').read_text())
        exit_statement = tree.body[-1]
        self.assertIsInstance(exit_statement, ast.Expr)
        call = exit_statement.value
        self.assertEqual('sys.exit', ast.unparse(call.func))
        self.assertEqual("0 if complete_suite_passed(r) and stops_passed(r, ('server_exit', 'restart_exit')) else 1", ast.unparse(call.args[0]))
        report_writes = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                         and isinstance(node.func, ast.Name) and node.func.id == 'write_report'
                         and any(isinstance(value, ast.Constant) and value.value == '-suite.json'
                                 for value in ast.walk(node))]
        self.assertEqual(1, len(report_writes))
        self.assertLess(report_writes[0].lineno, exit_statement.lineno)


if __name__ == '__main__':
    unittest.main()
