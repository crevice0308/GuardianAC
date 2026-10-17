"""No-JVM bytecode-proof and stale-import-plan regression tests."""
import copy
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest

from audit_server_statistics import ClassFile, verify_disabled_metrics_gate
from verify_harness_binding import RUNNERS, ROOT, SUPPORT, verify


def synthetic_gate(return_offset=324):
    """Independent tiny test object; not official server bytes or runtime proof."""
    fixture = object.__new__(ClassFile)
    fixture.pool = [None]
    code = bytearray(return_offset + 1)
    def reference(offset, opcode, text):
        fixture.pool.append((1, text))
        code[offset] = opcode
        width = 1 if opcode == 0x12 else 2
        code[offset + 1:offset + 1 + width] = (len(fixture.pool) - 1).to_bytes(width, 'big')
    reference(14, 0x12, 'plugins')
    reference(22, 0x12, 'bStats')
    reference(27, 0x12, 'config.yml')
    reference(118, 0x12, 'enabled')
    code[120] = 0x04
    reference(121, 0xB6, 'org/bukkit/configuration/file/YamlConfiguration.getBoolean(Ljava/lang/String;Z)Z')
    code[124] = 0x99
    code[125:127] = struct.pack('>h', return_offset - 124)
    reference(127, 0xBB, 'com/destroystokyo/paper/Metrics')
    reference(138, 0xB7, 'com/destroystokyo/paper/Metrics.<init>(Ljava/lang/String;Ljava/lang/String;ZLjava/util/logging/Logger;)V')
    code[return_offset] = 0xB1
    fixture.methods = {'startMetrics()V': bytes(code)}
    return fixture


class BytecodeProofTests(unittest.TestCase):
    def test_both_reviewed_return_shapes_have_disabled_gate(self):
        for offset in (324, 257):
            report = verify_disabled_metrics_gate(synthetic_gate(offset))
            self.assertEqual(offset, report['disabled_return_offset'])
            self.assertTrue(report['disabled_returns_before_metrics_construction'])

    def test_inverted_condition_fallthrough_and_nonreturn_are_rejected(self):
        for offset, byte in ((124, 0x9A), (125, 1), (126, 3), (324, 0)):
            fixture = synthetic_gate()
            code = bytearray(fixture.methods['startMetrics()V'])
            code[offset] = byte
            fixture.methods['startMetrics()V'] = bytes(code)
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                verify_disabled_metrics_gate(fixture)

    def test_changed_config_flag_or_metrics_constructor_is_rejected(self):
        for old in ('enabled', 'com/destroystokyo/paper/Metrics'):
            fixture = synthetic_gate()
            fixture.pool = [(1, 'unreviewed') if item == (1, old) else item for item in fixture.pool]
            with self.subTest(reference=old), self.assertRaises(ValueError):
                verify_disabled_metrics_gate(fixture)

    def test_class_parser_rejects_nonclass_and_truncation(self):
        for data in (b'', b'not a class', bytes.fromhex('cafebabe00000041000201')):
            with self.subTest(data=data), self.assertRaises(ValueError):
                ClassFile(data)

    def test_saved_official_audit_and_review_manifest_agree(self):
        audits = json.loads((ROOT / 'server-statistics-static-audit.json').read_text())['audits']
        reviewed = json.loads((ROOT / 'reviewed-server-statistics.json').read_text())['variants']
        self.assertEqual(set(reviewed), {a['server']['reviewed_variant'] for a in audits})
        for entry in audits:
            variant = entry['server']['reviewed_variant']
            self.assertEqual(reviewed[variant]['jar_sha256'], entry['server']['jar_sha256'])
            self.assertEqual(reviewed[variant]['class_sha256'], entry['server']['class_sha256'])
            self.assertTrue(entry['statistics_gate']['disabled_returns_before_metrics_construction'])
            self.assertEqual({'jvm_started': False, 'server_started': False, 'network_attempted': False}, entry['execution'])


class HarnessBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guardianac-binding-unit-')
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name) / 'test-support'
        self.copy.mkdir()

    def test_every_source_runner_resolves_current_safety_modules(self):
        for name, family in RUNNERS.items():
            with self.subTest(runner=name):
                result = verify(SUPPORT / family / name, {'GUARDIAN_CLIENT_SUPPORT': str(SUPPORT / 'client')})
                self.assertEqual(str(ROOT / 'server_harness.py'), result['bindings']['server_harness']['path'])
                self.assertFalse(result['runner_executed'])

    def test_fresh_byte_identical_private_copy_is_accepted(self):
        for family in ('modifier', 'client', 'runtime'):
            shutil.copytree(SUPPORT / family, self.copy / family, ignore=shutil.ignore_patterns('__pycache__'))
        result = verify(self.copy / 'modifier/run_modifiers.py', {'GUARDIAN_CLIENT_SUPPORT': str(self.copy / 'client')})
        self.assertEqual(str(self.copy / 'runtime/server_harness.py'), result['bindings']['server_harness']['path'])

    def test_copied_client_suite_guard_is_bound_to_current_source(self):
        for family in ('client', 'runtime'):
            shutil.copytree(SUPPORT / family, self.copy / family, ignore=shutil.ignore_patterns('__pycache__'))
        result = verify(self.copy / 'client/run_suite.py', {})
        self.assertEqual(str(self.copy / 'client/suite_gates.py'), result['bindings']['suite_gates']['path'])
        self.assertFalse(result['runner_executed'])
        self.assertFalse(result['jvm_started'])

    def test_stale_missing_or_shadowed_client_guard_is_rejected_without_import(self):
        for scenario in ('stale', 'missing', 'shadowed'):
            home = self.copy / scenario
            for family in ('client', 'runtime'):
                shutil.copytree(SUPPORT / family, home / family, ignore=shutil.ignore_patterns('__pycache__'))
            guard = home / 'client/suite_gates.py'
            marker = home / 'would-have-executed'
            if scenario == 'missing':
                guard.unlink()
            else:
                target = home / 'runtime/suite_gates.py' if scenario == 'shadowed' else guard
                target.write_text('from pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\n')
            with self.subTest(scenario=scenario), self.assertRaises(RuntimeError):
                verify(home / 'client/run_suite.py', {})
            self.assertFalse(marker.exists())

    def test_stale_env_override_and_missing_privacy_module_are_rejected(self):
        stale = self.copy / 'runtime'
        stale.mkdir()
        (stale / 'server_harness.py').write_text('# old unguarded runner\n')
        with self.assertRaises(RuntimeError):
            verify(SUPPORT / 'client/run_suite.py', {'GUARDIAN_RUNTIME_SUPPORT': str(stale)})
        shutil.copy2(ROOT / 'server_harness.py', stale)
        shutil.copy2(ROOT / 'fixture_safety.py', stale)
        with self.assertRaises(RuntimeError):
            verify(SUPPORT / 'client/run_suite.py', {'GUARDIAN_RUNTIME_SUPPORT': str(stale)})

    def test_client_directory_shadowing_is_rejected_without_import(self):
        shadow = self.copy / 'client'
        shadow.mkdir()
        marker = self.copy / 'would-have-executed'
        (shadow / 'server_harness.py').write_text('from pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\n')
        with self.assertRaises(RuntimeError):
            verify(SUPPORT / 'modifier/run_modifiers.py', {'GUARDIAN_CLIENT_SUPPORT': str(shadow)})
        self.assertFalse(marker.exists())

    def test_copied_runner_with_changed_import_logic_is_rejected(self):
        path = self.copy / 'run_suite.py'
        path.write_text((SUPPORT / 'client/run_suite.py').read_text() + '\n# changed runner\n')
        with self.assertRaises(RuntimeError):
            verify(path, {})

    def test_new_lifecycle_terminal_and_client_modules_are_bound_without_execution(self):
        for module,family in [('fixture_process','runtime'),('terminal_gates','runtime'),('protocol_client','client')]:
            for change in ('missing','stale'):
                home=self.copy/(module+'-'+change)
                for section in ('client','runtime'):
                    shutil.copytree(SUPPORT/section,home/section,ignore=shutil.ignore_patterns('__pycache__'))
                path=home/family/(module+'.py')
                marker=home/'MUST-NOT-EXECUTE'
                if change=='missing':path.unlink()
                else:path.write_text('from pathlib import Path\nPath('+repr(str(marker))+').touch()\n')
                with self.subTest(module=module,change=change),self.assertRaises(RuntimeError):
                    verify(home/'client/run_suite.py',{})
                self.assertFalse(marker.exists())

    def test_load_limits_observations_cannot_bypass_terminal_gate_binding(self):
        for section in ('runtime','client','load-limits'):
            shutil.copytree(SUPPORT/section,self.copy/section,ignore=shutil.ignore_patterns('__pycache__'))
        result=verify(self.copy/'load-limits/run_load_limits.py',{})
        self.assertIn('observations',result['bindings'])
        (self.copy/'load-limits/observations.py').write_text('def successful(report):return True\n')
        with self.assertRaises(RuntimeError):verify(self.copy/'load-limits/run_load_limits.py',{})

    def test_import_plan_validates_the_complete_selected_port_pair(self):
        result=verify(SUPPORT/'client/run_suite.py',{'GUARDIAN_PAPER_PORT':'26841','GUARDIAN_PURPUR_PORT':'26842'})
        self.assertEqual('127.0.0.1',result['fixture_host'])
        self.assertEqual({'paper-26.3':26841,'purpur-26.3':26842},result['fixture_ports'])
        with self.assertRaises(RuntimeError):verify(SUPPORT/'client/run_suite.py',{'GUARDIAN_PAPER_PORT':'26841'})


if __name__ == '__main__':
    unittest.main()
