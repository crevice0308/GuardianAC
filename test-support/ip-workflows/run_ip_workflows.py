"""Bounded replacement-artifact offline identity/shared-IP safety regressions."""
import datetime
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
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, os.environ.get('GUARDIAN_RUNTIME_SUPPORT', str(HERE.parent / 'runtime')))
sys.path.insert(0, os.environ.get('GUARDIAN_CLIENT_SUPPORT', str(HERE.parent / 'client')))
from server_harness import prepare, Server, sha, ROOT as RUNTIME_ROOT
from fixture_safety import validate_fixture, fixture_port
from terminal_gates import close_clients,stop_server,normal_exit,no_errors,assertions_passed,ip_case_passed,ip_first_phase_allows_restart,write_report,stops_passed
from protocol_client import Client, offline_uuid

ADDRESS = '127.0.0.31'
OTHER_ADDRESS = '127.0.0.32'
PORT = fixture_port('paper-26.3')
HISTORICAL_UNSAFE = '11ffe3b21f6c3d411320e84d85e3409e9651c21231c0a1ffa0cbba3ede7326f0'
EXPECTED = os.environ.get('GUARDIAN_IP_EXPECTED_SHA256', '')


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def inspect_storage(directory):
    database = directory / 'plugins/GuardianAC/bans/bans.db'
    con = sqlite3.connect('file:' + str(database) + '?mode=ro', uri=True)
    con.row_factory = sqlite3.Row
    try:
        integrity = [row[0] for row in con.execute('PRAGMA integrity_check')]
        foreign_keys = [list(row) for row in con.execute('PRAGMA foreign_key_check')]
        bans = []
        for row in con.execute('SELECT * FROM bans ORDER BY created_at'):
            record = dict(row)
            record['protected_ip_present'] = bool(record.pop('ip_encrypted'))
            record['ip_lookup_present'] = bool(record.pop('ip_lookup'))
            record['ownership_marker'] = 'GuardianAC:' + record['case_id'] + ':' + record['sync_token']
            bans.append(record)
        evidence = []
        for row in con.execute('SELECT * FROM evidence ORDER BY recorded_at'):
            record = dict(row)
            payload = record.pop('payload')
            record['hash_matches'] = payload is not None and hashlib.sha256(payload.encode()).hexdigest() == record['sha256']
            record['payload_bytes_match'] = payload is not None and len(payload.encode()) == record['payload_bytes']
            record['data'] = json.loads(payload) if payload else None
            evidence.append(record)
        observations = [dict(row) for row in con.execute('SELECT player_uuid,trusted,length(ip_lookup) AS lookup_length FROM ip_observations')]
        native_players = json.loads((directory / 'banned-players.json').read_text())
        native_ips = json.loads((directory / 'banned-ips.json').read_text())
        return {'integrity': integrity, 'foreign_key_violations': foreign_keys, 'bans': bans,
                'evidence': evidence, 'observations': observations,
                'native_players': native_players, 'native_ips': native_ips}
    finally:
        con.close()


def native_expiry_matches(entry, expiry):
    try:
        value = datetime.datetime.strptime(entry['expires'], '%Y-%m-%d %H:%M:%S %z').timestamp() * 1000
        return abs(value - expiry) < 1000
    except (ValueError, KeyError, TypeError):
        return False


