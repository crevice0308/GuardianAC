"""Inert fail-closed report gates and exception-safe fixture cleanup helpers.

No imports of server_harness, subprocesses, sockets, or fixture preparation.
Only Server.stop() may classify a stop as normal; its effective status, never
an unchecked raw child return code, is used by these gates.
"""

from collections import Counter
import json
import os
from pathlib import Path
import tempfile


def write_report(path, report, *, ensure_ascii=True):
    """Durably replace one JSON snapshot; a failed write keeps its predecessor.

    The temporary file is exclusively created beside the destination so replace
    stays on one filesystem. A failed serialization/write/replace removes only
    that unpublished temporary file; the prior report is never truncated.
    """
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            json.dump(report, output, indent=2, ensure_ascii=ensure_ascii)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def normal_exit(value):
    """None, False, floats and textual zero are not a witnessed normal exit."""
    return type(value) is int and value == 0


def normal_lifecycle(snapshot):
    """Only an observed requested normal stop is admissible lifecycle evidence."""
    return (isinstance(snapshot, dict) and snapshot.get('state') == 'normal_stop'
            and snapshot.get('normal_stop') is True
            and snapshot.get('termination_observed') is True
            and snapshot.get('terminal') is True
            and snapshot.get('reason') == 'requested_stop'
            and normal_exit(snapshot.get('child_returncode'))
            and normal_exit(snapshot.get('reported_returncode')))


def no_errors(report):
    if not isinstance(report, dict):
        return False
    for key, value in report.items():
        if not isinstance(key, str):
            return False
        if (key in ('error', 'traceback', 'termination') or key.endswith('_error')):
            return False
        if key.endswith('_errors') and value:
            return False
    return True


def assertions_passed(rows, expected_tests=None):
    if not isinstance(rows, list) or not rows:
        return False
    if any(not isinstance(row, dict) or row.get('passed') is not True
           or not isinstance(row.get('test'), str) or not row['test'] for row in rows):
        return False
    if expected_tests is not None:
        return (len(rows) == len(expected_tests)
                and Counter(row['test'] for row in rows) == Counter(expected_tests))
    return len({row['test'] for row in rows}) == len(rows)


def stops_passed(report, exit_keys):
    return (no_errors(report) and bool(exit_keys)
            and all(normal_exit(report.get(key))
                    and normal_lifecycle(report.get(key + '_lifecycle')) for key in exit_keys))


def report_passed(report, exit_keys=('server_exit',), expected_tests=None):
    """Require complete work, every intended stop, and non-vacuous true checks."""
    return (stops_passed(report, exit_keys) and report.get('run_completed') is True
            and assertions_passed(report.get('assertions'), expected_tests))


def record_error(report, field, error):
    """Keep every cleanup error without erasing the original run failure."""
    if field not in report:
        report[field] = []
    elif not isinstance(report[field], list):
        report[field] = [report[field]]
    report[field].append(repr(error))


def close_clients(report, clients, reports_key=None, report_client=None):
    """One broken client report/close must not skip other closes or server stop."""
    if reports_key is not None:
        report[reports_key] = []
    for client in clients:
        if client is None:
            continue
        if reports_key is not None:
            try:
                report[reports_key].append(client.report() if report_client is None else report_client(client))
            except (Exception, KeyboardInterrupt) as error:
                record_error(report, 'client_report_error', error)
        try:
            client.close()
        except (Exception, KeyboardInterrupt) as error:
            record_error(report, 'client_close_error', error)


def stop_server(report, server, exit_key='server_exit'):
    """Preserve an unknown status/error and continue to final report writing."""
    report[exit_key] = None
    if server is None:
        return None
    try:
        report[exit_key] = server.stop()
    except (Exception, KeyboardInterrupt) as error:
        record_error(report, 'shutdown_error', error)
    try:
        report[exit_key + '_lifecycle'] = server.shutdown_result
    except (Exception, KeyboardInterrupt) as error:
        record_error(report, 'shutdown_error', error)
    if not normal_exit(report[exit_key]) or not normal_lifecycle(report.get(exit_key + '_lifecycle')):
        record_error(report, 'shutdown_error', RuntimeError('No proven requested normal server stop: ' + exit_key))
    return report[exit_key]


