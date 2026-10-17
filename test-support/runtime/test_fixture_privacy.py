"""No JVMs, sockets, or live fixtures: privacy preparation and launch-gate tests."""
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from fixture_privacy import (BSTATS_DISABLED, BSTATS_RELATIVE, JVM_INJECTION_ENV, PRIVACY_JVM_ARGS,
                             initialize_privacy, inspect_reviewed_server,
                             privacy_preflight, validate_privacy_config)

ROOT = Path(__file__).resolve().parent


class FixturePrivacyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guardianac-privacy-unit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / 'paper-26.3'
        (self.directory / 'plugins').mkdir(parents=True)
        (self.directory / 'config').mkdir()
        self.paper = self.directory / 'config/paper-global.yml'
        self.paper.write_text('_version: 31\nspark:\n  enabled: true\n  enable-immediately: true\nother:\n  value: preserve-me\n')
        self.bstats = self.directory / BSTATS_RELATIVE

    def prepared(self):
        initialize_privacy(self.directory)

    def test_new_copy_has_explicit_disabled_statistics_and_profiler(self):
        self.prepared()
        report = validate_privacy_config(self.directory)
        self.assertFalse(report['bstats_enabled'])
        self.assertFalse(report['spark_enabled'])
        self.assertIn('preserve-me', self.paper.read_text())
        self.assertNotIn('serverUuid', json.dumps(report))
        self.assertNotIn('00000000', json.dumps(report))

    def test_existing_run_config_is_never_rewritten(self):
        self.prepared()
        original = self.bstats.read_bytes()
        with self.assertRaises(RuntimeError):
            initialize_privacy(self.directory)
        self.assertEqual(original, self.bstats.read_bytes())

    def test_missing_bstats_config_fails_closed(self):
        with self.assertRaises(RuntimeError):
            validate_privacy_config(self.directory)

    def test_missing_or_enabled_or_string_or_duplicate_statistics_rejected(self):
        self.prepared()
        for value in ('true', '"false"', '0', 'null', '{}', '[]'):
            with self.subTest(value=value):
                self.bstats.write_text(BSTATS_DISABLED.replace('enabled: false', 'enabled: ' + value))
                with self.assertRaises(RuntimeError):
                    validate_privacy_config(self.directory)
        for value in (BSTATS_DISABLED.replace('enabled: false\n', ''),
                      BSTATS_DISABLED + 'enabled: false\n',
                      BSTATS_DISABLED + 'enabled: true\n',
                      BSTATS_DISABLED.replace('enabled: false', 'enabled: *privacy'),
                      'defaults: &privacy {enabled: false}\n<<: *privacy\n'):
            self.bstats.write_text(value)
            with self.assertRaises(RuntimeError):
                validate_privacy_config(self.directory)

    def test_log_flags_and_identifier_must_also_be_initialized(self):
        self.prepared()
        for key in ('logFailedRequests', 'logSentData', 'logResponseStatusText'):
            self.bstats.write_text(BSTATS_DISABLED.replace(key + ': false', key + ': true'))
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                validate_privacy_config(self.directory)
        self.bstats.write_text('\n'.join(line for line in BSTATS_DISABLED.splitlines() if not line.startswith('serverUuid:')))
        with self.assertRaises(RuntimeError):
            validate_privacy_config(self.directory)

    def test_spark_missing_true_string_and_duplicate_rejected(self):
        self.prepared()
        for value in ('{}', 'spark: {}', 'spark:\n  enabled: true\n  enable-immediately: false',
                      'spark:\n  enabled: "false"\n  enable-immediately: false',
                      'spark:\n  enabled: false\n  enabled: false\n  enable-immediately: false',
                      'spark:\n  enabled: false\n  enable-immediately: true'):
            self.paper.write_text(value)
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                validate_privacy_config(self.directory)

    def test_startup_update_checker_must_be_explicitly_false(self):
        self.prepared()
        for suffix in ('', 'update-checker: {}', 'update-checker: {enabled: true}',
                       'update-checker: {enabled: "false"}'):
            self.paper.write_text('spark: {enabled: false, enable-immediately: false}\n' + suffix)
            with self.subTest(suffix=suffix), self.assertRaises(RuntimeError):
                validate_privacy_config(self.directory)

    def test_purpur_startup_commands_are_cleared_in_new_copy_and_rejected_if_reintroduced(self):
        old = self.directory
        self.directory = self.root / 'purpur-26.3'
        old.rename(self.directory)
        purpur = self.directory / 'purpur.yml'
        purpur.write_text('settings:\n  startup-commands: [version]\n  preserved: true\n')
        initialize_privacy(self.directory)
        self.assertTrue(validate_privacy_config(self.directory)['purpur_startup_commands_empty'])
        self.assertIn('preserved: true', purpur.read_text())
        for data in ('settings: {startup-commands: [version]}', 'settings: null', '{}'):
            purpur.write_text(data)
            with self.subTest(data=data), self.assertRaises(RuntimeError):
                validate_privacy_config(self.directory)

    def test_yaml_errors_do_not_quote_configuration_values(self):
        self.prepared()
        self.bstats.write_text('enabled: [private-configuration-value')
        with self.assertRaises(RuntimeError) as error:
            validate_privacy_config(self.directory)
        self.assertNotIn('private-configuration-value', str(error.exception))

    def test_config_and_parent_symlinks_rejected(self):
        self.prepared()
        real = self.root / 'private-copy.yml'
        self.bstats.rename(real)
        self.bstats.symlink_to(real)
        with self.assertRaises(RuntimeError):
            validate_privacy_config(self.directory)
        self.bstats.unlink()
        self.bstats.parent.rmdir()
        real_dir = self.root / 'external-config'
        real_dir.mkdir()
        (real_dir / 'config.yml').write_text(BSTATS_DISABLED)
        self.bstats.parent.symlink_to(real_dir, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            validate_privacy_config(self.directory)

    def test_unreviewed_server_bytes_fail_before_start(self):
        self.prepared()
        (self.directory / 'server.jar').write_bytes(b'not a reviewed official server')
        with self.assertRaises(RuntimeError):
            inspect_reviewed_server(self.directory)

    def test_jvm_injection_environment_is_rejected(self):
        self.prepared()
        for key in JVM_INJECTION_ENV:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                privacy_preflight(self.directory, {key: '-javaagent:unreviewed.jar'})

    def test_readonly_descriptor_has_no_uuid_or_runtime_success_claim(self):
        self.prepared()
        before = self.bstats.read_bytes(), self.paper.read_bytes()
        with patch('fixture_privacy.inspect_reviewed_server', return_value={'reviewed_variant': 'unit-fixture'}):
            report = privacy_preflight(self.directory, {})
        self.assertEqual(before, (self.bstats.read_bytes(), self.paper.read_bytes()))
        self.assertNotIn('serverUuid', json.dumps(report))
        self.assertNotIn('00000000', json.dumps(report))
        self.assertIn('not a runtime observation', ' '.join(report['limits']))


class HarnessLaunchPrivacyTests(unittest.TestCase):
    def setUp(self):
        FixturePrivacyTests.setUp(self)
        self.project = self.root / 'project'
        artifact = self.project / 'build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar'
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b'synthetic authorized plugin')
        self.plugin_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
        self.env = {'JAVA_HOME': str(self.root / 'JVM-MUST-NOT-RUN'),
                    'GUARDIAN_PROJECT_ROOT': str(self.project),
                    'GUARDIAN_PAPER_BASELINE': str(self.directory),
                    'GUARDIAN_PURPUR_BASELINE': str(self.root / 'purpur-26.3'),
                    'GUARDIAN_RUNTIME_OUTPUT': str(self.root / 'runs'),
                    'GUARDIAN_PLUGIN_SHA256': self.plugin_hash,
                    'GUARDIAN_ACCEPT_MINECRAFT_EULA': 'true'}
        self.environment = patch.dict(os.environ, self.env, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        spec = importlib.util.spec_from_file_location('privacy_test_server_harness', ROOT / 'server_harness.py')
        self.harness = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.harness)
        (self.directory / 'server.properties').write_text('motd=synthetic fixture\n')
        (self.directory / 'eula.txt').write_text('eula=true\n')
        # Simulate the vulnerable baseline: its plugin subtree is excluded by prepare.
        self.bstats.parent.mkdir()
        self.bstats.write_text('enabled: true\nserverUuid: private-baseline-test-marker\n')

    def prepare_copy(self):
        return self.harness.prepare('paper-26.3', 'unit-run')

    def test_preparation_disables_before_any_subprocess_and_preserves_baseline(self):
        before = self.bstats.read_bytes(), self.paper.read_bytes()
        with patch('subprocess.Popen') as popen, patch('subprocess.check_output') as check:
            directory = self.prepare_copy()
        popen.assert_not_called()
        check.assert_not_called()
        validate_privacy_config(directory)
        self.assertEqual(before, (self.bstats.read_bytes(), self.paper.read_bytes()))
        self.assertNotIn('private-baseline-test-marker', (directory / BSTATS_RELATIVE).read_text())

    def assert_launch_rejected_without_java(self, directory):
        with patch('subprocess.Popen') as popen, patch('subprocess.check_output') as check:
            with self.assertRaises(RuntimeError):
                self.harness.Server(directory, 'unit-boot')
        popen.assert_not_called()
        check.assert_not_called()

    def test_output_cannot_overlap_readonly_baseline(self):
        before = (self.directory / 'server.properties').read_bytes(), self.bstats.read_bytes()
        with patch.object(self.harness, 'ROOT', self.root.parent), self.assertRaises(RuntimeError):
            self.harness.prepare('paper-26.3', self.root.name)
        self.assertEqual(before, ((self.directory / 'server.properties').read_bytes(), self.bstats.read_bytes()))

    def test_missing_statistics_rejected_before_even_java_version(self):
        directory = self.prepare_copy()
        (directory / BSTATS_RELATIVE).unlink()
        self.assert_launch_rejected_without_java(directory)

    def test_tampered_statistics_rejected_before_even_java_version(self):
        directory = self.prepare_copy()
        path = directory / BSTATS_RELATIVE
        path.write_text(path.read_text().replace('enabled: false', 'enabled: true'))
        self.assert_launch_rejected_without_java(directory)
        with self.assertRaises(RuntimeError):
            self.prepare_copy()
        self.assertIn('enabled: true', path.read_text())

    def test_unreviewed_jar_rejected_before_even_java_version(self):
        directory = self.prepare_copy()
        self.assert_launch_rejected_without_java(directory)

    def test_preflight_and_redacted_descriptor_precede_mock_popen(self):
        directory = self.prepare_copy()
        events = []
        real_privacy = privacy_preflight
        def check_version(*args, **kwargs):
            validate_privacy_config(directory)
            self.assertEqual(20,kwargs['timeout'])
            events.append('version')
            return 'openjdk version "25.0.1"'
        def preflight(path):
            events.append('privacy')
            with patch('fixture_privacy.inspect_reviewed_server', return_value={'reviewed_variant': 'synthetic-unit-fixture'}):
                return real_privacy(path, {})
        (directory/'server.jar').write_bytes(b'synthetic reviewed server')
        def fake_popen(*args, **kwargs):
            self.assertTrue(set(PRIVACY_JVM_ARGS) <= set(args[0]))
            self.assertIn('-Dguardian.fixture.variant=paper-26.3',args[0])
            self.assertEqual({'host':'127.0.0.1','port':26731,'variant':'paper-26.3'},kwargs['target'])
            self.assertEqual(3600,kwargs['max_seconds'])
            self.assertEqual(self.plugin_hash,kwargs['binding_sha256']['plugin'])
            validate_privacy_config(directory)
            descriptor = directory.parent / 'paper-26.3-unit-boot-privacy.json'
            report = json.loads(descriptor.read_text())
            self.assertFalse(report['settings']['bstats_enabled'])
            self.assertNotIn('serverUuid', descriptor.read_text())
            events.append('popen')
            return Mock(stdout=[])
        with patch.object(self.harness, 'privacy_preflight', side_effect=preflight), \
                patch('subprocess.check_output', side_effect=check_version), \
                patch.object(self.harness, 'FixtureProcess', side_effect=fake_popen), \
                patch('threading.Thread.start'):
            server = self.harness.Server(directory, 'unit-boot')
            server.log.close()
        self.assertEqual(['privacy', 'version', 'privacy', 'popen'], events)

    def test_config_changed_by_version_subprocess_rejected_before_popen(self):
        directory = self.prepare_copy()
        def tamper(*args, **kwargs):
            (directory / BSTATS_RELATIVE).write_text('enabled: true\n')
            return 'openjdk version "25.0.1"'
        with patch.object(self.harness, 'privacy_preflight', return_value={}), \
                patch('subprocess.check_output', side_effect=tamper), patch('subprocess.Popen') as popen:
            with self.assertRaises(RuntimeError):
                self.harness.Server(directory, 'unit-boot')
        popen.assert_not_called()

    def test_selected_custom_port_pair_is_applied_only_to_fresh_copy(self):
        with patch.dict(os.environ,{'GUARDIAN_PAPER_PORT':'26841','GUARDIAN_PURPUR_PORT':'26842'}):
            directory=self.prepare_copy()
            self.assertIn('server-port=26841', (directory/'server.properties').read_text())
            before=(directory/'server.properties').read_bytes()
            self.assertEqual(directory,self.prepare_copy())
            self.assertEqual(before,(directory/'server.properties').read_bytes())
        with self.assertRaises(RuntimeError):self.prepare_copy()
        self.assertEqual(before,(directory/'server.properties').read_bytes())

    def test_invalid_partial_pair_rejected_before_prepare_writes_or_copy(self):
        output=Path(self.env['GUARDIAN_RUNTIME_OUTPUT'])
        self.assertFalse(output.exists())
        with patch.dict(os.environ,{'GUARDIAN_PAPER_PORT':'26841'}), \
                patch('shutil.copytree') as copy, patch('subprocess.Popen') as popen:
            with self.assertRaises(RuntimeError):self.prepare_copy()
            copy.assert_not_called();popen.assert_not_called()
        self.assertFalse(output.exists())

    def test_existing_mismatched_port_is_rejected_without_repair(self):
        directory=self.prepare_copy();path=directory/'server.properties'
        path.write_text(path.read_text().replace('server-port=26731','server-port=26841'))
        before=path.read_bytes()
        with self.assertRaises(RuntimeError):self.prepare_copy()
        self.assertEqual(before,path.read_bytes())

    def test_invalid_lifetime_and_existing_lifecycle_rejected_before_java(self):
        directory=self.prepare_copy()
        with patch.dict(os.environ,{'GUARDIAN_FIXTURE_MAX_SECONDS':'0'}), \
                patch('subprocess.check_output') as check, patch.object(self.harness,'FixtureProcess') as owned:
            with self.assertRaises(ValueError):self.harness.Server(directory,'unit-boot')
            check.assert_not_called();owned.assert_not_called()
        record=directory.parent/'paper-26.3-unit-boot-lifecycle.json'
        record.write_text('preserved lifecycle evidence')
        self.assert_launch_rejected_without_java(directory)
        self.assertEqual('preserved lifecycle evidence',record.read_text())


