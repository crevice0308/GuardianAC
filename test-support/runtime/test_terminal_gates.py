"""Offline-only terminal/result coverage: no runner import, JVM, process or socket.

Runner finalizers are extracted via AST and executed with fake owned servers,
fake clients and temporary JSON/SQLite files. The runnable scenario bodies are
never compiled or executed. Historical assertion names define coverage only;
they are not promoted into evidence that the current fixture bytes passed.
"""
import ast
from collections import Counter
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import terminal_gates as gates

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'client'))
from suite_gates import FIRST_PHASE_ASSERTIONS, COMPLETE_SUITE_ASSERTIONS, first_phase_allows_restart, complete_suite_passed


def lifecycle(**changes):
    return dict({'state': 'normal_stop', 'normal_stop': True, 'termination_observed': True,
                 'terminal': True, 'reason': 'requested_stop', 'child_returncode': 0,
                 'reported_returncode': 0}, **changes)


def good_report(exits=('server_exit',), names=('completed_scenario',)):
    result = {'run_completed': True, 'assertions': [{'test': name, 'passed': True} for name in names]}
    for key in exits:
        result[key] = 0
        result[key + '_lifecycle'] = lifecycle()
    return result


def matrix_report():
    result = good_report(('first_stop', 'stop'))
    result['first_results'] = [{'test': name, 'passed': True} for name in gates.MATRIX_FIRST_ASSERTIONS]
    result['restart_results'] = [{'test': name, 'passed': True} for name in gates.MATRIX_RESTART_ASSERTIONS]
    result['permanent_ip'] = {key: True for key in (
        'synthetic_real_inventory_positive_reachable', 'default_real_offline_client_refused',
        'protected_address_not_plaintext_in_sqlite')}
    return result


def expected_names(relative):
    tree = ast.parse((ROOT / relative).read_text())
    node = next(node for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == 'EXPECTED_ASSERTIONS' for target in node.targets))
    return ast.literal_eval(node.value.args[0])


def terminal_body(relative, function=None):
    tree = ast.parse((ROOT / relative).read_text(), filename=relative)
    body = tree.body if function is None else next(node.body for node in tree.body
                                                  if isinstance(node, ast.FunctionDef) and node.name == function)
    finalizers = [node.finalbody for node in body if isinstance(node, ast.Try) and node.finalbody]
    if len(finalizers) != 1:
        raise AssertionError('Expected one scenario-level finalizer in ' + relative)
    return compile(ast.Module(body=finalizers[0], type_ignores=[]), relative, 'exec')


