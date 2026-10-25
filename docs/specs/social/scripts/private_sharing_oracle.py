#!/usr/bin/env python3
"""Reference **spec oracle** for the FEAT-062 private-sharing wire contract v1.

This module is a executable restatement of
`docs/specs/social/PRIVATE-SHARING-WIRE-V1.md`. It exists so the golden vectors
and the mandatory negative corpus stop being prose.

**What this is.** A deterministic Python implementation of the derivations,
the canonical-content parser, the closed schemas, the reducer, the outbox and
the trusted-time model described by the wire contract. It is the *oracle* the
future implementations are measured against.

**What this is NOT.** It is not the product. It does not build, load, call or
test any Kotlin, Rust, MDK, Marmot or on-device code, and running it green
proves nothing about them. Every statement it makes is a statement about this
specification's own internal consistency. The eight adapter gates in FEAT-062
§5 are untouched by it.

No wall clock, no randomness, no network and no filesystem access: every value
below is derived from the fixture inputs alone.
"""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from typing import Any

# --------------------------------------------------------------------------
# Dispositions and closed reason-code sets (wire §8.3, §8.4, §8.6)
# --------------------------------------------------------------------------

#: The five negative dispositions the golden corpus must distinguish.
DROP_BEFORE_PARSE = "DROP_BEFORE_PARSE"
BOUNDED_DIGEST_ONLY = "BOUNDED_DIGEST_ONLY"
REJECT_NO_SLOT = "REJECT_NO_SLOT"
HELD = "HELD"
TERMINAL = "TERMINAL"
#: Positive outcome; never a member of the negative corpus.
APPLIED = "APPLIED"

DISPOSITIONS = (
    DROP_BEFORE_PARSE,
    BOUNDED_DIGEST_ONLY,
    REJECT_NO_SLOT,
    HELD,
    TERMINAL,
)

#: wire §8.6 — bounded diagnostic quarantine reason codes.
DIAGNOSTIC_REASONS = frozenset(
    {
        "CONTENT_UTF8_INVALID",
        "CONTENT_OVERSIZE",
        "CONTENT_JSON_INVALID",
        "CONTENT_JCS_INVALID",
        "ENVELOPE_SCHEMA_INVALID",
        "GENERATION_BINDING_INVALID",
        "TOPOLOGY_INVALID",
        "AUTHOR_BINDING_INVALID",
        "SLOT_BINDING_INVALID",
        "BODY_SCHEMA_INVALID",
        "PREDECESSOR_INVALID",
        "DEPENDENCY_INVALID",
        "EQUAL_SEQUENCE_CONFLICT",
    }
)

#: wire §8.3 — closed hold reasons.
HOLD_REASONS = frozenset(
    {
        "HOLD_PREDECESSOR_MISSING",
        # The projection's `grant_id` has no applied grant yet. There is no
        # separate "grant event missing" hold: a `grant_id` is unique
        # generation-wide and binds its grant event once, so a projection whose
        # `grant_event_id` disagrees with the applied grant is not waiting for a
        # dependency — it is rejected with `DEPENDENCY_INVALID` (wire §8.3).
        "HOLD_GRANT_MISSING",
        "HOLD_OFFER_HEAD_MISSING",
        # wire §8.5a: a live-subscription gap that durable replay has not yet
        # closed. Correctness never depends on live delivery.
        "HOLD_REPLAY_INCOMPLETE",
    }
)

#: wire §8.4 — closed local terminal fault codes.
TERMINAL_REASONS = frozenset(
    {
        "EQUAL_SEQUENCE_CONFLICT",
        "STORED_EVENT_INVALIDATED",
        "SOURCE_MAPPING_MISSING",
        "SOURCE_MAPPING_AMBIGUOUS",
        "GRANT_ID_COLLISION",
        "TOPOLOGY_INVALID",
        "PROFILE_MARKER_CHANGED",
        "LIFECYCLE_NOT_ACTIVE",
        "DUPLICATE_GENERATION",
        "MDK_QUARANTINE",
        "MDK_UNRECOVERABLE",
        "COMMIT_ROLLBACK_AFTER_ACTIVATION",
        "OFFER_NONE_RECEIVED",
        "STORAGE_BOUND_EXCEEDED",
        "WRONG_CREATOR",
        "PROMOTION_FAILED",
        "ATTEMPT_ID_REUSED",
        "ATTEMPT_ID_NOT_CSPRNG",
        # wire §8.5a / §8.7a — R5 outcomes made normative.
        "PROJECTION_AUTHORITY_INVALID",
        # FEAT-062 R8: two members hold different canonical branches for the
        # same fork and stay there. Named separately from the rollback code
        # because nothing was rolled back here — the disagreement itself is
        # the fault, and it is not repairable by re-reading either side.
        "BRANCH_SELECTION_DIVERGENT",
        "RETRY_NOT_TRANSPORT_IDENTICAL",
    }
)

REASONS_BY_DISPOSITION = {
    DROP_BEFORE_PARSE: frozenset(),
    BOUNDED_DIGEST_ONLY: DIAGNOSTIC_REASONS,
    REJECT_NO_SLOT: DIAGNOSTIC_REASONS,
    HELD: HOLD_REASONS,
    TERMINAL: TERMINAL_REASONS,
}

# --------------------------------------------------------------------------
# Constants from the wire contract
# --------------------------------------------------------------------------

NAMESPACE = "com.cruxcoach.private-sharing"
CARRIER_KIND = 1220
CARRIER_TAGS = [["L", NAMESPACE], ["l", "v1", NAMESPACE]]
CONTENT_MAX_BYTES = 65_536
MAX_SAFE_INT = 9_007_199_254_740_991
MIN_SAFE_INT = -9_007_199_254_740_991
GRANT_MAX_DURATION_S = 7_776_000
DETAIL_START_BUCKET_S = 900
BOOTSTRAP_NAME = "CruxCoach private sharing"
BOOTSTRAP_PROTOCOL = "com.cruxcoach.private-sharing/bootstrap/v1"

SCOPE_CODES = {
    "LOGBOOK_SUMMARY": 1,
    "PLAN_SUMMARY": 2,
    "LOGBOOK_DETAIL": 3,
    "PLAN_DETAIL": 4,
}
SCOPE_MIN_LEVEL = {
    "LOGBOOK_SUMMARY": "ACQUAINTANCE",
    "PLAN_SUMMARY": "ACQUAINTANCE",
    "LOGBOOK_DETAIL": "FRIEND",
    "PLAN_DETAIL": "FRIEND",
}
SCOPE_FIELDS = {
    "LOGBOOK_SUMMARY": ("all_time_send_count", "hardest_grade_milli"),
    "PLAN_SUMMARY": ("phase", "sessions_per_week"),
    "LOGBOOK_DETAIL": ("board_config_id", "duration_minutes", "sends", "started_at_utc"),
    "PLAN_DETAIL": ("focus_areas", "phase", "sessions", "sessions_per_week"),
}
#: wire §7.6 — the closed operation set of every scope, in printed order.
#:
#: `_validate_ops` consults this map before any shape check, so it is the
#: decision itself rather than a second copy of it: an operation name outside
#: its scope's tuple is rejected here, and the published §7.6 table is bound to
#: the same map. Widening a scope's cell — a `LOGBOOK_SUMMARY` that also admits
#: `UPSERT_SESSION` — would otherwise have left every fixture green while the
#: contract told an implementer to accept an operation the reducer refuses.
SCOPE_DELTA_OPS = {
    "LOGBOOK_SUMMARY": ("SET_SUMMARY",),
    "PLAN_SUMMARY": ("SET_SUMMARY",),
    "LOGBOOK_DETAIL": ("UPSERT_SESSION", "DELETE_SESSION"),
    "PLAN_DETAIL": ("UPSERT_PLAN", "DELETE_PLAN"),
}
DETAIL_SCOPES = ("LOGBOOK_DETAIL", "PLAN_DETAIL")
LEVELS = ("NOT_ESTABLISHED", "ACQUAINTANCE", "FRIEND")
OFFER_VALUES = ("NONE", "ACQUAINTANCE", "FRIEND")
PHASES = ("BASE", "STRENGTH", "POWER", "PERFORMANCE", "DELOAD")
SESSION_TYPES = ("STRENGTH", "POWER", "VOLUME", "TECHNIQUE", "DELOAD", "REST")
FOCUS_AREAS = (
    "core",
    "finger_strength",
    "flexibility",
    "power",
    "power_endurance",
    "technique",
    "upper_body_pull",
    "upper_body_push",
)
REVOKE_REASONS = ("REVOKED_BY_USER", "BLOCKED", "GENERATION_CLOSED")
ECOSYSTEM_CODES = {
    "KILTER": 1,
    "MOONBOARD": 2,
    "TENSION": 3,
    "GRASSHOPPER": 4,
    "DECOY": 5,
    "SOILL": 6,
    "TOUCHSTONE": 7,
}
BODY_TYPES = (
    "cc.generation.accept.v1",
    "cc.relationship.offer.v1",
    "cc.grant.set.v1",
    "cc.grant.revoke.v1",
    "cc.projection.snapshot.v1",
    "cc.projection.delta.v1",
)

#: wire §8.2 / §8.6 — storage bounds. Overflow terminalises the offending
#: generation before the insert; it never evicts and never prunes a safety
#: prefix or a tombstone.
BOUNDS = {
    "held_events_per_slot": 64,
    "held_bytes_per_slot": 262_144,
    "held_events_per_generation": 256,
    "held_bytes_per_generation": 1_048_576,
    "held_bytes_global": 4_194_304,
    "plan_detail_slots_per_publisher": 8,
    "grant_slots_per_publisher": 11,  # 3 resource-free scopes + 8 plan slots
    "sessions_per_logbook_grant": 100,
    "sends_per_session": 100,
    "delta_ops": 50,
    "planned_sessions": 14,
    "focus_areas": 8,
    "diagnostics_per_generation": 100,
    "diagnostic_days": 30,
}

#: wire §8.7 — outbox state to the closed user-visible label vocabulary.
OUTBOX_STATES = (
    "QUEUED",
    "IN_FLIGHT",
    "SENT_LOCALLY",
    "MDK_CANONICAL",
    "FAILED",
    "DELIVERY_UNCLEAR",
    "SUPERSEDED",
)
OUTBOX_LABELS = {
    "QUEUED": "Queued locally",
    "IN_FLIGHT": "Queued locally",
    "SENT_LOCALLY": "Queued locally",
    "MDK_CANONICAL": "Published to at least one configured relay",
    "FAILED": "Not sent — retries exhausted",
    # wire §8.7b: a first delivery that may already have had external effect and
    # cannot be repeated identically. It claims neither delivery nor
    # non-delivery, and it never drains by itself.
    "DELIVERY_UNCLEAR": "Delivery unclear — not retried automatically",
    "SUPERSEDED": None,  # audit-only; never an active transport status
}
NOT_PEER_CONFIRMED = "Not peer-confirmed"

#: wire §8.7b — the closed external-effect phase persisted on an outbox row. It
#: records what this device has committed about the send, and nothing else. Each
#: value is advanced by a local committed transaction of its own (§8.7c); no
#: local transaction ever spans a transport call, so the phase is never inferred
#: from a send summary and never guessed at recovery time.
EXTERNAL_EFFECT_PHASES = (
    "PRE_EXTERNAL_EFFECT",       # the send has not been authorised to start
    "EXTERNAL_EFFECT_UNCLEAR",   # the handover may have begun; no durable evidence either way
    "EXTERNAL_EFFECT_OBSERVED",  # the transport message was emitted and durably evidenced
)

#: wire §8.7c — the closed result of the write-ahead phase commit that has to
#: precede any transport handover. "unproven" is the crash case: the committing
#: process died without learning the outcome, so the commit is not proven.
WRITE_AHEAD_RESULTS = ("not_attempted", "failed", "unproven", "committed")

#: wire §8.7c — the closed outcomes of asking whether transport may be called.
HANDOVER_AUTHORISED = "HANDOVER_AUTHORISED"
HANDOVER_REFUSED = "HANDOVER_REFUSED"
HANDOVER_OUTCOMES = (HANDOVER_AUTHORISED, HANDOVER_REFUSED)

#: wire §8.7c — the closed refusal reason each non-committed write-ahead result
#: produces. ``committed`` is absent on purpose: it authorises the call and
#: refuses nothing, so it has no refusal reason at all.
WRITE_AHEAD_REFUSAL_REASONS = {
    "not_attempted": "WRITE_AHEAD_NOT_COMMITTED",
    "failed": "WRITE_AHEAD_COMMIT_FAILED",
    "unproven": "WRITE_AHEAD_COMMIT_UNPROVEN",
}

#: wire §8.7c — closed reasons no handover may begin.
HANDOVER_REFUSED_REASONS = frozenset(WRITE_AHEAD_REFUSAL_REASONS.values())

#: wire §8.7d — the closed results of a transport call that a proven write-ahead
#: commit already authorised. This is the outcome of the **call**, never of the
#: authorisation gate of §8.7c: authorisation is permission to call and says
#: nothing about what the call did.
#:
#: ``accepted_without_receipt`` is R6 finding 1 made representable: the fanout
#: reports a published endpoint, but no ``PublishedApplicationMessage`` and
#: therefore no typed source receipt is ever produced. Acceptance the adapter
#: cannot correlate to a source message id is **not** an observed delivery.
TRANSPORT_CALL_RESULTS = ("refused", "error", "no_answer",
                          "accepted_without_receipt", "receipt_persisted")

#: wire §8.7d — the closed outcomes after such a call.
POST_HANDOVER_UNCLEAR = "POST_HANDOVER_UNCLEAR"
POST_HANDOVER_OBSERVED = "POST_HANDOVER_OBSERVED"
POST_HANDOVER_OUTCOMES = (POST_HANDOVER_UNCLEAR, POST_HANDOVER_OBSERVED)

#: wire §8.7d — closed reasons the row stays unclear after an authorised call.
POST_HANDOVER_REASONS = frozenset(
    {
        "CALL_REFUSED_AFTER_AUTHORISATION",
        "CALL_ERROR_AFTER_AUTHORISATION",
        "CALL_WITHOUT_ANSWER",
        # R6 finding 1: the transport took it, and nothing correlatable came
        # back. Uncorrelated acceptance is the one "success" that must not be
        # treated as one.
        "ACCEPTED_WITHOUT_SOURCE_RECEIPT",
    }
)

#: wire §8.7e — what a recovered row still needs before anything of it can
#: reach the network. Lease recovery itself never calls the transport: it writes
#: durable state and releases the lease, and this names the step a *later* drain
#: attempt has to take.
DRAIN_STEPS = (
    "NONE",                                    # nothing may reach the network automatically
    "WRITE_AHEAD_FIRST_DELIVERY",              # an ordinary attempt, through the §8.7c gate
    "WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY",  # §8.7a replay, gated on R6-G2
)

#: wire §8.7c — the closed crash points of one send attempt, in order. They are
#: named from the outside: what a *recovering* worker can still establish from
#: durable local state alone.
SEND_CRASH_POINTS = (
    "before_write_ahead",
    "during_write_ahead_commit",
    "after_commit_before_handover",
    "during_handover",
    "after_handover_before_receipt",
    "after_receipt_persisted",
)

#: wire §8.7b — the closed outcomes of reclaiming an expired lease.
LEASE_HELD = "LEASE_HELD"
RECOVER_QUEUED = "RECOVER_QUEUED"
RECOVER_TRANSPORT_IDENTICAL = "RECOVER_TRANSPORT_IDENTICAL"
RESOLVE_FROM_RECEIPT = "RESOLVE_FROM_RECEIPT"
RECOVERY_BLOCKED = "RECOVERY_BLOCKED"
LEASE_RECOVERY_OUTCOMES = (
    LEASE_HELD,
    RECOVER_QUEUED,
    RECOVER_TRANSPORT_IDENTICAL,
    RESOLVE_FROM_RECEIPT,
    RECOVERY_BLOCKED,
)

#: wire §8.7b — closed reasons a recovery leaves no automatic route at all
#: (it never sends either way; see §8.7e for what a drain would need). None of
#: them is a generation fault: the row stays visible and stays the user's.
LEASE_RECOVERY_REASONS = frozenset(
    {
        "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
        "RECOVERY_RETRY_GATE_OPEN",
        "RECOVERY_EVIDENCE_INCOMPLETE",
        # §8.7c: durable evidence of an authorised handover under a phase that
        # still reads PRE_EXTERNAL_EFFECT. A correct writer cannot produce that
        # pair, and recovery refuses to requeue rather than trust the phase.
        "RECOVERY_WRITE_AHEAD_INCONSISTENT",
        # §8.7b: the replay itself is available — persisted identity, gate
        # closed — but the path that performs it produces no receipt that can be
        # correlated to a source id (R6 finding 1). Re-driving would create a
        # delivery CruxCoach could never account for, so it does not happen.
        "RECOVERY_SOURCE_CORRELATION_UNAVAILABLE",
    }
)

#: wire §8.7b/§8.7e — why a transport-identical replay is not admitted. All
#: unmet conditions are reported together: this is a conjunction, and naming
#: only the first failure would make one condition look decisive.
REPLAY_ADMISSION_REASONS = (
    "REPLAY_NO_PERSISTED_IDENTITY",
    "REPLAY_PRODUCT_GATE_OPEN",
    "REPLAY_NO_SOURCE_CORRELATION",
)

#: wire §8.7b — the blocked-recovery reason each unmet replay condition
#: produces. A row can show only one, so it shows the unmet condition nearest to
#: the row itself, in :data:`REPLAY_ADMISSION_REASONS` order; all of them are
#: blocking either way.
#:
#: The third entry is the one no recovery can reach today, and it is written
#: down precisely because of that: it is the state a **closed**
#: ``R6-G2-TRANSPORT-IDENTICAL-RETRY`` alone would reach at the pin, since the
#: resume path that performs the identical re-drive emits no
#: ``PublishedApplicationMessage`` (R6 finding 1). Recording it is what keeps
#: "only the gate is in the way" from becoming true by omission.
REPLAY_BLOCKING_TO_RECOVERY_REASON = {
    "REPLAY_NO_PERSISTED_IDENTITY": "RECOVERY_EXTERNAL_EFFECT_UNCLEAR",
    "REPLAY_PRODUCT_GATE_OPEN": "RECOVERY_RETRY_GATE_OPEN",
    "REPLAY_NO_SOURCE_CORRELATION": "RECOVERY_SOURCE_CORRELATION_UNAVAILABLE",
}


def recovery_reason_for_blocking(blocking) -> str | None:
    """wire §8.7b — the reason a blocked recovery shows for these unmet conditions.

    ``None`` means nothing is unmet, which is the only case in which a replay is
    admitted at all. Anything else names exactly one closed lease-recovery
    reason, chosen in the fixed order above rather than by arrival or severity.
    """
    unmet = list(blocking or ())
    unknown = [reason for reason in unmet if reason not in REPLAY_ADMISSION_REASONS]
    if unknown:
        raise SpecViolation(f"unknown replay-admission reason {unknown!r}")
    for reason in REPLAY_ADMISSION_REASONS:
        if reason in unmet:
            return REPLAY_BLOCKING_TO_RECOVERY_REASON[reason]
    return None

#: wire §8.4 — the closed adapter invalidation reasons, mirroring the pin
#: one-to-one. They are the adapter's statement of *why* a stored event was
#: withdrawn; they are not local fault codes, and a zero- or multi-match is a
#: local `SOURCE_MAPPING_MISSING`/`SOURCE_MAPPING_AMBIGUOUS` fault instead.
ADAPTER_INVALIDATION_REASONS = (
    "LOSING_BRANCH",
    "BEYOND_ANCHOR",
    "BEYOND_APP_RETENTION",
    "UNDECRYPTABLE_IN_CANONICAL_STATE",
)

#: Marmot/MDK commit-rollback seams the reducer must react to (MDK pin
#: `101d7994`, crates/traits/src/engine.rs).
ROLLBACK_EVENTS = ("GroupStateInvalidated", "ForkRecovered", "CommitRolledBack")
#: Commit classes whose rollback after activation is terminal.
AUTHORITY_COMMITS = ("promotion", "admin_policy_0x8003", "profile_0x8001", "lifecycle_0x800c")

#: wire §11.1 — the executed R6 adapter spike, and the only record of it this
#: bundle treats as binding. R6 ran after R5 against the same MDK pin and
#: returned an overall **NO-GO** on all four axes it judged. Its superseded
#: first attachments are explicitly not the source; the approved record is
#: `GO-NO-GO_1.md` with the patch named below.
R6_SOURCE = {
    "stage": "R6",
    "record": "GO-NO-GO_1.md",
    "patch_sha256": "820335ab329b58d716415b203b8511257546fa31ec2dfc44634db53c8cce0e36",
    "mdk_pin": "101d79946cff6c82d2b849d3b70902a7af7bac08",
    "marmot_spec_pin": "4ad4ae21479c3f3fa9950c6fc4556a76941a62e1",
    "overall": "NO-GO",
}

#: wire §11.1 — what R6 was shown to have demonstrated about the pin. This is
#: the *capability* axis and it is deliberately separate from gate status: R6
#: refuted several R5 absence claims outright, and recording a proven capability
#: as still-absent would be as dishonest as claiming the gate closed.
GATE_CAPABILITIES = (
    "ABSENT",                # not present at the pin, and R6 did not change that
    "PARTIAL",               # one half of what the gate asks for, not the other
    "PROVEN_AT_HOST_SCOPE",  # really demonstrated, on a host surface only
    "NOT_TESTED",            # never executed at all
)

#: wire §11.1 — the surface a capability was demonstrated on. Host-side Rust
#: tests against a MockRelay are real runtime evidence, and they are still only
#: host evidence.
GATE_EVIDENCE_SCOPES = (
    "NONE",
    "HOST_RUST_MOCKRELAY",       # real relay harness, real MLS, one host process
    "HOST_RUST_CRATE_INTERNAL",  # crate-internal `#[cfg(test)]`, private surfaces
)

#: Scopes this bundle may never record. R6 ran no instrumentation (gate G8) and
#: shipped nothing, so no gate can be justified by native, on-device or release
#: evidence. Naming them keeps the impossible claim executable rather than
#: merely discouraged.
FORBIDDEN_EVIDENCE_SCOPES = ("NATIVE", "ON_DEVICE", "RELEASE")

#: wire §11.1 — the closed verdict vocabulary the two spikes recorded per gate.
#: `NOT MET` is R6's own word for the one criterion it set itself and failed;
#: `NOT DECIDED` is the case where a stage never judged the gate at all.
GATE_VERDICTS = ("PASS", "FAIL", "PARTIAL", "NOT TESTED", "NOT MET", "NOT DECIDED")

#: How a verdict that names no criterion must be spelled where it is published.
#: Every other verdict is published as "criterion <id> <verdict>".
GATE_VERDICT_SPELLING = {"NOT DECIDED": "decided nothing"}


def gate_evidence_marker(entry: dict, stage: str) -> str:
    """The phrase a gate row must use to attribute evidence to one spike.

    R5 and R6 judged the same eight gates and disagreed about several of them,
    so "which spike found this" is load-bearing rather than editorial: R6
    *refuted* R5 outright on `R6-G2-TRANSPORT-IDENTICAL-RETRY`, and a row that
    prints R5's finding in R6's column states the opposite of §11.1's whole
    point. The criterion id and the verdict together are what distinguish the
    two — `R6-G8-INSTRUMENTATION-RUN` is `NOT TESTED` on both sides and differs
    only by criterion — so the marker is built from the pair.
    """
    verdict = entry[f"{stage}_verdict"]
    if verdict in GATE_VERDICT_SPELLING:
        return GATE_VERDICT_SPELLING[verdict]
    return f"criterion {entry[f'{stage}_criterion']} {verdict}".lower()

#: wire §8.7d / R6 finding 1 — whether the pin's resume path yields the typed
#: `PublishedApplicationMessage` that carries a source message id.
#:
#: It does not. `resume_outbound_fanouts` → `drive_outbound_fanout` reports the
#: fanout outcome but never emits `PublishedApplicationMessage`; only
#: `publish_queue`'s `ApplicationMessage` arm does. An app message whose
#: **first** accepted delivery happens during resume therefore never runs
#: `finalize_published_app_message_source_retention`: its `source_message_id_hex`
#: correlation and its retention finalisation simply do not happen.
#:
#: For CruxCoach this is not a detail. "The app was killed mid-send" is the
#: normal case on a phone, and §8.4 binds exactly one source id to one inner
#: event id. A delivery the adapter cannot correlate is not an observed
#: delivery, so it stays fail-closed unclear.
RESUME_EMITS_SOURCE_RECEIPT = False