def run_case(run, requested_type):
    if requested_type not in ('IP', 'DUAL'):
        raise ValueError('Only bounded offline IP and DUAL cases are supported')
    name = run + '-' + requested_type.lower()
    if (RUNTIME_ROOT / name).exists():
        raise RuntimeError('Preserve previous evidence: choose a fresh run name')
    directory = prepare('paper-26.3', name)
    helper = Path(os.environ['GUARDIAN_IP_FIXTURE_JAR']).resolve()
    shutil.copy2(helper, directory / 'plugins/RealClientFixture.jar')
    props = directory / 'server.properties'
    props.write_text(re.sub(r'(?m)^white-list=.*$', 'white-list=false', props.read_text()))
    for filename in ('banned-players.json', 'banned-ips.json'):
        if json.loads((directory / filename).read_text()):
            raise RuntimeError('Baseline native bans must be empty')
    with zipfile.ZipFile(directory / 'plugins/GuardianAC.jar') as artifact:
        config = artifact.read('config.yml').decode()
    for key, value in {'ban-type': requested_type, 'ban-duration-hours': '1', 'ip-duration-hours': '1',
                       'auto-ban': 'true', 'allow-permanent-ip': 'false', 'native-ban-sync': 'true',
                       'discord-enabled': 'false', 'broadcast': 'false'}.items():
        config, count = re.subn(r'(?m)^' + re.escape(key) + r':.*$', key + ': ' + value, config)
        if count != 1:
            raise RuntimeError('Unrecognized immutable default configuration key ' + key)
    (directory / 'plugins/GuardianAC').mkdir()
    (directory / 'plugins/GuardianAC/config.yml').write_text(config)
    validate_fixture(directory, EXPECTED)
    result = {'case': 'offline-' + requested_type.lower() + '-uuid-fallback', 'started_at': utc(),
              'server': 'Paper 26.3', 'plugin_sha256': sha(directory / 'plugins/GuardianAC.jar'),
              'server_sha256': sha(directory / 'server.jar'), 'fixture_sha256': sha(helper),
              'config_sha256': sha(directory / 'plugins/GuardianAC/config.yml'),
              'requested_ban_type': requested_type, 'expected_record_type': 'UUID',
              'ip_duration_hours': 1, 'uuid_duration_hours': 1, 'allow_permanent_ip': False,
              'actor_source': ADDRESS, 'separate_source': OTHER_ADDRESS,
              'client_implementation': 'Independent real TCP protocol777 minimal client; not rendered Mojang client',
              'synthetic_trust_setup': False,
              'trust_setup_scope': 'Two actual joined offline UUIDs at one loopback source; no trusted associations seeded and no Microsoft authentication validation',
              'recheck_limit': 'Live offline fallback validates identity protection; it does not isolate the later transaction race recheck',
              'assertions': []}
    clients = []
    peers = []
    server = None

    def check(test, passed, detail=None):
        entry = {'test': test, 'passed': bool(passed)}
        if detail is not None:
            entry['detail'] = detail
        result['assertions'].append(entry)
        print('GACIPTEST ' + json.dumps(entry), flush=True)
        return passed

    def command(command):
        start = server.cmd('gacip ' + command)
        server.expect('GACIP COMMAND ' + command.split()[0] + ' DONE', timeout=20, start=start)

    def report(label):
        command('report ' + label)
        return json.loads((directory / 'plugins/RealClientFixture' / (label + '.json')).read_text())

    def pump(client, duration):
        client.pump(duration)
        for peer in peers:
            if peer is not client and not peer.closed:
                peer.pump(.001)

    def ticks(client, count):
        for _ in range(count):
            if client.closed or client.disconnect:
                break
            client.tick()
            for peer in peers:
                if peer is not client and not peer.closed:
                    peer.pump(.001)

    def client(name, source):
        connection = Client(PORT, name, source_host=source)
        clients.append(connection)
        return connection

    def admission(name, source, allowed, label, case_id=None):
        connection = client(name, source)
        joined = connection.wait_join()
        if allowed:
            ticks(connection, 40)
            snapshot = report(label)
            player = next((p for p in snapshot['players'] if p['name'] == name), None)
            check(label + '_admitted', joined and not connection.closed and connection.disconnect is None)
            check(label + '_exact_source_and_protocol', player is not None and player['address'] == source and player['exactProtocol'])
            check(label + '_zero_findings', player is not None and player['findings'] == 0)
        else:
            check(label + '_denied_before_play', not joined and not connection.joined and connection.disconnect is not None)
            reason = ' '.join(str(event.get('disconnect', '')) for event in connection.events)
            check(label + '_same_case_not_generic_failure', case_id is not None and case_id in reason, reason)
        result[label] = connection.report()
        connection.close()
        time.sleep(.3)

    def verify_durable(storage, suffix):
        check('database_integrity_' + suffix, storage['integrity'] == ['ok'] and not storage['foreign_key_violations'])
        bans = storage['bans']
        check('exactly_one_synthetic_actor_ban_' + suffix,
              len(bans) == 1 and bans[0]['player_name'] == 'GacIpBad' and bans[0]['player_uuid'] == str(offline_uuid('GacIpBad')))
        if len(bans) != 1:
            return None
        ban = bans[0]
        check('automatic_scope_' + suffix, ban['type'] == 'UUID' and ban['actor'] == 'GuardianAC automatic evidence policy')
        duration = ban['expires_at'] - ban['created_at'] if ban['expires_at'] else None
        check('temporary_one_hour_not_permanent_' + suffix, ban['permanent'] == 0 and duration is not None and 3_590_000 <= duration <= 3_600_000, duration)
        check('exact_ip_protection_scope_' + suffix, not ban['protected_ip_present'] and not ban['ip_lookup_present'])
        check('no_revocation_' + suffix, all(ban[key] is None for key in ('revoked_at', 'uuid_revoked_at', 'ip_revoked_at')))
        supporting = [e for e in storage['evidence'] if e['id'] == ban['evidence_id']]
        check('linked_evidence_integrity_' + suffix, len(supporting) == 1 and supporting[0]['hash_matches'] and supporting[0]['payload_bytes_match'] and supporting[0]['case_id'] == ban['case_id'] and supporting[0]['player_uuid'] == ban['player_uuid'])
        if supporting:
            evidence = supporting[0]['data']
            findings = evidence.get('findings', [])
            check('real_slot15_and_pitch100_independent_evidence_' + suffix,
                  evidence.get('families', 0) >= 2 and
                  any(f['hard'] and f['observation']['measurements'].get('held-slot') == 15 for f in findings) and
                  any(f['hard'] and f['observation']['measurements'].get('pitch') == 100 for f in findings))
        check('all_evidence_hashes_and_lengths_' + suffix, all(e['hash_matches'] and e['payload_bytes_match'] for e in storage['evidence']))
        native = storage['native_players']
        check('exact_owned_native_uuid_' + suffix, len(native) == 1 and native[0]['uuid'] == ban['player_uuid'] and native[0]['source'] == ban['ownership_marker'] and native_expiry_matches(native[0], ban['expires_at']))
        native_ips = storage['native_ips']
        check('offline_never_native_ip_banned_' + suffix, not native_ips)
        trusted = {item['player_uuid'] for item in storage['observations'] if item['trusted'] == 1}
        check('no_synthetic_or_authenticated_trust_claim_' + suffix, not trusted)
        observed = {item['player_uuid'] for item in storage['observations']}
        check('actual_both_same_ip_identities_observed_' + suffix, {str(offline_uuid(n)) for n in ('GacIpBad', 'GacIpShared')} <= observed)
        check('ip_associations_use_lookup_hashes_' + suffix, all(item['lookup_length'] == 64 for item in storage['observations']))
        advisories = [e['data'] for e in storage['evidence'] if e['data'].get('type') == 'restricted-ip-login-advisory']
        check('same_ip_never_inferred_as_same_person_' + suffix, all(a.get('provenEvasion') is False and a.get('matchedCase') == ban['case_id'] for a in advisories))
        check('safe_shared_address_has_no_restriction_advisory_' + suffix, not advisories)
        return ban

    try:
        server = Server(directory, 'automatic-ip')
        server.expect('Done \\(')
        check('native_telemetry_enabled', any('internal development build enabled:' in line for line in server.lines))
        server.cmd('difficulty peaceful')
        command('setup')
        initial = report('initial')
        check('expected_initial_trusted_count', initial['trustedAssociations'] == 0 and initial['observedAssociations'] == 0 and initial['targetTrustedAssociation'] is False and initial['nativeOnlineMode'] is False)
        check('initial_packet_metrics_zero', initial['cancelled'] == 0 and initial['malformed'] == 0)
        admission('GacIpOther', OTHER_ADDRESS, True, 'clean-before')
        peer = client('GacIpShared', ADDRESS)
        if not check('untrusted_same_ip_peer_join', peer.wait_join()):
            raise RuntimeError('Clean same-IP peer could not join')
        peers.append(peer)
        ticks(peer, 40)
        peer_before = report('shared-peer-before')
        observed_peer = next((p for p in peer_before['players'] if p['name'] == 'GacIpShared'), None)
        check('first_actual_same_ip_observation_untrusted', peer_before['observedAssociations'] == 1 and peer_before['trustedAssociations'] == 0 and peer_before['targetTrustedAssociation'] is False)
        check('same_ip_peer_clean_exact_source', observed_peer is not None and observed_peer['address'] == ADDRESS and observed_peer['exactProtocol'] and observed_peer['findings'] == 0)
        bad = client('GacIpBad', ADDRESS)
        if not check('malformed_actor_join', bad.wait_join()):
            raise RuntimeError('Fixture actor could not join')
        ticks(bad, 100)
        before = report('before-malformed')
        actor = next((p for p in before['players'] if p['name'] == 'GacIpBad'), None)
        check('actor_exact_source_and_protocol', actor is not None and actor['address'] == ADDRESS and actor['exactProtocol'] and actor['findings'] == 0)
        result['before_malformed_snapshot'] = before
        check('two_actual_same_ip_actors_untrusted', before['observedAssociations'] == 2 and before['trustedAssociations'] == 0 and before['targetTrustedAssociation'] is False and before['nativeOnlineMode'] is False and {p['name'] for p in before['players'] if p['address'] == ADDRESS} == {'GacIpBad', 'GacIpShared'})
        bad.send('SET_CARRIED_ITEM', struct.pack('>h', 3)); pump(bad, .2)
        bad.send('SET_CARRIED_ITEM', struct.pack('>h', 15)); pump(bad, .2)
        bad.send('MOVE_PLAYER_ROT', struct.pack('>ffB', 0, 100, 1)); pump(bad, .2)
        after = report('isolated-malformed')
        actor = next((p for p in after['players'] if p['name'] == 'GacIpBad'), None)
        check('two_isolated_invalid_packets_cancelled', after['cancelled'] - before['cancelled'] == 2 and after['malformed'] - before['malformed'] == 2)
        check('isolated_invalid_not_standalone_kick', not bad.closed and bad.disconnect is None)
        check('isolated_invalid_not_applied_to_state', actor is not None and actor['slot'] == 3 and abs(actor['pitch']) <= 90)
        cycles = 0
        for _ in range(50):
            if bad.closed or bad.disconnect:
                break
            bad.send('SET_CARRIED_ITEM', struct.pack('>h', 15)); pump(bad, .15)
            if bad.closed or bad.disconnect:
                break
            bad.send('MOVE_PLAYER_ROT', struct.pack('>ffB', 0, 100, 1)); pump(bad, .45)
            cycles += 1
        result['malformed_cycles'] = cycles
        result['malformed_actor'] = bad.report()
        check('bounded_independent_wire_evidence_automatically_enforced', bad.disconnect is not None and 0 < cycles <= 50)
        check('bounded_wire_packet_count', bad.sent['game:SET_CARRIED_ITEM'] <= 52 and bad.sent['game:MOVE_PLAYER_ROT'] <= 51)
        bad.close()
        settled = report('after-enforcement')
        check('packet_queue_settled_without_drops', settled['queued'] == 0 and settled['dropped'] == 0)
        clean_peer = next((p for p in settled['players'] if p['name'] == 'GacIpShared'), None)
        check('same_ip_peer_survives_other_uuid_sanction', not peer.closed and peer.disconnect is None and clean_peer is not None and clean_peer['findings'] == 0 and clean_peer['address'] == ADDRESS)
        result['same_ip_peer_during_enforcement'] = peer.report()
        peer.close()
        time.sleep(.3)
        storage = inspect_storage(directory)
        if len(storage['bans']) != 1:
            raise RuntimeError('Expected one durable automatic sanction before admission tests')
        case_id = storage['bans'][0]['case_id']
        admission('GacIpBad', OTHER_ADDRESS, False, 'uuid-denied-other-ip', case_id)
        admission('GacIpShared', ADDRESS, True, 'same-ip-peer-rejoin', case_id)
        admission('GacIpSame', ADDRESS, True, 'same-ip-new-uuid', case_id)
        admission('GacIpOther', OTHER_ADDRESS, True, 'other-ip-clean')
        stop_server(result, server, 'initial_server_exit')
        check('initial_normal_stop', normal_exit(result['initial_server_exit']) and no_errors(result))
        server = None
        if not stops_passed(result, ('initial_server_exit',)) or not assertions_passed(result['assertions']):
            raise RuntimeError('Initial IP phase failed or did not stop normally; restart skipped')
        first_storage = inspect_storage(directory)
        result['initial_storage'] = first_storage
        first_ban = verify_durable(first_storage, 'initial')
        if not ip_first_phase_allows_restart(result):
            raise RuntimeError('Initial IP phase assertions incomplete or failed; restart skipped')
        server = Server(directory, 'automatic-ip-restart')
        server.expect('Done \\(')
        admission('GacIpBad', OTHER_ADDRESS, False, 'restart-uuid-denied', case_id)
        admission('GacIpShared', ADDRESS, True, 'restart-same-ip-peer', case_id)
        admission('GacIpSame', ADDRESS, True, 'restart-same-ip-new-uuid', case_id)
        admission('GacIpOther', OTHER_ADDRESS, True, 'restart-other-ip-clean')
        stop_server(result, server, 'restart_server_exit')
        check('restart_normal_stop', normal_exit(result['restart_server_exit']) and no_errors(result))
        server = None
        if not stops_passed(result, ('initial_server_exit', 'restart_server_exit')):
            raise RuntimeError('Final storage inspection unavailable: normal-stop/result gate did not pass; database was not read')
        final_storage = inspect_storage(directory)
        result['final_storage'] = final_storage
        final_ban = verify_durable(final_storage, 'restart')
        check('restart_preserves_exact_sanction_and_ownership', first_ban is not None and final_ban == first_ban)
        check('immutable_artifact_unchanged', sha(directory / 'plugins/GuardianAC.jar') == EXPECTED)
        logs = '\n'.join(p.read_text() for p in directory.parent.glob('paper-26.3-*.log'))
        check('no_guardian_or_fixture_errors', not re.search(r'GACIP ERROR|(?:ERROR|SEVERE).*\[(?:GuardianAC|RealClientFixture)\]|\[(?:GuardianAC|RealClientFixture)\].*(?:SEVERE|ERROR)|GuardianAC disabled|Security database is busy|セキュリティデータベースが混み合っています。|Ban persistence failed', logs))
        result['run_completed'] = True
    except (Exception, KeyboardInterrupt) as failure:
        result['error'] = repr(failure)
        result['traceback'] = traceback.format_exc()
        print(result['traceback'], flush=True)
    finally:
        close_clients(result, clients)
        if server is not None:
            stop_server(result, server, 'cleanup_server_exit')
        result['finished_at'] = utc()
        result['passed'] = ip_case_passed(result)
        output = Path(os.environ['GUARDIAN_IP_OUTPUT']) / run
        output.mkdir(parents=True, exist_ok=True)
        write_report(output / ('offline-' + requested_type.lower() + '.json'), result)
        print('GACIP CASE RESULT ' + json.dumps({k: result[k] for k in ('case', 'passed', 'plugin_sha256', 'started_at', 'finished_at')}), flush=True)
    return result


