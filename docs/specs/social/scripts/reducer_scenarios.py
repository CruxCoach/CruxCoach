#!/usr/bin/env python3
"""Executable reducer, topology, clock, outbox and restore scenarios.

These cover the ordering and local-model behaviour that is not expressible as
a single mutated payload: arrival permutations, the phase-dependent component
profile, the 900-second prospective boundary, reboot-aware time, outbox leases
across a boot change, the write-ahead order in front of the non-atomic transport
handover and its crash windows, commit rollback at an authority boundary,
storage bounds, disband and restore.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import private_sharing_oracle as o  # noqa: E402


def build_scenarios(b) -> dict:
    low, high = b.low, b.high
    ev = b.events

    def order(*names):
        return list(names)

    sequences = [
        {
            "id": "7.0a",
            "preload": "PROVISIONING",
            "deliver": order("acceptance"),
            "expect": {
                "dispositions": [o.APPLIED],
                "topology_phase": "PROVISIONING_INVITED",
                "topology_outcome": "PROVISIONING",
                "sendable": False,
            },
            "note": "acceptance alone leaves the generation PROVISIONING until promotion "
                    "is canonical",
        },
        {
            "id": "7.0b",
            "preload": "PROVISIONING",
            "deliver": order("A1"),
            "expect": {"dispositions": [o.REJECT_NO_SLOT], "reasons": ["DEPENDENCY_INVALID"]},
            "note": "an offer before acceptance is rejected outright, not held",
        },
        {
            "id": "7.0e",
            "preload": "PROVISIONING",
            "deliver": order("acceptance", "acceptance_conflict"),
            "expect": {"dispositions": [o.APPLIED, o.TERMINAL],
                       "terminal": True,
                       "terminal_reason": "EQUAL_SEQUENCE_CONFLICT",
                       "sendable": False},
            "note": "two different structurally and semantically valid acceptances at "
                    "seq = 1 terminalise the generation; wire §8.2's equal-sequence rule "
                    "is slot-agnostic and the acceptance slot is no exception",
        },
        {
            "id": "7.0e-reverse",
            "preload": "PROVISIONING",
            "deliver": order("acceptance_conflict", "acceptance"),
            "expect": {"dispositions": [o.APPLIED, o.TERMINAL],
                       "terminal": True,
                       "terminal_reason": "EQUAL_SEQUENCE_CONFLICT",
                       "sendable": False},
            "note": "the other arrival order, so 'under either arrival order' is executed "
                    "rather than asserted in prose; no comparison picks a winner",
        },
        {
            "id": "7.0d",
            "topology": {
                "phase": "ACTIVE",
                "acceptance_applied": False,
                "admin_accounts": [low, high],
                "leaf_accounts": [low, high],
            },
            "expect": {"topology_outcome": "PROVISIONING"},
            "note": "promotion committed before the acceptance applied is not ACTIVE",
        },
        {
            "id": "7.1",
            "deliver": order("A1", "B1"),
            "expect": {"effective_level": "ACQUAINTANCE",
                       "heads": {"low": "ACQUAINTANCE", "high": "FRIEND"}},
        },
        {
            "id": "7.2",
            "deliver": order("B1", "A1"),
            "expect": {"effective_level": "ACQUAINTANCE",
                       "heads": {"low": "ACQUAINTANCE", "high": "FRIEND"}},
            "note": "byte-identical state to 7.1",
        },
        {
            "id": "7.3",
            "deliver": order("A1", "A2"),
            "expect": {"effective_level": "NOT_ESTABLISHED", "terminal": False,
                       "heads": {"low": "FRIEND", "high": None}},
            "note": "a missing head contributes NOT_ESTABLISHED; it never synthesises a "
                    "received NONE and never terminalises",
        },
        {
            "id": "7.4",
            "deliver": order("A2", "A1"),
            "expect": {"effective_level": "NOT_ESTABLISHED", "terminal": False,
                       "heads": {"low": "FRIEND", "high": None},
                       # The hold *reason* is asserted, not only the fact of the
                       # hold: wire §8.3 closes that vocabulary, and a held event
                       # whose reason nobody checks could be waiting for anything.
                       "dispositions": [o.HELD, o.APPLIED],
                       "reasons": ["HOLD_PREDECESSOR_MISSING", None]},
            "note": "A2 is held for its missing predecessor, then released by A1; "
                    "only B1 can establish FRIEND",
        },
        {
            "id": "7.5",
            "deliver": order("A2", "A1", "B1"),
            "expect": {"effective_level": "FRIEND", "transition_display_time": 1786000200},
        },
        {
            "id": "7.6",
            "deliver": order("grant_summary_set", "A1", "A2", "B1"),
            "expect": {"dispositions": [o.HELD, o.APPLIED, o.APPLIED, o.APPLIED],
                       "grant_states": {"LOGBOOK_SUMMARY_first": "ACTIVE"}},
            "note": "the grant is held, never quarantined, until both exact heads arrive",
        },
        {
            "id": "7.7",
            "deliver": order("projection_summary_snapshot", "A1", "A2", "B1",
                             "grant_summary_set"),
            "expect": {"dispositions": [o.HELD, o.APPLIED, o.APPLIED, o.APPLIED, o.APPLIED],
                       "projection_present": True},
        },
        {
            "id": "7.8",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_delta"),
            "expect": {"reconciling": True, "projection_present": False,
                       # A projection gap is a *predecessor* hold, not a missing
                       # grant: the grant applied one event earlier. Asserting
                       # the code keeps the two apart (wire §8.3).
                       "dispositions_last": o.HELD,
                       "reasons_last": "HOLD_PREDECESSOR_MISSING"},
        },
        {
            "id": "7.9",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_delta", "projection_detail_snapshot"),
            "expect": {"reconciling": False, "session_duration_minutes": 90},
        },
        {
            "id": "7.10",
            "deliver": order("A1", "A1", "B1", "B1"),
            "expect": {"effective_level": "ACQUAINTANCE", "applied_event_count": 2},
            "note": "byte-identical redelivery is a no-op",
        },
        {
            "id": "7.11",
            "deliver": order("A1", "A2", "A2_conflict"),
            "expect": {"terminal": True, "terminal_reason": "EQUAL_SEQUENCE_CONFLICT"},
        },
        {
            "id": "7.11-reverse",
            "deliver": order("A1", "A2_conflict", "A2"),
            "expect": {"terminal": True, "terminal_reason": "EQUAL_SEQUENCE_CONFLICT"},
            "note": "the other arrival order of the same conflicting pair; the row has "
                    "always claimed both and only one was ever driven",
        },
        {
            "id": "7.12",
            "deliver": order("A1", "A2", "B1", "grant_summary_set",
                             "projection_summary_snapshot", "grant_summary_narrowing"),
            "expect": {"projection_present": False, "superseded_purged": True},
        },
        {
            "id": "7.13",
            "deliver": order("A1", "A2", "B1", "grant_summary_set",
                             "grant_summary_narrowing", "grant_summary_revoke"),
            "expect": {"grant_states": {"LOGBOOK_SUMMARY_current": "REVOKED"}},
        },
        {
            "id": "7.14",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_snapshot", "A3"),
            "expect": {"effective_level": "ACQUAINTANCE", "detail_retired": True,
                       "projection_present": False},
        },
        {
            "id": "7.15",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_snapshot", "A3", "A4"),
            "expect": {"effective_level": "FRIEND", "detail_retired": True,
                       "projection_present": False},
            "note": "the retirement is sticky across the re-upgrade",
        },
        {
            "id": "7.16",
            "deliver": order("A1", "A2", "B1", "A3", "A4", "A5"),
            "expect": {"terminal": True, "terminal_reason": "OFFER_NONE_RECEIVED"},
        },
        {
            "id": "7.17",
            "deliver": order("A1", "A2", "B1", "A3", "A4", "grant_detail_backdated"),
            "expect": {"detail_retired": True},
            "note": "the intervening downgrade is found by walking the gapless successors "
                    "of both referenced heads",
        },
        {
            "id": "7.18",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_snapshot", "projection_detail_delta",
                             "projection_detail_delta_boundary"),
            "expect": {"session_count": 2,
                       "boundary_session_started_at_utc": o.ceil900(1786000200)},
            "note": "the first sendable instant after a 1786000200 boundary is "
                    "ceil900(1786000200) = 1786000500",
        },
        {
            "id": "7.19",
            "deliver": order("A1", "A2", "B1", "grant_detail_backdated",
                             "projection_detail_snapshot", "wrong_generation"),
            "expect": {"dispositions_last": o.REJECT_NO_SLOT,
                       "reasons_last": "GENERATION_BINDING_INVALID"},
        },
    ]

    topology_phases = [
        {
            "id": "phase_provisioning_self_only",
            "state": {
                "phase": "PROVISIONING_SELF_ONLY",
                "admin_accounts": [low],
                "leaf_accounts": [low],
            },
            "expect": {"outcome": "PROVISIONING",
                       "admin_policy_0x8003": [low],
                       "leaf_accounts": [low],
                       "lifecycle_0x800c": "0x00",
                       "sendable": False},
            "note": "at the MDK pin the creator is the implicit sole admin of a self-only "
                    "group; a missing high admin here is expected, not a fault",
        },
        {
            "id": "phase_provisioning_invited",
            "state": {
                "phase": "PROVISIONING_INVITED",
                "admin_accounts": [low],
                "leaf_accounts": [low, high],
            },
            "expect": {"outcome": "PROVISIONING",
                       "admin_policy_0x8003": [low],
                       "leaf_accounts": [low, high],
                       "lifecycle_0x800c": "0x00",
                       "sendable": False},
            "note": "promotion is a separate commit; the invitee is a member, not an admin",
        },
        {
            "id": "phase_active",
            "state": {"phase": "ACTIVE", "admin_accounts": [low, high],
                      "leaf_accounts": [low, high]},
            "expect": {"outcome": "ACTIVE",
                       "admin_policy_0x8003": [low, high],
                       "leaf_accounts": [low, high],
                       "lifecycle_0x800c": "0x00",
                       "sendable": True},
        },
        {
            "id": "phase_active_convergence_hold",
            "state": {"phase": "ACTIVE", "admin_accounts": [low, high],
                      "leaf_accounts": [low, high], "mdk_lifecycle": "Merging"},
            "expect": {"outcome": "HOLD", "sendable": False},
            "note": "PendingPublish/Merging/Recovering hold send and apply without "
                    "terminalising for that fact alone",
        },
        {
            "id": "phase_disbanded",
            "state": {"phase": "DISBANDED", "signed_lifecycle": "disbanded",
                      "lifecycle_byte": "0x01", "admin_accounts": [low],
                      "leaf_accounts": [low]},
            "expect": {"outcome": o.TERMINAL, "reason": "LIFECYCLE_NOT_ACTIVE",
                       "admin_policy_0x8003": [low],
                       "leaf_accounts": [low],
                       "lifecycle_0x800c": "0x01",
                       "sendable": False},
            "note": "the disband commit removes every candidate-parent leaf except the "
                    "committer's and reduces the admin policy to exactly the committer",
        },
    ]

    state_machine = [
        {
            "id": "sm_provisioning_reaches_active_only_through_the_gate",
            "start_state": "PROVISIONING",
            "transitions": [{"event": "convergence_unresolved"},
                            {"event": "fresh_read_ok"},
                            {"event": "activation_gate_passed"}],
            "expect": {"states": ["PROVISIONING", "PROVISIONING", "ACTIVE"],
                       "final_state": "ACTIVE",
                       "refused_transitions": [],
                       "terminal_reason": None,
                       "send_allowed": True,
                       "apply_allowed": True},
            "note": "an unresolved convergence holds without terminalising; only the "
                    "activation gate of wire §9.5 promotes the generation",
        },
        {
            "id": "sm_active_never_returns_to_provisioning",
            "start_state": "ACTIVE",
            "transitions": [{"event": "return_to_provisioning"}],
            "expect": {"states": ["ACTIVE"],
                       "final_state": "ACTIVE",
                       "refused_transitions": ["return_to_provisioning"],
                       "terminal_reason": None,
                       "send_allowed": True},
            "note": "the forbidden edge is refused outright, never reinterpreted as a "
                    "neighbouring transition",
        },
        {
            "id": "sm_authority_rollback_after_activation_is_terminal",
            "start_state": "ACTIVE",
            "sticky_deny": ["revoke:grant-summary"],
            "transitions": [{"event": "rollback_authority_commit"}],
            "expect": {"states": [o.TERMINAL],
                       "final_state": o.TERMINAL,
                       "terminal_reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "refused_transitions": [],
                       "sticky_deny": ["revoke:grant-summary"],
                       "send_allowed": False,
                       "apply_allowed": False},
            "note": "a generation cannot un-become active; the tombstone survives it",
        },
        {
            "id": "sm_authority_rollback_before_activation_holds",
            "start_state": "PROVISIONING",
            "transitions": [{"event": "rollback_authority_commit"},
                            {"event": "fresh_read_ok"}],
            "expect": {"states": ["PROVISIONING", "PROVISIONING"],
                       "final_state": "PROVISIONING",
                       "terminal_reason": None,
                       "refused_transitions": [],
                       "send_allowed": False,
                       "apply_allowed": False},
        },
        {
            # FEAT-062 §3.3 and criterion 17: a promotion that can never
            # complete is a visible abort and a terminal generation, never a
            # one-admin group that limps on. `PROMOTION_FAILED` is a closed
            # terminal code of wire §8.4, so the outcome is asserted with its
            # code rather than only as "terminal".
            "id": "sm_failed_promotion_terminalises_and_never_activates",
            "start_state": "PROVISIONING",
            "transitions": [{"event": "convergence_unresolved"},
                            {"event": "terminal_fault", "detail": "PROMOTION_FAILED"},
                            {"event": "activation_gate_passed"}],
            "expect": {"states": ["PROVISIONING", o.TERMINAL, o.TERMINAL],
                       "final_state": o.TERMINAL,
                       "terminal_reason": "PROMOTION_FAILED",
                       "refused_transitions": ["activation_gate_passed"],
                       "send_allowed": False,
                       "apply_allowed": False},
            "note": "a permanently failed promotion terminalises that generation; the "
                    "activation gate is refused afterwards, so no fixture can turn the "
                    "abort back into an active one-admin group",
        },
        {
            # The provisioning half of the matrix: a generation may be re-held,
            # may record a deny and may see an unrelated rollback, and a replay
            # meant for a terminal generation is refused rather than reshaped
            # into one of those.
            "id": "sm_provisioning_holds_denies_and_refuses_a_terminal_replay",
            "start_state": "PROVISIONING",
            "transitions": [{"event": "return_to_provisioning"},
                            {"event": "rollback_unrelated_commit"},
                            {"detail": "block:peer", "event": "sticky_deny_recorded"},
                            {"event": "replay_after_terminal"}],
            "expect": {"states": ["PROVISIONING", "PROVISIONING", "PROVISIONING",
                                  "PROVISIONING"],
                       "final_state": "PROVISIONING",
                       "terminal_reason": None,
                       "refused_transitions": ["replay_after_terminal"],
                       "sticky_deny": ["block:peer"],
                       "send_allowed": False,
                       "apply_allowed": False},
        },
        {
            # An active generation holds on unresolved convergence, refuses the
            # activation gate it already passed and refuses a terminal replay;
            # only a fresh session-consistent read lifts the hold.
            "id": "sm_active_holds_on_convergence_and_refuses_a_second_activation",
            "start_state": "ACTIVE",
            "transitions": [{"event": "convergence_unresolved"},
                            {"event": "activation_gate_passed"},
                            {"event": "replay_after_terminal"},
                            {"event": "fresh_read_ok"}],
            "expect": {"states": ["ACTIVE", "ACTIVE", "ACTIVE",
                                  "ACTIVE"],
                       "final_state": "ACTIVE",
                       "terminal_reason": None,
                       "refused_transitions": ["activation_gate_passed",
                                               "replay_after_terminal"],
                       "send_allowed": True,
                       "apply_allowed": True},
            "note": "the hold blocks send and apply while it lasts; the fresh read is "
                    "the only thing that lifts it, and it never re-runs activation",
        },
        {
            # Terminal is absorbing against the whole vocabulary, not only
            # against the two edges the earlier case tries. Recording a further
            # deny is the one thing that still changes state, because the
            # sticky-deny set may only grow.
            "id": "sm_terminal_refuses_every_event_but_a_further_deny",
            "start_state": "ACTIVE",
            "sticky_deny": ["revoke:grant-summary"],
            "transitions": [{"detail": "MDK_QUARANTINE", "event": "terminal_fault"},
                            {"detail": "block:peer", "event": "sticky_deny_recorded"},
                            {"event": "convergence_unresolved"},
                            {"event": "fresh_read_ok"},
                            {"event": "rollback_authority_commit"},
                            {"event": "rollback_unrelated_commit"},
                            {"event": "terminal_fault"}],
            "expect": {"states": [o.TERMINAL] * 7,
                       "final_state": o.TERMINAL,
                       "terminal_reason": "MDK_QUARANTINE",
                       "refused_transitions": ["convergence_unresolved", "fresh_read_ok",
                                               "rollback_authority_commit",
                                               "rollback_unrelated_commit",
                                               "terminal_fault"],
                       "sticky_deny": ["block:peer", "revoke:grant-summary"],
                       "send_allowed": False,
                       "apply_allowed": False},
            "note": "a terminal generation is never re-terminalised, never re-read into "
                    "health and never rolled back into an earlier state",
        },
        {
            "id": "sm_terminal_is_absorbing",
            "start_state": o.TERMINAL,
            "sticky_deny": ["revoke:grant-summary", "downgrade:detail"],
            "transitions": [{"event": "activation_gate_passed"},
                            {"event": "return_to_provisioning"},
                            {"event": "replay_after_terminal"}],
            "expect": {"states": [o.TERMINAL, o.TERMINAL, o.TERMINAL],
                       "final_state": o.TERMINAL,
                       "refused_transitions": ["activation_gate_passed",
                                               "return_to_provisioning"],
                       "sticky_deny": ["downgrade:detail", "revoke:grant-summary"],
                       "send_allowed": False,
                       "apply_allowed": False},
            "note": "replay after a terminal generation changes nothing and revives "
                    "no tombstone",
        },
        {
            "id": "sm_sticky_deny_only_grows",
            "start_state": "ACTIVE",
            "sticky_deny": ["revoke:grant-summary"],
            "transitions": [{"event": "sticky_deny_recorded", "detail": "downgrade:detail"},
                            {"event": "rollback_unrelated_commit"},
                            {"event": "fresh_read_ok"},
                            {"event": "terminal_fault", "detail": "OFFER_NONE_RECEIVED"}],
            "expect": {"states": ["ACTIVE", "ACTIVE", "ACTIVE", o.TERMINAL],
                       "final_state": o.TERMINAL,
                       "terminal_reason": "OFFER_NONE_RECEIVED",
                       "refused_transitions": [],
                       "sticky_deny": ["downgrade:detail", "revoke:grant-summary"],
                       "send_allowed": False},
        },
        {
            "id": "sm_unrelated_rollback_holds_without_terminalising",
            "start_state": "ACTIVE",
            "transitions": [{"event": "rollback_unrelated_commit"}],
            "expect": {"states": ["ACTIVE"],
                       "final_state": "ACTIVE",
                       "terminal_reason": None,
                       "refused_transitions": [],
                       "send_allowed": False,
                       "apply_allowed": False},
            "note": "holding is not terminalising; one fresh session-consistent read "
                    "decides",
        },
    ]

    rollback = [
        {
            "id": "rollback_seams_diverge_on_the_canonical_branch",
            "signal": {"event": "ForkRecovered", "commit_class": "promotion",
                       "generation_state": "ACTIVE",
                       "peer_branch_divergent": True,
                       "invalidated_commit_id": "5f" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "BRANCH_SELECTION_DIVERGENT",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
            "note": "FEAT-062 R8 A3: the direct fork-recovery seam and witness-aware "
                    "stored convergence settled on different canonical branches for "
                    "the same fork and stayed there. §8.4a rule 6 fails closed on the "
                    "disagreement itself rather than on the rollback class",
        },
        {
            "id": "rollback_seams_diverge_before_activation_is_also_terminal",
            "signal": {"event": "CommitRolledBack", "commit_class": "unrelated",
                       "generation_state": "PROVISIONING",
                       "peer_branch_divergent": True,
                       "invalidated_commit_id": "60" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "BRANCH_SELECTION_DIVERGENT",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
            "note": "rule 6 is decided ahead of rules 3 and 4, so neither an "
                    "unrelated commit class nor a pre-activation generation softens "
                    "it: divergent canonical branches are not repairable by "
                    "re-reading either side",
        },
        {
            "id": "rollback_promotion_after_activation",
            "signal": {"event": "GroupStateInvalidated",
                       "commit_class": "promotion",
                       "generation_state": "ACTIVE",
                       "invalidated_commit_id": "55" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
        },
        {
            "id": "rollback_promotion_before_activation",
            "signal": {"event": "ForkRecovered", "commit_class": "promotion",
                       "generation_state": "PROVISIONING",
                       "invalidated_commit_id": "56" * 32},
            "expect": {"outcome": "HOLD", "tombstones_sticky": True,
                       "purge_deadline_advanced": False},
            "note": "a rolled-back promotion before activation returns to PROVISIONING",
        },
        {
            "id": "rollback_admin_policy_before_activation",
            "signal": {"event": "CommitRolledBack", "commit_class": "admin_policy_0x8003",
                       "generation_state": "PROVISIONING",
                       "invalidated_commit_id": "5b" * 32},
            "expect": {"outcome": "HOLD", "tombstones_sticky": True,
                       "purge_deadline_advanced": False},
            "note": "§8.4a rule 4 applies to every authority class, not only to the "
                    "promotion: before activation the generation holds and re-reads",
        },
        {
            "id": "rollback_profile_0x8001_before_activation",
            "signal": {"event": "GroupStateInvalidated", "commit_class": "profile_0x8001",
                       "generation_state": "PROVISIONING",
                       "invalidated_commit_id": "5c" * 32},
            "expect": {"outcome": "HOLD", "tombstones_sticky": True,
                       "purge_deadline_advanced": False},
            "note": "a rolled-back 0x8001 marker commit before activation is not "
                    "terminal by itself; only the fresh read decides",
        },
        {
            "id": "rollback_lifecycle_0x800c_before_activation",
            "signal": {"event": "CommitRolledBack", "commit_class": "lifecycle_0x800c",
                       "generation_state": "PROVISIONING",
                       "invalidated_commit_id": "5d" * 32},
            "expect": {"outcome": "HOLD", "tombstones_sticky": True,
                       "purge_deadline_advanced": False},
            "note": "the lifecycle seam before activation holds like the others; a "
                    "generation that never became ACTIVE cannot un-become it",
        },
        {
            "id": "rollback_profile_0x8001_after_activation",
            "signal": {"event": "CommitRolledBack", "commit_class": "profile_0x8001",
                       "generation_state": "ACTIVE", "invalidated_commit_id": "57" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
        },
        {
            "id": "rollback_admin_policy_after_activation",
            "signal": {"event": "GroupStateInvalidated", "commit_class": "admin_policy_0x8003",
                       "generation_state": "ACTIVE", "invalidated_commit_id": "58" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
        },
        {
            "id": "rollback_lifecycle_0x800c_after_activation",
            "signal": {"event": "ForkRecovered", "commit_class": "lifecycle_0x800c",
                       "generation_state": "ACTIVE", "invalidated_commit_id": "5a" * 32},
            "expect": {"outcome": o.TERMINAL, "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "tombstones_sticky": True, "purge_deadline_advanced": False},
        },
        {
            "id": "rollback_unrelated_commit",
            "signal": {"event": "CommitRolledBack", "commit_class": "unrelated",
                       "generation_state": "ACTIVE", "invalidated_commit_id": "59" * 32},
            "expect": {"outcome": "HOLD", "tombstones_sticky": True,
                       "purge_deadline_advanced": False},
            "note": "hold and re-read; the fresh session-consistent read decides",
        },
    ]

    clock = [
        {
            "id": "clock_normal_advance",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000000},
            "observe": {"boot_id": "boot-1", "monotonic_now": 1100, "wall_now": 1786000100},
            "expect": {"untrusted": False, "effective_now": 1786000100,
                       "grants_hidden": False, "purges_due": False,
                       "permissive_send_blocked": False, "restrictive_send_blocked": False},
        },
        {
            "id": "clock_rollback_before_deadline",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000100},
            "observe": {"boot_id": "boot-1", "monotonic_now": 1200, "wall_now": 100},
            "expect": {"untrusted": True, "untrusted_reason": "WALL_ROLLBACK",
                       "grants_hidden": True, "purges_due": True,
                       "permissive_send_blocked": True, "restrictive_send_blocked": False},
            "note": "the counterexample the high-water rule alone did not cover: a wall "
                    "clock parked far in the past before the deadline",
        },
        {
            "id": "clock_frozen_wall",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000100},
            "observe": {"boot_id": "boot-1", "monotonic_now": 1000 + 901,
                        "wall_now": 1786000100},
            "expect": {"untrusted": True, "untrusted_reason": "WALL_FROZEN",
                       "grants_hidden": True, "purges_due": True,
                       "permissive_send_blocked": True, "restrictive_send_blocked": False},
        },
        {
            "id": "clock_reboot",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000100},
            "observe": {"boot_id": "boot-2", "monotonic_now": 5, "wall_now": 1786000500},
            "expect": {"untrusted": True, "untrusted_reason": "BOOT_CHANGED",
                       "grants_hidden": True, "purges_due": True,
                       "permissive_send_blocked": True, "restrictive_send_blocked": False},
            "note": "a monotonic value from a previous boot is not comparable; the boot "
                    "check runs first and never compares the two",
        },
        {
            "id": "clock_monotonic_reset_same_boot",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000100},
            "observe": {"boot_id": "boot-1", "monotonic_now": 10, "wall_now": 1786000200},
            "expect": {"untrusted": True, "untrusted_reason": "MONOTONIC_RESET",
                       "grants_hidden": True, "purges_due": True,
                       "permissive_send_blocked": True, "restrictive_send_blocked": False},
        },
        {
            "id": "clock_trusted_reinitialisation",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000, "wall_high_water": 1786000100},
            "observe": {"boot_id": "boot-2", "monotonic_now": 5, "wall_now": 1786000500},
            "reinitialise": {"boot_id": "boot-2", "monotonic_now": 5,
                             "trusted_wall_now": 1786000500},
            "expect": {"untrusted": True, "untrusted_reason": "BOOT_CHANGED",
                       "grants_hidden": True, "purges_due": True,
                       "permissive_send_blocked": True, "restrictive_send_blocked": False,
                       "after_reinit_untrusted": False,
                       "after_reinit_high_water": 1786000500},
            "note": "the only exit is an explicit trusted re-anchor; never a peer's "
                    "issued_at and never a relay timestamp",
        },
        {
            "id": "clock_high_water_beats_backwards_wall",
            "start": {"boot_id": "boot-1", "monotonic_anchor": 1000,
                      "wall_high_water": 1793776400},
            "observe": {"boot_id": "boot-1", "monotonic_now": 1000, "wall_now": 1793776400},
            "expect": {"untrusted": False, "effective_now": 1793776400,
                       "grant_expired": True,
                       "grants_hidden": False, "purges_due": False,
                       "permissive_send_blocked": False, "restrictive_send_blocked": False},
            "note": "expires_at 1793776300 stays expired and its purge deadline keeps running",
        },
    ]

    outbox = [
        {
            "id": "outbox_state_to_label",
            "map": {state: o.outbox_label(state) for state in o.OUTBOX_STATES},
            "expect": {"every_visible_row_also_says": o.NOT_PEER_CONFIRMED,
                       "superseded_is_audit_only": True},
        },
        {
            "id": "outbox_expired_lease_same_boot",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "PRE_EXTERNAL_EFFECT",
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "same_inner_event_id": True,
                "recovery": {
                    "outcome": "RECOVER_QUEUED",
                    "reason": None,
                    "resulting_state": "QUEUED",
                    "label": "Queued locally",
                    "automatic": True,
                    "hands_to_transport": False,
                    "next_transport_step": "WRITE_AHEAD_FIRST_DELIVERY",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
                "drain": {
                    "next_transport_step": "WRITE_AHEAD_FIRST_DELIVERY",
                    "transport_reachable": True,
                    "requires_write_ahead_commit": True,
                    "requires_persisted_identity": False,
                    "required_closed_gate": None,
                    "durable_phase_before_call": "EXTERNAL_EFFECT_UNCLEAR",
                    "commits": {"not_attempted": False, "failed": False,
                                "unproven": False, "committed": True},
                },
            },
            "note": "the only automatic requeue there is: nothing had been handed to the "
                    "transport, so driving the same inner event is a first delivery",
        },
        {
            "id": "outbox_live_lease_other_worker",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786001500,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_UNCLEAR",
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": False,
                "recovery": {
                    "outcome": "LEASE_HELD",
                    "reason": None,
                    "resulting_state": "IN_FLIGHT",
                    "label": "Queued locally",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "the holder is still alive, so no other worker touches the row at all — "
                    "the phase never even gets to decide",
        },
        {
            "id": "outbox_lease_from_previous_boot",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 9_999_999_999,
                    "attempts": 3,
                    "external_effect_phase": "PRE_EXTERNAL_EFFECT",
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 10,
            "current_boot_id": "boot-2",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVER_QUEUED",
                    "reason": None,
                    "resulting_state": "QUEUED",
                    "label": "Queued locally",
                    "automatic": True,
                    "hands_to_transport": False,
                    "next_transport_step": "WRITE_AHEAD_FIRST_DELIVERY",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
                "drain": {
                    "next_transport_step": "WRITE_AHEAD_FIRST_DELIVERY",
                    "transport_reachable": True,
                    "requires_write_ahead_commit": True,
                    "requires_persisted_identity": False,
                    "required_closed_gate": None,
                    "durable_phase_before_call": "EXTERNAL_EFFECT_UNCLEAR",
                    "commits": {"not_attempted": False, "failed": False,
                                "unproven": False, "committed": True},
                },
            },
            "note": "a foreign boot id means expired/reclaimable regardless of a monotonic "
                    "lease_until that cannot be compared across boots; the requeue is "
                    "allowed by the phase, not by the boot change",
        },
        {
            "id": "outbox_lease_from_previous_boot_unclear_is_blocked",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 9_999_999_999,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_UNCLEAR",
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 10,
            "current_boot_id": "boot-2",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "the same foreign-boot row whose worker died after a possible external "
                    "effect: reclaiming the lease is allowed, requeueing it is not",
        },
        {
            "id": "outbox_lease_recovery_unclear_is_blocked",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_UNCLEAR",
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "the lease expired, but the send may already have had external effect and "
                    "no identical transport message is persisted, so nothing is requeued and "
                    "nothing is re-driven",
        },
        {
            "id": "outbox_lease_recovery_replayable_transport_stays_blocked",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_UNCLEAR",
                    "receipt": None,
                    "persisted_transport": {"source_message_id": "cc" * 32,
                                            "restart_durable": True,
                                            "replayable": True},
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY",
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_RETRY_GATE_OPEN",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": "cc" * 32,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "even a row that claims a restart-durable, replayable transport message "
                    "under its one source id stays blocked while R6-G2 is open; the id is "
                    "shown, not re-driven",
        },
        {
            "id": "outbox_lease_recovery_pre_phase_with_authorised_handover",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "PRE_EXTERNAL_EFFECT",
                    "handover_authorised": True,
                    "receipt": None,
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_WRITE_AHEAD_INCONSISTENT",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "a row that still reads PRE_EXTERNAL_EFFECT while a handover was already "
                    "authorised contradicts the write-ahead rule; recovery must fail closed "
                    "rather than requeue, because the phase write is not atomic with the "
                    "transport call",
        },
        {
            "id": "outbox_lease_recovery_partial_transport_record",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_UNCLEAR",
                    "receipt": None,
                    "persisted_transport": {"source_message_id": "cc" * 32,
                                            "restart_durable": False,
                                            "replayable": True},
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": "cc" * 32,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "a transport record that is not restart-durable is no record: the known "
                    "source id stays visible on the row and is never re-driven",
        },
        {
            "id": "outbox_lease_recovery_effect_observed",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_OBSERVED",
                    "receipt": {"intent_id": "intent-4",
                                "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                                "source_message_ids": ["dd" * 32],
                                "accepted_relays": [],
                                "canonical_at": None},
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RESOLVE_FROM_RECEIPT",
                    "reason": None,
                    "resulting_state": "SENT_LOCALLY",
                    "label": "Queued locally",
                    "automatic": True,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": "dd" * 32,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "the external effect is durably evidenced, so the receipt decides the "
                    "state; nothing is handed to the transport again and no publication "
                    "claim is upgraded",
        },
        {
            "id": "outbox_lease_recovery_observed_without_evidence",
            "row": {"state": "IN_FLIGHT", "lease_owner": "worker-a",
                    "lease_boot_id": "boot-1", "lease_until": 1786000900,
                    "attempts": 3,
                    "external_effect_phase": "EXTERNAL_EFFECT_OBSERVED",
                    "receipt": {"intent_id": "intent-5",
                                "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                                "source_message_ids": [],
                                "accepted_relays": [],
                                "canonical_at": None},
                    "persisted_transport": None,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"]},
            "now": 1786001000,
            "current_boot_id": "boot-1",
            "expect": {
                "recoverable": True,
                "recovery": {
                    "outcome": "RECOVERY_BLOCKED",
                    "reason": "RECOVERY_EVIDENCE_INCOMPLETE",
                    "resulting_state": "DELIVERY_UNCLEAR",
                    "label": "Delivery unclear — not retried automatically",
                    "automatic": False,
                    "hands_to_transport": False,
                    "next_transport_step": "NONE",
                    "transport_identical": False,
                    "source_message_id": None,
                    "binds_second_source_id": False,
                    "user_visible": True,
                    "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                },
            },
            "note": "an in-memory observation that no durable receipt backs is the unclear "
                    "case, not an observed one",
        },
        {
            "id": "outbox_supersession_matrix",
            "pairs": [
                {"prior": "restrictive", "next": "permissive", "allowed": False},
                {"prior": "permissive", "next": "restrictive", "allowed": True},
                {"prior": "permissive", "next": "permissive", "allowed": True},
                {"prior": "restrictive", "next": "restrictive", "allowed": True},
            ],
        },
        {
            "id": "outbox_relay_claim_requires_evidence",
            "receipts": [
                {"name": "canonical_with_accepted_relay",
                 "receipt": {"intent_id": "intent-1",
                             "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                             "source_message_ids": ["aa" * 32],
                             "accepted_relays": ["wss://relay.example/one"],
                             "canonical_at": 1786001000},
                 "expect_label": o.OUTBOX_LABELS["MDK_CANONICAL"],
                 "expect_queue_state": "MDK_CANONICAL"},
                {"name": "canonical_without_relay_evidence",
                 "receipt": {"intent_id": "intent-2",
                             "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                             "source_message_ids": ["bb" * 32],
                             "accepted_relays": [],
                             "canonical_at": 1786001000},
                 "expect_label": o.OUTBOX_LABELS["SENT_LOCALLY"],
                 "expect_queue_state": "SENT_LOCALLY"},
                {"name": "queued_offline",
                 "receipt": {"intent_id": "intent-3",
                             "inner_event_id": ev["grant_summary_revoke"]["inner_event_id"],
                             "source_message_ids": [],
                             "accepted_relays": [],
                             "canonical_at": None},
                 "expect_label": o.OUTBOX_LABELS["SENT_LOCALLY"],
                 "expect_queue_state": "QUEUED"},
            ],
            "note": "the label is earned by a persisted typed receipt naming at least one "
                    "concrete accepted relay endpoint, never assumed from a report count",
        },
    ]

    # wire §8.7c/§8.7d — the write-ahead order in front of a non-atomic handover,
    # what every crash window of one send attempt leaves behind for recovery, and
    # — kept apart from the authorisation gate — what the authorised call itself
    # leaves behind.
    revoke_id = ev["grant_summary_revoke"]["inner_event_id"]
    blocked_unclear = {
        "outcome": "RECOVERY_BLOCKED",
        "reason": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
        "resulting_state": "DELIVERY_UNCLEAR",
        "automatic": False,
        "hands_to_transport": False,
        "next_transport_step": "NONE",
    }
    requeued = {
        "outcome": "RECOVER_QUEUED",
        "reason": None,
        "resulting_state": "QUEUED",
        "automatic": True,
        "hands_to_transport": False,
        "next_transport_step": "WRITE_AHEAD_FIRST_DELIVERY",
    }

    def write_ahead_case(cid, result, outcome, reason, may_call, proven, phase, note,
                         phases=None, recovery=None):
        case = {
            "id": cid,
            "kind": "write_ahead",
            "state": {"write_ahead_commit": result},
            "inner_event_id": revoke_id,
            "expect": {
                "outcome": outcome,
                "reason": reason,
                "transport_may_be_called": may_call,
                "phase_commit_proven": proven,
                "durable_phase": phase,
                "claims_atomic_handover": False,
            },
            "note": note,
        }
        if phases is not None:
            case["expect"]["admissible_phases"] = phases
            case["expect"]["recovery"] = recovery
        return case

    blocked_after_call = {
        "outcome": "RECOVERY_BLOCKED",
        "reason": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
        "resulting_state": "DELIVERY_UNCLEAR",
        "automatic": False,
        "hands_to_transport": False,
        "next_transport_step": "NONE",
    }

    def post_handover_case(cid, call_result, outcome, reason, phase, recovery, note,
                           receipt=None):
        case = {
            "id": cid,
            "kind": "post_handover",
            "state": {"transport_call_result": call_result},
            "inner_event_id": revoke_id,
            "expect": {
                "outcome": outcome,
                "reason": reason,
                "durable_phase": phase,
                "reverts_to_pre_external_effect": False,
                "automatic_retry": False,
                "requeues": False,
                "claims_synchronous_no_effect_proof": False,
                "recovery": recovery,
            },
            "note": note,
        }
        if receipt is not None:
            case["receipt"] = receipt
        return case

    def crash_case(cid, point, phases, authorised, possibly_called, receipt_bound,
                   recovery, note, receipt=None):
        case = {
            "id": cid,
            "kind": "crash",
            "state": {"crash_point": point},
            "inner_event_id": revoke_id,
            "expect": {
                "admissible_phases": phases,
                "handover_authorised": authorised,
                "transport_possibly_called": possibly_called,
                "receipt_bound": receipt_bound,
                "pre_phase_admissible": "PRE_EXTERNAL_EFFECT" in phases,
                "claims_atomic_handover": False,
                "recovery": recovery,
            },
            "note": note,
        }
        if receipt is not None:
            case["receipt"] = receipt
        return case

    send_handover = [
        write_ahead_case(
            "send_write_ahead_not_attempted", "not_attempted",
            "HANDOVER_REFUSED", "WRITE_AHEAD_NOT_COMMITTED", False, False,
            "PRE_EXTERNAL_EFFECT",
            "calling the transport straight out of PRE_EXTERNAL_EFFECT is the reviewed "
            "hole: without the write-ahead commit no handover may begin, and the row is "
            "provably still pre-effect",
            phases=["PRE_EXTERNAL_EFFECT"],
            recovery=[{"phase": "PRE_EXTERNAL_EFFECT", **requeued}]),
        write_ahead_case(
            "send_write_ahead_commit_failed", "failed",
            "HANDOVER_REFUSED", "WRITE_AHEAD_COMMIT_FAILED", False, False,
            "PRE_EXTERNAL_EFFECT",
            "a provably failed commit leaves the row durably pre-effect and authorises "
            "nothing, so a later ordinary first delivery may start from the beginning",
            phases=["PRE_EXTERNAL_EFFECT"],
            recovery=[{"phase": "PRE_EXTERNAL_EFFECT", **requeued}]),
        write_ahead_case(
            "send_write_ahead_commit_unproven", "unproven",
            "HANDOVER_REFUSED", "WRITE_AHEAD_COMMIT_UNPROVEN", False, False, None,
            "the committing process never learned the outcome; unknown is not proven, "
            "so no transport call may follow, but the commit may still have landed — "
            "recovery therefore follows whichever phase is actually on disk",
            phases=["PRE_EXTERNAL_EFFECT", "EXTERNAL_EFFECT_UNCLEAR"],
            recovery=[{"phase": "PRE_EXTERNAL_EFFECT", **requeued},
                      {"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}]),
        write_ahead_case(
            "send_write_ahead_committed", "committed",
            "HANDOVER_AUTHORISED", None, True, True, "EXTERNAL_EFFECT_UNCLEAR",
            "only a proven commit of EXTERNAL_EFFECT_UNCLEAR authorises the handover, "
            "and from that moment recovery can only block, never requeue",
            phases=["EXTERNAL_EFFECT_UNCLEAR"],
            recovery=[{"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}]),
        {
            "id": "drain_step_none",
            "kind": "drain",
            "state": {"drain_step": "NONE"},
            "expect": {
                "transport_reachable": False,
                "requires_write_ahead_commit": False,
                "requires_persisted_identity": False,
                "required_closed_gate": None,
                "requires_source_correlated_receipt": False,
                "source_correlation_available_at_pin": True,
                "reachable_now": False,
                "hands_to_transport_from_recovery": False,
            },
            "note": "a blocked, held or receipt-resolved row has no route to the network "
                    "at all; recovery never had one either",
        },
        {
            "id": "drain_step_write_ahead_first_delivery",
            "kind": "drain",
            "state": {"drain_step": "WRITE_AHEAD_FIRST_DELIVERY"},
            "expect": {
                "transport_reachable": True,
                "requires_write_ahead_commit": True,
                "requires_persisted_identity": False,
                "required_closed_gate": None,
                "requires_source_correlated_receipt": False,
                "source_correlation_available_at_pin": True,
                "reachable_now": True,
                "hands_to_transport_from_recovery": False,
            },
            "note": "the route a requeued row takes: a later drain attempt, and only "
                    "through the §8.7c write-ahead gate. This is the publish_queue "
                    "route, the one R6 shows does emit PublishedApplicationMessage, so "
                    "a receipt here can still be correlated to its source id",
        },
        {
            "id": "drain_step_transport_identical_replay",
            "kind": "drain",
            "state": {"drain_step": "WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY"},
            "expect": {
                "transport_reachable": True,
                "requires_write_ahead_commit": True,
                "requires_persisted_identity": True,
                "required_closed_gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY",
                "requires_source_correlated_receipt": True,
                "source_correlation_available_at_pin": False,
                "reachable_now": False,
                "hands_to_transport_from_recovery": False,
            },
            "note": "the replay route is defined and stays unreachable for two "
                    "independent reasons. R6 proved the identical re-drive itself, so "
                    "R6-G2 is no longer refuted by absence — but it is still OPEN for "
                    "CruxCoach, and R6 finding 1 adds the harder one: the resume path "
                    "that performs the replay emits no PublishedApplicationMessage, so "
                    "the source-correlated receipt this route requires cannot be "
                    "obtained at the pin. Closing R6-G2 alone would not make it usable",
        },
        post_handover_case(
            "send_post_handover_accepted_without_source_receipt",
            "accepted_without_receipt",
            "POST_HANDOVER_UNCLEAR", "ACCEPTED_WITHOUT_SOURCE_RECEIPT",
            "EXTERNAL_EFFECT_UNCLEAR", blocked_after_call,
            "R6 finding 1 made executable: resume_outbound_fanouts reports a published "
            "fanout but never emits PublishedApplicationMessage, so a first accepted "
            "delivery on that path leaves no typed source receipt. Acceptance the "
            "adapter cannot correlate to a source id is not observation: the row stays "
            "EXTERNAL_EFFECT_UNCLEAR and is never labelled sent"),
        post_handover_case(
            "send_post_handover_refused_after_commit", "refused",
            "POST_HANDOVER_UNCLEAR", "CALL_REFUSED_AFTER_AUTHORISATION",
            "EXTERNAL_EFFECT_UNCLEAR", blocked_after_call,
            "the authorised call came back refused, but EXTERNAL_EFFECT_UNCLEAR was "
            "already durable before it was made; there is no automatic way back to "
            "PRE_EXTERNAL_EFFECT and no fresh first delivery"),
        post_handover_case(
            "send_post_handover_error_after_commit", "error",
            "POST_HANDOVER_UNCLEAR", "CALL_ERROR_AFTER_AUTHORISATION",
            "EXTERNAL_EFFECT_UNCLEAR", blocked_after_call,
            "an error returned by the authorised call is not evidence that nothing was "
            "sent, so the row stays unclear"),
        post_handover_case(
            "send_post_handover_no_answer", "no_answer",
            "POST_HANDOVER_UNCLEAR", "CALL_WITHOUT_ANSWER",
            "EXTERNAL_EFFECT_UNCLEAR", blocked_after_call,
            "no answer at all is the weakest evidence of the three and changes nothing "
            "about the conservative outcome"),
        post_handover_case(
            "send_post_handover_receipt_persisted", "receipt_persisted",
            "POST_HANDOVER_OBSERVED", None,
            "EXTERNAL_EFFECT_OBSERVED",
            {"outcome": "RESOLVE_FROM_RECEIPT", "reason": None,
             "resulting_state": "SENT_LOCALLY", "automatic": True,
             "hands_to_transport": False, "next_transport_step": "NONE"},
            "only a durable typed receipt moves the row past unclear, and it resolves "
            "the row without handing anything to the transport again",
            receipt={"intent_id": "intent-7", "inner_event_id": revoke_id,
                     "source_message_ids": ["dd" * 32], "accepted_relays": [],
                     "canonical_at": None}),
        crash_case(
            "send_crash_before_write_ahead", "before_write_ahead",
            ["PRE_EXTERNAL_EFFECT"], False, False, False,
            [{"phase": "PRE_EXTERNAL_EFFECT", **requeued}],
            "nothing was committed and nothing was called, so the requeue is the one "
            "provably safe recovery"),
        crash_case(
            "send_crash_during_write_ahead_commit", "during_write_ahead_commit",
            ["PRE_EXTERNAL_EFFECT", "EXTERNAL_EFFECT_UNCLEAR"], False, False, False,
            [{"phase": "PRE_EXTERNAL_EFFECT", **requeued},
             {"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}],
            "the commit may or may not have landed; both observations are safe because "
            "the transport is only ever called after a proven commit"),
        crash_case(
            "send_crash_after_commit_before_handover", "after_commit_before_handover",
            ["EXTERNAL_EFFECT_UNCLEAR"], True, True, False,
            [{"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}],
            "durable state cannot distinguish this from a crash inside the handover, so "
            "recovery conservatively blocks instead of requeueing for another attempt"),
        crash_case(
            "send_crash_during_handover", "during_handover",
            ["EXTERNAL_EFFECT_UNCLEAR"], True, True, False,
            [{"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}],
            "the send may have taken external effect; the phase was committed before the "
            "call, so no worker can ever read this row as pre-effect"),
        crash_case(
            "send_crash_after_handover_before_receipt", "after_handover_before_receipt",
            ["EXTERNAL_EFFECT_UNCLEAR"], True, True, False,
            [{"phase": "EXTERNAL_EFFECT_UNCLEAR", **blocked_unclear}],
            "an effect without durable receipt evidence stays unclear rather than being "
            "promoted to observed"),
        crash_case(
            "send_crash_after_receipt_persisted", "after_receipt_persisted",
            ["EXTERNAL_EFFECT_OBSERVED"], True, True, True,
            [{"phase": "EXTERNAL_EFFECT_OBSERVED",
              "outcome": "RESOLVE_FROM_RECEIPT", "reason": None,
              "resulting_state": "SENT_LOCALLY", "automatic": True,
              "hands_to_transport": False, "next_transport_step": "NONE"}],
            "only durable typed-receipt evidence reaches EXTERNAL_EFFECT_OBSERVED, and it "
            "resolves the row without handing anything to the transport again",
            receipt={"intent_id": "intent-6", "inner_event_id": revoke_id,
                     "source_message_ids": ["dd" * 32], "accepted_relays": [],
                     "canonical_at": None}),
    ]

    def bound_case(cid, bound_key, insert_kind, at_outcome, above_outcome, above_reason,
                   state_key=None, note=None):
        limit = o.BOUNDS[bound_key]
        key = state_key or bound_key
        case = {
            "id": cid,
            "bound_key": bound_key,
            "limit": limit,
            "at_limit_state": {"insert_kind": insert_kind, key: limit},
            "above_limit_state": {"insert_kind": insert_kind, key: limit + 1},
            "expect": {"at_limit": at_outcome, "reason_at": None,
                       "above_limit": above_outcome, "reason_above": above_reason,
                       "evicts": False, "prunes_tombstone": False,
                       "prunes_safety_prefix": False,
                       "other_generations_affected": False,
                       "terminalises_before_insert": True},
        }
        if note:
            case["note"] = note
        return case

    bounds = [
        bound_case("bound_held_events_per_slot", "held_events_per_slot", "held",
                   o.HELD, o.TERMINAL, "STORAGE_BOUND_EXCEEDED", state_key="held_events"),
        bound_case("bound_held_bytes_per_slot", "held_bytes_per_slot", "held",
                   o.HELD, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   state_key="held_bytes_slot"),
        bound_case("bound_held_events_per_generation", "held_events_per_generation", "held",
                   o.HELD, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   state_key="held_events_generation"),
        bound_case("bound_held_bytes_per_generation", "held_bytes_per_generation", "held",
                   o.HELD, o.TERMINAL, "STORAGE_BOUND_EXCEEDED", state_key="held_bytes"),
        bound_case("bound_held_bytes_global", "held_bytes_global", "held",
                   o.HELD, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   note="the global byte ceiling is the third residence: per slot, per "
                        "generation and across all generations"),
        bound_case("bound_plan_detail_slots_per_publisher", "plan_detail_slots_per_publisher",
                   "accepted", o.APPLIED, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   state_key="plan_detail_slots"),
        bound_case("bound_grant_slots_per_publisher", "grant_slots_per_publisher",
                   "accepted", o.APPLIED, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   state_key="grant_slots"),
        bound_case("bound_accepted_sessions_per_grant", "sessions_per_logbook_grant",
                   "accepted", o.APPLIED, o.TERMINAL, "STORAGE_BOUND_EXCEEDED",
                   state_key="sessions"),
        bound_case("bound_delta_result_sessions", "sessions_per_logbook_grant",
                   "delta_result", o.APPLIED, o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID",
                   state_key="sessions",
                   note="a delta is checked against the resulting state; overflow rejects "
                        "the whole delta and occupies no sequence"),
        {
            "id": "bound_safety_prefix_never_pruned",
            "expect": {"evicts": False, "prunes_tombstone": False,
                       "prunes_safety_prefix": False,
                       "other_generations_affected": False,
                       "partial_application": False,
                       "terminalises_before_insert": True},
            "note": "overflow terminalises the offending generation before the insert; it "
                    "never evicts, never prunes a safety prefix or tombstone, and never "
                    "touches another generation",
        },
    ]

    restore = [
        {
            "id": "restore_new_device",
            "state": {"restored_on_new_device": True, "outbox_rows": 3,
                      "session_uuid": None, "plan_uuid": None,
                      "product_size_id": None, "state": "INACTIVE_READ_ONLY"},
            "expect": {"outbox_drains": 0, "sends": 0, "applies": 0,
                       "mints_identifier": False, "row_eligible": False,
                       "product_size_id": None, "read_only": True,
                       "sendable": False, "tombstones_sticky": True},
        },
        {
            "id": "restore_preserves_backed_up_uuid",
            "state": {"restored_on_new_device": True,
                      "session_uuid": b.session_1, "plan_uuid": b.plan_uuid},
            "expect": {"session_uuid": b.session_1, "plan_uuid": b.plan_uuid,
                       "mints_identifier": False, "row_eligible": True,
                       "read_only": True, "sendable": False,
                       "outbox_drains": 0, "tombstones_sticky": True},
            "note": "a preserved id is still unsendable on a new device: eligibility of "
                    "the row and sendability of the device are two different rules",
        },
        {
            "id": "restore_same_device_reopen",
            "state": {"restored_on_new_device": False,
                      "session_uuid": b.session_1, "plan_uuid": b.plan_uuid},
            "expect": {"session_uuid": b.session_1, "plan_uuid": b.plan_uuid,
                       "mints_identifier": False, "row_eligible": True,
                       "read_only": False, "sendable": True,
                       "outbox_drains": 0, "tombstones_sticky": True},
            "note": "the same-device branch of wire §10: reopen is not the new-device "
                    "restore, so the rows are neither read-only nor unsendable — and "
                    "restore still mints no identifier and keeps tombstones sticky",
        },
        {
            "id": "restore_cross_brand_product_size_fails_closed",
            "state": {"restored_on_new_device": False, "session_uuid": b.session_1,
                      "requires_product_size": True, "product_size_id": None},
            "expect": {"session_uuid": b.session_1, "product_size_id": None,
                       "defaults_product_size": False, "mints_identifier": False,
                       "row_eligible": False, "read_only": False,
                       "sendable": False, "outbox_drains": 0,
                       "tombstones_sticky": True},
            "note": "wire §10: an unset or cross-brand product size stays NULL and makes "
                    "the affected item ineligible. Asserted on the same device on "
                    "purpose, so the new-device rule cannot mask it",
        },
        {
            "id": "restore_present_product_size_stays_eligible",
            "state": {"restored_on_new_device": False, "session_uuid": b.session_1,
                      "requires_product_size": True, "product_size_id": "eu-42"},
            "expect": {"session_uuid": b.session_1, "product_size_id": "eu-42",
                       "defaults_product_size": False, "mints_identifier": False,
                       "row_eligible": True, "read_only": False,
                       "sendable": True, "outbox_drains": 0,
                       "tombstones_sticky": True},
            "note": "the control for the rule above: a real product size is eligible, so "
                    "the ineligibility is caused by the NULL and not by the flag",
        },
    ]

    inner = ev["grant_summary_revoke"]["inner_event_id"]
    runtime_gates = [
        {
            "id": "gate_receipt_offline_queued_is_legitimate",
            "kind": "receipt",
            "state": {"intent_id": "intent-1", "inner_event_id": inner,
                      "source_message_ids": [], "retry_kind": "none"},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY"},
            "note": "an empty source-id list is an offline state, never a failure signal",
        },
        {
            "id": "gate_receipt_transport_identical_retry",
            "kind": "receipt",
            "state": {"intent_id": "intent-2", "inner_event_id": inner,
                      "source_message_ids": ["aa" * 32, "aa" * 32],
                      "retry_kind": "transport_identical"},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY"},
            "note": "the same transport message observed twice is one source id, not two",
        },
        {
            "id": "gate_receipt_second_transport_id_is_terminal",
            "kind": "receipt",
            "state": {"intent_id": "intent-3", "inner_event_id": inner,
                      "source_message_ids": ["aa" * 32, "bb" * 32],
                      "retry_kind": "none"},
            "expect": {"outcome": o.TERMINAL, "reason": "RETRY_NOT_TRANSPORT_IDENTICAL",
                       "gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY"},
            "note": "two distinct source ids for one inner id destroy the single-valued "
                    "correlation wire §8.4 depends on",
        },
        {
            "id": "gate_receipt_requeued_payload_is_not_a_retry",
            "kind": "receipt",
            "state": {"intent_id": "intent-4", "inner_event_id": inner,
                      "source_message_ids": ["aa" * 32], "retry_kind": "new_transport_id"},
            "expect": {"outcome": o.TERMINAL, "reason": "RETRY_NOT_TRANSPORT_IDENTICAL",
                       "gate": "R6-G2-TRANSPORT-IDENTICAL-RETRY"},
            "note": "the removed R5 requeue path: a new MLS transport id for the same "
                    "inner id may never be presented as a retry",
        },
        {
            "id": "gate_ingress_live_lag_closed_by_replay",
            "kind": "ingress",
            "state": {"live_lagged": True, "durable_replay_available": True,
                      "replay_gap_closed": True, "pruned_before_ack": False},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G3-BROADCAST-LAG-REPLAY"},
        },
        {
            "id": "gate_ingress_live_lag_not_yet_replayed",
            "kind": "ingress",
            "state": {"live_lagged": True, "durable_replay_available": True,
                      "replay_gap_closed": False, "pruned_before_ack": False},
            "expect": {"outcome": o.HELD, "reason": "HOLD_REPLAY_INCOMPLETE",
                       "gate": "R6-G3-BROADCAST-LAG-REPLAY"},
        },
        {
            "id": "gate_ingress_without_durable_replay_holds",
            "kind": "ingress",
            "state": {"live_lagged": True, "durable_replay_available": False,
                      "replay_gap_closed": False, "pruned_before_ack": False},
            "expect": {"outcome": o.HELD, "reason": "HOLD_REPLAY_INCOMPLETE",
                       "gate": "R6-G3-BROADCAST-LAG-REPLAY"},
            "note": "no durable replay means no completeness claim; holding is the "
                    "fail-closed answer, never assuming the live stream was complete",
        },
        {
            "id": "gate_ingress_pruned_before_ack_is_terminal",
            "kind": "ingress",
            "state": {"live_lagged": True, "durable_replay_available": True,
                      "replay_gap_closed": False, "pruned_before_ack": True},
            "expect": {"outcome": o.TERMINAL, "reason": "PROJECTION_AUTHORITY_INVALID",
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "a gap retention already pruned can never be replayed; nothing is "
                    "reconstructed and nothing is guessed",
        },
        {
            "id": "gate_ingress_unacked_survives_six_epoch_advances",
            "kind": "ingress",
            "state": {"live_lagged": False, "durable_replay_available": True,
                      "replay_gap_closed": True, "pruned_before_ack": False,
                      "epoch_advances": 6, "host_acked": False,
                      "unacked_item_present": True},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "wire §8.5: more than five epoch advances MUST NOT remove an "
                    "unacknowledged item; the epoch count never decides the outcome "
                    "while the item is still there",
        },
        {
            "id": "gate_ingress_unacked_lost_after_epoch_advances_is_terminal",
            "kind": "ingress",
            "state": {"live_lagged": False, "durable_replay_available": True,
                      "replay_gap_closed": True, "pruned_before_ack": False,
                      "epoch_advances": 6, "host_acked": False,
                      "unacked_item_present": False},
            "expect": {"outcome": o.TERMINAL, "reason": "PROJECTION_AUTHORITY_INVALID",
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "an unacknowledged item that is gone after epoch advances is the "
                    "forbidden loss of wire §8.5, so it fails closed instead of being "
                    "reconstructed from a partial view",
        },
        {
            "id": "gate_ingress_acked_item_may_be_gone_after_epoch_advances",
            "kind": "ingress",
            "state": {"live_lagged": False, "durable_replay_available": True,
                      "replay_gap_closed": True, "pruned_before_ack": False,
                      "epoch_advances": 6, "host_acked": True,
                      "unacked_item_present": False},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "after host ACK the CruxCoach transaction has committed and the "
                    "idempotency row decides, so the same absence is ordinary and not "
                    "a loss — this is the boundary the ACK exists to draw",
        },
        {
            "id": "gate_projection_raw_store_is_never_authority",
            "kind": "projection_authority",
            "state": {"authority": "mdk_raw", "raw_pruned": True, "host_acked": True},
            "expect": {"outcome": o.TERMINAL, "reason": "PROJECTION_AUTHORITY_INVALID",
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "R5 criterion E: a real retention sweep deletes expired raw custom "
                    "rows, so the raw app store is not long-lived CruxCoach state",
        },
        {
            "id": "gate_projection_durable_survives_a_pruned_raw_store",
            "kind": "projection_authority",
            "state": {"authority": "cruxcoach_durable", "raw_pruned": True,
                      "host_acked": True},
            "expect": {"outcome": o.APPLIED, "reason": None,
                       "gate": "R6-G1-DURABLE-PROJECTION"},
        },
        {
            "id": "gate_projection_pruned_before_host_ack_is_terminal",
            "kind": "projection_authority",
            "state": {"authority": "cruxcoach_durable", "raw_pruned": True,
                      "host_acked": False},
            "expect": {"outcome": o.TERMINAL, "reason": "PROJECTION_AUTHORITY_INVALID",
                       "gate": "R6-G1-DURABLE-PROJECTION"},
        },
        # wire §8.4 with FEAT-062 §5 gate 5: the adapter's own invalidation
        # reason. All four closed reasons are decided, and the durability
        # dimension is decided with them, because at the pin the reason is
        # emitted in memory after the canonical commit while only the
        # invalidated boolean is persisted — a non-durable reason is the
        # expected observation, and the answer is a reported FAIL.
        {
            "id": "gate_invalidation_losing_branch_reason_is_durable",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a1" * 32],
                      "source_message_id": "b1" * 32,
                      "adapter_reason": "LOSING_BRANCH",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": "LOSING_BRANCH",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "the branch-selection case R6 exercised only with an injected "
                    "trigger; the generation is terminal either way",
        },
        {
            "id": "gate_invalidation_beyond_anchor_reason_is_durable",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a2" * 32],
                      "source_message_id": "b2" * 32,
                      "adapter_reason": "BEYOND_ANCHOR",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": "BEYOND_ANCHOR",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
        },
        {
            "id": "gate_invalidation_beyond_app_retention_reason_is_durable",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a3" * 32],
                      "source_message_id": "b3" * 32,
                      "adapter_reason": "BEYOND_APP_RETENTION",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": "BEYOND_APP_RETENTION",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G1-DURABLE-PROJECTION"},
            "note": "retention withdrawing a stored event is terminal for the "
                    "generation; it is never repaired from the raw store",
        },
        {
            "id": "gate_invalidation_undecryptable_reason_is_durable",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a4" * 32],
                      "source_message_id": "b4" * 32,
                      "adapter_reason": "UNDECRYPTABLE_IN_CANONICAL_STATE",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": "UNDECRYPTABLE_IN_CANONICAL_STATE",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
        },
        {
            "id": "gate_invalidation_reason_not_restart_durable_fails_gate_5",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a5" * 32],
                      "source_message_id": "b5" * 32,
                      "adapter_reason": "LOSING_BRANCH",
                      "reason_restart_durable": False},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": None,
                       "reason_usable": False, "gate_reported_fail": True,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "the pin's own shape: the invalidated boolean persists and the "
                    "reason does not. FEAT-062 §5 gate 5 reports FAIL rather than "
                    "recording a reason that cannot survive a restart",
        },
        {
            "id": "gate_invalidation_absent_signal_after_a_rollback_fails_gate_5",
            "kind": "invalidation_signal",
            "state": {"signal_present": False, "branch_rolled_back": True},
            "expect": {"outcome": o.TERMINAL,
                       "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
                       "adapter_reason_recorded": None,
                       "reason_usable": False, "gate_reported_fail": True,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "FEAT-062 R8 A2, the null case: the payload's branch was "
                    "observably rolled back and no adapter invalidation for that "
                    "payload ever arrived. R5 and R6 only planned for a reason that "
                    "is not durable; this is the shape R8 observed on the pin's "
                    "direct fork-recovery seam. Absence never reads as 'still "
                    "canonical' — §5 gate 5 reports FAIL and the generation "
                    "terminalises from the commit-level rollback it did see",
        },
        {
            "id": "gate_invalidation_reason_outside_the_closed_set",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a6" * 32],
                      "source_message_id": "b6" * 32,
                      "adapter_reason": "SOMETHING_ELSE",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "STORED_EVENT_INVALIDATED",
                       "adapter_reason_recorded": None,
                       "reason_usable": False, "gate_reported_fail": True,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "no peer- or adapter-supplied text enters the reason vocabulary",
        },
        {
            "id": "gate_invalidation_zero_match_dominates_a_durable_reason",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": [],
                      "source_message_id": "b7" * 32,
                      "adapter_reason": "LOSING_BRANCH",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "SOURCE_MAPPING_MISSING",
                       "adapter_reason_recorded": "LOSING_BRANCH",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "a perfectly good reason never repairs an unusable mapping; no "
                    "inner id is ever guessed for it",
        },
        {
            "id": "gate_invalidation_without_a_source_id_is_a_missing_mapping",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["aa" * 32],
                      "source_message_id": None,
                      "adapter_reason": "LOSING_BRANCH",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "SOURCE_MAPPING_MISSING",
                       "adapter_reason_recorded": "LOSING_BRANCH",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
            "note": "wire §8.4 requires exactly one source message id per signal. A "
                    "signal that names none maps nothing, however many inner events "
                    "it claims to have matched, so it fails closed on the mapping",
        },
        {
            "id": "gate_invalidation_multi_match_dominates_a_durable_reason",
            "kind": "invalidation_signal",
            "state": {"matched_inner_event_ids": ["a8" * 32, "a9" * 32],
                      "source_message_id": "b8" * 32,
                      "adapter_reason": "BEYOND_ANCHOR",
                      "reason_restart_durable": True},
            "expect": {"outcome": o.TERMINAL, "reason": "SOURCE_MAPPING_AMBIGUOUS",
                       "adapter_reason_recorded": "BEYOND_ANCHOR",
                       "reason_usable": True, "gate_reported_fail": False,
                       "claims_exactly_once_invalidation": False,
                       "gate": "R6-G5-CUSTOM-REORG"},
        },
        {
            "id": "gate_exactly_once_local_reduction_is_permitted",
            "kind": "exactly_once",
            "state": {"scope": "cruxcoach_reduction"},
            "expect": {"outcome": o.EXACTLY_ONCE_PERMITTED, "reason": None,
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
        },
        {
            "id": "gate_exactly_once_inner_id_dedupe_is_permitted",
            "kind": "exactly_once",
            "state": {"scope": "inner_event_id"},
            "expect": {"outcome": o.EXACTLY_ONCE_PERMITTED, "reason": None,
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
        },
        {
            "id": "gate_exactly_once_source_id_dedupe_is_permitted",
            "kind": "exactly_once",
            "state": {"scope": "source_message_id"},
            "expect": {"outcome": o.EXACTLY_ONCE_PERMITTED, "reason": None,
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
            "note": "duplicate source delivery is a no-op locally; that is a statement "
                    "about this device's reduction and about nothing on the wire",
        },
        {
            "id": "gate_exactly_once_live_emission_is_forbidden",
            "kind": "exactly_once",
            "state": {"scope": "live_emission"},
            "expect": {"outcome": o.EXACTLY_ONCE_FORBIDDEN,
                       "reason": "EXACTLY_ONCE_SCOPE_EXCEEDED",
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
            "note": "forbidden as a claim even though R6 criterion 2 observed exactly "
                    "one live emission and exactly one raw row per side: that run was "
                    "one host process against a MockRelay, and the claim would be about "
                    "relays, networks and peers it never touched",
        },
        {
            "id": "gate_exactly_once_relay_delivery_is_forbidden",
            "kind": "exactly_once",
            "state": {"scope": "relay_delivery"},
            "expect": {"outcome": o.EXACTLY_ONCE_FORBIDDEN,
                       "reason": "EXACTLY_ONCE_SCOPE_EXCEEDED",
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
        },
        {
            "id": "gate_exactly_once_raw_row_count_is_forbidden",
            "kind": "exactly_once",
            "state": {"scope": "raw_row_count"},
            "expect": {"outcome": o.EXACTLY_ONCE_FORBIDDEN,
                       "reason": "EXACTLY_ONCE_SCOPE_EXCEEDED",
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
        },
        {
            "id": "gate_exactly_once_network_delivery_is_forbidden",
            "kind": "exactly_once",
            "state": {"scope": "network_delivery"},
            "expect": {"outcome": o.EXACTLY_ONCE_FORBIDDEN,
                       "reason": "EXACTLY_ONCE_SCOPE_EXCEEDED",
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
            "note": "one of the five scopes threat-model forbidden claim 10 names by "
                    "hand; R6's host-scope run touched no network at all, so the "
                    "observation cannot even be about this",
        },
        {
            "id": "gate_exactly_once_peer_receipt_is_forbidden",
            "kind": "exactly_once",
            "state": {"scope": "peer_receipt"},
            "expect": {"outcome": o.EXACTLY_ONCE_FORBIDDEN,
                       "reason": "EXACTLY_ONCE_SCOPE_EXCEEDED",
                       "gate": "R6-G4-EXACTLY-ONCE-SCOPE"},
            "note": "v1 defines no peer ACK at all, so there is nothing to be once about",
        },
        {
            # The published §11.2 table, declared as data so it is bound to the
            # oracle map and to the document in one place. Every scenario above
            # asserts one scope through the evaluator; this asserts that the
            # table a reader sees says the same thing about all eight.
            "id": "gate_exactly_once_published_claim_table",
            "kind": "exactly_once_table",
            "map": {
                "cruxcoach_reduction": "permitted",
                "inner_event_id": "permitted",
                "source_message_id": "permitted",
                "live_emission": "forbidden",
                "relay_delivery": "forbidden",
                "network_delivery": "forbidden",
                "raw_row_count": "forbidden",
                "peer_receipt": "forbidden",
            },
            "note": "wire §11.2 governs the claim, R6-G4-EXACTLY-ONCE-SCOPE governs the "
                    "evidence; closing that gate would move no row here",
        },
    ]

    # ------------------------------------------------------------------
    # The R6 evidence reconciliation, as literal expectations.
    #
    # These are deliberately *not* derived from the oracle registry: the
    # verifier compares each literal against `o.R6_GATES`, so mutating any
    # leaf here — a capability, an evidence scope, a closure flag — has to
    # be detected. The three dimensions are kept apart on purpose:
    #
    #   capability          what R6 actually demonstrated about the pin
    #   evidence_scope      the surface that demonstrated it, never wider
    #   cruxcoach_closure   whether that is enough to close the gate here
    #
    # R6 moved the first two for six of the eight gates. It moved the third
    # for none of them, and every gate therefore carries a named, non-empty
    # blocking remainder. `status` stays OPEN throughout.
    # ------------------------------------------------------------------
    r6_gates = [
        {
            "id": "R6-G1-DURABLE-PROJECTION",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "E",
                "r5_verdict": "PASS",
                "r6_criterion": None,
                "r6_verdict": "NOT DECIDED",
                "capability": "ABSENT",
                "evidence_scope": "NONE",
                "cruxcoach_closure": False,
            },
            "note": "R6 decided nothing here. The product design lock stays NO-GO, "
                    "active group retention still prunes the raw app store, and "
                    "finding 1 makes the hole deeper rather than shallower",
        },
        {
            "id": "R6-G2-TRANSPORT-IDENTICAL-RETRY",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "C",
                "r5_verdict": "FAIL",
                "r6_criterion": "1",
                "r6_verdict": "PASS",
                "capability": "PROVEN_AT_HOST_SCOPE",
                "evidence_scope": "HOST_RUST_MOCKRELAY",
                "cruxcoach_closure": False,
            },
            "note": "the R5 claim of absence is refuted: R6 criterion 1 re-published "
                    "the open endpoints with an identical message id and identical "
                    "ciphertext after a mid-fanout death. The gate still does not "
                    "close, because finding 1 removes the source correlation that "
                    "CruxCoach needs from exactly that path",
        },
        {
            "id": "R6-G3-BROADCAST-LAG-REPLAY",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "B",
                "r5_verdict": "FAIL",
                "r6_criterion": "3",
                "r6_verdict": "PASS",
                "capability": "PROVEN_AT_HOST_SCOPE",
                "evidence_scope": "HOST_RUST_CRATE_INTERNAL",
                "cruxcoach_closure": False,
            },
            "note": "a real 2152-event loss really was recovered exactly once, but "
                    "from a crate-internal test against a private broadcast sender; "
                    "no adapter-reachable surface drives it",
        },
        {
            "id": "R6-G4-EXACTLY-ONCE-SCOPE",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "C1",
                "r5_verdict": "PARTIAL",
                "r6_criterion": "2",
                "r6_verdict": "PASS",
                "capability": "PROVEN_AT_HOST_SCOPE",
                "evidence_scope": "HOST_RUST_MOCKRELAY",
                "cruxcoach_closure": False,
            },
            "note": "exactly-once live emission and one raw row held under deliberate "
                    "redelivery pressure, inside one host process. That is not relay "
                    "or network exactly-once, which §11.2 forbids claiming anyway",
        },
        {
            "id": "R6-G5-CUSTOM-REORG",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "C",
                "r5_verdict": "FAIL",
                "r6_criterion": "4",
                "r6_verdict": "NOT MET",
                "capability": "PARTIAL",
                "evidence_scope": "HOST_RUST_MOCKRELAY",
                "cruxcoach_closure": False,
            },
            "note": "the app half is real; the trigger is injected. This is R6's own "
                    "blocking open gate 1 and the reason the spike itself is NO-GO",
        },
        {
            "id": "R6-G6-UNIFFI-FORWARDING",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "A/F",
                "r5_verdict": "NOT TESTED",
                "r6_criterion": "5",
                "r6_verdict": "PASS",
                "capability": "PROVEN_AT_HOST_SCOPE",
                "evidence_scope": "HOST_RUST_MOCKRELAY",
                "cruxcoach_closure": False,
            },
            "note": "exported bindings really did forward a hostile payload byte for "
                    "byte and refuse all eleven reserved kinds, but construction went "
                    "through the crate-internal loopback-gated constructor",
        },
        {
            "id": "R6-G7-INBOUND-NATIVE-DELIVERY",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "D",
                "r5_verdict": "NOT TESTED",
                "r6_criterion": "6",
                "r6_verdict": "PASS",
                "capability": "PROVEN_AT_HOST_SCOPE",
                "evidence_scope": "HOST_RUST_MOCKRELAY",
                "cruxcoach_closure": False,
            },
            "note": "the negative proof is real and positively controlled, on the "
                    "host-side stream the native callback shares — not on the native "
                    "callback itself",
        },
        {
            "id": "R6-G8-INSTRUMENTATION-RUN",
            "expect": {
                "status": "OPEN",
                "r5_criterion": "F",
                "r5_verdict": "NOT TESTED",
                "r6_criterion": "8",
                "r6_verdict": "NOT TESTED",
                "capability": "NOT_TESTED",
                "evidence_scope": "NONE",
                "cruxcoach_closure": False,
            },
            "note": "no device, no emulator, no virtualisation; honestly recorded as "
                    "NOT TESTED and not simulated. While this stays open, every other "
                    "gate's host-scope evidence stays host-scope",
        },
    ]

    # The non-gate half of what R6 left open. Literal again, and asserted
    # against the oracle registry, so neither a finding that changed a rule nor
    # a release blocker can be dropped when prose is rewritten.
    r6_conditions = [
        {
            "id": "R6-F1-RESUME-WITHOUT-PUBLISHED-APP-MESSAGE",
            "expect": {"kind": "finding", "blocks_cruxcoach": True},
            "note": "the one finding that forced new normative text: it is why §8.7d "
                    "has ACCEPTED_WITHOUT_SOURCE_RECEIPT and why §8.7e's replay route "
                    "needs a source-correlated receipt on top of a closed R6-G2",
        },
        {
            "id": "R6-F2-DEFINITIVE-REJECTION-IS-TERMINAL",
            "expect": {"kind": "finding", "blocks_cruxcoach": False},
            "note": "a delivery bound, already covered by labels that claim neither "
                    "delivery nor non-delivery",
        },
        {
            "id": "R6-F3-NO-ON-DEMAND-RESUME",
            "expect": {"kind": "finding", "blocks_cruxcoach": False},
            "note": "limits when a replay could happen; §8.7e forbids that route "
                    "outright while R6-G2 is open, so no rule changes",
        },
        {
            "id": "R6-F4-DRAIN-PATH-ASYMMETRY",
            "expect": {"kind": "finding", "blocks_cruxcoach": False},
            "note": "§8.4 already terminalises every stored-event invalidation "
                    "whichever path reports it, so the asymmetry relaxes nothing",
        },
        {
            "id": "R6-F5-CUSTOM-REORG-TRIGGER-INJECTED",
            "expect": {"kind": "finding", "blocks_cruxcoach": True},
            "note": "R6's own blocking open gate: the app half is proven, the trigger "
                    "is not, so R6-G5 stays PARTIAL and the spike stays NO-GO",
        },
        {
            "id": "R6-B1-FIVE-RED-RELAY-TESTS",
            "expect": {"kind": "release_blocker", "blocks_cruxcoach": False},
            "note": "deterministic at the bare pin and at origin/master, so pre-existing "
                    "upstream defects rather than an R5 or R6 regression — and a red "
                    "release gate regardless of every capability above",
        },
        {
            "id": "R6-B2-NO-CONSUMER-APKS",
            "expect": {"kind": "release_blocker", "blocks_cruxcoach": False},
            "note": "no reproducible APK build from the supplied artefacts, and with "
                    "R6-G8 never run there is neither a package nor a run to point at",
        },
    ]

    # wire §8.7b/§8.7e — the full truth table of the replay admission.
    #
    # Three independent product conditions must ALL hold before a
    # transport-identical replay may be admitted. R6 proved the pin can perform
    # the re-drive, which satisfies none of them by itself: the persisted
    # identity is a property of the row, the gate is a CruxCoach decision, and
    # the source-correlated receipt is what finding 1 removes from the resume
    # path. Every combination is enumerated so that no single condition can
    # quietly become sufficient.
    def admission(persisted, gate, corr, blocking, note):
        return {
            "id": ("replay_admission_"
                   f"{'P' if persisted else 'p'}{'G' if gate else 'g'}{'C' if corr else 'c'}"),
            "state": {"persisted_identity": persisted,
                      "product_gate_closed": gate,
                      "source_correlation_available": corr},
            "expect": {
                "admitted": not blocking,
                "blocking": blocking,
                # The reason the *row* would carry, which is what a user sees.
                # It is asserted per combination so the mapping of §8.7b cannot
                # collapse: in particular `REPLAY_NO_SOURCE_CORRELATION` alone
                # must still produce a blocked row, which is the whole content
                # of R6 finding 1 for this contract.
                "blocked_recovery_reason": o.recovery_reason_for_blocking(blocking),
            },
            "note": note,
        }

    NO_ID = "REPLAY_NO_PERSISTED_IDENTITY"
    NO_GATE = "REPLAY_PRODUCT_GATE_OPEN"
    NO_CORR = "REPLAY_NO_SOURCE_CORRELATION"
    replay_admission = [
        admission(False, False, False, [NO_ID, NO_GATE, NO_CORR],
                  "nothing holds; every reason is reported at once, because this is a "
                  "conjunction and not a chain of first-failure excuses"),
        admission(False, False, True, [NO_ID, NO_GATE],
                  "a correlatable receipt alone admits nothing"),
        admission(False, True, False, [NO_ID, NO_CORR],
                  "closing the CruxCoach gate alone admits nothing"),
        admission(False, True, True, [NO_ID],
                  "without a persisted identical transport identity there is nothing to "
                  "replay, however open the rest is"),
        admission(True, False, False, [NO_GATE, NO_CORR],
                  "the pin's proven re-drive plus a persisted identity is still not "
                  "enough: this is today's real state, and it stays blocked"),
        admission(True, False, True, [NO_GATE],
                  "even with correlation available the product gate governs"),
        admission(True, True, False, [NO_CORR],
                  "the case the wire contract calls out explicitly: closing R6-G2 alone "
                  "does not open the replay route, because the resume path that performs "
                  "it emits no PublishedApplicationMessage (finding 1)"),
        admission(True, True, True, [],
                  "the only admitting combination, and it is unreachable at the pin: it "
                  "needs the gate closed AND a source-correlated receipt the resume path "
                  "does not produce"),
    ]

    # Two published tables that a reader reaches before any of the above, and
    # that no check read: the registry row in `docs/specs/INDEX.md` — the entry
    # point that says whether this feature may be worked on at all — and the
    # per-gate obligation table of threat model §6. Both are inside the bundle
    # manifest, so an edit changes the digest; a digest says only *that* a file
    # changed, and `--write-manifest` is the documented answer to any edit.
    published_bindings = [
        {
            "id": "index_registry_row_for_feat_062",
            "kind": "registry_row",
            "document": "docs/specs/INDEX.md",
            "feature": "FEAT-062",
            "expect": {
                "release": "v0.2.3",
                "status": "design-review",
                "queue": "blocked",
                "spec_path": "0.2.3/FEAT-062-personal-information-sharing.md",
            },
            "note": "the registry row, the spec's own front matter and the INDEX status "
                    "legend have to agree; a row reading design-locked would invite "
                    "exactly the work §0 records a No-Go for",
        },
        {
            "id": "threat_model_outstanding_gate_obligations",
            "kind": "gate_obligations",
            "document": "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
            "expect": {
                "header": ["Gate", "Outstanding verification obligation after R6"],
                "rows_per_gate": 1,
                "claims_closure": False,
            },
            "note": "§6 is where the bundle says what each open gate still owes. An "
                    "emptied cell or a dropped row leaves an obligation nobody can "
                    "read, while every registry assertion stays green",
        },
        {
            # wire §8.7c prints which closed reason each write-ahead result
            # refuses with. The oracle decides the same mapping, and a printed
            # mapping nothing reads is decoration however normative it sounds.
            "id": "wire_write_ahead_refusal_reason_mapping",
            "kind": "reason_mapping",
            "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
            "header": ["Refusal", "Closed reason", "What is established",
                       "Durable phase", "What may follow"],
            "oracle_map": "WRITE_AHEAD_REFUSAL_REASONS",
            "expect": {
                "map": {
                    "not_attempted": "WRITE_AHEAD_NOT_COMMITTED",
                    "failed": "WRITE_AHEAD_COMMIT_FAILED",
                    "unproven": "WRITE_AHEAD_COMMIT_UNPROVEN",
                },
            },
            "note": "`committed` is absent on purpose: it authorises the call and "
                    "refuses nothing, so it has no refusal reason to print",
        },
        {
            # wire §8.7b prints which blocked recovery reason each unmet replay
            # condition produces — including the one no recovery can reach while
            # R6-G2-TRANSPORT-IDENTICAL-RETRY is open, which is exactly the row
            # a reader would use to conclude that closing the gate is enough.
            "id": "wire_replay_blocking_to_recovery_reason_mapping",
            "kind": "reason_mapping",
            "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
            "header": ["Unmet condition", "Blocked recovery reason (§8.7b)"],
            "oracle_map": "REPLAY_BLOCKING_TO_RECOVERY_REASON",
            "expect": {
                "map": {
                    "REPLAY_NO_PERSISTED_IDENTITY": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
                    "REPLAY_PRODUCT_GATE_OPEN": "RECOVERY_RETRY_GATE_OPEN",
                    "REPLAY_NO_SOURCE_CORRELATION": "RECOVERY_SOURCE_CORRELATION_UNAVAILABLE",
                },
            },
        },
    ] + _published_tables()

    # wire §10 and FEAT-062 §8. One case per closed cause, so the decision the
    # two published tables are bound to is also driven on its own — a table-only
    # function would be decided by nothing but the document it checks.
    retirement = [
        {
            "id": "retirement_revoke",
            "cause": "REVOKE",
            "expect": {"hidden": True, "permanently_retired": False,
                       "generation_terminal": False,
                       "purge_deadline_s": 86_400, "purge_measured_from": "cause"},
            "note": "an explicit revoke hides and purges that grant, and leaves the "
                    "generation alive for the grants that were not revoked",
        },
        {
            "id": "retirement_level_downgrade",
            "cause": "LEVEL_DOWNGRADE",
            "expect": {"hidden": True, "permanently_retired": True,
                       "generation_terminal": False,
                       "purge_deadline_s": 86_400, "purge_measured_from": "cause"},
            "note": "FRIEND to ACQUAINTANCE retires detail permanently: a later "
                    "re-upgrade never revives it, which is why this is the one cause "
                    "that is permanent without being terminal",
        },
        {
            "id": "retirement_block_or_remove",
            "cause": "BLOCK_OR_REMOVE",
            "expect": {"hidden": True, "permanently_retired": False,
                       "generation_terminal": True,
                       "purge_deadline_s": 86_400, "purge_measured_from": "cause"},
        },
        {
            "id": "retirement_expiry",
            "cause": "EXPIRY",
            "expect": {"hidden": True, "permanently_retired": False,
                       "generation_terminal": False,
                       "purge_deadline_s": 7_776_000,
                       "purge_measured_from": "expiry"},
            "note": "the only cause whose window runs from expiry rather than from the "
                    "cause, and the one §10 says must not be conflated with the equally "
                    "long maximum grant duration measured from issue",
        },
        {
            "id": "retirement_superseding_set",
            "cause": "SUPERSEDING_SET",
            "expect": {"hidden": True, "permanently_retired": False,
                       "generation_terminal": False, "purge_deadline_s": 0,
                       "purge_measured_from": "same_local_transition"},
            "note": "not a deadline at all: the purge commits with the transition that "
                    "hides the superseded grant, so there is no window to miss",
        },
        {
            "id": "retirement_disband",
            "cause": "DISBAND",
            "expect": {"hidden": True, "permanently_retired": False,
                       "generation_terminal": True,
                       "purge_deadline_s": 86_400, "purge_measured_from": "cause"},
            "note": "a hostile unilateral disband is an authentic authorised terminal "
                    "action, so it retires exactly as a block does",
        },
    ]

    return _assemble(
        r6_gates, r6_conditions, replay_admission, published_bindings, sequences,
        topology_phases, state_machine, rollback, clock, outbox, send_handover,
        bounds, restore, retirement, runtime_gates,
    )


#: The rows every remaining normative table prints, stated here as literals.
#:
#: These are a *third* statement of the same rules: the oracle decides them from
#: the functions the scenarios drive, the documents print them, and this declares
#: them. All three are compared, so no side can drift alone and no row here is
#: self-confirming — mutating one disagrees with the oracle immediately.
#:
#: Before this existed the bundle bound only its label, claim, refusal and
#: obligation tables. Every other normative table was decoration: §8.7b could be
#: edited to requeue an unclear row, §8.7d to call an uncorrelated acceptance an
#: observed delivery, §8.7c to leave a pre-effect row after an authorised
#: handover, §8.4a to hold instead of terminalise after activation, and §6.3 to
#: lower `LOGBOOK_DETAIL` to `ACQUAINTANCE` — each with the whole run green.
_UNCLEAR = ["RECOVERY_BLOCKED", "RECOVERY_EXTERNAL_EFFECT_UNCLEAR"]
_BLOCKED_UNCLEAR = {"state": ["DELIVERY_UNCLEAR"]}
_LEASE_RECOVERY_ROWS = [
    {"key": "lease_live", "recovery": ["LEASE_HELD"], "state": ["IN_FLIGHT"]},
    {"key": "pre_external_effect", "recovery": ["RECOVER_QUEUED"], "state": ["QUEUED"]},
    # Both documents print "`SENT_LOCALLY` or `MDK_CANONICAL`, exactly as §8.7
    # earns it", and both halves are derived from a real receipt.
    {"key": "observed_with_receipt", "recovery": ["RESOLVE_FROM_RECEIPT"],
     "state": ["SENT_LOCALLY", "MDK_CANONICAL"]},
    {"key": "observed_without_receipt",
     "recovery": ["RECOVERY_BLOCKED", "RECOVERY_EVIDENCE_INCOMPLETE"], **_BLOCKED_UNCLEAR},
    {"key": "unclear_no_transport", "recovery": _UNCLEAR, **_BLOCKED_UNCLEAR},
    {"key": "unclear_unreplayable_transport", "recovery": _UNCLEAR, **_BLOCKED_UNCLEAR},
    {"key": "unclear_replayable_gate_open",
     "recovery": ["RECOVERY_BLOCKED", "RECOVERY_RETRY_GATE_OPEN"], **_BLOCKED_UNCLEAR},
    # The two counterfactuals wire §8.7b publishes on purpose: closing
    # R6-G2-TRANSPORT-IDENTICAL-RETRY alone still lands on the correlation the
    # resume path cannot supply (R6 finding 1).
    {"key": "unclear_replayable_gate_closed_uncorrelated",
     "recovery": ["RECOVERY_BLOCKED", "RECOVERY_SOURCE_CORRELATION_UNAVAILABLE"],
     **_BLOCKED_UNCLEAR},
    {"key": "unclear_replayable_gate_closed_correlated",
     "recovery": ["RECOVER_TRANSPORT_IDENTICAL"], "state": ["QUEUED"]},
]

_UNCLEAR_PHASE = ["EXTERNAL_EFFECT_UNCLEAR"]
_SEND_CRASH_ROWS = [
    {"key": "before_write_ahead", "phases": ["PRE_EXTERNAL_EFFECT"],
     "authorised": "no", "recovery": ["RECOVER_QUEUED"]},
    {"key": "during_write_ahead_commit",
     "phases": ["PRE_EXTERNAL_EFFECT", "EXTERNAL_EFFECT_UNCLEAR"],
     "authorised": "no", "recovery": ["RECOVER_QUEUED", "RECOVERY_BLOCKED"]},
    {"key": "after_commit_before_handover", "phases": _UNCLEAR_PHASE,
     "authorised": "yes", "recovery": ["RECOVERY_BLOCKED"]},
    {"key": "during_handover", "phases": _UNCLEAR_PHASE,
     "authorised": "yes", "recovery": ["RECOVERY_BLOCKED"]},
    {"key": "after_handover_before_receipt", "phases": _UNCLEAR_PHASE,
     "authorised": "yes", "recovery": ["RECOVERY_BLOCKED"]},
    {"key": "after_receipt_persisted", "phases": ["EXTERNAL_EFFECT_OBSERVED"],
     "authorised": "yes", "recovery": ["RESOLVE_FROM_RECEIPT"]},
]

#: §8.4a's outcome cell decides two things: the disposition word a reader sees,
#: and the closed fault code where there is one.
_AUTHORITY = ("promotion", "admin_policy_0x8003", "profile_0x8001", "lifecycle_0x800c")
_ROLLBACK_ROWS = (
    [{"key": f"{c}:ACTIVE", "word": "terminal",
      "reason": ["COMMIT_ROLLBACK_AFTER_ACTIVATION"]} for c in _AUTHORITY]
    + [{"key": f"{c}:PROVISIONING", "word": "hold", "reason": []} for c in _AUTHORITY]
    + [{"key": "unrelated:any", "word": "hold", "reason": []}]
)

#: wire §8.6 also prints what each disposition stores, and that is the half a
#: reader acts on: a `HELD` row claiming to store nothing would describe the
#: opposite of the durable hold the corpus decides for every held case.
_DISPOSITION_ROWS = [
    {"key": "DROP_BEFORE_PARSE", "stored": "nothing"},
    {"key": "BOUNDED_DIGEST_ONLY", "stored": "one"},
    {"key": "REJECT_NO_SLOT", "stored": "one"},
    {"key": "HELD", "stored": "the"},
    {"key": "TERMINAL", "stored": "the"},
]

#: wire §9.3 and golden §2.2 — component ids and placement are invariant, and
#: these two values are not. They are what the activation gate reads.
_TOPOLOGY_PHASE_ROWS = [
    {"key": "PROVISIONING_SELF_ONLY", "leaves": "[endpoint_low]",
     "admin_policy": "[endpoint_low]", "lifecycle": "0x00"},
    {"key": "PROVISIONING_INVITED", "leaves": "[endpoint_low,endpoint_high]",
     "admin_policy": "[endpoint_low]", "lifecycle": "0x00"},
    {"key": "ACTIVE", "leaves": "[endpoint_low,endpoint_high]",
     "admin_policy": "[endpoint_low,endpoint_high]", "lifecycle": "0x00"},
    {"key": "DISBANDED", "leaves": "[committer]",
     "admin_policy": "[committer]", "lifecycle": "0x01"},
]

#: wire §10 and FEAT-062 §8 — the same six retirement causes, published twice in
#: different words and different column layouts. The facts are what both must
#: state; the wording around them stays each document's own.
_RETIREMENT_ROWS = [
    {"key": "REVOKE", "local": ["hidden"], "deadline": ["purge_within_24_h"]},
    {"key": "LEVEL_DOWNGRADE", "local": ["hidden", "permanently_retired"],
     "deadline": ["purge_within_24_h"]},
    {"key": "BLOCK_OR_REMOVE", "local": ["hidden", "generation_terminal"],
     "deadline": ["purge_within_24_h"]},
    {"key": "EXPIRY", "local": ["hidden"],
     "deadline": ["purge_90_days_after_expiry"]},
    {"key": "SUPERSEDING_SET", "local": ["hidden"],
     "deadline": ["purge_in_the_same_transition"]},
    {"key": "DISBAND", "local": ["hidden", "generation_terminal"],
     "deadline": ["purge_within_24_h"]},
]

_PUBLISHED_TABLE_ROWS = {
    "wire_lease_recovery": _LEASE_RECOVERY_ROWS,
    # golden §8.9 prints the reachable rows only; the counterfactual gate-closed
    # pair belongs to the normative table in wire §8.7b.
    "golden_lease_recovery": [row for row in _LEASE_RECOVERY_ROWS
                              if not row["key"].startswith("unclear_replayable_gate_closed")],
    "wire_send_crash": _SEND_CRASH_ROWS,
    "golden_send_crash": _SEND_CRASH_ROWS,
    # wire §8.7b — the three phase definitions the recovery table is decided by.
    "wire_phase_meanings": [
        {"key": "PRE_EXTERNAL_EFFECT", "phase": ["PRE_EXTERNAL_EFFECT"],
         "meaning": ["not_authorised", "nothing_left_the_device"]},
        {"key": "EXTERNAL_EFFECT_UNCLEAR", "phase": ["EXTERNAL_EFFECT_UNCLEAR"],
         "meaning": ["authorised", "no_durable_evidence"]},
        {"key": "EXTERNAL_EFFECT_OBSERVED", "phase": ["EXTERNAL_EFFECT_OBSERVED"],
         "meaning": ["durably_evidenced"]},
    ],
    # wire §8.7b — the conjunction, and which conjuncts are unmet at the pin.
    "wire_replay_conditions": [
        {"key": "persisted_identity", "reason": ["REPLAY_NO_PERSISTED_IDENTITY"],
         "state": ["row_dependent"]},
        {"key": "product_gate_closed", "reason": ["REPLAY_PRODUCT_GATE_OPEN"],
         "state": ["gate_open"]},
        {"key": "source_correlated_receipt", "reason": ["REPLAY_NO_SOURCE_CORRELATION"],
         "state": ["correlation_unavailable"]},
    ],
    # wire §8.7e — what each recovery outcome leaves for a later drain attempt.
    "wire_drain_steps": [
        {"key": "lease_held", "outcome": ["LEASE_HELD"], "step": ["NONE"],
         "requires": ["nothing_automatic"]},
        {"key": "resolved_or_blocked",
         "outcome": ["RESOLVE_FROM_RECEIPT", "RECOVERY_BLOCKED"], "step": ["NONE"],
         "requires": ["nothing_automatic"]},
        {"key": "recover_queued", "outcome": ["RECOVER_QUEUED"],
         "step": ["WRITE_AHEAD_FIRST_DELIVERY"], "requires": ["write_ahead_gate"]},
        {"key": "recover_transport_identical",
         "outcome": ["RECOVER_TRANSPORT_IDENTICAL"],
         "step": ["WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY"],
         "requires": ["write_ahead_gate", "persisted_identity", "closed_gate",
                      "source_correlated_receipt"]},
    ],
    "wire_post_handover": [
        {"key": "refused",
         "outcome": ["POST_HANDOVER_UNCLEAR", "CALL_REFUSED_AFTER_AUTHORISATION"],
         "phase": _UNCLEAR_PHASE},
        {"key": "error",
         "outcome": ["POST_HANDOVER_UNCLEAR", "CALL_ERROR_AFTER_AUTHORISATION"],
         "phase": _UNCLEAR_PHASE},
        {"key": "no_answer",
         "outcome": ["POST_HANDOVER_UNCLEAR", "CALL_WITHOUT_ANSWER"],
         "phase": _UNCLEAR_PHASE},
        # R6 finding 1: the one result that looks like success and is not.
        {"key": "accepted_without_receipt",
         "outcome": ["POST_HANDOVER_UNCLEAR", "ACCEPTED_WITHOUT_SOURCE_RECEIPT"],
         "phase": _UNCLEAR_PHASE},
        {"key": "receipt_persisted", "outcome": ["POST_HANDOVER_OBSERVED"],
         "phase": ["EXTERNAL_EFFECT_OBSERVED"]},
    ],
    "golden_commit_rollback": _ROLLBACK_ROWS,
    "wire_supersession": [
        {"key": "restrictive->permissive#0", "allowed": "never"},
        {"key": "permissive->restrictive#1", "allowed": "yes"},
        {"key": "permissive->restrictive#2", "allowed": "yes"},
        {"key": "restrictive->permissive#3", "allowed": "never"},
    ],
    "wire_carrier_fields": [
        {"key": "id", "field": ["id"], "facts": ["nip01_id", "nip01_preimage"]},
        {"key": "pubkey", "field": ["pubkey"], "facts": ["mls_sender"]},
        {"key": "created_at", "field": ["created_at"], "facts": ["equals_issued_at"]},
        {"key": "kind", "field": ["kind"], "facts": ["carrier_kind"]},
        {"key": "tags", "field": ["tags"], "facts": ["exact_tag_array"]},
        {"key": "content", "field": ["content"],
         "facts": ["envelope_jcs", "envelope_type"]},
    ],
    "wire_envelope_fields": [
        {"key": "type+v", "field": ["type", "v"], "facts": ["envelope_type"]},
        {"key": "generation_id", "field": ["generation_id"],
         "facts": ["derived_generation"]},
        {"key": "endpoints", "field": ["endpoints"], "facts": ["sorted_endpoint_pair"]},
        {"key": "author", "field": ["author"],
         "facts": ["author_is_authenticated_sender"]},
        {"key": "object_id", "field": ["object_id"], "facts": ["deterministic_slot"]},
        {"key": "seq", "field": ["seq"], "facts": ["contiguous_sequence"]},
        {"key": "previous_event_id", "field": ["previous_event_id"],
         "facts": ["forbidden_at_one", "required_above_one", "equals_prior_event"]},
        {"key": "issued_at", "field": ["issued_at"],
         "facts": ["equals_created_at", "never_ordering_authority"]},
        {"key": "body", "field": ["body"], "facts": ["one_closed_body"]},
    ],
    "wire_scope_codes": [
        {"key": "LOGBOOK_SUMMARY", "scope": ["LOGBOOK_SUMMARY"], "code": "1"},
        {"key": "PLAN_SUMMARY", "scope": ["PLAN_SUMMARY"], "code": "2"},
        {"key": "LOGBOOK_DETAIL", "scope": ["LOGBOOK_DETAIL"], "code": "3"},
        {"key": "PLAN_DETAIL", "scope": ["PLAN_DETAIL"], "code": "4"},
    ],
    "wire_delta_operations": [
        {"key": "LOGBOOK_SUMMARY", "scope": ["LOGBOOK_SUMMARY"], "ops": ["SET_SUMMARY"]},
        {"key": "PLAN_SUMMARY", "scope": ["PLAN_SUMMARY"], "ops": ["SET_SUMMARY"]},
        {"key": "LOGBOOK_DETAIL", "scope": ["LOGBOOK_DETAIL"],
         "ops": ["UPSERT_SESSION", "DELETE_SESSION"]},
        {"key": "PLAN_DETAIL", "scope": ["PLAN_DETAIL"],
         "ops": ["UPSERT_PLAN", "DELETE_PLAN"]},
    ],
    "wire_scope_levels": [
        {"key": "LOGBOOK_SUMMARY", "level": ["ACQUAINTANCE"],
         "fields": ["all_time_send_count", "hardest_grade_milli"]},
        {"key": "PLAN_SUMMARY", "level": ["ACQUAINTANCE"],
         "fields": ["phase", "sessions_per_week"]},
        {"key": "LOGBOOK_DETAIL", "level": ["FRIEND"],
         "fields": ["board_config_id", "duration_minutes", "sends", "started_at_utc"]},
        {"key": "PLAN_DETAIL", "level": ["FRIEND"],
         "fields": ["focus_areas", "phase", "sessions", "sessions_per_week"]},
    ],
    "wire_storage_bounds": [
        {"key": "held_events_per_slot", "value": "64"},
        {"key": "held_bytes_per_slot", "value": "262,144"},
        {"key": "held_events_per_generation", "value": "256"},
        {"key": "held_bytes_per_generation", "value": "1,048,576"},
        {"key": "held_bytes_global", "value": "4,194,304"},
        {"key": "plan_detail_slots_per_publisher", "value": "8"},
        {"key": "grant_slots_per_publisher", "value": "11"},
        {"key": "sessions_per_logbook_grant", "value": "100"},
    ],
    "wire_collection_limits": [
        {"key": "delta_ops", "limit": "1\u201350"},
        {"key": "sessions_per_logbook_grant", "limit": "0\u2013100"},
        {"key": "sends_per_session", "limit": "0\u2013100"},
        {"key": "focus_areas", "limit": "1\u20138"},
        {"key": "planned_sessions", "limit": "0\u201314"},
    ],
    "golden_dispositions": _DISPOSITION_ROWS,
    "wire_dispositions": _DISPOSITION_ROWS,
    "wire_topology_phases": _TOPOLOGY_PHASE_ROWS,
    "golden_topology_phases": _TOPOLOGY_PHASE_ROWS,
    "golden_detail_boundary": [
        {"key": "1786000199", "floor900": "1785999600", "sendable": "no"},
        {"key": "1786000250", "floor900": "1785999600", "sendable": "no"},
        {"key": "1786000499", "floor900": "1785999600", "sendable": "no"},
        {"key": "1786000500", "floor900": "1786000500", "sendable": "yes"},
        {"key": "1786003345", "floor900": "1786003200", "sendable": "yes"},
    ],
    "wire_distrust_conditions": [
        {"key": "BOOT_CHANGED", "code": ["BOOT_CHANGED"]},
        {"key": "MONOTONIC_RESET", "code": ["MONOTONIC_RESET"]},
        {"key": "WALL_ROLLBACK", "code": ["WALL_ROLLBACK"]},
        {"key": "WALL_FROZEN", "code": ["WALL_FROZEN"]},
    ],
    "golden_trusted_time": [
        {"key": "ordinary_advance", "word": "trusted", "verdict": []},
        {"key": "wall_rollback", "word": "time_untrusted",
         "verdict": ["TIME_UNTRUSTED", "WALL_ROLLBACK"]},
        {"key": "wall_frozen", "word": "time_untrusted",
         "verdict": ["TIME_UNTRUSTED", "WALL_FROZEN"]},
        {"key": "boot_changed", "word": "time_untrusted",
         "verdict": ["TIME_UNTRUSTED", "BOOT_CHANGED"]},
        {"key": "monotonic_reset", "word": "time_untrusted",
         "verdict": ["TIME_UNTRUSTED", "MONOTONIC_RESET"]},
        {"key": "trusted_reanchor", "word": "trusted", "verdict": []},
    ],
    "wire_retirement": _RETIREMENT_ROWS,
    "feat_retirement": _RETIREMENT_ROWS,
    # golden §8.11 — the printed lifecycle edges. A refused edge prints no
    # resulting state at all, which is what separates it from one that survives.
    "golden_state_machine": [
        {"key": "PROVISIONING:activation_gate_passed", "start": ["PROVISIONING"],
         "event": ["activation_gate_passed"], "result": ["ACTIVE"],
         "facts": ["send_and_apply_allowed"]},
        {"key": "PROVISIONING:convergence_unresolved", "start": ["PROVISIONING"],
         "event": ["convergence_unresolved"], "result": ["PROVISIONING"],
         "facts": ["holding"]},
        {"key": "PROVISIONING:rollback_authority_commit", "start": ["PROVISIONING"],
         "event": ["rollback_authority_commit"], "result": ["PROVISIONING"],
         "facts": ["holding"]},
        {"key": "ACTIVE:return_to_provisioning", "start": ["ACTIVE"],
         "event": ["return_to_provisioning"], "result": [], "facts": ["refused"]},
        {"key": "ACTIVE:rollback_unrelated_commit", "start": ["ACTIVE"],
         "event": ["rollback_unrelated_commit"], "result": ["ACTIVE"],
         "facts": ["holding"]},
        {"key": "ACTIVE:rollback_authority_commit", "start": ["ACTIVE"],
         "event": ["rollback_authority_commit"],
         "result": ["TERMINAL", "COMMIT_ROLLBACK_AFTER_ACTIVATION"], "facts": []},
        {"key": "ACTIVE:terminal_fault", "start": ["ACTIVE"],
         "event": ["terminal_fault"], "result": ["TERMINAL"], "facts": []},
        {"key": "TERMINAL:activation_gate_passed", "start": ["TERMINAL"],
         "event": ["activation_gate_passed"], "result": [], "facts": ["refused"]},
    ],
    # FEAT-062 §2.3 — the pins and toolchain the spike is judged against.
    "feat_build_inputs": [
        {"key": "Marmot", "value": "4ad4ae21479c3f3fa9950c6fc4556a76941a62e1"},
        {"key": "MDK", "value": "101d79946cff6c82d2b849d3b70902a7af7bac08"},
        {"key": "Android ABI", "value": "arm64-v8a"},
        {"key": "Android native API", "value": "28"},
        {"key": "NDK", "value": "27.2.12479018"},
        {"key": "JDK", "value": "17"},
    ],
    # FEAT-062 §3.3 — the product-level state decisions, in printed order.
    "feat_state_decisions": [
        {"key": "EXACT_PROFILE_ACTIVE", "facts": ["active", "send_apply_allowed"]},
        {"key": "UNRESOLVED_CONVERGENCE",
         "facts": ["send_apply_held", "not_terminal_by_that_fact_alone"]},
        {"key": "UNRESOLVED_COMMIT_ROLLBACK",
         "facts": ["send_apply_held", "fresh_session_consistent_read"]},
        {"key": "ACCEPTANCE_MISSING", "facts": ["provisioning"]},
        {"key": "PROMOTION_CANNOT_COMPLETE",
         "facts": ["terminal", "user_visible_abort", "explicit_fresh_attempt"]},
        {"key": "AUTHORITY_ROLLBACK_AFTER_ACTIVE",
         "facts": ["terminal", "hide_all_data", "tombstones_sticky"]},
        {"key": "TOPOLOGY_INVALID", "facts": ["terminal", "hide_all_data"]},
        {"key": "STORAGE_BOUND_EXCEEDED",
         "facts": ["terminal", "before_the_insert", "nothing_evicted"]},
        {"key": "LIFECYCLE_NOT_ACTIVE", "facts": ["terminal", "hide_all_data"]},
        {"key": "MDK_QUARANTINE_OR_UNRECOVERABLE",
         "facts": ["terminal", "hide_all_data"]},
    ],
}

_PUBLISHED_TABLE_NOTES = {
    "wire_lease_recovery":
        "§8.7b is the fail-closed heart of the send path, and it was unbound: "
        "editing this table to requeue an unclear row left every fixture, oracle "
        "and manifest check green while the contract told a reader the opposite. "
        "The last two rows are the counterfactuals a closed R6-G2 alone reaches",
    "golden_lease_recovery":
        "the golden twin of wire §8.7b, without the two counterfactual "
        "gate-closed rows the normative table owns",
    "wire_send_crash":
        "every crash window of one send attempt: no window in which a handover "
        "was authorised may print a phase a recovering worker could read as "
        "pre-effect, which is the whole guarantee the write-ahead order buys",
    "golden_send_crash": "the golden twin of the wire §8.7c crash table",
    "wire_phase_meanings":
        "the recovery table reads this column and nothing else: redefining "
        "PRE_EXTERNAL_EFFECT as a phase that may already have been authorised "
        "would leave the one automatic requeue resting on nothing, with every "
        "recovery row still printing correctly",
    "wire_replay_conditions":
        "the State-today column is where the contract says which conjuncts are "
        "unmet at the pin; printing the gate as closed and the correlation as "
        "available would announce the replay route open against §11.1's "
        "registry and R6 finding 1, and it was bound to nothing",
    "wire_drain_steps":
        "the replay row is the only route by which an already-authorised send "
        "could leave the device again, and it is barred by a conjunction of "
        "four; dropping the closed gate or the source-correlated receipt from "
        "the printed cell is exactly the 'closing R6-G2 is enough' reading "
        "§8.7b and §8.7e exist to refuse",
    "wire_post_handover":
        "R6 finding 1 lands here: an acceptance the adapter cannot correlate is "
        "not an observed delivery, and flipping that one row would let the "
        "bundle claim the delivery it forbids claiming",
    "golden_commit_rollback":
        "§8.4a gives the same signal two outcomes and the generation state picks "
        "one, so every authority class is printed on both sides of activation",
    "wire_supersession":
        "a permissive row may never supersede a restrictive one; flipping the "
        "first or last row would silently authorise losing a revoke",
    "wire_carrier_fields":
        "§2.1 is where a reader learns what makes an event a FEAT-062 event at "
        "all; the kind cell could have been edited to 1221, or the id preimage "
        "to a different array, with every golden encoder vector still green "
        "because nothing read the table the vectors are supposed to illustrate",
    "wire_envelope_fields":
        "the closed member set of cc.envelope.v1 and the rule each member "
        "carries; the seq row could be dropped or the previous_event_id rule "
        "inverted — forbidden at 1, required above 1 is what makes a slot a "
        "chain at all and is what §8.2's ordering rule rests on — with every "
        "fixture, vocabulary and manifest check still green. The membership is "
        "checked against the oracle's own schema and the predecessor cell by "
        "really running validate_envelope at both sequences",
    "wire_scope_codes":
        "the code is the u8 byte inside the grant-slot preimage, not a label: "
        "swapping two rows states two different deterministic slot ids from the "
        "ones the oracle derives and the golden preimages print, which is a "
        "permanent interop break no fixture, manifest or vocabulary check saw",
    "wire_delta_operations":
        "the closed operation set per scope, taken from the map _validate_ops "
        "consults; widening a cell — a LOGBOOK_SUMMARY that also admits "
        "UPSERT_SESSION — told an implementer to accept an operation the "
        "reducer rejects, with the run green",
    "wire_scope_levels":
        "the minimum level and closed field tokens of every scope: lowering "
        "LOGBOOK_DETAIL to ACQUAINTANCE would widen detail sharing by one edit",
    "wire_storage_bounds":
        "a printed bound that is not the enforced one is exactly the decoration "
        "§8.2 exists to avoid",
    "wire_collection_limits":
        "the row set is what binds here; the limit cells are ranges in prose",
    "golden_dispositions": "the five closed dispositions, in order",
    "wire_dispositions":
        "the five closed dispositions and what each stores; a HELD row claiming "
        "to store nothing would describe the opposite of the durable hold "
        "every held corpus case decides",
    "wire_topology_phases":
        "component ids and placement are invariant and these two values are "
        "not; printing the ACTIVE admin policy as the creator alone would state "
        "a profile the activation gate terminalises on",
    "golden_topology_phases": "the golden twin of the wire §9.3 phase table",
    "golden_detail_boundary":
        "floor900 and sendability recomputed rather than tabulated, so the "
        "deliberate up-to-899-second unsendable gap cannot be edited away",
    "wire_distrust_conditions":
        "the closed-vocabulary rule proves §8.8 names all four codes and that "
        "fixtures decide them; it cannot see two rows swapped, and the code is "
        "what decides whether a time-bounded grant stays hidden and whether an "
        "unprovable purge is due now",
    "golden_trusted_time":
        "the golden twin of §8.8, verdict by verdict, including the explicit "
        "re-anchor that is the only exit — derived by really re-anchoring the "
        "clock the reboot row left untrusted",
    "wire_retirement":
        "§10 was pure decoration: relaxing a purge window from 24 h, printing a "
        "block or a disband as leaving the generation active, or turning the "
        "permanent detail retirement into a temporary hide left every fixture, "
        "oracle and manifest check green on the one table a revocation reaches",
    "feat_retirement":
        "the product-level twin of wire §10, split across an immediate-effect "
        "and a deadline column; the same facts must survive both wordings",
    "feat_build_inputs":
        "the pins the spike is judged against; naming a different MDK SHA, ABI, "
        "API level or NDK than R6 ran against would describe a build nobody "
        "executed, with every gate row still reading correctly",
    "golden_state_machine":
        "the fixtures drive the whole three-state/nine-event matrix, but the "
        "printed table nobody read is where a reader learns that ACTIVE never "
        "returns to PROVISIONING and that TERMINAL is absorbing; giving a "
        "refused edge a resulting state, or printing a hold where the machine "
        "terminalises after activation, left every scenario check green",
    "feat_state_decisions":
        "the one table a product reader consults to learn what terminalises; "
        "reporting MDK quarantine or a post-activation authority rollback as a "
        "repairable hold contradicts §8.4a and §9.5 in the exact cell that "
        "decides whether data stays visible",
}


def _published_tables() -> list:
    """One binding per normative table, in the registry's own order.

    The two dictionaries above are keyed by the same registry, and a table
    registered in the oracle without its rows or its note used to die here as a
    bare `KeyError` on whichever lookup came first — a stack trace that names
    the key but not the invariant. Both directions are stated instead, so
    adding a table to the registry and forgetting either half fails with the
    missing ids rather than with a traceback, and a leftover entry for a table
    the registry no longer knows fails too.
    """
    for label, declared in (("rows", _PUBLISHED_TABLE_ROWS),
                            ("notes", _PUBLISHED_TABLE_NOTES)):
        missing = [t for t in o.PUBLISHED_TABLES if t not in declared]
        orphans = [t for t in declared if t not in o.PUBLISHED_TABLES]
        if missing or orphans:
            raise o.SpecViolation(
                f"published-table {label} disagree with the oracle registry: "
                f"missing {missing}, orphaned {orphans}")
    return [
        {
            "id": f"published_table_{table_id}",
            "kind": "published_table",
            "table": table_id,
            "document": o.PUBLISHED_TABLES[table_id]["document"],
            "expect": {"rows": _PUBLISHED_TABLE_ROWS[table_id]},
            "note": _PUBLISHED_TABLE_NOTES[table_id],
        }
        for table_id in o.PUBLISHED_TABLES
    ]


def _assemble(r6_gates, r6_conditions, replay_admission, published_bindings, sequences,
              topology_phases, state_machine, rollback, clock, outbox, send_handover,
              bounds, restore, retirement, runtime_gates) -> dict:

    return {
        "schema": "cc.fixture.scenarios.v1",
        "protocol": o.NAMESPACE + "/v1",
        "note": (
            "Ordering, topology-phase, generation state-machine, clock, outbox, "
            "send-handover write-ahead, bound, restore, retirement, "
            "adapter-runtime-gate and R6 gate-evidence scenarios evaluated by the "
            "Python spec oracle. No product code runs."
        ),
        "r6_gates": r6_gates,
        "r6_conditions": r6_conditions,
        "replay_admission": replay_admission,
        "published_bindings": published_bindings,
        "sequences": sequences,
        "topology_phases": topology_phases,
        "state_machine": state_machine,
        "commit_rollback": rollback,
        "clock": clock,
        "outbox": outbox,
        "send_handover": send_handover,
        "bounds": bounds,
        "restore": restore,
        "retirement": retirement,
        "runtime_gates": runtime_gates,
    }