#: wire §11.1 — the open MDK-capability gates of the real-adapter evidence
#: stage **R6**. Each one is a fail-closed technical gate, not an assumption and
#: not a claim.
#:
#: R6 has now executed (:data:`R6_SOURCE`). It refuted four R5 absence/partial
#: findings with real runtime evidence and left the rest standing, so each entry
#: carries both records: `r5_evidence` for what R5 saw, `r6_evidence` for what
#: R6 actually demonstrated, and `blocking_remainder` for what still stands
#: between that and a CruxCoach-suitable closure.
#:
#: Every `status` is still OPEN and every `cruxcoach_closure` is still False.
#: That is a finding, not a formality: R6's own verdict is NO-GO, its criterion
#: 4 is unmet, its instrumentation gate was never run, and its finding 1 breaks
#: the source correlation FEAT-062 depends on. This Python harness is the S9
#: specification stage; it is a spec oracle, it is **not** R6 evidence, and it
#: can never move a gate out of OPEN.
R6_GATES = {
    "R6-G1-DURABLE-PROJECTION": {
        "status": "OPEN",
        "r5_criterion": "E",
        "r5_verdict": "PASS",
        "r6_criterion": None,
        "r6_verdict": "NOT DECIDED",
        "capability": "ABSENT",
        "evidence_scope": "NONE",
        "cruxcoach_closure": False,
        "title": "retention-independent long-lived CruxCoach projection and inbox",
        "r5_evidence": "R5 criterion E PASS: real retention sweeps delete expired raw "
                       "custom rows, so the MDK raw app store is pruned by active group "
                       "retention and is not a long-lived CruxCoach authority.",
        "r6_evidence": "R6 decided nothing here and changed nothing. Its product "
                       "design lock is NO-GO for the same reason as R5's: the raw app "
                       "store is still pruned by active group retention, and neither "
                       "disappearing-message semantics nor a retention-independent "
                       "local projection was decided.",
        "blocking_remainder": "No durable retention-independent projection exists, and "
                              "R6 finding 1 deepens the hole: a first delivery accepted "
                              "during resume never reaches "
                              "finalize_published_app_message_source_retention, so its "
                              "raw row keeps the retention it was created with and can "
                              "be pruned before CruxCoach ever acknowledges it.",
        "fail_closed": "The MDK raw app store is never read as CruxCoach authority. "
                       "Until a durable retention-independent projection exists, an "
                       "item that is gone before its host ACK is terminal with "
                       "PROJECTION_AUTHORITY_INVALID; nothing is reconstructed.",
    },
    "R6-G2-TRANSPORT-IDENTICAL-RETRY": {
        "status": "OPEN",
        "r5_criterion": "C",
        "r5_verdict": "FAIL",
        "r6_criterion": "1",
        "r6_verdict": "PASS",
        "capability": "PROVEN_AT_HOST_SCOPE",
        "evidence_scope": "HOST_RUST_MOCKRELAY",
        "cruxcoach_closure": False,
        "title": "transport-identical retry after an unclear or partial first delivery",
        "r5_evidence": "R5 criterion C FAIL: the pin has no transport-identical retry for "
                       "the publish-failure branch; the attempted payload requeue could "
                       "mint a second MLS transport id for one inner id and was removed.",
        "r6_evidence": "R6 criterion 1 PASS, and it refutes the R5 claim of absence. "
                       "custom_app_message_resumes_transport_identically_after_ambiguous"
                       "_partial_delivery kills the process mid-fanout of a kind-30078 "
                       "custom event against a real MLS session and a real SQLCipher "
                       "store; the durable OutboundFanout holds "
                       "[Accepted, Attempting, NotAttempted]; after restart under a "
                       "changed routing policy, drain() → resume_outbound_fanouts() "
                       "re-publishes exactly the two open endpoints with an identical "
                       "message.id and identical ciphertext bytes, the epoch stays 1, a "
                       "second resume publishes nothing and the fanout is deleted. A "
                       "second test characterises the other side: a definitive relay "
                       "rejection is terminal and is never retried.",
        "blocking_remainder": "The re-drive is proven; the correlation CruxCoach needs "
                              "is not. R6 finding 1: resume_outbound_fanouts never emits "
                              "PublishedApplicationMessage, so a first accepted delivery "
                              "on the resume path yields no source message id and no "
                              "retention finalisation — and 'killed while sending' is "
                              "the normal mobile case. Finding 3: there is no on-demand "
                              "resume; the replay depends on a process or session "
                              "restart. Finding 2: a definitively refused endpoint is "
                              "never served again even when it returns. All of it is "
                              "host/MockRelay evidence with G8 never run.",
        "fail_closed": "An unclear or partial first delivery is retried only by "
                       "re-driving the identical transport message. Binding a second "
                       "distinct source id to one inner event id is terminal with "
                       "RETRY_NOT_TRANSPORT_IDENTICAL; no new MLS or source id may be "
                       "presented as a retry of the same inner id. An expired lease over "
                       "a row whose external effect may already have begun is never "
                       "requeued automatically: it becomes a visible DELIVERY_UNCLEAR row "
                       "that is not automatically sendable (§8.7b). A transport "
                       "acceptance that carries no correlatable source receipt is not an "
                       "observed delivery and is terminalised into the same unclear row "
                       "with ACCEPTED_WITHOUT_SOURCE_RECEIPT (§8.7d).",
    },
    "R6-G3-BROADCAST-LAG-REPLAY": {
        "status": "OPEN",
        "r5_criterion": "B",
        "r5_verdict": "FAIL",
        "r6_criterion": "3",
        "r6_verdict": "PASS",
        "capability": "PROVEN_AT_HOST_SCOPE",
        "evidence_scope": "HOST_RUST_CRATE_INTERNAL",
        "cruxcoach_closure": False,
        "title": "broadcast lag is caught up by durable replay",
        "r5_evidence": "R5 criterion B FAIL: no harness overflows the private "
                       "subscribe_messages broadcast, forces RecvError::Lagged and then "
                       "proves store recovery without loss or duplication.",
        "r6_evidence": "R6 criterion 3 PASS, refuting the R5 claim that no harness "
                       "overflows the broadcast. "
                       "custom_event_survives_a_real_broadcast_lag_through_store_"
                       "recovery_exactly_once delivers the custom event over a real "
                       "MockRelay through a real subscribe_messages subscription and "
                       "overflows the real 1024-slot MarmotAppEvent broadcast: 4200 "
                       "filler events published, 2048 delivered, 2152 genuinely lost. "
                       "The custom event is recovered from the store exactly once with "
                       "its kind, tags, content and transport id, with no second "
                       "recovery in a 3s trailing window and the raw row still single.",
        "blocking_remainder": "The test is crate-internal (#[cfg(test)]) because the "
                              "broadcast sender is private and R6 deliberately added no "
                              "product test hook, and its filler events are synthetic. "
                              "No adapter-reachable or exported surface has been shown "
                              "to drive that recovery, so CruxCoach cannot yet rely on "
                              "it; and it is not native or on-device evidence.",
        "fail_closed": "Correctness never depends on live delivery. A live gap that "
                       "durable replay has not closed holds the affected objects with "
                       "HOLD_REPLAY_INCOMPLETE; a gap that retention already pruned is "
                       "terminal with PROJECTION_AUTHORITY_INVALID.",
    },
    "R6-G4-EXACTLY-ONCE-SCOPE": {
        "status": "OPEN",
        "r5_criterion": "C1",
        "r5_verdict": "PARTIAL",
        "r6_criterion": "2",
        "r6_verdict": "PASS",
        "capability": "PROVEN_AT_HOST_SCOPE",
        "evidence_scope": "HOST_RUST_MOCKRELAY",
        "cruxcoach_closure": False,
        "title": "exactly-once boundary between local reduction and the network",
        "r5_evidence": "R5 criterion C1 PARTIAL: the roundtrip returns on the first match, "
                       "observes no second live emission window and counts no raw rows, "
                       "so exactly-once live emission and raw row count 1 are unproven.",
        "r6_evidence": "R6 criterion 2 PASS, superseding the R5 PARTIAL. "
                       "custom_app_event_emits_once_with_one_raw_row_and_never_notifies_"
                       "new_message runs two identities over a real MockRelay and, "
                       "instead of stopping at the first match, applies redelivery "
                       "pressure twice — catch_up_accounts and then repair_full_history, "
                       "an account-wide relay query through the same ingest, convergence "
                       "and projection path — each with a drained 3s observation window. "
                       "The live counter stays exactly 1. Raw rows: exactly one for the "
                       "inner id at the receiver, exactly one for the payload anywhere, "
                       "exactly one at the sender, with transport-id correlation and "
                       "tags checked.",
        "blocking_remainder": "The evidence bounds live emission and raw rows inside one "
                              "host process against a MockRelay. It is not relay, "
                              "network or peer-receipt exactly-once — which §11.2 "
                              "forbids claiming in any case — and it is not native or "
                              "on-device. It also says nothing about the resume path, "
                              "which R6 finding 1 shows produces no source receipt at "
                              "all, so a resumed first delivery has no counted identity "
                              "to be exactly-once about.",
        "fail_closed": "CruxCoach claims only idempotent local reduction keyed by typed "
                       "ids. No network, relay, live-emission or raw-row exactly-once "
                       "guarantee may be claimed anywhere in this bundle or in the UI.",
    },
    "R6-G5-CUSTOM-REORG": {
        "status": "OPEN",
        "r5_criterion": "C",
        "r5_verdict": "FAIL",
        "r6_criterion": "4",
        "r6_verdict": "NOT MET",
        "capability": "PARTIAL",
        "evidence_scope": "HOST_RUST_MOCKRELAY",
        "cruxcoach_closure": False,
        "title": "deterministic custom reorg and invalidation through the app firehose",
        "r5_evidence": "R5 criterion C FAIL: no harness places a custom app payload "
                       "deterministically on the losing fork and observes its "
                       "invalidation.",
        "r6_evidence": "R6 criterion 4 NOT MET — the one criterion R6 itself failed. "
                       "losing_branch_invalidation_tombstones_a_real_custom_row_and_"
                       "never_reemits_it delivers a real custom event over MockRelay and "
                       "then drives GroupEvent::AppMessageInvalidated{LosingBranch} "
                       "through the productive ingest observer "
                       "observe_account_device_effects. Proven: the real app firehose "
                       "carries transport id, epoch, reason and payload ref unchanged; "
                       "the raw row is tombstoned by transport id with reason "
                       "LosingBranch; the timeline is empty; "
                       "received_message_update_from_record returns None; and after "
                       "close_storage and reopen exactly one row remains, still "
                       "invalidated and still not effective. Not proven: the fork.",
        "blocking_remainder": "The trigger is no longer the gap: R7 produced a real "
                              "relay-driven same-epoch fork through the public app "
                              "runtime with no injected decision, and R8 partitioned one "
                              "device for real and reached the state R7 could not, where "
                              "the device that sent and recorded the custom payload "
                              "actually loses that payload's branch. What R8 then "
                              "observed on the pin is that the direct fork-recovery seam "
                              "withdraws the rolled-back commit's group state and never "
                              "withdraws the app payloads that same rollback discarded, "
                              "so the raw row stays effective with no verdict and no "
                              "reason across restart. That is a located defect rather "
                              "than an untested half, and it is still open here: the "
                              "withdrawal R8 demonstrates comes from a patch to the "
                              "engine's fork-recovery seam that no released MDK carries. "
                              "Two further remainders stand: two members provably stay "
                              "on different canonical branches after the same fork (see "
                              "BRANCH_SELECTION_DIVERGENT), and R6 finding 4 still "
                              "records that the same event routed through "
                              "observe_drained_session_events publishes to subscribers "
                              "while the invalidation projection dispatch does not run.",
        "fail_closed": "Every stored-event invalidation, and every zero- or multi-match "
                       "source mapping, terminalises the generation; no partial "
                       "recomputation and no reorg repair exists.",
    },
    "R6-G6-UNIFFI-FORWARDING": {
        "status": "OPEN",
        "r5_criterion": "A/F",
        "r5_verdict": "NOT TESTED",
        "r6_criterion": "5",
        "r6_verdict": "PASS",
        "capability": "PROVEN_AT_HOST_SCOPE",
        "evidence_scope": "HOST_RUST_MOCKRELAY",
        "cruxcoach_closure": False,
        "title": "public UniFFI forwarding of caller kind, tags and content",
        "r5_evidence": "R5 criterion A/F NOT TESTED: the smoke test proves public "
                       "callability and an invalid group hex only, never real forwarding "
                       "or reserved-kind refusal across the FFI.",
        "r6_evidence": "R6 criterion 5 PASS. "
                       "public_custom_binding_forwards_kind_tags_and_content_unchanged_"
                       "to_a_peer drives two real identities over a real MockRelay using "
                       "only exported bindings (create_identity, create_group, "
                       "subscribe_events, send_custom_app_event, messages, "
                       "shutdown_and_close) with every assertion on FFI DTOs: a "
                       "deliberately hostile payload — leading and trailing spaces, a "
                       "multi-value tag, an emoji, Markdown-like content — arrives "
                       "byte-identical in kind, tags and content, content_tokens stays "
                       "empty, and both raw rows agree. "
                       "public_custom_binding_rejects_every_reserved_kind_fail_closed "
                       "refuses all eleven reserved kinds and leaves no row.",
        "blocking_remainder": "Construction went through the crate-internal Marmot::open "
                              "behind its documented dev/test loopback gate, because the "
                              "exported constructors reject loopback relays "
                              "production-correctly and R6 refused to add an exported "
                              "test-only constructor. The fully exported path from "
                              "constructor to send is therefore still unexecuted, and no "
                              "on-device run exists (G8).",
        "fail_closed": "No public FFI outbound surface is treated as existing. The "
                       "outbound contract stays a spike target, and no send path may be "
                       "enabled on the strength of the runtime-internal builder tests.",
    },
    "R6-G7-INBOUND-NATIVE-DELIVERY": {
        "status": "OPEN",
        "r5_criterion": "D",
        "r5_verdict": "NOT TESTED",
        "r6_criterion": "6",
        "r6_verdict": "PASS",
        "capability": "PROVEN_AT_HOST_SCOPE",
        "evidence_scope": "HOST_RUST_MOCKRELAY",
        "cruxcoach_closure": False,
        "title": "inbound local/native NewMessage path for a custom app event",
        "r5_evidence": "R5 criterion D NOT TESTED: the roundtrip watches the generic "
                       "MessageReceived firehose and never executes a local/native "
                       "notification callback for inbound custom traffic.",
        "r6_evidence": "R6 criterion 6 PASS, inside the same relay test as criterion 2. "
                       "subscribe_notifications — the same source the UniFFI "
                       "NotificationsSubscription and the native callback consume — is "
                       "subscribed before the custom send, and no notification for the "
                       "inner id appears over the whole observation window; the wake "
                       "collection surface collect_notifications_after_wake, the iOS NSE "
                       "path, is equally empty. A following chat message on the same "
                       "stream does produce NotificationTrigger::NewMessage, so the "
                       "absence is a property of the custom kind and not of a dead "
                       "subscription. The generic MessageReceived firehose still fires.",
        "blocking_remainder": "What was observed is the host-side stream the native "
                              "callback shares, not the native callback on a device. "
                              "Until G8 actually runs, no inbound isolation claim in "
                              "this bundle rests on a native surface.",
        "fail_closed": "Inbound isolation is asserted, never assumed: until the native "
                       "inbound path is exercised, no sharing event may reach any "
                       "notification, chat, list or unread surface.",
    },
    "R6-G8-INSTRUMENTATION-RUN": {
        "status": "OPEN",
        "r5_criterion": "F",
        "r5_verdict": "NOT TESTED",
        "r6_criterion": "8",
        "r6_verdict": "NOT TESTED",
        "capability": "NOT_TESTED",
        "evidence_scope": "NONE",
        "cruxcoach_closure": False,
        "title": "on-device instrumentation run on Android arm64",
        "r5_evidence": "R5 criterion F NOT TESTED: the instrumentation APK was compiled "
                       "and packaged, but `connectedDebugAndroidTest` exited 1 with "
                       "'No connected devices!'.",
        "r6_evidence": "R6 criterion 8 NOT TESTED, and honestly recorded as such. "
                       "`adb devices -l` exits 0 with an empty list; there is no "
                       "emulator package, no AVD, and no virtualisation at all "
                       "(/dev/kvm absent, no vmx|svm in /proc/cpuinfo). The arm64-v8a "
                       "bindings and libmarmot_uniffi.so were built against NDK "
                       "27.2.12479018 at API 26, but the run was not performed and was "
                       "not simulated. The consumer APKs were not reproduced either: "
                       "the android-consumer project is not part of MDK and was not "
                       "supplied, and R6 declined to invent it.",
        "blocking_remainder": "Unchanged from R5 and the reason every other gate's "
                              "evidence stays host-scope: FEAT-062 §5 gates 2 and 4-7 "
                              "need real on-device evidence, and a built .so or a "
                              "packaged-but-unrun APK is not a run.",
        "fail_closed": "FEAT-062 §5 gates 2 and 4–7 require real native on-device "
                       "evidence. A compiled-but-unrun instrumentation package, and this "
                       "S9 Python harness, are both explicitly not that evidence.",
    },
}

#: wire §11.1 — what R6 left open that is **not** one of the eight gates.
#:
#: The gate registry above answers "can the adapter do X". These are the other
#: two kinds of open item R6 recorded, and they matter here for opposite
#: reasons: the named findings are why several proven capabilities still do not
#: close their gate, and the release blockers are why a green adapter would
#: still not be shippable. Keeping them executable stops either from being
#: quietly dropped when a gate's prose is next rewritten.
#:
#: ``blocks_cruxcoach`` is deliberately narrower than "is a problem": it marks
#: the ones this specification had to change a normative rule for.
R6_OPEN_CONDITIONS = {
    "R6-F1-RESUME-WITHOUT-PUBLISHED-APP-MESSAGE": {
        "kind": "finding",
        "blocks_cruxcoach": True,
        "summary": "resume_outbound_fanouts reports a published fanout but never emits "
                   "PublishedApplicationMessage; only publish_queue's ApplicationMessage "
                   "arm does. A first accepted delivery during resume therefore never "
                   "runs finalize_published_app_message_source_retention, and no "
                   "source_message_id_hex is ever correlated to it.",
        "normative_effect": "§8.7d ACCEPTED_WITHOUT_SOURCE_RECEIPT: the row stays "
                            "EXTERNAL_EFFECT_UNCLEAR and is never labelled sent. §8.7e "
                            "additionally requires a source-correlated receipt for the "
                            "replay route, so closing R6-G2 alone does not open it.",
    },
    "R6-F2-DEFINITIVE-REJECTION-IS-TERMINAL": {
        "kind": "finding",
        "blocks_cruxcoach": False,
        "summary": "an endpoint that definitively rejects is never served again, even if "
                   "it later returns; only NotAttempted and Attempting count as open.",
        "normative_effect": "a delivery bound, not a correlation break: it is already "
                            "covered by the existing fail-closed labels, which claim "
                            "neither delivery nor non-delivery.",
    },
    "R6-F3-NO-ON-DEMAND-RESUME": {
        "kind": "finding",
        "blocks_cruxcoach": False,
        "summary": "the transport-identical retry depends on a process or session "
                   "restart through drain(); there is no 'resume outbound fanouts for "
                   "group X' API, and retry_group_convergence does something else.",
        "normative_effect": "recorded as a limit on when a replay could ever happen; it "
                            "changes no rule here, because §8.7e already forbids the "
                            "replay route outright while R6-G2 is open.",
    },
    "R6-F4-DRAIN-PATH-ASYMMETRY": {
        "kind": "finding",
        "blocks_cruxcoach": False,
        "summary": "the same AppMessageInvalidated routed through "
                   "observe_drained_session_events is published to subscribers, but the "
                   "projection_update_for_invalidation_event dispatch does not run, so "
                   "subscribers see a withdrawal while the raw row stays effective. R6 "
                   "did not establish whether that path can carry an invalidation in "
                   "production and did not fix it.",
        "normative_effect": "no rule is relaxed on its account: §8.4 already terminalises "
                            "every stored-event invalidation, whichever path reports it.",
    },
    "R6-F5-CUSTOM-REORG-TRIGGER-INJECTED": {
        "kind": "finding",
        "blocks_cruxcoach": True,
        "summary": "the losing-branch decision in the C4 test is injected into the "
                   "productive ingest observer rather than produced by a relay- or "
                   "fork-driven partition, so the app-level trigger is unproven.",
        "normative_effect": "R6-G5-CUSTOM-REORG stays open with capability PARTIAL; this "
                            "is R6's own blocking open gate and by itself makes the "
                            "spike NO-GO.",
    },
    "R6-B1-FIVE-RED-RELAY-TESTS": {
        "kind": "release_blocker",
        "blocks_cruxcoach": False,
        "summary": "five marmot-app relay integration tests fail deterministically: in "
                   "the full gate, individually and serially, at the bare pin without "
                   "any R5 or R6 change, and at origin/master. They are therefore "
                   "pre-existing upstream defects and provably not an R5 or R6 "
                   "regression. R6 did not fix them; that is out of scope.",
        "normative_effect": "no CruxCoach rule depends on them, and a release does: the "
                            "marmot-app quality gate stays red, so the release axis "
                            "stays NO-GO independently of every gate above.",
    },
    "R6-B2-NO-CONSUMER-APKS": {
        "kind": "release_blocker",
        "blocks_cruxcoach": False,
        "summary": "the consumer APKs and the R8 mapping were not reproduced: the "
                   "android-consumer project is not part of the MDK repository and was "
                   "not supplied with the R6 assignment, so no APK build is "
                   "reproducible from the artefacts. R6 did not invent one.",
        "normative_effect": "with R6-G8-INSTRUMENTATION-RUN never executed, there is no "
                            "packaged artefact and no run; neither may be presented as "
                            "native, on-device or release evidence.",
    },
}

#: The closed kinds an open condition may have.
OPEN_CONDITION_KINDS = ("finding", "release_blocker")

#: wire §11.2 — the only exactly-once claim scope v1 permits, as a closed map.
#:
#: This map is about **what CruxCoach is allowed to claim**, not about what the
#: pin was observed to do. The two are different questions and R6 pulled them
#: apart: it demonstrated exactly-once live emission and a raw row count of 1
#: under real redelivery pressure (criterion 2, see ``R6_GATES``), and every
#: entry below is unchanged by that.
#:
#: A ``False`` here is therefore never shorthand for "unproven". It is a
#: standing prohibition on a **product/protocol claim**, and it holds for
#: reasons no adapter evidence can retire:
#:
#: - the evidence is host-scope, one process against a MockRelay, while the
#:   claim would be about relays, networks and peers;
#: - `R6-G8-INSTRUMENTATION-RUN` never ran, so nothing is native or on-device;
#: - and the resume path emits no receipt at all (finding
#:   ``R6-F1-RESUME-WITHOUT-PUBLISHED-APP-MESSAGE``), so there are deliveries
#:   with nothing to be exactly-once *about*.
#:
#: Even a fully closed `R6-G4-EXACTLY-ONCE-SCOPE` would leave this map as it
#: is: the gate governs evidence, §11.2 governs the claim.
EXACTLY_ONCE_SCOPES = {
    "cruxcoach_reduction": True,   # idempotent local reduction keyed by typed ids
    "inner_event_id": True,        # duplicate inner id is a no-op
    "source_message_id": True,     # duplicate source delivery is a no-op
    # Observed once at host scope by R6 C2; still never claimed, because the
    # claim would reach past the process the observation was made in.
    "live_emission": False,
    "relay_delivery": False,       # no relay exactly-once exists
    "network_delivery": False,     # no network exactly-once exists
    # R6 C2 counted exactly one raw row per side. A count is not a guarantee:
    # retention prunes raw rows (G1) and a resumed first delivery is never
    # correlated to one, so the row count is not CruxCoach's to promise.
    "raw_row_count": False,
    "peer_receipt": False,         # v1 defines no peer ACK at all
}
EXACTLY_ONCE_PERMITTED = "PERMITTED"
EXACTLY_ONCE_FORBIDDEN = "FORBIDDEN"

#: wire §8.8 — the closed set of conditions that put the client into
#: `TIME_UNTRUSTED`. These decide whether a time-bounded grant stays hidden and
#: whether an unprovable purge is immediately due, so they are a safety
#: vocabulary and not diagnostics: a code the contract does not state, or one no
#: fixture ever produces, is the same defect here as anywhere else.
TIME_UNTRUSTED_REASONS = frozenset(
    {
        "BOOT_CHANGED",
        "MONOTONIC_RESET",
        "WALL_ROLLBACK",
        "WALL_FROZEN",
    }
)

#: The state word §8.8 prints beside those codes. It is not itself a distrust
#: reason, so it stays out of `TIME_UNTRUSTED_REASONS` and out of the closed-set
#: rule; it exists so the published trusted-time table can be read as
#: "(state, reason)" rather than by keyword.
TIME_UNTRUSTED = "TIME_UNTRUSTED"

#: wire §10 and FEAT-062 §8 — the closed set of causes that retire a grant or a
#: whole generation, in the order both documents print them.
#:
#: Both documents publish this rule, in different words and different column
#: layouts, and neither was bound to anything: a deadline could be relaxed, a
#: terminal generation could be printed as surviving, and every fixture, oracle
#: and manifest check stayed green. That is the same decoration class as an
#: unbound golden value, on the one table a user's revocation actually reaches.
RETIREMENT_CAUSES = (
    "REVOKE",
    "LEVEL_DOWNGRADE",
    "BLOCK_OR_REMOVE",
    "EXPIRY",
    "SUPERSEDING_SET",
    "DISBAND",
)

#: The two purge windows wire §10 states MUST NOT be conflated, in seconds.
#:
#: `POST_EXPIRY_PURGE_DEADLINE_S` is numerically equal to
#: `GRANT_MAX_DURATION_S`, and that coincidence is exactly what §10 warns
#: about: one is measured from issue and bounds a grant, the other is measured
#: from expiry and bounds a purge. They are kept as two names so a later change
#: to either cannot silently move the other.
PURGE_DEADLINE_S = 86_400
POST_EXPIRY_PURGE_DEADLINE_S = 7_776_000


def _purge_fact(deadline_s: int, measured_from: str) -> str:
    """The fact name for one purge window, derived from the window itself."""
    if measured_from == "same_local_transition":
        return "purge_in_the_same_transition"
    if measured_from == "expiry":
        return f"purge_{deadline_s // 86_400}_days_after_expiry"
    return f"purge_within_{deadline_s // 3_600}_h"


#: What each retirement fact must be *spelled* as in a published cell.
#:
#: The two deadline spellings are rendered from the constants above rather than
#: written out, so relaxing a window in the specification without changing the
#: rule — or the reverse — cannot stay green. Everything else in those cells
#: stays free prose: this map states what a row may not stop saying, not what
#: else it may say.
RETIREMENT_FACT_SPELLINGS = {
    "hidden": ("hide", "hidden"),
    "permanently_retired": ("permanently retire",),
    "generation_terminal": ("terminal",),
    _purge_fact(PURGE_DEADLINE_S, "cause"): (f"{PURGE_DEADLINE_S // 3_600} h",),
    _purge_fact(POST_EXPIRY_PURGE_DEADLINE_S, "expiry"): (
        f"{POST_EXPIRY_PURGE_DEADLINE_S // 86_400} days after expiry",),
    _purge_fact(0, "same_local_transition"): ("same committed local transition",),
}

#: wire §11.2 — the closed refusal code a forbidden claim scope carries. It has
#: one member because there is one way to exceed the permitted claim: reaching
#: past local reduction. It is kept as a named set rather than a literal so it
#: joins the same two-sided rule as every other closed vocabulary — the wire
#: contract must name it, and a fixture expectation must decide it.
EXACTLY_ONCE_SCOPE_EXCEEDED = "EXACTLY_ONCE_SCOPE_EXCEEDED"
EXACTLY_ONCE_REFUSAL_REASONS = frozenset({EXACTLY_ONCE_SCOPE_EXCEEDED})


class SpecViolation(Exception):
    """Raised when the oracle is asked to accept something the wire forbids."""