def matrix_passed(report):
    """Both nonempty matrix phases and their two normal stops are required."""
    if not isinstance(report, dict):
        return False
    permanent_ip = report.get('permanent_ip')
    return (isinstance(permanent_ip, dict)
            and all(permanent_ip.get(key) is True for key in (
                'synthetic_real_inventory_positive_reachable',
                'default_real_offline_client_refused',
                'protected_address_not_plaintext_in_sqlite'))
            and assertions_passed(report.get('first_results'), MATRIX_FIRST_ASSERTIONS)
            and assertions_passed(report.get('restart_results'), MATRIX_RESTART_ASSERTIONS)
            and report_passed(dict(report, assertions=report['first_results'] + report['restart_results']),
                              ('first_stop', 'stop'), MATRIX_FIRST_ASSERTIONS + MATRIX_RESTART_ASSERTIONS))


def boundaries_passed(report, expected_tests):
    if not no_errors(report) or report.get('run_completed') is not True:
        return False
    tests = report.get('tests')
    stops = report.get('stops')
    return (assertions_passed(tests, expected_tests)
            and [row['test'] for row in tests] == list(expected_tests)
            and isinstance(stops, list) and len(stops) == 3
            and all(isinstance(row, dict) and no_errors(row)
                    and normal_exit(row.get('exit_code'))
                    and normal_lifecycle(row.get('exit_code_lifecycle')) for row in stops)
            and [row.get('phase') for row in stops] == ['native-enabled', 'corrupt-database', 'recovered-database']
            and report.get('seed_database_unchanged') is True
            and normal_exit(report.get('restore_exit')))


def ip_case_passed(report):
    stops = ('initial_server_exit', 'restart_server_exit')
    if isinstance(report, dict) and 'cleanup_server_exit' in report:
        stops += ('cleanup_server_exit',)
    return report_passed(report, stops, IP_CASE_ASSERTIONS)


def ip_first_phase_allows_restart(report):
    return (stops_passed(report, ('initial_server_exit',))
            and assertions_passed(report.get('assertions'), IP_FIRST_ASSERTIONS))


