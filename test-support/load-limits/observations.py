"""Pure result checks for the bounded loopback load/connection fixture."""
import ipaddress
import re
from pathlib import Path
from terminal_gates import report_passed

CLEANUP_FIELDS = (
    'enginePlayers', 'telemetryPlayers', 'packetPlayers', 'packetQueue',
    'replayPlayers', 'replayBytes', 'hiddenPlayers', 'hiddenPositions', 'xrayViewers',
)

def source_address(index):
    if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < 64:
        raise ValueError('Only the fixed 64-client loopback range is authorized')
    result = '127.0.0.' + str(index + 2)
    if not ipaddress.ip_address(result).is_loopback:
        raise ValueError('Source address must be loopback')
    return result

def denied_joins(status):
    matches = re.findall(r'\| denied joins (\d+)', status)
    if len(matches) != 1:
        raise ValueError('Exactly one native GuardianAC status counter is required')
    return int(matches[0])

def has_rate_denial(report):
    raw = report.get('disconnect')
    if not isinstance(raw, str):
        return False
    try:
        payload = bytes.fromhex(raw)
        return any(reason.encode('utf-8') in payload for reason in (
            'Connection rate limit. Please retry shortly.',
            '接続頻度の上限に達しました。少し待ってから再接続してください。',
        ))
    except ValueError:
        return False

def burst_outcome(report):
    if has_rate_denial(report):
        return 'rate-denied'
    if report.get('joined') and not report.get('closed') and not report.get('disconnect'):
        return 'accepted'
    return 'unaccounted'

def all_cleaned(observation):
    return observation.get('players') == [] and all(observation.get(k) == 0 for k in CLEANUP_FIELDS)

def all_masked(viewers, expected_count, stone_id):
    return (len(viewers) == expected_count and expected_count > 0
            and all(v.get('retained_all') and v.get('states')
                    and all(state == stone_id for state in v['states']) for v in viewers.values()))

def successful(report):
    return report_passed(report)


def primary_database(plugin_directory):
    """Select production's authoritative DB; rotating backups are not competing DBs."""
    directory = Path(plugin_directory)
    database = directory / 'bans' / 'bans.db'
    if any(p.is_symlink() for p in (directory, directory / 'bans', database)) or not database.is_file():
        raise RuntimeError('Expected the isolated GuardianAC primary bans/bans.db without symlinks')
    return database