# --------------------------------------------------------------------------
# Primitives (wire §3.1, §4.1)
# --------------------------------------------------------------------------


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def jcs(value: Any) -> bytes:
    """RFC 8785 JCS for the v1 value space.

    v1 payloads contain only objects, arrays, ASCII strings and integers, and
    every unknown member is rejected before this is called, so ``sort_keys``
    over ASCII keys is byte-identical to JCS's UTF-16 code-unit ordering.
    """
    _assert_jcs_value_space(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )


def _assert_jcs_value_space(value: Any) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, float):
        raise SpecViolation("JSON floating point is forbidden in v1")
    if isinstance(value, int):
        if not (MIN_SAFE_INT <= value <= MAX_SAFE_INT):
            raise SpecViolation(f"integer outside the safe range: {value}")
        return
    if value is None or isinstance(value, str):
        return
    if isinstance(value, list):
        for item in value:
            _assert_jcs_value_space(item)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or not key.isascii():
                raise SpecViolation(f"non-ASCII member name: {key!r}")
            _assert_jcs_value_space(item)
        return
    raise SpecViolation(f"value outside the v1 JSON value space: {type(value)!r}")


def u8(value: int) -> bytes:
    return value.to_bytes(1, "big")


def u16be(value: int) -> bytes:
    return value.to_bytes(2, "big")


def u32be(value: int) -> bytes:
    return value.to_bytes(4, "big")


def uuid_bytes(value: str) -> bytes:
    raw = value.replace("-", "")
    if not re.fullmatch(r"[0-9a-f]{32}", raw):
        raise SpecViolation(f"not a lowercase canonical UUID: {value!r}")
    return bytes.fromhex(raw)


def render_uuid(raw: bytes) -> str:
    h = raw.hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def uuid8(digest: bytes) -> str:
    b = bytearray(digest[:16])
    b[6] = (b[6] & 0x0F) | 0x80
    b[8] = (b[8] & 0x3F) | 0x80
    return render_uuid(bytes(b))


def uuid7(unix_ms: int, rand_a: int, rand_b: int) -> str:
    """Mint a UUIDv7 whose 48 timestamp bits really are ``unix_ms``.

    The receiver never trusts these bits as authority time (wire §6.3); the
    sender still has to mint them correctly so the value is a UUIDv7 at all.
    """
    b = bytearray(16)
    b[0:6] = unix_ms.to_bytes(6, "big")
    b[6] = 0x70 | ((rand_a >> 8) & 0x0F)
    b[7] = rand_a & 0xFF
    b[8] = 0x80 | ((rand_b >> 56) & 0x3F)
    b[9:16] = (rand_b & ((1 << 56) - 1)).to_bytes(7, "big")
    return render_uuid(bytes(b))


def uuid_version(value: str) -> int:
    return uuid_bytes(value)[6] >> 4


def uuid_variant_ok(value: str) -> bool:
    return (uuid_bytes(value)[8] & 0xC0) == 0x80


def uuid7_timestamp_ms(value: str) -> int:
    return int.from_bytes(uuid_bytes(value)[0:6], "big")


def quic_varint(value: int) -> bytes:
    """MDK `encode_quic_varint` (crates/traits/src/app_components/codec.rs)."""
    if value < 64:
        return value.to_bytes(1, "big")
    if value < 16_384:
        return (0x4000 | value).to_bytes(2, "big")
    if value < 1_073_741_824:
        return (0x8000_0000 | value).to_bytes(4, "big")
    return (0xC000_0000_0000_0000 | value).to_bytes(8, "big")


def encode_component_vectors(parts: list[bytes]) -> bytes:
    out = b""
    for part in parts:
        out += quic_varint(len(part)) + part
    return out


