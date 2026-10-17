"""No-server tests of observation-only runner helpers extracted through AST.

Never import the runner: its top-level code prepares and starts a test server.
Only the three named helper definitions are compiled into isolated fake state.
"""
import ast
import collections
import json
from pathlib import Path
import tempfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'runtime'))
from terminal_gates import write_report
from types import SimpleNamespace
import unittest

RUNNER = Path(__file__).with_name('run_modifiers.py')


def helper_namespace(root, clients):
    tree = ast.parse(RUNNER.read_text(), filename=str(RUNNER))
    wanted = {'counter_snapshot', 'save', 'report'}
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in wanted]
    if {node.name for node in nodes} != wanted:
        raise AssertionError('Expected observation helpers are missing')
    namespace = {'write_report': write_report, 'clients': clients, 'json': json, 'out': root / 'out', 'd': root / 'server',
                 'r': {'existing': 'preserved'}, 'command': lambda value: None}
    namespace['out'].mkdir()
    (namespace['d'] / 'plugins/ModifierFixture').mkdir(parents=True)
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(RUNNER), 'exec'), namespace)
    return namespace


def actor():
    def forbidden(*args, **kwargs):
        raise AssertionError('Observation code must not send, pump, close, or consume a queue')
    return SimpleNamespace(name='GacModCounter', joined=True, closed=False, disconnect=None,
                           sent=collections.Counter({'game:CLIENT_TICK_END': 8}),
                           packets=collections.Counter({'game:CLIENTBOUND_KEEP_ALIVE': 2}),
                           delayed=[(3.5, b'unchanged payload')], delayed_count=2,
                           send=forbidden, pump=forbidden, close=forbidden)


class CounterSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_empty_snapshot(self):
        ns = helper_namespace(self.root, [])
        self.assertEqual(ns['counter_snapshot'](), [])

    def test_snapshot_copies_counters_without_mutating_actor(self):
        client = actor()
        ns = helper_namespace(self.root, [client])
        result = ns['counter_snapshot']()
        self.assertEqual(result, [{'name': 'GacModCounter', 'joined': True, 'closed': False,
                                  'disconnect': None, 'sent': {'game:CLIENT_TICK_END': 8},
                                  'received': {'game:CLIENTBOUND_KEEP_ALIVE': 2},
                                  'pending_delayed_keepalives': 1, 'delayed_keepalives_total': 2}])
        result[0]['sent']['game:CLIENT_TICK_END'] = 999
        result[0]['received'].clear()
        self.assertEqual(client.sent['game:CLIENT_TICK_END'], 8)
        self.assertEqual(client.packets['game:CLIENTBOUND_KEEP_ALIVE'], 2)
        self.assertEqual(client.delayed, [(3.5, b'unchanged payload')])
        self.assertFalse(client.closed)

    def test_old_snapshot_does_not_change_when_client_counters_advance(self):
        client = actor()
        ns = helper_namespace(self.root, [client])
        before = ns['counter_snapshot']()
        client.sent['game:CLIENT_TICK_END'] += 1
        client.packets['game:CLIENTBOUND_KEEP_ALIVE'] += 1
        self.assertEqual(before[0]['sent']['game:CLIENT_TICK_END'], 8)
        self.assertEqual(before[0]['received']['game:CLIENTBOUND_KEEP_ALIVE'], 2)
        self.assertEqual(ns['counter_snapshot']()[0]['sent']['game:CLIENT_TICK_END'], 9)

    def test_save_preserves_report_and_discloses_counter_scope(self):
        client = actor()
        ns = helper_namespace(self.root, [client])
        ns['save']()
        report = json.loads((ns['out'] / 'report.json').read_text())
        self.assertEqual(report['existing'], 'preserved')
        self.assertEqual(report['client_counters'], ns['counter_snapshot']())
        self.assertIn('Initial handshake/login-start send_id() calls are excluded', report['client_counter_scope'])
        self.assertIn('not a wire capture', report['client_counter_scope'])
        self.assertEqual(client.delayed, [(3.5, b'unchanged payload')])

    def test_phase_report_retains_server_fields_and_adds_only_snapshot(self):
        client = actor()
        ns = helper_namespace(self.root, [client])
        calls = []
        ns['command'] = calls.append
        original = {'label': 'unit-phase', 'dropped': 4, 'contextCaptureFailures': 0,
                    'players': [{'name': 'GacModCounter'}]}
        source = ns['d'] / 'plugins/ModifierFixture/unit-phase.json'
        source.write_text(json.dumps(original))
        result = ns['report']('unit-phase')
        self.assertEqual(calls, ['report unit-phase'])
        self.assertEqual({k: result[k] for k in original}, original)
        self.assertEqual(set(result) - set(original), {'client_counters'})
        self.assertEqual(json.loads(source.read_text()), original)
        self.assertEqual(json.loads((ns['out'] / 'unit-phase.json').read_text()), result)
        self.assertEqual(client.delayed, [(3.5, b'unchanged payload')])


if __name__ == '__main__':
    unittest.main()
