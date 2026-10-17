"""64 actual TCP clients + a finite 40-login same-IP burst; loopback only.
No production changes, sample injection, flood loops, cap increases, or network tuning.
Run only after any other fixture has released the single-server measurement window.
"""
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import struct
import sys
import time
import traceback

ROOT = Path(__file__).resolve().parent
RUNTIME = Path(os.environ.get('GUARDIAN_RUNTIME_SUPPORT', str(ROOT.parent / 'runtime'))).resolve()
CLIENT = Path(os.environ.get('GUARDIAN_CLIENT_SUPPORT', str(ROOT.parent / 'client'))).resolve()
sys.path[:0] = [str(RUNTIME), str(CLIENT)]
from server_harness import prepare, Server, sha, stamp
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,write_report,stops_passed
from protocol_client import Client
from chunk_decode import decode_chunk, at, block_update
from observations import (source_address, denied_joins, burst_outcome, all_cleaned,
                          all_masked, successful, CLEANUP_FIELDS, primary_database)

COUNT = 64
BURST = 40
TRAFFIC_SECONDS = 30
JOIN_SECONDS = 120
REFILL_SECONDS = 11

def main():
    variant = sys.argv[1] if len(sys.argv) > 1 else 'paper-26.3'
    run = sys.argv[2] if len(sys.argv) > 2 else 'load-limits-v1'
    scenario = sys.argv[3] if len(sys.argv) > 3 else 'all'
    if scenario not in {'all', 'load', 'burst'}:
        raise ValueError('Scenario must be all, load, or burst')
    if variant not in {'paper-26.3', 'purpur-26.3'} or not re.fullmatch(r'[A-Za-z0-9_-]+', run):
        raise ValueError('Invalid variant or fresh run name')
    out = Path(os.environ.get('GUARDIAN_LOAD_LIMITS_OUTPUT', str(Path.cwd() / 'guardianac-load-limits-evidence'))).resolve() / run
    out.mkdir(parents=True, exist_ok=False)
    report = {
        'started_utc': stamp(), 'variant': variant, 'scenario': scenario, 'requested_clients': COUNT if scenario != 'burst' else 0,
        'traffic_seconds_requested': TRAFFIC_SECONDS if scenario != 'burst' else 0,
        'burst_attempts_requested': BURST if scenario != 'load' else 0,
        'refill_wait_seconds': REFILL_SECONDS, 'assertions': [], 'measurements': [],
        'limitations': [
            'Bounded 64-client loopback functional/resource smoke, not production capacity certification.',
            '30 seconds of simultaneous clean stationary tick traffic; no representative mixed player workload.',
            'Minimal independently decoded protocol 777 clients, not rendered Microsoft-authenticated clients.',
            'Only fixed loopback source/destination addresses; no OS/network setting changes.',
            'Same-IP burst is exactly 40 login attempts, not a denial-of-service resilience proof.',
            'Heap/RSS snapshots establish observed resources, not retained-object or leak attribution.',
        ],
    }
    s = None
    d = None
    clients = []
    load_clients = []
    burst_clients = []
    max_rss_kib = 0
    client_errors = {}

    def save():
        write_report(out / 'report.json', report)

    def check(name, passed, detail=None):
        entry = {'test': name, 'passed': bool(passed)}
        if detail is not None:
            entry['detail'] = detail
        report['assertions'].append(entry)
        print('GACLOADTEST ' + json.dumps(entry), flush=True)
        save()

    def rss():
        nonlocal max_rss_kib
        if not s or s.p.poll() is not None:
            return None
        path = Path('/proc') / str(s.p.pid) / 'status'
        if not path.exists():
            return None
        match = re.search(r'VmRSS:\s*(\d+)', path.read_text())
        value = int(match[1]) if match else None
        if value:
            max_rss_kib = max(max_rss_kib, value)
        return value

    def pump(group, duration=.0001):
        for c in group:
            if c.closed:
                continue
            try:
                c.pump(duration)
            except (OSError, EOFError, ValueError) as error:
                client_errors[c.name] = repr(error)
                c.close()

    def settle(group, seconds):
        deadline = time.monotonic() + seconds
        next_tick = 0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now >= next_tick:
                for c in group:
                    if c.joined and c.state == 'game' and not c.closed and not c.disconnect:
                        try:
                            c.send('MOVE_PLAYER_STATUS_ONLY', b'\1')
                            c.send('CLIENT_TICK_END')
                        except OSError as error:
                            client_errors[c.name] = repr(error)
                            c.close()
                next_tick = time.monotonic() + .05
            pump(group)
            rss()
            time.sleep(.001)

    def command(prefix, cmd):
        start = s.cmd(prefix + ' ' + cmd)
        marker = 'GACREAL' if prefix == 'gacreal' else 'GACMOD'
        s.expect(marker + ' COMMAND ' + cmd.split()[0] + ' DONE', timeout=30, start=start)

    def metrics(label):
        command('gacmod', 'report soak-' + label)
        a = json.loads((d / 'plugins/ModifierFixture' / ('soak-' + label + '.json')).read_text())
        a['rssKiB'] = rss()
        report['measurements'].append(a)
        (out / (label + '-metrics.json')).write_text(json.dumps(a, indent=2))
        save()
        return a

    def ore_report(label):
        command('gacreal', 'report ' + label)
        a = json.loads((d / 'plugins/RealClientFixture' / (label + '.json')).read_text())
        (out / (label + '-ore.json')).write_text(json.dumps(a, indent=2))
        return a

    def status(label):
        start = s.cmd('gac status')
        line = s.expect('announced bans.*denied joins', timeout=20, start=start)
        report[label + '_status'] = line.strip()
        return denied_joins(line)

    def sql():
        database = primary_database(d / 'plugins/GuardianAC')
        connection = sqlite3.connect('file:' + str(database) + '?mode=ro', uri=True)
        try:
            return {
                'bans': connection.execute('SELECT count(*) FROM bans').fetchone()[0],
                'evidence': connection.execute('SELECT count(*) FROM evidence').fetchone()[0],
                'integrity': [x[0] for x in connection.execute('PRAGMA integrity_check').fetchall()],
                'database_bytes': database.stat().st_size,
                'wal_bytes': Path(str(database) + '-wal').stat().st_size if Path(str(database) + '-wal').exists() else 0,
            }
        finally:
            connection.close()

    def cleanup(group, label):
        for c in group:
            c.close()
        # No traffic during cleanup. A bounded three-second server drain precedes observation.
        time.sleep(3)
        a = metrics(label)
        check(label + '_all_player_state_zero', all_cleaned(a),
              {k: a.get(k) for k in CLEANUP_FIELDS} | {'online_players': len(a['players'])})
        return a

    def make(name, source_ip):
        # The client validates loopback; record the OS-observed bound source after connecting.
        c = Client(port, name, source_host=source_ip)
        c.fixture_source_ip = c.s.getsockname()[0]
        clients.append(c)
        return c

    try:
        d = prepare(variant, run)
        for env, target in [('GUARDIAN_CLIENT_FIXTURE_JAR', 'RealClientFixture.jar'),
                            ('GUARDIAN_MODIFIER_FIXTURE_JAR', 'ModifierFixture.jar')]:
            shutil.copy2(Path(os.environ[env]), d / 'plugins' / target)
        config = d / 'server.properties'
        values = config.read_text()
        for key, value in [('white-list', 'false'), ('max-players', str(COUNT))]:
            if re.search(r'(?m)^' + key + r'=', values):
                values = re.sub(r'(?m)^' + key + r'=.*$', key + '=' + value, values)
            else:
                values += '\n' + key + '=' + value + '\n'
        config.write_text(values)
        sources = [Path(__file__), ROOT / 'observations.py', CLIENT / 'protocol_client.py',
                   CLIENT / 'chunk_decode.py', CLIENT / 'schema/packet-ids.json',
                   RUNTIME / 'server_harness.py', RUNTIME / 'fixture_safety.py',
                   RUNTIME / 'fixture_privacy.py', RUNTIME / 'reviewed-server-statistics.json']
        report['source_sha256'] = {str(p.relative_to(ROOT.parent)) if p.is_relative_to(ROOT.parent) else p.name: sha(p) for p in sources}
        report['jar_sha256'] = sha(d / 'plugins/GuardianAC.jar')
        report['jar_bytes'] = (d / 'plugins/GuardianAC.jar').stat().st_size
        report['server_sha256'] = sha(d / 'server.jar')
        report['fixture_sha256'] = {p.name: sha(p) for p in (d / 'plugins').glob('*.jar') if p.name != 'GuardianAC.jar'}
        report['server_properties_sha256'] = sha(config)
        report['bukkit_config_sha256'] = sha(d / 'bukkit.yml')
        port = fixture_port(variant)
        s = Server(d, 'load-limits')
        s.expect('Done \\(')
        s.expect('GuardianAC internal development build enabled', timeout=5)
        s.cmd('difficulty peaceful')
        command('gacreal', 'setup')
        baseline = metrics('baseline')
        initial_denied = status('baseline')
        report['baseline_sqlite'] = sql()
        check('fresh_fixture_has_no_prior_bans_or_evidence', report['baseline_sqlite']['bans'] == 0 and report['baseline_sqlite']['evidence'] == 0)
        check('fixture_heap_cap_768_mib', baseline['heapMax'] <= 768 * 1024 * 1024)

        if scenario in {'all', 'load'}:
            join_start = time.monotonic()
            join_deadline = join_start + JOIN_SECONDS
            for i in range(COUNT):
                if time.monotonic() >= join_deadline:
                    raise TimeoutError('Fixed 120-second load join window expired')
                c = make('GacReal64_' + str(i).zfill(2), source_address(i))
                load_clients.append(c)
                one_deadline = min(join_deadline, time.monotonic() + 15)
                while time.monotonic() < one_deadline and not c.closed and not c.disconnect:
                    settle(load_clients, .05)
                    if c.joined and c.position:
                        break
                print('GACLOAD JOIN ' + c.name + ' ' + str(c.joined and bool(c.position)), flush=True)
                if not c.joined or not c.position or c.closed or c.disconnect:
                    raise RuntimeError('Load client did not reach healthy joined state: ' + c.name)
            report['join_seconds'] = time.monotonic() - join_start
            settle(load_clients, 3)
            report['joined_metrics'] = metrics('joined-64')
            report['actual_sources'] = {c.name: c.fixture_source_ip for c in load_clients}
            check('all_64_distinct_loopback_sources', len(set(report['actual_sources'].values())) == COUNT
                  and set(report['actual_sources'].values()) == {source_address(i) for i in range(COUNT)}, report['actual_sources'])
            check('all_64_accepted', len(report['joined_metrics']['players']) == COUNT)
            check('load_did_not_trigger_join_limiter', status('load-joined') == initial_denied)
            started = time.monotonic()
            for sample in range(6):
                settle(load_clients, 5)
                metrics('traffic-' + str(sample + 1))
            report['traffic_seconds_actual'] = time.monotonic() - started
            traffic_samples = [a for a in report['measurements'] if a['label'].startswith('soak-traffic-')]
            report['traffic_resource_range'] = {key: {'min': min(a[key] for a in traffic_samples), 'max': max(a[key] for a in traffic_samples)}
                                                for key in ('tps', 'heapUsed', 'heapCommitted', 'heapMax', 'gcCount')}
            far = ore_report('load-far')
            viewers = {}
            for c in load_clients:
                states = []
                frames = []
                for i, raw in enumerate(c.fixture_chunk_history):
                    states.append(at(decode_chunk(raw), 40, 20, 8))
                    filename = c.name + '-far-' + str(i) + '.bin'
                    (out / filename).write_bytes(raw)
                    frames.append({'file': filename, 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)})
                viewers[c.name] = {'states': states, 'retained_all': c.fixture_chunk_packets == len(states),
                                   'received_frames': c.fixture_chunk_packets, 'frames': frames}
            report['far_wire_viewers'] = viewers
            report['far_observation'] = far
            clean = report['measurements'][-1]
            check('all_64_remained_connected', len(load_clients) == COUNT and all(c.joined and not c.closed and not c.disconnect for c in load_clients))
            check('at_least_30_seconds_simultaneous_traffic', report['traffic_seconds_actual'] >= TRAFFIC_SECONDS, report['traffic_seconds_actual'])
            check('all_64_clean_findings_zero', len(clean['players']) == COUNT and clean['findingsCount'] == 0 and all(not p['findings'] for p in clean['players']))
            check('all_64_traffic_sampled_tps_at_least_18', report['traffic_resource_range']['tps']['min'] >= 18, report['traffic_resource_range']['tps'])
            check('all_64_clean_bans_zero', clean['banCount'] == 0 and sql()['bans'] == 0)
            check('all_64_clean_packet_drops_zero', clean['dropped'] == 0, clean['dropped'])
            check('all_64_clean_malformed_zero', clean['malformed'] == 0, clean['malformed'])
            check('all_64_actual_far_ore_frames_masked', all_masked(viewers, COUNT, far['stoneId']),
                  {'covered_viewers': sum(bool(v['states']) for v in viewers.values()), 'total_frames': sum(len(v['states']) for v in viewers.values()), 'stone_id': far['stoneId']})
            check('eligible_ore_budget_failures_zero', far['budgetLimitedChunks'] == 0, far['budgetLimitedChunks'])
            check('world_ore_unchanged_before_reveal', far['worldOre'] == 'DIAMOND_ORE')
            command('gacreal', 'nearall')
            settle(load_clients, 5)
            near = ore_report('load-near')
            near_metrics = metrics('near-64')
            check('all_64_near_findings_bans_zero', len(near_metrics['players']) == COUNT and near_metrics['findingsCount'] == 0 and near_metrics['banCount'] == 0)
            reveals = {}
            for c in load_clients:
                updates = [block_update(raw) for raw in c.block_updates]
                reveals[c.name] = [p for p in updates if (p['x'], p['y'], p['z']) == (40, 20, 8)]
            report['near_wire_viewers'] = reveals
            check('all_64_actual_near_ore_reveals', len(reveals) == COUNT and all(any(p['state'] == near['oreId'] for p in updates) for updates in reveals.values()))
            check('world_ore_unchanged_after_reveal', near['worldOre'] == 'DIAMOND_ORE')
            report['load_clients_before_quit'] = [c.report() for c in load_clients]
            cleanup(load_clients, 'load-all-quit')

        if scenario in {'all', 'burst'}:
            # A new 127.0.0.1 bucket is used, distinct from all 64 previous client sources.
            before_burst = status('before-burst')
            burst_start = time.monotonic()
            for i in range(BURST):
                c = make('GacRealBurst' + str(i).zfill(2), '127.0.0.1')
                burst_clients.append(c)
            report['burst_connect_seconds'] = time.monotonic() - burst_start
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                settle(burst_clients, .05)
                if all(c.joined or c.disconnect or c.closed for c in burst_clients):
                    break
            report['burst_clients'] = [c.report() for c in burst_clients]
            outcomes = collections.Counter(burst_outcome(c.report()) for c in burst_clients)
            report['burst_outcomes'] = dict(outcomes)
            report['burst_total_seconds'] = time.monotonic() - burst_start
            after_burst = status('after-burst')
            report['burst_denied_counter_delta'] = after_burst - before_burst
            burst_metrics = metrics('burst')
            report['burst_sqlite'] = sql()
            check('burst_is_exactly_40_attempts', len(burst_clients) == BURST)
            check('burst_has_real_rate_denials', outcomes['rate-denied'] > 0 and after_burst - before_burst == outcomes['rate-denied'],
                  {'wire_outcomes': dict(outcomes), 'native_denied_delta': after_burst - before_burst})
            check('burst_has_no_unaccounted_outcomes', outcomes['unaccounted'] == 0 and sum(outcomes.values()) == BURST)
            check('burst_did_not_create_bans', report['burst_sqlite']['bans'] == 0 and burst_metrics['banCount'] == 0)
            check('burst_did_not_create_findings_or_evidence', burst_metrics['findingsCount'] == 0 and report['burst_sqlite']['evidence'] == 0)
            report['burst_clients_before_quit'] = [c.report() for c in burst_clients]
            cleanup(burst_clients, 'burst-all-quit')
            refill_start = time.monotonic()
            time.sleep(REFILL_SECONDS)
            report['refill_seconds_actual'] = time.monotonic() - refill_start
            recovery = make('GacRealRecover', '127.0.0.1')
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and not recovery.closed and not recovery.disconnect:
                settle([recovery], .05)
                if recovery.joined and recovery.position:
                    break
            settle([recovery], 2)
            report['recovery_client'] = recovery.report()
            recovery_metrics = metrics('recovery')
            check('same_ip_recovers_after_refill', recovery.joined and bool(recovery.position) and not recovery.closed and not recovery.disconnect)
            check('recovery_has_no_new_rate_denial', status('recovery') == after_burst)
            check('recovery_has_no_findings_or_bans', recovery_metrics['findingsCount'] == 0 and recovery_metrics['banCount'] == 0 and sql()['bans'] == 0)
            cleanup([recovery], 'final-all-quit')
        report['run_completed'] = True
    except (Exception, KeyboardInterrupt) as error:
        report['error'] = repr(error)
        report['traceback'] = traceback.format_exc()
        print(report['traceback'], flush=True)
    finally:
        report['client_errors'] = client_errors
        close_clients(report, clients, reports_key='clients_at_shutdown',
                      report_client=lambda c: dict(c.report(), actual_source_ip=c.fixture_source_ip))
        stop_server(report, s)
        if not stops_passed(report, ('server_exit',)):
            report['sqlite_error'] = 'Final SQLite inspection unavailable: normal-stop/result gate did not pass; database was not read'
        elif d and (d / 'plugins/GuardianAC').exists():
            try:
                report['final_sqlite'] = sql()
                check('final_sqlite_integrity', report['final_sqlite']['integrity'] == ['ok'])
                check('final_sqlite_no_bans_or_evidence', report['final_sqlite']['bans'] == 0 and report['final_sqlite']['evidence'] == 0)
            except (Exception, KeyboardInterrupt) as error:
                report['sqlite_error'] = repr(error)
                report['error'] = report.get('error') or 'SQLite verification failed'
        check('all_context_captures_succeeded', bool(report['measurements']) and all(a.get('contextCaptureFailures') == 0 for a in report['measurements']),
              [a.get('contextCaptureFailures') for a in report['measurements']])
        report['max_rss_kib'] = max_rss_kib
        report['ended_utc'] = stamp()
        check('client_helper_errors_zero', not client_errors, client_errors)
        report['passed'] = successful(report)
        save()
        print('GACLOAD FINISHED ' + json.dumps({'passed': report['passed'], 'assertions_passed': sum(a['passed'] for a in report['assertions']),
              'assertions_total': len(report['assertions']), 'error': report.get('error'), 'server_exit': report.get('server_exit')}), flush=True)
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