def floor900(unix_s: int) -> int:
    return (unix_s // DETAIL_START_BUCKET_S) * DETAIL_START_BUCKET_S


def ceil900(unix_s: int) -> int:
    return -((-unix_s) // DETAIL_START_BUCKET_S) * DETAIL_START_BUCKET_S


# --------------------------------------------------------------------------
# Derivations (wire §4.2 – §4.6, §9.2)
# --------------------------------------------------------------------------


def _derive(label: str, payload: bytes) -> tuple[str, str, str]:
    preimage = label.encode("ascii") + b"\x00" + payload
    digest = hashlib.sha256(preimage).digest()
    return preimage.hex(), digest.hex(), uuid8(digest)


def sort_endpoints(a_hex: str, b_hex: str) -> tuple[str, str]:
    for value in (a_hex, b_hex):
        if not re.fullmatch(r"[0-9a-f]{64}", value):
            raise SpecViolation(f"account is not hex32: {value!r}")
    if a_hex == b_hex:
        raise SpecViolation("the two endpoints must be distinct")
    low, high = sorted((bytes.fromhex(a_hex), bytes.fromhex(b_hex)))
    return low.hex(), high.hex()


def generation_id(group_id_hex: str, low: str, high: str) -> tuple[str, str, str]:
    gid = bytes.fromhex(group_id_hex)
    if not 1 <= len(gid) <= 65_535:
        raise SpecViolation("mdk_group_id must be 1..65535 bytes")
    return _derive(
        "cc-ps-generation-v1",
        u16be(len(gid)) + gid + bytes.fromhex(low) + bytes.fromhex(high),
    )


def endpoint_pair_id(low: str, high: str) -> tuple[str, str]:
    preimage = b"cc-ps-endpoints-v1\x00" + bytes.fromhex(low) + bytes.fromhex(high)
    return preimage.hex(), sha256_hex(preimage)


def acceptance_slot(gen: str, high: str) -> tuple[str, str, str]:
    return _derive("cc-ps-acceptance-slot-v1", uuid_bytes(gen) + bytes.fromhex(high))


def offer_slot(gen: str, actor: str) -> tuple[str, str, str]:
    return _derive("cc-ps-offer-slot-v1", uuid_bytes(gen) + bytes.fromhex(actor))


def grant_slot(gen: str, publisher: str, scope: str, plan_uuid: str | None = None):
    if scope not in SCOPE_CODES:
        raise SpecViolation(f"unknown scope {scope!r}")
    suffix = b""
    if scope == "PLAN_DETAIL":
        if plan_uuid is None:
            raise SpecViolation("PLAN_DETAIL needs exactly one recipient-specific plan uuid")
        suffix = uuid_bytes(plan_uuid)
    elif plan_uuid is not None:
        raise SpecViolation(f"{scope} has no resource suffix")
    return _derive(
        "cc-ps-grant-slot-v1",
        uuid_bytes(gen) + bytes.fromhex(publisher) + u8(SCOPE_CODES[scope]) + suffix,
    )


def projection_slot(gen: str, publisher: str, grant_id: str) -> tuple[str, str, str]:
    """wire §4.3. The publisher is part of the preimage.

    Without it, a peer that reuses the other publisher's ``grant_id`` would
    claim the same projection slot. The slot is therefore keyed by
    ``(generation, publisher, grant)`` and the projection's author must equal
    the referenced grant's publisher (wire §7.1).
    """
    return _derive(
        "cc-ps-projection-slot-v1",
        uuid_bytes(gen) + bytes.fromhex(publisher) + uuid_bytes(grant_id),
    )


def session_uuid(gen: str, recipient: str, local_session: str, board_config_id: str):
    return _derive(
        "cc-ps-session-v1",
        uuid_bytes(gen)
        + bytes.fromhex(recipient)
        + uuid_bytes(local_session)
        + bytes.fromhex(board_config_id),
    )


def plan_uuid(gen: str, recipient: str, local_plan: str) -> tuple[str, str, str]:
    return _derive(
        "cc-ps-plan-v1",
        uuid_bytes(gen) + bytes.fromhex(recipient) + uuid_bytes(local_plan),
    )


def canonical_board(board: dict) -> dict:
    required = {"angle_mdeg", "ecosystem", "layout_id", "type", "v"}
    eco = board.get("ecosystem")
    if eco not in ECOSYSTEM_CODES:
        raise SpecViolation(f"unknown ecosystem {eco!r}")
    if eco != "MOONBOARD":
        required |= {"product_id", "size_id"}
    if set(board) != required:
        raise SpecViolation(f"cc.board member set mismatch: {sorted(board)}")
    if board["type"] != "cc.board" or board["v"] != 1:
        raise SpecViolation("cc.board type/version mismatch")
    if not 0 <= board["angle_mdeg"] <= 90_000:
        raise SpecViolation("angle_mdeg out of range")
    for member in ("layout_id", "product_id", "size_id"):
        if member in board and board[member] < 0:
            raise SpecViolation(f"{member} must be non-negative")
    return board


def board_config_id(board: dict) -> str:
    return sha256_hex(jcs(canonical_board(board)))


def hold_fingerprint(ecosystem: str, frames: list[list[tuple[int, int]]]) -> tuple[str, str]:
    out = b"cc-holds-v1\x00" + u8(ECOSYSTEM_CODES[ecosystem]) + u16be(len(frames))
    for frame in frames:
        holds = sorted((int(p), int(r)) for p, r in frame)
        if len(set(holds)) != len(holds):
            raise SpecViolation("duplicate hold in a frame")
        positions = [p for p, _ in holds]
        if len(set(positions)) != len(positions):
            raise SpecViolation("duplicate position with conflicting roles")
        out += u16be(len(holds))
        for position, role in holds:
            out += u32be(position) + u16be(role)
    return out.hex(), sha256_hex(out)


def canonical_problem(problem: dict) -> dict:
    expected = {"board", "hold_fingerprint", "is_mirror", "provider_problem_id", "type", "v"}
    if set(problem) != expected:
        raise SpecViolation(f"cc.problem member set mismatch: {sorted(problem)}")
    if problem["type"] != "cc.problem" or problem["v"] != 1:
        raise SpecViolation("cc.problem type/version mismatch")
    if not re.fullmatch(r"[0-9a-f-]{1,64}", problem["provider_problem_id"]):
        raise SpecViolation("provider_problem_id grammar")
    if not re.fullmatch(r"[0-9a-f]{64}", problem["hold_fingerprint"]):
        raise SpecViolation("hold_fingerprint must be hex32")
    canonical_board(problem["board"])
    return problem


def problem_hash(problem: dict) -> str:
    return sha256_hex(jcs(canonical_problem(problem)))


def bootstrap_marker(attempt_id: str, low: str, high: str) -> dict:
    if uuid_version(attempt_id) != 4 or not uuid_variant_ok(attempt_id):
        raise SpecViolation("attempt_id must be a canonical UUIDv4")
    return {
        "attempt_id": attempt_id,
        "endpoint_high": high,
        "endpoint_low": low,
        "protocol": BOOTSTRAP_PROTOCOL,
    }


def bootstrap_component_0x8001(name: str, description: bytes) -> bytes:
    """MDK encodes the group profile as one QUIC-varint vector per field."""
    return encode_component_vectors([name.encode("utf-8"), description])


# --------------------------------------------------------------------------
# Carrier and canonical content (wire §2.1, §2.3, §3.1)
# --------------------------------------------------------------------------


def nip01_event_id(pubkey: str, created_at: int, kind: int, tags: list, content: str) -> str:
    serialised = json.dumps(
        [0, pubkey, created_at, kind, tags, content], ensure_ascii=False, separators=(",", ":")
    )
    return sha256_hex(serialised.encode("utf-8"))


def marmot_app_event(event_id: str, pubkey: str, created_at: int, content: str) -> str:
    """`MarmotAppEvent::encode()` at MDK pin 101d7994: declaration order, no sig."""
    ordered = {
        "id": event_id,
        "pubkey": pubkey,
        "created_at": created_at,
        "kind": CARRIER_KIND,
        "tags": CARRIER_TAGS,
        "content": content,
    }
    return json.dumps(ordered, ensure_ascii=False, separators=(",", ":"))


def prefilter_carrier(kind: Any, tags: Any) -> tuple[bool, str | None]:
    """Step 2 of wire §2.3: exact kind and byte-exact tag equality.

    A mismatch drops before content is read and stores nothing at all — not
    even a diagnostic record.
    """
    if kind != CARRIER_KIND:
        return False, "kind"
    if tags != CARRIER_TAGS:
        return False, "tags"
    return True, None


def parse_canonical_content(raw: bytes) -> tuple[dict | None, str | None]:
    """Steps 3 of wire §2.3 plus §3.1. Returns ``(object, reason_code)``."""
    if len(raw) > CONTENT_MAX_BYTES:
        return None, "CONTENT_OVERSIZE"
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None, "CONTENT_UTF8_INVALID"
    if unicodedata.normalize("NFC", text) != text:
        return None, "CONTENT_JCS_INVALID"

    duplicate = False

    def _hook(pairs):
        nonlocal duplicate
        keys = [k for k, _ in pairs]
        if len(set(keys)) != len(keys):
            duplicate = True
        return dict(pairs)

    try:
        value = json.loads(text, object_pairs_hook=_hook, parse_float=_reject_float)
    except SpecViolation:
        return None, "CONTENT_JSON_INVALID"
    except json.JSONDecodeError:
        return None, "CONTENT_JSON_INVALID"
    if duplicate:
        return None, "CONTENT_JSON_INVALID"
    if _has_unsafe_number(value):
        return None, "CONTENT_JSON_INVALID"
    if not isinstance(value, dict):
        return None, "CONTENT_JCS_INVALID"
    try:
        if jcs(value) != raw:
            return None, "CONTENT_JCS_INVALID"
    except SpecViolation:
        return None, "CONTENT_JCS_INVALID"
    return value, None


def _reject_float(_text: str):
    raise SpecViolation("JSON floating point is forbidden")


def _has_unsafe_number(value: Any) -> bool:
    """wire §3.1: integers must lie inside the IEEE-754 safe integer range."""
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return not (MIN_SAFE_INT <= value <= MAX_SAFE_INT)
    if isinstance(value, list):
        return any(_has_unsafe_number(v) for v in value)
    if isinstance(value, dict):
        return any(_has_unsafe_number(v) for v in value.values())
    return False


def evaluate_marmot_app_event(event: dict, mls_sender: str) -> tuple[str, str | None]:
    """Steps 1 and 2 of wire §2.3, before any CruxCoach content parse.

    MDK owns the six-field structure, the NIP-01 id and the sender binding; the
    CruxCoach adapter owns the exact kind/tag prefilter. Everything rejected
    here stores nothing at all — not even a diagnostic record.
    """
    expected_members = ["id", "pubkey", "created_at", "kind", "tags", "content"]
    if list(event.keys()) != expected_members:
        return DROP_BEFORE_PARSE, None
    if not _is_hex32(event["id"]) or not _is_hex32(event["pubkey"]):
        return DROP_BEFORE_PARSE, None
    if not _is_unix_s(event["created_at"]):
        return DROP_BEFORE_PARSE, None
    recomputed = nip01_event_id(
        event["pubkey"], event["created_at"], event["kind"], event["tags"], event["content"]
    )
    if recomputed != event["id"]:
        return DROP_BEFORE_PARSE, None
    if event["pubkey"] != mls_sender:
        return DROP_BEFORE_PARSE, None
    ok, _ = prefilter_carrier(event["kind"], event["tags"])
    if not ok:
        return DROP_BEFORE_PARSE, None
    return APPLIED, None


def evaluate_bootstrap_marker(
    observed_name: str,
    observed_description: bytes,
    low: str,
    high: str,
    attempt_id: str,
    after_activation: bool,
) -> tuple[str, str | None]:
    """wire §9.2. Before activation a mismatch is simply not a FEAT-062 group;
    after activation it is an observed profile change and terminalises."""
    expected_name = BOOTSTRAP_NAME
    try:
        expected = jcs(bootstrap_marker(attempt_id, low, high))
    except SpecViolation:
        expected = None
    matches = observed_name == expected_name and expected is not None and observed_description == expected
    if matches:
        return APPLIED, None
    if after_activation:
        return TERMINAL, "PROFILE_MARKER_CHANGED"
    return DROP_BEFORE_PARSE, None


def evaluate_attempt(attempt: dict) -> tuple[str, str | None]:
    """wire §4.2 / §9.1 — the local bootstrap attempt identifier."""
    if attempt.get("derived_from_observable_input"):
        return TERMINAL, "ATTEMPT_ID_NOT_CSPRNG"
    value = attempt["attempt_id"]
    if uuid_version(value) != 4 or not uuid_variant_ok(value):
        return TERMINAL, "ATTEMPT_ID_NOT_CSPRNG"
    if attempt.get("reused_after_abort"):
        return TERMINAL, "ATTEMPT_ID_REUSED"
    if attempt.get("second_group_for_same_attempt"):
        return TERMINAL, "DUPLICATE_GENERATION"
    return APPLIED, None


# --------------------------------------------------------------------------
# Closed schemas (wire §5, §6, §7)
# --------------------------------------------------------------------------

ENVELOPE_REQUIRED = ("author", "body", "endpoints", "generation_id", "issued_at", "object_id", "seq", "type", "v")
ENVELOPE_OPTIONAL = ("previous_event_id",)


def _members_ok(obj: dict, required, optional=()) -> bool:
    keys = set(obj)
    return set(required) <= keys and keys <= set(required) | set(optional)


def _is_hex32(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _is_unix_s(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _is_uuid(value: Any, version: int) -> bool:
    if not isinstance(value, str):
        return False
    try:
        raw = uuid_bytes(value)
    except SpecViolation:
        return False
    return (raw[6] >> 4) == version and (raw[8] & 0xC0) == 0x80


def validate_envelope(env: dict) -> str | None:
    if not _members_ok(env, ENVELOPE_REQUIRED, ENVELOPE_OPTIONAL):
        return "ENVELOPE_SCHEMA_INVALID"
    if env["type"] != "cc.envelope.v1" or env["v"] != 1:
        return "ENVELOPE_SCHEMA_INVALID"
    if not (isinstance(env["endpoints"], list) and len(env["endpoints"]) == 2):
        return "ENVELOPE_SCHEMA_INVALID"
    if not all(_is_hex32(e) for e in env["endpoints"]):
        return "ENVELOPE_SCHEMA_INVALID"
    if bytes.fromhex(env["endpoints"][0]) >= bytes.fromhex(env["endpoints"][1]):
        return "ENVELOPE_SCHEMA_INVALID"
    if not _is_hex32(env["author"]) or env["author"] not in env["endpoints"]:
        return "AUTHOR_BINDING_INVALID"
    for member in ("generation_id", "object_id"):
        value = env[member]
        if not _is_uuid(value, 8):
            return "ENVELOPE_SCHEMA_INVALID"
    if not isinstance(env["seq"], int) or isinstance(env["seq"], bool) or env["seq"] < 1:
        return "ENVELOPE_SCHEMA_INVALID"
    if env["seq"] == 1 and "previous_event_id" in env:
        return "PREDECESSOR_INVALID"
    if env["seq"] > 1 and "previous_event_id" not in env:
        return "PREDECESSOR_INVALID"
    if "previous_event_id" in env and not _is_hex32(env["previous_event_id"]):
        return "PREDECESSOR_INVALID"
    if not _is_unix_s(env["issued_at"]):
        return "ENVELOPE_SCHEMA_INVALID"
    if not isinstance(env["body"], dict):
        return "BODY_SCHEMA_INVALID"
    if env["body"].get("type") not in BODY_TYPES:
        return "BODY_SCHEMA_INVALID"
    return validate_body(env["body"], env)


def validate_body(body: dict, env: dict) -> str | None:
    kind = body.get("type")
    if body.get("v") != 1:
        return "BODY_SCHEMA_INVALID"
    if kind == "cc.generation.accept.v1":
        if not _members_ok(body, ("acceptance_revision", "type", "v")):
            return "BODY_SCHEMA_INVALID"
        rev = body["acceptance_revision"]
        if not isinstance(rev, int) or isinstance(rev, bool) or not 1 <= rev <= 1000:
            return "BODY_SCHEMA_INVALID"
        return None
    if kind == "cc.relationship.offer.v1":
        if not _members_ok(body, ("offer", "type", "v")):
            return "BODY_SCHEMA_INVALID"
        if body["offer"] not in OFFER_VALUES:
            return "BODY_SCHEMA_INVALID"
        return None
    if kind == "cc.grant.set.v1":
        return _validate_grant_set(body, env)
    if kind == "cc.grant.revoke.v1":
        return _validate_grant_revoke(body, env)
    if kind in ("cc.projection.snapshot.v1", "cc.projection.delta.v1"):
        return _validate_projection(body, env)
    return "BODY_SCHEMA_INVALID"


def _validate_grant_set(body: dict, env: dict) -> str | None:
    required = [
        "expires_at",
        "fields",
        "grant_id",
        "issued_at",
        "offer_heads",
        "publisher",
        "recipient",
        "scope",
        "type",
        "v",
    ]
    optional = ["not_before", "resource_id", "supersedes_grant_id"]
    if not _members_ok(body, required, optional):
        return "BODY_SCHEMA_INVALID"
    scope = body["scope"]
    if scope not in SCOPE_CODES:
        return "BODY_SCHEMA_INVALID"
    if body["publisher"] != env["author"]:
        return "AUTHOR_BINDING_INVALID"
    others = [e for e in env["endpoints"] if e != body["publisher"]]
    if body["recipient"] not in others:
        return "BODY_SCHEMA_INVALID"
    if not _is_uuid(body["grant_id"], 7):
        return "BODY_SCHEMA_INVALID"
    if body["issued_at"] != env["issued_at"]:
        return "BODY_SCHEMA_INVALID"
    if not _is_unix_s(body["expires_at"]):
        return "BODY_SCHEMA_INVALID"
    if not body["issued_at"] < body["expires_at"] <= body["issued_at"] + GRANT_MAX_DURATION_S:
        return "BODY_SCHEMA_INVALID"
    fields = body["fields"]
    allowed = SCOPE_FIELDS[scope]
    if not isinstance(fields, list) or not fields:
        return "BODY_SCHEMA_INVALID"
    if fields != sorted(set(fields)) or any(f not in allowed for f in fields):
        return "BODY_SCHEMA_INVALID"
    heads = body["offer_heads"]
    if not isinstance(heads, list) or len(heads) != 2 or not all(_is_hex32(h) for h in heads):
        return "BODY_SCHEMA_INVALID"
    if heads[0] == heads[1]:
        return "BODY_SCHEMA_INVALID"
    if scope == "PLAN_DETAIL":
        if not _is_uuid(body.get("resource_id"), 8):
            return "BODY_SCHEMA_INVALID"
    elif "resource_id" in body:
        return "BODY_SCHEMA_INVALID"
    if scope in DETAIL_SCOPES:
        if not _is_unix_s(body.get("not_before")):
            return "BODY_SCHEMA_INVALID"
    elif "not_before" in body:
        return "BODY_SCHEMA_INVALID"
    if env["seq"] == 1 and "supersedes_grant_id" in body:
        return "BODY_SCHEMA_INVALID"
    if env["seq"] > 1 and "supersedes_grant_id" not in body:
        return "BODY_SCHEMA_INVALID"
    return None


def _validate_grant_revoke(body: dict, env: dict) -> str | None:
    if not _members_ok(body, ("grant_id", "publisher", "reason", "recipient", "scope", "type", "v")):
        return "BODY_SCHEMA_INVALID"
    if body["scope"] not in SCOPE_CODES or body["reason"] not in REVOKE_REASONS:
        return "BODY_SCHEMA_INVALID"
    if not _is_uuid(body["grant_id"], 7):
        return "BODY_SCHEMA_INVALID"
    # wire §6.4: the revoke's author is the grant's publisher, stated as
    # explicitly here as for cc.grant.set.v1.
    if body["publisher"] != env["author"]:
        return "AUTHOR_BINDING_INVALID"
    others = [e for e in env["endpoints"] if e != body["publisher"]]
    if body["recipient"] not in others:
        return "BODY_SCHEMA_INVALID"
    return None


def _validate_projection(body: dict, env: dict) -> str | None:
    is_delta = body["type"] == "cc.projection.delta.v1"
    required = ["as_of", "fields", "grant_event_id", "grant_id", "scope", "type", "v"]
    required.append("ops" if is_delta else "payload")
    optional = ["prospective_from", "resource_id"]
    if not _members_ok(body, required, optional):
        return "BODY_SCHEMA_INVALID"
    scope = body["scope"]
    if scope not in SCOPE_CODES:
        return "BODY_SCHEMA_INVALID"
    if not _is_hex32(body["grant_event_id"]) or not _is_uuid(body["grant_id"], 7):
        return "BODY_SCHEMA_INVALID"
    if not _is_unix_s(body["as_of"]):
        return "BODY_SCHEMA_INVALID"
    fields = body["fields"]
    allowed = SCOPE_FIELDS[scope]
    if not isinstance(fields, list) or not fields or fields != sorted(set(fields)):
        return "BODY_SCHEMA_INVALID"
    if any(f not in allowed for f in fields):
        return "BODY_SCHEMA_INVALID"
    if scope in DETAIL_SCOPES:
        if not _is_unix_s(body.get("prospective_from")):
            return "BODY_SCHEMA_INVALID"
    elif "prospective_from" in body:
        return "BODY_SCHEMA_INVALID"
    if scope == "PLAN_DETAIL":
        if "resource_id" not in body:
            return "BODY_SCHEMA_INVALID"
    elif "resource_id" in body:
        return "BODY_SCHEMA_INVALID"
    if env["seq"] == 1 and is_delta:
        return "BODY_SCHEMA_INVALID"
    if is_delta:
        return _validate_ops(body)
    return _validate_payload(body["payload"], body, env)


def _validate_ops(body: dict) -> str | None:
    ops = body["ops"]
    if not isinstance(ops, list) or not 1 <= len(ops) <= BOUNDS["delta_ops"]:
        return "BODY_SCHEMA_INVALID"
    scope = body["scope"]
    for op in ops:
        if not isinstance(op, dict) or "op" not in op:
            return "BODY_SCHEMA_INVALID"
        name = op["op"]
        # wire §7.6's closed per-scope operation set, consulted rather than
        # restated. The branches below still decide each operation's shape.
        if name not in SCOPE_DELTA_OPS.get(scope, ()):
            return "BODY_SCHEMA_INVALID"
        if scope in ("LOGBOOK_SUMMARY", "PLAN_SUMMARY"):
            if name != "SET_SUMMARY" or set(op) != {"op", "row"}:
                return "BODY_SCHEMA_INVALID"
            if _validate_summary_row(op["row"], body) is not None:
                return "BODY_SCHEMA_INVALID"
        elif scope == "LOGBOOK_DETAIL":
            if name == "UPSERT_SESSION":
                if set(op) != {"op", "row"}:
                    return "BODY_SCHEMA_INVALID"
                if _validate_session_row(op["row"], body) is not None:
                    return "BODY_SCHEMA_INVALID"
            elif name == "DELETE_SESSION":
                if set(op) != {"op", "session_uuid"} or not _is_uuid(op["session_uuid"], 8):
                    return "BODY_SCHEMA_INVALID"
            else:
                return "BODY_SCHEMA_INVALID"
        elif scope == "PLAN_DETAIL":
            if name == "UPSERT_PLAN":
                if set(op) != {"op", "plan"}:
                    return "BODY_SCHEMA_INVALID"
                if _validate_plan(op["plan"], body, allow_null=False) is not None:
                    return "BODY_SCHEMA_INVALID"
            elif name == "DELETE_PLAN":
                if set(op) != {"op"}:
                    return "BODY_SCHEMA_INVALID"
            else:
                return "BODY_SCHEMA_INVALID"
    return None


def _validate_payload(payload: Any, body: dict, env: dict) -> str | None:
    scope = body["scope"]
    if not isinstance(payload, dict):
        return "BODY_SCHEMA_INVALID"
    if scope == "LOGBOOK_SUMMARY" or scope == "PLAN_SUMMARY":
        return _validate_summary_row(payload, body)
    if scope == "LOGBOOK_DETAIL":
        expected = {"prospective_from", "sessions", "type", "v"}
        if set(payload) != expected or payload["type"] != "cc.logbook_detail" or payload["v"] != 1:
            return "BODY_SCHEMA_INVALID"
        if payload["prospective_from"] != body["prospective_from"]:
            return "BODY_SCHEMA_INVALID"
        sessions = payload["sessions"]
        if not isinstance(sessions, list) or len(sessions) > BOUNDS["sessions_per_logbook_grant"]:
            return "BODY_SCHEMA_INVALID"
        uuids = [s.get("session_uuid") if isinstance(s, dict) else None for s in sessions]
        if uuids != sorted(u for u in uuids if isinstance(u, str)) or len(set(uuids)) != len(uuids):
            return "BODY_SCHEMA_INVALID"
        for row in sessions:
            reason = _validate_session_row(row, body)
            if reason:
                return reason
        return None
    if scope == "PLAN_DETAIL":
        expected = {"plan", "prospective_from", "type", "v"}
        if set(payload) != expected or payload["type"] != "cc.plan_detail" or payload["v"] != 1:
            return "BODY_SCHEMA_INVALID"
        if payload["prospective_from"] != body["prospective_from"]:
            return "BODY_SCHEMA_INVALID"
        # wire §7.5: the sequence-1 snapshot requires the selected plan
        # object; only a later full snapshot may carry plan: null.
        return _validate_plan(payload["plan"], body, allow_null=env["seq"] > 1)
    return "BODY_SCHEMA_INVALID"


def _validate_summary_row(row: Any, body: dict) -> str | None:
    if not isinstance(row, dict):
        return "BODY_SCHEMA_INVALID"
    scope = body["scope"]
    want_type = "cc.logbook_summary" if scope == "LOGBOOK_SUMMARY" else "cc.plan_summary"
    if row.get("type") != want_type or row.get("v") != 1:
        return "BODY_SCHEMA_INVALID"
    tokens = set(row) - {"type", "v"}
    granted = set(body["fields"])
    if scope == "PLAN_SUMMARY" and not tokens:
        return None  # the single closed absence state
    if tokens != granted:
        return "BODY_SCHEMA_INVALID"
    if "all_time_send_count" in row and not 0 <= row["all_time_send_count"] <= 1_000_000:
        return "BODY_SCHEMA_INVALID"
    if "hardest_grade_milli" in row:
        value = row["hardest_grade_milli"]
        if value != 0 and not 10_000 <= value <= 34_000:
            return "BODY_SCHEMA_INVALID"
    if "phase" in row and row["phase"] not in PHASES:
        return "BODY_SCHEMA_INVALID"
    if "sessions_per_week" in row and not 0 <= row["sessions_per_week"] <= 21:
        return "BODY_SCHEMA_INVALID"
    return None


def _validate_session_row(row: Any, body: dict) -> str | None:
    if not isinstance(row, dict):
        return "BODY_SCHEMA_INVALID"
    granted = set(body["fields"])
    if set(row) != granted | {"session_uuid"}:
        return "BODY_SCHEMA_INVALID"
    if not _is_uuid(row["session_uuid"], 8):
        return "BODY_SCHEMA_INVALID"
    if "started_at_utc" in row:
        start = row["started_at_utc"]
        if not _is_unix_s(start):
            return "BODY_SCHEMA_INVALID"
        # wire §7.4: 900-aligned and at or after the boundary — a receiver
        # rule, not only a producer rule.
        if start % DETAIL_START_BUCKET_S != 0:
            return "BODY_SCHEMA_INVALID"
        if start < body["prospective_from"]:
            return "BODY_SCHEMA_INVALID"
    if "duration_minutes" in row:
        value = row["duration_minutes"]
        if not isinstance(value, int) or value % 15 or not 15 <= value <= 720:
            return "BODY_SCHEMA_INVALID"
    if "board_config_id" in row and not _is_hex32(row["board_config_id"]):
        return "BODY_SCHEMA_INVALID"
    if "sends" in row:
        sends = row["sends"]
        if not isinstance(sends, list) or len(sends) > BOUNDS["sends_per_session"]:
            return "BODY_SCHEMA_INVALID"
        hashes = []
        for send in sends:
            if not isinstance(send, dict) or set(send) != {"grade_milli", "problem_hash"}:
                return "BODY_SCHEMA_INVALID"
            if not _is_hex32(send["problem_hash"]):
                return "BODY_SCHEMA_INVALID"
            if not 10_000 <= send["grade_milli"] <= 34_000:
                return "BODY_SCHEMA_INVALID"
            hashes.append(send["problem_hash"])
        if hashes != sorted(hashes) or len(set(hashes)) != len(hashes):
            return "BODY_SCHEMA_INVALID"
    return None


def _validate_plan(plan: Any, body: dict, allow_null: bool) -> str | None:
    if plan is None:
        return None if allow_null else "BODY_SCHEMA_INVALID"
    if not isinstance(plan, dict):
        return "BODY_SCHEMA_INVALID"
    if set(plan) != set(body["fields"]):
        return "BODY_SCHEMA_INVALID"
    if "phase" in plan and plan["phase"] not in PHASES:
        return "BODY_SCHEMA_INVALID"
    if "sessions_per_week" in plan and not 0 <= plan["sessions_per_week"] <= 21:
        return "BODY_SCHEMA_INVALID"
    if "focus_areas" in plan:
        areas = plan["focus_areas"]
        if not isinstance(areas, list) or not 1 <= len(areas) <= BOUNDS["focus_areas"]:
            return "BODY_SCHEMA_INVALID"
        if areas != sorted(set(areas)) or any(a not in FOCUS_AREAS for a in areas):
            return "BODY_SCHEMA_INVALID"
    if "sessions" in plan:
        sessions = plan["sessions"]
        if not isinstance(sessions, list) or len(sessions) > BOUNDS["planned_sessions"]:
            return "BODY_SCHEMA_INVALID"
        keys = []
        for row in sessions:
            if not isinstance(row, dict) or set(row) != {
                "day_of_week",
                "session_type",
                "target_duration_min",
                "target_rpe_deci",
            }:
                return "BODY_SCHEMA_INVALID"
            if not 0 <= row["day_of_week"] <= 6:
                return "BODY_SCHEMA_INVALID"
            if row["session_type"] not in SESSION_TYPES:
                return "BODY_SCHEMA_INVALID"
            if not 0 <= row["target_duration_min"] <= 600:
                return "BODY_SCHEMA_INVALID"
            if not 10 <= row["target_rpe_deci"] <= 100:
                return "BODY_SCHEMA_INVALID"
            keys.append((row["day_of_week"], row["session_type"]))
        if keys != sorted(keys):
            return "BODY_SCHEMA_INVALID"
    return None


# --------------------------------------------------------------------------
# Trusted time (wire §8.8)
# --------------------------------------------------------------------------


class TrustedClock:
    """Reboot-aware monotonic high-water clock.

    Within one boot ``effective_now`` advances monotonically. A boot change, a
    monotonic reset, a wall rollback or a frozen wall clock puts the clock in
    ``TIME_UNTRUSTED``, which is fail-closed: time-bounded grants are hidden,
    permissive send/apply is blocked, and every purge is immediately due.
    """

    FREEZE_TOLERANCE_S = 900

    def __init__(self, boot_id: str, monotonic_anchor: int, wall_high_water: int):
        self.boot_id = boot_id
        self.monotonic_anchor = monotonic_anchor
        self.wall_high_water = wall_high_water
        self.untrusted = False
        self.untrusted_reason: str | None = None

    def observe(self, boot_id: str, monotonic_now: int, wall_now: int) -> int | None:
        if boot_id != self.boot_id:
            return self._distrust("BOOT_CHANGED")
        if monotonic_now < self.monotonic_anchor:
            return self._distrust("MONOTONIC_RESET")
        delta = monotonic_now - self.monotonic_anchor
        if wall_now < self.wall_high_water:
            return self._distrust("WALL_ROLLBACK")
        if delta >= self.FREEZE_TOLERANCE_S and wall_now <= self.wall_high_water:
            return self._distrust("WALL_FROZEN")
        effective = max(wall_now, self.wall_high_water + delta)
        self.wall_high_water = effective
        self.monotonic_anchor = monotonic_now
        return effective

    def _distrust(self, reason: str) -> None:
        if reason not in TIME_UNTRUSTED_REASONS:  # pragma: no cover - defensive
            raise SpecViolation(f"unknown trusted-time distrust reason {reason!r}")
        self.untrusted = True
        self.untrusted_reason = reason
        return None

    def reinitialise(self, boot_id: str, monotonic_now: int, trusted_wall_now: int) -> int:
        """The only exit from TIME_UNTRUSTED: an explicit trusted re-anchor.

        Never a peer's ``issued_at``, never a relay timestamp.
        """
        self.boot_id = boot_id
        self.monotonic_anchor = monotonic_now
        self.wall_high_water = max(self.wall_high_water, trusted_wall_now)
        self.untrusted = False
        self.untrusted_reason = None
        return self.wall_high_water


# --------------------------------------------------------------------------
# Outbox (wire §8.7)
# --------------------------------------------------------------------------


def outbox_label(state: str) -> str | None:
    if state not in OUTBOX_STATES:
        raise SpecViolation(f"unknown outbox state {state!r}")
    return OUTBOX_LABELS[state]


def lease_is_recoverable(row: dict, now: int, current_boot_id: str) -> bool:
    """The lease itself is expired, so another worker may reclaim the row.

    A monotonic ``lease_until`` from a previous boot is meaningless, so the
    boot check comes first and never compares the two values.

    Reclaiming the lease is **not** the same as requeueing the row. This
    predicate says only that no worker holds it any more; what the reclaiming
    worker is then allowed to do is decided by §8.7b, which is where the
    external-effect phase enters.
    """
    if row["state"] != "IN_FLIGHT":
        return False
    if row.get("lease_boot_id") != current_boot_id:
        return True
    return row["lease_until"] <= now


def supersession_allowed(prior_kind: str, next_kind: str) -> bool:
    """wire §8.7: a permissive row may never supersede a restrictive one."""
    if prior_kind not in ("restrictive", "permissive") or next_kind not in (
        "restrictive",
        "permissive",
    ):
        raise SpecViolation("supersession kinds are closed")
    return not (prior_kind == "restrictive" and next_kind == "permissive")


def relay_publication_claim(receipt: dict) -> str:
    """wire §10: the label is earned by persisted evidence, never assumed.

    ``MDK_CANONICAL`` requires both the canonical-branch report and at least
    one concrete accepted relay endpoint in the durable typed receipt.
    """
    canonical = receipt.get("canonical_at") is not None
    accepted = list(receipt.get("accepted_relays") or [])
    if canonical and accepted:
        return OUTBOX_LABELS["MDK_CANONICAL"]
    return OUTBOX_LABELS["SENT_LOCALLY"]


# --------------------------------------------------------------------------
# Topology / component profile (wire §9.3, §9.5)
# --------------------------------------------------------------------------

REQUIRED_COMPONENT_IDS = ("0x8001", "0x8003", "0x8004", "0x8009", "0x800c")
GROUP_CONTEXT_STATE_IDS = ("0x8001", "0x8003", "0x8004", "0x800c")
LEAF_ONLY_IDS = ("0x8009",)
FORBIDDEN_COMPONENT_IDS = ("0x8002", "0x8005", "0x8006", "0x8007", "0x8008", "0x800a", "0x800b")


def expected_admin_policy(phase: str, low: str, high: str, committer: str | None = None):
    """wire §9.3 — 0x8003 is phase-dependent; only its id and placement are not.

    At the MDK pin the creator is the implicit sole admin, promotion is a
    separate commit, and a disband commit reduces the admin policy to exactly
    the committer.
    """
    if phase in ("PROVISIONING_SELF_ONLY", "PROVISIONING_INVITED"):
        return [low]
    if phase == "ACTIVE":
        return sorted([low, high], key=bytes.fromhex)
    if phase == "DISBANDED":
        if committer is None:
            raise SpecViolation("a disbanded group names its committer")
        return [committer]
    raise SpecViolation(f"unknown phase {phase!r}")


def expected_lifecycle_byte(phase: str) -> str:
    return "0x01" if phase == "DISBANDED" else "0x00"


PHASES_TOPOLOGY = (
    "PROVISIONING_SELF_ONLY",
    "PROVISIONING_INVITED",
    "ACTIVE",
    "DISBANDED",
)


def expected_leaf_accounts(phase: str, low: str, high: str, committer: str | None = None):
    if phase == "PROVISIONING_SELF_ONLY":
        return [low]
    if phase in ("PROVISIONING_INVITED", "ACTIVE"):
        return sorted([low, high], key=bytes.fromhex)
    if phase == "DISBANDED":
        if committer is None:
            raise SpecViolation("a disbanded group names its committer")
        return [committer]
    raise SpecViolation(f"unknown phase {phase!r}")


def evaluate_topology(state: dict) -> tuple[str, str | None]:
    """One session-consistent MDK read (wire §9.3, §9.5).

    Returns ``(outcome, reason)`` where outcome is ``PROVISIONING``, ``HOLD``,
    ``ACTIVE`` or ``TERMINAL``. Component **ids and placement** are invariant;
    the **values** of `0x8003` and `0x800c` are phase-dependent, because at the
    MDK pin the creator is the implicit sole admin, promotion is a separate
    commit, and a disband commit reduces both.
    """
    phase = state.get("phase", "ACTIVE")
    if phase not in PHASES_TOPOLOGY:
        raise SpecViolation(f"unknown phase {phase!r}")
    low, high = state["endpoint_low"], state["endpoint_high"]

    if state.get("actual_creator", low) != low:
        return TERMINAL, "WRONG_CREATOR"
    if state.get("duplicate_nonterminal_groups"):
        return TERMINAL, "DUPLICATE_GENERATION"
    if state.get("mdk_lifecycle") == "Unrecoverable":
        return TERMINAL, "MDK_UNRECOVERABLE"
    if state.get("mdk_quarantined"):
        return TERMINAL, "MDK_QUARANTINE"
    if state.get("marker_changed_after_activation"):
        return TERMINAL, "PROFILE_MARKER_CHANGED"

    signed_lifecycle = state.get("signed_lifecycle", "active")
    lifecycle_byte = state.get("lifecycle_byte", expected_lifecycle_byte(phase))
    if signed_lifecycle != "active" or lifecycle_byte != "0x00":
        return TERMINAL, "LIFECYCLE_NOT_ACTIVE"

    required = list(state.get("group_context_required", REQUIRED_COMPONENT_IDS))
    if required != sorted(REQUIRED_COMPONENT_IDS):
        return TERMINAL, "TOPOLOGY_INVALID"
    present = list(state.get("group_context_state", GROUP_CONTEXT_STATE_IDS))
    if sorted(present) != sorted(GROUP_CONTEXT_STATE_IDS):
        return TERMINAL, "TOPOLOGY_INVALID"
    if state.get("group_context_has_0x0002"):
        return TERMINAL, "TOPOLOGY_INVALID"
    for leaf_dict in state.get("leaf_dictionaries", [["0x0001", "0x0002", "0x8009"]]):
        if sorted(leaf_dict) != sorted(["0x0001", "0x0002", "0x8009"]):
            return TERMINAL, "TOPOLOGY_INVALID"

    admins = list(state.get("admin_accounts", expected_admin_policy(phase, low, high)))
    leaves = list(state.get("leaf_accounts", expected_leaf_accounts(phase, low, high)))
    if phase in ("PROVISIONING_SELF_ONLY", "PROVISIONING_INVITED"):
        # A missing high admin before the canonical promotion is the expected
        # provisioning state, not a fault.
        if admins != expected_admin_policy(phase, low, high):
            return TERMINAL, "TOPOLOGY_INVALID"
        if sorted(leaves) != sorted(expected_leaf_accounts(phase, low, high)):
            return TERMINAL, "TOPOLOGY_INVALID"
        return "PROVISIONING", None
    if phase == "ACTIVE":
        if sorted(leaves) != sorted([low, high]) or len(leaves) != 2:
            return TERMINAL, "TOPOLOGY_INVALID"
        if sorted(admins) != sorted([low, high]):
            return TERMINAL, "TOPOLOGY_INVALID"
        if not state.get("acceptance_applied", True):
            return "PROVISIONING", None
        if state.get("mdk_lifecycle", "Stable") != "Stable":
            return "HOLD", None
        return "ACTIVE", None
    return TERMINAL, "LIFECYCLE_NOT_ACTIVE"


def evaluate_commit_rollback(signal: dict) -> tuple[str, str | None]:
    """wire §8.4a — the pin's commit-rollback seam at an authority boundary."""
    if signal["event"] not in ROLLBACK_EVENTS:
        raise SpecViolation(f"unknown rollback event {signal['event']!r}")
    commit_class = signal["commit_class"]
    if commit_class not in AUTHORITY_COMMITS + ("unrelated",):
        raise SpecViolation(f"unknown commit class {commit_class!r}")
    # FEAT-062 R8 (A3) — the two resolution seams can settle on DIFFERENT
    # canonical branches for the same fork and stay there with no further input.
    # R8 reproduced it: the direct fork-recovery seam orders by
    # (source epoch, priority, committer, digest) and carries no app-payload
    # term, while witness-aware stored convergence stops earlier, at
    # `app_witness_score` — and a device can only ever count witnesses on a
    # branch it has already applied, because a rival branch's payloads are
    # wrapped under that branch's own epoch exporter secret. A peer's authority
    # decision is then not a fact about this device's state, so this is decided
    # BEFORE the class and activation tests: it is terminal whatever the commit
    # class, and re-reading cannot repair it.
    if signal.get("peer_branch_divergent"):
        return TERMINAL, "BRANCH_SELECTION_DIVERGENT"
    if commit_class == "unrelated":
        return "HOLD", None
    if signal.get("generation_state") == "ACTIVE":
        return TERMINAL, "COMMIT_ROLLBACK_AFTER_ACTIVATION"
    return "HOLD", None


def rollback_keeps_tombstones(signal: dict) -> bool:
    """wire §8.4a rule 5: a rollback never revives a safety-lowering tombstone.

    The answer is unconditionally ``True``; the signal is still validated so a
    fixture cannot assert stickiness for a signal the contract does not define.
    """
    evaluate_commit_rollback(signal)
    return True


def rollback_advances_purge_deadline(signal: dict) -> bool:
    """wire §8.4a rule 1: the rollback itself advances no purge deadline."""
    evaluate_commit_rollback(signal)
    return False


# --------------------------------------------------------------------------
# Reducer (wire §6, §7, §8.1 – §8.3)
# --------------------------------------------------------------------------


class Reducer:
    """Deterministic, fail-closed application reducer for one generation."""

    def __init__(self, generation_id_value: str, low: str, high: str):
        self.generation_id = generation_id_value
        self.low = low
        self.high = high
        self.terminal_reason: str | None = None
        self.applied: dict[tuple[str, int], dict] = {}
        self.slot_tail: dict[str, int] = {}
        self.held: list[dict] = []
        self.reconciling: set[str] = set()
        self.diagnostics: list[dict] = []
        self.offer_heads: dict[str, dict] = {}
        self.offer_chain: dict[str, list[dict]] = {}
        self.grants: dict[str, dict] = {}          # grant_id -> record
        self.grant_publisher: dict[str, str] = {}  # grant_id -> publisher
        self.retired_grants: set[str] = set()
        self.projections: dict[str, dict] = {}
        self.acceptance_applied = False
        self.active = False
        self.held_bytes = 0
        self._releasing = False

    # -- helpers ---------------------------------------------------------
    @property
    def terminal(self) -> bool:
        return self.terminal_reason is not None

    def _terminalise(self, reason: str) -> tuple[str, str]:
        if reason not in TERMINAL_REASONS:
            raise SpecViolation(f"terminal reason not in the closed set: {reason}")
        if self.terminal_reason is None:
            self.terminal_reason = reason
        return TERMINAL, reason

    def _diagnostic(self, reason: str, disposition: str) -> tuple[str, str]:
        if reason not in DIAGNOSTIC_REASONS:
            raise SpecViolation(f"diagnostic reason not in the closed set: {reason}")
        if len(self.diagnostics) < BOUNDS["diagnostics_per_generation"]:
            self.diagnostics.append({"reason": reason})
        return disposition, reason

    def effective_level(self) -> str:
        levels = []
        for endpoint in (self.low, self.high):
            head = self.offer_heads.get(endpoint)
            levels.append(head["offer"] if head else "NOT_ESTABLISHED")
        return min(levels, key=LEVELS.index)

    # -- ingest ----------------------------------------------------------
    def ingest(self, item: dict) -> tuple[str, str | None]:
        """Ingest one delivered carrier item and return ``(disposition, reason)``."""
        ok, _ = prefilter_carrier(item.get("kind", CARRIER_KIND), item.get("tags", CARRIER_TAGS))
        if not ok:
            return DROP_BEFORE_PARSE, None

        raw = item["content"].encode("utf-8") if isinstance(item["content"], str) else item["content"]
        env, reason = parse_canonical_content(raw)
        if reason:
            return self._diagnostic(reason, BOUNDED_DIGEST_ONLY)

        reason = validate_envelope(env)
        if reason:
            return self._diagnostic(reason, REJECT_NO_SLOT)

        if env["generation_id"] != self.generation_id:
            return self._diagnostic("GENERATION_BINDING_INVALID", REJECT_NO_SLOT)
        if env["endpoints"] != [self.low, self.high]:
            return self._diagnostic("GENERATION_BINDING_INVALID", REJECT_NO_SLOT)
        if item.get("authenticated_sender", env["author"]) != env["author"]:
            return self._diagnostic("AUTHOR_BINDING_INVALID", REJECT_NO_SLOT)

        reason = self._check_slot_binding(env)
        if reason:
            return self._diagnostic(reason, REJECT_NO_SLOT)

        if self.terminal:
            # A terminal generation applies nothing further; the rule is
            # sticky and never repaired by a later event.
            return TERMINAL, self.terminal_reason

        body_type = env["body"]["type"]
        if not self.active and body_type != "cc.generation.accept.v1":
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)

        return self._sequence(env, item)

    def _check_slot_binding(self, env: dict) -> str | None:
        body = env["body"]
        kind = body["type"]
        author = env["author"]
        if kind == "cc.generation.accept.v1":
            if author != self.high:
                return "AUTHOR_BINDING_INVALID"
            expected = acceptance_slot(self.generation_id, self.high)[2]
            if env["seq"] != 1:
                return "SLOT_BINDING_INVALID"
        elif kind == "cc.relationship.offer.v1":
            expected = offer_slot(self.generation_id, author)[2]
        elif kind in ("cc.grant.set.v1", "cc.grant.revoke.v1"):
            if body["publisher"] != author:
                return "AUTHOR_BINDING_INVALID"
            plan = body.get("resource_id") if body["scope"] == "PLAN_DETAIL" else None
            expected = grant_slot(self.generation_id, author, body["scope"], plan)[2]
        else:
            publisher = self.grant_publisher.get(body["grant_id"], author)
            if publisher != author:
                return "AUTHOR_BINDING_INVALID"
            expected = projection_slot(self.generation_id, author, body["grant_id"])[2]
        return None if env["object_id"] == expected else "SLOT_BINDING_INVALID"

    def _sequence(self, env: dict, item: dict) -> tuple[str, str | None]:
        slot = env["object_id"]
        seq = env["seq"]
        event_id = item["inner_event_id"]
        tail = self.slot_tail.get(slot, 0)

        existing = self.applied.get((slot, seq))
        if existing is not None:
            if existing["inner_event_id"] == event_id:
                return APPLIED, None  # byte-identical redelivery is a no-op
            return self._terminalise("EQUAL_SEQUENCE_CONFLICT")

        if seq == tail + 1:
            if seq > 1:
                predecessor = self.applied[(slot, seq - 1)]
                if env["previous_event_id"] != predecessor["inner_event_id"]:
                    return self._diagnostic("PREDECESSOR_INVALID", REJECT_NO_SLOT)
            return self._apply(env, item)

        if seq <= tail:
            # A late event at an occupied sequence with different bytes is an
            # equal-sequence conflict; the applied branch above covers the
            # identical case.
            return self._terminalise("EQUAL_SEQUENCE_CONFLICT")

        return self._hold(env, item, "HOLD_PREDECESSOR_MISSING", reconciling=True)

    def _hold(self, env: dict, item: dict, reason: str, reconciling: bool = False):
        raw_len = len(item["content"].encode("utf-8"))
        slot = env["object_id"]
        per_slot = [h for h in self.held if h["env"]["object_id"] == slot]
        if len(per_slot) + 1 > BOUNDS["held_events_per_slot"]:
            return self._terminalise("STORAGE_BOUND_EXCEEDED")
        if sum(h["bytes"] for h in per_slot) + raw_len > BOUNDS["held_bytes_per_slot"]:
            return self._terminalise("STORAGE_BOUND_EXCEEDED")
        if len(self.held) + 1 > BOUNDS["held_events_per_generation"]:
            return self._terminalise("STORAGE_BOUND_EXCEEDED")
        if self.held_bytes + raw_len > BOUNDS["held_bytes_per_generation"]:
            return self._terminalise("STORAGE_BOUND_EXCEEDED")
        self.held.append({"env": env, "item": item, "reason": reason, "bytes": raw_len})
        self.held_bytes += raw_len
        if reconciling:
            self.reconciling.add(slot)
        return HELD, reason

    def _apply(self, env: dict, item: dict) -> tuple[str, str | None]:
        body = env["body"]
        kind = body["type"]
        handler = {
            "cc.generation.accept.v1": self._apply_acceptance,
            "cc.relationship.offer.v1": self._apply_offer,
            "cc.grant.set.v1": self._apply_grant_set,
            "cc.grant.revoke.v1": self._apply_grant_revoke,
            "cc.projection.snapshot.v1": self._apply_projection,
            "cc.projection.delta.v1": self._apply_projection,
        }[kind]
        disposition, reason = handler(env, item)
        if disposition == APPLIED:
            self.applied[(env["object_id"], env["seq"])] = {
                "inner_event_id": item["inner_event_id"],
                "env": env,
            }
            self.slot_tail[env["object_id"]] = env["seq"]
            if env["object_id"] in self.reconciling and not any(
                h["env"]["object_id"] == env["object_id"] for h in self.held
            ):
                self.reconciling.discard(env["object_id"])
            self._release_held()
        return disposition, reason

    def _release_held(self) -> None:
        """Re-evaluate held events on every relevant arrival (wire §8.2).

        An event that is only re-held under a *different* missing dependency is
        not progress; without that rule the fixed point would never be reached.
        """
        if self._releasing:
            return
        self._releasing = True
        try:
            progress = True
            while progress and not self.terminal:
                progress = False
                for entry in list(self.held):
                    if entry not in self.held:
                        continue
                    env, item = entry["env"], entry["item"]
                    slot, seq = env["object_id"], env["seq"]
                    if seq != self.slot_tail.get(slot, 0) + 1:
                        continue
                    if seq > 1:
                        predecessor = self.applied.get((slot, seq - 1))
                        if not predecessor:
                            continue
                        if env["previous_event_id"] != predecessor["inner_event_id"]:
                            continue
                    self.held.remove(entry)
                    self.held_bytes -= entry["bytes"]
                    disposition, _ = self._apply(env, item)
                    if disposition != HELD:
                        progress = True
        finally:
            self._releasing = False

    def _apply_acceptance(self, env: dict, _item: dict) -> tuple[str, str | None]:
        self.acceptance_applied = True
        return APPLIED, None

    def _apply_offer(self, env: dict, item: dict) -> tuple[str, str | None]:
        author = env["author"]
        offer = env["body"]["offer"]
        record = {
            "offer": offer,
            "issued_at": env["issued_at"],
            "inner_event_id": item["inner_event_id"],
            "seq": env["seq"],
        }
        self.offer_chain.setdefault(author, []).append(record)
        if offer == "NONE":
            self.offer_heads[author] = record
            return self._terminalise("OFFER_NONE_RECEIVED")
        previous = self.offer_heads.get(author)
        self.offer_heads[author] = record
        if previous and LEVELS.index(offer) < LEVELS.index(previous["offer"]):
            self._retire_detail_grants()
        return APPLIED, None

    def _retire_detail_grants(self) -> None:
        for grant_id, grant in self.grants.items():
            if grant["scope"] in DETAIL_SCOPES and grant["state"] == "ACTIVE":
                grant["state"] = "RETIRED"
                self.retired_grants.add(grant_id)
                self.projections.pop(grant_id, None)

    def _head_by_event_id(self, endpoint: str, event_id: str) -> dict | None:
        for record in self.offer_chain.get(endpoint, []):
            if record["inner_event_id"] == event_id:
                return record
        return None

    def _apply_grant_set(self, env: dict, item: dict) -> tuple[str, str | None]:
        body = env["body"]
        grant_id = body["grant_id"]
        publisher = body["publisher"]

        owner = self.grant_publisher.get(grant_id)
        if owner is not None and owner != publisher:
            # wire §6.3: grant ids are unique generation-wide. Reusing the
            # other publisher's id is a conflict, never a shared slot.
            return self._terminalise("GRANT_ID_COLLISION")
        if owner == publisher and grant_id in self.grants:
            return self._diagnostic("BODY_SCHEMA_INVALID", REJECT_NO_SLOT)  # reused id

        low_head = self._head_by_event_id(self.low, body["offer_heads"][0])
        high_head = self._head_by_event_id(self.high, body["offer_heads"][1])
        # A reversed pair is a rejection, never a hold: both ids exist, they
        # are simply bound to the wrong endpoints (wire §6.3, endpoint order).
        reversed_pair = self._head_by_event_id(self.high, body["offer_heads"][0]) is not None or (
            self._head_by_event_id(self.low, body["offer_heads"][1]) is not None
        )
        if reversed_pair:
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if low_head is None or high_head is None:
            return self._hold(env, item, "HOLD_OFFER_HEAD_MISSING")

        referenced = min(
            (low_head["offer"], high_head["offer"]), key=LEVELS.index
        )
        minimum = SCOPE_MIN_LEVEL[body["scope"]]
        if LEVELS.index(referenced) < LEVELS.index(minimum):
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if LEVELS.index(self.effective_level()) < LEVELS.index(minimum):
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)

        if body["scope"] in DETAIL_SCOPES:
            expected = max(body["issued_at"], low_head["issued_at"], high_head["issued_at"])
            if body["not_before"] != expected:
                return self._diagnostic("BODY_SCHEMA_INVALID", REJECT_NO_SLOT)
            if self._downgrade_between(low_head, high_head, minimum):
                self.retired_grants.add(grant_id)
                self.grant_publisher[grant_id] = publisher
                self.grants[grant_id] = {
                    "scope": body["scope"],
                    "state": "RETIRED",
                    "publisher": publisher,
                }
                return APPLIED, None

        slot_count = sum(
            1
            for g in self.grants.values()
            if g["publisher"] == publisher and g["scope"] == "PLAN_DETAIL"
        )
        if body["scope"] == "PLAN_DETAIL" and slot_count >= BOUNDS["plan_detail_slots_per_publisher"]:
            return self._terminalise("STORAGE_BOUND_EXCEEDED")

        superseded = body.get("supersedes_grant_id")
        if superseded is not None:
            prior = self.grants.get(superseded)
            if prior is None or prior["publisher"] != publisher:
                return self._diagnostic("BODY_SCHEMA_INVALID", REJECT_NO_SLOT)
            prior["state"] = "SUPERSEDED"
            self.projections.pop(superseded, None)

        self.grant_publisher[grant_id] = publisher
        self.grants[grant_id] = {
            "scope": body["scope"],
            "fields": list(body["fields"]),
            "state": "ACTIVE",
            "publisher": publisher,
            "grant_event_id": item["inner_event_id"],
            "not_before": body.get("not_before"),
            "expires_at": body["expires_at"],
            "resource_id": body.get("resource_id"),
        }
        return APPLIED, None

    def _downgrade_between(self, low_head: dict, high_head: dict, minimum: str) -> bool:
        """wire §6.3: walk every gapless successor of both referenced heads."""
        for endpoint, referenced in ((self.low, low_head), (self.high, high_head)):
            chain = self.offer_chain.get(endpoint, [])
            other = high_head if endpoint == self.low else low_head
            for record in chain:
                if record["seq"] <= referenced["seq"]:
                    continue
                pair = min((record["offer"], other["offer"]), key=LEVELS.index)
                if LEVELS.index(pair) < LEVELS.index(minimum):
                    return True
        return False

    def _apply_grant_revoke(self, env: dict, _item: dict) -> tuple[str, str | None]:
        body = env["body"]
        grant = self.grants.get(body["grant_id"])
        if grant is None or grant["publisher"] != body["publisher"]:
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if grant["state"] != "ACTIVE":
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        grant["state"] = "REVOKED"
        self.projections.pop(body["grant_id"], None)
        return APPLIED, None

    def _apply_projection(self, env: dict, item: dict) -> tuple[str, str | None]:
        body = env["body"]
        grant = self.grants.get(body["grant_id"])
        if grant is None:
            return self._hold(env, item, "HOLD_GRANT_MISSING")
        if grant["publisher"] != env["author"]:
            return self._diagnostic("AUTHOR_BINDING_INVALID", REJECT_NO_SLOT)
        if grant.get("grant_event_id") != body["grant_event_id"]:
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if grant["state"] != "ACTIVE":
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if body["scope"] != grant["scope"] or list(body["fields"]) != list(grant["fields"]):
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if body.get("resource_id") != grant.get("resource_id"):
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)
        if grant["scope"] in DETAIL_SCOPES and body["prospective_from"] != grant["not_before"]:
            return self._diagnostic("DEPENDENCY_INVALID", REJECT_NO_SLOT)

        state = self.projections.setdefault(body["grant_id"], {"sessions": {}, "plan": None,
                                                               "summary": None})
        if body["type"] == "cc.projection.snapshot.v1":
            payload = body["payload"]
            if grant["scope"] == "LOGBOOK_DETAIL":
                state["sessions"] = {row["session_uuid"]: row for row in payload["sessions"]}
            elif grant["scope"] == "PLAN_DETAIL":
                state["plan"] = payload["plan"]
            else:
                state["summary"] = payload
            return APPLIED, None

        projected = dict(state["sessions"])
        plan = state["plan"]
        summary = state["summary"]
        for op in body["ops"]:
            if op["op"] == "UPSERT_SESSION":
                projected[op["row"]["session_uuid"]] = op["row"]
            elif op["op"] == "DELETE_SESSION":
                projected.pop(op["session_uuid"], None)
            elif op["op"] == "UPSERT_PLAN":
                plan = op["plan"]
            elif op["op"] == "DELETE_PLAN":
                plan = None
            elif op["op"] == "SET_SUMMARY":
                summary = op["row"]
        # wire §7.6: the resulting state, not only the delta, must satisfy
        # every collection bound. Overflow rejects the whole delta.
        if len(projected) > BOUNDS["sessions_per_logbook_grant"]:
            return self._diagnostic("BODY_SCHEMA_INVALID", REJECT_NO_SLOT)
        state["sessions"] = projected
        state["plan"] = plan
        state["summary"] = summary
        return APPLIED, None

    # -- out-of-band signals --------------------------------------------
    def invalidate(self, mapping: dict) -> tuple[str, str]:
        """wire §8.4 — apply an adapter invalidation to this generation.

        The *decision* is not restated here: it is
        :func:`evaluate_invalidation_signal`, so the reducer path and the
        adapter-gate path cannot drift into two different readings of one rule.
        This method only carries the state change the reducer owns.
        """
        return self._terminalise(evaluate_invalidation_signal(mapping)["reason"])


# --------------------------------------------------------------------------
# Generation state machine (FEAT-062 §3.3, wire §8.4a, §9.4 – §9.7)
# --------------------------------------------------------------------------

GENERATION_STATES = ("PROVISIONING", "ACTIVE", "TERMINAL")

#: The closed event vocabulary of the generation lifecycle.
STATE_MACHINE_EVENTS = (
    "activation_gate_passed",
    "convergence_unresolved",
    "fresh_read_ok",
    "return_to_provisioning",
    "rollback_authority_commit",
    "rollback_unrelated_commit",
    "sticky_deny_recorded",
    "terminal_fault",
    "replay_after_terminal",
)

#: ``(state, event) -> next state``. Every pair absent from this table is a
#: **forbidden** transition: it is refused, it changes nothing, and it is never
#: silently reinterpreted as a neighbouring one. The two that matter most are
#: ``ACTIVE -> PROVISIONING`` (a generation cannot un-become active, wire §8.4a)
#: and every escape from ``TERMINAL`` (terminal is absorbing, wire §9.7).
ALLOWED_TRANSITIONS = {
    ("PROVISIONING", "activation_gate_passed"): "ACTIVE",
    ("PROVISIONING", "convergence_unresolved"): "PROVISIONING",
    ("PROVISIONING", "fresh_read_ok"): "PROVISIONING",
    ("PROVISIONING", "return_to_provisioning"): "PROVISIONING",
    ("PROVISIONING", "rollback_authority_commit"): "PROVISIONING",
    ("PROVISIONING", "rollback_unrelated_commit"): "PROVISIONING",
    ("PROVISIONING", "sticky_deny_recorded"): "PROVISIONING",
    ("PROVISIONING", "terminal_fault"): "TERMINAL",
    ("ACTIVE", "convergence_unresolved"): "ACTIVE",
    ("ACTIVE", "fresh_read_ok"): "ACTIVE",
    ("ACTIVE", "rollback_authority_commit"): "TERMINAL",
    ("ACTIVE", "rollback_unrelated_commit"): "ACTIVE",
    ("ACTIVE", "sticky_deny_recorded"): "ACTIVE",
    ("ACTIVE", "terminal_fault"): "TERMINAL",
    ("TERMINAL", "replay_after_terminal"): "TERMINAL",
    ("TERMINAL", "sticky_deny_recorded"): "TERMINAL",
}

#: Events that leave the generation holding: send and apply stay blocked until a
#: fresh session-consistent read succeeds (wire §8.4a, §9.5).
HOLDING_EVENTS = ("convergence_unresolved", "rollback_authority_commit",
                  "rollback_unrelated_commit")


class GenerationStateMachine:
    """The generation lifecycle as an explicit machine with forbidden edges.

    Refusing a transition is a first-class outcome: the caller learns that the
    edge does not exist rather than receiving a nearest-neighbour guess.
    """

    def __init__(self, state: str, sticky_deny=()):
        if state not in GENERATION_STATES:
            raise SpecViolation(f"unknown generation state {state!r}")
        self.state = state
        self.terminal_reason: str | None = None
        self.sticky_deny: set[str] = set(sticky_deny)
        self.holding = False

    def apply(self, event: str, detail: str | None = None) -> tuple[bool, str | None]:
        if event not in STATE_MACHINE_EVENTS:
            raise SpecViolation(f"unknown lifecycle event {event!r}")
        key = (self.state, event)
        if key not in ALLOWED_TRANSITIONS:
            return False, "FORBIDDEN_TRANSITION"
        before = set(self.sticky_deny)
        if event == "sticky_deny_recorded":
            if detail is None:
                raise SpecViolation("a sticky deny names what it denies")
            self.sticky_deny.add(detail)
        self.state = ALLOWED_TRANSITIONS[key]
        if event in HOLDING_EVENTS:
            self.holding = True
        elif event in ("fresh_read_ok", "activation_gate_passed"):
            self.holding = False
        if self.state == TERMINAL and self.terminal_reason is None:
            if detail is not None and detail in TERMINAL_REASONS:
                self.terminal_reason = detail
            elif event == "rollback_authority_commit":
                self.terminal_reason = "COMMIT_ROLLBACK_AFTER_ACTIVATION"
            elif detail is not None:
                raise SpecViolation(f"terminal reason not in the closed set: {detail}")
        if not before <= self.sticky_deny:  # pragma: no cover - defensive
            raise SpecViolation("a sticky deny was lost")
        return True, None

    def send_allowed(self) -> bool:
        return self.state == "ACTIVE" and not self.holding

    def apply_allowed(self) -> bool:
        # Send and apply are gated together everywhere in this contract.
        return self.send_allowed()


# --------------------------------------------------------------------------
# Storage bounds, restore and outbox helpers (wire §8.2, §8.7, §10)
# --------------------------------------------------------------------------

#: Fixture key -> bound name, for one attempted insert.
BOUND_KEYS = (
    ("held_events", "held_events_per_slot"),
    ("held_bytes_slot", "held_bytes_per_slot"),
    ("held_events_generation", "held_events_per_generation"),
    ("held_bytes", "held_bytes_per_generation"),
    ("held_bytes_global", "held_bytes_global"),
    ("plan_detail_slots", "plan_detail_slots_per_publisher"),
    ("grant_slots", "grant_slots_per_publisher"),
)


def evaluate_bound_insert(state: dict) -> tuple[str, str | None]:
    """One attempted insert against the hard bounds of wire §8.2 / §7.6.

    ``insert_kind`` selects the rule: a ``held`` or ``accepted`` insert that
    would exceed a storage bound terminalises the offending generation *before*
    the insert, while a ``delta_result`` overflow rejects the whole delta and
    occupies no sequence.
    """
    kind = state.get("insert_kind", "held")
    if kind == "delta_result":
        if state.get("sessions", 0) > BOUNDS["sessions_per_logbook_grant"]:
            return REJECT_NO_SLOT, "BODY_SCHEMA_INVALID"
        return APPLIED, None
    if kind not in ("held", "accepted"):
        raise SpecViolation(f"unknown insert kind {kind!r}")
    for key, bound in BOUND_KEYS:
        if state.get(key, 0) > BOUNDS[bound]:
            return TERMINAL, "STORAGE_BOUND_EXCEEDED"
    if state.get("sessions", 0) > BOUNDS["sessions_per_logbook_grant"]:
        return TERMINAL, "STORAGE_BOUND_EXCEEDED"
    return (HELD, None) if kind == "held" else (APPLIED, None)


def bound_overflow_policy() -> dict:
    """wire §8.2: what overflow does and, above all, what it never does."""
    return {
        "terminalises_before_insert": True,
        "evicts": False,
        "prunes_tombstone": False,
        "prunes_safety_prefix": False,
        "other_generations_affected": False,
        "partial_application": False,
    }


def evaluate_restore(state: dict) -> dict:
    """wire §10: restore is preserve-or-NULL, inactive and read-only.

    Two independent NULL rules gate eligibility, and both fail closed. A
    ``NULL`` ``session_uuid``/``plan_uuid`` makes its row ineligible because a
    minted id would silently address a different remote row. An unset or
    cross-brand ``product_size_id`` makes the *affected* item ineligible for the
    same reason and is never defaulted to a plausible value; items that carry no
    product-size dimension at all are untouched by it, which is why the caller
    states ``requires_product_size`` rather than it being inferred from a NULL.
    """
    new_device = bool(state.get("restored_on_new_device"))
    session = state.get("session_uuid")
    product_size = state.get("product_size_id")
    product_size_ok = product_size is not None or not state.get("requires_product_size")
    eligible = session is not None and product_size_ok
    return {
        "outbox_drains": 0,
        "sends": 0,
        "applies": 0,
        "mints_identifier": False,
        "session_uuid": session,
        "plan_uuid": state.get("plan_uuid"),
        "product_size_id": product_size,
        "defaults_product_size": False,
        "row_eligible": eligible,
        "tombstones_sticky": True,
        "sendable": not new_device and eligible,
        "read_only": new_device,
    }


def lease_recovery_event_id(row: dict) -> str:
    """wire §8.7: whatever §8.7b decides, recovery mints no second inner id."""
    if row["state"] != "IN_FLIGHT":
        raise SpecViolation("only an IN_FLIGHT row is recovered")
    return row["inner_event_id"]


def _recovery_source_id(row: dict) -> str | None:
    """The one source id this row already bound, if any (§8.7a).

    More than one distinct id is not a recovery input at all: §8.7a
    terminalises that receipt with ``RETRY_NOT_TRANSPORT_IDENTICAL`` long
    before any lease expires, so it is not representable here.
    """
    receipt = row.get("receipt")
    if receipt is None:
        return None
    bound = sorted(set(receipt.get("source_message_ids") or []))
    if len(bound) > 1:
        raise SpecViolation("a row bound to two distinct source ids is not recoverable")
    return bound[0] if bound else None


def evaluate_send_handover(state: dict) -> dict:
    """wire §8.7c — no transport call without a proven write-ahead commit.

    This is the **authorisation gate**, evaluated *before* the call. Its
    ``HANDOVER_AUTHORISED`` means only that the transport may now be called; it
    is never a statement about what the call then did. What the call did is
    §8.7d (:func:`evaluate_post_handover`), and the two must not be conflated:
    a refusal *here* means no call happened at all, while a refusal *there*
    happens with ``EXTERNAL_EFFECT_UNCLEAR`` already durable.

    A handover to the transport cannot be undone and cannot be rolled back with
    a local transaction; no local transaction spans it. So the order is fixed:
    the row's phase is committed to ``EXTERNAL_EFFECT_UNCLEAR`` **first**, and
    the transport may be called only once that commit is proven. A commit that
    failed, was never attempted, or whose result the caller cannot establish,
    authorises nothing — "unknown" is not "committed".

    The protocol therefore over-approximates external effect and never
    under-approximates it: it may end up saying *unclear* for a send that never
    happened, and it can never say *pre* for one that may have.
    """
    result = state.get("write_ahead_commit")
    if result not in WRITE_AHEAD_RESULTS:
        raise SpecViolation(f"unknown write-ahead result {result!r}")
    reasons = WRITE_AHEAD_REFUSAL_REASONS
    pre, unclear, _observed = EXTERNAL_EFFECT_PHASES
    if result == "committed":
        return {
            "outcome": HANDOVER_AUTHORISED,
            "reason": None,
            "transport_may_be_called": True,
            "phase_commit_proven": True,
            # The one durable fact that authorises the call is this phase.
            "durable_phase": unclear,
            "admissible_phases": [unclear],
            "claims_atomic_handover": False,
        }
    # Refusing the handover is not the same as knowing where the row now stands.
    # `not_attempted` and a *proven* failure leave it durably pre-effect. An
    # unproven commit may still have landed: the caller simply never learned,
    # so both phases are admissible and recovery follows whichever is on disk.
    # Nothing is written back to force one of them — that would be an invented
    # transition, and §8.7c does not have one.
    admissible = [pre, unclear] if result == "unproven" else [pre]
    return {
        "outcome": HANDOVER_REFUSED,
        "reason": reasons[result],
        "transport_may_be_called": False,
        "phase_commit_proven": False,
        "durable_phase": None if result == "unproven" else pre,
        "admissible_phases": admissible,
        "claims_atomic_handover": False,
    }


def evaluate_drain_step(state: dict) -> dict:
    """wire §8.7e — what a later drain attempt must satisfy before any I/O.

    Lease recovery hands nothing to the transport. It leaves a durable state and
    a named next step, and this is the contract of that step: every route to the
    network runs through the write-ahead gate of §8.7c, and the replay route
    additionally requires the *same* persisted MLS message under its one source
    id and a closed ``R6-G2-TRANSPORT-IDENTICAL-RETRY``.

    The replay route now carries a second, independent requirement. R6 proved
    the identical re-drive itself, so that route is no longer blocked merely by
    an unproven capability — but the pin performs it through
    ``resume_outbound_fanouts``, and R6 finding 1 shows that path emits no
    ``PublishedApplicationMessage``. The source-correlated receipt this route
    needs is therefore not obtainable at the pin, and closing
    ``R6-G2-TRANSPORT-IDENTICAL-RETRY`` on its own would still not make the
    route usable for CruxCoach. Both conditions gate ``reachable_now``, so
    flipping either one alone leaves it False.

    ``reachable_now`` is read from the gate registry; it is never written there.
    """
    step = state.get("drain_step")
    if step not in DRAIN_STEPS:
        raise SpecViolation(f"unknown drain step {step!r}")
    replay = step == "WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY"
    gate = "R6-G2-TRANSPORT-IDENTICAL-RETRY" if replay else None
    reachable = step != "NONE"
    gate_closed = gate is None or R6_GATES[gate]["status"] == "CLOSED"
    # The ordinary first-delivery route runs through publish_queue, which R6
    # shows does emit the typed receipt; only the replay route depends on resume.
    correlation = True if not replay else RESUME_EMITS_SOURCE_RECEIPT
    return {
        "transport_reachable": reachable,
        "requires_write_ahead_commit": reachable,
        "requires_persisted_identity": replay,
        "required_closed_gate": gate,
        "requires_source_correlated_receipt": replay,
        "source_correlation_available_at_pin": correlation,
        "reachable_now": reachable and gate_closed and correlation,
        # Recovery is a state change; the network is only ever reached later.
        "hands_to_transport_from_recovery": False,
    }


def evaluate_post_handover(state: dict) -> dict:
    """wire §8.7d — what an authorised transport call leaves behind.

    By the time the call is made, ``EXTERNAL_EFFECT_UNCLEAR`` is durable: that
    commit is what authorised it (§8.7c). So a refusal, an error or no answer at
    all cannot restore ``PRE_EXTERNAL_EFFECT``. Undoing the phase would be safe
    only if the adapter synchronously and durably established that no external
    effect whatsoever had begun, and that the write-back itself stayed
    fail-closed across a crash. No such capability is established at the pin and
    this oracle assumes none, so the row simply stays unclear and is left to
    §8.7b — visible, never automatically re-driven.

    Only durable typed-receipt evidence moves the row on to
    ``EXTERNAL_EFFECT_OBSERVED``. "The transport accepted it" is not that
    evidence: R6 finding 1 shows the pin has a real path — the resume path —
    that publishes successfully and returns no ``PublishedApplicationMessage``
    at all, so nothing binds the delivery to a source message id. §8.4 needs
    exactly one source id per inner event id, and a delivery that cannot be
    correlated can never satisfy it. That case is ``accepted_without_receipt``
    and it stays unclear, permanently and visibly, rather than being labelled
    sent on the strength of an acceptance CruxCoach cannot name.
    """
    result = state.get("transport_call_result")
    if result not in TRANSPORT_CALL_RESULTS:
        raise SpecViolation(f"unknown transport call result {result!r}")
    reasons = {
        "refused": "CALL_REFUSED_AFTER_AUTHORISATION",
        "error": "CALL_ERROR_AFTER_AUTHORISATION",
        "no_answer": "CALL_WITHOUT_ANSWER",
        "accepted_without_receipt": "ACCEPTED_WITHOUT_SOURCE_RECEIPT",
    }
    observed = result == "receipt_persisted"
    return {
        "outcome": POST_HANDOVER_OBSERVED if observed else POST_HANDOVER_UNCLEAR,
        "reason": None if observed else reasons[result],
        "durable_phase": "EXTERNAL_EFFECT_OBSERVED" if observed
                         else "EXTERNAL_EFFECT_UNCLEAR",
        # None of these is ever true once a call was authorised. A second first
        # delivery is exactly what §8.7a and §8.7b exist to prevent.
        "reverts_to_pre_external_effect": False,
        "automatic_retry": False,
        "requeues": False,
        "claims_synchronous_no_effect_proof": False,
    }


def evaluate_send_crash(state: dict) -> dict:
    """wire §8.7c — what a recovering worker can still establish after a crash.

    ``admissible_phases`` is what may be found on disk at that point. The
    guarantee the write-ahead order buys is exactly one implication:
    ``PRE_EXTERNAL_EFFECT`` is admissible only where no handover was ever
    authorised, so the automatic requeue of §8.7b stays safe.
    """
    point = state.get("crash_point")
    if point not in SEND_CRASH_POINTS:
        raise SpecViolation(f"unknown send crash point {point!r}")
    pre, unclear, observed = EXTERNAL_EFFECT_PHASES
    table = {
        # nothing committed, nothing called
        "before_write_ahead": ([pre], False, False, False),
        # the commit may or may not have landed; either way it was never proven,
        # so the transport was never called
        "during_write_ahead_commit": ([pre, unclear], False, False, False),
        # from durable state alone this is indistinguishable from a crash inside
        # the handover, so it is treated as if the send may have taken effect
        "after_commit_before_handover": ([unclear], True, True, False),
        "during_handover": ([unclear], True, True, False),
        "after_handover_before_receipt": ([unclear], True, True, False),
        "after_receipt_persisted": ([observed], True, True, True),
    }
    phases, authorised, possibly_called, receipt_bound = table[point]
    return {
        "admissible_phases": list(phases),
        "handover_authorised": authorised,
        "transport_possibly_called": possibly_called,
        "receipt_bound": receipt_bound,
        "pre_phase_admissible": pre in phases,
        "claims_atomic_handover": False,
    }


def replay_admission(state: dict) -> dict:
    """wire §8.7b/§8.7e — the conjunction that admits a transport-identical replay.

    R6 established that the pin **can** re-drive the identical transport
    message: a durable ``OutboundFanout`` plus ``resume_outbound_fanouts``
    republishes only the still-open endpoints under the same ``message.id`` and
    the same ciphertext bytes. That is a capability, and it satisfies none of
    the three conditions below on its own.

    FEAT-062 admits the replay only when all three hold together:

    1. ``persisted_identity`` — this row really carries a restart-durable,
       repeatable record of the identical transport message. A remembered id is
       knowledge, not a replayable message.
    2. ``product_gate_closed`` — ``R6-G2-TRANSPORT-IDENTICAL-RETRY`` is closed.
       The gate is a CruxCoach decision about relying on the capability, never a
       statement that the capability is missing.
    3. ``source_correlation_available`` — the replaying path yields a receipt
       that can be bound to one source message id. R6 finding 1 says the resume
       path does not: it emits no ``PublishedApplicationMessage``, so
       ``finalize_published_app_message_source_retention`` never runs and §8.4's
       single-valued correlation can never be established for that delivery.

    Closing the gate alone is therefore not sufficient, and neither is any other
    single condition. Every unmet one is reported, so the result reads as the
    conjunction it is rather than as a queue of excuses.
    """
    persisted = bool(state["persisted_identity"])
    gate_closed = bool(state["product_gate_closed"])
    correlated = bool(state["source_correlation_available"])
    blocking = []
    if not persisted:
        blocking.append("REPLAY_NO_PERSISTED_IDENTITY")
    if not gate_closed:
        blocking.append("REPLAY_PRODUCT_GATE_OPEN")
    if not correlated:
        blocking.append("REPLAY_NO_SOURCE_CORRELATION")
    return {"admitted": not blocking, "blocking": blocking}


def evaluate_lease_recovery(row: dict, now: int, current_boot_id: str, *,
                            world: dict | None = None) -> dict:
    """wire §8.7b — what an expired lease may do, per external-effect phase.

    An expired lease says only that no worker holds the row. It says nothing
    about how far the send got, so the phase — not the lease — decides.

    Recovery never calls the transport. It writes durable state, releases the
    lease and names the ``next_transport_step`` a *later* drain attempt would
    have to satisfy through §8.7c/§8.7e. Per phase:

    * ``PRE_EXTERNAL_EFFECT``: nothing can have left the device, so the row goes
      back to ``QUEUED`` — from where a later drain attempt may drive the same
      inner event as a first delivery. No second inner id and no second source
      id exist to mint.
    * ``EXTERNAL_EFFECT_OBSERVED``: the transport message is durably evidenced,
      so the row is resolved *from that receipt*. Nothing is handed to the
      transport again and no claim is upgraded.
    * ``EXTERNAL_EFFECT_UNCLEAR``: the first delivery may already have had
      external effect. The only automatic route left is a later replay of the
      identical persisted MLS transport message under its one existing source
      id (§8.7a), which recovery may prepare but never perform. While
      ``R6-G2-TRANSPORT-IDENTICAL-RETRY`` is open, no transport may be treated
      as replayable at all, so the row becomes ``DELIVERY_UNCLEAR``: visible,
      never automatically sendable, and never silently re-sent.

    ``world`` states the two facts this decision reads from outside the row —
    whether the product gate is closed and whether the replaying path yields a
    source-correlated receipt — and defaults to the recorded ones. It exists so
    the two **counterfactual** rows §8.7b publishes ("were the gate closed…")
    are decided by *this* code under a stated hypothesis rather than written out
    by hand beside it. It is never an input to a fixture: no scenario passes it,
    and :func:`published_table_rows` is its only caller.
    """
    if row["state"] != "IN_FLIGHT":
        raise SpecViolation("only an IN_FLIGHT row is recovered")
    phase = row.get("external_effect_phase")
    if phase not in EXTERNAL_EFFECT_PHASES:
        raise SpecViolation(f"unknown external effect phase {phase!r}")
    inner = row["inner_event_id"]
    bound_source = _recovery_source_id(row)

    transport = row.get("persisted_transport")
    if transport is not None:
        if not transport.get("source_message_id"):
            raise SpecViolation("a persisted transport without its source id is not a record")
        if bound_source is not None and transport["source_message_id"] != bound_source:
            raise SpecViolation("a replay under a second distinct source id is not a retry")
    # Representability is checked before the lease is, so a contradictory row is
    # never waved through just because its lease still happens to hold.
    if phase == "PRE_EXTERNAL_EFFECT" and (bound_source is not None or transport is not None):
        raise SpecViolation("a pre-external-effect row cannot already carry a source id")
    # A record that is not restart-durable *and* repeatable is not a record at
    # all; only the id it names is knowledge, and knowledge is not a retry.
    replayable = bool(
        transport
        and transport.get("restart_durable")
        and transport.get("replayable")
    )
    known_source = bound_source
    if known_source is None and transport is not None:
        known_source = transport["source_message_id"]

    def outcome(name, reason, state, automatic, step, identical, source):
        if name not in LEASE_RECOVERY_OUTCOMES:  # pragma: no cover - defensive
            raise SpecViolation(f"unknown recovery outcome {name!r}")
        if reason is not None and reason not in LEASE_RECOVERY_REASONS:  # pragma: no cover
            raise SpecViolation(f"unknown recovery reason {reason!r}")
        if step not in DRAIN_STEPS:  # pragma: no cover - defensive
            raise SpecViolation(f"unknown drain step {step!r}")
        return {
            "outcome": name,
            "reason": reason,
            "resulting_state": state,
            "label": OUTBOX_LABELS[state],
            # `automatic` is the whole point of §8.7b: may the drain loop act on
            # this row without an explicit user decision? It is a statement
            # about the durable state change only — never about network I/O.
            "automatic": automatic,
            # Recovery writes durable state and releases the lease. It never
            # calls the transport: reaching the network always goes through the
            # write-ahead gate of §8.7c, in a later drain attempt (§8.7e).
            "hands_to_transport": False,
            "releases_lease": name != LEASE_HELD,
            # What *this* recovery leaves for the worker that ran it. `NONE`
            # under LEASE_HELD means this worker touches nothing — the holder
            # continues under its own §8.7c gate — not that the row is dead.
            "next_transport_step": step,
            "transport_identical": identical,
            "source_message_id": source,
            # Nothing here ever mints a second identity of either kind.
            "binds_second_source_id": False,
            "user_visible": OUTBOX_LABELS[state] is not None,
            "inner_event_id": inner,
        }

    if not lease_is_recoverable(row, now, current_boot_id):
        # The holder still owns it; no other worker may advance or requeue it.
        return outcome(LEASE_HELD, None, "IN_FLIGHT", False, "NONE", False, known_source)

    if phase == "PRE_EXTERNAL_EFFECT":
        if row.get("handover_authorised"):
            # §8.7c: the committed phase *is* the authorisation, so a correct
            # writer cannot produce this pair. Durable evidence that a handover
            # was authorised outweighs a phase that still reads pre-effect, and
            # recovery fails closed instead of driving the event a second time.
            return outcome(RECOVERY_BLOCKED, "RECOVERY_WRITE_AHEAD_INCONSISTENT",
                           "DELIVERY_UNCLEAR", False, "NONE", False, known_source)
        return outcome(RECOVER_QUEUED, None, "QUEUED", True, "WRITE_AHEAD_FIRST_DELIVERY",
                       False, None)

    if phase == "EXTERNAL_EFFECT_OBSERVED":
        if bound_source is None:
            # "Observed" without a durable receipt is exactly the unclear case.
            return outcome(RECOVERY_BLOCKED, "RECOVERY_EVIDENCE_INCOMPLETE",
                           "DELIVERY_UNCLEAR", False, "NONE", False, known_source)
        return outcome(RESOLVE_FROM_RECEIPT, None, receipt_queue_state(row["receipt"]),
                       True, "NONE", False, bound_source)

    # EXTERNAL_EFFECT_UNCLEAR — the replay is admitted only by the full
    # conjunction of §8.7b/§8.7e, never by any single condition. R6 showed the
    # pin can perform the re-drive; that is not one of the conditions.
    facts = world or {}
    admission = replay_admission({
        "persisted_identity": replayable,
        "product_gate_closed": facts.get(
            "product_gate_closed",
            R6_GATES["R6-G2-TRANSPORT-IDENTICAL-RETRY"]["status"] == "CLOSED"),
        "source_correlation_available": facts.get(
            "source_correlation_available", RESUME_EMITS_SOURCE_RECEIPT),
    })
    if not admission["admitted"]:
        # Reported in the order of REPLAY_ADMISSION_REASONS, so the row's label
        # names the condition nearest to the row itself first: no record to
        # replay, then the product gate, then the correlation the pin cannot
        # supply. All of them are blocking; only one can be shown.
        reason = recovery_reason_for_blocking(admission["blocking"])
        return outcome(RECOVERY_BLOCKED, reason,
                       "DELIVERY_UNCLEAR", False, "NONE", False, known_source)
    return outcome(RECOVER_TRANSPORT_IDENTICAL, None, "QUEUED", True,
                   "WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY", True,
                   transport["source_message_id"])


def visible_label_footer() -> str:
    """wire §8.7: every visible transport label carries this line too."""
    return NOT_PEER_CONFIRMED


def receipt_queue_state(receipt: dict) -> str:
    """wire §8.7: the queue state a durable typed receipt actually earns."""
    canonical = receipt.get("canonical_at") is not None
    accepted = list(receipt.get("accepted_relays") or [])
    if canonical and accepted:
        return "MDK_CANONICAL"
    if receipt.get("source_message_ids"):
        return "SENT_LOCALLY"
    return "QUEUED"


# --------------------------------------------------------------------------
# R6 adapter-evidence gates made executable (wire §8.5a, §8.7a, §11.1)
# --------------------------------------------------------------------------


def evaluate_outbound_receipt(state: dict) -> tuple[str, str | None]:
    """wire §8.7a — one inner event has at most one distinct source id.

    The removed R5 requeue path showed why the rule has to sit on the receipt:
    re-queueing the payload mints a second MLS transport id for the same inner
    id and destroys the single-valued correlation §8.4 depends on.

    R6 has since shown that the pin *can* re-drive the identical transport
    message (criterion 1, see ``R6_GATES``), which retires R5's claim that no
    such capability exists — and changes nothing here. This function is about
    what a receipt may bind, not about what the transport can do: a second
    distinct source id is terminal whether or not an identical re-drive is
    available, and R6's own finding 1 means a resumed delivery may bind no
    source id at all.
    """
    source_ids = list(state.get("source_message_ids") or [])
    if len(set(source_ids)) != len(source_ids):
        # The same transport message observed twice is one id, not two.
        source_ids = sorted(set(source_ids))
    retry = state.get("retry_kind", "none")
    if retry not in ("none", "transport_identical", "new_transport_id"):
        raise SpecViolation(f"unknown retry kind {retry!r}")
    if retry == "new_transport_id":
        return TERMINAL, "RETRY_NOT_TRANSPORT_IDENTICAL"
    if len(set(source_ids)) > 1:
        return TERMINAL, "RETRY_NOT_TRANSPORT_IDENTICAL"
    if state.get("inner_event_id") is None:
        raise SpecViolation("a receipt without an inner event id is not representable")
    return APPLIED, None


def evaluate_ingress_recovery(state: dict) -> tuple[str, str | None]:
    """wire §8.5a — a lagged live subscription is caught up by durable replay.

    ``RecvError::Lagged`` on the private broadcast is expected, not exceptional.
    Nothing may be concluded from live delivery alone (R5 criterion B).

    Epoch advances are not a retention mechanism: wire §8.5 requires that
    neither pruning nor more than five epoch advances remove an unacknowledged
    item. The count itself therefore never decides anything here — what decides
    is whether the item is still there before its host ACK. An unacknowledged
    item that is gone is the forbidden loss and fails closed; the same absence
    after the ACK is ordinary, because the CruxCoach transaction has committed
    and the idempotency row of §8.5 owns the outcome from then on.
    """
    if state.get("pruned_before_ack"):
        return TERMINAL, "PROJECTION_AUTHORITY_INVALID"
    if not state.get("unacked_item_present", True) and not state.get("host_acked"):
        return TERMINAL, "PROJECTION_AUTHORITY_INVALID"
    if not state.get("live_lagged"):
        return APPLIED, None
    if state.get("durable_replay_available") and state.get("replay_gap_closed"):
        return APPLIED, None
    return HELD, "HOLD_REPLAY_INCOMPLETE"


def evaluate_projection_authority(state: dict) -> tuple[str, str | None]:
    """wire §8.5a — the MDK raw app store is never the CruxCoach authority.

    R5 criterion E proved a real retention sweep deletes expired raw custom
    rows while a later survivor stays. That PASS is exactly what makes the raw
    store unusable as long-lived state, so the durable CruxCoach projection is
    mandatory and its absence is fail-closed, never best-effort.
    """
    authority = state.get("authority")
    if authority not in ("mdk_raw", "cruxcoach_durable"):
        raise SpecViolation(f"unknown projection authority {authority!r}")
    if authority == "mdk_raw":
        return TERMINAL, "PROJECTION_AUTHORITY_INVALID"
    if state.get("raw_pruned") and not state.get("host_acked"):
        return TERMINAL, "PROJECTION_AUTHORITY_INVALID"
    return APPLIED, None


def evaluate_invalidation_signal(state: dict) -> dict:
    """wire §8.4 with FEAT-062 §5 gate 5 — is an invalidation signal usable?

    Every invalidation of a stored kind-1220 event is terminal; that is not in
    question here and no branch below softens it. What differs is whether the
    signal was usable at all, and the two failures are kept apart:

    * **the mapping** — zero or multiple matched inner ids is a local
      `SOURCE_MAPPING_MISSING` / `SOURCE_MAPPING_AMBIGUOUS` fault. It never
      guesses an inner id, and it dominates: an unusable mapping is decided
      before the reason is even looked at.
    * **the reason** — the adapter reason must be one of the closed four and
      must survive a restart. At the pin the invalidated boolean is persisted
      while the reason is emitted in memory after the canonical commit, so a
      non-durable reason is the *expected* observation, not an exotic one.
      §5 gate 5 says what follows: the gate is reported **FAIL** rather than
      softened, and the durable record keeps no reason it cannot stand behind.

    No branch ever claims exactly-once invalidation delivery — §5 gate 5
    forbids that outright while the reason is not durable, and §11.2 forbids
    the wider claim regardless.
    """
    # FEAT-062 R8 (A2) — the NULL case: the payload's branch was demonstrably
    # rolled back and **no** payload-level signal arrived at all. R6 and R5 only
    # ever planned for "the reason is not durable"; R7 could not reach the state
    # that decides this, and R8 reached it and observed exactly this shape on
    # the pin's direct fork-recovery seam. Absence is not evidence that the
    # payload is still canonical, so it is never read as "nothing to withdraw":
    # the generation terminalises from the commit-level rollback it did see, and
    # §5 gate 5 is reported FAIL rather than silently passing for lack of input.
    if not state.get("signal_present", True):
        if not state.get("branch_rolled_back"):
            raise SpecViolation(
                "an absent invalidation signal is only decidable against an "
                "observed branch rollback"
            )
        return {
            "outcome": TERMINAL,
            "reason": "COMMIT_ROLLBACK_AFTER_ACTIVATION",
            "adapter_reason_recorded": None,
            "reason_usable": False,
            "gate_reported_fail": True,
            "claims_exactly_once_invalidation": False,
        }
    matches = list(state.get("matched_inner_event_ids") or [])
    reason = state.get("adapter_reason")
    durable = bool(state.get("reason_restart_durable"))
    usable_reason = reason in ADAPTER_INVALIDATION_REASONS and durable
    # §8.4 maps exactly *one* source message id to exactly one inner id. A
    # signal that names no usable source id maps nothing, whatever it claims to
    # have matched, so it fails closed on the mapping rather than on the reason.
    if not _is_hex32(state.get("source_message_id")):
        local = "SOURCE_MAPPING_MISSING"
    elif len(matches) == 0:
        local = "SOURCE_MAPPING_MISSING"
    elif len(matches) > 1:
        local = "SOURCE_MAPPING_AMBIGUOUS"
    else:
        local = "STORED_EVENT_INVALIDATED"
    if local not in TERMINAL_REASONS:  # pragma: no cover - defensive
        raise SpecViolation(f"unknown terminal reason {local!r}")
    return {
        "outcome": TERMINAL,
        "reason": local,
        # The reason the durable record may keep. A reason the adapter cannot
        # reproduce after a restart is not recorded as if it could be.
        "adapter_reason_recorded": reason if usable_reason else None,
        "reason_usable": usable_reason,
        # FEAT-062 §5 gate 5: report FAIL, never a softened claim.
        "gate_reported_fail": not usable_reason,
        "claims_exactly_once_invalidation": False,
    }


def evaluate_exactly_once_claim(state: dict) -> tuple[str, str | None]:
    """wire §11.2 — which exactly-once claim v1 is allowed to make.

    Permission to claim, not a report of what the pin does. R6 observed both
    exactly-once live emission and a raw row count of 1 at host scope; neither
    observation widens this function's answer by one scope, because a claim
    made here would be about relays, networks and peers that no host-scope run
    ever touched. See :data:`EXACTLY_ONCE_SCOPES`.
    """
    scope = state.get("scope")
    if scope not in EXACTLY_ONCE_SCOPES:
        raise SpecViolation(f"unknown exactly-once scope {scope!r}")
    if EXACTLY_ONCE_SCOPES[scope]:
        return EXACTLY_ONCE_PERMITTED, None
    return EXACTLY_ONCE_FORBIDDEN, EXACTLY_ONCE_SCOPE_EXCEEDED


def clock_gate(clock: "TrustedClock") -> dict:
    """wire §8.8 — the fail-closed effects of ``TIME_UNTRUSTED``."""
    untrusted = bool(clock.untrusted)
    return {
        "grants_hidden": untrusted,
        "purges_due": untrusted,
        "permissive_send_blocked": untrusted,
        # Blocking a restrictive action would lower safety, so it never happens.
        "restrictive_send_blocked": False,
    }


def evaluate_retirement(cause: str) -> dict:
    """wire §10 and FEAT-062 §8 — what one retirement cause decides.

    Every cause hides immediately; they differ in whether the retirement is
    permanent, whether the whole generation goes terminal, and by when the purge
    is due. A new grant never revives old data and never extends a running
    deadline, so the answer depends on the cause alone.
    """
    if cause not in RETIREMENT_CAUSES:
        raise SpecViolation(f"unknown retirement cause {cause!r}")
    if cause == "EXPIRY":
        deadline_s, measured_from = POST_EXPIRY_PURGE_DEADLINE_S, "expiry"
    elif cause == "SUPERSEDING_SET":
        # Not a deadline at all: the purge is part of the same committed local
        # transition that hides the superseded grant.
        deadline_s, measured_from = 0, "same_local_transition"
    else:
        deadline_s, measured_from = PURGE_DEADLINE_S, "cause"
    return {
        # No cause leaves the retired data visible. This is asserted rather
        # than assumed because it is the one property every row shares.
        "hidden": True,
        "permanently_retired": cause == "LEVEL_DOWNGRADE",
        "generation_terminal": cause in ("BLOCK_OR_REMOVE", "DISBAND"),
        "purge_deadline_s": deadline_s,
        "purge_measured_from": measured_from,
    }


def retirement_facts(cause: str) -> dict:
    """The facts a published retirement row must state, by column group.

    ``local`` is what the immediate local effect column must say; ``deadline``
    is what the purge column must say. wire §10 prints both in one cell and
    FEAT-062 §8 splits them across two, so the groups are named rather than
    positional.
    """
    decided = evaluate_retirement(cause)
    local = ["hidden"]
    if decided["permanently_retired"]:
        local.append("permanently_retired")
    if decided["generation_terminal"]:
        local.append("generation_terminal")
    return {
        "key": cause,
        "local": local,
        "deadline": [_purge_fact(decided["purge_deadline_s"],
                                 decided["purge_measured_from"])],
    }


# --------------------------------------------------------------------------
# Product-level contract statements (FEAT-062 §2.2, §2.3, §3.3)
# --------------------------------------------------------------------------
#
# The wire contract is bound rule by rule and table by table. The *product*
# specification restates the same rules for a reader who never opens the annexes,
# and those restatements were bound by nothing: §2.2 could hand MLS to CruxCoach,
# §2.3 could name a different MDK pin than the one R6 ran against, and §3.3 —
# the one table a product reader consults to learn what terminalises — could
# report MDK quarantine as a repairable hold.


#: FEAT-062 §2.2 / wire §1 — what each side owns *exclusively*.
#:
#: Ownership is the boundary the whole design rests on: MDK owns MLS and the
#: transport, CruxCoach owns application semantics inside the canonical content
#: bytes. A phrase from one column appearing in the other is that boundary being
#: redrawn, which is why both directions are asserted.
OWNERSHIP_BOUNDARY = {
    "MARMOT_MDK": (
        "mls",
        "keypackages",
        "group id",
        "welcome",
        "admin policy",
        "epoch",
        "fork",
        "retained history",
        "invalidation",
        "relays",
        "cursor",
    ),
    "CRUXCOACH": (
        "relationship and grant semantics",
        "closed schemas",
        "semantic slot ids",
        "inbox",
        "outbox",
        "reducer",
        "purge deadlines",
        "jcs",
    ),
}

#: FEAT-062 §2.3 — the pinned build inputs, in printed order.
#:
#: The two SHAs are taken from :data:`R6_SOURCE` rather than restated, so the
#: table cannot come to name a pin the recorded spike never ran against. The
#: toolchain values are the spike's own gate-1 inputs; §5 gate 1 and criterion 1
#: restate them in prose, and `check_build_inputs` binds those restatements to
#: this tuple as well.
BUILD_INPUTS = (
    ("Marmot", R6_SOURCE["marmot_spec_pin"]),
    ("MDK", R6_SOURCE["mdk_pin"]),
    ("Android ABI", "arm64-v8a"),
    ("Android native API", "28"),
    ("NDK", "27.2.12479018"),
    ("JDK", "17"),
)

#: The one ABI this feature targets. Any other ABI token in the product spec is
#: a different build than the one gate 1 reproduces.
ANDROID_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

#: The API levels FEAT-062 may name: the gated one, and the historical host
#: baseline §12 records as *not* answering the API-28 question.
ANDROID_API_LEVELS = ("28", "26")

#: FEAT-062 §3.3 — the observations the product-level state table decides, in
#: printed order. Each one is answered by the evaluator that already owns it, so
#: the table cannot drift from the reducer, the topology gate or the rollback
#: rule it summarises.
STATE_DECISION_OBSERVATIONS = (
    "EXACT_PROFILE_ACTIVE",
    "UNRESOLVED_CONVERGENCE",
    "UNRESOLVED_COMMIT_ROLLBACK",
    "ACCEPTANCE_MISSING",
    "PROMOTION_CANNOT_COMPLETE",
    "AUTHORITY_ROLLBACK_AFTER_ACTIVE",
    "TOPOLOGY_INVALID",
    "STORAGE_BOUND_EXCEEDED",
    "LIFECYCLE_NOT_ACTIVE",
    "MDK_QUARANTINE_OR_UNRECOVERABLE",
)

#: What each state-decision fact must be *spelled* as in the published cell.
#: As with the retirement facts, this says what a row may not stop saying; the
#: rest of the cell stays free prose.
STATE_DECISION_FACT_SPELLINGS = {
    "active": ("active",),
    "send_apply_allowed": ("send/apply allowed",),
    "send_apply_held": ("hold new send/apply",),
    "not_terminal_by_that_fact_alone": ("not terminal by that fact alone",),
    "fresh_session_consistent_read": ("fresh session-consistent read",),
    "provisioning": ("provisioning",),
    "terminal": ("terminal",),
    "hide_all_data": ("hide all data", "hides all data"),
    "tombstones_sticky": ("tombstones stay sticky",),
    "user_visible_abort": ("user visibly aborts", "visible abort"),
    "explicit_fresh_attempt": ("fresh attempt",),
    "before_the_insert": ("before the insert",),
    "nothing_evicted": ("nothing evicted", "evicts nothing"),
}

#: Facts a row states beside its disposition, where the disposition word alone
#: does not carry the rule: send/apply being allowed or held, the fresh read a
#: rollback forces, and the abort route out of a promotion that cannot complete.
#: The sticky-tombstone and eviction-free facts are *not* here — they are
#: derived from `rollback_keeps_tombstones` and `bound_overflow_policy` in
#: :func:`state_decision_facts`, so relaxing either rule moves the table too.
_STATE_DECISION_EXTRA_FACTS = {
    "EXACT_PROFILE_ACTIVE": ("send_apply_allowed",),
    "UNRESOLVED_CONVERGENCE": ("not_terminal_by_that_fact_alone",),
    "UNRESOLVED_COMMIT_ROLLBACK": ("fresh_session_consistent_read",),
    "PROMOTION_CANNOT_COMPLETE": ("user_visible_abort", "explicit_fresh_attempt"),
}

#: Observations whose terminality hides every materialised row of the
#: generation. The bound-overflow row is deliberately not one of them: it
#: terminalises that generation *before the insert* and evicts nothing.
_STATE_DECISION_HIDES_ALL = (
    "AUTHORITY_ROLLBACK_AFTER_ACTIVE",
    "TOPOLOGY_INVALID",
    "LIFECYCLE_NOT_ACTIVE",
    "MDK_QUARANTINE_OR_UNRECOVERABLE",
)


#: Two decoded account identities in `endpoint_low < endpoint_high` byte order,
#: for deriving a §3.3 row. The values are irrelevant to every decision below
#: except their order, which `expected_admin_policy` sorts on.
_SAMPLE_LOW, _SAMPLE_HIGH, _SAMPLE_THIRD = "11" * 32, "22" * 32, "33" * 32


def _topology_sample(**overrides) -> dict:
    """One canonical session-consistent read, for deriving a §3.3 row."""
    state = {
        "phase": "ACTIVE",
        "endpoint_low": _SAMPLE_LOW,
        "endpoint_high": _SAMPLE_HIGH,
    }
    state.update(overrides)
    return state


def evaluate_state_decision(observation: str) -> tuple[str, str | None]:
    """FEAT-062 §3.3 — one product-level observation, decided by its owner.

    Nothing here restates a disposition: every row calls the evaluator that
    already decides it, so the product table and the reducer cannot disagree.
    """
    if observation not in STATE_DECISION_OBSERVATIONS:
        raise SpecViolation(f"unknown state observation {observation!r}")
    if observation == "EXACT_PROFILE_ACTIVE":
        return evaluate_topology(_topology_sample())
    if observation == "UNRESOLVED_CONVERGENCE":
        # `Merging` is the pin's unresolved-convergence lifecycle; §3.3 groups it
        # with `PendingPublish` and `Recovering`, which hold for the same reason.
        return evaluate_topology(_topology_sample(mdk_lifecycle="Merging"))
    if observation == "UNRESOLVED_COMMIT_ROLLBACK":
        return evaluate_commit_rollback({
            "event": "GroupStateInvalidated",
            "commit_class": "promotion",
            "generation_state": "PROVISIONING",
        })
    if observation == "ACCEPTANCE_MISSING":
        return evaluate_topology(_topology_sample(acceptance_applied=False))
    if observation == "PROMOTION_CANNOT_COMPLETE":
        # A promotion that cannot complete has no repair path: the user aborts
        # visibly and the generation ends, which is the machine's own
        # `PROVISIONING -> terminal_fault` edge rather than a second rule.
        machine = GenerationStateMachine("PROVISIONING")
        moved, refusal = machine.apply("terminal_fault")
        if not moved:  # pragma: no cover - defensive
            raise SpecViolation(f"the abort edge was refused: {refusal}")
        return machine.state, machine.terminal_reason
    if observation == "AUTHORITY_ROLLBACK_AFTER_ACTIVE":
        return evaluate_commit_rollback({
            "event": "GroupStateInvalidated",
            "commit_class": "promotion",
            "generation_state": "ACTIVE",
        })
    if observation == "TOPOLOGY_INVALID":
        return evaluate_topology(_topology_sample(
            leaf_accounts=[_SAMPLE_LOW, _SAMPLE_HIGH, _SAMPLE_THIRD]))
    if observation == "STORAGE_BOUND_EXCEEDED":
        return evaluate_bound_insert({
            "insert_kind": "held",
            "held_events": BOUNDS["held_events_per_slot"] + 1,
        })
    if observation == "LIFECYCLE_NOT_ACTIVE":
        return evaluate_topology(_topology_sample(signed_lifecycle="disbanded"))
    return evaluate_topology(_topology_sample(mdk_quarantined=True))


def state_decision_facts(observation: str) -> list[str]:
    """The facts FEAT-062 §3.3's result cell must state for one observation."""
    outcome, _reason = evaluate_state_decision(observation)
    facts: list[str] = []
    if outcome == "ACTIVE":
        facts.append("active")
    elif outcome == "HOLD":
        facts.append("send_apply_held")
    elif outcome == "PROVISIONING":
        facts.append("provisioning")
    elif outcome == TERMINAL:
        facts.append("terminal")
        if observation in _STATE_DECISION_HIDES_ALL:
            facts.append("hide_all_data")
    else:  # pragma: no cover - defensive
        raise SpecViolation(f"{observation} decided {outcome!r}")
    facts.extend(_STATE_DECISION_EXTRA_FACTS.get(observation, ()))
    if observation == "AUTHORITY_ROLLBACK_AFTER_ACTIVE" and rollback_keeps_tombstones({
            "event": "GroupStateInvalidated",
            "commit_class": "promotion",
            "generation_state": "ACTIVE"}):
        facts.append("tombstones_sticky")
    if observation == "STORAGE_BOUND_EXCEEDED":
        policy = bound_overflow_policy()
        if policy["terminalises_before_insert"]:
            facts.append("before_the_insert")
        if not policy["evicts"]:
            facts.append("nothing_evicted")
    # Printed order follows the cells themselves: the disposition first, then
    # what it implies. `hide_all_data` is already appended above.
    return sorted(set(facts), key=facts.index)


# --------------------------------------------------------------------------
# Published tables, decided in code
# --------------------------------------------------------------------------
#
# `social/README.md` says published tables are bound to the oracle "because a
# printed mapping nothing reads is decoration however normative it sounds", and
# names four of them. The rest of the bundle's normative tables were not bound,
# and the gap was not cosmetic: §8.7b's recovery table could be edited to requeue
# an unclear row, §8.7d's call-result table to call an uncorrelated acceptance an
# observed delivery, §8.7c's crash table to leave a pre-effect row after an
# authorised handover, §8.4a's rollback table to hold instead of terminalise
# after activation, and every fixture, oracle, registry and manifest check stayed
# green while the document told a reader the opposite of the rule.
#
# A table is bound by reading each row's *bound* columns and comparing them,
# in printed order, with rows this module computes from the same decision
# functions the scenarios drive. Two read modes exist:
#
# `TABLE_TOKENS` collects the backticked spans that belong to the column's closed
# vocabulary, so surrounding prose stays free while a vocabulary word cannot be
# dropped, added or swapped; `TABLE_TEXT` normalises the whole cell, for the
# columns whose value is not a vocabulary token at all.
#
# `TABLE_PHRASES` is the third mode, for the columns that state their rule in
# prose rather than in a vocabulary — wire §10's and FEAT-062 §8's retirement
# cells. The oracle decides a list of *facts*, each with the spellings a cell may
# use to state it, and the column must state every fact its row decides. It is
# deliberately one-sided: it catches a rule that stops being stated, not extra
# prose beside it, because these cells legitimately carry per-document wording
# ("queue revoke event", "no MDK removal attempt is needed") that no decision
# function should own.

TABLE_TOKENS = "tokens"
TABLE_TEXT = "text"
TABLE_PHRASES = "phrases"

#: The recovery vocabulary a §8.7b "Recovery"/"Outcome" cell may name.
_RECOVERY_VOCAB = frozenset(LEASE_RECOVERY_OUTCOMES) | LEASE_RECOVERY_REASONS
_STATE_VOCAB = frozenset(OUTBOX_STATES)
_PHASE_VOCAB = frozenset(EXTERNAL_EFFECT_PHASES)
_POST_VOCAB = frozenset(POST_HANDOVER_OUTCOMES) | POST_HANDOVER_REASONS
_TERMINAL_VOCAB = frozenset(TERMINAL_REASONS)
_DRAIN_VOCAB = frozenset(DRAIN_STEPS)
_REPLAY_REASON_VOCAB = frozenset(REPLAY_ADMISSION_REASONS)

#: How wire §8.7b's "Meaning" column must spell each phase definition. These are
#: the sentences the recovery rules rest on, not decoration: the requeue is safe
#: only because `PRE_EXTERNAL_EFFECT` means the send was never authorised.
PHASE_MEANING_SPELLINGS = {
    "not_authorised": ("has not been authorised",),
    "nothing_left_the_device": ("nothing can have left the device",),
    "authorised": ("was authorised",),
    "no_durable_evidence": ("no durable evidence",),
    "durably_evidenced": ("durably evidenced",),
}

#: How wire §8.7e's requirement column must spell each conjunct of a drain step.
#: Dropping one of the replay row's four is the edit that would turn a
#: four-condition bar into a shorter one a reader could believe is met.
DRAIN_REQUIREMENT_SPELLINGS = {
    "nothing_automatic": ("nothing",),
    "write_ahead_gate": ("§8.7c gate",),
    "persisted_identity": ("persisted mls message",),
    "closed_gate": ("r6-g2-transport-identical-retry",),
    "source_correlated_receipt": ("source-correlated receipt",),
}

#: How wire §8.7b's "State today" column must spell each condition's state. The
#: two that are unmet at the pin are what keeps the replay route shut, so what
#: this must catch is a cell *softened* towards met: "open" is absent from a
#: cell rewritten to "closed", and "unavailable" from one rewritten to
#: "available", so both softenings fail. The opposite pairing is looser —
#: "available" is a substring of "unavailable" — and is deliberately not relied
#: on, because a met state is only ever expected once the registry above says
#: so, which is itself asserted per gate.
REPLAY_CONDITION_STATE_SPELLINGS = {
    "row_dependent": ("row-dependent",),
    "gate_open": ("open",),
    "gate_closed": ("closed",),
    "correlation_unavailable": ("unavailable",),
    "correlation_available": ("available",),
}


def _sample_row(phase: str, *, receipt=None, transport=None, authorised=False) -> dict:
    """One canonical `IN_FLIGHT` outbox row, for deriving a published row."""
    return {
        "state": "IN_FLIGHT",
        "lease_owner": "worker-a",
        "lease_boot_id": "boot-1",
        "lease_until": 1786000900,
        "attempts": 1,
        "inner_event_id": "0" * 64,
        "external_effect_phase": phase,
        "handover_authorised": authorised,
        "receipt": receipt,
        "persisted_transport": transport,
    }


#: A durable typed receipt with its one bound source id, and a restart-durable
#: replayable transport record. Both are the shapes §8.7b's rows describe.
_RECEIPT = {
    "intent_id": "intent-published",
    "inner_event_id": "0" * 64,
    "source_message_ids": ["d" * 64],
    "accepted_relays": [],
    "canonical_at": None,
}
#: The same receipt with the concrete relay evidence §8.7 requires, so the
#: `MDK_CANONICAL` half of that row is earned rather than asserted.
_RECEIPT_CANONICAL = dict(_RECEIPT, accepted_relays=["wss://relay.example"],
                          canonical_at=1786001000)
_REPLAYABLE = {"restart_durable": True, "replayable": True, "source_message_id": "c" * 64}
_UNREPLAYABLE = {"restart_durable": False, "replayable": False, "source_message_id": "c" * 64}

#: The hypothesis each counterfactual §8.7b row states in words. Recording them
#: here is what keeps "closing the gate would be enough" from becoming true by
#: omission: the same code decides those rows, under the stated assumption.
_GATE_CLOSED = {"product_gate_closed": True, "source_correlation_available": False}
_GATE_CLOSED_AND_CORRELATED = {"product_gate_closed": True,
                               "source_correlation_available": True}


def _recovery_cells(row: dict, *, now: int = 1786001000, world: dict | None = None):
    """The (recovery, resulting state) token lists one §8.7b row prints."""
    result = evaluate_lease_recovery(row, now, "boot-1", world=world)
    recovery = [result["outcome"]] + ([result["reason"]] if result["reason"] else [])
    return recovery, [result["resulting_state"]]


def _lease_recovery_published_rows() -> list[dict]:
    """wire §8.7b and golden §8.9 — every recovery outcome, in printed order.

    The last two rows are the counterfactuals the contract publishes on purpose:
    a closed `R6-G2-TRANSPORT-IDENTICAL-RETRY` alone still lands on
    `RECOVERY_SOURCE_CORRELATION_UNAVAILABLE`, because the resume path emits no
    `PublishedApplicationMessage` (R6 finding 1). They are decided by the same
    function under an explicit hypothesis rather than asserted beside it.
    """
    live = _sample_row("EXTERNAL_EFFECT_UNCLEAR")
    return [
        {"key": "lease_live", **dict(zip(("recovery", "state"),
                                         _recovery_cells(live, now=1786000800)))},
        {"key": "pre_external_effect",
         **dict(zip(("recovery", "state"), _recovery_cells(_sample_row("PRE_EXTERNAL_EFFECT"))))},
        # Both documents print "`SENT_LOCALLY` or `MDK_CANONICAL`, exactly as
        # §8.7 earns it", so both are derived — from a receipt that names no
        # accepted relay and one that does. Deriving only the first would have
        # let the published row drop the earned one.
        {"key": "observed_with_receipt",
         "recovery": _recovery_cells(_sample_row("EXTERNAL_EFFECT_OBSERVED",
                                                 receipt=_RECEIPT))[0],
         "state": (_recovery_cells(_sample_row("EXTERNAL_EFFECT_OBSERVED",
                                               receipt=_RECEIPT))[1]
                   + _recovery_cells(_sample_row("EXTERNAL_EFFECT_OBSERVED",
                                                 receipt=_RECEIPT_CANONICAL))[1])},
        {"key": "observed_without_receipt",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_OBSERVED"))))},
        {"key": "unclear_no_transport",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_UNCLEAR"))))},
        {"key": "unclear_unreplayable_transport",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_UNCLEAR",
                                                transport=_UNREPLAYABLE))))},
        {"key": "unclear_replayable_gate_open",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_UNCLEAR",
                                                transport=_REPLAYABLE))))},
        {"key": "unclear_replayable_gate_closed_uncorrelated",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_UNCLEAR",
                                                transport=_REPLAYABLE),
                                    world=_GATE_CLOSED)))},
        {"key": "unclear_replayable_gate_closed_correlated",
         **dict(zip(("recovery", "state"),
                    _recovery_cells(_sample_row("EXTERNAL_EFFECT_UNCLEAR",
                                                transport=_REPLAYABLE),
                                    world=_GATE_CLOSED_AND_CORRELATED)))},
    ]