# Expected identities are a coverage schema, not evidence that current bytes ran.
# Matrix identities were cross-checked against both preserved 328-row Paper/Purpur
# reports and the unchanged Java producer sources pinned below. Seven repeated
# GUI-click names are intentional; exact multiplicities are required for them.
# IP identities match the current run_case/admission/verify_durable call paths.
MATRIX_FIRST_ASSERTIONS = (
    'runtime_supported',
    'plugin_enabled',
    'rules_at_least_61',
    'all_bukkit_listener_families',
    'native_xray_config_readable',
    'command_no_permission_denied',
    'command_view_cannot_reload',
    'command_view_cannot_unban',
    'command_unbanip_rejects_hex_hostname',
    'unauthorized_tab_empty',
    'operator_bypass_default_false',
    'native_uuid_marker',
    'native_ip_marker',
    'sync_preserves_foreign_uuid',
    'sync_preserves_foreign_ip',
    'unban_preserves_foreign_uuid',
    'unban_preserves_foreign_ip',
    'evidence_saved',
    'consistent_backup_written',
    'login_denies_committed_uuid_ban',
    'login_allows_clean_fixture',
    'login_preserves_other_denial',
    'gui_54_slots',
    'gui_custom_holder',
    'gui_cancel_SHIFT_LEFT',
    'gui_cancel_NUMBER_KEY',
    'gui_cancel_DOUBLE_CLICK',
    'gui_cancel_DROP',
    'gui_cancel_SWAP_OFFHAND',
    'gui_cancel_LEFT',
    'gui_drag_cancelled',
    'gui_view_cannot_mutate',
    'gui_admin_can_change_setting',
    'gui_admin_setting_persisted',
    'gui_delayed_admin_permission_rechecked',
    'gui_ip_case_button_present',
    'gui_ip_case_54_slots',
    'gui_ip_case_hides_address',
    'gui_ip_transfer_SHIFT_LEFT_cancelled',
    'gui_ip_transfer_NUMBER_KEY_cancelled',
    'gui_ip_transfer_DOUBLE_CLICK_cancelled',
    'gui_ip_transfer_DROP_cancelled',
    'gui_ip_transfer_SWAP_OFFHAND_cancelled',
    'gui_ip_drag_cancelled',
    'gui_ip_click_37_cancelled',
    'gui_ip_view_only_cannot_request',
    'gui_ip_click_37_cancelled',
    'gui_ip_request_permission_rechecked',
    'gui_ip_other_actor_cannot_consume',
    'gui_ip_close_invalidates_token',
    'gui_ip_click_46_cancelled',
    'gui_ip_confirm_permission_rechecked',
    'gui_ip_rejected_confirmation_not_replayable',
    'gui_ip_click_50_cancelled',
    'gui_ip_preview_pagination_keeps_same_token',
    'gui_ip_preview_page_has_exact_last_case',
    'gui_ip_preview_pagination_stays_54_slots',
    'gui_ip_paged_close_invalidates_token',
    'gui_ip_click_37_cancelled',
    'gui_ip_click_opens_ip_confirmation',
    'gui_ip_confirmation_hides_address',
    'gui_ip_click_46_cancelled',
    'gui_ip_dual_retains_uuid_only',
    'gui_ip_existing_same_ip_case_unchanged',
    'gui_ip_later_same_ip_case_unchanged',
    'gui_ip_uuid_native_remains',
    'gui_ip_native_same_ip_survivor_preserved',
    'gui_ip_audit_exact_actor_scope_reason',
    'gui_ip_evidence_preserved',
    'gui_ip_token_one_use',
    'gui_ip_revoked_ip_button_disabled',
    'gui_ip_click_37_cancelled',
    'gui_ip_click_46_cancelled',
    'gui_ip_owned_native_ip_removed',
    'gui_ip_owned_native_uuid_preserved',
    'gui_ip_owned_dual_uuid_still_active',
    'gui_ip_click_37_cancelled',
    'gui_ip_click_46_cancelled',
    'gui_ip_foreign_native_ip_untouched',
    'gui_ip_foreign_case_uuid_preserved',
    'gui_confirmation_token_created',
    'gui_closed_confirmation_invalidated',
    'gui_closed_confirmation_keeps_ban',
    'packet_events_runtime_version_26_3',
    'chunk_rewrite_requested',
    'chunk_ore_concealed',
    'chunk_original_not_mutated',
    'chunk_heightmaps_preserved',
    'chunk_ledger_inactive_before_send',
    'chunk_reencoded_nonempty',
    'chunk_wire_roundtrip_ore_hidden',
    'chunk_wire_roundtrip_biomes',
    'chunk_ledger_active_after_send',
    'chunk_native_antixray_skips_rewrite',
    'chunk_unknown_native_state_skips',
    'packet_unobserved_protocol_ineligible',
    'packet_valid_held_slot_allowed',
    'packet_native_protocol_eligible',
    'packet_invalid_held_slot_cancelled',
    'packet_truncated_decode_cancelled',
    'packet_ingress_drains_on_main',
    'packet_non_target_not_decoded',
    'packet_non_target_not_cancelled',
    'packet_non_target_not_malformed',
    'packet_non_target_no_evidence_emitted',
    'packet_non_target_event_heuristics_ineligible',
    'packet_unknown_not_decoded',
    'packet_unknown_not_cancelled',
    'packet_unknown_not_malformed',
    'packet_unknown_no_evidence_emitted',
    'packet_unknown_event_heuristics_ineligible',
    'packet_session_old_sample_enqueued',
    'packet_session_same_uuid_world_replaces_identity',
    'packet_session_replacement_protocol_unobserved',
    'packet_session_old_owner_queue_dropped',
    'packet_session_new_owner_protocol_observed',
    'packet_session_old_receive_unread',
    'packet_session_old_receive_not_cancelled',
    'packet_session_old_receive_not_malformed',
    'packet_session_old_receive_no_evidence',
    'packet_session_old_receive_preserves_protocol',
    'packet_session_old_player_forget_preserves_new_owner',
    'packet_session_xray_ledger_seeded',
    'packet_session_old_disconnect_preserves_new_owner',
    'packet_session_old_disconnect_preserves_protocol',
    'packet_session_old_disconnect_preserves_xray_ledger',
    'packet_session_current_disconnect_defers_state_cleanup',
    'packet_session_current_disconnect_ineligible',
    'packet_session_current_disconnect_hides_context',
    'packet_session_current_disconnect_defers_xray_cleanup',
    'packet_session_retired_user_not_readmitted',
    'packet_session_retired_receive_ignored',
    'packet_session_current_disconnect_main_cleanup',
    'packet_session_current_disconnect_xray_cleanup',
    'packet_session_closed_user_not_readmitted_after_reap',
    'packet_disconnect_context_forgotten',
    'packet_missing_protocol_ineligible',
    'settings_runtime_startup_nondefault_capacity_from_pinned_config',
    'settings_runtime_engine_uses_validated_startup_capacity',
    'settings_runtime_telemetry_uses_same_validated_startup_capacity',
    'settings_runtime_explicit_telemetry_capacity_overrides_plugin_cache',
    'settings_runtime_legacy_four_argument_telemetry_ctor_retained',
    'settings_runtime_tuning_on_low_tps_applies_immediately',
    'settings_runtime_tuning_off_resets_immediately',
    'settings_runtime_repeated_off_apply_clears_stale_multiplier',
    'settings_runtime_disabled_periodic_pulse_resets_multiplier',
    'settings_runtime_xray_on_real_break_emits_to_isolated_sink',
    'settings_runtime_xray_on_enclosed_ore_candidate_recorded',
    'settings_runtime_xray_on_mining_history_grows',
    'settings_runtime_xray_master_off_reaches_production_service',
    'settings_runtime_xray_off_clears_mining_and_candidate_history',
    'settings_runtime_xray_off_real_break_has_no_sink_growth',
    'settings_runtime_xray_reenable_starts_fresh_history',
    'settings_runtime_mining_analysis_off_also_stops_sink',
    'advanced_settings_reachable_from_system_settings',
    'advanced_settings_view_only_can_inspect_but_not_edit',
    'advanced_settings_view_only_cannot_save',
    'advanced_settings_adjustment_is_unsaved',
    'advanced_settings_save_rechecks_admin_at_dispatch',
    'advanced_settings_unrelated_change_invalidates_draft',
    'advanced_settings_reload_invalidates_unsaved_draft',
    'advanced_settings_close_invalidates_queued_save',
    'advanced_settings_same_uuid_wrong_player_cannot_edit',
    'advanced_settings_max-players_labels_and_54_slots',
    'advanced_settings_max-players_minimum_is_clamped_and_unsaved',
    'advanced_settings_max-players_maximum_is_clamped_and_unsaved',
    'advanced_settings_max-players_default_staged_only',
    'advanced_settings_max-players_explicit_save_persisted',
    'advanced_settings_max-players_restart_pending_visible',
    'advanced_settings_saved_capacity_does_not_reallocate_running_engine',
    'advanced_settings_packets-per-tick_labels_and_54_slots',
    'advanced_settings_packets-per-tick_minimum_is_clamped_and_unsaved',
    'advanced_settings_packets-per-tick_maximum_is_clamped_and_unsaved',
    'advanced_settings_packets-per-tick_default_staged_only',
    'advanced_settings_packets-per-tick_explicit_save_persisted',
    'advanced_settings_packet_budget_live_value_visible',
    'advanced_settings_evidence-mib_labels_and_54_slots',
    'advanced_settings_evidence-mib_minimum_is_clamped_and_unsaved',
    'advanced_settings_evidence-mib_maximum_is_clamped_and_unsaved',
    'advanced_settings_evidence-mib_default_staged_only',
    'advanced_settings_evidence-mib_explicit_save_persisted',
    'advanced_settings_evidence-mib_restart_pending_visible',
    'advanced_settings_evidence-days_labels_and_54_slots',
    'advanced_settings_evidence-days_minimum_is_clamped_and_unsaved',
    'advanced_settings_evidence-days_maximum_is_clamped_and_unsaved',
    'advanced_settings_evidence-days_default_staged_only',
    'advanced_settings_evidence-days_explicit_save_persisted',
    'advanced_settings_evidence-days_restart_pending_visible',
    'advanced_settings_replay-total-mib_labels_and_54_slots',
    'advanced_settings_replay-total-mib_minimum_is_clamped_and_unsaved',
    'advanced_settings_replay-total-mib_maximum_is_clamped_and_unsaved',
    'advanced_settings_replay-total-mib_default_staged_only',
    'advanced_settings_replay-total-mib_explicit_save_persisted',
    'advanced_settings_replay-total-mib_restart_pending_visible',
    'advanced_settings_replay-per-player-kib_labels_and_54_slots',
    'advanced_settings_replay-per-player-kib_minimum_is_clamped_and_unsaved',
    'advanced_settings_replay-per-player-kib_maximum_is_clamped_and_unsaved',
    'advanced_settings_replay-per-player-kib_default_staged_only',
    'advanced_settings_replay-per-player-kib_explicit_save_persisted',
    'advanced_settings_replay-per-player-kib_restart_pending_visible',
    'permanent_ip_default_offline_real_actor_present',
    'permanent_ip_default_offline_environment_refused',
    'permanent_ip_default_offline_tcp_observation_untrusted',
    'permanent_ip_default_offline_production_controller_denied',
    'telemetry_lifecycle_real_actor_online',
    'telemetry_new_adapter_has_no_sessions',
    'telemetry_start_admits_existing_online',
    'telemetry_existing_actor_observation_is_admitted',
    'telemetry_reset_retains_admission',
    'telemetry_close_clears_all_sessions',
    'telemetry_late_close_after_shutdown_no_resurrection',
    'telemetry_late_held_after_shutdown_no_resurrection',
    'telemetry_late_teleport_after_shutdown_no_resurrection',
    'telemetry_reset_after_shutdown_no_resurrection',
    'telemetry_closed_adapter_emits_no_samples',
    'telemetry_restart_readmits_existing_online',
    'telemetry_forget_closes_identity_session',
    'telemetry_late_events_after_forget_no_resurrection',
    'telemetry_fixture_final_cleanup_empty',
    'permanent_ip_candidate_1_guard_once_uuid_only',
    'permanent_ip_synthetic_v2_hard_proof_sufficient',
    'permanent_ip_candidate_not_ip_enforcement',
    'permanent_ip_protected_provenance_exact_binding',
    'permanent_ip_ordinary_records_hide_exact_ip',
    'permanent_ip_view_only_denied',
    'permanent_ip_unban_only_denied',
    'permanent_ip_candidate_2_guard_once_uuid_only',
    'permanent_ip_legacy_proof_fails_closed',
    'permanent_ip_candidate_3_guard_once_uuid_only',
    'permanent_ip_predicate_proof_fails_closed',
    'permanent_ip_candidate_4_guard_once_uuid_only',
    'permanent_ip_missing-field_proof_fails_closed',
    'permanent_ip_candidate_5_guard_once_uuid_only',
    'permanent_ip_untrusted_target_fails_closed',
    'permanent_ip_candidate_6_guard_once_uuid_only',
    'permanent_ip_non_native_identity_fails_closed',
    'permanent_ip_candidate_7_guard_once_uuid_only',
    'permanent_ip_all_observed_accounts_count_untrusted',
    'permanent_ip_legacy_arbitrary_source_not_upgradeable',
    'permanent_ip_ordinary_case_gui_hides_exact_ip',
    'permanent_ip_audit_gui_hides_exact_ip',
    'permanent_ip_review_54_slots_exact_admin_ip',
    'permanent_ip_review_binds_evidence_digest',
    'permanent_ip_all_54_slots_transfer_protected',
    'permanent_ip_review_drag_protected',
    'permanent_ip_separate_controllers_cannot_cross_menus',
    'permanent_ip_proof_ack_disabled_before_read',
    'permanent_ip_cannot_confirm_before_acknowledgements',
    'permanent_ip_proof_reader_preserves_full_timing_fields',
    'permanent_ip_all_evidence_pages_traversed',
    'permanent_ip_impact_ack_separately_required',
    'permanent_ip_shared_address_consequence_visible',
    'permanent_ip_final_54_slots_bound_exact_ip',
    'permanent_ip_close_cancels_confirmation',
    'permanent_ip_candidate_8_guard_once_uuid_only',
    'permanent_ip_wrong_actor_cannot_apply',
    'permanent_ip_same_uuid_new_player_session_denied',
    'permanent_ip_candidate_9_guard_once_uuid_only',
    'permanent_ip_navigation_cancels_confirmation',
    'permanent_ip_candidate_10_guard_once_uuid_only',
    'permanent_ip_permission_loss_before_action_denied',
    'permanent_ip_candidate_11_guard_once_uuid_only',
    'permanent_ip_settings_change_before_action_denied',
    'permanent_ip_candidate_12_guard_once_uuid_only',
    'permanent_ip_late_untrusted_second_account_denied',
    'permanent_ip_candidate_13_guard_once_uuid_only',
    'permanent_ip_source_revoked_before_sql_claim_denied',
    'permanent_ip_candidate_14_guard_once_uuid_only',
    'permanent_ip_explicit_cancel_denied',
    'permanent_ip_candidate_15_guard_once_uuid_only',
    'permanent_ip_quit_invalidates_session',
    'permanent_ip_candidate_16_guard_once_uuid_only',
    'permanent_ip_case_invalidation_denied',
    'permanent_ip_candidate_17_guard_once_uuid_only',
    'permanent_ip_actual_30_second_confirmation_expired',
    'permanent_ip_expiry_audit_retained',
    'permanent_ip_request_audit_actor_and_time',
    'permanent_ip_review_audit_hides_address',
    'permanent_ip_synthetic_gui_positive_reachable',
    'permanent_ip_source_uuid_record_unchanged',
    'permanent_ip_permanent_ip_has_owned_native_entry',
    'permanent_ip_linked_proof_and_actor_retained',
    'permanent_ip_approval_reason_records_shared_impact',
    'permanent_ip_one_apply_audit_exact_actor_and_case',
    'permanent_ip_consumed_candidate_not_reviewable',
    'permanent_ip_double_click_and_replay_one_case',
    'permanent_ip_consumed_applied_case_audit_gui_accessible',
    'permanent_ip_consumed_audit_gui_has_no_exact_ip',
    'native_lifecycle_revoked_commit_callbacks_no_notification',
    'native_lifecycle_revoked_reviewed_ip_not_reinstalled',
    'native_lifecycle_revoked_automatic_uuid_not_reinstalled',
    'native_lifecycle_inactive_uuid_and_ip_orphans_cleaned',
    'native_lifecycle_mismatched_exact_marker_preserved',
    'native_lifecycle_foreign_uuid_and_ip_preserved',
    'native_lifecycle_surviving_uuid_and_ip_reinstated',
    'native_lifecycle_partial_ip_unban_keeps_uuid',
    'native_lifecycle_held_guard_authorized_before_confirmation',
    'native_lifecycle_confirmation_immediately_cancels_unclaimed_guard',
    'native_lifecycle_confirmation_preserves_unrelated_guard',
    'native_lifecycle_fresh_decision_after_confirmation_remains_eligible',
    'native_lifecycle_confirmed_case_scope_does_not_revoke_later_independent_case',
    'settings_runtime_restart_seed_five_allocations_persisted',
    'settings_runtime_reload_does_not_resize_live_tracking',
    'settings_runtime_reload_does_not_resize_live_storage',
    'settings_runtime_reload_does_not_resize_live_replay',
    'settings_runtime_restart_seed_gui_pending_and_active_37',
    'native_lifecycle_restart_crash_window_seeded',
)

