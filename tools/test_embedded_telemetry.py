#!/usr/bin/env python3
"""Offline parser/source and final-JAR mutation regressions; never execute classes."""
import difflib
import os
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
import zipfile

from verify_embedded_telemetry import ADAPTER, BUILDER, classfile, inspect, instructions, invocations, invocation_sites

ROOT = Path(__file__).resolve().parent.parent
JAR = Path(os.environ.get('GUARDIANAC_TEST_JAR', ROOT / 'build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar'))


def specimen(name, methods=(), constants=()):
    """Construct inert classfile bytes for the reader; these are never JVM-loaded."""
    pool = []
    def add(tag, value):
        pool.append(bytes([tag]) + value)
        return len(pool)
    def utf(value):
        value = value.encode()
        return add(1, struct.pack('>H', len(value)) + value)
    def cls(value): return add(7, struct.pack('>H', utf(value)))
    this, parent, code_attr = cls(name), cls('java/lang/Object'), utf('Code')
    for value in constants: utf(value)
    bodies = []
    for method, calls in methods:
        method_index, descriptor = utf(method), utf('()V')
        code = b''
        for owner, target in calls:
            owner_index = cls(owner)
            signature = add(12, struct.pack('>HH', utf(target), utf('()V')))
            reference = add(10, struct.pack('>HH', owner_index, signature))
            code += b'\xb8' + struct.pack('>H', reference)
        code += b'\xb1'
        body = struct.pack('>HHI', 0, 0, len(code)) + code + b'\0\0\0\0'
        bodies.append(struct.pack('>HHHHHI', 9, method_index, descriptor, 1, code_attr, len(body)) + body)
    return (struct.pack('>IHHH', 0xCAFEBABE, 0, 69, len(pool) + 1) + b''.join(pool)
            + struct.pack('>HHHHHH', 0x21, this, parent, 0, 0, len(bodies))
            + b''.join(bodies) + b'\0\0')


class SourceAndParserTests(unittest.TestCase):
    def test_patch_matches_full_preferred_source(self):
        package = ROOT / 'third-party/patches/packetevents-2.14.0'
        relative = 'spigot/src/main/java/io/github/retrooper/packetevents/factory/spigot/SpigotPacketEventsBuilder.java'
        with tarfile.open(ROOT / 'third-party/sources/packetevents-v2.14.0.tar.gz') as archive:
            pristine = archive.extractfile('packetevents-2.14.0/' + relative).read().decode()
        edited = (package / relative.removeprefix('spigot/')).read_text()
        patch = ''.join(difflib.unified_diff(pristine.splitlines(keepends=True), edited.splitlines(keepends=True),
                                            fromfile='a/' + relative, tofile='b/' + relative))
        self.assertEqual(patch, (package / 'disable-embedded-metrics.patch').read_text())
        self.assertIn('Copyright (C) 2022 retrooper and contributors', edited)
        self.assertNotIn('new Metrics(', edited)
        self.assertNotIn('new SimplePie(', edited)

    def test_parser_identifies_actual_call(self):
        pool, methods = classfile(specimen('Fixture', [('init', [('org/example/Metrics', '<init>')])]))
        self.assertEqual([('org/example/Metrics', '<init>', '()V')], list(invocations(methods[0][2], pool)))

    def test_parser_rejects_truncation(self):
        with self.assertRaises(ValueError): classfile(specimen('Fixture')[:-1])

    def test_instruction_walker_respects_switch_padding_and_wide(self):
        # NOP, tableswitch (padding to offset 4), one branch, wide iinc, return.
        code = b'\0\xaa\0\0' + struct.pack('>iiii', 0, 1, 1, 0) + b'\xc4\x84\0\1\0\1\xb1'
        self.assertEqual([], list(invocations(code, [None])))
        code = b'\xab\0\0\0' + struct.pack('>iiii', 0, 1, 99, 0) + b'\xb1'
        self.assertEqual([], list(invocations(code, [None])))


class FinalJarTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not JAR.is_file(): raise AssertionError('Build the final shadowJar before this test: ' + str(JAR))
        with zipfile.ZipFile(JAR) as archive:
            # Mutations retain the real final builder/adapter classes, plus a
            # compact sample; full final-JAR pass separately scans every class.
            cls.fixture = {name: archive.read(name) for name in
                           (BUILDER + '.class', BUILDER + '$1.class', ADAPTER + '.class')}

    def audit(self, replacements):
        with tempfile.TemporaryDirectory(prefix='guardianac-telemetry-static-') as directory:
            path = Path(directory) / 'fixture.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                for name, data in (self.fixture | replacements).items(): archive.writestr(name, data)
            return inspect(path)['issues']

    def test_actual_shaded_jar_has_no_metrics_implementation_or_references(self):
        report = inspect(JAR)
        self.assertEqual([], report['issues'])
        self.assertGreater(report['classfiles_scanned'], 2000)
        self.assertGreater(report['builder_init_invocations_scanned'], 30)

    def test_restored_bstats_class_is_rejected(self):
        name = 'dev/guardianac/libs/pe/impl/packetevents/bstats/bukkit/Metrics'
        self.assertTrue(any('bStats' in issue for issue in self.audit({name + '.class': specimen(name)})))

    def test_endpoint_literal_without_metrics_class_is_rejected(self):
        self.assertTrue(any('endpoint' in issue for issue in self.audit({
            'Endpoint.class': specimen('Endpoint', constants=('https://bStats.org/api/v2/data/bukkit',))})))

    def test_dead_metrics_reference_is_rejected(self):
        self.assertTrue(any('reference' in issue for issue in self.audit({
            'Dead.class': specimen('Dead', constants=('org/bstats/bukkit/Metrics',))})))

    def test_actual_scheduler_instruction_in_builder_is_rejected(self):
        owner = 'java/util/concurrent/ScheduledThreadPoolExecutor'
        changed = specimen(BUILDER + '$1', [('init', [('org/bukkit/plugin/PluginManager', 'registerEvents'),
                                                    (owner, 'scheduleAtFixedRate')])])
        self.assertTrue(any('scheduler invocation' in issue for issue in self.audit({BUILDER + '$1.class': changed})))

    def test_restored_no_op_setting_is_rejected(self):
        changed = specimen(ADAPTER, [('load', [(BUILDER, 'build'), ('org/example/Settings', 'bStats')])])
        self.assertTrue(any('no-op bStats' in issue for issue in self.audit({ADAPTER + '.class': changed})))

    def update_setting_mutation(self, remove=False):
        data = self.fixture[ADAPTER + '.class']
        pool, methods = classfile(data)
        load = next(code for name, _, code in methods if name == 'load')
        offset = next(position for position, _, (_, target, _) in invocation_sites(load, pool)
                      if target == 'checkForUpdates')
        changed = bytearray(load)
        if remove:
            changed[offset:offset + 3] = b'\x57\x00\x00'  # drop boolean, keep Settings receiver
        else:
            previous = next(start for start, _, _ in instructions(load) if start == offset - 1)
            self.assertEqual(0x03, changed[previous])
            changed[previous] = 0x04  # ICONST_1: valid checkForUpdates(true) call
        self.assertEqual(1, data.count(load))
        return data.replace(load, bytes(changed))

    def test_enabled_update_check_is_rejected_in_actual_adapter(self):
        issues = self.audit({ADAPTER + '.class': self.update_setting_mutation()})
        self.assertTrue(any('checkForUpdates(false)' in issue for issue in issues))

    def test_removed_update_setting_is_rejected_in_actual_adapter(self):
        issues = self.audit({ADAPTER + '.class': self.update_setting_mutation(remove=True)})
        self.assertTrue(any('checkForUpdates(false)' in issue for issue in issues))

    def test_missing_functional_init_is_rejected(self):
        self.assertTrue(any('init()' in issue for issue in self.audit({BUILDER + '$1.class': specimen(BUILDER + '$1')})))


if __name__ == '__main__': unittest.main()