def _send_crash_published_rows() -> list[dict]:
    """wire §8.7c and golden §8.9 — every crash window of one send attempt."""
    rows = []
    for point in SEND_CRASH_POINTS:
        crash = evaluate_send_crash({"crash_point": point})
        recovery: list[str] = []
        for phase in crash["admissible_phases"]:
            result = evaluate_lease_recovery(
                _sample_row(phase,
                            receipt=_RECEIPT if crash["receipt_bound"] else None,
                            authorised=crash["handover_authorised"] and phase != "PRE_EXTERNAL_EFFECT"),
                1786001000, "boot-1")
            if result["outcome"] not in recovery:
                recovery.append(result["outcome"])
        rows.append({
            "key": point,
            "phases": list(crash["admissible_phases"]),
            "authorised": "yes" if crash["handover_authorised"] else "no",
            "recovery": recovery,
        })
    return rows


def _phase_meaning_published_rows() -> list[dict]:
    """wire §8.7b — what each external-effect phase *means*.

    The recovery table below is decided entirely by this column: reading
    `PRE_EXTERNAL_EFFECT` is what authorises the one automatic requeue, and the
    only thing that makes it safe is that the phase means the send was never
    authorised to start. A table that redefined it as "may have been authorised"
    would leave every recovery row, fixture and vocabulary check green while the
    contract told a reader the requeue rests on nothing.

    The `authorised` half is not restated here: it is read from the same crash
    evaluator §8.7c's table is built from, so the two cannot drift apart.
    """
    pre, unclear, observed = EXTERNAL_EFFECT_PHASES
    # The crash window that ends in each phase says whether a handover had been
    # authorised by then; that is exactly what the meaning column states.
    authorised = {
        evaluate_send_crash({"crash_point": point})["admissible_phases"][-1]:
            evaluate_send_crash({"crash_point": point})["handover_authorised"]
        for point in SEND_CRASH_POINTS
    }
    meanings = {
        pre: ["not_authorised", "nothing_left_the_device"],
        unclear: ["authorised", "no_durable_evidence"],
        observed: ["durably_evidenced"],
    }
    rows = []
    for phase in EXTERNAL_EFFECT_PHASES:
        facts = list(meanings[phase])
        # A phase whose crash window had no authorised handover must say so, and
        # one that did must not claim the opposite.
        if authorised.get(phase) is False and "not_authorised" not in facts:
            raise SpecViolation(f"{phase} is unauthorised but does not state it")
        rows.append({"key": phase, "phase": [phase], "meaning": facts})
    return rows