class PureTerminalGateTests(unittest.TestCase):
    def test_actual_integer_zero_only(self):
        self.assertTrue(gates.normal_exit(0))
        for value in (None, False, True, '0', '', 0.0, [], {}, 1, -15, 124, 125, 126):
            with self.subTest(value=value):
                self.assertFalse(gates.normal_exit(value))

    def test_normal_snapshot_requires_every_explicit_fact(self):
        self.assertTrue(gates.normal_lifecycle(lifecycle()))
        for key in lifecycle():
            missing = lifecycle(); del missing[key]
            self.assertFalse(gates.normal_lifecycle(missing), key)
            for value in (None, False, 0, 1, '', 'true', '0'):
                bad = lifecycle(**{key: value})
                if type(value) is int and value == 0 and key.endswith('returncode'):
                    continue
                with self.subTest(key=key, value=value):
                    self.assertFalse(gates.normal_lifecycle(bad))
        for state in ('forced_termination', 'unknown', 'running', 'normal_exit'):
            self.assertFalse(gates.normal_lifecycle(lifecycle(state=state)))
        self.assertFalse(gates.normal_lifecycle(lifecycle(reason='owner_disconnected')))

    def test_complete_report_and_nonempty_true_assertions_required(self):
        self.assertTrue(gates.report_passed(good_report()))
        for changes in ({'assertions': []}, {'assertions': None}, {'assertions': [None]},
                        {'assertions': [{'passed': True}]}, {'assertions': [{'test': '', 'passed': True}]},
                        {'assertions': [{'test': 'one', 'passed': 1}]}, {'run_completed': False},
                        {'run_completed': 1}, {'run_completed': None}):
            self.assertFalse(gates.report_passed(good_report() | changes))
        for key in ('server_exit', 'server_exit_lifecycle', 'run_completed', 'assertions'):
            report = good_report(); del report[key]
            self.assertFalse(gates.report_passed(report))
        for report in (None, [], {}, {'error': None}):
            self.assertFalse(gates.report_passed(report))

    def test_errors_fail_even_if_their_values_are_empty(self):
        for key in ('error', 'traceback', 'shutdown_error', 'final_cleanup_error',
                    'sqlite_error', 'client_close_error', 'report_snapshot_error', 'termination'):
            for value in (None, False, '', [], 'failure'):
                with self.subTest(key=key, value=value):
                    self.assertFalse(gates.report_passed(good_report() | {key: value}))
        self.assertFalse(gates.report_passed(good_report() | {'client_errors': {'actor': 'failure'}}))
        self.assertTrue(gates.report_passed(good_report() | {'client_errors': {}}))

    def test_all_stops_require_independent_status_and_lifecycle(self):
        keys = ('initial', 'restart')
        self.assertTrue(gates.report_passed(good_report(keys), keys))
        for key in keys:
            for value in (None, False, '0', 0.0, 1, 124, 125):
                self.assertFalse(gates.report_passed(good_report(keys) | {key: value}, keys))
            self.assertFalse(gates.report_passed(good_report(keys) | {key + '_lifecycle': lifecycle(normal_stop=False)}, keys))
        self.assertFalse(gates.report_passed(good_report(), ()))

    def test_duplicates_unknown_missing_and_extra_names_fail(self):
        names = ('a', 'b')
        good = good_report(names=names)
        self.assertTrue(gates.report_passed(good, expected_tests=names))
        for rows in ([{'test': 'a', 'passed': True}] * 2,
                     [{'test': 'a', 'passed': True}],
                     good['assertions'] + [{'test': 'extra', 'passed': True}],
                     [{'test': 'a', 'passed': True}, {'test': 'wrong', 'passed': True}]):
            self.assertFalse(gates.report_passed(good | {'assertions': rows}, expected_tests=names))
        self.assertFalse(gates.assertions_passed([{'test': 'a', 'passed': True}] * 2))

    def test_predicates_do_not_mutate_failure_evidence(self):
        result = good_report() | {'shutdown_error': 'test'}
        before = copy.deepcopy(result)
        self.assertFalse(gates.report_passed(result))
        self.assertEqual(before, result)

    def test_matrix_exact_307_plus_21_with_documented_repeated_clicks(self):
        self.assertEqual(307, len(gates.MATRIX_FIRST_ASSERTIONS))
        self.assertEqual(21, len(gates.MATRIX_RESTART_ASSERTIONS))
        all_names = gates.MATRIX_FIRST_ASSERTIONS + gates.MATRIX_RESTART_ASSERTIONS
        self.assertEqual(328, len(all_names))
        self.assertEqual(321, len(set(all_names)))
        self.assertEqual({'gui_ip_click_37_cancelled': 5, 'gui_ip_click_46_cancelled': 4},
                         {name: count for name, count in Counter(all_names).items() if count > 1})
        self.assertTrue(gates.matrix_passed(matrix_report()))
        for phase in ('first_results', 'restart_results'):
            for action in ('empty', 'missing', 'duplicate', 'wrong_name', 'not_bool'):
                report = matrix_report()
                if action == 'empty': report[phase] = []
                elif action == 'missing': report[phase].pop()
                elif action == 'duplicate': report[phase][-1] = copy.deepcopy(report[phase][0])
                elif action == 'wrong_name': report[phase][-1]['test'] = 'unknown'
                else: report[phase][-1]['passed'] = 1
                self.assertFalse(gates.matrix_passed(report), (phase, action))
        for key in ('first_stop', 'stop'):
            report = matrix_report(); report[key] = False
            self.assertFalse(gates.matrix_passed(report))
        report = matrix_report(); report['permanent_ip']['protected_address_not_plaintext_in_sqlite'] = 1
        self.assertFalse(gates.matrix_passed(report))

    def test_matrix_identity_schema_is_bound_to_unchanged_java_producers(self):
        directory = ROOT / 'runtime/fixture/src/test/guardian'
        for name, expected in gates.MATRIX_PRODUCER_SHA256.items():
            self.assertEqual(expected, hashlib.sha256((directory / name).read_bytes()).hexdigest(), name)

    def test_ip_case_requires_all_78_names_both_stops_and_first_47_for_restart(self):
        self.assertEqual(78, len(gates.IP_CASE_ASSERTIONS))
        self.assertEqual(78, len(set(gates.IP_CASE_ASSERTIONS)))
        self.assertEqual(47, len(gates.IP_FIRST_ASSERTIONS))
        report = good_report(('initial_server_exit', 'restart_server_exit'), gates.IP_CASE_ASSERTIONS)
        self.assertTrue(gates.ip_case_passed(report))
        first = good_report(('initial_server_exit',), gates.IP_FIRST_ASSERTIONS)
        self.assertTrue(gates.ip_first_phase_allows_restart(first))
        for index in range(78):
            truncated = copy.deepcopy(report); truncated['assertions'].pop(index)
            self.assertFalse(gates.ip_case_passed(truncated))
        for key in ('initial_server_exit', 'restart_server_exit'):
            self.assertFalse(gates.ip_case_passed(report | {key: False}))
        first['assertions'].pop()
        self.assertFalse(gates.ip_first_phase_allows_restart(first))
        self.assertFalse(gates.ip_case_passed(report | {'cleanup_server_exit': None}))

    def test_ip_identity_schema_matches_current_constant_and_template_calls(self):
        tree = ast.parse((ROOT / 'ip-workflows/run_ip_workflows.py').read_text())
        run_case = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'run_case')
        direct = []
        durable = next(node for node in run_case.body if isinstance(node, ast.FunctionDef) and node.name == 'verify_durable')
        durable_prefixes = []
        for node in ast.walk(durable):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'check':
                durable_prefixes.append(ast.literal_eval(node.args[0].left))
        for statement in run_case.body:
            if isinstance(statement, ast.FunctionDef):
                continue
            for node in ast.walk(statement):
                if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
                    continue
                if node.func.id == 'check' and isinstance(node.args[0], ast.Constant):
                    direct.append(node.args[0].value)
                elif node.func.id == 'verify_durable':
                    suffix = ast.literal_eval(node.args[1])
                    direct.extend(prefix + suffix for prefix in durable_prefixes)
                elif node.func.id == 'admission':
                    allowed, label = ast.literal_eval(node.args[2]), ast.literal_eval(node.args[3])
                    suffixes = ('_admitted', '_exact_source_and_protocol', '_zero_findings') if allowed else ('_denied_before_play', '_same_case_not_generic_failure')
                    direct.extend(label + suffix for suffix in suffixes)
        self.assertEqual(Counter(gates.IP_CASE_ASSERTIONS), Counter(direct))

    def test_boundaries_exact_order_three_stops_and_seed_integrity(self):
        names = ['one', 'two']
        report = {'run_completed': True, 'tests': [{'test': name, 'passed': True} for name in names],
                  'seed_database_unchanged': True, 'restore_exit': 0,
                  'stops': [{'phase': phase, 'exit_code': 0, 'exit_code_lifecycle': lifecycle()}
                            for phase in ('native-enabled', 'corrupt-database', 'recovered-database')]}
        self.assertTrue(gates.boundaries_passed(report, names))
        for index in range(3):
            bad = copy.deepcopy(report); bad['stops'][index]['exit_code'] = False
            self.assertFalse(gates.boundaries_passed(bad, names))
            bad = copy.deepcopy(report); bad['stops'][index].pop('exit_code_lifecycle')
            self.assertFalse(gates.boundaries_passed(bad, names))
        for changes in ({'tests': []}, {'stops': []}, {'restore_exit': False}, {'seed_database_unchanged': 1},
                        {'tests': list(reversed(report['tests']))}, {'error': None}):
            self.assertFalse(gates.boundaries_passed(report | changes, names))

    def test_suite_pure_counts_are_retained_and_lifecycle_is_additional(self):
        report = good_report(('server_exit', 'restart_exit'), COMPLETE_SUITE_ASSERTIONS)
        report['ban_records'] = [{'player_name': 'GacRealBad', 'evidence_id': 'unit-only'}]
        self.assertTrue(complete_suite_passed(report))
        self.assertTrue(gates.stops_passed(report, ('server_exit', 'restart_exit')))
        del report['restart_exit_lifecycle']
        self.assertTrue(complete_suite_passed(report))  # Existing pure contract is preserved.
        self.assertFalse(gates.stops_passed(report, ('server_exit', 'restart_exit')))