MATRIX_RESTART_ASSERTIONS = (
    'restart_db_record_persisted',
    'restart_evidence_persisted',
    'restart_foreign_native_preserved',
    'restart_foreign_ip_preserved',
    'settings_runtime_restart_engine_and_telemetry_capacity_41',
    'settings_runtime_restart_storage_allocations_applied',
    'settings_runtime_restart_replay_allocations_applied',
    'settings_runtime_restart_gui_five_active_values_updated',
    'native_lifecycle_restart_inactive_uuid_and_ip_native_orphans_cleaned',
    'native_lifecycle_restart_remaining_active_case_reinstated',
    'native_lifecycle_restart_foreign_native_entries_preserved',
    'native_lifecycle_restart_unknown_token_preserved',
    'permanent_ip_restart_permanent_ip_durable',
    'permanent_ip_restart_source_uuid_expiry_unchanged',
    'permanent_ip_restart_linked_evidence_and_audit_durable',
    'permanent_ip_restart_revoke_only_ip_preserves_source_uuid',
    'permanent_ip_restart_ip_revoke_audit_exact_scope_actor',
    'permanent_ip_restart_revoke_retains_both_proofs',
    'disabled_state',
    'disable_no_listeners',
    'disable_no_scheduled_tasks',
)

IP_CASE_ASSERTIONS = (
    'native_telemetry_enabled',
    'expected_initial_trusted_count',
    'initial_packet_metrics_zero',
    'clean-before_admitted',
    'clean-before_exact_source_and_protocol',
    'clean-before_zero_findings',
    'untrusted_same_ip_peer_join',
    'first_actual_same_ip_observation_untrusted',
    'same_ip_peer_clean_exact_source',
    'malformed_actor_join',
    'actor_exact_source_and_protocol',
    'two_actual_same_ip_actors_untrusted',
    'two_isolated_invalid_packets_cancelled',
    'isolated_invalid_not_standalone_kick',
    'isolated_invalid_not_applied_to_state',
    'bounded_independent_wire_evidence_automatically_enforced',
    'bounded_wire_packet_count',
    'packet_queue_settled_without_drops',
    'same_ip_peer_survives_other_uuid_sanction',
    'uuid-denied-other-ip_denied_before_play',
    'uuid-denied-other-ip_same_case_not_generic_failure',
    'same-ip-peer-rejoin_admitted',
    'same-ip-peer-rejoin_exact_source_and_protocol',
    'same-ip-peer-rejoin_zero_findings',
    'same-ip-new-uuid_admitted',
    'same-ip-new-uuid_exact_source_and_protocol',
    'same-ip-new-uuid_zero_findings',
    'other-ip-clean_admitted',
    'other-ip-clean_exact_source_and_protocol',
    'other-ip-clean_zero_findings',
    'initial_normal_stop',
    'database_integrity_initial',
    'exactly_one_synthetic_actor_ban_initial',
    'automatic_scope_initial',
    'temporary_one_hour_not_permanent_initial',
    'exact_ip_protection_scope_initial',
    'no_revocation_initial',
    'linked_evidence_integrity_initial',
    'real_slot15_and_pitch100_independent_evidence_initial',
    'all_evidence_hashes_and_lengths_initial',
    'exact_owned_native_uuid_initial',
    'offline_never_native_ip_banned_initial',
    'no_synthetic_or_authenticated_trust_claim_initial',
    'actual_both_same_ip_identities_observed_initial',
    'ip_associations_use_lookup_hashes_initial',
    'same_ip_never_inferred_as_same_person_initial',
    'safe_shared_address_has_no_restriction_advisory_initial',
    'restart-uuid-denied_denied_before_play',
    'restart-uuid-denied_same_case_not_generic_failure',
    'restart-same-ip-peer_admitted',
    'restart-same-ip-peer_exact_source_and_protocol',
    'restart-same-ip-peer_zero_findings',
    'restart-same-ip-new-uuid_admitted',
    'restart-same-ip-new-uuid_exact_source_and_protocol',
    'restart-same-ip-new-uuid_zero_findings',
    'restart-other-ip-clean_admitted',
    'restart-other-ip-clean_exact_source_and_protocol',
    'restart-other-ip-clean_zero_findings',
    'restart_normal_stop',
    'database_integrity_restart',
    'exactly_one_synthetic_actor_ban_restart',
    'automatic_scope_restart',
    'temporary_one_hour_not_permanent_restart',
    'exact_ip_protection_scope_restart',
    'no_revocation_restart',
    'linked_evidence_integrity_restart',
    'real_slot15_and_pitch100_independent_evidence_restart',
    'all_evidence_hashes_and_lengths_restart',
    'exact_owned_native_uuid_restart',
    'offline_never_native_ip_banned_restart',
    'no_synthetic_or_authenticated_trust_claim_restart',
    'actual_both_same_ip_identities_observed_restart',
    'ip_associations_use_lookup_hashes_restart',
    'same_ip_never_inferred_as_same_person_restart',
    'safe_shared_address_has_no_restriction_advisory_restart',
    'restart_preserves_exact_sanction_and_ownership',
    'immutable_artifact_unchanged',
    'no_guardian_or_fixture_errors',
)