def _drain_facts(step: str) -> list[str]:
    """wire §8.7e — the conditions one drain step must satisfy, from the oracle."""
    req = evaluate_drain_step({"drain_step": step})
    if not req["transport_reachable"]:
        return ["nothing_automatic"]
    facts = []
    if req["requires_write_ahead_commit"]:
        facts.append("write_ahead_gate")
    if req["requires_persisted_identity"]:
        facts.append("persisted_identity")
    if req["required_closed_gate"]:
        facts.append("closed_gate")
    if req["requires_source_correlated_receipt"]:
        facts.append("source_correlated_receipt")
    return facts


def _drain_step_published_rows() -> list[dict]:
    """wire §8.7e — what a recovered row still needs before anything reaches the network.

    The replay row is the one that matters: it is the only route by which an
    already-authorised send could ever leave the device again, and it is barred
    by a conjunction. Printing it with the closed gate or the source-correlated
    receipt dropped would describe a two-condition rule the oracle does not
    implement — and would be exactly the "closing R6-G2 is enough" reading
    §8.7b and §8.7e both exist to refuse. Every conjunct is read from
    :func:`evaluate_drain_step`, never restated.
    """
    return [
        {"key": "lease_held", "outcome": ["LEASE_HELD"],
         "step": ["NONE"], "requires": _drain_facts("NONE")},
        {"key": "resolved_or_blocked",
         "outcome": ["RESOLVE_FROM_RECEIPT", "RECOVERY_BLOCKED"],
         "step": ["NONE"], "requires": _drain_facts("NONE")},
        {"key": "recover_queued", "outcome": ["RECOVER_QUEUED"],
         "step": ["WRITE_AHEAD_FIRST_DELIVERY"],
         "requires": _drain_facts("WRITE_AHEAD_FIRST_DELIVERY")},
        {"key": "recover_transport_identical",
         "outcome": ["RECOVER_TRANSPORT_IDENTICAL"],
         "step": ["WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY"],
         "requires": _drain_facts("WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY")},
    ]


