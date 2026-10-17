"""Static UTF-8 catalog and exact fixture-oracle checks; never starts a JVM/server."""
import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'src/main/resources/locales'


def catalog(language):
    entries = {}
    for line in (RESOURCES / f'runtime_{language}.properties').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in entries:
            raise ValueError('Duplicate catalog key: ' + key)
        entries[key] = value.replace('\\n', '\n')
    return entries


def expect_patterns(path):
    tree = ast.parse((ROOT / path).read_text())
    return [node.args[0].value for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and node.func.attr == 'expect' and node.args
            and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)]


class RuntimeLocalizationTests(unittest.TestCase):
    def test_both_catalogs_have_exact_keys_placeholders_and_real_translations(self):
        source = (ROOT / 'src/main/java/dev/guardianac/bukkit/RuntimeMessages.java').read_text()
        keys = {key: int(count) for key, count in re.findall(r'^        ([A-Z_]+)\((\d+)\)[,;]', source, re.M)}
        self.assertEqual(len(keys), 60)
        ja, en = catalog('ja'), catalog('en')
        self.assertEqual(set(keys), set(ja))
        self.assertEqual(set(keys), set(en))
        for key, count in keys.items():
            with self.subTest(key=key):
                self.assertNotEqual(ja[key], en[key])
                self.assertRegex(ja[key], r'[ぁ-んァ-ン一-龯]')
                self.assertNotRegex(en[key], r'[ぁ-んァ-ン一-龯]')
                for value in (ja[key], en[key]):
                    self.assertTrue(value.strip())
                    self.assertEqual(set(map(int, re.findall(r'\{(\d+)\}', value))), set(range(count)))
                    self.assertNotRegex(re.sub(r'\{\d+\}', '', value), '[{}]')

    def test_every_direct_and_retried_diagnostic_has_an_explicit_translation(self):
        plugin = (ROOT / 'src/main/java/dev/guardianac/bukkit/GuardianPlugin.java').read_text()
        reconciler = (ROOT / 'src/main/java/dev/guardianac/security/NativeBanReconciler.java').read_text()
        actual = set(re.findall(r'systemError\("([^"]+)"\)', plugin))
        actual.update(re.findall(r'report\("([^"]+)"\)', reconciler))
        actual.update(reason + '; committed database state is unchanged and periodic repair will retry'
                      for reason in re.findall(r'retry\(tick,"([^"]+)"\)', reconciler))
        expected = {value for key, value in catalog('en').items() if key.startswith('ERROR_') and key != 'ERROR_UNKNOWN'}
        self.assertEqual(expected, actual)

    def test_plugin_visible_sinks_have_no_hardcoded_string_messages(self):
        source = (ROOT / 'src/main/java/dev/guardianac/bukkit/GuardianPlugin.java').read_text()
        self.assertNotRegex(source, r'(?:sendMessage|broadcastMessage|notify)\("')
        self.assertNotRegex(source, r'disallow\([^,]+,"')
        self.assertIn('RuntimeMessages.status(messageLanguage(),"GuardianAC "', source)
        self.assertIn('RuntimeMessages.banScreen(messageLanguage(),record.permanent(),record.expiresAt(),record.caseId())', source)
        self.assertIn('current==null?"ja":current.language()', source)
        self.assertNotIn('getLocale(', source)

    def test_smoke_and_cave_success_oracles_accept_only_explicit_language_variants(self):
        tree = ast.parse((ROOT / 'test-support/runtime/server_harness.py').read_text())
        expected = {}
        for node in ast.walk(tree):
            if isinstance(node, ast.For) and isinstance(node.iter, ast.List):
                try:
                    pairs = ast.literal_eval(node.iter)
                    if all(isinstance(pair, tuple) and len(pair) == 2 for pair in pairs):
                        expected.update(pairs)
                except (ValueError, TypeError):
                    pass
        keys = {'guardian reload': 'CONFIG_LOADED', 'gac unban MissingFixture': 'NO_ACTIVE_BAN',
                'gac confirm invalid': 'CONFIRM_EXPIRED', 'gac replay invalid': 'INVALID_CASE'}
        for command, key in keys.items():
            for language in ('ja', 'en'):
                self.assertRegex(catalog(language)[key], expected[command])
            for rejected in ('Configuration rejected', '設定を読み込めませんでした。', 'Something happened', ''):
                self.assertIsNone(re.search(expected[command], rejected))
        cave = expect_patterns('test-support/client/run_cave_wire.py')
        for language in ('ja', 'en'):
            self.assertTrue(any(re.search(pattern, catalog(language)['CONFIG_LOADED']) for pattern in cave))

    def test_ip_workflow_error_oracle_preserves_english_and_recognizes_japanese_database_busy(self):
        source = (ROOT / 'test-support/ip-workflows/run_ip_workflows.py').read_text()
        tree = ast.parse(source)
        patterns = [node.args[0].value for node in ast.walk(tree) if isinstance(node, ast.Call)
                    and isinstance(node.func, ast.Attribute) and node.func.attr == 'search' and node.args
                    and isinstance(node.args[0], ast.Constant) and 'GACIP ERROR' in str(node.args[0].value)]
        self.assertEqual(len(patterns), 1)
        for language in ('ja', 'en'):
            self.assertRegex(catalog(language)['LOGIN_BUSY'], patterns[0])
        self.assertRegex('Ban persistence failed', patterns[0])
        self.assertIsNone(re.search(patterns[0], 'GuardianAC internal development build enabled'))


if __name__ == '__main__':
    unittest.main()