def main():
    run = sys.argv[1] if len(sys.argv) == 2 else ''
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', run):
        raise ValueError('Provide a fresh bounded run name')
    if not re.fullmatch(r'[0-9a-f]{64}', EXPECTED) or EXPECTED == HISTORICAL_UNSAFE or os.environ.get('GUARDIAN_PLUGIN_SHA256') != EXPECTED:
        raise RuntimeError('This evidence fixture is pinned to the exact reviewed immutable artifact')
    cases = []
    for requested_type in ('IP', 'DUAL'):
        case = run_case(run, requested_type)
        cases.append(case)
        # Do not try another server after a blocked launch or failed first case.
        if case.get('passed') is not True or not ip_case_passed(case):
            break
    summary = {'run': run, 'plugin_sha256': EXPECTED, 'passed': len(cases) == 2 and all(case.get('passed') is True and ip_case_passed(case) for case in cases),
               'case_pair_completed': len(cases) == 2,
               'second_case_skipped_after_failure': len(cases) < 2,
               'passed_assertions': sum(sum(a['passed'] for a in case['assertions']) for case in cases),
               'total_assertions': sum(len(case['assertions']) for case in cases),
               'scope': 'Two isolated offline Paper26.3 cases, two actual untrusted same-IP TCP actors, IP and DUAL requests both safely fall back to temporary UUID-only',
               'limits': ['No real accounts, addresses, players, webhook interactions, or production changes',
                          'Offline synthetic identities do not validate Microsoft authentication',
                          'Live offline fallback does not isolate the late transaction race recheck',
                          'One-hour expiration timestamp verified; does not wait one real hour',
                          'Different UUIDs at one address are not treated as one person or grounds for extra sanctions',
                          'Paper26.3 only for this additional fixture; not rendered vanilla client'],
               'cases': [{'case': case['case'], 'passed': case['passed'], 'error': case.get('error')} for case in cases]}
    write_report(Path(os.environ['GUARDIAN_IP_OUTPUT']) / run / 'summary.json', summary)
    print(json.dumps(summary, indent=2), flush=True)
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
