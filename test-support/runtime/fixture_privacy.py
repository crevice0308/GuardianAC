"""Offline, fail-closed privacy checks for the reviewed disposable fixtures.

This module does not launch a JVM, open a socket, or authorize a server start.
Missing/unparseable settings are unsafe: upstream bStats defaults to enabled.
"""
from pathlib import Path
import hashlib
import json
import os
import stat
import zipfile

BSTATS_RELATIVE = Path('plugins/bStats/config.yml')
PAPER_RELATIVE = Path('config/paper-global.yml')
PURPUR_RELATIVE = Path('purpur.yml')
PRIVACY_JVM_ARGS = ('-Dpaper.disableStartupVersionCheck=true',
                    '-DPurpur.IReallyDontWantStartupCommands=true')
# Deliberately synthetic and shared by all disposable tests. Never copy/report a
# baseline's persistent metrics identifier or include one in the audit descriptor.
BSTATS_DISABLED = '''# Disposable local fixture: optional external statistics are disabled.
enabled: false
serverUuid: 00000000-0000-4000-8000-000000000000
logFailedRequests: false
logSentData: false
logResponseStatusText: false
'''
LOG_FLAGS = ('logFailedRequests', 'logSentData', 'logResponseStatusText')
JVM_INJECTION_ENV = ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')
MANIFEST = Path(__file__).with_name('reviewed-server-statistics.json')


def _regular_path(directory, relative, required=True):
    """Reject links at every component without resolving through them."""
    directory = Path(directory)
    for path in (directory, *directory.parents):
        if path.is_symlink():
            raise RuntimeError('Fixture privacy paths must not contain symlinks')
    path = directory
    for part in Path(relative).parts:
        path /= part
        if path.is_symlink():
            raise RuntimeError('Fixture privacy paths must not contain symlinks')
    if path.exists():
        if not stat.S_ISREG(path.stat().st_mode):
            raise RuntimeError('Fixture privacy input must be a regular file')
    elif required:
        raise RuntimeError('Required fixture privacy input is missing: ' + str(relative))
    return path