def _replay_condition_published_rows() -> list[dict]:
    """wire §8.7b — the replay conjunction, each condition with its state today.

    The three conditions are the whole of what keeps a post-external-effect row
    from being re-driven silently, and the "State today" column is where the
    contract tells a reader which of them are unmet *at the pin*. That column
    was decoration: printing the gate as closed and the correlation as
    available would have announced the replay route open, contradicting §11.1's
    registry and R6 finding 1, with the whole run green.

    Both halves are read from the registries the rest of the bundle is decided
    from — the gate's status from :data:`R6_GATES`, the correlation from
    :data:`RESUME_EMITS_SOURCE_RECEIPT` — so the table cannot claim a state the
    oracle does not hold, and closing the gate in the registry without editing
    the table fails just as loudly as the reverse.
    """
    gate_open = R6_GATES["R6-G2-TRANSPORT-IDENTICAL-RETRY"]["status"] == "OPEN"
    return [
        {"key": "persisted_identity", "reason": ["REPLAY_NO_PERSISTED_IDENTITY"],
         "state": ["row_dependent"]},
        {"key": "product_gate_closed", "reason": ["REPLAY_PRODUCT_GATE_OPEN"],
         "state": ["gate_open" if gate_open else "gate_closed"]},
        {"key": "source_correlated_receipt", "reason": ["REPLAY_NO_SOURCE_CORRELATION"],
         "state": ["correlation_available" if RESUME_EMITS_SOURCE_RECEIPT
                   else "correlation_unavailable"]},
    ]


def _post_handover_published_rows() -> list[dict]:
    """wire §8.7d — what each closed transport-call result leaves behind."""
    rows = []
    for result in TRANSPORT_CALL_RESULTS:
        out = evaluate_post_handover({"transport_call_result": result})
        rows.append({
            "key": result,
            "outcome": [out["outcome"]] + ([out["reason"]] if out["reason"] else []),
            "phase": [out["durable_phase"]],
        })
    return rows


def _rollback_published_rows() -> list[dict]:
    """golden §8.4 — every authority commit class, on both sides of activation.

    §8.4a gives the same signal two different outcomes and the generation state
    picks one, so a table that printed only one side would leave half of its own
    rule unstated.
    """
    def decide(commit_class: str, state: str):
        outcome, reason = evaluate_commit_rollback({
            "event": "GroupStateInvalidated",
            "commit_class": commit_class,
            "generation_state": state,
        })
        return {"word": outcome.lower(), "reason": [reason] if reason else []}

    rows = [dict(decide(commit_class, state), key=f"{commit_class}:{state}")
            for state in ("ACTIVE", "PROVISIONING")
            for commit_class in AUTHORITY_COMMITS]
    # `unrelated` is one printed row because it really is state-independent, and
    # that is asserted rather than assumed: were the two sides to diverge, the
    # table would owe a row per side exactly as the authority classes do.
    both = [decide("unrelated", state) for state in ("ACTIVE", "PROVISIONING")]
    if both[0] != both[1]:  # pragma: no cover - defensive
        raise SpecViolation("an unrelated rollback is not state-independent")
    rows.append(dict(both[0], key="unrelated:any"))
    return rows


def _supersession_published_rows() -> list[dict]:
    """wire §8.7 — the forbidden supersession pairs, in printed order."""
    pairs = (("restrictive", "permissive"), ("permissive", "restrictive"),
             ("permissive", "restrictive"), ("restrictive", "permissive"))
    return [{"key": f"{prior}->{nxt}#{index}",
             "allowed": "yes" if supersession_allowed(prior, nxt) else "never"}
            for index, (prior, nxt) in enumerate(pairs)]


def _scope_published_rows() -> list[dict]:
    """wire §6.3 — the minimum level and closed field tokens of every scope."""
    return [{"key": scope,
             "level": [SCOPE_MIN_LEVEL[scope]],
             "fields": list(SCOPE_FIELDS[scope])}
            for scope in SCOPE_CODES]


def _scope_code_published_rows() -> list[dict]:
    """wire §4.3 — the scope code recovered from the real grant-slot preimage.

    The number in this table is not a label: it is the `u8` byte that enters
    `SHA256(... || u8(scope_code) || resource_suffix)`, so two swapped rows are
    two different deterministic slot ids and a permanent interop break. It is
    read back out of the preimage the oracle actually builds rather than from
    `SCOPE_CODES`, so the printed value is bound to the derivation itself.
    """
    generation = "4026ec6e-92d1-8da9-9520-555f4c674a70"
    publisher = "a" * 64
    plan_uuid = "16147ec9-8d19-8886-a3b8-8b3011f59284"
    # label || 0x00 || uuid_bytes(generation) || publisher account bytes
    offset = len(b"cc-ps-grant-slot-v1") + 1 + 16 + 32
    rows = []
    for scope in SCOPE_CODES:
        preimage = bytes.fromhex(grant_slot(
            generation, publisher, scope,
            plan_uuid if scope == "PLAN_DETAIL" else None)[0])
        rows.append({"key": scope, "scope": [scope], "code": str(preimage[offset])})
    return rows


def _delta_operation_published_rows() -> list[dict]:
    """wire §7.6 — the closed operation set every scope's cell must print."""
    return [{"key": scope, "scope": [scope], "ops": list(SCOPE_DELTA_OPS[scope])}
            for scope in SCOPE_CODES]


def _carrier_field_order() -> tuple[str, ...]:
    """The field order `MarmotAppEvent::encode()` really emits (wire §2.1)."""
    return tuple(json.loads(marmot_app_event("0" * 64, "1" * 64, 0, "{}")))


#: How wire §2.1's "Required value" column must state each carrier field's rule.
#: The two machine values are rendered from the constants the oracle encodes
#: with — the carrier kind and the NIP-01 preimage array — so the table cannot
#: name a kind or a preimage the encoder does not use.
CARRIER_FIELD_SPELLINGS = {
    "nip01_id": ("nip-01",),
    "nip01_preimage": ("[0," + ",".join(
        f for f in _carrier_field_order() if f != "id") + "]",),
    "mls_sender": ("mls-authenticated sender",),
    "equals_issued_at": ("issued_at",),
    "carrier_kind": (str(CARRIER_KIND),),
    "exact_tag_array": ("exactly the two-tag array",),
    "envelope_jcs": ("jcs",),
    "envelope_type": ("cc.envelope.v1",),
}

#: What each carrier field's cell decides, keyed by the encoder's own field.
_CARRIER_FIELD_FACTS = {
    "id": ["nip01_id", "nip01_preimage"],
    "pubkey": ["mls_sender"],
    "created_at": ["equals_issued_at"],
    "kind": ["carrier_kind"],
    "tags": ["exact_tag_array"],
    "content": ["envelope_jcs", "envelope_type"],
}


def _carrier_field_published_rows() -> list[dict]:
    """wire §2.1 — the six carrier fields, in the pinned encoder order.

    This table is where a reader learns the carrier's identity: the kind that
    makes an event a FEAT-062 event at all, the id preimage, and that `content`
    is one JCS envelope. Its row set and order come from `marmot_app_event`, so
    a dropped, added or reordered field fails here as well as in the golden
    encoder vectors.
    """
    return [{"key": field, "field": [field], "facts": list(_CARRIER_FIELD_FACTS[field])}
            for field in _carrier_field_order()]


#: How wire §5's "Requirement" column must state each envelope member's rule.
ENVELOPE_FIELD_SPELLINGS = {
    "envelope_type": ("cc.envelope.v1",),
    "derived_generation": ("derived",),
    "sorted_endpoint_pair": ("endpoint_low_hex,endpoint_high_hex",),
    "author_is_authenticated_sender": ("authenticated sender",),
    "deterministic_slot": ("deterministic",),
    "contiguous_sequence": ("contiguous",),
    "forbidden_at_one": ("forbidden at 1",),
    "required_above_one": ("required above 1",),
    "equals_prior_event": ("prior event id",),
    "equals_created_at": ("inner created_at",),
    "never_ordering_authority": ("never an ordering authority",),
    "one_closed_body": ("exactly one closed body",),
}

