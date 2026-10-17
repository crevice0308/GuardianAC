"""Pure fail-closed sequencing gates; importing this module starts no work."""

FIRST_PHASE_ASSERTIONS = frozenset({
    'GacRealClean_join',
    'clean_trace_connected',
    'clean_trace_zero_findings',
    'clean_trace_zero_malformed_or_cancelled',
    'ore_fixture_chunk_received',
    'far_outgoing_ore_masked',
    'world_ore_not_mutated',
    'near_ore_authoritative_reveal',
    'reveal_does_not_mutate_world',
    'GacRealBad_join',
    'invalid_slot_and_pitch_cancelled',
    'isolated_invalid_packets_not_standalone_kick',
    'invalid_packets_do_not_change_player_state',
    'repeated_independent_hard_evidence_enforced',
    'native_packet_context_bound_without_capture_failures',
    'ban_persisted_with_evidence',
    'clean_not_banned',
})
COMPLETE_SUITE_ASSERTIONS = FIRST_PHASE_ASSERTIONS | {
    'persisted_ban_denies_rejoin', 'clean_rejoin_after_restart',
}


def first_phase_allows_restart(result):
    """A saved BAN alone never authorizes cascading past a failed first phase."""
    if not isinstance(result, dict):
        return False
    if any(key in result for key in ('error', 'traceback', 'shutdown_error')):
        return False
    if type(result.get('server_exit')) is not int or result['server_exit'] != 0:
        return False
    assertions = result.get('assertions')
    if not isinstance(assertions, list) or len(assertions) != len(FIRST_PHASE_ASSERTIONS):
        return False
    if any(not isinstance(row, dict) or row.get('passed') is not True
           or not isinstance(row.get('test'), str) for row in assertions):
        return False
    if {row['test'] for row in assertions} != FIRST_PHASE_ASSERTIONS:
        return False
    records = result.get('ban_records')
    return isinstance(records, list) and any(
        isinstance(row, dict) and row.get('player_name') == 'GacRealBad'
        and bool(row.get('evidence_id')) for row in records)


def complete_suite_passed(result):
    """Only all intended checks and both normal stops form a passing report."""
    if not isinstance(result, dict) or 'restart_skipped' in result:
        return False
    if type(result.get('restart_exit')) is not int or result['restart_exit'] != 0:
        return False
    assertions = result.get('assertions')
    if not isinstance(assertions, list) or len(assertions) != len(COMPLETE_SUITE_ASSERTIONS):
        return False
    if any(not isinstance(row, dict) or row.get('passed') is not True
           or not isinstance(row.get('test'), str) for row in assertions):
        return False
    if {row['test'] for row in assertions} != COMPLETE_SUITE_ASSERTIONS:
        return False
    first_phase = dict(result, assertions=[row for row in assertions
                                         if row['test'] in FIRST_PHASE_ASSERTIONS])
    return first_phase_allows_restart(first_phase)
