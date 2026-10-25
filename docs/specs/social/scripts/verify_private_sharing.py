#!/usr/bin/env python3
"""FEAT-062 reference harness — the checked-in executable spec oracle run.

    python3 docs/specs/social/scripts/verify_private_sharing.py
    python3 docs/specs/social/scripts/verify_private_sharing.py --write-manifest

What it does, in one pass over the checked-in bundle:

1. re-derives every positive golden value from the closed fixture inputs and
   compares it against both `fixtures/positive-vectors.json` and the literal
   values printed in `PRIVATE-SHARING-GOLDEN-VECTORS.md`;
2. runs every negative case in `fixtures/negative-corpus.json` through the
   oracle and asserts the expected disposition — one of `DROP_BEFORE_PARSE`,
   `BOUNDED_DIGEST_ONLY`, `REJECT_NO_SLOT`, `HELD`, `TERMINAL` — together with
   a reason code from that disposition's closed set;
3. runs the reducer, topology-phase, generation state-machine, commit-rollback,
   clock, outbox, send-handover, bound, restore and adapter-runtime-gate
   scenarios in `fixtures/reducer-scenarios.json`, including the write-ahead
   order in front of the non-atomic transport handover, every crash window of
   one send attempt, and — kept apart from that authorisation gate — every
   result of the authorised transport call itself;
4. checks that the mandatory-coverage table in the golden vectors and the
   corpus areas agree in both directions, that the R6 gate registry is
   named identically by every document that carries it, and that every
   published §8.7c refusal table — and the running prose around it — states
   the same durable-phase split the send-handover fixtures drive;
4a. requires every closed reason code the oracle can decide to be named by the
   wire contract **and** decided by a fixture expectation; requires every closed
   value of the generation state machine, the rollback seams and the
   exactly-once claim to be exercised; and binds the published mapping tables —
   wire §11.2, §8.7b, §8.7c, the threat model's per-gate obligations, and the
   `FEAT-062` row of `docs/specs/INDEX.md` together with the spec's own front
   matter — to the oracle;
5. checks every relative Markdown link and anchor across the six bundle files;
6. mutates **every** expectation field of every fixture, one at a time, and
   requires the scoped check to turn red — an expectation that survives its own
   mutation is not an assertion and fails this run;
7. recomputes the bundle and evidence manifests and compares them against
   `fixtures/MANIFEST.json`.

**Honesty statement.** This is a *spec oracle*: a second, independent Python
statement of the wire contract. It executes no Kotlin, no Rust, no MDK, no
Marmot and nothing on a device. A green run says the specification is
internally consistent and its fixtures reproduce. It says nothing about the
eight adapter gates in FEAT-062 §5 or the eight R6 gates in wire §11.1, all of
which remain open, and it does not lift the No-Go.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote

sys.path.insert(0, str(Path(__file__).resolve().parent))

import private_sharing_oracle as o  # noqa: E402
from negative_corpus import AREAS  # noqa: E402

HERE = Path(__file__).resolve().parent
SOCIAL = HERE.parent
SPECS = SOCIAL.parent
FIXTURES = SOCIAL / "fixtures"

#: The normative bundle, in the fixed order the manifest rule uses.
BUNDLE_FILES = (
    "docs/specs/INDEX.md",
    "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
    "docs/specs/social/README.md",
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
    "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
    "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
)

#: The checked-in evidence, in the fixed order the manifest rule uses.
EVIDENCE_FILES = (
    "docs/specs/social/fixtures/negative-corpus.json",
    "docs/specs/social/fixtures/positive-vectors.json",
    "docs/specs/social/fixtures/reducer-scenarios.json",
    "docs/specs/social/scripts/build_fixtures.py",
    "docs/specs/social/scripts/negative_corpus.py",
    "docs/specs/social/scripts/private_sharing_oracle.py",
    "docs/specs/social/scripts/reducer_scenarios.py",
    "docs/specs/social/scripts/verify_private_sharing.py",
)

REPO_ROOT = SPECS.parent.parent


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.checks = 0

    def check(self, condition: bool, message: str) -> bool:
        self.checks += 1
        if not condition:
            self.errors.append(message)
        return condition

    def equal(self, actual, expected, message: str) -> bool:
        return self.check(actual == expected, f"{message}: got {actual!r}, want {expected!r}")


# --------------------------------------------------------------------------
# 1. Positive vectors
# --------------------------------------------------------------------------


def rebuild() -> "object":
    from build_fixtures import Builder

    builder = Builder()
    builder.build_derivations()
    builder.build_events()
    return builder


def check_positive(report: Report, builder, stored: dict) -> None:
    fresh = builder.positive()
    for section in ("inputs", "derivations", "bootstrap", "events", "board_config_id",
                    "hold_preimage_hex", "hold_fingerprint", "problem_hash"):
        report.equal(
            json.dumps(fresh[section], sort_keys=True),
            json.dumps(stored[section], sort_keys=True),
            f"positive fixture section '{section}' is stale",
        )

    # Independent re-derivation of every printed triple.
    for item in stored["derivations"]:
        preimage = bytes.fromhex(item["preimage_hex"])
        digest = hashlib.sha256(preimage).digest()
        report.equal(digest.hex(), item["sha256"], f"{item['name']} digest")
        if item["kind"] == "uuid8":
            report.equal(o.uuid8(digest), item["value"], f"{item['name']} uuid8")
            report.check(o.uuid_version(item["value"]) == 8, f"{item['name']} version nibble")
            report.check(o.uuid_variant_ok(item["value"]), f"{item['name']} variant")
        else:
            report.equal(digest.hex(), item["value"], f"{item['name']} sha256 value")

    # Grant ids really are UUIDv7 whose timestamp bits match issued_at.
    grant_ids = stored["inputs"]["grant_ids"]
    issued = {
        grant_ids["LOGBOOK_SUMMARY"]: "grant_summary_set",
        grant_ids["LOGBOOK_SUMMARY_narrowed"]: "grant_summary_narrowing",
        grant_ids["LOGBOOK_DETAIL"]: "grant_detail_backdated",
        grant_ids["PLAN_DETAIL"]: "grant_plan_detail",
    }
    for value, event_name in issued.items():
        report.check(o.uuid_version(value) == 7, f"{value} is not a UUIDv7")
        report.check(o.uuid_variant_ok(value), f"{value} variant")
        body = json.loads(stored["events"][event_name]["content"])["body"]
        report.equal(
            o.uuid7_timestamp_ms(value), body["issued_at"] * 1000,
            f"{value} timestamp bits do not match issued_at",
        )

    # Every event: canonical JCS, size, digest, NIP-01 id, full encoder bytes.
    for name, event in stored["events"].items():
        raw = event["content"].encode("utf-8")
        parsed, reason = o.parse_canonical_content(raw)
        report.check(reason is None, f"{name} content is not canonical: {reason}")
        report.equal(len(raw), event["content_size"], f"{name} content_size")
        report.equal(o.sha256_hex(raw), event["content_sha256"], f"{name} content_sha256")
        report.equal(
            o.nip01_event_id(event["author"], event["issued_at"], o.CARRIER_KIND,
                             o.CARRIER_TAGS, event["content"]),
            event["inner_event_id"],
            f"{name} inner_event_id",
        )
        report.check(len(raw) <= o.CONTENT_MAX_BYTES, f"{name} exceeds the content cap")
        if "full_event" in event:
            full = o.marmot_app_event(
                event["inner_event_id"], event["author"], event["issued_at"], event["content"]
            )
            report.equal(full, event["full_event"], f"{name} full encoder bytes")
            report.equal(len(full.encode("utf-8")), event["full_event_size"],
                         f"{name} full_event_size")
            report.equal(o.sha256_hex(full.encode("utf-8")), event["full_event_sha256"],
                         f"{name} full_event_sha256")
            decoded = json.loads(full)
            report.equal(list(decoded.keys()),
                         ["id", "pubkey", "created_at", "kind", "tags", "content"],
                         f"{name} pinned struct order")
            report.check("sig" not in decoded, f"{name} carries a sig")

    boot = stored["bootstrap"]
    description = boot["description"].encode("utf-8")
    report.equal(len(description), boot["description_size"], "bootstrap description_size")
    report.equal(o.sha256_hex(description), boot["description_sha256"],
                 "bootstrap description_sha256")
    report.equal(boot["name"], o.BOOTSTRAP_NAME, "bootstrap marker name")
    report.equal(boot["name_size"], len(o.BOOTSTRAP_NAME.encode("utf-8")), "bootstrap name_size")
    component = o.bootstrap_component_0x8001(boot["name"], description)
    report.equal(component.hex(), boot["component_data_hex"], "0x8001 component data")
    report.equal(len(component), boot["component_data_size"], "0x8001 component size")
    report.equal(o.sha256_hex(component), boot["component_data_sha256"], "0x8001 component digest")
    report.equal(o.quic_varint(len(boot["name"].encode())).hex(), "19", "name QUIC varint")
    report.equal(o.quic_varint(len(description)).hex(), "4112", "description QUIC varint")

    report.equal(stored["board_config_id"], o.board_config_id(stored["inputs"]["board"]),
                 "board_config_id")
    frames = [[tuple(h) for h in frame] for frame in stored["inputs"]["hold_frames"]]
    pre, fingerprint = o.hold_fingerprint(stored["inputs"]["board"]["ecosystem"], frames)
    report.equal(stored["hold_preimage_hex"], pre, "hold_preimage_hex")
    report.equal(stored["hold_fingerprint"], fingerprint, "hold_fingerprint")
    report.equal(
        stored["problem_hash"],
        o.problem_hash({
            "board": stored["inputs"]["board"],
            "hold_fingerprint": stored["hold_fingerprint"],
            "is_mirror": False,
            "provider_problem_id": stored["inputs"]["provider_problem_id"],
            "type": "cc.problem",
            "v": 1,
        }),
        "problem_hash",
    )


# --------------------------------------------------------------------------
# 2. Negative corpus
# --------------------------------------------------------------------------


def preloaded_reducer(builder, preload: str) -> o.Reducer:
    """Build the reducer state a negative case is evaluated against."""
    reducer = o.Reducer(builder.gen1, builder.low, builder.high)
    if preload == "PROVISIONING":
        return reducer
    if preload == "PROVISIONING_ACCEPTED":
        # Still `PROVISIONING` — the promotion has not been applied — but the one
        # body that may flow there already occupies its slot. This is the only
        # state in which a *second* acceptance can be evaluated at all.
        deliver(reducer, builder, "acceptance")
        return reducer
    reducer.active = True
    reducer.acceptance_applied = True
    plan = {
        "ACTIVE_NO_HEADS": [],
        "ACTIVE_HEADS_ONLY": ["A1", "A2", "B1"],
        "ACTIVE_A1_A2": ["A1", "A2"],
        "ACTIVE_WITH_A1": ["A1"],
        "ACTIVE_ACQUAINTANCE_HEADS": ["A1", "B1"],
        "ACTIVE_SUMMARY_GRANT": ["A1", "A2", "B1", "grant_summary_set"],
        "ACTIVE_SUMMARY_CHAIN": ["A1", "A2", "B1", "grant_summary_set",
                                 "grant_summary_narrowing"],
        "ACTIVE_WITH_DETAIL_GRANT": ["A1", "A2", "B1", "grant_detail_backdated",
                                     "grant_summary_set", "grant_plan_detail"],
        "ACTIVE_DETAIL_SNAPSHOT": ["A1", "A2", "B1", "grant_detail_backdated",
                                   "projection_detail_snapshot"],
        "ACTIVE_DETAIL_SNAPSHOT_FULL": ["A1", "A2", "B1", "grant_detail_backdated",
                                        "projection_detail_snapshot"],
        "ACTIVE_PLAN_GRANT": ["A1", "A2", "B1", "grant_plan_detail"],
        "TERMINAL_EQUAL_SEQUENCE": ["A1", "A2", "A2_conflict"],
    }[preload]
    for name in plan:
        deliver(reducer, builder, name)
    if preload == "ACTIVE_DETAIL_SNAPSHOT_FULL":
        # Fill the accepted logbook state to exactly the 100-session bound so a
        # 50-row delta must overflow it.
        grant_id = builder.positive()["inputs"]["grant_ids"]["LOGBOOK_DETAIL"]
        state = reducer.projections.setdefault(
            grant_id, {"sessions": {}, "plan": None, "summary": None}
        )
        for i in range(o.BOUNDS["sessions_per_logbook_grant"] - len(state["sessions"])):
            state["sessions"][f"pad{i:04d}"] = {}
    return reducer


def deliver(reducer: o.Reducer, builder, event_name: str):
    event = builder.events[event_name]
    return reducer.ingest(
        {
            "kind": o.CARRIER_KIND,
            "tags": o.CARRIER_TAGS,
            "content": event["content"],
            "inner_event_id": event["inner_event_id"],
            "authenticated_sender": event["author"],
        }
    )


def reconstruct_content(case: dict, builder) -> str | bytes:
    """The case's exact content bytes, either stored verbatim or by recipe.

    Two cases carry a recipe instead of their bytes: 64 KiB of padding, and the
    one content that is not UTF-8 and therefore cannot be a JSON string at all.
    Both pin the reconstructed length and digest, so the recipe is checked and
    not trusted.
    """
    if "content" in case:
        return case["content"]
    gen = case["content_generator"]
    if gen["kind"] == "suffix_pad":
        base = builder.events[gen["base_event"]]["content"]
        return base[:-1] + f',"{gen["member"]}":"' + gen["char"] * gen["repeat"] + '"}'
    if gen["kind"] == "invalid_utf8":
        base = builder.events[gen["base_event"]]["content"].encode("utf-8")
        at = gen["at"]
        return base[:at] + bytes.fromhex(gen["byte_hex"]) + base[at + 1:]
    raise SystemExit(f"unknown content generator {gen['kind']!r}")


def check_negative_case(report: Report, builder, case: dict) -> None:
    name = case["name"]
    expected = case["expected_disposition"]
    report.check(expected in o.DISPOSITIONS, f"{name}: disposition outside the closed five")
    reason = case["expected_reason"]
    allowed = o.REASONS_BY_DISPOSITION[expected]
    if allowed:
        report.check(reason in allowed,
                     f"{name}: reason {reason!r} not in the closed set for {expected}")
    else:
        report.check(reason is None, f"{name}: {expected} carries no reason code")

    kind = case["case_type"]
    if kind == "wire":
        content = reconstruct_content(case, builder)
        raw = content.encode("utf-8") if isinstance(content, str) else content
        report.equal(len(raw), case["content_size"], f"{name} content_size")
        report.equal(o.sha256_hex(raw), case["content_sha256"], f"{name} content_sha256")
        reducer = preloaded_reducer(builder, case["preload"])
        item = {
            "kind": o.CARRIER_KIND,
            "tags": o.CARRIER_TAGS,
            "content": content,
            # Content that is not UTF-8 has no recomputable NIP-01 id — the id
            # is taken over a JSON serialisation those bytes cannot appear in —
            # so none is claimed. Wire §8.6 records an inner id in the
            # diagnostic only when the event is structurally valid.
            "inner_event_id": o.nip01_event_id(
                case["authenticated_sender"] or builder.low, 1786000100,
                o.CARRIER_KIND, o.CARRIER_TAGS, content) if isinstance(content, str)
            else None,
            "authenticated_sender": case["authenticated_sender"],
        }
        if item["authenticated_sender"] is None:
            parsed, _ = o.parse_canonical_content(raw)
            item["authenticated_sender"] = (
                parsed.get("author") if isinstance(parsed, dict) else builder.low
            )
        got, got_reason = reducer.ingest(item)
    elif kind == "carrier":
        got, got_reason = o.evaluate_marmot_app_event(case["event"], case["mls_sender"])
    elif kind == "bootstrap_marker":
        s = case["state"]
        got, got_reason = o.evaluate_bootstrap_marker(
            s["name"], s["description"].encode("utf-8"), builder.low, builder.high,
            s["attempt_id"], s["after_activation"],
        )
    elif kind == "attempt":
        got, got_reason = o.evaluate_attempt(case["state"])
    elif kind == "topology":
        got, got_reason = o.evaluate_topology(case["state"])
    elif kind == "rollback":
        got, got_reason = o.evaluate_commit_rollback(case["state"])
    elif kind == "invalidation":
        reducer = preloaded_reducer(builder, "ACTIVE_WITH_DETAIL_GRANT")
        got, got_reason = reducer.invalidate(case["state"])
    elif kind == "derivation":
        got, got_reason = evaluate_derivation(case["state"])
    elif kind == "held_bound":
        got, got_reason = o.evaluate_bound_insert(case["state"])
    elif kind == "receipt":
        got, got_reason = o.evaluate_outbound_receipt(case["state"])
    elif kind == "ingress":
        got, got_reason = o.evaluate_ingress_recovery(case["state"])
    elif kind == "projection_authority":
        got, got_reason = o.evaluate_projection_authority(case["state"])
    else:
        report.check(False, f"{name}: unknown case_type {kind!r}")
        return

    report.equal(got, expected, f"{name} disposition")
    report.equal(got_reason, reason, f"{name} reason")


def check_fixture_generators(report: Report, negative: dict, scenarios: dict) -> None:
    """`build_fixtures.py` is the only author of these files, so prove it is.

    That module's own docstring says a stale fixture file "fails the build rather
    than silently becoming the new truth". It was true of `positive-vectors.json`
    only: :func:`check_positive` compares a fresh build against the stored
    sections, and nothing did the same for the negative corpus or the scenarios.
    A case hand-edited into either file — or, worse, a case edited *out* of a
    generator while the JSON kept it — reproduced nothing and drifted silently.

    The generators are deterministic by construction: they read the closed inputs
    at the top of `build_fixtures.py`, iterate no set, consult no clock and draw
    no randomness, and `build_fixtures.py` serialises all three files with the
    same `sort_keys=True` dump. So a section that differs is a real edit, never
    an ordering artefact.

    A **fresh** builder is used on purpose. The generators take the builder as an
    argument, and reusing the one every other check holds would risk this
    comparison perturbing state those checks depend on; the cost is one extra
    derivation pass.
    """
    fresh = rebuild()
    for name, built, stored in (
        ("negative-corpus.json", fresh.negative(), negative),
        ("reducer-scenarios.json", fresh.scenarios(), scenarios),
    ):
        if not report.equal(sorted(built), sorted(stored),
                            f"{name} top-level keys differ from build_fixtures.py"):
            continue
        for section in sorted(built):
            report.equal(
                json.dumps(built[section], ensure_ascii=False, sort_keys=True),
                json.dumps(stored[section], ensure_ascii=False, sort_keys=True),
                f"{name} section '{section}' is not what build_fixtures.py "
                f"produces — regenerate it rather than editing the JSON",
            )


def check_negative(report: Report, builder, corpus: dict) -> None:
    report.equal(list(corpus["areas"]), list(AREAS), "corpus area list")
    report.equal(list(corpus["dispositions"]), list(o.DISPOSITIONS), "disposition vocabulary")

    seen_areas: set[str] = set()
    for case in corpus["cases"]:
        seen_areas.add(case["area"])
        check_negative_case(report, builder, case)

    missing = [area for area in AREAS if area not in seen_areas]
    report.check(not missing, f"mandatory areas without a concrete case: {missing}")


def evaluate_derivation(state: dict) -> tuple[str, str | None]:
    """A producer-side refusal: the object never becomes a wire event at all."""
    try:
        if state["kind"] == "board":
            o.canonical_board(state["board"])
        elif state["kind"] == "problem":
            o.canonical_problem(state["problem"])
        elif state["kind"] == "holds":
            o.hold_fingerprint(state["ecosystem"], [[tuple(h) for h in f]
                                                    for f in state["frames"]])
        else:
            raise o.SpecViolation(f"unknown derivation kind {state['kind']!r}")
    except o.SpecViolation:
        return o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID"
    except KeyError:
        return o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID"
    return o.APPLIED, None


# --------------------------------------------------------------------------
# 3. Scenarios — one checker per case, so the mutation battery can scope
# --------------------------------------------------------------------------


def check_sequence_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    expect = case["expect"]
    if "topology" in case:
        state = dict(case["topology"])
        state.setdefault("endpoint_low", builder.low)
        state.setdefault("endpoint_high", builder.high)
        outcome, reason = o.evaluate_topology(state)
        report.equal(outcome, expect["topology_outcome"], f"{cid} topology outcome")
        if "topology_reason" in expect:
            report.equal(reason, expect["topology_reason"], f"{cid} topology reason")
        return

    reducer = preloaded_reducer(builder, case.get("preload", "ACTIVE_NO_HEADS"))
    results = [deliver(reducer, builder, name) for name in case["deliver"]]
    dispositions = [r[0] for r in results]
    reasons = [r[1] for r in results]
    if "dispositions" in expect:
        report.equal(dispositions, expect["dispositions"], f"{cid} dispositions")
    if "reasons" in expect:
        report.equal(reasons, expect["reasons"], f"{cid} reasons")
    if "dispositions_last" in expect:
        report.equal(dispositions[-1], expect["dispositions_last"], f"{cid} last disposition")
    if "reasons_last" in expect:
        report.equal(reasons[-1], expect["reasons_last"], f"{cid} last reason")
    if "effective_level" in expect:
        report.equal(reducer.effective_level(), expect["effective_level"], f"{cid} level")
    if "heads" in expect:
        for side, endpoint in (("low", builder.low), ("high", builder.high)):
            head = reducer.offer_heads.get(endpoint)
            report.equal(head["offer"] if head else None, expect["heads"][side],
                         f"{cid} {side} head")
    if "terminal" in expect:
        report.equal(reducer.terminal, expect["terminal"], f"{cid} terminal")
    if "terminal_reason" in expect:
        report.equal(reducer.terminal_reason, expect["terminal_reason"], f"{cid} reason")
    if "transition_display_time" in expect:
        times = [h["issued_at"] for h in reducer.offer_heads.values()]
        report.equal(max(times), expect["transition_display_time"], f"{cid} display time")
    if "applied_event_count" in expect:
        report.equal(len(reducer.applied), expect["applied_event_count"], f"{cid} applied")
    if "reconciling" in expect:
        report.equal(bool(reducer.reconciling), expect["reconciling"], f"{cid} reconciling")
    if "projection_present" in expect:
        report.equal(bool(reducer.projections), expect["projection_present"],
                     f"{cid} projection present")
    if "session_duration_minutes" in expect:
        rows = list(reducer.projections.values())[0]["sessions"]
        report.equal(list(rows.values())[0]["duration_minutes"],
                     expect["session_duration_minutes"], f"{cid} duration")
    if "session_count" in expect:
        rows = list(reducer.projections.values())[0]["sessions"]
        report.equal(len(rows), expect["session_count"], f"{cid} session count")
    if "boundary_session_started_at_utc" in expect:
        rows = list(reducer.projections.values())[0]["sessions"]
        starts = sorted(r["started_at_utc"] for r in rows.values())
        report.equal(starts[0], expect["boundary_session_started_at_utc"],
                     f"{cid} boundary start")
        report.check(all(s % o.DETAIL_START_BUCKET_S == 0 for s in starts),
                     f"{cid} every wire start is 900-aligned")
    if "detail_retired" in expect:
        retired = any(
            g["scope"] in o.DETAIL_SCOPES and g["state"] == "RETIRED"
            for g in reducer.grants.values()
        )
        report.equal(retired, expect["detail_retired"], f"{cid} detail retired")
    if "superseded_purged" in expect:
        purged = any(g["state"] == "SUPERSEDED" for g in reducer.grants.values())
        report.equal(purged, expect["superseded_purged"], f"{cid} supersession")
    if "grant_states" in expect:
        for label, want in expect["grant_states"].items():
            states = {g["state"] for g in reducer.grants.values()}
            report.check(want in states, f"{cid} grant state {label} -> {want}: {states}")
    if "sendable" in expect:
        report.equal(reducer.active and not reducer.terminal, expect["sendable"],
                     f"{cid} sendable")
    if "topology_phase" in expect:
        state = {
            "phase": expect["topology_phase"],
            "endpoint_low": builder.low,
            "endpoint_high": builder.high,
            "acceptance_applied": reducer.acceptance_applied,
        }
        outcome, _ = o.evaluate_topology(state)
        report.equal(outcome, expect["topology_outcome"],
                     f"{cid} topology outcome for phase {expect['topology_phase']}")
        report.equal(reducer.active and not reducer.terminal, outcome == "ACTIVE",
                     f"{cid} send/apply follows the topology outcome")


def check_topology_phase_case(report: Report, builder, case: dict) -> None:
    state = dict(case["state"])
    state.setdefault("endpoint_low", builder.low)
    state.setdefault("endpoint_high", builder.high)
    outcome, reason = o.evaluate_topology(state)
    report.equal(outcome, case["expect"]["outcome"], f"{case['id']} outcome")
    if "reason" in case["expect"]:
        report.equal(reason, case["expect"]["reason"], f"{case['id']} reason")
    if "admin_policy_0x8003" in case["expect"]:
        committer = state["admin_accounts"][0] if state["phase"] == "DISBANDED" else None
        report.equal(
            o.expected_admin_policy(state["phase"], builder.low, builder.high, committer),
            case["expect"]["admin_policy_0x8003"], f"{case['id']} 0x8003 value",
        )
    if "lifecycle_0x800c" in case["expect"]:
        report.equal(o.expected_lifecycle_byte(state["phase"]),
                     case["expect"]["lifecycle_0x800c"], f"{case['id']} 0x800c value")
    if "leaf_accounts" in case["expect"]:
        committer = state["admin_accounts"][0] if state["phase"] == "DISBANDED" else None
        report.equal(
            o.expected_leaf_accounts(state["phase"], builder.low, builder.high, committer),
            case["expect"]["leaf_accounts"], f"{case['id']} leaf accounts",
        )
    if "sendable" in case["expect"]:
        report.equal(outcome == "ACTIVE", case["expect"]["sendable"], f"{case['id']} sendable")


def check_state_machine_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    expect = case["expect"]
    machine = o.GenerationStateMachine(case["start_state"], case.get("sticky_deny", []))
    outcomes: list[str] = []
    refused: list[str] = []
    for step in case["transitions"]:
        accepted, _reason = machine.apply(step["event"], step.get("detail"))
        outcomes.append(machine.state)
        if not accepted:
            refused.append(step["event"])
    if "states" in expect:
        report.equal(outcomes, expect["states"], f"{cid} visited states")
    if "final_state" in expect:
        report.equal(machine.state, expect["final_state"], f"{cid} final state")
    if "terminal_reason" in expect:
        report.equal(machine.terminal_reason, expect["terminal_reason"], f"{cid} terminal reason")
    if "refused_transitions" in expect:
        report.equal(refused, expect["refused_transitions"], f"{cid} refused transitions")
    if "sticky_deny" in expect:
        report.equal(sorted(machine.sticky_deny), sorted(expect["sticky_deny"]),
                     f"{cid} sticky deny set")
    if "send_allowed" in expect:
        report.equal(machine.send_allowed(), expect["send_allowed"], f"{cid} send allowed")
    if "apply_allowed" in expect:
        report.equal(machine.apply_allowed(), expect["apply_allowed"], f"{cid} apply allowed")


def check_commit_rollback_case(report: Report, builder, case: dict) -> None:
    outcome, reason = o.evaluate_commit_rollback(case["signal"])
    report.equal(outcome, case["expect"]["outcome"], f"{case['id']} rollback outcome")
    report.equal(reason, case["expect"].get("reason"), f"{case['id']} rollback reason")
    if "tombstones_sticky" in case["expect"]:
        report.equal(o.rollback_keeps_tombstones(case["signal"]),
                     case["expect"]["tombstones_sticky"], f"{case['id']} tombstones sticky")
    if "purge_deadline_advanced" in case["expect"]:
        report.equal(o.rollback_advances_purge_deadline(case["signal"]),
                     case["expect"]["purge_deadline_advanced"],
                     f"{case['id']} purge deadline advanced")


def check_clock_case(report: Report, builder, case: dict) -> None:
    clock = o.TrustedClock(**case["start"])
    effective = clock.observe(**case["observe"])
    expect = case["expect"]
    report.equal(clock.untrusted, expect["untrusted"], f"{case['id']} untrusted")
    if "untrusted_reason" in expect:
        report.equal(clock.untrusted_reason, expect["untrusted_reason"],
                     f"{case['id']} untrusted reason")
    if "effective_now" in expect:
        report.equal(effective, expect["effective_now"], f"{case['id']} effective_now")
    if "grant_expired" in expect:
        report.equal(effective is not None and effective > 1793776300,
                     expect["grant_expired"], f"{case['id']} expiry")
    gate = o.clock_gate(clock)
    for key in ("grants_hidden", "purges_due", "permissive_send_blocked",
                "restrictive_send_blocked"):
        if key in expect:
            report.equal(gate[key], expect[key], f"{case['id']} {key}")
    if "reinitialise" in case:
        high_water = clock.reinitialise(**case["reinitialise"])
        report.equal(clock.untrusted, expect["after_reinit_untrusted"],
                     f"{case['id']} after reinit")
        report.equal(high_water, expect["after_reinit_high_water"],
                     f"{case['id']} reinit high water")


def check_outbox_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    expect = case.get("expect", {})
    if "map" in case:
        for state, label in case["map"].items():
            report.equal(o.outbox_label(state), label, f"{cid} label for {state}")
        visible = {o.outbox_label(s) for s in o.OUTBOX_STATES if o.outbox_label(s)}
        report.equal(len(visible), 4, f"{cid} exactly four visible labels")
    if "every_visible_row_also_says" in expect:
        report.equal(o.visible_label_footer(), expect["every_visible_row_also_says"],
                     f"{cid} every visible label also says")
    if "superseded_is_audit_only" in expect:
        report.equal(o.outbox_label("SUPERSEDED") is None,
                     expect["superseded_is_audit_only"], f"{cid} SUPERSEDED is audit-only")
    if "row" in case:
        report.equal(
            o.lease_is_recoverable(case["row"], case["now"], case["current_boot_id"]),
            expect["recoverable"], f"{cid} lease recoverable",
        )
        if "same_inner_event_id" in expect:
            report.equal(
                o.lease_recovery_event_id(case["row"]) == case["row"]["inner_event_id"],
                expect["same_inner_event_id"], f"{cid} recovery keeps the one inner id",
            )
    if "recovery" in expect:
        result = o.evaluate_lease_recovery(case["row"], case["now"], case["current_boot_id"])
        for key, value in expect["recovery"].items():
            report.check(key in result,
                         f"{cid} recovery expectation {key} has no oracle result")
            report.equal(result.get(key), value, f"{cid} recovery {key}")
        # Invariants no fixture may opt out of: recovery never mints a second
        # inner id and never binds a second source id (wire §8.7a, §8.7b). It
        # also never hands anything to the network — without exception; that is
        # asserted just below.
        report.equal(result.get("inner_event_id"), case["row"]["inner_event_id"],
                     f"{cid} recovery keeps the one inner event id")
        report.equal(result.get("binds_second_source_id"), False,
                     f"{cid} recovery binds no second source id")
        # Recovery is a durable state change plus a lease release. It never
        # calls the transport: every route to the network runs through the
        # write-ahead gate of §8.7c in a later drain attempt (§8.7e).
        report.equal(result.get("hands_to_transport"), False,
                     f"{cid} recovery hands a message to the transport itself")
        report.check(result.get("next_transport_step") in o.DRAIN_STEPS,
                     f"{cid} recovery names an unknown drain step "
                     f"{result.get('next_transport_step')!r}")
        if result.get("outcome") == "RECOVER_TRANSPORT_IDENTICAL":
            report.equal(
                o.R6_GATES["R6-G2-TRANSPORT-IDENTICAL-RETRY"]["status"], "CLOSED",
                f"{cid} re-drives a transport message while R6-G2 is open",
            )
    if "drain" in expect:
        drain = expect["drain"]
        recovered = o.evaluate_lease_recovery(case["row"], case["now"],
                                              case["current_boot_id"])
        report.equal(recovered["next_transport_step"], drain["next_transport_step"],
                     f"{cid} drain step after recovery")
        req = o.evaluate_drain_step({"drain_step": recovered["next_transport_step"]})
        for key in ("transport_reachable", "requires_write_ahead_commit",
                    "requires_persisted_identity", "required_closed_gate"):
            if key in drain:
                report.equal(req[key], drain[key], f"{cid} drain {key}")
        # The follow-on itself: only a proven write-ahead commit authorises the
        # first transport call, and it does so under a durable unclear phase.
        for commit, may_call in drain["commits"].items():
            gate = o.evaluate_send_handover({"write_ahead_commit": commit})
            report.equal(gate["transport_may_be_called"], may_call,
                         f"{cid} drain after a {commit} commit")
            if may_call:
                report.equal(gate["durable_phase"], drain["durable_phase_before_call"],
                             f"{cid} drain phase before the call")
                report.equal(gate["phase_commit_proven"], True,
                             f"{cid} drain calls without a proven commit")
    if "gate" in expect:
        report.check(expect["gate"] in o.R6_GATES, f"{cid} names an unknown R6 gate")
        report.equal(o.R6_GATES[expect["gate"]]["status"], "OPEN",
                     f"{cid} gate {expect['gate']} must stay OPEN")
    if "pairs" in case:
        for pair in case["pairs"]:
            report.equal(o.supersession_allowed(pair["prior"], pair["next"]),
                         pair["allowed"], f"{cid} {pair['prior']}->{pair['next']}")
    if "receipts" in case:
        for receipt in case["receipts"]:
            report.equal(o.relay_publication_claim(receipt["receipt"]),
                         receipt["expect_label"], f"{cid} {receipt['name']}")
            if "expect_queue_state" in receipt:
                report.equal(o.receipt_queue_state(receipt["receipt"]),
                             receipt["expect_queue_state"], f"{cid} {receipt['name']} state")


def check_send_handover_case(report: Report, builder, case: dict) -> None:
    """wire §8.7c/§8.7d — the write-ahead order before the call, what each crash
    window leaves, and what the authorised call itself leaves. The authorisation
    gate and the call result are asserted as two separate things."""
    cid = case["id"]
    expect = case["expect"]
    kind = case["kind"]

    if kind == "write_ahead":
        result = o.evaluate_send_handover(case["state"])
        for key, value in expect.items():
            if key == "recovery":
                continue
            report.check(key in result,
                         f"{cid} write-ahead expectation {key} has no oracle result")
            report.equal(result.get(key), value, f"{cid} write-ahead {key}")
        # Refusing the handover says nothing by itself about where the row now
        # stands. Every phase this gate result admits is driven through lease
        # recovery, so an `unproven` commit is asserted on both observations —
        # the pre-effect one that may be attempted again, and the unclear one
        # that must fail closed.
        covered = [entry["phase"] for entry in expect["recovery"]]
        report.equal(sorted(covered), sorted(result["admissible_phases"]),
                     f"{cid} recovery expectations do not cover every admissible phase")
        for entry in expect["recovery"]:
            row = {
                "state": "IN_FLIGHT",
                "lease_owner": "worker-a",
                "lease_boot_id": "boot-1",
                "lease_until": 1786000900,
                "attempts": 1,
                "inner_event_id": case["inner_event_id"],
                "external_effect_phase": entry["phase"],
                "handover_authorised": result["transport_may_be_called"],
                "receipt": None,
                "persisted_transport": None,
            }
            recovered = o.evaluate_lease_recovery(row, 1786001000, "boot-1")
            for key in ("outcome", "reason", "resulting_state", "automatic",
                        "hands_to_transport", "next_transport_step"):
                report.equal(recovered.get(key), entry[key],
                             f"{cid} recovery from {entry['phase']} {key}")
            report.equal(recovered["hands_to_transport"], False,
                         f"{cid} recovery from {entry['phase']} calls the transport itself")
        # Commit before transport, and its contrapositive: no proven commit, no
        # handover. Neither may be weakened by a fixture.
        if result["transport_may_be_called"]:
            report.check(result["phase_commit_proven"],
                         f"{cid} authorises transport without a proven phase commit")
            report.equal(result["durable_phase"], "EXTERNAL_EFFECT_UNCLEAR",
                         f"{cid} authorises transport under the wrong durable phase")
        else:
            report.check(not result["phase_commit_proven"] or result["transport_may_be_called"],
                         f"{cid} proves a commit yet refuses without a reason")
            report.check(result["reason"] in o.HANDOVER_REFUSED_REASONS,
                         f"{cid} refuses with an unknown reason {result['reason']!r}")
        report.equal(result["claims_atomic_handover"], False,
                     f"{cid} claims a transport call is atomic with a local transaction")
        # The gate is evaluated before the call, so it may not carry any result
        # of that call. Mixing the two is what §8.7d exists to prevent.
        for leaked in ("transport_call_result", "receipt_bound", "external_effect",
                       "reverts_to_pre_external_effect"):
            report.check(leaked not in result,
                         f"{cid} authorisation result mixes in a transport-call result")
        return

    if kind == "drain":
        result = o.evaluate_drain_step(case["state"])
        for key, value in expect.items():
            report.check(key in result,
                         f"{cid} drain expectation {key} has no oracle result")
            report.equal(result.get(key), value, f"{cid} drain {key}")
        report.equal(result["hands_to_transport_from_recovery"], False,
                     f"{cid} lets recovery reach the transport directly")
        if result["required_closed_gate"] is not None:
            # The replay route is defined, and defined as unreachable while its
            # gate is open. The gate itself is read here, never moved.
            report.check(result["required_closed_gate"] in o.R6_GATES,
                         f"{cid} names an unknown R6 gate")
            report.equal(o.R6_GATES[result["required_closed_gate"]]["status"], "OPEN",
                         f"{cid} gate {result['required_closed_gate']} must stay OPEN")
            report.equal(result["reachable_now"], False,
                         f"{cid} replay route is reachable while its gate is open")
            report.equal(result["requires_persisted_identity"], True,
                         f"{cid} replay route without the persisted identity requirement")
        return

    if kind == "post_handover":
        result = o.evaluate_post_handover(case["state"])
        for key, value in expect.items():
            if key == "recovery":
                continue
            report.check(key in result,
                         f"{cid} post-handover expectation {key} has no oracle result")
            report.equal(result.get(key), value, f"{cid} post-handover {key}")
        # After an authorised call there is no way back and no automatic retry.
        report.equal(result["reverts_to_pre_external_effect"], False,
                     f"{cid} reverts an authorised send to PRE_EXTERNAL_EFFECT")
        report.equal(result["automatic_retry"], False,
                     f"{cid} retries a send that was already authorised")
        report.equal(result["requeues"], False,
                     f"{cid} requeues a send that was already authorised")
        report.equal(result["claims_synchronous_no_effect_proof"], False,
                     f"{cid} assumes a synchronous durable no-external-effect proof")
        report.check(result["durable_phase"] != "PRE_EXTERNAL_EFFECT",
                     f"{cid} leaves a pre-external-effect row after an authorised call")
        if result["outcome"] == o.POST_HANDOVER_UNCLEAR:
            report.check(result["reason"] in o.POST_HANDOVER_REASONS,
                         f"{cid} stays unclear with an unknown reason {result['reason']!r}")
        else:
            report.equal(result["durable_phase"], "EXTERNAL_EFFECT_OBSERVED",
                         f"{cid} claims an observed effect without the observed phase")
        # The durable phase the call left is driven through lease recovery, so
        # the call result and the row the user ends up seeing are one path.
        entry = expect["recovery"]
        observed = result["durable_phase"] == "EXTERNAL_EFFECT_OBSERVED"
        row = {
            "state": "IN_FLIGHT",
            "lease_owner": "worker-a",
            "lease_boot_id": "boot-1",
            "lease_until": 1786000900,
            "attempts": 1,
            "inner_event_id": case["inner_event_id"],
            "external_effect_phase": result["durable_phase"],
            "handover_authorised": True,
            "receipt": case.get("receipt") if observed else None,
            "persisted_transport": None,
        }
        recovered = o.evaluate_lease_recovery(row, 1786001000, "boot-1")
        for key in ("outcome", "reason", "resulting_state", "automatic",
                    "hands_to_transport", "next_transport_step"):
            report.equal(recovered.get(key), entry[key],
                         f"{cid} recovery after the call {key}")
        report.equal(recovered["hands_to_transport"], False,
                     f"{cid} re-drives a send whose call was already authorised")
        return

    if kind != "crash":
        report.check(False, f"{cid}: unknown send-handover kind {kind!r}")
        return

    crash = o.evaluate_send_crash(case["state"])
    for key, value in expect.items():
        if key == "recovery":
            continue
        report.check(key in crash, f"{cid} crash expectation {key} has no oracle result")
        report.equal(crash.get(key), value, f"{cid} crash {key}")
    report.equal(crash["claims_atomic_handover"], False,
                 f"{cid} claims a transport call is atomic with a local transaction")

    # The write-ahead guarantee itself: wherever a handover was authorised or the
    # send may already have taken effect, a recovering worker can never find
    # PRE_EXTERNAL_EFFECT — which is what makes the §8.7b requeue safe.
    if crash["handover_authorised"] or crash["transport_possibly_called"]:
        report.check("PRE_EXTERNAL_EFFECT" not in crash["admissible_phases"],
                     f"{cid} leaves a pre-external-effect row after an authorised handover")
    if "PRE_EXTERNAL_EFFECT" in crash["admissible_phases"]:
        report.check(not crash["handover_authorised"],
                     f"{cid} admits a pre-external-effect row although a handover was authorised")
        report.check(not crash["transport_possibly_called"],
                     f"{cid} admits a pre-external-effect row although transport may have run")

    # Every admissible durable observation is driven through lease recovery, so
    # the crash window and the recovery path are asserted as one path.
    covered = [entry["phase"] for entry in expect["recovery"]]
    report.equal(sorted(covered), sorted(crash["admissible_phases"]),
                 f"{cid} recovery expectations do not cover every admissible phase")
    for entry in expect["recovery"]:
        row = {
            "state": "IN_FLIGHT",
            "lease_owner": "worker-a",
            "lease_boot_id": "boot-1",
            "lease_until": 1786000900,
            "attempts": 1,
            "inner_event_id": case["inner_event_id"],
            "external_effect_phase": entry["phase"],
            "handover_authorised": crash["handover_authorised"],
            "receipt": case.get("receipt") if crash["receipt_bound"] else None,
            "persisted_transport": None,
        }
        result = o.evaluate_lease_recovery(row, 1786001000, "boot-1")
        for key in ("outcome", "reason", "resulting_state", "automatic",
                    "hands_to_transport", "next_transport_step"):
            report.equal(result.get(key), entry[key],
                         f"{cid} recovery from {entry['phase']} {key}")
        report.equal(result["hands_to_transport"], False,
                     f"{cid} recovery from {entry['phase']} calls the transport itself")


def check_bound_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    expect = case["expect"]
    if "limit" in case:
        report.equal(case["limit"], o.BOUNDS[case["bound_key"]], f"{cid} limit")
    if "at_limit_state" in case:
        outcome, reason = o.evaluate_bound_insert(case["at_limit_state"])
        report.equal(outcome, expect["at_limit"], f"{cid} at the limit")
        report.equal(reason, expect.get("reason_at"), f"{cid} reason at the limit")
    if "above_limit_state" in case:
        outcome, reason = o.evaluate_bound_insert(case["above_limit_state"])
        report.equal(outcome, expect["above_limit"], f"{cid} above the limit")
        report.equal(reason, expect["reason_above"], f"{cid} reason above the limit")
    policy = o.bound_overflow_policy()
    for key in ("evicts", "prunes_tombstone", "prunes_safety_prefix",
                "other_generations_affected", "partial_application",
                "terminalises_before_insert"):
        if key in expect:
            report.equal(policy[key], expect[key], f"{cid} overflow policy {key}")


def check_restore_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    expect = case["expect"]
    result = o.evaluate_restore(case["state"])
    for key, value in expect.items():
        report.check(key in result, f"{cid} restore expectation {key} has no oracle result")
        report.equal(result.get(key), value, f"{cid} restore {key}")


def check_retirement_case(report: Report, builder, case: dict) -> None:
    """wire §10 / FEAT-062 §8 — one retirement cause, decided rather than read."""
    cid = case["id"]
    expect = case["expect"]
    result = o.evaluate_retirement(case["cause"])
    for key, value in expect.items():
        report.check(key in result,
                     f"{cid} retirement expectation {key} has no oracle result")
        report.equal(result.get(key), value, f"{cid} retirement {key}")


def check_runtime_gate_case(report: Report, builder, case: dict) -> None:
    cid = case["id"]
    kind = case["kind"]
    if kind == "exactly_once_table":
        # A declared table rather than an evaluated state: it binds the oracle
        # map to the rows wire §11.2 actually prints, so it carries a `map`
        # instead of an `expect`.
        check_exactly_once_table_case(report, case)
        return
    expect = case["expect"]
    if kind == "receipt":
        outcome, reason = o.evaluate_outbound_receipt(case["state"])
    elif kind == "ingress":
        outcome, reason = o.evaluate_ingress_recovery(case["state"])
    elif kind == "projection_authority":
        outcome, reason = o.evaluate_projection_authority(case["state"])
    elif kind == "exactly_once":
        outcome, reason = o.evaluate_exactly_once_claim(case["state"])
    elif kind == "invalidation_signal":
        result = o.evaluate_invalidation_signal(case["state"])
        for key, value in expect.items():
            if key == "gate":
                continue
            report.check(key in result,
                         f"{cid} invalidation expectation {key} has no oracle result")
            report.equal(result.get(key), value, f"{cid} invalidation {key}")
        # Invariants no fixture may opt out of: an invalidation is terminal
        # whatever else is wrong with the signal, a reason that is not
        # restart-durable is never recorded as if it were, and no branch claims
        # exactly-once invalidation delivery (FEAT-062 §5 gate 5).
        report.equal(result["outcome"], o.TERMINAL,
                     f"{cid} an invalidation that is not terminal")
        report.equal(result["reason_usable"], result["adapter_reason_recorded"] is not None,
                     f"{cid} records a reason it cannot stand behind")
        report.equal(result["gate_reported_fail"], not result["reason_usable"],
                     f"{cid} softens gate 5 instead of reporting FAIL")
        report.equal(result["claims_exactly_once_invalidation"], False,
                     f"{cid} claims exactly-once invalidation delivery")
        if "gate" in expect:
            report.check(expect["gate"] in o.R6_GATES, f"{cid} names an unknown R6 gate")
            report.equal(o.R6_GATES[expect["gate"]]["status"], "OPEN",
                         f"{cid} gate {expect['gate']} must stay OPEN")
        return
    else:
        report.check(False, f"{cid}: unknown runtime-gate kind {kind!r}")
        return
    report.equal(outcome, expect["outcome"], f"{cid} outcome")
    report.equal(reason, expect.get("reason"), f"{cid} reason")
    if kind == "ingress" and "epoch_advances" in case["state"]:
        # wire §8.5 — epoch advances are not a retention mechanism, so the count
        # must not be able to change the answer. Asserting that as an invariance
        # keeps the fixture's declared count from being an unread decoration:
        # the outcome is re-evaluated below and above the "more than five"
        # threshold and must be identical every time.
        for probe in (0, 5, case["state"]["epoch_advances"] + 7):
            probed = dict(case["state"], epoch_advances=probe)
            report.equal(o.evaluate_ingress_recovery(probed), (outcome, reason),
                         f"{cid} outcome depends on the epoch count (probe={probe})")
    if "gate" in expect:
        report.check(expect["gate"] in o.R6_GATES, f"{cid} names an unknown R6 gate")
        report.equal(o.R6_GATES[expect["gate"]]["status"], "OPEN",
                     f"{cid} gate {expect['gate']} must stay OPEN")


def check_r6_gate_case(report: Report, builder, case: dict) -> None:
    """wire §11.1 — the R6 reconciliation, asserted one gate at a time.

    Three dimensions are asserted separately because R6 moved them separately:
    what the pin was shown to do (``capability``), the surface that showed it
    (``evidence_scope``), and whether that is enough to close the gate for
    CruxCoach (``cruxcoach_closure``). Collapsing them is exactly how a
    host-side MockRelay pass would turn into a native or release claim.
    """
    gid = case["id"]
    expect = case["expect"]
    report.check(gid in o.R6_GATES, f"{gid} is not a known R6 gate")
    entry = o.R6_GATES.get(gid, {})
    for key, value in expect.items():
        report.check(key in entry, f"{gid} registry has no {key}")
        report.equal(entry.get(key), value, f"{gid} {key}")
    # Invariants no fixture may opt out of.
    report.equal(entry.get("status"), "OPEN", f"{gid} is not OPEN")
    report.equal(entry.get("cruxcoach_closure"), False,
                 f"{gid} claims a CruxCoach-suitable closure")
    report.check(entry.get("capability") in o.GATE_CAPABILITIES,
                 f"{gid} has an unknown capability {entry.get('capability')!r}")
    report.check(entry.get("evidence_scope") in o.GATE_EVIDENCE_SCOPES,
                 f"{gid} has an unknown evidence scope {entry.get('evidence_scope')!r}")
    # Host-side and crate-internal evidence never becomes native, on-device or
    # release evidence, however strong it is.
    report.check(entry.get("evidence_scope") not in o.FORBIDDEN_EVIDENCE_SCOPES,
                 f"{gid} claims {entry.get('evidence_scope')} evidence")
    # A gate that is open for no stated reason is decoration.
    report.check(bool(entry.get("blocking_remainder")),
                 f"{gid} is OPEN but names no blocking remainder")
    report.check(bool(entry.get("r6_evidence")), f"{gid} carries no R6 evidence record")
    report.check(bool(entry.get("r5_evidence")), f"{gid} carries no R5 evidence record")
    report.check(bool(entry.get("fail_closed")), f"{gid} carries no fail-closed rule")
    # Which spike found what. "Non-empty" was all the two evidence records were
    # ever asked for, so the registry could attribute R6's verdict to R5 — or
    # carry the same text twice — and every assertion stayed green. Each stage's
    # own record must state its own criterion and verdict, and must not state
    # the other stage's: that pair is the only thing separating them where both
    # read `NOT TESTED`, as they do for `R6-G8-INSTRUMENTATION-RUN`.
    for stage, other in (("r5", "r6"), ("r6", "r5")):
        report.check(entry.get(f"{stage}_verdict") in o.GATE_VERDICTS,
                     f"{gid} has an unknown {stage} verdict "
                     f"{entry.get(f'{stage}_verdict')!r}")
        prose = re.sub(r"[`*]", "", entry.get(f"{stage}_evidence", "")).lower()
        report.check(o.gate_evidence_marker(entry, stage) in prose,
                     f"{gid} {stage}_evidence does not state its own verdict "
                     f"{o.gate_evidence_marker(entry, stage)!r}: {prose[:120]!r}")
        report.check(o.gate_evidence_marker(entry, other) not in prose,
                     f"{gid} {stage}_evidence states the {other} verdict "
                     f"{o.gate_evidence_marker(entry, other)!r}, collapsing the "
                     f"R5/R6 distinction: {prose[:120]!r}")
    # A proven capability that closed nothing must say what is still missing,
    # and it may never be recorded as absent — that was the stale R5 claim.
    if entry.get("capability") == "PROVEN_AT_HOST_SCOPE":
        report.check(entry.get("evidence_scope") != "NONE",
                     f"{gid} is proven but records no evidence scope")


def check_r6_condition_case(report: Report, builder, case: dict) -> None:
    """wire §11.1 — the non-gate open items R6 recorded.

    A finding that changed a normative rule and a release blocker that changed
    none are both easy to lose in a prose rewrite, so each is asserted with the
    same weight as a gate.
    """
    cid = case["id"]
    report.check(cid in o.R6_OPEN_CONDITIONS, f"{cid} is not a known R6 open condition")
    entry = o.R6_OPEN_CONDITIONS.get(cid, {})
    for key, value in case["expect"].items():
        report.check(key in entry, f"{cid} registry has no {key}")
        report.equal(entry.get(key), value, f"{cid} {key}")
    report.check(entry.get("kind") in o.OPEN_CONDITION_KINDS,
                 f"{cid} has an unknown kind {entry.get('kind')!r}")
    report.check(bool(entry.get("summary")), f"{cid} carries no summary")
    report.check(bool(entry.get("normative_effect")),
                 f"{cid} does not say what it changed, or that it changed nothing")
    # A condition that blocks CruxCoach must point at the rule it forced; one
    # that does not must still say why not, which the check above covers.
    if entry.get("blocks_cruxcoach"):
        report.check(len(entry.get("normative_effect", "")) > 40,
                     f"{cid} blocks CruxCoach but states no substantive effect")


def check_replay_admission_case(report: Report, builder, case: dict) -> None:
    """wire §8.7b/§8.7e — a replay is admitted only by the full conjunction."""
    cid = case["id"]
    result = o.replay_admission(case["state"])
    expect = case["expect"]
    report.equal(result["admitted"], expect["admitted"], f"{cid} admitted")
    report.equal(result["blocking"], expect["blocking"], f"{cid} blocking reasons")
    # The reason the blocked row actually carries. Without this the admission
    # truth table proved only *that* a combination is refused, never what the
    # user is then shown, and the §8.7b mapping — including the one entry no
    # recovery can reach while `R6-G2-TRANSPORT-IDENTICAL-RETRY` is open —
    # would be prose nothing executes.
    report.equal(o.recovery_reason_for_blocking(result["blocking"]),
                 expect["blocked_recovery_reason"],
                 f"{cid} blocked recovery reason")
    # Structural invariants no fixture may opt out of: admission is exactly the
    # conjunction, and every blocking reason is a known one.
    state = case["state"]
    report.equal(result["admitted"],
                 all((state["persisted_identity"], state["product_gate_closed"],
                      state["source_correlation_available"])),
                 f"{cid} admission is not the conjunction of all three conditions")
    for reason in result["blocking"]:
        report.check(reason in o.REPLAY_ADMISSION_REASONS,
                     f"{cid} names an unknown blocking reason {reason!r}")
    # A blocked combination always names a reason, and an admitted one never
    # does: a blocked row with no reason would be the silent version of exactly
    # the fail-closed outcome this section exists to make visible.
    report.equal(expect["blocked_recovery_reason"] is None, expect["admitted"],
                 f"{cid} blocked-ness and the shown reason disagree")
    if expect["blocked_recovery_reason"] is not None:
        report.check(expect["blocked_recovery_reason"] in o.LEASE_RECOVERY_REASONS,
                     f"{cid} shows a reason outside the closed §8.7b set")
    # Missing only the source correlation must still block. If this ever became
    # admissible, closing R6-G2 alone would silently open the replay route that
    # §8.7e says it must not.
    if (state["persisted_identity"] and state["product_gate_closed"]
            and not state["source_correlation_available"]):
        report.equal(expect["blocked_recovery_reason"],
                     "RECOVERY_SOURCE_CORRELATION_UNAVAILABLE",
                     f"{cid} a closed gate alone must still leave the row blocked")
        report.equal(expect["admitted"], False,
                     f"{cid} admits a replay the resume path cannot correlate")


#: Closure phrasings that may never appear in a per-gate obligation cell. An
#: obligation says what is *still owed*; a cell that says the gate passed is the
#: opposite statement in the same place.
OBLIGATION_CLOSURE_PHRASES = ("is closed", "now closed", "already closed", "closed by",
                              "discharged", "gate passed", "nothing outstanding",
                              "nothing further is owed", "nothing is owed", "no longer owed",
                              "no longer open", "satisfied by", "met in full")


def _normalised_cell(cell: str) -> str:
    """A ``TABLE_TEXT`` cell reduced to the one word it decides.

    These columns carry a bare value — ``yes``/``no``/``never``, a bound, a
    computed instant — sometimes with emphasis or a trailing clause
    ("yes, after durable enqueue"). The first token after stripping emphasis and
    backticks is that value, and everything after it stays free prose.
    """
    # Backticks and `*` emphasis only: `_` is part of the values themselves
    # (`endpoint_low`, `held_bytes_per_slot`) and stripping it silently rewrote
    # every one of them.
    text = re.sub(r"[`*]", "", cell).strip().lower()
    if not text:
        return ""
    # A trailing clause is separated by ", " or " — "; a thousands separator is
    # not, so "262,144" stays one value while "yes, after durable enqueue" does
    # not become a second one.
    text = re.split(r",\s+|\s+—\s+", text)[0]
    return text.split(" ")[0].strip()


def _section_lines(lines: list[str], heading: str) -> list[str] | None:
    """The lines a heading owns, up to the next heading of the same or higher level."""
    if heading not in lines:
        return None
    start = lines.index(heading)
    level = len(heading) - len(heading.lstrip("#"))
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.startswith("#") and len(line) - len(line.lstrip("#")) <= level:
            return lines[start:index]
    return lines[start:]


def check_published_table(report: Report, case: dict, lines: list[str]) -> None:
    """A normative table a reader meets, bound row by row to the oracle.

    The bundle already bound its label, claim, refusal and obligation tables and
    said why: "a printed mapping nothing reads is decoration however normative it
    sounds". Its other normative tables were not bound, and every one of them
    could be edited into the opposite rule with the whole run staying green —
    §8.7b requeueing an unclear row, §8.7d calling an uncorrelated acceptance an
    observed delivery, §8.7c leaving a pre-effect row after an authorised
    handover, §8.4a holding instead of terminalising after activation.

    Three things are compared, so neither side can drift alone: the fixture's
    declared rows, the rows the oracle decides from the same functions the
    scenarios drive, and the rows the document actually prints. The fixture is
    therefore never self-confirming — mutating one of its tokens disagrees with
    the oracle, and editing the document disagrees with both.
    """
    cid = case["id"]
    table_id = case["table"]
    if not report.check(table_id in o.PUBLISHED_TABLES,
                        f"{cid} names an unknown published table {table_id!r}"):
        return
    entry = o.PUBLISHED_TABLES[table_id]
    report.equal(case["document"], entry["document"],
                 f"{cid} binds a document the registry does not name")
    oracle_rows = o.published_table_rows(table_id)

    # A header alone identifies most of these tables. Three of the wire
    # contract's schema tables share `Field | Requirement`, so an entry may name
    # the heading whose section owns it; the heading itself is then pinned too.
    heading = entry.get("heading")
    if heading is not None:
        lines = _section_lines(lines, heading)
        if not report.check(lines is not None,
                            f"{cid} names a heading {heading!r} the document does "
                            f"not print"):
            return

    # 1. the fixture declares exactly what the oracle decides.
    report.equal(case["expect"]["rows"], oracle_rows,
                 f"{cid} declares rows the oracle does not decide")

    # 2. the document prints exactly those rows, in that order.
    header = list(entry["header"])
    starts = [i for i, line in enumerate(lines) if _table_cells(line) == header]
    if not report.equal(len(starts), 1,
                        f"{cid} has no single table with the pinned header {header}"):
        return
    printed: list[list[str]] = []
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == len(header):
            printed.append(cells)
        row += 1
    if not report.equal(len(printed), len(oracle_rows),
                        f"{cid} prints {len(printed)} rows for {len(oracle_rows)} the "
                        f"oracle decides"):
        return
    for index, (cells, want) in enumerate(zip(printed, oracle_rows)):
        for column, binders in sorted(entry["columns"].items()):
            cell = cells[column]
            # One cell may decide more than one thing — §8.4a's outcome cell
            # carries both the disposition word and the closed fault code — so a
            # column takes a list of binders rather than a single one.
            for mode, key, vocabulary in binders:
                if mode == o.TABLE_TOKENS:
                    # De-duplicated, order preserved: a cell may legitimately
                    # name a value twice in prose, which is not a second decision.
                    seen: list[str] = []
                    for token in re.findall(r"`([A-Za-z0-9_]+)`", cell):
                        if token in vocabulary and token not in seen:
                            seen.append(token)
                    got = seen
                elif mode == o.TABLE_PHRASES:
                    # A prose cell states its facts in words, so each fact
                    # carries the spellings that count as stating it. Only the
                    # facts this row decides are looked for: a missing one
                    # shortens the list and fails against the oracle's, which is
                    # the edit — a relaxed deadline, a dropped terminality —
                    # that this mode exists to catch.
                    prose = re.sub(r"[`*]", "", cell).lower()
                    # An unrecognised fact has no spelling and so can never be
                    # stated: it drops out here and fails, rather than raising.
                    got = [fact for fact in want[key]
                           if any(spelling in prose
                                  for spelling in vocabulary.get(fact, ()))]
                else:
                    got = _normalised_cell(cell)
                report.equal(got, want[key],
                             f"{cid} row {index} ({want['key']}) column "
                             f"{header[column]!r} prints {got!r} for {key!r}, but "
                             f"the oracle decides {want[key]!r}: {cell!r}")


def check_published_binding_case(report: Report, builder, case: dict) -> None:
    """Published tables a reader reaches before any fixture, bound to the oracle.

    Both cases here were decoration until now. The INDEX registry row is the
    entry point that says whether FEAT-062 may be worked on at all, and nothing
    tied it to the spec's own front matter: a row reading `design-locked` would
    have invited exactly the work §0 records a No-Go for, with every other check
    green. Threat model §6 is where the bundle states what each open gate still
    owes; `check_gate_capability_agreement` binds the two *other* per-gate
    tables cell by cell, and this one was left out, so a blanked cell or a
    dropped row silently removed an obligation.
    """
    cid = case["id"]
    expect = case["expect"]
    rel = case["document"]
    lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")

    if case["kind"] == "registry_row":
        feature = case["feature"]
        rows = [line for line in lines if line.startswith(f"| {feature} |")]
        if not report.equal(len(rows), 1, f"{cid} has no single {feature} registry row"):
            return
        cells = _table_cells(rows[0])
        if not report.equal(len(cells), 5, f"{cid} registry row has the wrong shape"):
            return
        _feature, _title, release, status, path = cells
        report.equal(release, expect["release"], f"{cid} release cell")
        report.equal(status, expect["status"], f"{cid} status cell")
        report.check(expect["spec_path"] in path,
                     f"{cid} path cell does not name {expect['spec_path']}: {path!r}")
        spec = SPECS / expect["spec_path"]
        report.check(spec.is_file(), f"{cid} registry row points at a missing spec file")
        # The row and the spec's own front matter are two published statements
        # of the same fact, and the fixture pins both to one value.
        meta = read_front_matter(spec) if spec.is_file() else {}
        report.equal(meta.get("status"), expect["status"],
                     f"{cid} front matter status differs from the registry row")
        report.equal(meta.get("queue"), expect["queue"],
                     f"{cid} front matter queue differs from the registry")
        # A status the registry's own legend does not define is not a status.
        legend_starts = [i for i, line in enumerate(lines)
                         if _table_cells(line) == ["Status", "Meaning"]]
        if report.equal(len(legend_starts), 1, f"{cid} has no single status legend"):
            legend: set[str] = set()
            row = legend_starts[0] + 2
            while row < len(lines) and lines[row].startswith("|"):
                legend.add(_table_cells(lines[row])[0])
                row += 1
            report.check(expect["status"] in legend,
                         f"{cid} status {expect['status']!r} is absent from the legend")
            # The legend is normative for the whole registry, not only for this
            # row: the file's own conventions say spec front matter carries
            # `status:` "per the legend above". Checking only FEAT-062 left every
            # other row free to invent a status word — and the same edit that
            # added FEAT-062 also added a `reserved` row the legend never
            # defined, so the gap was not hypothetical.
            registry_header = ["ID", "Name", "Target", "Status", "Spec path"]
            table_starts = [i for i, line in enumerate(lines)
                            if _table_cells(line) == registry_header]
            if report.equal(len(table_starts), 1,
                            f"{cid} has no single registry table with header "
                            f"{registry_header}"):
                row = table_starts[0] + 2
                while row < len(lines) and lines[row].startswith("|"):
                    cells = _table_cells(lines[row])
                    if len(cells) == len(registry_header):
                        report.check(
                            cells[3] in legend,
                            f"{cid} registry row {cells[0]!r} carries status "
                            f"{cells[3]!r}, which the status legend does not define",
                        )
                    row += 1
        # The queue state is prose in the registry, not a column, so it is
        # asserted where a reader meets it.
        report.check(f"`{expect['queue']}`" in "\n".join(lines),
                     f"{cid} no longer prints the queue state `{expect['queue']}`")
        return

    if case["kind"] == "reason_mapping":
        # A mapping the wire prints as a table and the oracle decides in code.
        # Both are normative statements of the same rule, so the fixture pins
        # one map and this binds all three together; a printed mapping nothing
        # reads is decoration however normative it sounds.
        oracle = getattr(o, case["oracle_map"], None)
        if not report.check(isinstance(oracle, dict),
                            f"{cid} names no oracle map {case['oracle_map']!r}"):
            return
        declared = expect["map"]
        report.equal(declared, dict(oracle),
                     f"{cid} disagrees with the oracle map {case['oracle_map']}")
        header = list(case["header"])
        starts = [i for i, line in enumerate(lines) if _table_cells(line) == header]
        if not report.equal(len(starts), 1,
                            f"{cid} has no single table with header {header}"):
            return
        printed: dict[str, str] = {}
        row = starts[0] + 2
        while row < len(lines) and lines[row].startswith("|"):
            cells = _table_cells(lines[row])
            if len(cells) == len(header):
                # Both columns name their value in backticks; the key cell may
                # carry prose after it ("`failed`, provably").
                key = re.search(r"`([A-Za-z0-9_]+)`", cells[0])
                value = re.search(r"`([A-Za-z0-9_]+)`", cells[1])
                if key and value:
                    printed[key.group(1)] = value.group(1)
            row += 1
        report.equal(printed, declared,
                     f"{cid} the published table does not print the declared mapping")
        return

    if case["kind"] == "published_table":
        check_published_table(report, case, lines)
        return

    if case["kind"] != "gate_obligations":
        report.check(False, f"{cid}: unknown published-binding kind {case['kind']!r}")
        return

    header = list(expect["header"])
    starts = [i for i, line in enumerate(lines) if _table_cells(line) == header]
    if not report.equal(len(starts), 1,
                        f"{cid} has no single obligation table with header {header}"):
        return
    counts: dict[str, int] = {}
    cells_by_gate: dict[str, str] = {}
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == len(header):
            match = re.match(r"`([A-Z0-9\-]+)`$", cells[0])
            if match:
                counts[match.group(1)] = counts.get(match.group(1), 0) + 1
                cells_by_gate[match.group(1)] = cells[1]
        row += 1
    report.equal(sorted(cells_by_gate), sorted(o.R6_GATES),
                 f"{cid} does not state an obligation for exactly the open gates")
    for gate in sorted(o.R6_GATES):
        report.equal(counts.get(gate, 0), expect["rows_per_gate"],
                     f"{cid} rows for {gate}")
        cell = cells_by_gate.get(gate, "")
        report.check(len(cell) >= GATE_PROSE_MIN,
                     f"{cid} states no obligation for {gate}: {cell!r}")
        claims = any(phrase in cell.lower() for phrase in OBLIGATION_CLOSURE_PHRASES)
        report.equal(claims, expect["claims_closure"],
                     f"{cid} obligation for {gate} claims a closure: {cell!r}")


SCENARIO_SECTIONS = {
    "r6_gates": check_r6_gate_case,
    "published_bindings": check_published_binding_case,
    "replay_admission": check_replay_admission_case,
    "r6_conditions": check_r6_condition_case,
    "sequences": check_sequence_case,
    "topology_phases": check_topology_phase_case,
    "state_machine": check_state_machine_case,
    "commit_rollback": check_commit_rollback_case,
    "clock": check_clock_case,
    "outbox": check_outbox_case,
    "send_handover": check_send_handover_case,
    "bounds": check_bound_case,
    "restore": check_restore_case,
    "retirement": check_retirement_case,
    "runtime_gates": check_runtime_gate_case,
}


def check_send_handover_coverage(report: Report, scenarios: dict) -> None:
    """wire §8.7c/§8.7d — no closed value of the send path may go untested.

    A missing case would be a silent hole exactly where these sections exist to
    remove one, so the fixture set must name every write-ahead result, every
    crash point and every transport-call result.
    """
    cases = scenarios.get("send_handover", [])
    results = sorted({c["state"]["write_ahead_commit"] for c in cases
                      if c.get("kind") == "write_ahead"})
    points = sorted({c["state"]["crash_point"] for c in cases
                     if c.get("kind") == "crash"})
    calls = sorted({c["state"]["transport_call_result"] for c in cases
                    if c.get("kind") == "post_handover"})
    report.equal(results, sorted(o.WRITE_AHEAD_RESULTS),
                 "send-handover fixtures do not cover every write-ahead result")
    report.equal(points, sorted(o.SEND_CRASH_POINTS),
                 "send-handover fixtures do not cover every send crash point")
    steps = sorted({c["state"]["drain_step"] for c in cases if c.get("kind") == "drain"})
    report.equal(calls, sorted(o.TRANSPORT_CALL_RESULTS),
                 "send-handover fixtures do not cover every transport-call result")
    report.equal(steps, sorted(o.DRAIN_STEPS),
                 "send-handover fixtures do not cover every drain step")


def check_state_machine_coverage(report: Report, scenarios: dict) -> None:
    """FEAT-062 criterion 26g — every state/event pair, refused ones included.

    The criterion says in so many words that the fixtures assert the refused
    transitions and not only the accepted ones. That is a claim about the whole
    matrix: an unexercised pair is a transition the specification defines and
    nothing decides, and the pairs most worth deciding are the escapes from
    `TERMINAL`, which is supposed to be absorbing.
    """
    seen: set[tuple[str, str]] = set()
    for case in scenarios.get("state_machine", []):
        machine = o.GenerationStateMachine(case["start_state"], case.get("sticky_deny", []))
        for step in case["transitions"]:
            seen.add((machine.state, step["event"]))
            machine.apply(step["event"], step.get("detail"))
    every = {(state, event) for state in o.GENERATION_STATES
             for event in o.STATE_MACHINE_EVENTS}
    missing = sorted(every - seen)
    report.check(not missing,
                 f"state/event pairs no fixture ever exercises: {missing}")


#: golden §7's header, and the rows that are decided somewhere other than a
#: `sequences` scenario, with the corpus case that decides each.
#:
#: The table is a reader's index of what the reducer really does, and it was
#: bound to nothing: a row could be printed with no scenario behind it and the
#: run stayed green. That is not hypothetical — 7.0e was printed for exactly
#: that reason, and the document had to carry a paragraph admitting it instead.
#: The exemption list is closed and its corpus cases are looked up by name, so
#: "decided by the corpus" cannot become a place to park an unexecuted row.
SEQUENCE_TABLE_HEADER = ["#", "Delivery sequence", "Required outcome"]
SEQUENCE_ROWS_DECIDED_BY_CORPUS = {
    "7.0c": ("accept_wrong_author_endpoint_low", o.REJECT_NO_SLOT,
             "AUTHOR_BINDING_INVALID"),
}


def check_sequence_table_binding(report: Report, negative: dict, scenarios: dict) -> None:
    """golden §7 prints exactly the reducer sequences the fixtures execute.

    Both directions. A printed row with nothing behind it claims an outcome no
    check owns, and an executed scenario the document stops printing is a
    reducer rule a reader can no longer find.
    """
    path = SOCIAL / "PRIVATE-SHARING-GOLDEN-VECTORS.md"
    lines = path.read_text(encoding="utf-8").split("\n")
    starts = [i for i, line in enumerate(lines)
              if _table_cells(line) == SEQUENCE_TABLE_HEADER]
    if not report.equal(len(starts), 1,
                        f"golden §7 has no single table with the header "
                        f"{SEQUENCE_TABLE_HEADER}"):
        return
    printed: list[str] = []
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == len(SEQUENCE_TABLE_HEADER):
            printed.append(cells[0])
        row += 1

    executed = [case["id"] for case in scenarios.get("sequences", [])]
    report.equal(sorted(printed), sorted(set(printed)),
                 "golden §7 prints a sequence row twice")
    report.equal(sorted(set(printed) - set(SEQUENCE_ROWS_DECIDED_BY_CORPUS)),
                 sorted(executed),
                 "golden §7 and the executed sequence scenarios disagree")

    # Every exempted row really is decided, by the corpus case it names, with the
    # disposition and reason that row stands on.
    by_name = {case["name"]: case for case in negative["cases"]}
    for rid, (name, disposition, reason) in SEQUENCE_ROWS_DECIDED_BY_CORPUS.items():
        report.check(rid in printed, f"golden §7 no longer prints row {rid}")
        case = by_name.get(name)
        if not report.check(case is not None,
                            f"golden §7 row {rid} names a corpus case that does not "
                            f"exist: {name}"):
            continue
        report.equal(case["expected_disposition"], disposition,
                     f"golden §7 row {rid} corpus disposition")
        report.equal(case["expected_reason"], reason,
                     f"golden §7 row {rid} corpus reason")


def check_commit_rollback_coverage(report: Report, scenarios: dict) -> None:
    """wire §8.4a — every rollback seam and every authority commit class.

    The three seams are not interchangeable: MDK reports a branch-selection
    rollback through `GroupStateInvalidated`, `ForkRecovered` and
    `CommitRolledBack`, and FEAT-062's authority boundaries are commits, so a
    seam or a commit class that no fixture drives is a rule nothing decides.
    Both generation states matter too, and they matter **per** seam and **per**
    commit class rather than in aggregate. §8.4a states two different outcomes
    for the same signal — hold and return to `PROVISIONING` before activation,
    terminal with `COMMIT_ROLLBACK_AFTER_ACTIVATION` after it — so a class
    driven on one side only leaves the other half of its own rule undecided.
    Aggregating the two states across all classes hid exactly that: every class
    was exercised, both states appeared *somewhere*, and only `promotion` had
    actually been driven on both sides.
    """
    cases = scenarios.get("commit_rollback", [])
    events = {c["signal"]["event"] for c in cases}
    classes = {c["signal"]["commit_class"] for c in cases}
    report.equal(sorted(events), sorted(o.ROLLBACK_EVENTS),
                 "the rollback fixtures do not drive every seam")
    report.equal(sorted(classes), sorted(o.AUTHORITY_COMMITS + ("unrelated",)),
                 "the rollback fixtures do not drive every commit class")
    both_sides = {"PROVISIONING", "ACTIVE"}
    for commit_class in o.AUTHORITY_COMMITS:
        states = {c["signal"].get("generation_state") for c in cases
                  if c["signal"]["commit_class"] == commit_class}
        report.check(both_sides <= states,
                     f"the authority commit class {commit_class} is not driven on "
                     f"both sides of activation: {sorted(s for s in states if s)}")
    for event in o.ROLLBACK_EVENTS:
        states = {c["signal"].get("generation_state") for c in cases
                  if c["signal"]["event"] == event}
        report.check(both_sides <= states,
                     f"the rollback seam {event} is not driven on both sides of "
                     f"activation: {sorted(s for s in states if s)}")


def check_published_table_coverage(report: Report, scenarios: dict) -> None:
    """Every registered normative table is bound, and every binding is registered.

    Equality in both directions. A table added to the oracle registry without a
    fixture would be enumerated by nothing and mutated by nothing, which is the
    decoration this whole section exists to remove; a fixture naming a table the
    registry does not know would assert against nothing.
    """
    bound = sorted(case["table"] for case in scenarios.get("published_bindings", [])
                   if case.get("kind") == "published_table")
    report.equal(bound, sorted(o.PUBLISHED_TABLES),
                 "the published-table bindings and the oracle registry disagree")

    # wire §8.7b publishes two rows under an explicit hypothesis — "were the gate
    # closed" — and `evaluate_lease_recovery` decides them through its `world`
    # argument. That argument exists only for those rows, so the *real* world is
    # asserted here: with no hypothesis, a replayable unclear row still blocks on
    # the open gate, and `RECOVER_TRANSPORT_IDENTICAL` is unreachable. Without
    # this, a counterfactual row could quietly become the behaviour.
    real = o.evaluate_lease_recovery(
        o._sample_row("EXTERNAL_EFFECT_UNCLEAR", transport=o._REPLAYABLE),
        1786001000, "boot-1")
    report.equal(real["outcome"], o.RECOVERY_BLOCKED,
                 "a replayable unclear row is recovered without the R6-G2 hypothesis")
    report.equal(real["reason"], "RECOVERY_RETRY_GATE_OPEN",
                 "the real world no longer blocks a replay on the open gate")
    report.equal(real["resulting_state"], "DELIVERY_UNCLEAR",
                 "the real world no longer fails closed to a visible unclear row")


def check_retirement_coverage(report: Report, scenarios: dict) -> None:
    """wire §10 — every retirement cause is decided, exactly once.

    Equality rather than containment, for the same reason the replay-admission
    table is checked that way: a dropped cause would shrink the rule the two
    published tables are bound to, and both tables would then agree with an
    oracle that had quietly stopped deciding it.
    """
    seen = sorted(case["cause"] for case in scenarios.get("retirement", []))
    report.equal(seen, sorted(o.RETIREMENT_CAUSES),
                 "the retirement fixtures do not decide exactly the closed set of "
                 f"causes {list(o.RETIREMENT_CAUSES)}")


#: wire §8.8 states two things the oracle also implements: the freeze tolerance
#: and the formula `effective_now` advances by. Neither is a vocabulary token or
#: a table cell, so no binding above reaches them, and both are load-bearing —
#: the tolerance decides when a parked wall clock becomes `WALL_FROZEN`, and the
#: formula is the whole reason a high-water mark alone was rejected as unsafe.
CLOCK_MODEL_PINS = (
    "monotonic time advanced by at least "
    f"{o.TrustedClock.FREEZE_TOLERANCE_S} s while the wall clock did not advance",
    "effective_now = max(wall_now, wall_high_water + "
    "(monotonic_now - monotonic_anchor))",
)


def check_clock_model_published(report: Report) -> None:
    wire_rel = "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md"
    wire = (REPO_ROOT / wire_rel).read_text(encoding="utf-8")
    for pinned in CLOCK_MODEL_PINS:
        report.check(pinned in wire,
                     f"wire §8.8 no longer states the clock model the oracle "
                     f"enforces: {pinned!r}")


def check_replay_admission_coverage(report: Report, scenarios: dict) -> None:
    """wire §8.7b/§8.7e — the replay conjunction is decided as a whole table.

    FEAT-062 criterion 3b and wire §8.7b/§8.7e both state that the harness
    enumerates **all eight** combinations of the three conditions, precisely so
    that no single one can quietly become sufficient. Nothing enforced that
    claim: `check_replay_admission_case` decides whatever combinations the
    fixture happens to carry, so dropping one left every check green while the
    documents still said the table was complete.

    The row that matters most is the one where only the source correlation is
    missing. It is the state a **closed** `R6-G2-TRANSPORT-IDENTICAL-RETRY`
    alone would reach at the pin, and it is the only evidence in this bundle
    that closing that gate does not open the replay route. Losing it silently
    would turn "the gate is the last obstacle" into a true-looking reading of a
    corpus that no longer refutes it.
    """
    conditions = ("persisted_identity", "product_gate_closed",
                  "source_correlation_available")
    seen = [tuple(bool(case["state"][key]) for key in conditions)
            for case in scenarios.get("replay_admission", [])]
    every = [(a, b, c)
             for a in (False, True) for b in (False, True) for c in (False, True)]
    # Equality, not containment: a missing combination and a duplicated one are
    # both failures — the second would let a fixture pad the count.
    report.equal(sorted(seen), sorted(every),
                 "the replay-admission fixtures do not decide exactly the eight "
                 f"combinations of {list(conditions)}")


def check_scenarios(report: Report, builder, scenarios: dict) -> None:
    for section, checker in SCENARIO_SECTIONS.items():
        report.check(section in scenarios, f"reducer-scenarios.json has no '{section}' section")
        for case in scenarios.get(section, []):
            checker(report, builder, case)
    # A section nobody executes would be decoration, and the mutation battery
    # would never reach it either, so an unknown one fails the run.
    unknown = [key for key, value in scenarios.items()
               if isinstance(value, list) and key not in SCENARIO_SECTIONS]
    report.check(not unknown, f"reducer-scenarios.json has unexecuted sections: {unknown}")


# --------------------------------------------------------------------------
# 4. R6 gate registry cross-check
# --------------------------------------------------------------------------

#: Every document that must name the complete open-gate registry verbatim.
GATE_DOCUMENTS = (
    "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
    "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
    "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
)


def read_front_matter(path) -> dict:
    """The leading ``---`` block as a flat mapping, or ``{}`` if there is none."""
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return {}
    block, _, _ = text[4:].partition("\n---\n")
    return dict(
        (key.strip(), value.strip())
        for key, sep, value in (line.partition(":") for line in block.split("\n"))
        if sep and not key.startswith(" ")
    )


def check_markdown_tables(report: Report) -> None:
    """Every bundle table must have a uniform cell count.

    Most of this specification's normative content — the gate registry, the
    state and phase mappings, the call-result tables — is expressed as Markdown
    tables, and a row whose cell count drifts from its header silently renders
    as a different table than the one that was written. An unescaped ``|``
    inside a code span is enough to do it, so the shape is asserted rather than
    trusted.
    """
    separator = re.compile(r"\|[\s:\-|]+\|")
    for rel in BUNDLE_FILES:
        lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
        index = 0
        while index < len(lines) - 1:
            header, below = lines[index], lines[index + 1]
            if not (header.startswith("|") and separator.fullmatch(below)):
                index += 1
                continue
            width = header.count("|") - 1
            report.equal(below.count("|") - 1, width,
                         f"{rel}:{index + 2} separator width does not match its header")
            row = index + 2
            while row < len(lines) and lines[row].startswith("|"):
                report.equal(lines[row].count("|") - 1, width,
                             f"{rel}:{row + 1} table row width does not match its header")
                row += 1
            report.check(row > index + 2, f"{rel}:{index + 1} table has no rows")
            index = row


#: The one phrase each capability must be written as in the two documents that
#: tabulate it. FEAT-062 §5a says in so many words that it is the product-level
#: view of wire §11.1 and "must agree with it"; this is that sentence made
#: executable, so the two tables cannot drift apart or fall behind the registry.
CAPABILITY_PHRASES = {
    "ABSENT": "absent",
    "PARTIAL": "partial",
    "PROVEN_AT_HOST_SCOPE": "proven at host scope",
    "NOT_TESTED": "not tested",
}

#: Per document: the gate table's header cells, the index of the cell that
#: carries the capability verdict, and the index of the cell that carries the
#: evidence scope. The header is pinned so neither index can silently come to
#: mean a different column after a table edit.
#:
#: Wire §11.1 states both in one "Capability / scope" cell, so its two indices
#: coincide. FEAT-062 §5a splits them into separate columns, and the scope
#: column needs its own binding: screening only the capability cell left §5a's
#: "Evidence scope" column free to claim a native, on-device or release run
#: while every other check stayed green — including for
#: `R6-G8-INSTRUMENTATION-RUN`, whose scope is `NONE` because it never ran.
GATE_CAPABILITY_DOCUMENTS = {
    "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md": (
        ["Gate", "Open capability", "R6 capability", "Evidence scope",
         "Fail-closed rule while open"],
        2,
        3,
    ),
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": (
        ["Gate", "What is open", "R5 evidence", "R6 evidence", "Capability / scope",
         "Blocking remainder", "Fail-closed while open"],
        4,
        4,
    ),
}

#: The one phrase each evidence scope must be written as in the documents that
#: tabulate it. `FORBIDDEN_SCOPE_TOKENS` screens for a scope R6 never reached;
#: this binds the cell to the scope the oracle actually records, so a row can
#: neither widen `NONE` into runtime evidence nor blur
#: `HOST_RUST_CRATE_INTERNAL` into a full host-relay run.
SCOPE_PHRASES = {
    "NONE": "none",
    "HOST_RUST_MOCKRELAY": "host rust + mockrelay",
    "HOST_RUST_CRATE_INTERNAL": "host rust, crate-internal",
}

#: Gate-table columns whose prose is load-bearing, by header name.
#:
#: `check_r6_gate_case` asserts the oracle registry carries a blocking remainder,
#: a fail-closed rule and both evidence records. That is a statement about the
#: registry, not about the table a reader actually sees: emptying one of these
#: cells left the published gate row stating no fail-closed rule — or no R6
#: evidence at all — while every registry assertion stayed green. The shortest
#: real cell is 37 characters, so a blanked or placeholder cell fails this.
GATE_REQUIRED_PROSE = {
    "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md": (
        "Open capability", "Fail-closed rule while open",
    ),
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": (
        "What is open", "R5 evidence", "R6 evidence", "Blocking remainder",
        "Fail-closed while open",
    ),
}

#: The fail-closed column of each gate table, by document. Its content is bound
#: to the disposition :data:`private_sharing_oracle.GATE_FAIL_CLOSED_DISPOSITIONS`
#: derives by running that gate's evaluator, so the two published statements of
#: one rule cannot drift apart or away from the code.
GATE_FAIL_CLOSED_COLUMNS = {
    "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md":
        ("Fail-closed rule while open",),
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": ("Fail-closed while open",),
}

#: The minimum length a load-bearing gate-table cell must have to count as
#: stating something. Well under the shortest real cell and well over any
#: placeholder.
GATE_PROSE_MIN = 30

#: Column pairs that may never collapse into each other. R5 and R6 reached
#: different conclusions on the same gates — R6 refuted R5 outright on
#: `R6-G2-TRANSPORT-IDENTICAL-RETRY` — and a row printing one text in both
#: columns erases exactly the differentiation §11.1 exists to record.
GATE_DISTINCT_PROSE = {
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": (("R5 evidence", "R6 evidence"),),
}

#: Per document: the gate-table columns carrying each spike's evidence, by
#: header name. Only wire §11.1 tabulates both; FEAT-062 §5a states the product
#: view and has no such columns.
#:
#: `GATE_DISTINCT_PROSE` above only asks that the two cells *differ*, which
#: swapping them satisfies perfectly. So the row could print R5's finding in
#: R6's column — the one edit that turns "R6 refuted R5" into its opposite for
#: `R6-G2-TRANSPORT-IDENTICAL-RETRY` — and, more quietly, could restate R6's
#: verdict as `FAIL` where the registry records `PASS`. Both left every other
#: check green, because nothing bound either cell to the spike it names.
GATE_EVIDENCE_COLUMNS = {
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": (("r5", "R5 evidence"),
                                                     ("r6", "R6 evidence")),
}

#: Evidence-scope vocabulary no gate may claim while `R6-G8-INSTRUMENTATION-RUN`
#: has never run. R6's evidence is host-scope without exception.
FORBIDDEN_SCOPE_TOKENS = ("native", "on-device", "on device", "release", "device run")


def _table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


#: The two documents that print the total outbox-state -> user-visible label
#: map, with their pinned header cells.
#:
#: `OUTBOX_LABELS` is the oracle's copy of that map and every outbox scenario
#: asserts against it, but nothing bound the *published* tables to it.
#: Relabelling `MDK_CANONICAL` as "Delivered to peer", softening
#: `DELIVERY_UNCLEAR` to "Queued locally", or giving audit-only `SUPERSEDED` an
#: active-send label each left every fixture, scenario and registry check green
#: while the specification told a reader the opposite of §10.8, criterion 42a
#: and threat-model forbidden claim 10.
OUTBOX_LABEL_TABLES = (
    ("docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     ["Outbox state", "Label"]),
    ("docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     ["Outbox state", "User-visible label"]),
)

#: Words a transport label may never use. v1 defines no peer ACK, so a label
#: that reports the peer got something is false by construction, and the only
#: once-ness this bundle claims is idempotent local reduction.
FORBIDDEN_LABEL_CLAIMS = ("delivered", "read receipt", "peer received",
                          "received by the peer", "confirmed by the peer",
                          "exactly once", "exactly-once")


def check_outbox_label_tables(report: Report) -> None:
    """Every published state -> label table must print the oracle's total map.

    The mapping is normative in FEAT-062 §8 and wire §8.7 and is the single
    place the specification states what a user is actually shown about a send.
    """
    for rel, header in OUTBOX_LABEL_TABLES:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        lines = text.split("\n")
        starts = [i for i, line in enumerate(lines) if _table_cells(line) == header]
        if not report.equal(
                len(starts), 1,
                f"{rel} has no single outbox label table with header {header}"):
            continue
        seen: dict[str, str] = {}
        row = starts[0] + 2
        while row < len(lines) and lines[row].startswith("|"):
            cells = _table_cells(lines[row])
            if len(cells) == len(header):
                # One row may group several states, as FEAT-062 §8 does.
                for state in re.findall(r"`([A-Z_]+)`", cells[0]):
                    seen[state] = cells[1]
            row += 1
        # Total in both directions: no state missing, none invented.
        report.equal(sorted(seen), sorted(o.OUTBOX_STATES),
                     f"{rel} label table does not map exactly the outbox states")
        for state, cell in sorted(seen.items()):
            label = o.OUTBOX_LABELS.get(state)
            if label is None:
                # `SUPERSEDED` is audit-only and carries no active-send label.
                report.check(cell.lower().startswith("none"),
                             f"{rel} gives {state} an active-send label: {cell!r}")
            else:
                report.check(label in cell,
                             f"{rel} does not label {state} {label!r}: got {cell!r}")
            for claim in FORBIDDEN_LABEL_CLAIMS:
                report.check(claim not in cell.lower(),
                             f"{rel} label for {state} claims {claim!r}: {cell!r}")
        # Every visible label is qualified by the same truth: v1 has no peer ACK.
        report.check(o.NOT_PEER_CONFIRMED in text,
                     f"{rel} no longer prints the {o.NOT_PEER_CONFIRMED!r} qualifier")


#: The product specification, whose restatements of the wire rules are what a
#: reader — or an implementer deciding whether to start — actually meets.
SPEC_REL = "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md"

#: FEAT-062 §2.2's header, pinned so the two ownership columns cannot be
#: renamed out from under the check below.
OWNERSHIP_HEADER = ["Marmot/MDK owns exclusively", "CruxCoach owns exclusively"]


def check_ownership_boundary(report: Report) -> None:
    """FEAT-062 §2.2 — neither side may take over the other's column.

    Ownership is the boundary this design rests on: MDK owns MLS, membership,
    epochs, relays and invalidation; CruxCoach owns application semantics inside
    the canonical content bytes and nothing else. The table was free prose, so
    the two cells of a row could be swapped outright — handing MLS, KeyPackages
    and the group id to CruxCoach and the grant semantics to MDK — with every
    other check in this bundle green.

    Both directions are asserted, because either alone is satisfiable by a swap:
    each side's exclusive phrases must appear in its own column and never in the
    other's.
    """
    lines = (REPO_ROOT / SPEC_REL).read_text(encoding="utf-8").split("\n")
    starts = [i for i, line in enumerate(lines) if _table_cells(line) == OWNERSHIP_HEADER]
    if not report.equal(len(starts), 1,
                        f"{SPEC_REL} has no single ownership table with header "
                        f"{OWNERSHIP_HEADER}"):
        return
    owned = {"MARMOT_MDK": [], "CRUXCOACH": []}
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == 2:
            owned["MARMOT_MDK"].append(cells[0].lower())
            owned["CRUXCOACH"].append(cells[1].lower())
        row += 1
    if not report.check(owned["MARMOT_MDK"],
                        f"{SPEC_REL} ownership table prints no rows"):
        return
    for side, phrases in sorted(o.OWNERSHIP_BOUNDARY.items()):
        mine = "\n".join(owned[side])
        theirs = "\n".join(owned["CRUXCOACH" if side == "MARMOT_MDK" else "MARMOT_MDK"])
        for phrase in phrases:
            report.check(phrase in mine,
                         f"{SPEC_REL} §2.2 no longer records {phrase!r} as owned "
                         f"exclusively by {side}")
            report.check(phrase not in theirs,
                         f"{SPEC_REL} §2.2 hands {phrase!r} to the other side of "
                         f"the ownership boundary")


def check_build_inputs(report: Report) -> None:
    """FEAT-062 §2.3, §5 gate 1 and criterion 1 must name one build.

    The table itself is bound row by row as `feat_build_inputs`. This binds the
    prose that restates it: gate 1 and its acceptance criterion name the ABI,
    the API level and the NDK again, and nothing tied those restatements to the
    pins — so the spike could be specified against `armeabi-v7a`, API 21 and a
    different NDK while §2.3 still printed the pinned ones.
    """
    text = (REPO_ROOT / SPEC_REL).read_text(encoding="utf-8")
    inputs = dict(o.BUILD_INPUTS)
    # One ABI only: every ABI token in the product spec is the gated one.
    for abi in o.ANDROID_ABIS:
        if abi == inputs["Android ABI"]:
            report.check(text.count(f"`{abi}`") >= 2,
                         f"{SPEC_REL} no longer restates the pinned ABI {abi!r} in "
                         f"gate 1 and criterion 1")
        else:
            report.check(abi not in text,
                         f"{SPEC_REL} names the ABI {abi!r}, which is not the "
                         f"pinned {inputs['Android ABI']!r}")
    # Every NDK version printed anywhere is the pinned one.
    for found in sorted(set(re.findall(r"NDK\s+`([0-9.]+)`", text))):
        report.equal(found, inputs["NDK"], f"{SPEC_REL} names an unpinned NDK version")
    report.check(re.search(r"NDK\s+`" + re.escape(inputs["NDK"]) + r"`", text),
                 f"{SPEC_REL} no longer names the pinned NDK {inputs['NDK']!r}")
    # Every API level printed is either the gated one or the recorded host
    # baseline §12 states does *not* answer the API-28 question.
    for found in sorted(set(re.findall(r"API[ -](\d+)", text))):
        report.check(found in o.ANDROID_API_LEVELS,
                     f"{SPEC_REL} names API level {found!r}, which is neither the "
                     f"gated {inputs['Android native API']!r} nor the recorded "
                     f"host baseline")
    report.check(f"API {inputs['Android native API']}" in text
                 and f"API-{inputs['Android native API']}" in text,
                 f"{SPEC_REL} no longer gates gate 1 and criterion 1 on API "
                 f"{inputs['Android native API']}")


#: Numbers the product specification restates from the wire contract, as
#: ``(label, pattern, expected)``. Each pattern must match at least once — a
#: deleted sentence fails — and every match must be the enforced value, so a cap
#: cannot be widened in the document a user-facing decision is read from.
#:
#: `wire_collection_limits` and `wire_storage_bounds` bind the annex's own
#: tables; nothing reached FEAT-062's prose, where §4.3 and §6.1 restate the
#: 65,536-byte envelope cap, the 100-session detail bound, the 1–50 delta range,
#: the 90-day grant maximum and the quarantine bounds.
def _restated_limits() -> tuple:
    return (
        ("envelope content cap", r"limited to ([\d,]+) UTF-8 bytes",
         (f"{o.CONTENT_MAX_BYTES:,}",)),
        ("envelope content cap (criterion)", r"above ([\d,]+) UTF-8 bytes",
         (f"{o.CONTENT_MAX_BYTES:,}",)),
        ("logbook detail sessions", r"at most (\d+) complete prospective sessions",
         (str(o.BOUNDS["sessions_per_logbook_grant"]),)),
        ("logbook state sessions", r"logbook state above (\d+) sessions",
         (str(o.BOUNDS["sessions_per_logbook_grant"]),)),
        ("sends per session", r"more than (\d+) distinct sends",
         (str(o.BOUNDS["sends_per_session"]),)),
        ("delta operations", r"with (\d+) to (\d+) operations",
         ("1", str(o.BOUNDS["delta_ops"]))),
        ("delta operations (criterion)", r"deltas contain (\d+)–(\d+) operations",
         ("1", str(o.BOUNDS["delta_ops"]))),
        ("grant maximum duration", r"expiry no more than\n?(\d+) days after issue",
         (str(o.GRANT_MAX_DURATION_S // 86_400),)),
        ("quarantine bounds",
         r"capped at (\d+) records\nper generation and (\d+) days",
         (str(o.BOUNDS["diagnostics_per_generation"]),
          str(o.BOUNDS["diagnostic_days"]))),
    )


def check_restated_limits(report: Report) -> None:
    """Every bound FEAT-062 restates is the bound the oracle enforces."""
    text = (REPO_ROOT / SPEC_REL).read_text(encoding="utf-8")
    for label, pattern, expected in _restated_limits():
        found = re.findall(pattern, text)
        if not report.check(found,
                            f"{SPEC_REL} no longer states the {label} the oracle "
                            f"enforces ({'/'.join(expected)})"):
            continue
        for match in found:
            got = match if isinstance(match, tuple) else (match,)
            report.equal(got, expected,
                         f"{SPEC_REL} restates the {label} as a value the oracle "
                         f"does not enforce")


#: Threat model §5's leakage matrix, and the rows whose answer is a *grant*
#: decision rather than a metadata fact. Each names the projection scope that
#: decides it.
LEAKAGE_HEADER = ["Data", "Relay/crawler", "Acquaintance", "Friend", "Public"]
LEAKAGE_SCOPE_ROWS = {
    "explicitly granted all-time summary, including pre-connection history":
        "LOGBOOK_SUMMARY",
    "prospective logbook detail": "LOGBOOK_DETAIL",
    "one selected prospective plan": "PLAN_DETAIL",
}


def check_leakage_matrix(report: Report) -> None:
    """Threat model §5 may not give a level more than its scope authorises.

    The matrix is the compact answer to "who can see what", and it was bound by
    nothing: raising prospective logbook detail from *No* at `ACQUAINTANCE` to
    *Yes* contradicts wire §6.3's minimum level — the rule
    `wire_scope_levels` already binds one table over — in the one place a
    reviewer looks for the summary.
    """
    rel = "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md"
    lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
    starts = [i for i, line in enumerate(lines) if _table_cells(line) == LEAKAGE_HEADER]
    if not report.equal(len(starts), 1,
                        f"{rel} has no single leakage matrix with header "
                        f"{LEAKAGE_HEADER}"):
        return
    printed: dict[str, list[str]] = {}
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == len(LEAKAGE_HEADER):
            printed[re.sub(r"[`*]", "", cells[0]).strip().lower()] = [
                cell.strip().lower() for cell in cells[1:]]
        row += 1
    for data, scope in sorted(LEAKAGE_SCOPE_ROWS.items()):
        cells = printed.get(data)
        if not report.check(cells, f"{rel} §5 no longer has a row for {data!r}"):
            continue
        acquaintance, friend, public = cells[1], cells[2], cells[3]
        minimum = o.SCOPE_MIN_LEVEL[scope]
        # A scope requiring FRIEND may never be answered "yes" for an
        # acquaintance, and no application scope is ever public.
        if minimum == "FRIEND":
            report.check(acquaintance == "no",
                         f"{rel} §5 shows {scope} to an acquaintance ({acquaintance!r}) "
                         f"while wire §6.3 requires {minimum}")
            report.check("only if explicitly granted" in friend,
                         f"{rel} §5 no longer makes {scope} conditional on an "
                         f"explicit grant for a friend: {friend!r}")
        else:
            report.check(acquaintance == "yes" and friend == "yes",
                         f"{rel} §5 does not show {scope} at its minimum level "
                         f"{minimum}: {acquaintance!r}/{friend!r}")
        report.check(public == "no",
                     f"{rel} §5 makes {scope} public: {public!r}")


#: FEAT-062 §5 — the eight binary spike gates, by number and title.
#:
#: "All eight must pass" is the sentence the whole No-Go rests on, and the list
#: it counts was free prose: dropping a gate, renumbering the list or retitling
#: an entry left every gate-table, registry and fixture check green while the
#: spike's scope quietly shrank.
SPIKE_GATES = (
    ("1", "Reproducible native build"),
    ("2", "Dedicated group creation"),
    ("3", "Dedicated outbound"),
    ("4", "Dedicated acknowledged inbox"),
    ("5", "Unambiguous invalidation"),
    ("6", "Hard isolation"),
    ("7", "Android two-client recovery"),
    ("8", "Signer atomicity"),
)

#: FEAT-062 §11 — the closed set of acceptance-criterion ids, in printed order.
#:
#: The criteria are what a later implementation is judged against, and every one
#: of them was unbound: a criterion could be deleted outright and nothing
#: noticed. The order is part of the binding because the documents cite criteria
#: by number ("criterion 3b", "criterion 51a"), so a renumber silently redirects
#: every citation. Two pairs are deliberately out of alphabetical order — 12b
#: before 12a, 51b before 51a — because that is how they were inserted; the
#: order is pinned as printed rather than normalised.
ACCEPTANCE_CRITERIA = (
    "1", "2", "2a", "3", "3a", "3b", "4", "4a", "4b", "5", "5a", "6", "7", "8",
    "9", "10", "11", "12", "12b", "12a", "13", "13a", "14", "15", "15a", "16",
    "17", "18", "18a", "19", "20", "21", "22", "23", "24", "25", "26", "26a",
    "26b", "26c", "26d", "26e", "26f", "26g", "26h", "26i", "26j", "27", "28",
    "29", "30", "31", "32", "33", "34", "34a", "35", "36", "37", "38", "39",
    "40", "41", "42", "42a", "43", "44", "44a", "45", "45a", "46", "47", "48",
    "49", "49a", "50", "50a", "50b", "50c", "50d", "51", "51b", "51c", "51a",
    "52", "53", "54", "55",
)

#: The lettered groups §11 is organised into, in order.
ACCEPTANCE_SECTIONS = (
    "A. Adapter spike",
    "B. Registry, group and generation",
    "C. Ordering and reducer",
    "D. Relationship, grant and projection",
    "E. Minimisation and retention",
    "F. Runtime, restore and UX",
    "G. Eventual Gradle gates",
)

#: The length floor a criterion must clear to be stating a requirement. The
#: shortest real ones are §11.G's single Gradle commands (46 characters); a
#: blanked or placeholder criterion is well under this.
CRITERION_MIN = 40


def _spec_section(text: str, heading: str, next_heading: str) -> str | None:
    parts = text.split(heading)
    if len(parts) != 2:
        return None
    return parts[1].split(next_heading)[0]


def check_spec_gate_and_criteria_registry(report: Report) -> None:
    """FEAT-062 §5 and §11 are closed lists, not free prose.

    Everything else in this harness binds a rule's *content*. These two bind the
    lists themselves: the eight binary gates the No-Go is defined by, and the
    acceptance criteria a later implementation is judged against. Both were
    unbound in the direction that matters most — deletion — so a gate or a
    criterion could vanish with the whole run green.
    """
    text = (REPO_ROOT / SPEC_REL).read_text(encoding="utf-8")

    section = _spec_section(text, "## 5. Adapter spike: eight binary gates", "\n## 5a.")
    if report.check(section is not None,
                    f"{SPEC_REL} has no single §5 adapter-spike section"):
        printed = re.findall(r"(?m)^(\d+)\. \*\*(.+?)\.\*\*", section)
        report.equal(printed, [tuple(gate) for gate in SPIKE_GATES],
                     f"{SPEC_REL} §5 does not print exactly the eight binary gates")
        # The count is stated in prose as well as by the list, and that sentence
        # is what a reader counts against. Compared with whitespace normalised,
        # so re-wrapping the paragraph is free and weakening it is not.
        flowed = " ".join(section.split())
        report.check("all eight must pass against the pins in §2.3" in flowed,
                     f"{SPEC_REL} §5 no longer requires all "
                     f"{len(SPIKE_GATES)} gates to pass")
        report.check("A partial pass is No-Go." in flowed,
                     f"{SPEC_REL} §5 no longer refuses a partial pass")

    section = _spec_section(text, "## 11. Acceptance criteria", "\n## 12.")
    if not report.check(section is not None,
                        f"{SPEC_REL} has no single §11 acceptance-criteria section"):
        return
    report.equal(re.findall(r"(?m)^### (.+)$", section), list(ACCEPTANCE_SECTIONS),
                 f"{SPEC_REL} §11 does not print exactly its lettered groups")
    printed_ids = re.findall(r"(?m)^(\d+[a-z]?)\.\s", section)
    report.equal(printed_ids, list(ACCEPTANCE_CRITERIA),
                 f"{SPEC_REL} §11 does not print exactly the acceptance criteria, "
                 f"in order")
    # A criterion that states nothing is the same decoration an unasserted
    # fixture expectation is, so each one's text is required to be a sentence.
    bodies = re.split(r"(?m)^(?=\d+[a-z]?\.\s)", section)[1:]
    for body in bodies:
        match = re.match(r"(\d+[a-z]?)\.\s", body)
        if not match:  # pragma: no cover - defensive
            continue
        stripped = " ".join(body[match.end():].split())
        report.check(len(stripped) >= CRITERION_MIN,
                     f"{SPEC_REL} criterion {match.group(1)} states too little to "
                     f"be a requirement: {stripped!r}")


def check_gate_capability_agreement(report: Report) -> None:
    for rel, (header, capability_index, scope_index) in GATE_CAPABILITY_DOCUMENTS.items():
        lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
        headers = [line for line in lines if _table_cells(line) == header]
        report.equal(len(headers), 1,
                     f"{rel} has no single gate table with the pinned header {header}")
        for gate, entry in o.R6_GATES.items():
            rows = [line for line in lines if line.startswith(f"| `{gate}` |")]
            report.equal(len(rows), 1, f"{rel} has no single table row for {gate}")
            if len(rows) != 1:
                continue
            cells = _table_cells(rows[0])
            if not report.equal(len(cells), len(header),
                                f"{rel} row for {gate} has the wrong column count"):
                continue
            # Read the capability *cell*, not the whole row. Reading the row let
            # a cell claim one verdict while the row still contained the word
            # for another, so a row could read "absent" and "proven at host
            # scope" at once and satisfy the check.
            capability = cells[capability_index].lower()
            phrase = CAPABILITY_PHRASES[entry["capability"]]
            report.check(phrase in capability,
                         f"{rel} does not record {gate} as '{phrase}' in "
                         f"{header[capability_index]!r}: got {cells[capability_index]!r}")
            for other, other_phrase in CAPABILITY_PHRASES.items():
                if other == entry["capability"] or other_phrase in phrase:
                    continue
                report.check(other_phrase not in capability,
                             f"{rel} capability cell for {gate} also claims "
                             f"'{other_phrase}' while it is {entry['capability']}")
            report.check(
                not [t for t in FORBIDDEN_SCOPE_TOKENS if t in capability],
                f"{rel} capability/scope cell for {gate} claims a scope R6 never "
                f"reached: {cells[capability_index]!r}",
            )
            # The evidence scope is the column that says how far R6 actually
            # got, and where §5a keeps it in its own column it needs its own
            # assertions: the capability cell can read "not tested" while the
            # scope cell next to it claims an on-device release run, and every
            # other check in this bundle would still pass.
            scope_cell = cells[scope_index].lower()
            scope_phrase = SCOPE_PHRASES[entry["evidence_scope"]]
            report.check(scope_phrase in scope_cell,
                         f"{rel} does not record {gate}'s evidence scope as "
                         f"'{scope_phrase}' in {header[scope_index]!r}: got "
                         f"{cells[scope_index]!r}")
            for other, other_phrase in SCOPE_PHRASES.items():
                if other == entry["evidence_scope"] or other_phrase in scope_phrase:
                    continue
                report.check(other_phrase not in scope_cell,
                             f"{rel} evidence-scope cell for {gate} also claims "
                             f"'{other_phrase}' while it is {entry['evidence_scope']}")
            report.check(
                not [t for t in FORBIDDEN_SCOPE_TOKENS if t in scope_cell],
                f"{rel} evidence-scope cell for {gate} claims a scope R6 never "
                f"reached: {cells[scope_index]!r}",
            )
            # An open gate whose published row states no fail-closed rule, no
            # blocking remainder or no evidence is decoration in exactly the way
            # the registry assertions were written to forbid — they just never
            # reached the table.
            for column in GATE_REQUIRED_PROSE.get(rel, ()):
                cell = cells[header.index(column)]
                report.check(len(cell) >= GATE_PROSE_MIN,
                             f"{rel} row for {gate} states nothing in "
                             f"{column!r}: {cell!r}")
            # A fail-closed cell that states *something* was all the length check
            # above could ask for. What it has to state is the disposition the
            # gate's own evaluator reaches: softening G1's from "terminalises" to
            # "holds" left every fixture green while §5a contradicted §8.5a.
            #
            # A gate with no locally decidable disposition — a native/on-device
            # one — simply skips this block. It used to `continue` out of the
            # whole row instead, which silently exempted that gate from every
            # check below as well: `R6-G8-INSTRUMENTATION-RUN` was the one row
            # whose R5/R6 columns could be swapped and whose prose could claim a
            # closure, precisely because it is the gate that never ran.
            disposition = o.GATE_FAIL_CLOSED_DISPOSITIONS.get(gate)
            if disposition is not None:
                for column in GATE_FAIL_CLOSED_COLUMNS.get(rel, ()):
                    cell = cells[header.index(column)].lower()
                    report.check(disposition in cell,
                                 f"{rel} fail-closed cell for {gate} does not say the "
                                 f"rule is {disposition!r}, which is what its own "
                                 f"evaluator decides: {cells[header.index(column)]!r}")
                    opposite = o.FAIL_CLOSED_OPPOSITE[disposition]
                    report.check(opposite not in cell,
                                 f"{rel} fail-closed cell for {gate} says {opposite!r} "
                                 f"where the oracle decides {disposition!r}: "
                                 f"{cells[header.index(column)]!r}")
            # Each evidence column belongs to one spike and must say so. The
            # criterion/verdict pair is the discriminator, so a swap fails on
            # both cells at once and a rewritten verdict fails on one.
            for stage, column in GATE_EVIDENCE_COLUMNS.get(rel, ()):
                cell = re.sub(r"[`*]", "", cells[header.index(column)]).lower()
                own = o.gate_evidence_marker(entry, stage)
                other = o.gate_evidence_marker(entry, "r6" if stage == "r5" else "r5")
                report.check(own in cell,
                             f"{rel} {column!r} for {gate} does not state {own!r}, "
                             f"which is the verdict the registry records: "
                             f"{cells[header.index(column)]!r}")
                report.check(other not in cell,
                             f"{rel} {column!r} for {gate} states {other!r}, which "
                             f"belongs to the other spike: "
                             f"{cells[header.index(column)]!r}")
            for left, right in GATE_DISTINCT_PROSE.get(rel, ()):
                report.check(cells[header.index(left)] != cells[header.index(right)],
                             f"{rel} row for {gate} prints identical {left!r} and "
                             f"{right!r} cells, collapsing the R5/R6 distinction")
            # The registry is the only place a closure could be declared, and it
            # declares none; no table may imply otherwise. The phrases are
            # matched whole: "fail-closed" and "unclosed gap" are the vocabulary
            # of a gate that is still open, not a claim that it shut.
            for overclaim in ("gate is closed", "now closed", "is discharged",
                              "gate passed", "gate closed"):
                report.check(overclaim not in rows[0].lower(),
                             f"{rel} row for {gate} claims '{overclaim}'")


#: Present-tense phrasings that assert a capability is *absent from the pin*.
#: Each is legitimate only while its gate's capability really is ABSENT or
#: NOT_TESTED. Once R6 demonstrated the capability, the same sentence became a
#: false statement about the pin — even though the gate is still OPEN, because
#: an open gate is a CruxCoach product decision, not a claim about MDK.
ABSENCE_PHRASES = {
    "R6-G2-TRANSPORT-IDENTICAL-RETRY": (
        "pin does not offer a transport-identical retry",
        "pin has no transport-identical retry",
        "pin offers no such retry",
        "no transport-identical retry on the publish-failure branch",
        # Variants that make the *gate* the open question about the pin's
        # ability. R6 answered that question; what the gate governs is whether
        # CruxCoach may rely on the answer.
        "can be satisfied at the pin is precisely what",
        "can be satisfied at the pin is exactly what",
        "is exactly what `r6-g2-transport-identical-retry` leaves open",
        "is precisely what `r6-g2-transport-identical-retry` leaves open",
    ),
    "R6-G3-BROADCAST-LAG-REPLAY": (
        "no harness that forces that overflow",
        "no harness overflows",
        "the gap is real and untested",
    ),
    "R6-G4-EXACTLY-ONCE-SCOPE": (
        "counted no raw rows",
        "no second live-emission window",
        "watched no second emission window",
    ),
    "R6-G6-UNIFFI-FORWARDING": (
        "never real forwarding",
        "no public uniffi call exists",
    ),
    "R6-G7-INBOUND-NATIVE-DELIVERY": (
        "never executes a local/native notification callback",
    ),
}


def check_no_stale_absence_claims(report: Report) -> None:
    """No document may say the pin lacks a capability R6 demonstrated.

    The distinction this guards is the one R6 forced: a gate stays OPEN because
    CruxCoach cannot yet *rely* on a capability, which is a different statement
    from the capability not existing. Rows of the §11.1 gate table are exempt —
    that table's R5 column is a historical citation and must keep saying what R5
    found — and so is any sentence that quotes the old claim in order to retire
    it, since reporting a refutation means repeating what was refuted.
    """
    live = {gate: phrases for gate, phrases in ABSENCE_PHRASES.items()
            if o.R6_GATES[gate]["capability"] not in ("ABSENT", "NOT_TESTED")}
    for rel in BUNDLE_FILES:
        lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
        found: dict[str, list[str]] = {gate: [] for gate in live}
        for number, line in enumerate(lines, 1):
            if line.startswith("| `R6-G"):
                continue  # the §11.1 registry row, R5 column included
            lowered = line.lower()
            # A refutation has to restate the claim it retires. Look at the
            # sentence in context, not the line alone, because these documents
            # wrap: the marker often sits on the line before or after.
            window = " ".join(lines[max(0, number - 2):number + 1]).lower()
            if any(marker in window for marker in
                   ("refut", "no longer", "retire", "supersed", "r5 claim",
                    "r5's claim", "r5's finding", "r5 finding")):
                continue
            for gate, phrases in live.items():
                for phrase in phrases:
                    if phrase in lowered:
                        found[gate].append(f"{number} ('{phrase}')")
        # One assertion per document and gate. Asserting per line would make the
        # run's assertion count a measure of document length rather than of
        # anything checked.
        for gate, hits in found.items():
            report.check(
                not hits,
                f"{rel} states {gate} as absent from the pin at {'; '.join(hits)}, "
                f"but R6 recorded it as {o.R6_GATES[gate]['capability']}",
            )


#: An indicative assertion that a **named** gate is shut. The gate id has to be
#: in the same sentence, so the global overclaim blacklist — which matches fixed
#: phrases like "gate is closed" — cannot see it: the gate's own name sits
#: between the subject and the verb, and "Gate `R6-G1-DURABLE-PROJECTION` is now
#: closed" matched nothing at all.
#:
#: Only the indicative is screened. "requires a closed `R6-G2…`", "were the gate
#: closed tomorrow" and the §8.7b condition row that *defines* the closed-gate
#: condition are all legitimate and stay legal; asserting that a gate is, was or
#: has been closed, discharged or passed is not.
#: Cues that mark what follows as a stated condition rather than a claim. They
#: must appear *before* the gate name in the same sentence, so "a closed
#: `R6-G2-…` is required" is legal while "`R6-G2-…` is closed" alone is not.
CONDITIONAL_CUES = ("only when", "only if", "requires", "required", "require",
                    "would", "were ", "unless", "condition", "if ", "assum")

GATE_CLOSURE_ASSERTION = re.compile(
    r"`(R6-G\d[A-Z0-9\-]*)`[^.;:]{0,80}?\b(?:is|was|are|were|has been|have been)\s+"
    r"(?:now\s+|already\s+|therefore\s+)?(?:closed|discharged|passed|met|proven and closed)\b",
    re.I,
)


def check_no_gate_closure_claims(report: Report) -> None:
    """No document may assert, in prose, that a named gate is shut.

    Every gate's own registry row is bound per document, and the oracle registry
    is asserted OPEN per gate. Neither reaches running prose, and prose is where
    a reader actually meets a verdict: golden §8.10 says "Gate `R6-G1-…` stays
    open" in a paragraph no check owned, so turning that into "is now closed"
    left the whole run green while the bundle announced the opposite of §11.1,
    of its own No-Go and of threat-model forbidden claim 13.

    Table rows are skipped: the §8.7b condition table states "closed" as the
    *condition* it defines, and the §11.1 rows are bound cell by cell already.

    A sentence that states the closure as one item of a **condition** — "admits
    it only when three conditions hold together: … gate `R6-G2-…` is closed, and
    …" — is legitimate and stays legal. Those are recognised by a cue earlier in
    the same sentence, so the cue has to precede the gate, not merely appear
    somewhere near it.
    """
    for rel in BUNDLE_FILES:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        for sentence in prose_sentences(text):
            match = GATE_CLOSURE_ASSERTION.search(sentence)
            if match is None:
                continue
            prefix = sentence[:match.start()].lower()
            if any(cue in prefix for cue in CONDITIONAL_CUES):
                continue
            report.check(
                False,
                f"{rel} asserts that {match.group(1)} is closed, but every gate "
                f"is OPEN: {sentence.strip()[:180]!r}",
            )


#: Fail-closed rules the bundle states as absolutes. A hedge in the same sentence
#: turns "never" into "usually", which is precisely the softening no fixture can
#: see: the oracle keeps deciding the absolute rule while the specification tells
#: a reader it has exceptions.
HEDGE_WORDS = ("normally", "usually", "generally", "typically", "in most cases",
               "by default", "as a rule", "for the most part", "ordinarily")

#: The vocabulary whose sentences may not hedge. Each is a fail-closed outcome
#: the oracle decides unconditionally.
UNHEDGEABLE_TOKENS = ("DELIVERY_UNCLEAR", "PROJECTION_AUTHORITY_INVALID",
                      "RETRY_NOT_TRANSPORT_IDENTICAL", "STORAGE_BOUND_EXCEEDED",
                      "COMMIT_ROLLBACK_AFTER_ACTIVATION", "TIME_UNTRUSTED")


def check_no_hedged_fail_closed_rules(report: Report) -> None:
    """A fail-closed rule may not be stated with a hedge.

    "never automatically sendable" and "normally not automatically sendable" are
    different specifications, and only the first is the one the oracle decides.
    The difference is invisible to every fixture, because the fixtures were
    always asserting the absolute.
    """
    for rel in BUNDLE_FILES:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        for sentence in prose_sentences(text):
            if not any(f"`{token}`" in sentence for token in UNHEDGEABLE_TOKENS):
                continue
            hedges = [word for word in HEDGE_WORDS if word in sentence.lower()]
            report.check(
                not hedges,
                f"{rel} hedges a fail-closed rule with {hedges}: "
                f"{sentence.strip()[:180]!r}",
            )


#: Every closed reason vocabulary the oracle enforces, with the wire section
#: that owns it. Two things are asserted per code, and they fail for opposite
#: reasons:
#:
#: 1. the **wire contract names it** — otherwise the harness enforces a closed
#:    set no specification states, which is the same defect as an unbound
#:    golden value, on the vocabulary that decides every disposition;
#: 2. **some fixture expectation decides it** — otherwise the code is declared
#:    closed, is never produced, is never asserted, and nobody would notice it
#:    describing a state the reducer cannot be in.
#:
#: The second half is deliberately stricter than "the string appears in a
#: fixture file": a code named only in a `note` is documentation, not a
#: decision, so only expectation leaves count.
CLOSED_REASON_SETS = {
    "wire §8.6 diagnostic reason": o.DIAGNOSTIC_REASONS,
    "wire §8.3 hold reason": o.HOLD_REASONS,
    "wire §8.4 terminal fault code": o.TERMINAL_REASONS,
    "wire §8.7b lease-recovery reason": o.LEASE_RECOVERY_REASONS,
    "wire §8.7c handover-refusal reason": o.HANDOVER_REFUSED_REASONS,
    "wire §8.7d post-handover reason": o.POST_HANDOVER_REASONS,
    "wire §8.7b/§8.7e replay-admission reason": frozenset(o.REPLAY_ADMISSION_REASONS),
    "wire §8.4 adapter invalidation reason": frozenset(o.ADAPTER_INVALIDATION_REASONS),
    # §11.2's refusal code was the one vocabulary this rule did not cover: five
    # fixture expectations decided it and the golden vectors printed it, while
    # the wire contract — the normative side — never named it at all.
    "wire §11.2 exactly-once refusal code": o.EXACTLY_ONCE_REFUSAL_REASONS,
    # §8.8's distrust codes decide whether a time-bounded grant stays hidden and
    # whether an unprovable purge is due, so they belong to the same rule rather
    # than to the sentence above it that says "every reason code the oracle can
    # decide". They were the last set that sentence covered in words only.
    "wire §8.8 trusted-time distrust code": o.TIME_UNTRUSTED_REASONS,
}


def expectation_values(positive: dict, negative: dict, scenarios: dict) -> set[str]:
    """Every string a fixture *expectation* leaf claims, list members included."""
    values: set[str] = set()
    for _scope, holder, key, _path, _locator in expectation_slots(positive, negative,
                                                                  scenarios):
        leaf = holder[key]
        if isinstance(leaf, str):
            values.add(leaf)
        elif isinstance(leaf, list):
            values.update(item for item in leaf if isinstance(item, str))
    return values


#: wire §11.2 — the first cell each exactly-once scope is printed as. Pinned so
#: the published table can be read row by row instead of by keyword.
EXACTLY_ONCE_ROW_LABELS = {
    "cruxcoach_reduction": "CruxCoach reduction keyed by typed ids",
    "inner_event_id": "duplicate inner event id",
    "source_message_id": "duplicate source message id",
    "live_emission": "live emission",
    "relay_delivery": "relay delivery",
    "network_delivery": "network delivery",
    "raw_row_count": "raw row count",
    "peer_receipt": "peer receipt",
}


def _published_exactly_once_table() -> dict[str, str]:
    """The wire §11.2 scope/claim table as ``{first cell: lowercased claim}``."""
    rel = "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md"
    lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
    starts = [i for i, line in enumerate(lines) if _table_cells(line) == ["Scope", "Claim"]]
    if len(starts) != 1:
        return {}
    printed: dict[str, str] = {}
    row = starts[0] + 2
    while row < len(lines) and lines[row].startswith("|"):
        cells = _table_cells(lines[row])
        if len(cells) == 2:
            printed[cells[0]] = cells[1].lower()
        row += 1
    return printed


def check_exactly_once_table_case(report: Report, case: dict) -> None:
    """wire §11.2 — the published claim table, bound to the oracle map.

    `EXACTLY_ONCE_SCOPES` was already asserted by the scenario cases, but
    nothing bound the *published* rows to it: flipping one from `forbidden` to
    `permitted` left every fixture green while the specification told a reader
    the opposite of §11.2, threat-model forbidden claim 10 and criterion 42a.
    This is the same binding the outbox-label tables have, for the same reason.
    """
    cid = case["id"]
    declared = case["map"]
    printed = _published_exactly_once_table()
    report.equal(sorted(declared), sorted(o.EXACTLY_ONCE_SCOPES),
                 f"{cid} does not declare exactly the closed claim scopes")
    report.equal(sorted(printed), sorted(EXACTLY_ONCE_ROW_LABELS.values()),
                 f"{cid} wire §11.2 does not print exactly the closed scope rows")
    for scope, want in sorted(declared.items()):
        report.check(scope in o.EXACTLY_ONCE_SCOPES, f"{cid} names an unknown scope {scope}")
        if scope not in o.EXACTLY_ONCE_SCOPES:
            continue
        # The fixture declares it, the oracle decides it and the document prints
        # it; all three have to say the same word.
        oracle = "permitted" if o.EXACTLY_ONCE_SCOPES[scope] else "forbidden"
        report.equal(want, oracle, f"{cid} disagrees with the oracle for {scope}")
        cell = printed.get(EXACTLY_ONCE_ROW_LABELS[scope])
        if not report.check(cell is not None,
                            f"{cid} wire §11.2 prints no row for {scope}"):
            continue
        report.check(oracle in cell,
                     f"{cid} wire §11.2 does not record {scope} as {oracle}: {cell!r}")
        report.check(("forbidden" if o.EXACTLY_ONCE_SCOPES[scope] else "permitted")
                     not in cell,
                     f"{cid} wire §11.2 row for {scope} claims both at once")


def check_exactly_once_claim(report: Report, scenarios: dict) -> None:
    """Every closed claim scope is decided, and the published table is declared.

    A scope nothing exercises is a claim boundary no check owns, and
    `network_delivery` is one of the five the threat model forbids by name.
    """
    cases = [c for c in scenarios.get("runtime_gates", []) if c.get("kind") == "exactly_once"]
    report.equal(sorted({c["state"]["scope"] for c in cases}),
                 sorted(o.EXACTLY_ONCE_SCOPES),
                 "the exactly-once fixtures do not decide every closed claim scope")
    tables = [c for c in scenarios.get("runtime_gates", [])
              if c.get("kind") == "exactly_once_table"]
    report.equal(len(tables), 1,
                 "no single fixture declares the published §11.2 claim table")


def names_token(text: str, token: str) -> bool:
    """Does ``text`` name exactly ``token``, rather than something containing it?

    Plain containment was not enough, and the failure was silent: `_` is a word
    character, so renaming `HOLD_REPLAY_INCOMPLETE` to `HOLD_REPLAY_INCOMPLETE_X`
    throughout the wire contract still satisfied ``code in wire``. The harness
    would have gone on enforcing a closed set the specification no longer states
    — the exact defect this rule exists to catch, hidden by the substring.
    """
    return re.search(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])",
                     text) is not None


#: Closed **schema** vocabularies — the enumerated value spaces of the wire
#: bodies themselves, as opposed to the reason codes above — with the section
#: that owns each.
#:
#: `CLOSED_REASON_SETS` covers the codes the oracle *emits*. These are the sets
#: it *accepts against*, and they were unguarded in the one direction that
#: matters most: widening. A member added here is a value the oracle would
#: silently start admitting on the wire while no specification defines it —
#: `BODY_TYPES` gaining a seventh body, `LEVELS` a fourth relationship level,
#: `REVOKE_REASONS` a fourth reason — and every fixture, table and manifest
#: check stayed green, because nothing a fixture declares changes when a closed
#: set merely grows. That is the mirror image of the defect the reason-code rule
#: already forbids, and it is checked the same way.
#:
#: The requirement is the strong spelling: the wire must name the member **in a
#: code span**, which is how it names all of them today. Bare containment would
#: let a focus area called `core` be satisfied by the word "core" in running
#: prose.
#:
#: Only this direction is checked, and deliberately so. The reason-code rule's
#: second half — "some fixture expectation decides it" — does not transfer:
#: these are input value spaces, and a legitimate member such as the
#: `PLAN_SUMMARY` scope may never be an expected *outcome* anywhere.
CLOSED_SCHEMA_SETS = {
    "wire §5 body type": o.BODY_TYPES,
    "wire §6.2 relationship level": o.LEVELS,
    "wire §6.2 offer value": o.OFFER_VALUES,
    "wire §6.3 grant scope": tuple(o.SCOPE_CODES),
    "wire §6.3 detail scope": o.DETAIL_SCOPES,
    "wire §6.4 revoke reason": o.REVOKE_REASONS,
    "wire §7.3/§7.5 training phase": o.PHASES,
    "wire §7.5 session type": o.SESSION_TYPES,
    "wire §7.5 focus area": o.FOCUS_AREAS,
    "wire §4.5 board ecosystem": tuple(o.ECOSYSTEM_CODES),
    "wire §9.3 required component id": o.REQUIRED_COMPONENT_IDS,
    "wire §9.3 forbidden component id": o.FORBIDDEN_COMPONENT_IDS,
}


def check_closed_schema_vocabulary(report: Report, wire: str) -> None:
    """Every member of a closed schema set must be named by the wire contract."""
    for label, members in CLOSED_SCHEMA_SETS.items():
        report.check(bool(members), f"the {label} vocabulary is empty")
        for member in sorted(members):
            report.check(
                f"`{member}`" in wire,
                f"the wire contract does not name the {label} `{member}`, so the "
                f"oracle admits a value no specification states",
            )


def check_component_id_partition(report: Report, wire: str) -> None:
    """wire §9.3 — the component id sets are a partition, not three lists.

    `LEAF_ONLY_IDS` was read by nothing at all: it is meant to be exactly the
    required ids that are *not* GroupContext state, and pointing it at `0x8004`
    — a state component — contradicted §9.3 with the whole run green. Stating
    the relation is what makes the constant an assertion rather than a comment,
    and it costs one line to also forbid the overlap that would let an id be
    required and forbidden at once.
    """
    required = set(o.REQUIRED_COMPONENT_IDS)
    state = set(o.GROUP_CONTEXT_STATE_IDS)
    report.equal(sorted(o.LEAF_ONLY_IDS), sorted(required - state),
                 "wire §9.3 leaf-only components are not the required ids outside "
                 "GroupContext state")
    report.check(state <= required,
                 f"wire §9.3 GroupContext state components are not all required: "
                 f"{sorted(state - required)}")
    report.check(not required & set(o.FORBIDDEN_COMPONENT_IDS),
                 f"wire §9.3 lists component ids as both required and forbidden: "
                 f"{sorted(required & set(o.FORBIDDEN_COMPONENT_IDS))}")
    for member in sorted(o.LEAF_ONLY_IDS):
        report.check(f"`{member}`" in wire,
                     f"the wire contract does not name the leaf-only component "
                     f"`{member}`")


def check_closed_reason_vocabulary(report: Report, positive: dict, negative: dict,
                                   scenarios: dict) -> None:
    """No closed code may be unstated by the wire or undecided by the fixtures."""
    wire = (REPO_ROOT / "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md").read_text(
        encoding="utf-8")
    exercised = expectation_values(positive, negative, scenarios)
    for label, codes in CLOSED_REASON_SETS.items():
        for code in sorted(codes):
            report.check(names_token(wire, code),
                         f"the wire contract does not name the {label} {code}")
            report.check(code in exercised,
                         f"no fixture expectation ever decides the {label} {code}")
    check_closed_schema_vocabulary(report, wire)
    check_component_id_partition(report, wire)


def check_gate_registry(report: Report, scenarios: dict) -> None:
    for rel in GATE_DOCUMENTS:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        for gate in o.R6_GATES:
            report.check(names_token(text, gate),
                         f"{rel} does not name the open gate {gate}")
    for gate, entry in o.R6_GATES.items():
        report.equal(entry["status"], "OPEN", f"{gate} is not OPEN in the oracle registry")
        report.check(bool(entry["r5_evidence"]), f"{gate} carries no R5 evidence reference")
        report.check(bool(entry["r6_evidence"]), f"{gate} carries no R6 evidence reference")
        report.check(bool(entry["fail_closed"]), f"{gate} carries no fail-closed rule")
    # Every gate must appear in the executed fixture section, or a gate could be
    # quietly reconciled in the registry without any assertion reaching it.
    report.equal(sorted(c["id"] for c in scenarios.get("r6_gates", [])),
                 sorted(o.R6_GATES),
                 "the r6_gates fixtures do not cover every gate in the registry")
    report.equal(sorted(c["id"] for c in scenarios.get("r6_conditions", [])),
                 sorted(o.R6_OPEN_CONDITIONS),
                 "the r6_conditions fixtures do not cover every open condition")
    # At least one release blocker must survive: the release axis is NO-GO for
    # reasons that no adapter gate can discharge, and losing that would let a
    # green gate table read as a shippable result.
    report.check(any(e["kind"] == "release_blocker"
                     for e in o.R6_OPEN_CONDITIONS.values()),
                 "no release blocker is recorded, so the release axis looks clear")
    # The binding R6 record is the approved one, not the superseded attachment,
    # and the provenance is pinned here rather than read from any external file.
    report.equal(o.R6_SOURCE["patch_sha256"],
                 "820335ab329b58d716415b203b8511257546fa31ec2dfc44634db53c8cce0e36",
                 "the R6 source record does not name the approved patch")
    report.equal(o.R6_SOURCE["record"], "GO-NO-GO_1.md",
                 "the R6 source record does not name the approved GO/NO-GO document")
    report.equal(o.R6_SOURCE["mdk_pin"], "101d79946cff6c82d2b849d3b70902a7af7bac08",
                 "the R6 source record does not name the MDK pin")
    report.equal(o.R6_SOURCE["marmot_spec_pin"],
                 "4ad4ae21479c3f3fa9950c6fc4556a76941a62e1",
                 "the R6 source record does not name the Marmot spec pin")
    report.equal(o.R6_SOURCE["overall"], "NO-GO", "R6 is recorded as anything but NO-GO")
    # The spec's own front matter carries the same two pins and the status the
    # whole bundle depends on. If either pin drifted from the provenance record
    # the gate evidence would silently describe a different tree.
    spec_rel = "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md"
    meta = read_front_matter(REPO_ROOT / spec_rel)
    report.equal(meta.get("mdk_pin"), o.R6_SOURCE["mdk_pin"],
                 "the spec front matter's mdk_pin differs from the R6 provenance record")
    report.equal(meta.get("marmot_pin"), o.R6_SOURCE["marmot_spec_pin"],
                 "the spec front matter's marmot_pin differs from the R6 provenance record")
    # R6 is not a product or release approval, so neither field may have moved.
    report.equal(meta.get("status"), "design-review",
                 "the spec front matter no longer says design-review")
    report.equal(meta.get("queue"), "blocked",
                 "the spec front matter no longer says blocked")
    # The annexes are revised as one bundle — the manifest binds them as one —
    # so their metadata must not drift apart from the spec's. A `revised` date
    # left behind on an annex that did change is a small lie about the document,
    # and it is exactly the kind that survives every content-level check.
    for rel in BUNDLE_FILES:
        annex = read_front_matter(REPO_ROOT / rel)
        if not annex:
            continue  # README/INDEX carry no front matter
        report.equal(annex.get("revised"), meta.get("revised"),
                     f"{rel} front matter revised drifted from the spec's")
        report.equal(annex.get("status"), "design-review",
                     f"{rel} front matter no longer says design-review")
        for field, source in (("mdk_pin", o.R6_SOURCE["mdk_pin"]),
                              ("marmot_pin", o.R6_SOURCE["marmot_spec_pin"])):
            if field in annex:
                report.equal(annex[field], source,
                             f"{rel} front matter {field} differs from the R6 record")
    # R6 proved capabilities; it closed no gate. If that ever changes it must be
    # a reviewed decision, never a side effect of editing an evidence string.
    report.check(not any(e["cruxcoach_closure"] for e in o.R6_GATES.values()),
                 "a gate claims CruxCoach closure while FEAT-062 is No-Go")
    check_markdown_tables(report)
    check_outbox_label_tables(report)
    check_ownership_boundary(report)
    check_build_inputs(report)
    check_restated_limits(report)
    check_leakage_matrix(report)
    check_spec_gate_and_criteria_registry(report)
    check_gate_capability_agreement(report)
    check_no_stale_absence_claims(report)
    check_no_gate_closure_claims(report)
    check_no_hedged_fail_closed_rules(report)
    # The send-path vocabulary is normative in wire §8.7c–§8.7e, so the wire
    # document must name every closed value of it. Without this, deleting the
    # row that documents a fail-closed outcome would leave the oracle asserting
    # a rule no specification states. The reason codes themselves are covered
    # more strictly by `check_closed_reason_vocabulary`.
    wire = (REPO_ROOT / "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md").read_text(
        encoding="utf-8")
    for phase in o.EXTERNAL_EFFECT_PHASES:
        report.check(names_token(wire, phase),
                     f"wire §8.7 does not name the phase {phase}")
    for step in o.DRAIN_STEPS:
        if step != "NONE":
            report.check(names_token(wire, step),
                         f"wire §8.7e does not name the drain step {step}")
    # No document may claim an open gate is proven, passed or discharged. This
    # is a blacklist over prose, so it is a backstop and not a proof: the
    # load-bearing guarantees are the structural ones above (the per-gate table
    # row, the provenance binding and the verdict pins below), which do not
    # depend on anticipating a phrasing.
    for rel in BUNDLE_FILES:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8").lower()
        for phrase in ("all eight r6 gates pass", "r6 gates are closed",
                       "gate is proven at the pin", "exactly-once delivery is guaranteed",
                       # Closure phrasings a bundle edit could otherwise slip in
                       # while every per-gate row still reads correctly.
                       "all eight are now `closed`", "all eight are now closed",
                       "gates are therefore proven and closed",
                       "proven and closed", "therefore discharged",
                       "no longer open", "all eight gates are closed",
                       # wire §8.7c: no document may claim the cross-boundary
                       # atomicity that the write-ahead order exists to avoid.
                       "atomically with the transport",
                       "in the same transaction as the transport call",
                       "one atomic step with the transport"):
            report.check(phrase not in text, f"{rel} overclaims: {phrase}")

    check_document_provenance(report)


#: The verdict sentences and forbidden claims the bundle turns on, pinned per
#: document by exact text.
#:
#: These are deliberately exact. `check_gate_registry` already pins the front
#: matter (`design-review`, `blocked`) and the oracle's own gate statuses, but
#: nothing bound the *prose* verdict that every reader actually sees, so a
#: document could announce a release while its front matter still said blocked.
#: The threat model's forbidden claims are pinned for the same reason: they are
#: the only place the bundle states the S9/native and acceptance/delivery
#: separations as prohibitions, and inverting one is exactly the edit no
#: content-level check would notice. Rewording any of these is a reviewed
#: decision, and having to update this tuple is the point rather than a cost.
VERDICT_PINS = (
    ("docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "**FEAT-062 is No-Go for product implementation and release.**"),
    ("docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "All eight R6 gates therefore stay **open**."),
    ("docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "All eight remain `OPEN`."),
    # The one sentence that stops "Published to at least one configured relay"
    # from being claimable on a canonical-branch report alone. The oracle decides
    # it (`receipt_queue_state`, `relay_publication_claim`) and the outbox
    # receipts assert it; nothing bound the sentence that states it.
    ("docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "`MDK_CANONICAL` is reached only when **both** `canonical_at` is set and\n"
     "`accepted_relays` is non-empty."),
    ("docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "R6 is likewise\nan overall **NO-GO** on all four axes."),
    # Threat model §8, the three forbidden claims this bundle's honesty rests
    # on: the exactly-once scope, S9 versus R6/native evidence, and a transport
    # acceptance that nothing correlates.
    ("docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "10. Exactly-once holds anywhere beyond CruxCoach's own idempotent reduction —"),
    ("docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "13. The S9 Python harness is R6, native or on-device evidence, or that a green\n"
     "    harness run closes any gate."),
    ("docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "14. A transport acceptance is a delivery even when nothing correlates it to a\n"
     "    source message id, or that R6's host-scope runtime evidence is native,\n"
     "    on-device or release evidence."),
    # FEAT-062 §10.8 is where the product spec — not the threat model — states
    # the honest-UI rule a reader meets. `check_outbox_label_tables` forbids the
    # false words inside a *label*; nothing reached the sentence that says why,
    # so §10.8 could invert into a peer-receipt and exactly-once claim while
    # every label stayed clean.
    ("docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "v1 has no peer receipt, and nothing claims exactly-once delivery: the only\n"
     "   once-ness CruxCoach provides is that the same event reduces once locally,\n"
     "   however often it arrives."),
    # Restore mints no identifier. `evaluate_restore` decides it and the restore
    # scenarios assert it, but a minted id would silently address a *different*
    # remote row, so the two documents' MUST NOT is pinned as well.
    ("docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "restore MUST NOT mint a new one"),
    ("docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "a restored\n  value is exactly the backed-up value or `NULL`, never newly minted."),
)


#: The requirement areas whose normative rules must stay pinned. Closed and
#: asserted in both directions against :data:`NORMATIVE_RULE_PINS`, so an area
#: cannot lose its last pin and quietly stop being covered.
NORMATIVE_RULE_AREAS = (
    "broadcast-lag-replay",
    "data-minimisation",
    "delivery-states",
    "durable-projection",
    "exactly-once-boundary",
    "generation-bootstrap",
    "grant-retirement-revocation",
    "honest-ui",
    "inbound-isolation",
    "invalidation-reorg",
    "mdk-capability-honesty",
    "offline-restore",
    "queue-and-status",
    "r5-r6-reconciliation",
    "security-claims",
    "spike-gate-discipline",
    "transport-identical-retry",
)

#: The load-bearing normative rules of this bundle, pinned by exact text.
#:
#: Everything structural in this harness binds a *shape*: a table row, a closed
#: vocabulary, a gate cell, a fixture expectation. The rules those shapes serve
#: are stated in running prose, and prose was bound by almost nothing — only the
#: dozen verdict sentences of :data:`VERDICT_PINS`. So the bundle's central
#: claims could be inverted outright with every fixture, oracle, table and
#: vocabulary check green:
#:
#: - §8.5a's "MUST NOT re-read the MDK raw app store as authority" into "MAY",
#:   which is the single rule this whole specification exists to state;
#: - §8.7a's "a retry MUST be transport-identical" into "SHOULD", and
#:   "a re-queued payload is a new event, never a retry" into its opposite;
#: - §8.4's one-to-one `source_message_id` → `inner_event_id` mapping into a
#:   many-to-many one, and its zero-/multi-match terminality into "ignored";
#: - §10's "never revive old data" and "never extends the purge deadline" into
#:   permissions; §2.4's isolation `MUST NOT` into `MAY`.
#:
#: Only `MANIFEST.json` moved, and the README documents `--write-manifest` as
#: the answer to *any* bundle edit — so the whole-file digest is precisely not a
#: semantic check. Each entry below is the published statement of a rule the
#: oracle implements, which is the same "two published statements of one rule"
#: class the table bindings already cover; this is that binding where the second
#: statement happens to be a sentence rather than a row.
#:
#: What this does **not** do, stated plainly: a pin proves the rule is still
#: stated, not that nothing elsewhere contradicts it. The structural bindings
#: carry that weight. Rewording a pinned rule is a reviewed decision, and having
#: to update this tuple is the point rather than a cost.
NORMATIVE_RULE_PINS = (
    # --- the durable CruxCoach projection is the authority, never MDK's raw store
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "**The MDK raw app store is not a CruxCoach authority.**"),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "CruxCoach MUST hold its own durable projection — the acknowledged inbox of\n"
     "  §8.5 plus the reduced application state — and MUST NOT re-read the MDK raw\n"
     "  app store as authority for anything. A client that does is terminal with\n"
     "  `PROJECTION_AUTHORITY_INVALID`."),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "An inbox item that is gone before its host ACK is terminal with\n"
     "  `PROJECTION_AUTHORITY_INVALID` for that generation. Nothing is reconstructed,\n"
     "  interpolated or guessed from a partial view."),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A retention-independent long-lived projection does **not** exist at the pin."),
    # §8.5 is the ACK and crash boundary itself, and it was pinned nowhere: the
    # host ACK could be moved ahead of the commit it exists to follow, the
    # cross-store transaction §7.2 and the threat model both forbid could be
    # permitted here, and retention could be allowed to take an unacknowledged
    # item — each the exact rule the durable projection rests on.
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The dedicated adapter inbox persists snapshot/live/replay items independently\n"
     "of MDK message retention until host ACK."),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "CruxCoach writes accepted or held state and the source-to-inner mapping in one\n"
     "SQLCipher transaction. It sends host ACK only after commit. A crash before ACK\n"
     "replays; a crash after ACK sees an idempotency row. No transaction spans MDK and\n"
     "CruxCoach stores. More than five epoch advances and pruning MUST NOT remove an\n"
     "unacknowledged item."),
    ("durable-projection", "docs/specs/social/README.md",
     "MDK owning the transport does **not** make MDK's raw application store CruxCoach\n"
     "state."),
    ("durable-projection", "docs/specs/social/README.md",
     "the raw store is never read as authority: CruxCoach keeps its own durable\n"
     "projection — the acknowledged inbox plus reduced state — and an item lost before\n"
     "its host ACK terminalises that generation instead of being reconstructed."),

    # --- broadcast lag is caught up from durable state, never from live delivery
    ("broadcast-lag-replay", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "**Live delivery is never a correctness input.**"),
    ("broadcast-lag-replay", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Correctness MUST rest on the durable snapshot/replay path alone. A live item\n"
     "  is an optimisation."),
    ("broadcast-lag-replay", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A live gap that durable replay has not yet closed holds the affected objects\n"
     "  with `HOLD_REPLAY_INCOMPLETE`."),
    ("broadcast-lag-replay", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A gap whose bytes retention already removed is terminal with\n"
     "  `PROJECTION_AUTHORITY_INVALID` under the rule above."),
    ("broadcast-lag-replay", "docs/specs/social/README.md",
     "Live\nsubscription delivery is an optimisation on top of that durable path, never a\n"
     "correctness input."),

    # --- a retry is transport-identical or it is not a retry
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "an intended inner event MUST NOT become bound to more than one **distinct**\n"
     "  `source_message_id`; observing the same transport message twice is one id, not\n"
     "  two;"),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "a retry after an unclear or partial first delivery MUST be\n"
     "  **transport-identical**: the identical transport message is re-driven."),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "re-encrypting or re-queueing the inner payload produces a **new** transport\n"
     "  message. It is a new event, never a retry of the same inner id. Presenting it\n"
     "  as one is terminal with `RETRY_NOT_TRANSPORT_IDENTICAL`;"),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "binding a second distinct source id to one inner event id is terminal with the\n"
     "  same code, whatever produced it."),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Until they are all met the fail-closed behaviour is exactly the one above: an\n"
     "unclear or partial first delivery is re-driven identically or not at all."),
    # §8.7c's write-ahead order itself. §8.7b's phase *table* is bound, and the
    # send-handover scenarios drive the crash windows, but the two numbered steps
    # that fix the order — and the step-3 rule that an unestablished commit
    # result authorises nothing — were pinned nowhere. Inverting them reopens the
    # precise window the section exists to close: a crash after a possible
    # external effect but before the phase commit, leaving a row that still reads
    # `PRE_EXTERNAL_EFFECT` and is automatically requeued and driven a second time.
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "1. the row's phase is committed to `EXTERNAL_EFFECT_UNCLEAR` in a local\n"
     "   transaction of its own, **before** any handover is attempted;\n"
     "2. only once that commit is **proven** may the transport be called at all;"),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "3. a commit that was never attempted, that failed, or whose result the caller\n"
     "   cannot establish authorises nothing. Unknown is not committed, and no\n"
     "   handover may begin;"),
    # The implication the whole of §8.7b rests on, and the one automatic requeue
    # is only safe because of it.
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "> if a recovering worker durably reads `PRE_EXTERNAL_EFFECT`, then the\n"
     "> write-ahead commit was never proven, so the transport was never called, so\n"
     "> nothing can have left the device."),
    # §8.7's recovery rule, and the reason §8.4's one-to-one correlation stays
    # decidable: reclaiming a lease may never produce a second identity.
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "outcomes. No recovery path ever mints a second inner event id;"),
    # The fail-closed default for the phase column. Reading an undecodable phase
    # as `PRE_EXTERNAL_EFFECT` instead is exactly the under-approximation §8.7c
    # forbids: it would automatically requeue a row that may already have had
    # external effect, which is the one thing the asymmetry exists to prevent.
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A stored phase that\ncannot be read or decoded is treated as "
     "`EXTERNAL_EFFECT_UNCLEAR`, because the\nunclear case is the fail-closed one."),
    # §8.7e's verdict on its own last row. The table's conjuncts are bound
    # above, but the sentence that says the route is *unreachable at the pin*
    # is what a reader takes the fail-closed state from, and "unreachable" is
    # exactly what `evaluate_drain_step` computes for that step today.
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The last row is defined and unreachable, now for two independent reasons."),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Closing `R6-G2-TRANSPORT-IDENTICAL-RETRY` on its own would consequently **not**\n"
     "make the replay route usable here: a successful replay would still land on\n"
     "§8.7d's `ACCEPTED_WITHOUT_SOURCE_RECEIPT` and stay unclear."),

    # --- invalidation/reorg, including the source↔inner mapping it rests on
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Every adapter invalidation must map exactly one `source_message_id` to exactly\n"
     "one stored kind-1220 `inner_event_id` and a closed reason."),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Invalidation of any\nstored event, applied or held, makes the whole generation "
     "terminal immediately,\nhides all data and requires a fresh group/generation. A "
     "zero-match or multi-match\nmapping has the same result."),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "There is no partial recomputation after invalidation."),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Locally initiated revoke, downgrade, block, removal and purge\n"
     "tombstones remain sticky."),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The adapter is never\nasked to supply the id from context, and CruxCoach never "
     "infers one."),
    # §8.4a's *outcomes* are bound through golden §8.4's table, but the wire's
    # own statement of the rule was not: a generation could be allowed to
    # un-become active, which is the one thing an authority boundary forbids.
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "3. If the rolled-back commit is a promotion, an admin-policy change, a `0x8001`\n"
     "   profile change or the `0x800c` transition **and** the generation had already\n"
     "   reached `ACTIVE`, it is terminal with `COMMIT_ROLLBACK_AFTER_ACTIVATION`. A\n"
     "   generation cannot un-become active"),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "4. Before activation, the same rollback returns the generation to\n"
     "   `PROVISIONING`; it is not terminal by itself"),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "5. Safety-lowering tombstones stay sticky through every one of these. A rollback\n"
     "   never revives a revoked grant, an old offer head or purged data."),

    # --- the explicit delivery/confirmation states, and the unclear one
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The unclear label is the honest wording for §8.7b: it states that the outcome of\n"
     "one first delivery was never established and that nothing is being re-sent for\n"
     "it. It must not be softened into *queued*, because the row will never drain by\n"
     "itself, and it must not be hardened into *sent* or *not sent*, because neither\n"
     "is known."),
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A send that *was* authorised is never re-attempted\n"
     "  automatically (§8.7d), and a `DELIVERY_UNCLEAR` row (§8.7b) is on no schedule\n"
     "  at all"),
    # Both label tables are bound row by row and are total over these seven, but
    # the column rule that *declares* the closed set was not: an eighth state
    # could be printed here — `DELIVERED` is the obvious one — and it would carry
    # no label, no oracle behaviour and no forbidden-claim screen.
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "| `state` | exactly one of `QUEUED`, `IN_FLIGHT`, `SENT_LOCALLY`, "
     "`MDK_CANONICAL`, `FAILED`, `DELIVERY_UNCLEAR`, `SUPERSEDED` |"),
    # §8.7's boot rule. A monotonic deadline minted in a previous boot is
    # meaningless in this one, so a foreign-boot lease is expired outright.
    # Nothing pinned that: re-admitting the `lease_until` comparison would let a
    # rebooted device treat a still-"live" foreign lease as held and leave the
    # row untouched — or, with a passed deadline, silently take a row whose
    # external-effect phase is the only thing entitled to decide it.
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "an `IN_FLIGHT` row whose `lease_boot_id` differs from the current boot has an\n"
     "  expired lease, **without comparing `lease_until` at all**"),
    # A `FAILED` row that may be pruned by age is how an unsent revoke disappears
    # silently, which is the safety regression §8.7 names in the same breath.
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "the row is `FAILED` and stays visible; a `FAILED` row is never pruned by age\n"
     "  alone, because silent disappearance would hide an unsent revoke."),
    # Supersession is the one way a row leaves the send path without being sent,
    # so the replacement has to be durable in the same committed transaction;
    # relaxing it drops the original with nothing standing in for it.
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "a row may be `SUPERSEDED` only when the superseding row is already durably\n"
     "  enqueued in the same committed transaction."),

    # --- the narrow exactly-once boundary
    ("exactly-once-boundary", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "CruxCoach claims exactly-once **local reduction**, and nothing else:"),
    ("exactly-once-boundary", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A refusal is a\nstatement about the **claim**, never about the pin: the five "
     "forbidden scopes\nstay forbidden whatever an adapter is later shown to do."),
    ("exactly-once-boundary", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "`R6-G4-EXACTLY-ONCE-SCOPE` governs the evidence and stays open; this section\n"
     "governs the claim. Closing that gate would not move a single row"),

    # --- revocation, retirement and the epoch/generation boundary
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The revoked materialised data is hidden immediately and purged within 24 hours."),
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "An increase after downgrade and a new grant after revoke/expiry never revive old\n"
     "data."),
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "a narrower or\nnewer grant never extends the purge deadline of fields already "
     "removed under an\nolder grant, and never cancels a running purge."),
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The maximum grant duration (90 days from issue) and the post-expiry purge\n"
     "deadline (90 days after expiry) are two distinct 90-day windows and MUST NOT be\n"
     "conflated."),
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A revoke\nauthored by the recipient, or naming a grant published by the other "
     "endpoint, is\nrejected and occupies no sequence."),
    ("grant-retirement-revocation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "A grant request is not part of v1. There is no request body, and a party holding\n"
     "no valid grant cannot cause one."),

    # --- offline and restore
    ("offline-restore", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "On a\nnew device, application data is inactive and read-only, every restored "
     "outbox\nrow is unsendable, and the outbox drains nothing. Blocks and terminal "
     "tombstones\nremain sticky."),
    ("offline-restore", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The same rule governs product size: an unset or cross-brand `product_size_id`\n"
     "stays `NULL`, makes the affected item ineligible and fails closed. It is never\n"
     "defaulted to a plausible value."),
    ("offline-restore", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "What\nit does state is that restoring any backup grants no sharing rights: "
     "restored\nstate is closed, and no identifier is minted by restore."),
    ("offline-restore", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "`foreign_keys = ON` MUST be active and verified for the production SQLCipher\n"
     "connection before any FEAT-062 migration or query runs, and a failed activation\n"
     "or verification MUST fail application startup."),

    # --- what the UI may honestly say
    ("honest-ui", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The published label is earned only by a\npersisted typed receipt naming at least "
     "one concrete accepted relay endpoint; it\nmeans neither *all configured relays* "
     "nor that a peer received or read the\nevent. v1 has no peer ACK."),
    ("honest-ui", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Every one of the four visible labels is shown together with **Not\n"
     "peer-confirmed**."),

    # --- inbound isolation, which is R6-G7's fail-closed rule in prose
    ("inbound-isolation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Kind 1220 and its Marmot group MUST NOT be exposed through `send_text`, a chat\n"
     "timeline, chat list, unread state, conversation preview, Markdown, mentions,\n"
     "reply/reaction/edit UI or NewMessage notification. Social code MUST NOT use\n"
     "`NostrRelayPool` or a second transport cursor."),

    # --- which spike found what, and what it did and did not settle.
    # §5a's table is bound cell by cell and the registry is asserted per gate;
    # neither reaches §0, which is where a reader actually meets the R5/R6
    # story. Inverting R5's retention finding, dropping R6's refutation, or
    # quietly turning "both recorded NO-GO" into a partial GO left every other
    # check green.
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Two throwaway adapter spikes have been executed against exactly these pins, and\n"
     "both recorded **NO-GO** on every axis they judged"),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "It established that active group retention really does\n"
     "prune raw application rows, so the MDK raw app store is **not** long-lived\n"
     "CruxCoach state and a retention-independent durable projection is mandatory and\n"
     "missing."),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "**R6** has since run, and it changed what is known — without changing the\n"
     "verdict."),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "it explicitly **refuted** the R5 claim\nthat the pin has no transport-identical "
     "retry"),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "its own criterion 4 is **not met** — the custom-reorg trigger is injected, not\n"
     "  relay- or fork-driven, so the R5 reorg gap stays open and that alone blocks\n"
     "  the spike;"),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "the on-device instrumentation run was **never executed** (no device, no\n"
     "  emulator, no virtualisation), so every capability above is host-scope\n"
     "  evidence and nothing is native, on-device or release evidence;"),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "R5's NO-GO on moving the product design to `design-locked` is **untouched**:\n"
     "  R6 decided nothing about retention or a durable projection, so the design\n"
     "  stays `design-review`;"),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "the resume path emits no\n  `PublishedApplicationMessage`, so a message whose "
     "*first* accepted delivery\n  happens during resume never gets its source-id "
     "correlation or its retention\n  finalisation."),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Nothing in this repository is R6 evidence, and the specification harness\n"
     "described below is explicitly not a substitute for it."),

    # --- no absent MDK capability may be described as present.
    # `check_no_stale_absence_claims` guards the opposite direction — a
    # capability R6 *proved* still being called absent. Nothing guarded this
    # one, so §0's inventory of what the pin does not have could be rewritten
    # into an inventory of what it does.
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "MDK has no public or UniFFI outbound API whose caller supplies an inner\n"
     "   event's kind, tags and content."),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "The FFI record loses `source_message_id_hex` and is\n"
     "   not a dedicated inbox whose retention is independent of MDK pruning until a\n"
     "   host acknowledgement."),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "`SendSummaryFfi` supplies no durable inner-to-source association."),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Its `published`\n   field counts transport reports, not accepted relays, and the "
     "accepting\n   endpoints on `TransportPublishReport.accepted` do not cross the "
     "FFI. Nothing\n   in it is source evidence or relay evidence."),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "`AppMessageInvalidated` identifies the MDK/source message id, not the inner\n"
     "   event id, and the pin persists only the invalidated boolean"),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "No adapter and no product implementation of this feature exists in this\n"
     "repository."),
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "It executes no Kotlin, Rust, MDK, Marmot or on-device\n"
     "code, discharges none of the eight gates, and does not lift the No-Go."),
    ("mdk-capability-honesty", "docs/specs/INDEX.md",
     "that\nharness is a Python spec oracle, runs no product, native or on-device code, and\n"
     "discharges none of the eight gates."),

    # --- the registry entry point: may this feature be worked on at all.
    # `check_published_binding_case` binds the FEAT-062 row's status cell and
    # the spec's own front matter, and asks only that the token `blocked`
    # appear *somewhere* in the file. It appears twice, so rewriting "its queue
    # is explicitly `blocked`" into `open` left the other occurrence to satisfy
    # the check — in the one file whose whole job is telling an implementer
    # whether to pick this up.
    ("queue-and-status", "docs/specs/INDEX.md",
     "It is `design-review`, **not** design-locked, and its queue is explicitly\n"
     "`blocked`. It is **not queued for the autonomous implementer** and must not be\n"
     "picked up by one: `blocked` is not a work item. Its own §0 records a **No-Go\n"
     "verdict for implementation and release**."),

    # --- the same R5/R6 story where the registry states it. Nothing reached
    # INDEX.md's prose: "both returned NO-GO" could become a partial GO, and
    # "all eight gates stay open" could be inverted outright — the phrase
    # blacklist matches fixed spellings and this one names no gate id.
    ("r5-r6-reconciliation", "docs/specs/INDEX.md",
     "Two such spikes, **R5** and **R6**, have been executed against these pins, and\n"
     "both returned **NO-GO** on every axis they judged, including the move of the\n"
     "product design to `design-locked`."),
    ("r5-r6-reconciliation", "docs/specs/INDEX.md",
     "All eight named R6 gates of FEAT-062 §5a therefore\n"
     "stay open, and nothing in this repository is R6 evidence."),
    ("durable-projection", "docs/specs/INDEX.md",
     "R5 established that active group retention\n"
     "prunes raw application rows, so the MDK raw store is not long-lived CruxCoach\n"
     "state; that remains the reason the product design may not be locked, and R6\n"
     "decided nothing about it."),

    # ------------------------------------------------------------------
    # The product specification and the threat model, where a reader who
    # never opens the wire contract meets the same rules.
    #
    # Every pin above this line is in the wire contract, the README or the
    # registry. FEAT-062 and the threat model restate those rules for the
    # audience that decides whether to build this — and they were bound by
    # almost nothing: §7.2 could claim a cross-store transaction, §9 could
    # make a restored row sendable, §10.7 could let a sharing event into
    # chat, §4.1 could make the computed `NOT_ESTABLISHED` level close every
    # generation the moment it opens, criterion 42a could permit the
    # exactly-once claim it exists to forbid, and PT-26, PT-37, PT-38, PT-39
    # and PT-40 could each be inverted into the risk they name — with the
    # gate tables, the fixtures, the vocabularies and the manifest all green.
    # ------------------------------------------------------------------

    # --- the durable projection, stated where the product states it
    ("durable-projection", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "No transaction spans MDK storage and the CruxCoach SQLCipher database."),
    ("durable-projection", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "The MDK raw application store is **not** that durable copy."),
    ("durable-projection", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "an item that disappears before its host ACK terminalises\n"
     "that generation rather than being reconstructed."),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "the MDK raw app store is never an authority; CruxCoach keeps its own durable "
     "projection and acknowledged inbox; an item lost before host ACK terminalises "
     "with `PROJECTION_AUTHORITY_INVALID` instead of being reconstructed"),
    ("durable-projection", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "unacknowledged inbox items are durable independently of MDK retention and "
     "pruning"),

    # --- broadcast lag, stated where the product states it
    ("broadcast-lag-replay", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "For the same reason, live subscription delivery is an optimisation and never a\n"
     "correctness input."),
    ("broadcast-lag-replay", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "A live gap holds the affected objects until durable replay\n"
     "closes it, and a gap whose bytes retention already removed is terminal."),
    ("broadcast-lag-replay", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "correctness rests on the durable snapshot/replay path; a live gap holds with "
     "`HOLD_REPLAY_INCOMPLETE`; a gap retention already pruned is terminal"),

    # --- one inner event, one source id, one identical re-drive
    ("transport-identical-retry",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "A receipt binds **at most one distinct** source message id."),
    ("transport-identical-retry",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Re-encrypting or re-queueing the payload creates a new event and may\n"
     "never be presented as a retry of the same inner id; binding a second distinct\n"
     "source id to one inner event id is terminal."),
    ("transport-identical-retry", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "a receipt binds at most one distinct source message id; a retry is the "
     "identical transport message; a re-encrypted or re-queued send is a new event "
     "and is terminal with `RETRY_NOT_TRANSPORT_IDENTICAL` if presented as a retry"),

    # --- what a send state means, and what no recovery may do to it
    ("delivery-states", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- **Published to at least one configured relay** — MDK reported the event on the\n"
     "  canonical branch **and** the durable typed receipt names at least one concrete\n"
     "  accepted relay endpoint. It never means all configured relays;"),
    ("delivery-states", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "The row stays visible and is never pruned by age, it\n"
     "  claims neither delivery nor non-delivery, it is never sent automatically, and\n"
     "  only an explicit user decision leaves it"),
    ("delivery-states", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "26h. Reclaiming an expired lease never requeues a row by itself."),
    ("delivery-states", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "no fixture, criterion or string reverts it to\n"
     "     `PRE_EXTERNAL_EFFECT`, retries it automatically, requeues it, or assumes a\n"
     "     synchronous durable proof that no external effect began."),
    ("delivery-states", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "the unclear label claims neither delivery nor non-delivery and offers no "
     "automatic re-send"),

    # --- the claim boundary, in the criterion and the threat that own it
    ("exactly-once-boundary",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "42a. No user-visible string, label, log line or test name claims exactly-once\n"
     "     delivery, relay deduplication, a peer receipt or a read receipt."),
    ("exactly-once-boundary", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "the only permitted claim is idempotent local reduction keyed by typed ids; live "
     "emission, relay delivery, network delivery, raw row counts and peer receipts "
     "are explicitly forbidden claims"),

    # --- invalidation is terminal, with no partial recomputation
    ("invalidation-reorg", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- Any MDK invalidation of a stored kind-1220 event, including a held event, makes\n"
     "  the generation terminal immediately."),
    ("invalidation-reorg", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "any invalidation of a stored kind-1220 event terminalises and hides the whole "
     "generation; there is no partial recomputation; local safety reductions remain "
     "sticky"),

    # --- what ends a relationship, and what a later grant may never undo
    ("grant-retirement-revocation",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Only an explicitly received, valid and applied offer whose body carries\n"
     "`offer: \"NONE\"` terminates the generation. The computed `NOT_ESTABLISHED` level\n"
     "never does"),
    ("grant-retirement-revocation",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "A revoke occupies the same linear slot, names its current grant id and is\n"
     "terminal for that grant."),
    ("grant-retirement-revocation",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "A new grant never stops the purge of data derived under an older grant, and\n"
     "never extends the deadline for fields already dropped."),

    # --- restore grants nothing, and the production connection is checked
    ("offline-restore", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- On a new device, restored application rows are inactive and read-only."),
    ("offline-restore", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- Restored generation bindings, grants and outbox rows are unsendable; the\n"
     "  outbox drains nothing."),
    ("offline-restore", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "`foreign_keys = ON` must be active and verified on the production SQLCipher\n"
     "connection before any FEAT-062 migration or query runs."),
    ("offline-restore", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "backup contains no MLS state; restored app data read-only/inactive; outbox "
     "sends nothing; row ids are preserve-or-`NULL` and never minted; fresh explicit "
     "group/generation required"),

    # --- the UX truths a screen may not soften
    ("honest-ui", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "3. Revocation, removal and disband cannot delete a peer's existing plaintext\n"
     "   copy, and either admin can disband at any time."),
    ("honest-ui", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "4. MLS sender authentication proves an account authored bytes, not that a climb,\n"
     "   grade or plan is true."),
    ("honest-ui", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "the published label requires a persisted typed receipt naming a concrete "
     "accepted relay endpoint, never a report count"),

    # --- isolation, where the product states it
    ("inbound-isolation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "7. A sharing event never enters chat UI and never raises NewMessage push."),
    ("inbound-isolation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "46. Spies observe zero social `NostrRelayPool` calls, zero second social cursors,\n"
     "    and zero chat timeline/list/unread/Markdown/NewMessage effects."),

    # --- the four deep sub-conditions, and what a green Python run is not
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "1. crash-safe correlation of `inner_event_id` to every `source_message_id`,\n"
     "   including a crash after external publication but before host persistence;"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "2. a restart-durable invalidation reason, or an explicit FAIL for that gate;"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "3. defined offline/queued receipt behaviour — refuse queueing, or persist\n"
     "   `Queued { intent_id, inner_event_id }` and bind its one source id later;"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "4. a proven cross-store crash rail, with the protocol-level ACK never\n"
     "   reinterpreted as the host ACK."),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
     "It executes no Kotlin, no Rust, no MDK, no Marmot and nothing on\n"
     "a device, so a green run is evidence about this specification only."),
    # Criterion 50b requires the harness to be a spec oracle *and to be labelled
    # as one*. The sentence above survives relabelling it a conformance suite,
    # which is the half a reader takes the authority of the run from.
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
     "The harness is a **spec oracle** — a second, independent Python statement of the\n"
     "wire contract."),
    ("mdk-capability-honesty", "docs/specs/social/README.md",
     "The harness is a *spec oracle* written in Python. It\n"
     "executes no Kotlin, no Rust, no MDK, no Marmot and nothing on a device."),
    # Criterion 50c and its README twin are the bundle's claim that *every*
    # fixture expectation is really asserted. Nothing bound the word "every":
    # both could be softened to a sample, which is the one edit that would make
    # the mutation battery's own guarantee — and this count — meaningless.
    ("mdk-capability-honesty", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "50c. The harness proves its own fixtures are assertions: it mutates **every**\n"
     "     expectation field in every fixture file, one at a time, and requires the\n"
     "     scoped check to fail. An expectation that survives its own mutation fails\n"
     "     the run, and nothing is sampled or skipped."),
    ("mdk-capability-honesty", "docs/specs/social/README.md",
     "the opposite on every run: it takes **every** expectation field in\n"
     "`positive-vectors.json`, `negative-corpus.json` and `reducer-scenarios.json`,\n"
     "mutates it one at a time, re-runs the checks that own that fixture location, and\n"
     "requires them to fail."),
    ("mdk-capability-honesty", "docs/specs/social/README.md",
     "Nothing is sampled, nothing is capped and\nnothing is skipped; the run prints "
     "the number of mutation slots it covered."),
    # The threat model's copy of the four deep sub-conditions is pinned above.
    # The wire contract is where they are *normative*, and it was unbound: §11
    # property 4 could permit the cross-store transaction §7.2, §8.5 and the
    # threat model all forbid, with every gate row still reading correctly.
    # §11's required-adapter-contract table is the definition of what the spike
    # must build. It was unbound, so the two rows carrying the durable
    # projection's preconditions could be shortened into a contract MDK already
    # satisfies — retiring `R6-G1-DURABLE-PROJECTION`'s substance while the gate
    # row itself still read `OPEN`.
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "| inbox | dedicated snapshot/live/replay with full provenance and "
     "retention-independent pre-ACK persistence |"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "| ACK | callable only after durable CruxCoach commit, and revision-bound so a "
     "later invalidation reopens the row |"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Four properties are explicitly **not** provable at the pin without exceeding the\n"
     "agreed patch budget, and each is a binary No-Go gate rather than an assumption:"),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "1. **Crash-safe outbound correlation.** `inner_event_id` and its one resulting\n"
     "   `source_message_id` must stay correlated across every crash point, including\n"
     "   the point where the event was already published externally but not yet\n"
     "   persisted by the host. A purely synchronous in-memory receipt association is\n"
     "   a fail."),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "2. **Durable invalidation reason.** The pin persists the invalidated boolean but\n"
     "   emits the reason only in memory after the canonical commit. The reason must\n"
     "   become restart-durably correlated, or the gate fails. No exactly-once\n"
     "   invalidation notification may be claimed."),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "3. **Offline/queued receipt.** A queueing send path must either refuse offline\n"
     "   queueing outright or persist `Queued { intent_id, inner_event_id }` and later\n"
     "   bind that one source id restart-durably (§8.7a)."),
    ("mdk-capability-honesty", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "4. **Cross-store crash rail.** No transaction spans the engine store, the\n"
     "   adapter store and the CruxCoach database. Every crash point of the rail\n"
     "   `engine canonical commit -> repeatable pending ingress -> adapter inbox\n"
     "   revision -> CruxCoach transaction -> host ACK` must be proven. The existing\n"
     "   protocol-level ACK MUST NOT be reinterpreted as the host ACK."),

    # --- which stage this bundle is, where each document says so
    ("r5-r6-reconciliation", "docs/specs/social/README.md",
     "This bundle is the **S9** specification stage. **R5** and **R6** were executed\n"
     "throwaway adapter spikes, and both returned NO-GO on every axis they judged."),
    ("r5-r6-reconciliation", "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md",
     "It is the **S9** specification stage. **R6** — the executed real-adapter spike —\n"
     "is a different thing entirely."),
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "`resume_outbound_fanouts` never emits `PublishedApplicationMessage`, so an app\n"
     "  message whose **first** accepted delivery lands during resume never runs\n"
     "  `finalize_published_app_message_source_retention`."),
    # G8 is why every other gate's evidence stays host-scope, so "remains
    # untested" is load-bearing for all eight. Nothing bound it: §12 could
    # announce the on-device run that never happened while the §5a and §11.1
    # rows still printed "not tested", and no per-gate screen reads §12.
    ("r5-r6-reconciliation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- `R6-G8-INSTRUMENTATION-RUN` remains untested, and it was untested in R6 for a\n"
     "  stronger reason than in R5: the host has no device, no emulator package, no\n"
     "  AVD and no virtualisation at all. Untested is not a soft pass, and it is the\n"
     "  reason every other gate's evidence is recorded as host-scope."),
    # R6's two non-gate results. `R6_OPEN_CONDITIONS` records them and the
    # fixtures assert them, but the sentence a release reader actually meets was
    # unbound: rewriting "five" to "zero" retired a red release gate outright,
    # and neither sentence names a gate id for the per-gate screens to catch.
    ("r5-r6-reconciliation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Two further R6 results are recorded because a release decision depends on them,\n"
     "even though neither is a gate in this table: five pre-existing `marmot-app`\n"
     "relay integration tests fail deterministically — at the bare pin and at\n"
     "`origin/master` alike, so provably no R5 or R6 regression, but a red gate all\n"
     "the same — and the consumer APKs were not reproduced, because that project is\n"
     "not part of MDK and was not supplied."),

    # --- the registry's reservation, which is what stops an id being reused
    ("queue-and-status", "docs/specs/INDEX.md",
     "**FEAT-052 – FEAT-061 are taken and MUST NOT be reused.**"),
    ("queue-and-status", "docs/specs/INDEX.md",
     "That series is **design input only**. Nothing in it is implemented, tracked, or\n"
     "endorsed by this registry"),

    # --- who may create a generation, and what makes one terminal at birth
    ("generation-bootstrap", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- Only `endpoint_low` may create the relationship group."),
    ("generation-bootstrap", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "- A Welcome whose actual creator is not `endpoint_low` is not activated. That\n"
     "  derived generation is terminally rejected."),
    ("generation-bootstrap", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "If either\nclient ever discovers more than one nonterminal sharing group for the same\n"
     "endpoint pair — accepted or locally bound — including after activation, every\n"
     "affected generation becomes terminal, all data is hidden, and a new explicit\n"
     "attempt is required."),
    ("generation-bootstrap", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "A retention component is\nforbidden even when its value is 0, because an absent "
     "component and a signed\nzero are different canonical states and only absence "
     "keeps retention outside\npeer control."),
    # The activation gate itself. FEAT-062 §3.3's table row is bound to the
    # evaluator, but the wire's own precondition — that send *and* apply stay
    # blocked until the read proves all of it — was stated nowhere the run read.
    ("generation-bootstrap", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "Send **and** apply are blocked until one session-consistent local MDK read "
     "proves:"),

    # --- what membership alone is worth, and what is never claimed absolutely
    ("security-claims", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "The central rule is: **Marmot membership grants nothing.**"),
    ("security-claims", "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md",
     "No per-epoch guarantee is claimed in the absolute."),
    ("security-claims", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "5. Group membership and relationship level grant no data without an explicit\n"
     "   grant."),

    # --- the spike's own discipline: all eight, on real surfaces, no silent
    #     enlargement, and a proven capability is still not a closed gate
    ("spike-gate-discipline",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Before any product slice starts, an **isolated throwaway adapter\n"
     "spike** must pass all eight binary gates in "
     "[§5](#5-adapter-spike-eight-binary-gates)."),
    ("spike-gate-discipline",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Gates 2 and 4–7 must run against real native, on-device code and against every\n"
     "White Noise chat, timeline, unread, Markdown and push surface. Fakes and stubs\n"
     "do not satisfy them."),
    ("spike-gate-discipline",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "If the required semantics can only be reached by\n"
     "changing the engine, session or account crates, that is a scope-expanding No-Go\n"
     "requiring a fresh independent review decision, not a silent enlargement."),
    ("spike-gate-discipline",
     "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "**No gate closes.** A proven capability is not a discharged gate."),

    # --- what may never reach a payload, and what a migration may never do
    # The 65,536-byte cap is checked against the oracle only where FEAT-062
    # *restates* it, so the wire contract — the normative source — could widen
    # it alone and stay green, taking the whole no-overflow-carrier rule with it.
    ("data-minimisation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "The complete canonical `content` is at most **65,536 UTF-8 bytes**. There is no\n"
     "compression or alternate payload carrier. A larger object emits no event."),
    ("data-minimisation", "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md",
     "3. The adapter checks the 65,536-byte UTF-8 `content` limit, duplicate JSON keys,\n"
     "   canonical JCS and the closed envelope/version."),
    ("data-minimisation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "No payload may contain raw logbook/ascent rows; bids, bid counts, attempts,\n"
     "attempt counts or timestamps;"),
    ("data-minimisation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "Every problem has a canonical hold fingerprint. If the complete board-native\n"
     "hold sequence cannot be derived, that ascent is ineligible."),
    ("data-minimisation", "docs/specs/0.2.3/FEAT-062-personal-information-sharing.md",
     "No schema migration is authorised while this spec is blocked. A future\n"
     "implementation migration must be additive, **backfill-free and transactional**."),
)

#: The length floor a pinned rule must clear. A pin short enough to match by
#: accident would assert nothing; the shortest real one is well above this.
NORMATIVE_PIN_MIN = 40

#: Threat model §8 — the closed, numbered list of claims this bundle forbids.
#:
#: `VERDICT_PINS` pins three of these verbatim, for the exactly-once scope, the
#: S9/R6 separation and the uncorrelated acceptance. The other eleven were
#: bound by nothing: deleting item 11 ("the MDK raw application store is
#: durable CruxCoach state"), item 12 ("a re-queued send is a retry of the same
#: inner event"), item 6 ("queued or relay-published means peer-delivered") or
#: item 9 ("any adapter surface required by this design already exists at the
#: pins") retired a prohibition this specification rests on, with the whole run
#: green. A list is the wrong shape to guard sentence by sentence anyway, so it
#: is bound the way the published tables are: as a complete, ordered set.
#:
#: Compared with whitespace normalised, so re-wrapping a claim is free and
#: dropping, reordering, renumbering or rewording one is not.
FORBIDDEN_CLAIMS = (
    "Marmot, MLS or MDK hides metadata or makes sharing anonymous.",
    "Revoke, downgrade, removal, disband or block deletes plaintext already held "
    "by a peer.",
    "A signature or MLS sender proves a climb, grade or plan is true.",
    "A KeyPackage or inbox relay list is private, or can be unpublished.",
    "Group membership, an accepted Welcome or `FRIEND` alone grants access.",
    "Queued or relay-published means peer-delivered or read.",
    "A backup restores an MLS group or reconnects a relationship.",
    "Forward secrecy or post-compromise security survives retained keys, retained "
    "plaintext or a currently compromised endpoint without qualification.",
    "Any adapter surface, harness or product code required by this design already "
    "exists at the pins.",
    "Exactly-once holds anywhere beyond CruxCoach's own idempotent reduction — for "
    "live emission, relay delivery, network delivery, raw row counts or a peer "
    "receipt.",
    "The MDK raw application store is durable CruxCoach state, or a live "
    "subscription is a complete stream.",
    "A re-encrypted or re-queued send is a retry of the same inner event.",
    "The S9 Python harness is R6, native or on-device evidence, or that a green "
    "harness run closes any gate.",
    "A transport acceptance is a delivery even when nothing correlates it to a "
    "source message id, or that R6's host-scope runtime evidence is native, "
    "on-device or release evidence.",
)

FORBIDDEN_CLAIMS_HEADING = "## 8. Claims that are forbidden"


def check_forbidden_claims(report: Report) -> None:
    """Threat model §8 prints exactly the forbidden claims, in order."""
    rel = "docs/specs/social/PRIVATE-SHARING-THREAT-MODEL.md"
    text = (REPO_ROOT / rel).read_text(encoding="utf-8")
    parts = text.split(FORBIDDEN_CLAIMS_HEADING)
    if not report.equal(len(parts), 2,
                        f"{rel} has no single {FORBIDDEN_CLAIMS_HEADING!r} section"):
        return
    body = re.split(r"\n#{1,6} ", parts[1])[0].strip()
    printed: list[tuple[str, str]] = []
    for item in re.split(r"\n(?=\d+\. )", body):
        match = re.match(r"(\d+)\.\s+(.*)", item, re.S)
        if match:
            printed.append((match.group(1), " ".join(match.group(2).split())))
    report.equal([claim for _, claim in printed], list(FORBIDDEN_CLAIMS),
                 f"{rel} §8 does not print exactly the forbidden claims this "
                 f"bundle rests on")
    # Numbered from 1 with no gaps: the documents cite these by number — the
    # README names "forbidden claim 10" and "forbidden claim 13" — so a renumber
    # silently redirects every citation to a different prohibition.
    report.equal([number for number, _ in printed],
                 [str(i) for i in range(1, len(FORBIDDEN_CLAIMS) + 1)],
                 f"{rel} §8 claims are not numbered 1..{len(FORBIDDEN_CLAIMS)} in order")


#: How `social/README.md` names each requirement area it says is pinned.
#:
#: The area list is closed and asserted in both directions against the pins, but
#: only inside this harness. The README publishes the same list in prose for a
#: reviewer who reads the map rather than the code, and that list was free text:
#: adding an area here left the README describing a smaller guarantee than the
#: run makes, and dropping one from the README hid a covered area from the only
#: reader who would notice it was missing.
NORMATIVE_AREA_PHRASES = {
    "broadcast-lag-replay": "broadcast lag and replay",
    "data-minimisation": "data minimisation",
    "delivery-states": "delivery states",
    "durable-projection": "durable projection",
    "exactly-once-boundary": "the exactly-once boundary",
    "generation-bootstrap": "generation bootstrap",
    "grant-retirement-revocation": "grant retirement and revocation",
    "honest-ui": "honest UI",
    "inbound-isolation": "inbound isolation",
    "invalidation-reorg": "invalidation and reorg",
    "mdk-capability-honesty": "MDK-capability honesty",
    "offline-restore": "offline and restore",
    "queue-and-status": "the registry's queue and status",
    "r5-r6-reconciliation": "the R5/R6 reconciliation",
    "security-claims": "security claims",
    "spike-gate-discipline": "spike-gate discipline",
    "transport-identical-retry": "transport-identical retry",
}


def check_normative_rule_pins(report: Report) -> None:
    """Every pinned normative rule is still stated, and every area still pinned."""
    seen: set[str] = set()
    texts: list[str] = []
    for area, rel, sentence in NORMATIVE_RULE_PINS:
        seen.add(area)
        texts.append(sentence)
        report.check(len(sentence) >= NORMATIVE_PIN_MIN,
                     f"normative pin for {area} is too short to assert anything: "
                     f"{sentence!r}")
        report.check(rel in BUNDLE_FILES,
                     f"normative pin for {area} names {rel}, which is not a bundle file")
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        report.check(sentence in text,
                     f"{rel} no longer states its pinned {area} rule: {sentence!r}")
    # Both directions, so neither the registry nor the area list can shrink
    # alone: an area with no pin is an uncovered requirement, and a pin naming
    # an unlisted area is coverage nobody declared.
    report.equal(sorted(seen), sorted(NORMATIVE_RULE_AREAS),
                 "the pinned normative rules and the declared requirement areas "
                 "disagree")
    report.equal(len(set(texts)), len(texts),
                 "a normative rule is pinned twice, so one copy asserts nothing new")
    # The README publishes the same closed list, and a reviewer reads that one.
    readme_rel = "docs/specs/social/README.md"
    readme = (REPO_ROOT / readme_rel).read_text(encoding="utf-8")
    report.equal(sorted(NORMATIVE_AREA_PHRASES), sorted(NORMATIVE_RULE_AREAS),
                 "the published area names and the declared requirement areas "
                 "disagree")
    for area in NORMATIVE_RULE_AREAS:
        phrase = NORMATIVE_AREA_PHRASES.get(area, "")
        report.check(phrase and phrase in readme,
                     f"{readme_rel} does not name the pinned requirement area "
                     f"{area} as {phrase!r}")


def check_document_provenance(report: Report) -> None:
    """The documents must print the R6 provenance and verdict they rest on.

    `check_gate_registry` asserts these values inside the oracle, which proves
    the harness agrees with itself. It does not prove the *specification* still
    names the same spike: wire §11.1 prints the binding patch digest, the record
    name and both pins, and swapping any of them left every other check green —
    the same decoration class as an unbound golden value, on the provenance that
    decides which spike the evidence describes.
    """
    wire_rel = "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md"
    wire = (REPO_ROOT / wire_rel).read_text(encoding="utf-8")
    for field in ("patch_sha256", "record", "mdk_pin", "marmot_spec_pin"):
        value = o.R6_SOURCE[field]
        report.check(value in wire,
                     f"wire §11.1 no longer prints the R6 {field} {value!r}")
    report.check(o.R6_SOURCE["overall"] in wire,
                 f"wire §11.1 no longer records R6 as {o.R6_SOURCE['overall']}")

    for rel, sentence in VERDICT_PINS:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        report.check(sentence in text,
                     f"{rel} no longer carries its pinned verdict: {sentence!r}")

    check_normative_rule_pins(report)
    check_forbidden_claims(report)


# --------------------------------------------------------------------------
# 5. Golden-vectors document cross-check
# --------------------------------------------------------------------------


#: Keys whose value is the *rendered* result of a derivation printed beside it.
RENDERED_DERIVATION_KEYS = ("generation_id", "object_id", "session_uuid", "plan_uuid")


def check_document_records(report: Report, stored: dict, text: str) -> None:
    """Bind every value the golden vectors print to the fixture record it claims.

    The document prints its values in record-shaped fenced blocks: a derivation
    block carries ``preimage_hex``/``sha256`` plus the rendered identifier, an
    event block carries ``content_size``/``content_sha256``/``inner_event_id``,
    and a ``marmot-event`` block carries the full pinned encoder bytes.

    Without this, a printed value is decoration. `MANIFEST.json` would notice
    that the file changed, but the manifest is a whole-file digest that cannot
    tell a prose edit from a corrupted golden value, and ``--write-manifest`` is
    the documented response to *any* bundle edit — so regenerating it clears the
    only complaint and the wrong value survives. Golden §1.1 and §10, README's
    "in both directions" and FEAT-062 criteria 50/50a all claim these values are
    reproduced, so each one is bound to its fixture record here and the two sets
    are compared in both directions.
    """
    derivation_by_preimage = {d["preimage_hex"]: d for d in stored["derivations"]}
    event_by_inner = {e["inner_event_id"]: e for e in stored["events"].values()}

    # The bootstrap marker name is normative GroupContext bytes (wire §9.2) and
    # is printed as a bare fenced block rather than a key=value line.
    name = stored["bootstrap"]["name"]
    report.check(f"\n```text\n{name}\n```" in text,
                 f"golden vectors no longer print the exact bootstrap name {name!r}")

    fenced = re.findall(r"```(text|marmot-event)\s*\n(.*?)\n```", text, re.S)
    current = None          # the event that following full_event_* lines describe
    seen_events: set[str] = set()
    seen_full: set[str] = set()

    for language, body in fenced:
        if language == "marmot-event":
            match = re.search(r'"id":"([0-9a-f]{64})"', body)
            if not report.check(match is not None,
                                f"marmot-event block carries no id: {body[:60]}"):
                continue
            event = event_by_inner.get(match.group(1))
            if not report.check(event is not None,
                                f"marmot-event id is not a fixture event: {match.group(1)}"):
                continue
            current = event
            seen_full.add(event["name"])
            report.equal(body, event["full_event"],
                         f"golden full encoder bytes for {event['name']}")
            continue

        record = dict(re.findall(r"^([a-z0-9_]+)=(\S+)$", body, re.M))

        if "preimage_hex" in record:
            derivation = derivation_by_preimage.get(record["preimage_hex"])
            if report.check(
                    derivation is not None,
                    f"golden preimage is absent from the fixtures: "
                    f"{record['preimage_hex'][:32]}…"):
                report.equal(record.get("sha256"), derivation["sha256"],
                             f"golden sha256 for derivation {derivation['name']}")
                for key in RENDERED_DERIVATION_KEYS:
                    if key in record:
                        report.equal(record[key], derivation["value"],
                                     f"golden {key} for derivation {derivation['name']}")

        if "inner_event_id" in record:
            event = event_by_inner.get(record["inner_event_id"])
            if report.check(
                    event is not None,
                    f"golden inner_event_id is not a fixture event: "
                    f"{record['inner_event_id']}"):
                current = event
                seen_events.add(event["name"])
                report.equal(record.get("content_size"), str(event["content_size"]),
                             f"golden content_size for {event['name']}")
                report.equal(record.get("content_sha256"), event["content_sha256"],
                             f"golden content_sha256 for {event['name']}")

        if "full_event_size" in record or "full_event_sha256" in record:
            if report.check(current is not None,
                            f"golden full_event_* block names no event: {body[:60]}"):
                report.equal(record.get("full_event_size"), str(current["full_event_size"]),
                             f"golden full_event_size for {current['name']}")
                report.equal(record.get("full_event_sha256"), current["full_event_sha256"],
                             f"golden full_event_sha256 for {current['name']}")

    # Both directions, so a fixture value the document stops printing is caught
    # as loudly as a printed value the fixtures do not derive.
    report.equal(seen_events, {e["name"] for e in stored["events"].values()},
                 "golden vectors print every fixture event")
    report.equal(seen_full,
                 {e["name"] for e in stored["events"].values() if "full_event" in e},
                 "golden vectors print every full encoder vector")


def check_document(report: Report, builder, stored: dict) -> None:
    path = SOCIAL / "PRIVATE-SHARING-GOLDEN-VECTORS.md"
    text = path.read_text(encoding="utf-8")

    # Every fenced json block is exact JCS.
    blocks = re.findall(r"```json\s*\n(.*?)\n```", text, re.S)
    for raw in blocks:
        try:
            value = json.loads(raw)
            canonical = o.jcs(value).decode("utf-8") == raw
        except (json.JSONDecodeError, o.SpecViolation):
            canonical = False
        report.check(canonical, f"golden json block is not canonical JCS: {raw[:70]}")
    report.check(len(blocks) >= 30, f"expected the full json corpus, found {len(blocks)} blocks")

    # Every printed key=value line must match the fixture.
    printed = dict(re.findall(r"^([a-z0-9_]+)=(\S+)$", text, re.M))
    known = {
        "description_size": str(stored["bootstrap"]["description_size"]),
        "description_sha256": stored["bootstrap"]["description_sha256"],
        "component_data_hex": stored["bootstrap"]["component_data_hex"],
        "component_data_size": str(stored["bootstrap"]["component_data_size"]),
        "component_data_sha256": stored["bootstrap"]["component_data_sha256"],
        "board_config_id": stored["board_config_id"],
        "hold_preimage_hex": stored["hold_preimage_hex"],
        "hold_fingerprint": stored["hold_fingerprint"],
        "problem_hash": stored["problem_hash"],
        "attempt_id": stored["inputs"]["attempt_id"],
        # The attempt id is a CSPRNG UUIDv4 (wire §4.2), so the two nibbles the
        # document prints beside it are derived from it, not free values.
        "version_nibble": stored["inputs"]["attempt_id"][14],
        "variant_nibble": stored["inputs"]["attempt_id"][19],
    }
    for key, want in known.items():
        report.check(key in printed, f"golden vectors no longer print {key}")
        if key in printed:
            report.equal(printed[key], want, f"golden vectors {key}")

    check_document_records(report, stored, text)

    # Every preimage/sha256/uuid triple printed in the document must be one of
    # the fixture derivations, and every fixture derivation must be printed.
    doc_preimages = set(re.findall(r"^preimage_hex=([0-9a-f]+)$", text, re.M))
    fixture_preimages = {d["preimage_hex"] for d in stored["derivations"]}
    report.check(
        doc_preimages <= fixture_preimages,
        f"golden vectors print preimages absent from the fixtures: "
        f"{sorted(p[:32] for p in doc_preimages - fixture_preimages)}",
    )
    report.check(
        fixture_preimages <= doc_preimages,
        f"fixtures derive values the golden vectors do not print: "
        f"{sorted(d['name'] for d in stored['derivations'] if d['preimage_hex'] not in doc_preimages)}",
    )

    # Every content_sha256 / inner_event_id printed must belong to a fixture event.
    event_digests = {e["content_sha256"] for e in stored["events"].values()}
    event_ids = {e["inner_event_id"] for e in stored["events"].values()}
    for digest in re.findall(r"^content_sha256=([0-9a-f]{64})$", text, re.M):
        report.check(digest in event_digests, f"unknown content_sha256 in the document: {digest}")
    for value in re.findall(r"^inner_event_id=([0-9a-f]{64})$", text, re.M):
        report.check(value in event_ids, f"unknown inner_event_id in the document: {value}")

    # The mandatory-coverage table and the corpus areas agree.
    doc_areas = set(re.findall(r"^\| `?([a-z0-9_.]+)`? \| ", text, re.M))
    for area in AREAS:
        report.check(area in doc_areas,
                     f"golden §9 does not name the corpus area '{area}'")

    # …and the corpus is *mandatory*, which is the whole force of that table.
    # The areas were bound in both directions while the section that declares
    # them obligatory was not, so the heading could read "Optional negative
    # corpus" — and the sentence forbidding a case-name-only corpus could go —
    # with every area still listed and every fixture still green. Criterion 51
    # requires concrete invalid bytes or concrete observed state "rather than
    # case names alone"; this is that requirement where the corpus states it.
    for stated in ("## 9. Mandatory negative corpus",
                   "Mandatory coverage, one corpus area per row:",
                   "A case name alone does not satisfy this\ncorpus."):
        report.check(stated in text,
                     f"golden §9 no longer declares the corpus mandatory: {stated!r}")

    # The bundle must not claim more than it has.
    for phrase in ("adapter is implemented", "gates pass", "production conformance proven"):
        report.check(phrase not in text.lower(), f"golden vectors overclaim: {phrase}")


# --------------------------------------------------------------------------
# 5a. The §8.7c refusal split — published tables and the prose around them
# --------------------------------------------------------------------------


#: Per document: the pinned header of the §8.7c refusal table and, by name, the
#: index of each cell this binding owns — `name` the refusing result, `phases`
#: the durable phases it admits, `reason` its closed refusal code, `recovery`
#: what recovery makes of those phases. A document that carries no such column
#: simply omits the key: golden §8.7c states the refusal code in prose above the
#: table, and wire §8.7c answers "What may follow" in prose that carries no
#: oracle vocabulary to bind.
#:
#: The header is pinned in full so a column inserted, dropped or reordered fails
#: here rather than silently shifting what every index below means.
#:
#: A refusal at this gate establishes one thing — no transport call was made —
#: and says nothing by itself about where the row now stands.
#: `check_send_handover_case` already drives *every* observation each refusal
#: admits through lease recovery, so the fixtures carry that split; nothing bound
#: the *published* tables to it. Letting the `unproven` row admit only a
#: pre-effect phase, or drop `RECOVERY_BLOCKED` from its recovery cell, left
#: every fixture, oracle, registry and manifest check green while the
#: specification told a reader that an unproven commit is always safely
#: retryable — the one reading wire §8.7c and FEAT-062 criterion 26i exist to
#: forbid, because that commit may in fact have landed.
WRITE_AHEAD_REFUSAL_TABLES = {
    "docs/specs/social/PRIVATE-SHARING-GOLDEN-VECTORS.md": (
        ["Refusal", "Admissible durable phases", "Recovery from each"],
        {"name": 0, "phases": 1, "recovery": 2}),
    "docs/specs/social/PRIVATE-SHARING-WIRE-V1.md": (
        ["Refusal", "Closed reason", "What is established", "Durable phase",
         "What may follow"],
        {"name": 0, "reason": 1, "phases": 3}),
}


def write_ahead_refusals(scenarios: dict) -> dict:
    """What each refusing write-ahead result admits, read from the fixtures.

    Taken from the executed `send_handover` cases rather than restated by hand
    here, so the documents are bound to the same records the mutation battery
    owns and `check_send_handover_case` has already asserted against the oracle.
    """
    out: dict[str, dict] = {}
    for case in scenarios.get("send_handover", []):
        if case.get("kind") != "write_ahead":
            continue
        expect = case["expect"]
        if expect["outcome"] != o.HANDOVER_REFUSED:
            continue
        out[case["state"]["write_ahead_commit"]] = {
            "reason": expect["reason"],
            "phases": sorted(expect["admissible_phases"]),
            "outcomes": sorted({entry["outcome"] for entry in expect["recovery"]}),
            "states": sorted({entry["resulting_state"] for entry in expect["recovery"]}),
        }
    return out


def prose_sentences(text: str):
    """The running prose of a document, sentence by sentence.

    Fenced blocks and table rows are dropped — the tables have their own,
    stronger binding above — and wrapped lines are rejoined, so a claim is read
    the way a reader meets it rather than the way the file happens to wrap.
    """
    body = re.sub(r"```.*?```", " ", text, flags=re.S)
    paragraphs: list[str] = []
    current: list[str] = []
    for line in body.split("\n"):
        stripped = line.strip()
        if not stripped or stripped.startswith("|") or stripped.startswith("#"):
            if current:
                paragraphs.append(" ".join(current))
                current = []
            continue
        current.append(stripped)
    if current:
        paragraphs.append(" ".join(current))
    for paragraph in paragraphs:
        yield from re.split(r"(?<=[.;:])\s+(?=[-A-Z`*\[])", paragraph)


def check_write_ahead_refusal_tables(report: Report, scenarios: dict) -> None:
    """Every published §8.7c refusal table states the split the fixtures drive."""
    refusals = write_ahead_refusals(scenarios)
    refusing = sorted(
        result for result in o.WRITE_AHEAD_RESULTS
        if o.evaluate_send_handover({"write_ahead_commit": result})["outcome"]
        == o.HANDOVER_REFUSED
    )
    report.equal(sorted(refusals), refusing,
                 "the send-handover fixtures do not drive every refusing "
                 "write-ahead result")
    vocabulary = set(o.LEASE_RECOVERY_OUTCOMES) | set(o.OUTBOX_STATES)
    for rel, (header, index) in sorted(WRITE_AHEAD_REFUSAL_TABLES.items()):
        lines = (REPO_ROOT / rel).read_text(encoding="utf-8").split("\n")
        starts = [i for i, line in enumerate(lines) if _table_cells(line) == header]
        if not report.equal(len(starts), 1,
                            f"{rel} has no single write-ahead refusal table with "
                            f"the pinned header {header}"):
            continue
        seen: dict[str, list[list[str]]] = {}
        row = starts[0] + 2
        while row < len(lines) and lines[row].startswith("|"):
            cells = _table_cells(lines[row])
            if len(cells) == len(header):
                for name in re.findall(r"`([a-z_]+)`", cells[index["name"]]):
                    seen.setdefault(name, []).append(cells)
            row += 1
        # Total in both directions: no refusal missing from the table, none invented.
        report.equal(sorted(seen), sorted(refusals),
                     f"{rel} refusal table does not tabulate exactly the "
                     f"refusing write-ahead results")
        for name, rows in sorted(seen.items()):
            if name not in refusals:
                continue
            # One row per refusal, or a second row could restate it differently
            # and the cells asserted below would be whichever came last.
            if not report.equal(len(rows), 1,
                                f"{rel} refusal table states {name!r} in more than "
                                f"one row"):
                continue
            cells = rows[0]
            record = refusals[name]
            # Exactly the phases, so the cell can neither drop the unclear
            # observation nor add one recovery is never driven from.
            report.equal(sorted(set(re.findall(r"`([A-Z_]+)`", cells[index["phases"]]))),
                         record["phases"],
                         f"{rel} row for {name!r} does not admit exactly the "
                         f"durable phases the fixtures drive through recovery")
            if "reason" in index:
                # The refusal code is a closed §8.7c vocabulary, and the row that
                # publishes it must carry the one the fixture actually decides.
                report.equal(
                    sorted(set(re.findall(r"`([A-Z_]+)`", cells[index["reason"]]))),
                    [record["reason"]],
                    f"{rel} row for {name!r} does not state exactly the refusal "
                    f"reason the fixture decides")
            if "recovery" not in index:
                continue
            recovery_cell = cells[index["recovery"]]
            named = set(re.findall(r"`([A-Z_]+)`", recovery_cell))
            missing = sorted(set(record["outcomes"]) - named)
            report.check(not missing,
                         f"{rel} recovery cell for {name!r} does not name {missing}, "
                         f"which recovery reaches from a phase this refusal admits: "
                         f"{recovery_cell!r}")
            foreign = sorted((named & vocabulary)
                             - set(record["outcomes"]) - set(record["states"]))
            report.check(not foreign,
                         f"{rel} recovery cell for {name!r} names {foreign}, which "
                         f"recovery never reaches from a phase this refusal admits: "
                         f"{recovery_cell!r}")


def check_write_ahead_refusal_prose(report: Report, scenarios: dict) -> None:
    """No sentence may collapse the gate refusals onto a single durable phase.

    The tables above are bound row by row; running prose is where that split is
    most easily lost. A sentence that lists `not_attempted`, `failed` and
    `unproven` together and then names one phase reads as a guarantee for all
    three — true for the first two, false for the third, whose commit may have
    landed. The false half is exactly the "still pre-effect, just try again"
    reading wire §8.7c and criterion 26i forbid, and it is invisible to every
    fixture, because the fixtures were right all along.

    A sentence naming one refusal may legitimately discuss one of the
    observations it admits, so only the grouping form is required to be total.
    Every sentence, grouping or not, is forbidden from claiming a phase its
    refusals do not admit at all; and the refusal that admits more than one
    phase must be stated with all of them somewhere a reader actually meets,
    or this screen would be satisfied by prose that says nothing.
    """
    refusals = write_ahead_refusals(scenarios)
    phases = set(o.EXTERNAL_EFFECT_PHASES)
    for rel in BUNDLE_FILES:
        text = (REPO_ROOT / rel).read_text(encoding="utf-8")
        for sentence in prose_sentences(text):
            named = {name for name in refusals if f"`{name}`" in sentence}
            claimed = {phase for phase in phases if f"`{phase}`" in sentence}
            if not named or not claimed:
                continue
            admissible = set().union(*(set(refusals[name]["phases"]) for name in named))
            report.check(
                claimed <= admissible,
                f"{rel} attributes {sorted(claimed - admissible)} to the gate "
                f"refusals {sorted(named)}, which never admit it: "
                f"{sentence.strip()[:180]!r}",
            )
            if len(named) < 2:
                continue
            report.equal(
                sorted(claimed), sorted(admissible),
                f"{rel} states one durable phase for the grouped gate refusals "
                f"{sorted(named)}, which do not share one: "
                f"{sentence.strip()[:180]!r}",
            )

    golden = (SOCIAL / "PRIVATE-SHARING-GOLDEN-VECTORS.md").read_text(encoding="utf-8")
    sentences = list(prose_sentences(golden))
    for name, record in sorted(refusals.items()):
        if len(record["phases"]) < 2:
            continue
        report.check(
            any(f"`{name}`" in sentence
                and all(f"`{phase}`" in sentence for phase in record["phases"])
                for sentence in sentences),
            f"golden vectors carry no sentence naming {name!r} together with "
            f"every durable phase it admits {record['phases']}",
        )


# --------------------------------------------------------------------------
# 6. Links and anchors
# --------------------------------------------------------------------------


def github_slug(heading: str) -> str:
    s = heading.strip().lower()
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"[`*_~]", "", s)
    s = re.sub(r"[^\w\- ]", "", s, flags=re.UNICODE)
    s = re.sub(r"\s+", "-", s)
    return re.sub(r"-+", "-", s).strip("-")


def anchors_of(text: str) -> set[str]:
    seen: dict[str, int] = {}
    out: set[str] = set()
    for line in text.splitlines():
        m = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not m:
            continue
        base = github_slug(m.group(1))
        count = seen.get(base, 0)
        seen[base] = count + 1
        out.add(base if count == 0 else f"{base}-{count}")
    return out


def check_links(report: Report) -> tuple[int, int]:
    link_re = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
    links = 0
    anchor_total = 0
    for rel in BUNDLE_FILES:
        src = REPO_ROOT / rel
        text = src.read_text(encoding="utf-8")
        anchor_total += len(anchors_of(text))
        for raw in link_re.findall(text):
            links += 1
            target = raw.strip().split()[0].strip("<>")
            if not target or re.match(r"^(?:https?|mailto):", target):
                continue
            path_part, sep, anchor = target.partition("#")
            dst = src if not path_part else (src.parent / unquote(path_part)).resolve()
            if not report.check(dst.exists(), f"dead link {rel} -> {target}"):
                continue
            if sep and dst.suffix.lower() == ".md":
                known = anchors_of(dst.read_text(encoding="utf-8"))
                report.check(unquote(anchor).lower() in known,
                             f"dead anchor {rel} -> {target}")
    return links, anchor_total


# --------------------------------------------------------------------------
# 7. Mutation battery — every expectation field must be an assertion
# --------------------------------------------------------------------------


def _mutated_variant(value):
    """A different value of the same JSON shape, for a single expectation leaf."""
    if isinstance(value, bool):
        return not value
    if isinstance(value, int):
        return value + 1
    if isinstance(value, str):
        if re.fullmatch(r"[0-9a-f]{64}", value):
            return ("0" if value[0] != "0" else "1") + value[1:]
        return value + "~mutant"
    if value is None:
        return "~mutant"
    if isinstance(value, list):
        return value + ["~mutant"]
    raise TypeError(f"no mutation defined for {type(value)!r}")


def _leaf_slots(container, key, path: str):
    """Yield ``(container, key, path)`` for every scalar/list leaf under ``key``."""
    value = container[key]
    if isinstance(value, dict):
        for sub in sorted(value):
            yield from _leaf_slots(value, sub, f"{path}.{sub}")
        return
    if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
        for index, item in enumerate(value):
            for sub in sorted(item):
                yield from _leaf_slots(item, sub, f"{path}[{index}].{sub}")
        return
    yield container, key, path


def expectation_slots(positive: dict, negative: dict, scenarios: dict):
    """Every fixture field that claims an outcome, with its scoped re-check."""
    slots: list[tuple[str, object, object, str, dict]] = []

    def add(scope, container, key, path, locator):
        for holder, leaf, leaf_path in _leaf_slots(container, key, path):
            slots.append((scope, holder, leaf, leaf_path, locator))

    for index, item in enumerate(positive["derivations"]):
        for key in ("preimage_hex", "sha256", "value"):
            add("positive", item, key, f"derivations[{item['name']}].{key}", {})
    for name, event in positive["events"].items():
        for key in ("content_size", "content_sha256", "inner_event_id", "full_event",
                    "full_event_size", "full_event_sha256"):
            if key in event:
                add("positive", event, key, f"events[{name}].{key}", {})
    for key in ("name", "name_size", "description_size", "description_sha256",
                "component_data_hex", "component_data_size", "component_data_sha256"):
        add("positive", positive["bootstrap"], key, f"bootstrap.{key}", {})
    for key in ("board_config_id", "hold_preimage_hex", "hold_fingerprint", "problem_hash"):
        add("positive", positive, key, key, {})

    for index, case in enumerate(negative["cases"]):
        for key in ("expected_disposition", "expected_reason", "content_size",
                    "content_sha256"):
            if key in case:
                add("negative", case, key, f"cases[{case['name']}].{key}",
                    {"index": index})

    for section in SCENARIO_SECTIONS:
        for index, case in enumerate(scenarios.get(section, [])):
            locator = {"section": section, "index": index}
            label = f"{section}[{case['id']}]"
            if "expect" in case:
                add("scenario", case, "expect", f"{label}.expect", locator)
            if "limit" in case:
                add("scenario", case, "limit", f"{label}.limit", locator)
            if "map" in case:
                add("scenario", case, "map", f"{label}.map", locator)
            for collection, field in (("pairs", "allowed"),
                                      ("receipts", "expect_label"),
                                      ("receipts", "expect_queue_state")):
                for sub, item in enumerate(case.get(collection, [])):
                    if field in item:
                        add("scenario", item, field, f"{label}.{collection}[{sub}].{field}",
                            locator)
    return slots


#: Field names that declare an outcome rather than an input. ``expect``-shaped
#: names plus the three bare ones the fixtures use for the same purpose.
EXPECTATION_KEY_NAMES = frozenset({"limit", "map", "allowed"})


def _is_expectation_key(key: str) -> bool:
    return (key == "expect" or key.startswith("expect_")
            or key.startswith("expected_") or key in EXPECTATION_KEY_NAMES)


def _walk_dicts(node, path: str):
    """Yield ``(dict, path)`` for every mapping in a loaded fixture document."""
    if isinstance(node, dict):
        yield node, path
        for key in sorted(node):
            yield from _walk_dicts(node[key], f"{path}.{key}")
    elif isinstance(node, list):
        for index, item in enumerate(node):
            yield from _walk_dicts(item, f"{path}[{index}]")


def check_expectation_slot_coverage(report: Report, positive, negative,
                                    scenarios) -> None:
    """Criterion 50c is a claim about *every* expectation field, so prove it.

    :func:`expectation_slots` enumerates the fixture locations the mutation
    battery owns, and that enumeration is written by hand. Without this guard a
    newly added expectation field would simply not be enumerated: nothing would
    read it, nothing would mutate it, and the run would stay green while the
    fixture quietly claimed an outcome no check owns. That is exactly the
    decoration the battery exists to forbid, so an expectation-shaped field the
    battery does not reach fails the run instead of being ignored.

    "Expectation-shaped" is the fixtures' naming convention — :func:`
    _is_expectation_key` — not a proof of intent, so this guard catches a field
    that *looks* like an outcome and is unreachable. A fixture that declared an
    outcome under some other name would still need the enumeration extended by
    hand; the convention is what keeps that from being silent in practice.
    """
    covered = {(id(holder), key)
               for _, holder, key, _, _ in expectation_slots(positive, negative, scenarios)}
    documents = (("positive-vectors.json", positive),
                 ("negative-corpus.json", negative),
                 ("reducer-scenarios.json", scenarios))
    for name, document in documents:
        for holder, path in _walk_dicts(document, name):
            for key in sorted(holder):
                if not _is_expectation_key(key):
                    continue
                leaves = list(_leaf_slots(holder, key, f"{path}.{key}"))
                # An empty container declares no outcome at all, so it yields no
                # leaf, no mutation slot and no assertion — a case built that way
                # would pass by claiming nothing.
                report.check(
                    bool(leaves),
                    f"expectation container declares nothing and asserts nothing: "
                    f"{path}.{key}",
                )
                missing = [leaf_path
                           for leaf_holder, leaf_key, leaf_path in leaves
                           if (id(leaf_holder), leaf_key) not in covered]
                report.check(
                    not missing,
                    f"expectation field is outside the mutation battery "
                    f"(never asserted, never mutated): {missing}",
                )


def run_scoped(builder, positive, negative, scenarios, scope, locator) -> list[str]:
    """Re-run only the checks that own one fixture location."""
    scoped = Report()
    try:
        if scope == "positive":
            check_positive(scoped, builder, positive)
        elif scope == "negative":
            check_negative_case(scoped, builder, negative["cases"][locator["index"]])
        elif scope == "scenario":
            section = locator["section"]
            SCENARIO_SECTIONS[section](scoped, builder,
                                       scenarios[section][locator["index"]])
        else:  # pragma: no cover - defensive
            raise SystemExit(f"unknown mutation scope {scope!r}")
    except Exception as exc:  # noqa: BLE001 - an exception is a detected mutation
        scoped.errors.append(f"raised {type(exc).__name__}: {exc}")
    return scoped.errors


def check_mutation_sensitivity(report: Report, builder, positive, negative,
                               scenarios) -> int:
    """Mutate every expectation field and require the scoped check to fail.

    A field that survives its own mutation is decoration, not an assertion.
    Nothing is sampled and nothing is skipped: the battery covers every
    expectation slot the fixtures declare.
    """
    slots = expectation_slots(positive, negative, scenarios)
    for scope, container, key, path, locator in slots:
        original = container[key]
        try:
            container[key] = _mutated_variant(original)
        except TypeError as exc:
            report.check(False, f"mutation battery cannot mutate {path}: {exc}")
            continue
        errors = run_scoped(builder, positive, negative, scenarios, scope, locator)
        container[key] = original
        report.check(bool(errors),
                     f"expectation is never asserted (survives mutation): {path}")
    # The battery is only meaningful if the unmutated fixtures are green.
    for scope, locator in (("positive", {}),):
        report.check(not run_scoped(builder, positive, negative, scenarios, scope, locator),
                     "mutation battery control run is not green")
    return len(slots)


# --------------------------------------------------------------------------
# 8. Manifests
# --------------------------------------------------------------------------


#: The width of the manifest rule's length prefix, in bytes, and the word the
#: README spells it with.
#:
#: `social/README.md` prints the digest formula for an independent reviewer who
#: recomputes both values *without* this repository's tooling — that is the
#: whole purpose of the rule — and nothing bound the printed formula to the code
#: that computes it. Widening the prefix in prose, or renaming the hash, left a
#: reviewer computing a digest this harness would then reject as tampering, with
#: the run green and `--write-manifest` papering over the difference.
MANIFEST_LENGTH_PREFIX_BYTES = 4
MANIFEST_LENGTH_PREFIX_WORDS = {2: "two-byte", 4: "four-byte", 8: "eight-byte"}
MANIFEST_HASH = "SHA-256"


def _manifest_length_token() -> str:
    return f"u{MANIFEST_LENGTH_PREFIX_BYTES * 8}be"


def check_manifest_rule_prose(report: Report) -> None:
    """The README's printed manifest rule is the rule :func:`build_manifest` runs."""
    rel = "docs/specs/social/README.md"
    text = (REPO_ROOT / rel).read_text(encoding="utf-8")
    token = _manifest_length_token()
    for stated in (
        f"UTF8(path) || 0x00 || {token}(byte length) || file bytes",
        f"`{token}` is a {MANIFEST_LENGTH_PREFIX_WORDS[MANIFEST_LENGTH_PREFIX_BYTES]} "
        f"big-endian unsigned length.",
        f"the set digest is `{MANIFEST_HASH}` over the concatenation",
        f"`{MANIFEST_HASH}(file bytes)`",
    ):
        report.check(stated in text,
                     f"{rel} no longer prints the manifest rule this harness "
                     f"computes: {stated!r}")


def build_manifest() -> dict:
    """The reproducible bundle/evidence manifest rule of `social/README.md`.

    For each named set, in the fixed order below, the entry is
    `sha256(file bytes)`. The set digest is
    `SHA-256( for each entry: UTF-8(path) || 0x00 || u32be(len(bytes)) || bytes )`.
    `MANIFEST.json` is never an input to either digest, so it can record both.
    """
    def digest_set(paths):
        entries = []
        running = hashlib.sha256()
        for rel in paths:
            data = (REPO_ROOT / rel).read_bytes()
            entries.append({"path": rel, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            running.update(rel.encode("utf-8"))
            running.update(b"\x00")
            running.update(len(data).to_bytes(MANIFEST_LENGTH_PREFIX_BYTES, "big"))
            running.update(data)
        return entries, running.hexdigest()

    bundle_entries, bundle_sha = digest_set(BUNDLE_FILES)
    evidence_entries, evidence_sha = digest_set(EVIDENCE_FILES)
    return {
        "schema": "cc.manifest.v1",
        "rule": (
            "For each set, in the listed order: sha256 of the file bytes. The set digest is "
            f"{MANIFEST_HASH} over the concatenation of UTF8(path) || 0x00 || "
            f"{_manifest_length_token()}(byte length) || "
            "file bytes for every entry in that order. MANIFEST.json itself is excluded from "
            "both sets."
        ),
        "bundle": {"files": bundle_entries, "sha256": bundle_sha},
        "evidence": {"files": evidence_entries, "sha256": evidence_sha},
    }


def _manifest_entry_shape(entries) -> list:
    """The part of a manifest entry the per-path digest comparison cannot see.

    The path fixes identity, the byte length is a digest input in its own right,
    and the key set catches an entry that carries something else instead. Order
    is preserved because the digest rule concatenates entries in the listed one.
    """
    return [[e.get("path"), e.get("size"), sorted(e)] for e in entries]


def check_manifest_file_lists(report: Report) -> None:
    """`social/README.md` prints the two file sets; they must be the computed ones.

    The README calls its lists normative — "these six files, in exactly this
    order" — and says an independent reviewer can recompute both digests without
    this repository's tooling. That reviewer reads the *README*, so a file
    dropped from, added to or reordered in the printed list is a different rule
    than the one :func:`build_manifest` applies: the two would compute different
    digests and only the printed one would be wrong. Nothing bound them, and
    `--write-manifest` would have papered over the difference on the next run.
    """
    text = (SOCIAL / "README.md").read_text(encoding="utf-8")
    blocks = re.findall(r"```text\s*\n(.*?)\n```", text, re.S)
    for label, expected in (("bundle", BUNDLE_FILES), ("evidence", EVIDENCE_FILES)):
        printed = [block.strip().split("\n") for block in blocks
                   if block.strip().split("\n")[0].startswith("docs/specs/")]
        matches = [lines for lines in printed if len(lines) == len(expected)
                   and lines[0] == expected[0]]
        if not report.equal(len(matches), 1,
                            f"social/README.md does not print exactly one {label} "
                            f"file list of {len(expected)} entries starting at "
                            f"{expected[0]}"):
            continue
        report.equal(matches[0], list(expected),
                     f"social/README.md prints a {label} set the manifest rule "
                     f"does not use")


def check_manifest(report: Report) -> dict:
    fresh = build_manifest()
    path = FIXTURES / "MANIFEST.json"
    if not path.is_file():
        report.check(False, "fixtures/MANIFEST.json is missing; run --write-manifest")
        return fresh
    stored = json.loads(path.read_text(encoding="utf-8"))
    for scope in ("bundle", "evidence"):
        want = {e["path"]: e["sha256"] for e in fresh[scope]["files"]}
        got = {e["path"]: e["sha256"] for e in stored.get(scope, {}).get("files", [])}
        for name in sorted(set(want) | set(got)):
            report.equal(got.get(name), want[name] if name in want else None,
                         f"MANIFEST.json {scope} entry is stale: {name}")
        # A path→digest mapping asserts neither the recorded byte lengths nor
        # the order, and the rule beside it depends on both: it hashes
        # `UTF8(path) || 0x00 || u32be(byte length) || file bytes` per entry, in
        # the listed order. The README promises an independent reviewer can
        # recompute that value from this manifest *without* this repository's
        # tooling, and a reviewer reads the recorded entries. Zeroed lengths, a
        # dropped `size` and a reversed `files` list all survived the mapping
        # comparison, each leaving that reviewer hashing a different preimage
        # than the digest recorded beside it — with `--write-manifest` papering
        # over the difference on the next run.
        report.equal(_manifest_entry_shape(stored.get(scope, {}).get("files", [])),
                     _manifest_entry_shape(fresh[scope]["files"]),
                     f"MANIFEST.json {scope} entries are stale in order, byte "
                     f"length or shape — run --write-manifest")
        report.equal(stored.get(scope, {}).get("sha256"), fresh[scope]["sha256"],
                     f"MANIFEST.json {scope} digest is stale — run --write-manifest")
    report.equal(stored.get("rule"), fresh["rule"], "MANIFEST.json rule text")
    # The schema tag says which manifest format an independent reviewer should
    # recompute against. Only the digests, the entries and the rule text were
    # compared, so it could name any format at all.
    report.equal(stored.get("schema"), fresh["schema"], "MANIFEST.json schema tag")
    return fresh


# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-manifest", action="store_true")
    args = parser.parse_args()

    if args.write_manifest:
        manifest = build_manifest()
        (FIXTURES / "MANIFEST.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"bundle_sha256={manifest['bundle']['sha256']}")
        print(f"evidence_sha256={manifest['evidence']['sha256']}")
        return 0

    report = Report()
    builder = rebuild()
    positive = json.loads((FIXTURES / "positive-vectors.json").read_text(encoding="utf-8"))
    negative = json.loads((FIXTURES / "negative-corpus.json").read_text(encoding="utf-8"))
    scenarios = json.loads((FIXTURES / "reducer-scenarios.json").read_text(encoding="utf-8"))

    check_positive(report, builder, positive)
    check_fixture_generators(report, negative, scenarios)
    check_negative(report, builder, negative)
    check_scenarios(report, builder, scenarios)
    check_send_handover_coverage(report, scenarios)
    check_state_machine_coverage(report, scenarios)
    check_sequence_table_binding(report, negative, scenarios)
    check_commit_rollback_coverage(report, scenarios)
    check_replay_admission_coverage(report, scenarios)
    check_retirement_coverage(report, scenarios)
    check_clock_model_published(report)
    check_published_table_coverage(report, scenarios)
    check_closed_reason_vocabulary(report, positive, negative, scenarios)
    check_exactly_once_claim(report, scenarios)
    check_gate_registry(report, scenarios)
    check_document(report, builder, positive)
    check_write_ahead_refusal_tables(report, scenarios)
    check_write_ahead_refusal_prose(report, scenarios)
    check_expectation_slot_coverage(report, positive, negative, scenarios)
    links, anchor_count = check_links(report)
    mutations = check_mutation_sensitivity(report, builder, positive, negative, scenarios)
    check_manifest_file_lists(report)
    check_manifest_rule_prose(report)
    manifest = check_manifest(report)

    result = {
        "ok": not report.errors,
        "role": "spec oracle only — no product, native or on-device code was executed",
        "adapter_gates": "all eight remain open; this run does not lift the FEAT-062 No-Go",
        "r6_gates": (
            f"{len(o.R6_GATES)} adapter-evidence gates, all OPEN after the executed "
            f"R6 spike ({o.R6_SOURCE['record']}, overall {o.R6_SOURCE['overall']}); "
            "R6 proved capabilities at host scope and closed no gate; "
            "S9 harness is not R6 evidence"
        ),
        "counts": {
            "assertions": report.checks,
            "positive_events": len(positive["events"]),
            "positive_derivations": len(positive["derivations"]),
            "negative_cases": len(negative["cases"]),
            "negative_areas": len(negative["areas"]),
            "sequences": len(scenarios["sequences"]),
            "topology_phases": len(scenarios["topology_phases"]),
            "state_machine_cases": len(scenarios.get("state_machine", [])),
            "commit_rollback_cases": len(scenarios["commit_rollback"]),
            "clock_cases": len(scenarios["clock"]),
            "outbox_cases": len(scenarios["outbox"]),
            "send_handover_cases": len(scenarios.get("send_handover", [])),
            "bound_cases": len(scenarios["bounds"]),
            "restore_cases": len(scenarios["restore"]),
            "retirement_cases": len(scenarios.get("retirement", [])),
            "runtime_gate_cases": len(scenarios.get("runtime_gates", [])),
            "r6_gate_cases": len(scenarios.get("r6_gates", [])),
            "r6_condition_cases": len(scenarios.get("r6_conditions", [])),
            "replay_admission_cases": len(scenarios.get("replay_admission", [])),
            "published_binding_cases": len(scenarios.get("published_bindings", [])),
            "mutation_slots": mutations,
            "links": links,
            "anchors": anchor_count,
        },
        "bundle_sha256": manifest["bundle"]["sha256"],
        "evidence_sha256": manifest["evidence"]["sha256"],
        "errors": report.errors,
    }
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if not report.errors else 1


if __name__ == "__main__":
    sys.exit(main())