IP_FIRST_ASSERTIONS = IP_CASE_ASSERTIONS[:47]

MATRIX_PRODUCER_SHA256 = {'PermanentIpFixture.java': 'cde7b6cf986b33117a1f611234ab6e1eab8c3d29b0416b0b8d97f8b0325ce809', 'NativeLifecycleFixture.java': 'ec266f6a9f6564ccda2252a93788512809ab7774de0a9862bf3298052a710611', 'OfflineRecovery.java': '56264aa37857f28c52f08fd9cc4af051919b314e68f7f09d806ada6d47c23cb0', 'TelemetryLifecycleFixture.java': 'fac620b37c7aa5d5570f91f24de4333dd805aaf458ae50b65a003cc331c72fd3', 'SettingsRuntimeFixture.java': '308d845d823cd4df98146ebe6c4406ac3ede85093a4d9edac8ecac4567549192', 'GuardianFixture.java': 'a0d42b49a075f81af2f3e7c138f5c22bf1e77e177d143fc3bd7ea1613fce1bcc', 'AdvancedSettingsRuntimeFixture.java': 'fe994e64415887bc351bc1c36ec202acdb8a0c7cb1fa8ed4c9cd2375be3206e6', 'GuiIpUnbanFixture.java': '765645326f276ecc802031bbc18bf000e02fac3f0d827590505957c31f0a9cc2', 'PacketFixture.java': 'c64f599a6dc607c38c9af9257d3502c1ccc6af6c73836eb04e2ffa2bf3d62e19'}