class LaunchPathCoverageTests(unittest.TestCase):
    def test_all_shipped_server_launches_use_single_guarded_harness(self):
        launches = []
        runners = []
        for path in ROOT.parent.rglob('*.py'):
            if path.name.startswith('test_') or path.name == 'audit_server_statistics.py':
                continue
            tree = ast.parse(path.read_text())
            imports_server = False
            calls_server = False
            for node in ast.walk(tree):
                if isinstance(node, ast.ImportFrom) and node.module == 'server_harness':
                    imports_server |= any(alias.name in ('Server', '*') for alias in node.names)
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'Server':
                    calls_server = True
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == 'Popen':
                    launches.append(path.relative_to(ROOT.parent).as_posix())
            if calls_server and path.name != 'server_harness.py':
                self.assertTrue(imports_server, str(path))
                runners.append(path.relative_to(ROOT.parent).as_posix())
        self.assertEqual({'runtime/fixture_process.py'}, set(launches))
        harness=ast.parse((ROOT/'server_harness.py').read_text())
        owned=[n for n in ast.walk(harness) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='FixtureProcess']
        self.assertEqual(1,len(owned))
        self.assertTrue({'runtime/run_matrix.py', 'modifier/run_modifiers.py', 'load-limits/run_load_limits.py',
                         'ip-workflows/run_ip_workflows.py', 'client/run_suite.py',
                         'client/run_cave_wire.py', 'client/run_load_smoke.py',
                         'client/run_configuration_overlap.py'} <= set(runners))
        # Boundary runner is intentionally executable; inspect rather than import it.
        boundary = ast.parse((ROOT / 'test_boundaries.py').read_text())
        self.assertTrue(any(isinstance(node, ast.ImportFrom) and node.module == 'server_harness'
                            for node in ast.walk(boundary)))


if __name__ == '__main__':
    unittest.main()