class ReadOnlyReportGateTests(unittest.TestCase):
    def test_historical_modifier_report_cannot_acquire_current_terminal_pass(self):
        path = ROOT / 'modifier/summarize_modifiers.py'
        spec = importlib.util.spec_from_file_location('modifier_summary_under_test', path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        keys = ('enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes',
                'hiddenPlayers','hiddenPositions','xrayViewers')
        report = good_report() | {'soak': {'elapsed_seconds': 1800, 'all_quit': dict.fromkeys(keys, 0)},
                                  'sqlite': {'integrity': [['ok']]}}
        self.assertTrue(module.summarize(report)['full_30_minute_soak_passed'])
        for changes in ({'server_exit': False}, {'server_exit_lifecycle': None}, {'run_completed': None},
                        {'shutdown_error': ''}, {'final_cleanup_error': 'unknown'}):
            self.assertFalse(module.summarize(report | changes)['full_30_minute_soak_passed'])
        del report['server_exit_lifecycle']
        self.assertFalse(module.summarize(report)['full_30_minute_soak_passed'])

    def test_persisted_evidence_predicate_false_cannot_exit_zero(self):
        relative = 'client/verify_persisted_evidence.py'
        tree = ast.parse((ROOT / relative).read_text())
        predicate = next(node for node in tree.body if isinstance(node, ast.Assign)
                         and any(isinstance(target, ast.Name) and target.id == 'passed' for target in node.targets))
        # Only compile its pure in-memory predicate; no module import, SQLite or path reads.
        code = compile(ast.Module(body=[predicate], type_ignores=[]), relative, 'exec')
        good = {'player_name': 'GacRealBad', 'type': 'UUID', 'hash_matches': True, 'independent_families': 2,
                'raw_slot15_present': True, 'raw_pitch100_present': True}
        cases = [([good], True), ([], False), ([good, good], False)]
        cases += [([good | {key: value}], False) for key, value in (
            ('player_name', 'WrongActor'), ('type', 'IP'), ('hash_matches', False),
            ('independent_families', 1), ('raw_slot15_present', False), ('raw_pitch100_present', False))]
        last = compile(ast.Module(body=[tree.body[-1]], type_ignores=[]), relative, 'exec')
        for rows, expected in cases:
            ns = {'out': rows}; exec(code, ns)
            self.assertIs(ns['passed'], expected)
            with self.assertRaises(SystemExit) as exit_result:
                exec(last, ns)
            self.assertEqual(0 if expected else 1, exit_result.exception.code)
        for value in (None, False, 1, 'true'):
            with self.assertRaises(SystemExit) as exit_result:
                exec(last, {'passed': value})
            self.assertEqual(1, exit_result.exception.code)

    def test_persisted_evidence_final_report_is_written_before_nonzero_exit(self):
        relative = 'client/verify_persisted_evidence.py'
        tree = ast.parse((ROOT / relative).read_text())
        # The last two top-level statements are only print + exit; select the
        # preceding write_report call explicitly, never execute any DB reads.
        statements = [node for node in tree.body if isinstance(node, ast.Expr)
                      and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
                      and node.value.func.id == 'write_report'] + [tree.body[-1]]
        self.assertEqual(2, len(statements))
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'unit'; output.mkdir()
            ns = {'write_report': gates.write_report, 'Path': Path,
                  'os': SimpleNamespace(environ={'GUARDIAN_CLIENT_OUTPUT': temporary}),
                  'run': 'unit', 'variant': 'paper-26.3', 'result': {'passed': False}, 'passed': False}
            with self.assertRaises(SystemExit) as exit_result:
                exec(compile(ast.Module(body=statements, type_ignores=[]), relative, 'exec'), ns)
            self.assertEqual(1, exit_result.exception.code)
            self.assertEqual({'passed': False}, json.loads((output / 'paper-26.3-persisted-evidence.json').read_text()))


class AtomicReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name); self.path = self.directory / 'report.json'
        self.before = b'{"previous": "complete"}\n'; self.path.write_bytes(self.before)

    def assert_predecessor_intact(self):
        self.assertEqual(self.before, self.path.read_bytes())
        self.assertEqual(['report.json'], [p.name for p in self.directory.iterdir()])

    def test_successful_durable_replacement_and_no_temp_file(self):
        gates.write_report(self.path, {'current': 'complete', 'unicode': '確認'}, ensure_ascii=False)
        self.assertEqual({'current': 'complete', 'unicode': '確認'}, json.loads(self.path.read_text()))
        self.assertIn('確認', self.path.read_text())
        self.assertEqual(['report.json'], [p.name for p in self.directory.iterdir()])

    def test_serialization_failure_preserves_predecessor(self):
        with self.assertRaises(TypeError):
            gates.write_report(self.path, {'unserializable': object()})
        self.assert_predecessor_intact()

    def test_partial_write_failure_preserves_predecessor(self):
        def fail_after_write(report, output, **options):
            output.write('{"partial":')
            raise OSError('injected write failure')
        with patch.object(gates.json, 'dump', side_effect=fail_after_write), self.assertRaises(OSError):
            gates.write_report(self.path, {'new': True})
        self.assert_predecessor_intact()

    def test_file_sync_failure_preserves_predecessor(self):
        with patch.object(gates.os, 'fsync', side_effect=OSError('injected fsync failure')), self.assertRaises(OSError):
            gates.write_report(self.path, {'new': True})
        self.assert_predecessor_intact()

    def test_replace_failure_preserves_predecessor(self):
        with patch.object(gates.os, 'replace', side_effect=OSError('injected replace failure')), self.assertRaises(OSError):
            gates.write_report(self.path, {'new': True})
        self.assert_predecessor_intact()

    def test_file_sync_precedes_replace_and_directory_sync_follows(self):
        operations = []
        actual_replace = gates.os.replace
        def replace(source, target):
            self.assertNotEqual(str(source), str(target))
            self.assertEqual(self.path.parent, Path(source).parent)
            operations.append('replace')
            actual_replace(source, target)
        with patch.object(gates.os, 'fsync', side_effect=lambda fd: operations.append('fsync')), \
             patch.object(gates.os, 'replace', side_effect=replace):
            gates.write_report(self.path, {'new': True})
        self.assertEqual(['fsync', 'replace', 'fsync'], operations)

    def test_directory_sync_failure_is_reported_after_complete_replacement(self):
        with patch.object(gates.os, 'fsync', side_effect=[None, OSError('directory fsync failed')]), self.assertRaises(OSError):
            gates.write_report(self.path, {'new': True})
        self.assertEqual({'new': True}, json.loads(self.path.read_text()))
        self.assertEqual(['report.json'], [p.name for p in self.directory.iterdir()])