def _yaml_mapping(path):
    # SafeLoader prevents object construction; reject duplicate keys and aliases
    # instead of relying on different YAML implementations' override semantics.
    try:
        import yaml
    except ImportError:
        raise RuntimeError('Fixture privacy preflight requires PyYAML; no JVM was started') from None

    class StrictLoader(yaml.SafeLoader):
        def compose_node(self, parent, index):
            if self.check_event(yaml.AliasEvent):
                raise ValueError('YAML aliases are not accepted')
            return super().compose_node(parent, index)

        def construct_mapping(self, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = self.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result:
                    raise ValueError('Non-string or duplicate YAML key')
                result[key] = self.construct_object(value_node, deep=deep)
            return result

    try:
        value = yaml.load(path.read_text(encoding='utf-8'), Loader=StrictLoader)
        if not isinstance(value, dict):
            raise ValueError('Expected a YAML mapping')
        return value
    except (OSError, UnicodeError, ValueError, TypeError, yaml.YAMLError):
        # Parser errors can quote private configuration values. Never surface them.
        raise RuntimeError('Fixture privacy YAML is invalid or ambiguous: ' + path.name) from None


def initialize_privacy(directory):
    """Seed a NEW disposable copy only; preserve all existing run evidence."""
    directory = Path(directory)
    bstats = _regular_path(directory, BSTATS_RELATIVE, required=False)
    if bstats.exists():
        raise RuntimeError('Do not overwrite existing metrics evidence; use a fresh fixture')
    paper = _regular_path(directory, PAPER_RELATIVE)
    settings = _yaml_mapping(paper)
    spark = settings.get('spark', {})
    if not isinstance(spark, dict):
        raise RuntimeError('Fixture spark configuration must be a mapping')
    spark.update({'enabled': False, 'enable-immediately': False})
    settings['spark'] = spark
    update = settings.get('update-checker', {})
    if not isinstance(update, dict):
        raise RuntimeError('Fixture update-checker configuration must be a mapping')
    update['enabled'] = False
    settings['update-checker'] = update
    import yaml
    paper.write_text(yaml.safe_dump(settings, sort_keys=False), encoding='utf-8')
    if directory.name == 'purpur-26.3':
        purpur = _regular_path(directory, PURPUR_RELATIVE)
        purpur_config = _yaml_mapping(purpur)
        general = purpur_config.setdefault('settings', {})
        if not isinstance(general, dict):
            raise RuntimeError('Fixture Purpur settings must be a mapping')
        general['startup-commands'] = []
        purpur.write_text(yaml.safe_dump(purpur_config, sort_keys=False), encoding='utf-8')
    bstats.parent.mkdir(parents=True, exist_ok=True)
    bstats.write_text(BSTATS_DISABLED, encoding='utf-8')
    return validate_privacy_config(directory)


def validate_privacy_config(directory):
    """Read-only validation; absent, true, quoted, duplicate or alias flags fail."""
    bstats = _regular_path(directory, BSTATS_RELATIVE)
    paper = _regular_path(directory, PAPER_RELATIVE)
    statistics = _yaml_mapping(bstats)
    for key in ('enabled', *LOG_FLAGS):
        if statistics.get(key) is not False:
            raise RuntimeError('Optional bStats setting must be explicitly false: ' + key)
    if not isinstance(statistics.get('serverUuid'), str) or not statistics['serverUuid']:
        raise RuntimeError('Disposable bStats identifier must be initialized before startup')
    global_config = _yaml_mapping(paper)
    spark = global_config.get('spark')
    if not isinstance(spark, dict) or any(spark.get(key) is not False for key in ('enabled', 'enable-immediately')):
        raise RuntimeError('Bundled spark must be explicitly disabled in the fixture')
    update = global_config.get('update-checker')
    if not isinstance(update, dict) or update.get('enabled') is not False:
        raise RuntimeError('Startup version checks must be explicitly disabled in the fixture')
    config_hashes = {str(BSTATS_RELATIVE): hashlib.sha256(bstats.read_bytes()).hexdigest(),
                     str(PAPER_RELATIVE): hashlib.sha256(paper.read_bytes()).hexdigest()}
    if Path(directory).name == 'purpur-26.3':
        purpur = _regular_path(directory, PURPUR_RELATIVE)
        general = _yaml_mapping(purpur).get('settings')
        commands = general.get('startup-commands') if isinstance(general, dict) else None
        if commands != []:
            raise RuntimeError('Purpur fixture startup commands must be explicitly empty')
        config_hashes[str(PURPUR_RELATIVE)] = hashlib.sha256(purpur.read_bytes()).hexdigest()
    return {
        'bstats_enabled': False,
        'bstats_log_failed_requests': False,
        'bstats_log_sent_data': False,
        'bstats_log_response_status_text': False,
        'spark_enabled': False,
        'spark_enable_immediately': False,
        'startup_version_check_enabled': False,
        'purpur_startup_commands_empty': True if Path(directory).name == 'purpur-26.3' else None,
        'config_sha256': config_hashes,
    }


def inspect_reviewed_server(directory):
    """Bind the config-gate reasoning to exact reviewed official server bytes."""
    directory = Path(directory)
    reviewed = json.loads(MANIFEST.read_text(encoding='utf-8'))['variants'].get(directory.name)
    if reviewed is None:
        raise RuntimeError('Server statistics implementation has not been reviewed')
    observed = {}
    for relative, expected in reviewed['jar_sha256'].items():
        path = _regular_path(directory, relative)
        observed[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        if observed[relative] != expected:
            raise RuntimeError('Unreviewed server bytes; offline statistics review required: ' + relative)
    runtime = _regular_path(directory, reviewed['runtime_jar'])
    try:
        with zipfile.ZipFile(runtime) as archive:
            for name, expected in reviewed['class_sha256'].items():
                if archive.namelist().count(name) != 1 or hashlib.sha256(archive.read(name)).hexdigest() != expected:
                    raise RuntimeError('Reviewed server statistics class does not match: ' + name)
    except (OSError, KeyError, zipfile.BadZipFile):
        raise RuntimeError('Reviewed server statistics classes are unavailable') from None
    return {'reviewed_variant': directory.name, 'jar_sha256': observed,
            'class_sha256': dict(reviewed['class_sha256'])}


def privacy_preflight(directory, environ=None):
    """Run BEFORE any Java process and again immediately before server Popen."""
    environment = os.environ if environ is None else environ
    for key in JVM_INJECTION_ENV:
        if environment.get(key, '').strip():
            raise RuntimeError('Unreviewed JVM option injection is not allowed in a fixture: ' + key)
    return {
        'schema': 1,
        'kind': 'offline_config_and_reviewed_bytecode_preflight',
        'settings': validate_privacy_config(directory),
        'server': inspect_reviewed_server(directory),
        'required_jvm_args': list(PRIVACY_JVM_ARGS),
        'harness_source_sha256': {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
            for name in ('server_harness.py', 'fixture_safety.py', 'fixture_privacy.py', 'fixture_process.py', 'reviewed-server-statistics.json')},
        'claim': 'The reviewed server bStats disabled branch returns before metrics construction or scheduling.',
        'limits': [
            'This descriptor is not approval to start a server.',
            'This is static/config evidence, not a runtime observation of blocked or absent network traffic.',
            'It does not prove that every server component avoids all non-loopback egress.',
            'Embedded plugin telemetry needs its own source/artifact verification.',
        ],
    }
