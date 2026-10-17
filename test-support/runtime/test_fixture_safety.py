"""No server, JVM or real socket: fixture settings and centralized port gates."""
import ast
import hashlib
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from fixture_safety import (FIXTURE_HOST, enforce_properties, fixture_port,
                            fixture_ports, settings, validate_client_port,
                            validate_fixture)
from fixture_privacy import initialize_privacy

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / 'client'))
from protocol_client import Client, text

CUSTOM = {'GUARDIAN_PAPER_PORT': '26841', 'GUARDIAN_PURPUR_PORT': '26842'}
TCP_RUNNERS = (
    'runtime/run_matrix.py', 'client/run_suite.py', 'client/run_cave_wire.py',
    'client/run_configuration_overlap.py', 'client/run_load_smoke.py',
    'modifier/run_modifiers.py', 'ip-workflows/run_ip_workflows.py',
    'load-limits/run_load_limits.py',
)


class SafetyTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.d = Path(self.tmp.name) / 'paper-26.3'
        self.d.mkdir()
        (self.d / 'plugins').mkdir()
        (self.d / 'plugins/GuardianAC.jar').write_bytes(b'fixture')
        self.sha = hashlib.sha256(b'fixture').hexdigest()
        (self.d / 'eula.txt').write_text('eula=true\n')
        (self.d / 'server.properties').write_text('motd=test\n')
        (self.d / 'config').mkdir()
        (self.d / 'config/paper-global.yml').write_text('spark:\n  enabled: true\n')
        initialize_privacy(self.d)

    def enforce(self):
        enforce_properties(self.d / 'server.properties', 'paper-26.3')

    def replace_setting(self, key, value):
        path = self.d / 'server.properties'
        path.write_text('\n'.join(key + '=' + value if line.startswith(key + '=') else line
                                  for line in path.read_text().splitlines()) + '\n')

    def test_missing_keys_added(self):
        self.enforce()
        validate_fixture(self.d, self.sha)

    def test_wrong_values_repaired_only_by_explicit_preparation(self):
        (self.d / 'server.properties').write_text('server-ip=0.0.0.0\nenable-rcon=true\nenable-query=true\n')
        self.enforce()
        validate_fixture(self.d, self.sha)

    def test_each_unsafe_reuse_rejected(self):
        for key, value in [('server-ip', '0.0.0.0'), ('server-port', '25565'),
                           ('online-mode', 'true'), ('enable-rcon', 'true'), ('enable-query', 'true')]:
            self.enforce()
            self.replace_setting(key, value)
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                validate_fixture(self.d, self.sha)

    def test_third_party_rejected(self):
        self.enforce()
        (self.d / 'plugins/OtherPlugin.jar').write_bytes(b'x')
        with self.assertRaises(RuntimeError):
            validate_fixture(self.d, self.sha)

    def test_webhook_rejected(self):
        self.enforce()
        (self.d / 'plugins/discord-webhook.txt').write_text('')
        with self.assertRaises(RuntimeError):
            validate_fixture(self.d, self.sha)

    def test_artifact_mismatch_rejected(self):
        self.enforce()
        with self.assertRaises(RuntimeError):
            validate_fixture(self.d, '0' * 64)

    def test_duplicate_override_rejected(self):
        self.enforce()
        path = self.d / 'server.properties'
        path.write_text(path.read_text() + 'server-ip=0.0.0.0\n')
        with self.assertRaises(RuntimeError):
            validate_fixture(self.d, self.sha)

    def test_known_helpers_allowed(self):
        self.enforce()
        for name in ['GuardianFixture.jar', 'RealClientFixture.jar', 'ModifierFixture.jar']:
            (self.d / 'plugins' / name).write_bytes(b'x')
        validate_fixture(self.d, self.sha)

    def test_custom_pair_used_for_preparation_and_readonly_validation(self):
        with patch.dict(os.environ, CUSTOM):
            self.enforce()
            before = (self.d / 'server.properties').read_bytes()
            validate_fixture(self.d, self.sha)
            self.assertIn(b'server-ip=127.0.0.1\n', before)
            self.assertIn(b'server-port=26841\n', before)
            self.assertEqual(before, (self.d / 'server.properties').read_bytes())

    def test_changed_environment_rejects_prepared_properties_without_repair(self):
        self.enforce()
        path = self.d / 'server.properties'
        before = path.read_bytes()
        with patch.dict(os.environ, CUSTOM), self.assertRaisesRegex(RuntimeError, 'server-port'):
            validate_fixture(self.d, self.sha)
        self.assertEqual(before, path.read_bytes())

    def test_wrong_variant_custom_port_rejected(self):
        with patch.dict(os.environ, CUSTOM):
            self.enforce()
            self.replace_setting('server-port', CUSTOM['GUARDIAN_PURPUR_PORT'])
            with self.assertRaisesRegex(RuntimeError, 'server-port'):
                validate_fixture(self.d, self.sha)

    def test_nonliteral_or_nonloopback_bind_rejected(self):
        for value in ('localhost', '127.1', '2130706433', '::1', '127.0.0.2', '', 'example.com'):
            self.enforce()
            self.replace_setting('server-ip', value)
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, 'server-ip'):
                validate_fixture(self.d, self.sha)

    def test_noncanonical_prepared_port_rejected(self):
        for value in ('026731', '+26731', '26731.0', '26731 # comment', r'\u003226731'):
            self.enforce()
            self.replace_setting('server-port', value)
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, 'server-port'):
                validate_fixture(self.d, self.sha)

    def test_invalid_pair_fails_without_writing_properties(self):
        path = self.d / 'server.properties'
        before = path.read_bytes()
        with patch.dict(os.environ, {'GUARDIAN_PAPER_PORT': '26841'}), self.assertRaises(RuntimeError):
            self.enforce()
        self.assertEqual(before, path.read_bytes())