class CleanupHelperTests(unittest.TestCase):
    def test_errors_do_not_skip_other_client_closes_or_stop(self):
        first = SimpleNamespace(report=Mock(side_effect=RuntimeError('report failed')),
                                close=Mock(side_effect=RuntimeError('close failed')))
        second = SimpleNamespace(report=Mock(return_value={'name': 'second'}), close=Mock())
        server = SimpleNamespace(stop=Mock(return_value=0), shutdown_result=lifecycle())
        report = good_report()
        gates.close_clients(report, [first, second], reports_key='clients')
        gates.stop_server(report, server)
        first.close.assert_called_once(); second.close.assert_called_once(); server.stop.assert_called_once()
        self.assertEqual([{'name': 'second'}], report['clients'])
        self.assertFalse(gates.report_passed(report))

    def test_stop_errors_and_unknown_status_do_not_throw_away_report(self):
        for value in (None, False, '0', 0.0, 124, 125):
            report = good_report()
            server = SimpleNamespace(stop=Mock(return_value=value), shutdown_result=lifecycle())
            self.assertEqual(value, gates.stop_server(report, server))
            self.assertIn('shutdown_error', report)
            self.assertFalse(gates.report_passed(report))
        for failure in (RuntimeError('stop failed'), KeyboardInterrupt()):
            server = SimpleNamespace(stop=Mock(side_effect=failure), shutdown_result=lifecycle())
            report = good_report(); gates.stop_server(report, server)
            self.assertIsNone(report['server_exit'])
            self.assertIn('shutdown_error', report)
        report = good_report(); gates.stop_server(report, None)
        self.assertFalse(gates.report_passed(report))

    def test_missing_lifecycle_or_contradiction_overrides_effective_zero(self):
        for server in (SimpleNamespace(stop=Mock(return_value=0)),
                       SimpleNamespace(stop=Mock(return_value=0), shutdown_result=lifecycle(normal_stop=False))):
            report = good_report(); gates.stop_server(report, server)
            self.assertEqual(0, report['server_exit'])
            self.assertIn('shutdown_error', report)
            self.assertFalse(gates.report_passed(report))

    def test_existing_error_and_raw_status_are_preserved(self):
        report = good_report() | {'error': 'original error', 'shutdown_error': 'earlier stop error'}
        snapshot = lifecycle(state='forced_termination', normal_stop=False, child_returncode=0, reported_returncode=124)
        server = SimpleNamespace(stop=Mock(return_value=124), shutdown_result=snapshot)
        gates.stop_server(report, server)
        self.assertEqual('original error', report['error'])
        self.assertIn('earlier stop error', report['shutdown_error'])
        self.assertEqual(snapshot, report['server_exit_lifecycle'])
        self.assertEqual(124, report['server_exit'])


class ExtractedFinalizerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.out = self.root / 'out'; self.out.mkdir()
        self.directory = self.root / 'server'; (self.directory / 'plugins/GuardianAC').mkdir(parents=True)
        self.client = SimpleNamespace(report=Mock(return_value={'name': 'fixture'}), close=Mock())
        self.server = SimpleNamespace(stop=Mock(return_value=0), shutdown_result=lifecycle(),
                                      p=SimpleNamespace(poll=lambda: None))

    def namespace(self, report):
        ns = vars(gates).copy()
        ns.update({'r': report, 'result': report, 'report': report, 'clients': [self.client],
                   'c': self.client, 'a': self.client, 'b': None, 'client': self.client,
                   's': self.server, 'server': self.server, 'out': self.out, 'd': self.directory,
                   'json': json, 'sqlite3': sqlite3, 'time': SimpleNamespace(sleep=lambda seconds: None),
                   'variant': 'paper-26.3', 'print': lambda *a, **kw: None})
        ns['check'] = lambda name, passed, detail=None: report['assertions'].append({'test': name, 'passed': passed})
        ns['save'] = lambda: (self.out / 'report.json').write_text(json.dumps(report))
        return ns

    def test_cave_load_and_overlap_write_failed_reports_for_every_bad_exit(self):
        for filename, output in (('run_cave_wire.py', 'paper-26.3-cave.json'),
                                 ('run_load_smoke.py', 'paper-26.3-load.json'),
                                 ('run_configuration_overlap.py', 'paper-26.3-configuration-overlap.json')):
            relative = 'client/' + filename
            names = expected_names(relative)
            for value in (0, None, False, '0', 0.0, 124, 125):
                with self.subTest(runner=filename, value=value):
                    report = good_report(('exit',), names)
                    self.server.stop = Mock(return_value=value)
                    ns = self.namespace(report); ns['EXPECTED_ASSERTIONS'] = frozenset(names)
                    exec(terminal_body(relative), ns)
                    saved = json.loads((self.out / output).read_text())
                    self.assertIs(saved['passed'], type(value) is int and value == 0)
                    self.server.stop.assert_called_once()

    def test_cave_load_overlap_preserve_report_even_when_client_report_and_close_fail(self):
        self.client.report.side_effect = RuntimeError('client report failed')
        self.client.close.side_effect = RuntimeError('client close failed')
        for filename in ('run_cave_wire.py', 'run_load_smoke.py', 'run_configuration_overlap.py'):
            relative = 'client/' + filename; names = expected_names(relative)
            report = good_report(('exit',), names); ns = self.namespace(report); ns['EXPECTED_ASSERTIONS'] = frozenset(names)
            exec(terminal_body(relative), ns)
            self.assertFalse(report['passed'])
            self.assertIn('client_report_error', report); self.assertIn('client_close_error', report)
            self.assertEqual(0, report['exit'])

    def test_modifier_cleanup_and_sqlite_failures_still_save_nonpassing_report(self):
        report = good_report(); ns = self.namespace(report)
        ns['report'] = Mock(side_effect=RuntimeError('final cleanup failed'))
        exec(terminal_body('modifier/run_modifiers.py'), ns)
        saved = json.loads((self.out / 'report.json').read_text())
        self.assertFalse(saved['passed']); self.assertIn('final_cleanup_error', saved); self.assertIn('sqlite_error', saved)
        self.server.stop.assert_called_once()

    def test_modifier_normal_finalizer_and_snapshot_failure_fallback(self):
        db = sqlite3.connect(self.directory / 'plugins/GuardianAC/bans.db')
        db.executescript('CREATE TABLE bans(id); CREATE TABLE evidence(id);'); db.close()
        fields = ('enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes',
                  'hiddenPlayers','hiddenPositions','xrayViewers','contextCaptureFailures')
        for failed_snapshot in (False, True):
            report = good_report(); ns = self.namespace(report); ns['report'] = lambda name: dict.fromkeys(fields, 0)
            if failed_snapshot: ns['save'] = Mock(side_effect=RuntimeError('snapshot failed'))
            exec(terminal_body('modifier/run_modifiers.py'), ns)
            saved = json.loads((self.out / 'report.json').read_text())
            self.assertIs(saved['passed'], not failed_snapshot)

    def test_matrix_finalizer_saves_strict_terminal_result(self):
        for value in (0, False, None, 124):
            report = matrix_report(); self.server.stop = Mock(return_value=value); ns = self.namespace(report)
            exec(terminal_body('runtime/run_matrix.py'), ns)
            saved = json.loads((self.root / 'paper-26.3-integration-report.json').read_text())
            self.assertIs(saved['passed'], type(value) is int and value == 0)

    def test_load_limits_finalizer_preserves_source_evidence_and_shutdown_failure(self):
        self.client.fixture_source_ip = '127.0.0.2'
        for failed_stop in (False, True):
            report = good_report() | {'measurements': [{'contextCaptureFailures': 0}]}
            ns = self.namespace(report)
            ns.update({'client_errors': {}, 'max_rss_kib': 5, 'stamp': lambda: 'unit-only',
                       'sql': lambda: {'integrity': ['ok'], 'bans': 0, 'evidence': 0},
                       'successful': gates.report_passed})
            self.server.stop.side_effect = RuntimeError('stop failed') if failed_stop else None
            exec(terminal_body('load-limits/run_load_limits.py', 'main'), ns)
            saved = json.loads((self.out / 'report.json').read_text())
            self.assertIs(saved['passed'], not failed_stop)
            self.assertEqual('127.0.0.2', saved['clients_at_shutdown'][0]['actual_source_ip'])

    def test_suite_shutdown_error_still_writes_and_never_restarts(self):
        report = good_report(names=FIRST_PHASE_ASSERTIONS)
        self.server.stop.side_effect = RuntimeError('stop failed')
        ns = self.namespace(report)
        ns.update({'first_phase_allows_restart': first_phase_allows_restart, 'complete_suite_passed': complete_suite_passed,
                   'Server': Mock(side_effect=AssertionError('No restart allowed')), 'restart_clients': []})
        exec(terminal_body('client/run_suite.py'), ns)
        saved = json.loads((self.out / 'paper-26.3-suite.json').read_text())
        self.assertFalse(saved['passed']); self.assertIn('shutdown_error', saved); self.assertIn('restart_skipped', saved)
        ns['Server'].assert_not_called()

    def test_ip_finalizer_saves_report_after_cleanup_exception(self):
        for failed_stop in (False, True):
            report = good_report(('initial_server_exit', 'restart_server_exit'), gates.IP_CASE_ASSERTIONS)
            report.update({'case': 'unit-only', 'plugin_sha256': 'unit-only', 'started_at': 'unit-only'})
            ns = self.namespace(report)
            ns.update({'run': 'fresh', 'requested_type': 'IP', 'utc': lambda: 'unit-only', 'Path': Path, 'os': gates.os})
            self.server.stop.side_effect = RuntimeError('cleanup stop failed') if failed_stop else None
            with patch.dict(gates.os.environ, {'GUARDIAN_IP_OUTPUT': str(self.out)}):
                exec(terminal_body('ip-workflows/run_ip_workflows.py', 'run_case'), ns)
            saved = json.loads((self.out / 'fresh/offline-ip.json').read_text())
            self.assertIs(saved['passed'], not failed_stop)

    def test_boundary_finalizer_preserves_seed_verification_error(self):
        names = ['one', 'two']
        for fail_seed_read in (False, True):
            report = {'run_completed': True, 'tests': [{'test': name, 'passed': True} for name in names],
                      'restore_exit': 0, 'stops': [{'phase': phase, 'exit_code': 0, 'exit_code_lifecycle': lifecycle()}
                          for phase in ('native-enabled', 'corrupt-database', 'recovered-database')]}
            ns = self.namespace(report)
            ns.update({'s': None, 'EXPECTED_TESTS': names, 'root': self.out, 'seed_store': self.directory,
                       'seed_hash': 'unit-only', 'sha': Mock(return_value='unit-only',
                           side_effect=OSError('seed read failed') if fail_seed_read else None)})
            exec(terminal_body('runtime/test_boundaries.py'), ns)
            saved = json.loads((self.out / 'report.json').read_text())
            self.assertIs(saved['passed'], not fail_seed_read)

    def test_modifier_and_suite_never_inspect_sqlite_after_bad_stop(self):
        fields = ('enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes',
                  'hiddenPlayers','hiddenPositions','xrayViewers','contextCaptureFailures')
        for relative in ('modifier/run_modifiers.py', 'client/run_suite.py'):
            for value in (None, False, '0', 124, 125):
                with self.subTest(runner=relative, exit=value):
                    report = good_report()
                    ns = self.namespace(report)
                    ns['report'] = lambda name: dict.fromkeys(fields, 0)
                    ns['sqlite3'] = SimpleNamespace(connect=Mock(side_effect=AssertionError('No database read allowed')))
                    ns.update({'first_phase_allows_restart': first_phase_allows_restart,
                               'complete_suite_passed': complete_suite_passed,
                               'Server': Mock(side_effect=AssertionError('No restart allowed'))})
                    self.server.stop = Mock(return_value=value)
                    with patch.object(Path, 'rglob', side_effect=AssertionError('Do not even search for stopped databases')) as search:
                        exec(terminal_body(relative), ns)
                    search.assert_not_called(); ns['sqlite3'].connect.assert_not_called()
                    self.assertIn('database was not read', report['sqlite_error'])
                    self.assertFalse(report['passed'])

    def test_load_limits_never_calls_final_sql_after_bad_stop(self):
        self.client.fixture_source_ip = '127.0.0.2'
        for value in (None, False, '0', 124, 125):
            report = good_report() | {'measurements': [{'contextCaptureFailures': 0}]}
            ns = self.namespace(report)
            ns.update({'client_errors': {}, 'max_rss_kib': 0, 'stamp': lambda: 'unit-only',
                       'sql': Mock(side_effect=AssertionError('No final database read allowed')),
                       'successful': gates.report_passed})
            self.server.stop = Mock(return_value=value)
            exec(terminal_body('load-limits/run_load_limits.py', 'main'), ns)
            ns['sql'].assert_not_called()
            self.assertIn('database was not read', report['sqlite_error'])
            self.assertFalse(report['passed'])

    def test_ip_initial_and_final_storage_calls_are_unreachable_after_bad_stop(self):
        relative = 'ip-workflows/run_ip_workflows.py'
        tree = ast.parse((ROOT / relative).read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'run_case')
        scenario = next(node for node in function.body if isinstance(node, ast.Try) and node.finalbody)
        for exit_key, target in (('initial_server_exit', 'first_storage'), ('restart_server_exit', 'final_storage')):
            begin = next(i for i, node in enumerate(scenario.body) if isinstance(node, ast.Expr)
                         and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
                         and node.value.func.id == 'stop_server' and node.value.args[-1].value == exit_key)
            end = next(i for i, node in enumerate(scenario.body) if isinstance(node, ast.Assign)
                       and any(isinstance(name, ast.Name) and name.id == target for name in node.targets))
            code = compile(ast.Module(body=scenario.body[begin:end + 1], type_ignores=[]), relative, 'exec')
            for value in (None, False, '0', 124, 125):
                report = good_report(('initial_server_exit', 'restart_server_exit'), gates.IP_CASE_ASSERTIONS)
                ns = self.namespace(report)
                ns['directory'] = self.directory
                ns['inspect_storage'] = Mock(side_effect=AssertionError('No stopped database read allowed'))
                self.server.stop = Mock(return_value=value)
                with self.assertRaises(RuntimeError):
                    exec(code, ns)
                ns['inspect_storage'].assert_not_called()
                self.assertIn('shutdown_error', report)

    def test_boundary_final_seed_read_is_skipped_after_unknown_or_forced_stop(self):
        for value in (None, False, '0', 124, 125):
            report = {'run_completed': False, 'tests': [{'test': 'one', 'passed': True}],
                      'restore_exit': 0, 'stops': [{'phase': 'native-enabled', 'exit_code': value,
                                                  'exit_code_lifecycle': lifecycle()}]}
            ns = self.namespace(report)
            ns.update({'s': None, 'EXPECTED_TESTS': ['one'], 'root': self.out, 'seed_store': self.directory,
                       'seed_hash': 'unit-only', 'sha': Mock(side_effect=AssertionError('No final seed database read allowed'))})
            exec(terminal_body('runtime/test_boundaries.py'), ns)
            ns['sha'].assert_not_called()
            self.assertIn('database was not read', report['seed_verification_error'])
            self.assertFalse(report['passed'])

    def test_each_runner_exit_is_bound_to_its_report_gate(self):
        for relative, gate in (
                ('modifier/run_modifiers.py', 'report_passed'), ('client/run_cave_wire.py', 'report_passed'),
                ('client/run_load_smoke.py', 'report_passed'), ('client/run_configuration_overlap.py', 'report_passed'),
                ('runtime/run_matrix.py', 'matrix_passed'), ('runtime/test_boundaries.py', 'boundaries_passed'),
                ('ip-workflows/run_ip_workflows.py', 'ip_case_passed'), ('load-limits/run_load_limits.py', 'successful')):
            tree = ast.parse((ROOT / relative).read_text())
            assignments = [node for node in ast.walk(tree) if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Subscript) and isinstance(target.slice, ast.Constant)
                                   and target.slice.value == 'passed' for target in node.targets)]
            self.assertTrue(any(isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
                                and node.value.func.id == gate for node in assignments), relative)
            self.assertTrue(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                                and node.func.id == 'stop_server' for node in ast.walk(tree)), relative)
        suite = (ROOT / 'client/run_suite.py').read_text()
        self.assertIn("first_phase_allows_restart(r) and stops_passed(r,('server_exit',))", suite)
        self.assertIn("complete_suite_passed(r) and stops_passed(r,('server_exit','restart_exit'))", suite)


if __name__ == '__main__':
    unittest.main()
