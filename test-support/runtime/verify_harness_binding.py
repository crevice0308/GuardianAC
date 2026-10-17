#!/usr/bin/env python3
"""Read-only import-plan validation. NEVER import/execute the supplied runner.

Usage (with the planned runner environment already set):
  python -B verify_harness_binding.py /absolute/path/to/run_modifiers.py

A copied runner is accepted only when it and all resolved safety modules match
this reviewed source tree byte-for-byte. Stale helper copies fail closed.
"""
import hashlib
import importlib.machinery
import json
import os
from pathlib import Path
import sys
from fixture_safety import fixture_ports,FIXTURE_HOST

ROOT = Path(__file__).resolve().parent
SUPPORT = ROOT.parent
RUNNERS = {
    'server_harness.py': 'runtime', 'run_matrix.py': 'runtime', 'test_boundaries.py': 'runtime',
    'run_suite.py': 'client', 'run_cave_wire.py': 'client', 'run_load_smoke.py': 'client',
    'run_configuration_overlap.py': 'client', 'run_modifiers.py': 'modifier',
    'run_ip_workflows.py': 'ip-workflows', 'run_load_limits.py': 'load-limits',
}
MODULES = ('server_harness', 'fixture_safety', 'fixture_privacy', 'fixture_process', 'terminal_gates')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify(runner, environ=None):
    environment = os.environ if environ is None else environ
    selected_ports=fixture_ports(environment)
    runner = Path(runner).resolve()
    family = RUNNERS.get(runner.name)
    if family is None or not runner.is_file():
        raise RuntimeError('Unknown runner; review its imports before any server start')
    expected_runner = SUPPORT / family / runner.name
    if digest(runner) != digest(expected_runner):
        raise RuntimeError('Runner differs from reviewed current source: ' + runner.name)
    sibling = runner.parent.parent
    runtime = str(Path(environment.get('GUARDIAN_RUNTIME_SUPPORT', str(sibling / 'runtime'))).resolve())
    client = str(Path(environment.get('GUARDIAN_CLIENT_SUPPORT', str(sibling / 'client'))).resolve())
    if family == 'runtime':
        prefix = []
    elif family == 'modifier':
        if not environment.get('GUARDIAN_CLIENT_SUPPORT'):
            raise RuntimeError('Modifier runner requires explicit GUARDIAN_CLIENT_SUPPORT')
        # This runner intentionally uses its sibling runtime, not the env override.
        prefix = [client, str(sibling / 'runtime')]
    elif family == 'ip-workflows':
        prefix = [client, runtime]  # two insert(0) calls in this order at execution
    elif family == 'load-limits':
        prefix = [runtime, client]
    else:
        prefix = [runtime]
    search = prefix + [str(runner.parent)]
    search += [str(Path(value or '.').resolve()) for value in environment.get('PYTHONPATH', '').split(os.pathsep)
               if environment.get('PYTHONPATH') is not None]
    # Only intended project locations are accepted; no site-packages fallback.
    bindings = {}
    expected_modules = {name: ROOT / (name + '.py') for name in MODULES}
    if family in {'client','modifier','ip-workflows','load-limits'} or runner.name=='run_matrix.py':
        expected_modules['protocol_client'] = SUPPORT / 'client/protocol_client.py'

    if family=='load-limits':expected_modules['observations']=SUPPORT/'load-limits/observations.py'
    if family == 'client' and runner.name == 'run_suite.py':
        expected_modules['suite_gates'] = SUPPORT / 'client/suite_gates.py'
    for name, expected in expected_modules.items():
        module_search=([str(sibling/'client')]+search) if runner.name=='run_matrix.py' and name=='protocol_client' else search
        spec = importlib.machinery.PathFinder.find_spec(name, module_search)
        if spec is None or spec.origin is None:
            raise RuntimeError('Safety module cannot be resolved in planned runner paths: ' + name)
        actual = Path(spec.origin)
        if actual.suffix != '.py' or digest(actual) != digest(expected):
            raise RuntimeError('Stale or unreviewed safety module would be imported: ' + name)
        bindings[name] = {'path': str(actual.resolve()), 'sha256': digest(actual)}
    resolved_manifest = Path(bindings['fixture_privacy']['path']).with_name('reviewed-server-statistics.json')
    if not resolved_manifest.is_file() or digest(resolved_manifest) != digest(ROOT / resolved_manifest.name):
        raise RuntimeError('Copied server statistics review manifest is missing or stale')
    return {'kind': 'read_only_runner_import_plan', 'runner': str(runner), 'runner_sha256': digest(runner),
            'search_prefix': search, 'bindings': bindings,
            'fixture_host':FIXTURE_HOST,'fixture_ports':selected_ports,
            'manifest_sha256': digest(resolved_manifest),
            'runner_executed': False, 'jvm_started': False, 'server_started': False,
            'limit': 'Verifies the supplied command environment and reviewed source bytes; not start authorization.'}


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Supply one planned runner path. This command only reads files.')
    print(json.dumps(verify(sys.argv[1]), indent=2))