class PortSelectionTests(unittest.TestCase):
    def test_default_pair_is_explicit_and_distinct(self):
        self.assertEqual(fixture_ports({}), {'paper-26.3': 26731, 'purpur-26.3': 26732})
        self.assertEqual(FIXTURE_HOST, '127.0.0.1')

    def test_valid_custom_pair_and_closed_bounds(self):
        for paper, purpur in ((26841, 26842), (1024, 65535), (65535, 1024)):
            env = {'GUARDIAN_PAPER_PORT': str(paper), 'GUARDIAN_PURPUR_PORT': str(purpur)}
            self.assertEqual(fixture_ports(env), {'paper-26.3': paper, 'purpur-26.3': purpur})
            self.assertEqual(fixture_port('paper-26.3', env), paper)
            self.assertEqual(fixture_port('purpur-26.3', env), purpur)
            with patch.dict(os.environ, env, clear=True):
                self.assertEqual(settings('paper-26.3')['server-port'], str(paper))
                self.assertEqual(settings('purpur-26.3')['server-port'], str(purpur))
                self.assertEqual(settings('paper-26.3')['server-ip'], FIXTURE_HOST)

    def test_partial_override_is_ambiguous_and_rejected(self):
        for key, value in CUSTOM.items():
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, 'together'):
                fixture_ports({key: value})

    def test_invalid_or_ambiguous_port_values_rejected(self):
        invalid = ('', '0', '1', '1023', '65536', '99999', '123456', '-26841', '+26841',
                   '026841', ' 26841', '26841 ', '26841\n', '26_841', '26841.0', '2.6841e4',
                   '0x68d9', '127.0.0.1:26841', 'localhost:26841', '26841,26842',
                   '\u0662\u0666\u0668\u0664\u0661', True, False, None, 26841, 26841.0)
        for key in CUSTOM:
            for value in invalid:
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    fixture_ports(CUSTOM | {key: value})

    def test_duplicate_pair_rejected_even_when_unused_variant_selected(self):
        env = {'GUARDIAN_PAPER_PORT': '26841', 'GUARDIAN_PURPUR_PORT': '26841'}
        for variant in ('paper-26.3', 'purpur-26.3'):
            with self.subTest(variant=variant), self.assertRaisesRegex(RuntimeError, 'distinct'):
                fixture_port(variant, env)

    def test_unknown_variant_rejected(self):
        for variant in ('paper', 'paper-26.4', 'spigot-26.3', '', None):
            with self.subTest(variant=variant), self.assertRaises(RuntimeError):
                fixture_port(variant, CUSTOM)

    def test_selector_returns_independent_data(self):
        result = fixture_ports({})
        result['paper-26.3'] = 1
        self.assertEqual(fixture_port('paper-26.3', {}), 26731)

    def test_client_port_requires_selected_integer(self):
        for value in ('26841', 26841.0, True, None, 26731, 26732, 26843, 0, 65536):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                validate_client_port(value, CUSTOM)
        self.assertEqual(validate_client_port(26841, CUSTOM), 26841)
        self.assertEqual(validate_client_port(26842, CUSTOM), 26842)

    def test_custom_client_target_and_handshake_use_same_literal_loopback_pair(self):
        for port in (26841, 26842):
            sock = Mock()
            with patch.dict(os.environ, CUSTOM, clear=True), patch('protocol_client.socket.create_connection', return_value=sock) as connect:
                client = Client(port, 'GacRealPortMock')
                connect.assert_called_once_with((FIXTURE_HOST, port), timeout=10, source_address=None)
                self.assertIn(text(FIXTURE_HOST) + struct.pack('>H', port), sock.sendall.call_args_list[0].args[0])
                client.close()

    def test_stale_target_and_bad_environment_never_open_socket(self):
        cases = [(CUSTOM, 26731), (CUSTOM, 26732), (CUSTOM, '26841'),
                 ({'GUARDIAN_PAPER_PORT': '26841'}, 26841),
                 ({'GUARDIAN_PAPER_PORT': '26841', 'GUARDIAN_PURPUR_PORT': '26841'}, 26841)]
        for env, port in cases:
            with self.subTest(env=env, port=port), patch.dict(os.environ, env, clear=True), patch('protocol_client.socket.create_connection') as connect:
                with self.assertRaises(RuntimeError):
                    Client(port, 'GacRealPortMock')
                connect.assert_not_called()

    def test_all_tcp_runners_select_ports_centrally_without_stale_targets(self):
        # Parse only. Several runners intentionally execute a server at import time.
        for relative in TCP_RUNNERS:
            tree = ast.parse((ROOT.parent / relative).read_text(), filename=relative)
            imports = [node for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)
                       and node.module == 'fixture_safety']
            calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
            with self.subTest(runner=relative):
                self.assertTrue(any(any(alias.name == 'fixture_port' for alias in node.names) for node in imports))
                self.assertTrue(any(isinstance(node.func, ast.Name) and node.func.id == 'fixture_port' for node in calls))
                for node in ast.walk(tree):
                    if isinstance(node, ast.Constant):
                        self.assertNotIn(node.value, (26731, 26732, '26731', '26732'))
                for node in calls:
                    if isinstance(node.func, ast.Name) and node.func.id in {'Client', 'ModifierClient'}:
                        self.assertTrue(node.args)
                        self.assertNotIsInstance(node.args[0], ast.Constant)

    def test_only_low_level_client_opens_tcp_and_uses_central_literal_host(self):
        connections = []
        for path in ROOT.parent.rglob('*.py'):
            if path.name.startswith('test_'):
                continue
            tree = ast.parse(path.read_text(), filename=str(path))
            connections += [(path, node) for node in ast.walk(tree)
                            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                            and isinstance(node.func.value, ast.Name) and node.func.value.id == 'socket'
                            and node.func.attr == 'create_connection']
        self.assertEqual(len(connections), 1)
        path, call = connections[0]
        self.assertEqual(path, ROOT.parent / 'client/protocol_client.py')
        self.assertEqual(ast.unparse(call.args[0]), '(FIXTURE_HOST, port)')

    def test_java_helper_gate_matches_python_port_contract(self):
        source = (ROOT / 'fixture/safety/FixturePorts.java').read_text()
        for key in CUSTOM:
            self.assertIn('"' + key + '"', source)
        self.assertIn('value.matches("[1-9][0-9]{3,4}")', source)
        self.assertIn('port < 1024 || port > 65535', source)
        self.assertIn('paperSet != purpurSet', source)
        self.assertIn('paper == purpur', source)
        self.assertIn('!"127.0.0.1".equals(host) || onlineMode || port != expected', source)
        self.assertIn('System.getProperty("guardian.fixture.variant")', source)
        self.assertIn('System.getenv()', source)
        self.assertIn(': "26731"', source)
        self.assertIn(': "26732"', source)

    def test_java_helpers_and_build_scripts_use_shared_exact_endpoint_gate(self):
        helpers = [('modifier', 'ModifierFixture.java'), ('ip-workflows', 'RealClientFixture.java')]
        for family, name in helpers:
            source = (ROOT.parent / family / 'fixture' / name).read_text()
            build = (ROOT.parent / family / 'fixture/build.sh').read_text()
            with self.subTest(helper=family):
                self.assertIn('import test.guardianfixture.safety.FixturePorts;', source)
                self.assertIn('FixturePorts.requireEndpoint(', source)
                self.assertIn('../../runtime/fixture/safety/FixturePorts.java', build)
                for stale in ('26700', '26799', '26731', '26732'):
                    self.assertNotIn(stale, source)
        ip_source = (ROOT.parent / 'ip-workflows/fixture/RealClientFixture.java').read_text()
        self.assertIn('FixturePorts.requireEndpoint("paper-26.3",', ip_source)


if __name__ == '__main__':
    unittest.main()