#: wire §5 prints one row per envelope member, with `type` and `v` sharing the
#: first. The order is the document's; the *membership* is asserted against the
#: oracle's own closed schema below, so a member added to one and not the other
#: raises here rather than passing.
_ENVELOPE_PRINTED_ROWS = (
    (("type", "v"), ["envelope_type"]),
    (("generation_id",), ["derived_generation"]),
    (("endpoints",), ["sorted_endpoint_pair"]),
    (("author",), ["author_is_authenticated_sender"]),
    (("object_id",), ["deterministic_slot"]),
    (("seq",), ["contiguous_sequence"]),
    (("previous_event_id",), None),          # decided by running the validator
    (("issued_at",), ["equals_created_at", "never_ordering_authority"]),
    (("body",), ["one_closed_body"]),
)


def _predecessor_rule_facts() -> list[str]:
    """wire §5 — the linear-chain rule, decided by really running the validator.

    "Forbidden at 1; required above 1" is what makes a slot a chain at all, and
    §8.2's whole ordering rule rests on it. Probing both sequences in both
    shapes is what keeps this cell from being a sentence the run never reads.
    """
    generation = "4026ec6e-92d1-8da9-9520-555f4c674a70"
    low, high = "aa" * 32, "bb" * 32

    def envelope(seq: int, predecessor: bool) -> dict:
        env = {
            "author": low,
            "body": {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
            "endpoints": [low, high],
            "generation_id": generation,
            "issued_at": 1786000100,
            "object_id": generation,
            "seq": seq,
            "type": "cc.envelope.v1",
            "v": 1,
        }
        if predecessor:
            env["previous_event_id"] = "cc" * 32
        return env

    facts = []
    if (validate_envelope(envelope(1, True)) == "PREDECESSOR_INVALID"
            and validate_envelope(envelope(1, False)) is None):
        facts.append("forbidden_at_one")
    if (validate_envelope(envelope(2, False)) == "PREDECESSOR_INVALID"
            and validate_envelope(envelope(2, True)) is None):
        facts.append("required_above_one")
    facts.append("equals_prior_event")
    return facts


def _envelope_field_published_rows() -> list[dict]:
    """wire §5 — the closed member set of `cc.envelope.v1`, in printed order."""
    printed = [name for fields, _ in _ENVELOPE_PRINTED_ROWS for name in fields]
    if sorted(printed) != sorted(ENVELOPE_REQUIRED + ENVELOPE_OPTIONAL):
        raise SpecViolation(
            "wire §5's printed envelope rows are not the closed member set "
            f"{sorted(ENVELOPE_REQUIRED + ENVELOPE_OPTIONAL)}")
    return [{"key": "+".join(fields), "field": list(fields),
             "facts": _predecessor_rule_facts() if facts is None else list(facts)}
            for fields, facts in _ENVELOPE_PRINTED_ROWS]


def _bounds_published_rows(keys) -> list[dict]:
    """wire §8.2 / §3.2 — a printed limit is the one the oracle enforces."""
    return [{"key": key, "value": f"{BOUNDS[key]:,}"} for key in keys]


def _disposition_published_rows() -> list[dict]:
    """wire §8.6 and golden §9 — the five closed dispositions and what they store."""
    return [{"key": name, "stored": DISPOSITION_STORAGE[name]} for name in DISPOSITIONS]


#: wire §8.6 — what each disposition stores, as the first word of the cell.
#: A disposition whose printed "Stored" column drifts from this says the
#: opposite of the rule the corpus decides for every one of its cases.
DISPOSITION_STORAGE = {
    "DROP_BEFORE_PARSE": "nothing",
    "BOUNDED_DIGEST_ONLY": "one",
    "REJECT_NO_SLOT": "one",
    "HELD": "the",
    "TERMINAL": "the",
}


def _topology_phase_published_rows(low_label: str, high_label: str,
                                   committer_label: str) -> list[dict]:
    """wire §9.3 and golden §2.2 — the phase-dependent half of the profile.

    Component ids and placement are invariant; `0x8003` and `0x800c` are not,
    and they are what the activation gate reads. A table that printed the
    `ACTIVE` admin policy as the creator alone, or the `ACTIVE` lifecycle byte as
    `0x01`, would state a profile the oracle terminalises on.
    """
    def render(accounts, low, high, committer):
        names = {low: low_label, high: high_label}
        if committer is not None:
            names[committer] = committer_label
        return "[" + ",".join(names[a] for a in accounts) + "]"

    low, high, committer = "a" * 64, "b" * 64, "a" * 64
    rows = []
    for phase in ("PROVISIONING_SELF_ONLY", "PROVISIONING_INVITED", "ACTIVE", "DISBANDED"):
        actual = committer if phase == "DISBANDED" else None
        rows.append({
            "key": phase,
            "leaves": render(expected_leaf_accounts(phase, low, high, actual),
                             low, high, actual),
            "admin_policy": render(expected_admin_policy(phase, low, high, actual),
                                   low, high, actual),
            "lifecycle": expected_lifecycle_byte(phase),
        })
    return rows


def _collection_limit_published_rows() -> list[dict]:
    """wire §3.2 — every collection limit, as the range the contract prints."""
    return [
        {"key": "delta_ops", "limit": f"1–{BOUNDS['delta_ops']}"},
        {"key": "sessions_per_logbook_grant",
         "limit": f"0–{BOUNDS['sessions_per_logbook_grant']}"},
        {"key": "sends_per_session", "limit": f"0–{BOUNDS['sends_per_session']}"},
        {"key": "focus_areas", "limit": f"1–{BOUNDS['focus_areas']}"},
        {"key": "planned_sessions", "limit": f"0–{BOUNDS['planned_sessions']}"},
    ]


def _detail_boundary_published_rows() -> list[dict]:
    """golden §6.5 — floor900 and sendability, computed rather than tabulated."""
    prospective_from = 1786000200
    starts = (1786000199, 1786000250, 1786000499, 1786000500, 1786003345)
    rows = []
    for start in starts:
        floored = floor900(start)
        sendable = start >= prospective_from and floored >= prospective_from
        rows.append({"key": str(start), "floor900": str(floored),
                     "sendable": "yes" if sendable else "no"})
    return rows


#: The vocabulary a published trusted-time verdict cell may name: the state word
#: plus the four closed §8.8 distrust codes.
_CLOCK_VOCAB = frozenset({TIME_UNTRUSTED}) | TIME_UNTRUSTED_REASONS

#: One clock per published trusted-time row, as ``(persisted, observation)``.
#:
#: These are inputs, not outcomes. Each is fed to a real :class:`TrustedClock`
#: and the verdict is whatever that clock reaches, so a published row states the
#: model's answer rather than a transcription of it.
_CLOCK_ANCHOR = ("boot-1", 1000, 1786000100)


def _observed_clock(persisted: tuple, observation: tuple) -> "TrustedClock":
    clock = TrustedClock(*persisted)
    clock.observe(*observation)
    return clock


def _clock_verdict(clock: "TrustedClock") -> dict:
    """The (word, vocabulary) pair one published verdict cell must state."""
    if clock.untrusted:
        return {"word": TIME_UNTRUSTED.lower(),
                "verdict": [TIME_UNTRUSTED, clock.untrusted_reason]}
    return {"word": "trusted", "verdict": []}


def _distrust_condition_published_rows() -> list[dict]:
    """wire §8.8 — each distrust condition and the code it really reaches.

    The rows are in the order :meth:`TrustedClock.observe` tests them, which is
    the order the contract prints them in: a boot change is decided before a
    monotonic reset, and a rollback before a freeze. Deriving the code by running
    the clock is what stops two rows from being swapped — both codes would still
    be named by the section, and the closed-vocabulary rule alone would pass.
    """
    cases = (
        ("BOOT_CHANGED", ("boot-2", 1100, 1786000200)),
        ("MONOTONIC_RESET", ("boot-1", 10, 1786000200)),
        ("WALL_ROLLBACK", ("boot-1", 1200, 100)),
        # Exactly the tolerance, so the published "at least 900 s" is the
        # boundary this row stands on rather than a comfortable margin.
        ("WALL_FROZEN", ("boot-1", 1000 + TrustedClock.FREEZE_TOLERANCE_S, 1786000100)),
    )
    rows = []
    for key, observation in cases:
        clock = _observed_clock(_CLOCK_ANCHOR, observation)
        rows.append({"key": key, "code": [clock.untrusted_reason]})
    return rows


def _trusted_time_published_rows() -> list[dict]:
    """golden §8.9 — the six trusted-time cases, in printed order.

    The last row is the only exit §8.8 defines, and it is derived by actually
    re-anchoring the clock the reboot row left untrusted: printing it as trusted
    without running the exit would be the same decoration the rest of this
    section removes.
    """
    ordinary = ("boot-1", 1000, 1786000000)
    rows = [
        {"key": "ordinary_advance",
         **_clock_verdict(_observed_clock(ordinary, ("boot-1", 1100, 1786000100)))},
        {"key": "wall_rollback",
         **_clock_verdict(_observed_clock(_CLOCK_ANCHOR, ("boot-1", 1200, 100)))},
        {"key": "wall_frozen",
         **_clock_verdict(_observed_clock(
             _CLOCK_ANCHOR,
             ("boot-1", 1000 + TrustedClock.FREEZE_TOLERANCE_S + 1, 1786000100)))},
        {"key": "boot_changed",
         **_clock_verdict(_observed_clock(_CLOCK_ANCHOR, ("boot-2", 5, 1786000500)))},
        {"key": "monotonic_reset",
         **_clock_verdict(_observed_clock(_CLOCK_ANCHOR, ("boot-1", 10, 1786000200)))},
    ]
    rebooted = _observed_clock(_CLOCK_ANCHOR, ("boot-2", 5, 1786000500))
    rebooted.reinitialise("boot-2", 5, 1786000500)
    rows.append({"key": "trusted_reanchor", **_clock_verdict(rebooted)})
    return rows


def _retirement_published_rows() -> list[dict]:
    """wire §10 and FEAT-062 §8 — every retirement cause, in printed order."""
    return [retirement_facts(cause) for cause in RETIREMENT_CAUSES]


def _build_input_published_rows() -> list[dict]:
    """FEAT-062 §2.3 — the pinned build inputs, in printed order.

    The value cells are normalised the way :func:`_normalised_cell` reads them,
    so a row that names a different pin, ABI, API level, NDK or JDK than the
    spike ran against fails here rather than being read as a second opinion.
    """
    return [{"key": name, "value": value.lower()} for name, value in BUILD_INPUTS]


def _state_decision_published_rows() -> list[dict]:
    """FEAT-062 §3.3 — every observation's result, decided by its own evaluator."""
    return [{"key": observation, "facts": state_decision_facts(observation)}
            for observation in STATE_DECISION_OBSERVATIONS]


#: golden §8.11 — the lifecycle edges the document prints, in printed order.
#:
#: The fixtures already drive the complete three-state/nine-event matrix, so the
#: *machine* was covered. The published table was not: nothing read it, and it is
#: the only place a reader who never opens a fixture learns that `ACTIVE` never
#: returns to `PROVISIONING` and that `TERMINAL` is absorbing. Printing
#: ``ACTIVE, holding`` where the machine terminalises, or a resulting state where
#: the edge is refused, left every scenario, vocabulary and manifest check green
#: above a table stating the opposite rule.
STATE_MACHINE_PUBLISHED_EDGES = (
    ("PROVISIONING", "activation_gate_passed"),
    ("PROVISIONING", "convergence_unresolved"),
    ("PROVISIONING", "rollback_authority_commit"),
    ("ACTIVE", "return_to_provisioning"),
    ("ACTIVE", "rollback_unrelated_commit"),
    ("ACTIVE", "rollback_authority_commit"),
    ("ACTIVE", "terminal_fault"),
    ("TERMINAL", "activation_gate_passed"),
)

#: How golden §8.11's result column must spell the fact its row decides. A
#: refused edge states its refusal; a surviving one states whether the
#: generation is holding or may send and apply. Terminal rows state neither,
#: because `TERMINAL` is absorbing and holding is meaningless there.
STATE_MACHINE_RESULT_SPELLINGS = {
    "refused": ("refused",),
    "holding": ("holding",),
    "send_and_apply_allowed": ("send and apply allowed",),
}

_GENERATION_STATE_VOCAB = frozenset(GENERATION_STATES)
_STATE_MACHINE_EVENT_VOCAB = frozenset(STATE_MACHINE_EVENTS)
_STATE_MACHINE_RESULT_VOCAB = _GENERATION_STATE_VOCAB | frozenset(TERMINAL_REASONS)


def _state_machine_published_rows() -> list[dict]:
    """golden §8.11 — each printed edge, decided by really running the machine."""
    rows = []
    for start, event in STATE_MACHINE_PUBLISHED_EDGES:
        machine = GenerationStateMachine(start)
        accepted, _reason = machine.apply(event)
        if not accepted:
            result: list[str] = []
            facts = ["refused"]
        else:
            result = [machine.state]
            if machine.terminal_reason is not None:
                result.append(machine.terminal_reason)
            if machine.state == TERMINAL:
                facts = []
            elif machine.holding:
                facts = ["holding"]
            elif machine.send_allowed():
                facts = ["send_and_apply_allowed"]
            else:  # pragma: no cover - no printed edge reaches it today
                facts = []
        rows.append({"key": f"{start}:{event}", "start": [start], "event": [event],
                     "result": result, "facts": facts})
    return rows


#: Every published table this bundle binds, by id.
#:
#: ``columns`` maps the *bound* column index to ``(mode, row key, vocabulary)``.
#: An unbound column is free prose. The header is pinned in full, so a column
#: inserted, dropped or reordered fails here rather than silently shifting what
#: every index means.
PUBLISHED_TABLES = {
    "wire_lease_recovery": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Phase", "Recovery", "Row becomes"],
        "columns": {1: [(TABLE_TOKENS, "recovery", _RECOVERY_VOCAB)],
                    2: [(TABLE_TOKENS, "state", _STATE_VOCAB)]},
        "rows": _lease_recovery_published_rows,
    },
    "golden_lease_recovery": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Lease", "Phase", "Persisted evidence", "Outcome", "Row becomes"],
        "columns": {3: [(TABLE_TOKENS, "recovery", _RECOVERY_VOCAB)],
                    4: [(TABLE_TOKENS, "state", _STATE_VOCAB)]},
        # golden §8.9 prints the reachable rows only; the two counterfactual
        # gate-closed rows live in wire §8.7b, which is the normative table.
        "rows": lambda: [row for row in _lease_recovery_published_rows()
                         if not row["key"].startswith("unclear_replayable_gate_closed")],
    },
    "wire_send_crash": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Crash point", "Durable phase found", "Handover authorised",
                   "Recovery (§8.7b)"],
        "columns": {1: [(TABLE_TOKENS, "phases", _PHASE_VOCAB)],
                    2: [(TABLE_TEXT, "authorised", None)],
                    3: [(TABLE_TOKENS, "recovery", _RECOVERY_VOCAB)]},
        "rows": _send_crash_published_rows,
    },
    "golden_send_crash": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Crash point", "Durable phase found", "Handover authorised", "Recovery"],
        "columns": {1: [(TABLE_TOKENS, "phases", _PHASE_VOCAB)],
                    2: [(TABLE_TEXT, "authorised", None)],
                    3: [(TABLE_TOKENS, "recovery", _RECOVERY_VOCAB)]},
        "rows": _send_crash_published_rows,
    },
    "wire_phase_meanings": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Phase", "Meaning"],
        "columns": {0: [(TABLE_TOKENS, "phase", _PHASE_VOCAB)],
                    1: [(TABLE_PHRASES, "meaning", PHASE_MEANING_SPELLINGS)]},
        "rows": _phase_meaning_published_rows,
    },
    "wire_replay_conditions": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Condition", "Unmet reason", "Meaning", "State today"],
        "columns": {1: [(TABLE_TOKENS, "reason", _REPLAY_REASON_VOCAB)],
                    3: [(TABLE_PHRASES, "state", REPLAY_CONDITION_STATE_SPELLINGS)]},
        "rows": _replay_condition_published_rows,
    },
    "wire_drain_steps": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Recovery outcome", "Next step",
                   "What the drain attempt must satisfy"],
        "columns": {0: [(TABLE_TOKENS, "outcome", _RECOVERY_VOCAB)],
                    1: [(TABLE_TOKENS, "step", _DRAIN_VOCAB)],
                    2: [(TABLE_PHRASES, "requires", DRAIN_REQUIREMENT_SPELLINGS)]},
        "rows": _drain_step_published_rows,
    },
    "wire_post_handover": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Call result", "Outcome", "Durable phase"],
        "columns": {1: [(TABLE_TOKENS, "outcome", _POST_VOCAB)],
                    2: [(TABLE_TOKENS, "phase", _PHASE_VOCAB)]},
        "rows": _post_handover_published_rows,
    },
    "golden_commit_rollback": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Rolled-back commit class", "Generation state", "Outcome"],
        # The one cell decides two things: the disposition word a reader sees
        # and, where there is one, the closed §8.4 fault code.
        "columns": {2: [(TABLE_TEXT, "word", None),
                        (TABLE_TOKENS, "reason", _TERMINAL_VOCAB)]},
        "rows": _rollback_published_rows,
    },
    "wire_supersession": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Prior row", "Superseding row", "Allowed"],
        "columns": {2: [(TABLE_TEXT, "allowed", None)]},
        "rows": _supersession_published_rows,
    },
    "wire_carrier_fields": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Field", "Required value"],
        "columns": {0: [(TABLE_TOKENS, "field", frozenset(_carrier_field_order()))],
                    1: [(TABLE_PHRASES, "facts", CARRIER_FIELD_SPELLINGS)]},
        "rows": _carrier_field_published_rows,
    },
    "wire_envelope_fields": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        # §4.5 and §6.3 print the same header, so this one is scoped.
        "heading": "## 5. Envelope and common sequencing",
        "header": ["Field", "Requirement"],
        "columns": {0: [(TABLE_TOKENS, "field",
                         frozenset(ENVELOPE_REQUIRED + ENVELOPE_OPTIONAL))],
                    1: [(TABLE_PHRASES, "facts", ENVELOPE_FIELD_SPELLINGS)]},
        "rows": _envelope_field_published_rows,
    },
    "wire_scope_codes": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Scope", "Code"],
        "columns": {0: [(TABLE_TOKENS, "scope", frozenset(SCOPE_CODES))],
                    1: [(TABLE_TEXT, "code", None)]},
        "rows": _scope_code_published_rows,
    },
    "wire_delta_operations": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Scope", "Allowed operation"],
        "columns": {0: [(TABLE_TOKENS, "scope", frozenset(SCOPE_CODES))],
                    1: [(TABLE_TOKENS, "ops",
                         frozenset(op for ops in SCOPE_DELTA_OPS.values() for op in ops))]},
        "rows": _delta_operation_published_rows,
    },
    "wire_scope_levels": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Scope", "Minimum level", "Field tokens"],
        "columns": {1: [(TABLE_TOKENS, "level", frozenset(LEVELS))],
                    2: [(TABLE_TOKENS, "fields",
                         frozenset(t for tokens in SCOPE_FIELDS.values() for t in tokens))]},
        "rows": _scope_published_rows,
    },
    "wire_storage_bounds": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Bound", "Value"],
        "columns": {1: [(TABLE_TEXT, "value", None)]},
        "rows": lambda: _bounds_published_rows((
            "held_events_per_slot", "held_bytes_per_slot",
            "held_events_per_generation", "held_bytes_per_generation",
            "held_bytes_global", "plan_detail_slots_per_publisher",
            "grant_slots_per_publisher", "sessions_per_logbook_grant")),
    },
    "wire_collection_limits": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Collection", "Limit"],
        "columns": {1: [(TABLE_TEXT, "limit", None)]},
        "rows": _collection_limit_published_rows,
    },
    "golden_dispositions": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Disposition", "Meaning", "Reason vocabulary"],
        "columns": {},   # golden §9 states the meaning in prose of its own
        "rows": _disposition_published_rows,
    },
    "wire_dispositions": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Disposition", "Stored", "Slot"],
        "columns": {1: [(TABLE_TEXT, "stored", None)]},
        "rows": _disposition_published_rows,
    },
    "wire_topology_phases": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Phase", "Leaves", "`0x8003`", "`0x800c`"],
        "columns": {1: [(TABLE_TEXT, "leaves", None)],
                    2: [(TABLE_TEXT, "admin_policy", None)],
                    3: [(TABLE_TEXT, "lifecycle", None)]},
        "rows": lambda: _topology_phase_published_rows(
            "endpoint_low", "endpoint_high", "committer"),
    },
    "golden_topology_phases": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Phase", "Leaves", "`0x8003` admin policy", "`0x800c`", "Outcome"],
        "columns": {1: [(TABLE_TEXT, "leaves", None)],
                    2: [(TABLE_TEXT, "admin_policy", None)],
                    3: [(TABLE_TEXT, "lifecycle", None)]},
        "rows": lambda: _topology_phase_published_rows(
            "endpoint_low", "endpoint_high", "committer"),
    },
    "golden_detail_boundary": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Private unrounded start", "`floor900`", "Sendable", "Why"],
        "columns": {1: [(TABLE_TEXT, "floor900", None)],
                    2: [(TABLE_TEXT, "sendable", None)]},
        "rows": _detail_boundary_published_rows,
    },
    "wire_distrust_conditions": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Condition", "Code"],
        "columns": {1: [(TABLE_TOKENS, "code", TIME_UNTRUSTED_REASONS)]},
        "rows": _distrust_condition_published_rows,
    },
    "golden_trusted_time": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Case", "Persisted", "Observation", "Result"],
        # One cell decides two things: the trusted/untrusted word a reader sees
        # and, where there is one, the closed §8.8 distrust code.
        "columns": {3: [(TABLE_TEXT, "word", None),
                        (TABLE_TOKENS, "verdict", _CLOCK_VOCAB)]},
        "rows": _trusted_time_published_rows,
    },
    "wire_retirement": {
        "document": "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
        "header": ["Cause", "Required result"],
        # §10 states the local effect and the purge window in one cell.
        "columns": {1: [(TABLE_PHRASES, "local", RETIREMENT_FACT_SPELLINGS),
                        (TABLE_PHRASES, "deadline", RETIREMENT_FACT_SPELLINGS)]},
        "rows": _retirement_published_rows,
    },
    "feat_build_inputs": {
        "document": "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
        "header": ["Input", "Required value"],
        "columns": {1: [(TABLE_TEXT, "value", None)]},
        "rows": _build_input_published_rows,
    },
    "feat_state_decisions": {
        "document": "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
        "header": ["Observation", "Result"],
        "columns": {1: [(TABLE_PHRASES, "facts", STATE_DECISION_FACT_SPELLINGS)]},
        "rows": _state_decision_published_rows,
    },
    "golden_state_machine": {
        "document": "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
        "header": ["Start", "Event", "Result"],
        "columns": {0: [(TABLE_TOKENS, "start", _GENERATION_STATE_VOCAB)],
                    1: [(TABLE_TOKENS, "event", _STATE_MACHINE_EVENT_VOCAB)],
                    2: [(TABLE_TOKENS, "result", _STATE_MACHINE_RESULT_VOCAB),
                        (TABLE_PHRASES, "facts", STATE_MACHINE_RESULT_SPELLINGS)]},
        "rows": _state_machine_published_rows,
    },
    "feat_retirement": {
        "document": "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
        "header": ["Trigger", "Immediate local effect", "Deadline / network effect"],
        # FEAT-062 §8 splits the same rule across two columns, which is why the
        # fact groups are named rather than positional.
        "columns": {1: [(TABLE_PHRASES, "local", RETIREMENT_FACT_SPELLINGS)],
                    2: [(TABLE_PHRASES, "deadline", RETIREMENT_FACT_SPELLINGS)]},
        "rows": _retirement_published_rows,
    },
}


def published_table_rows(table_id: str) -> list[dict]:
    """The rows a published table must print, decided by the oracle."""
    entry = PUBLISHED_TABLES.get(table_id)
    if entry is None:
        raise SpecViolation(f"unknown published table {table_id!r}")
    return entry["rows"]()


#: The disposition each gate's fail-closed rule actually reaches, decided by
#: calling that gate's own evaluator rather than by restating its prose.
#:
#: Four of the eight have locally decidable behaviour (golden §8.10); the other
#: four are native/on-device obligations with nothing local to decide, so they
#: are absent here rather than given an invented outcome.
#:
#: Both documents print a "fail-closed while open" cell per gate, and until now
#: only the cell's *length* was checked. Softening `R6-G1-DURABLE-PROJECTION`'s
#: from "terminalises that generation" to "holds that generation" left every
#: fixture green while §5a told a reader the opposite of §8.5a — the rule that
#: exists because R5 proved a retention sweep really deletes raw rows.
def _fail_closed_dispositions() -> dict:
    def word(outcome: str) -> str:
        return {TERMINAL: "terminal", HELD: "hold"}[outcome]

    return {
        "R6-G1-DURABLE-PROJECTION": word(
            evaluate_projection_authority({"authority": "mdk_raw"})[0]),
        "R6-G2-TRANSPORT-IDENTICAL-RETRY": word(
            evaluate_outbound_receipt({
                "inner_event_id": "0" * 64,
                "source_message_ids": ["a" * 64, "b" * 64],
            })[0]),
        "R6-G3-BROADCAST-LAG-REPLAY": word(
            evaluate_ingress_recovery({
                "live_lagged": True,
                "durable_replay_available": True,
                "replay_gap_closed": False,
                "pruned_before_ack": False,
            })[0]),
        "R6-G5-CUSTOM-REORG": word(
            evaluate_invalidation_signal({
                "source_message_id": "1" * 64,
                "matched_inner_event_ids": ["2" * 64],
                "adapter_reason": "LOSING_BRANCH",
                "reason_restart_durable": True,
            })["outcome"]),
    }


GATE_FAIL_CLOSED_DISPOSITIONS = _fail_closed_dispositions()

#: The word that contradicts each disposition. A cell saying a generation
#: "holds" where the oracle terminalises is the softening this pair catches.
FAIL_CLOSED_OPPOSITE = {"terminal": "hold", "hold": "terminal"}
