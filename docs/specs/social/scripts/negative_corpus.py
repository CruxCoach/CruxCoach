#!/usr/bin/env python3
"""Concrete negative cases for every mandatory mutation area of Golden §9.

Each case carries concrete bytes (or a concrete observed state) and exactly one
expected disposition from the closed five, plus a reason code from that
disposition's closed set. A case name alone is never enough.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import private_sharing_oracle as o  # noqa: E402

#: Areas mirrored 1:1 by the mandatory-coverage table in
#: PRIVATE-SHARING-GOLDEN-VECTORS.md §9. The verifier fails when the document
#: and this list disagree, in either direction.
AREAS = (
    "marmot_app_event",
    "canonical_content",
    "cc.envelope.v1",
    "cc.generation.accept.v1",
    "bootstrap_marker_0x8001",
    "cc.relationship.offer.v1",
    "cc.grant.set.v1",
    "cc.grant.revoke.v1",
    "projection_bodies",
    "board_and_problem",
    "summary_payloads",
    "logbook_detail_rows",
    "plan_detail_rows",
    "delta_operations",
    "removed_shapes",
    "payload_prohibition",
    "component_profile",
    "bootstrap_and_provisioning",
    "reducer_and_topology",
    "adapter_runtime_gates",
)

PROHIBITED_MEMBERS = (
    "ascent_rows",
    "bids",
    "bid_count",
    "attempts",
    "attempt_count",
    "attempt_timestamps",
    "comment",
    "private_note",
    "adaptationNotes",
    "injury",
    "body_weight",
    "sleep_hours",
    "pain",
    "skin",
    "mood",
    "workout_log",
    "payment",
    "gym",
    "gps",
    "credential",
    "key_reference",
    "backup_location",
    "mls_secret",
    "local_row_id",
    "userId",
    "generatedBy",
    "planVersion",
    "climb_name",
    "setter_name",
    "description",
    "hold_sequence",
    "relay_url",
    "group_id",
    "epoch",
)


def _mutate(obj, path, value, delete=False):
    """Return a deep copy of ``obj`` with ``path`` set to ``value``/removed."""
    clone = json.loads(json.dumps(obj))
    cursor = clone
    for key in path[:-1]:
        cursor = cursor[key]
    if delete:
        cursor.pop(path[-1], None)
    else:
        cursor[path[-1]] = value
    return clone


def build_negative_corpus(b) -> dict:
    cases: list[dict] = []

    def wire(name, area, envelope_obj, disposition, reason, *, raw=None, note=None,
             authenticated_sender=None, preload="ACTIVE_WITH_DETAIL_GRANT",
             generator=None):
        content = raw if raw is not None else o.jcs(envelope_obj).decode("utf-8")
        case = {
            "name": name,
            "area": area,
            "case_type": "wire",
            "preload": preload,
            "content_size": len(content.encode("utf-8")),
            "content_sha256": o.sha256_hex(content.encode("utf-8")),
            "authenticated_sender": authenticated_sender,
            "expected_disposition": disposition,
            "expected_reason": reason,
            "note": note,
        }
        if generator is None:
            case["content"] = content
        else:
            # One case is 64 KiB of padding. Storing the recipe plus the exact
            # size and digest pins the same bytes without checking in the noise;
            # the verifier reconstructs and re-hashes them.
            case["content_generator"] = generator
        cases.append(case)

    def carrier(name, area, event, mls_sender, disposition, note=None):
        cases.append(
            {
                "name": name,
                "area": area,
                "case_type": "carrier",
                "event": event,
                "mls_sender": mls_sender,
                "expected_disposition": disposition,
                "expected_reason": None,
                "note": note,
            }
        )

    def state(name, area, kind, payload, disposition, reason, note=None):
        cases.append(
            {
                "name": name,
                "area": area,
                "case_type": kind,
                "state": payload,
                "expected_disposition": disposition,
                "expected_reason": reason,
                "note": note,
            }
        )

    low, high = b.low, b.high
    a1 = b.events["A1"]
    a1_env = json.loads(a1["content"])
    detail_snap = json.loads(b.events["projection_detail_snapshot"]["content"])
    grant_detail = json.loads(b.events["grant_detail_backdated"]["content"])
    grant_summary = json.loads(b.events["grant_summary_set"]["content"])
    revoke = json.loads(b.events["grant_summary_revoke"]["content"])
    plan_snap = json.loads(b.events["projection_plan_snapshot"]["content"])
    delta = json.loads(b.events["projection_detail_delta"]["content"])
    accept_env = json.loads(b.events["acceptance"]["content"])

    # ------------------------------------------------------------------
    # 1. Carrier: six-field MarmotAppEvent, id, sender, kind, tags
    # ------------------------------------------------------------------
    def raw_carrier(**overrides):
        content = overrides.pop("content", a1["content"])
        pubkey = overrides.pop("pubkey", low)
        created_at = overrides.pop("created_at", a1["issued_at"])
        kind = overrides.pop("kind", o.CARRIER_KIND)
        tags = overrides.pop("tags", o.CARRIER_TAGS)
        event_id = overrides.pop(
            "id", o.nip01_event_id(pubkey, created_at, kind, tags, content)
        )
        event = {
            "id": event_id,
            "pubkey": pubkey,
            "created_at": created_at,
            "kind": kind,
            "tags": tags,
            "content": content,
        }
        event.update(overrides)
        return event

    carrier("carrier_wrong_kind_1221", "marmot_app_event", raw_carrier(kind=1221), low,
            o.DROP_BEFORE_PARSE, "wrong kind never reaches a content parse")
    carrier("carrier_tags_reordered", "marmot_app_event",
            raw_carrier(tags=[["l", "v1", o.NAMESPACE], ["L", o.NAMESPACE]]), low,
            o.DROP_BEFORE_PARSE)
    carrier("carrier_l_tag_two_elements", "marmot_app_event",
            raw_carrier(tags=[["L", o.NAMESPACE], ["l", "v1"]]), low, o.DROP_BEFORE_PARSE,
            "the NIP-32 label tag keeps its namespace third element")
    carrier("carrier_extra_tag", "marmot_app_event",
            raw_carrier(tags=[["L", o.NAMESPACE], ["l", "v1", o.NAMESPACE], ["t", "cruxcoach"]]),
            low, o.DROP_BEFORE_PARSE)
    carrier("carrier_wrong_namespace", "marmot_app_event",
            raw_carrier(tags=[["L", "com.cruxcoach.social"], ["l", "v1", "com.cruxcoach.social"]]),
            low, o.DROP_BEFORE_PARSE)
    carrier("carrier_sig_present", "marmot_app_event", raw_carrier(sig="00" * 64), low,
            o.DROP_BEFORE_PARSE, "the pinned encoder has exactly six fields and no sig")
    carrier("carrier_missing_created_at", "marmot_app_event",
            {k: v for k, v in raw_carrier().items() if k != "created_at"}, low,
            o.DROP_BEFORE_PARSE)
    carrier("carrier_wrong_nip01_id", "marmot_app_event", raw_carrier(id="11" * 32), low,
            o.DROP_BEFORE_PARSE)
    carrier("carrier_inner_pubkey_not_mls_sender", "marmot_app_event",
            raw_carrier(pubkey=high), low, o.DROP_BEFORE_PARSE,
            "MDK authenticates the sender leaf before the adapter sees anything")

    # ------------------------------------------------------------------
    # 2. Canonical content
    # ------------------------------------------------------------------
    dup_top = (
        '{"author":"' + low + '","author":"' + high + '","body":{"offer":"ACQUAINTANCE",'
        '"type":"cc.relationship.offer.v1","v":1},"endpoints":["' + low + '","' + high + '"],'
        '"generation_id":"' + b.gen1 + '","issued_at":1786000100,"object_id":"' + b.offer_a
        + '","seq":1,"type":"cc.envelope.v1","v":1}'
    )
    wire("content_duplicate_key_top_level", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JSON_INVALID", raw=dup_top)
    dup_nested = a1["content"].replace(
        '{"offer":"ACQUAINTANCE"', '{"offer":"ACQUAINTANCE","offer":"FRIEND"', 1
    )
    wire("content_duplicate_key_nested", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JSON_INVALID", raw=dup_nested)
    non_jcs = a1["content"].replace('{"author":', '{"v":1,"author":', 1).replace(
        ',"type":"cc.envelope.v1","v":1}', ',"type":"cc.envelope.v1"}'
    )
    wire("content_non_jcs_member_order", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JCS_INVALID", raw=non_jcs)
    wire("content_float_value", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JSON_INVALID",
         raw=a1["content"].replace('"v":1},"endpoints"', '"v":1.0},"endpoints"', 1))
    wire("content_unsafe_integer", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JSON_INVALID",
         raw=a1["content"].replace('"issued_at":1786000100', '"issued_at":9007199254740992', 1))
    wire("content_non_nfc_string", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JCS_INVALID",
         raw=a1["content"].replace('"ACQUAINTANCE"', '"ACQUAINTANCEÅ"', 1))
    # Content that is not UTF-8 at all. One byte of a canonical envelope is
    # replaced by a bare continuation byte, which no decoder may accept, so the
    # object never becomes an envelope and only the §8.6 digest record survives.
    # This is the one closed §8.6 reason whose bytes cannot be written as a JSON
    # string, so the corpus stores the recipe and pins the exact length and
    # digest instead — the same treatment the oversize case gets.
    base_bytes = a1["content"].encode("utf-8")
    assert a1["content"].isascii(), "the recipe indexes bytes, so the base must be ASCII"
    utf8_at = a1["content"].index("ACQUAINTANCE")
    not_utf8 = base_bytes[:utf8_at] + b"\x80" + base_bytes[utf8_at + 1:]
    cases.append(
        {
            "name": "content_not_utf8",
            "area": "canonical_content",
            "case_type": "wire",
            "preload": "ACTIVE_WITH_DETAIL_GRANT",
            "content_generator": {"kind": "invalid_utf8", "base_event": "A1",
                                  "at": utf8_at, "byte_hex": "80"},
            "content_size": len(not_utf8),
            "content_sha256": o.sha256_hex(not_utf8),
            "authenticated_sender": low,
            "expected_disposition": o.BOUNDED_DIGEST_ONLY,
            "expected_reason": "CONTENT_UTF8_INVALID",
            "note": "the namespace matched, so the digest record is written, but the "
                    "content never decodes; no inner event id is claimed for it, "
                    "because wire §8.6 records one only when the event is "
                    "structurally valid",
        }
    )
    padded = a1["content"][:-1] + ',"zz":"' + "x" * o.CONTENT_MAX_BYTES + '"}'
    wire("content_oversize_above_the_cap", "canonical_content", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_OVERSIZE", raw=padded,
         generator={"kind": "suffix_pad", "base_event": "A1", "member": "zz",
                    "char": "x", "repeat": o.CONTENT_MAX_BYTES})
    wire("content_unknown_body_type", "canonical_content",
         _mutate(a1_env, ["body", "type"], "cc.relationship.offer.v2"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")

    # ------------------------------------------------------------------
    # 3. Envelope
    # ------------------------------------------------------------------
    wire("envelope_unknown_member", "cc.envelope.v1",
         dict(sorted({**a1_env, "note": "hi"}.items())),
         o.REJECT_NO_SLOT, "ENVELOPE_SCHEMA_INVALID")
    wire("envelope_unknown_version", "cc.envelope.v1", _mutate(a1_env, ["v"], 2),
         o.REJECT_NO_SLOT, "ENVELOPE_SCHEMA_INVALID")
    wire("envelope_wrong_generation", "cc.envelope.v1",
         _mutate(a1_env, ["generation_id"], b.gen2),
         o.REJECT_NO_SLOT, "GENERATION_BINDING_INVALID")
    wire("envelope_endpoints_unsorted", "cc.envelope.v1",
         _mutate(a1_env, ["endpoints"], [high, low]),
         o.REJECT_NO_SLOT, "ENVELOPE_SCHEMA_INVALID")
    wire("envelope_endpoints_equal", "cc.envelope.v1",
         _mutate(a1_env, ["endpoints"], [low, low]),
         o.REJECT_NO_SLOT, "ENVELOPE_SCHEMA_INVALID")
    wire("envelope_author_not_an_endpoint", "cc.envelope.v1",
         _mutate(a1_env, ["author"], "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"),
         o.REJECT_NO_SLOT, "AUTHOR_BINDING_INVALID")
    wire("envelope_random_object_id", "cc.envelope.v1",
         _mutate(a1_env, ["object_id"], "0f0f0f0f-0f0f-8f0f-8f0f-0f0f0f0f0f0f"),
         o.REJECT_NO_SLOT, "SLOT_BINDING_INVALID")
    wire("envelope_seq_zero", "cc.envelope.v1", _mutate(a1_env, ["seq"], 0),
         o.REJECT_NO_SLOT, "ENVELOPE_SCHEMA_INVALID")
    wire("envelope_previous_at_seq_1", "cc.envelope.v1",
         dict(sorted({**a1_env, "previous_event_id": "11" * 32}.items())),
         o.REJECT_NO_SLOT, "PREDECESSOR_INVALID")
    a2_env = json.loads(b.events["A2"]["content"])
    wire("envelope_missing_previous_above_seq_1", "cc.envelope.v1",
         {k: v for k, v in a2_env.items() if k != "previous_event_id"},
         o.REJECT_NO_SLOT, "PREDECESSOR_INVALID")
    wire("envelope_wrong_predecessor", "cc.envelope.v1",
         _mutate(a2_env, ["previous_event_id"], "22" * 32),
         o.REJECT_NO_SLOT, "PREDECESSOR_INVALID",
         note="the slot stays free; the correct event at seq 2 still applies",
         preload="ACTIVE_WITH_A1")
    wire("envelope_cross_slot_predecessor", "cc.envelope.v1",
         _mutate(a2_env, ["previous_event_id"], b.events["B1"]["inner_event_id"]),
         o.REJECT_NO_SLOT, "PREDECESSOR_INVALID", preload="ACTIVE_WITH_A1")

    # ------------------------------------------------------------------
    # 4. cc.generation.accept.v1
    # ------------------------------------------------------------------
    wire("accept_wrong_author_endpoint_low", "cc.generation.accept.v1",
         _mutate(accept_env, ["author"], low),
         o.REJECT_NO_SLOT, "AUTHOR_BINDING_INVALID", preload="PROVISIONING")
    wire("accept_seq_above_one", "cc.generation.accept.v1",
         dict(sorted({**accept_env, "seq": 2, "previous_event_id": "33" * 32}.items())),
         o.REJECT_NO_SLOT, "SLOT_BINDING_INVALID", preload="PROVISIONING")
    wire("accept_revision_zero", "cc.generation.accept.v1",
         _mutate(accept_env, ["body", "acceptance_revision"], 0),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="PROVISIONING")
    wire("accept_revision_above_range", "cc.generation.accept.v1",
         _mutate(accept_env, ["body", "acceptance_revision"], 1001),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="PROVISIONING")
    wire("accept_missing_revision", "cc.generation.accept.v1",
         _mutate(accept_env, ["body", "acceptance_revision"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="PROVISIONING")
    wire("accept_unknown_member", "cc.generation.accept.v1",
         _mutate(accept_env, ["body", "auto_accepted"], True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="PROVISIONING")
    # The one accept case that is *valid* and still terminal. Golden §7.0e used
    # to say in prose that no byte-exact input for it existed and that no
    # conformance run may report §6.1's acceptance-conflict sentence as covered;
    # these are those bytes. The rule is wire §8.2's slot-agnostic
    # equal-sequence rule, so the acceptance slot must not be the one slot where
    # a second valid event is only assumed to conflict.
    wire("accept_equal_sequence_conflict", "cc.generation.accept.v1",
         json.loads(b.events["acceptance_conflict"]["content"]),
         o.TERMINAL, "EQUAL_SEQUENCE_CONFLICT", preload="PROVISIONING_ACCEPTED",
         note="a second structurally and semantically valid acceptance at seq = 1 "
              "terminalises the generation; no id, time or arrival comparison "
              "chooses a winner")

    # ------------------------------------------------------------------
    # 5. Bootstrap marker
    # ------------------------------------------------------------------
    def marker_case(name, marker_obj, after_activation, disposition, reason,
                    marker_name=o.BOOTSTRAP_NAME, raw_description=None):
        description = (
            raw_description.encode("utf-8")
            if raw_description is not None
            else json.dumps(marker_obj, ensure_ascii=False, sort_keys=True,
                            separators=(",", ":")).encode("utf-8")
        )
        state(
            name,
            "bootstrap_marker_0x8001",
            "bootstrap_marker",
            {
                "name": marker_name,
                "description": description.decode("utf-8"),
                "after_activation": after_activation,
                "attempt_id": b.marker["attempt_id"],
            },
            disposition,
            reason,
        )

    marker_case("marker_changed_protocol_before_activation",
                {**b.marker, "protocol": "com.cruxcoach.private-sharing/bootstrap/v2"},
                False, o.DROP_BEFORE_PARSE, None)
    marker_case("marker_changed_protocol_after_activation",
                {**b.marker, "protocol": "com.cruxcoach.private-sharing/bootstrap/v2"},
                True, o.TERMINAL, "PROFILE_MARKER_CHANGED")
    marker_case("marker_changed_name_after_activation", b.marker, True,
                o.TERMINAL, "PROFILE_MARKER_CHANGED", marker_name="CruxCoach sharing")
    marker_case("marker_missing_member_after_activation",
                {k: v for k, v in b.marker.items() if k != "endpoint_high"}, True,
                o.TERMINAL, "PROFILE_MARKER_CHANGED")
    marker_case("marker_extra_member_after_activation", {**b.marker, "note": "x"}, True,
                o.TERMINAL, "PROFILE_MARKER_CHANGED")
    marker_case("marker_uppercase_hex_after_activation",
                {**b.marker, "endpoint_low": b.low.upper()}, True,
                o.TERMINAL, "PROFILE_MARKER_CHANGED")
    marker_case(
        "marker_non_jcs_member_order_after_activation", b.marker, True,
        o.TERMINAL, "PROFILE_MARKER_CHANGED",
        raw_description=json.dumps(
            {
                "protocol": b.marker["protocol"],
                "attempt_id": b.marker["attempt_id"],
                "endpoint_low": b.marker["endpoint_low"],
                "endpoint_high": b.marker["endpoint_high"],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )
    marker_case("marker_endpoints_disagree_before_activation",
                {**b.marker, "endpoint_high":
                    "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"},
                False, o.DROP_BEFORE_PARSE, None)

    # ------------------------------------------------------------------
    # 6. cc.relationship.offer.v1
    # ------------------------------------------------------------------
    wire("offer_unknown_value", "cc.relationship.offer.v1",
         _mutate(a1_env, ["body", "offer"], "BEST_FRIEND"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("offer_wrong_actor_slot", "cc.relationship.offer.v1",
         _mutate(a1_env, ["object_id"], b.offer_b),
         o.REJECT_NO_SLOT, "SLOT_BINDING_INVALID",
         note="A cannot occupy B's deterministic offer slot")
    wire("offer_unknown_member", "cc.relationship.offer.v1",
         _mutate(a1_env, ["body", "reason"], "because"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")

    # ------------------------------------------------------------------
    # 7. cc.grant.set.v1
    # ------------------------------------------------------------------
    gs = grant_summary
    wire("grant_expiry_beyond_90_days", "cc.grant.set.v1",
         _mutate(gs, ["body", "expires_at"], gs["body"]["issued_at"] + o.GRANT_MAX_DURATION_S + 1),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_expiry_not_after_issue", "cc.grant.set.v1",
         _mutate(gs, ["body", "expires_at"], gs["body"]["issued_at"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_ungrantable_field_token", "cc.grant.set.v1",
         _mutate(gs, ["body", "fields"], ["all_time_send_count", "sends"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_fields_unsorted", "cc.grant.set.v1",
         _mutate(gs, ["body", "fields"], ["hardest_grade_milli", "all_time_send_count"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_fields_empty", "cc.grant.set.v1", _mutate(gs, ["body", "fields"], []),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_summary_with_detail_boundary", "cc.grant.set.v1",
         _mutate(gs, ["body", "not_before"], gs["body"]["issued_at"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_detail_without_boundary", "cc.grant.set.v1",
         _mutate(grant_detail, ["body", "not_before"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_detail_wrong_boundary", "cc.grant.set.v1",
         _mutate(grant_detail, ["body", "not_before"], grant_detail["body"]["issued_at"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID",
         note="not_before must be the exact max of grant and both head issue times",
         preload="ACTIVE_HEADS_ONLY")
    wire("grant_resource_on_wrong_scope", "cc.grant.set.v1",
         dict(sorted({**gs, "body": dict(sorted(
             {**gs["body"], "resource_id": b.plan_uuid}.items()))}.items())),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    plan_grant = json.loads(b.events["grant_plan_detail"]["content"])
    wire("grant_plan_detail_two_resources", "cc.grant.set.v1",
         _mutate(plan_grant, ["body", "resource_id"], [b.plan_uuid, b.plan_uuid]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_id_not_uuidv7", "cc.grant.set.v1",
         _mutate(gs, ["body", "grant_id"], b.gen1),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_publisher_not_author", "cc.grant.set.v1",
         _mutate(gs, ["body", "publisher"], high),
         o.REJECT_NO_SLOT, "AUTHOR_BINDING_INVALID")
    wire("grant_recipient_equals_publisher", "cc.grant.set.v1",
         _mutate(gs, ["body", "recipient"], low),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    narrowing = json.loads(b.events["grant_summary_narrowing"]["content"])
    wire("grant_missing_supersession_above_seq_1", "cc.grant.set.v1",
         _mutate(narrowing, ["body", "supersedes_grant_id"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_supersession_at_seq_1", "cc.grant.set.v1",
         _mutate(gs, ["body", "supersedes_grant_id"], narrowing["body"]["grant_id"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("grant_offer_heads_reversed", "cc.grant.set.v1",
         _mutate(gs, ["body", "offer_heads"], list(reversed(gs["body"]["offer_heads"]))),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="ACTIVE_HEADS_ONLY")
    wire("grant_offer_head_missing_is_held", "cc.grant.set.v1", gs,
         o.HELD, "HOLD_OFFER_HEAD_MISSING", preload="ACTIVE_NO_HEADS",
         note="a missing dependency holds; it never rejects and never terminalises")
    wire("grant_detail_on_acquaintance_heads", "cc.grant.set.v1",
         _mutate(grant_detail, ["body", "offer_heads"],
                 [b.events["A1"]["inner_event_id"], b.events["B1"]["inner_event_id"]]),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="ACTIVE_ACQUAINTANCE_HEADS",
         note="existing heads that do not establish FRIEND reject the whole grant")

    # ------------------------------------------------------------------
    # 8. cc.grant.revoke.v1
    # ------------------------------------------------------------------
    wire("revoke_unknown_reason", "cc.grant.revoke.v1",
         _mutate(revoke, ["body", "reason"], "CLEANUP"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_CHAIN")
    wire("revoke_publisher_not_author", "cc.grant.revoke.v1",
         _mutate(revoke, ["body", "publisher"], high),
         o.REJECT_NO_SLOT, "AUTHOR_BINDING_INVALID", preload="ACTIVE_SUMMARY_CHAIN")
    wire("revoke_wrong_scope_slot", "cc.grant.revoke.v1",
         _mutate(revoke, ["body", "scope"], "LOGBOOK_DETAIL"),
         o.REJECT_NO_SLOT, "SLOT_BINDING_INVALID", preload="ACTIVE_SUMMARY_CHAIN")
    wire("revoke_of_noncurrent_grant", "cc.grant.revoke.v1",
         _mutate(revoke, ["body", "grant_id"], gs["body"]["grant_id"]),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="ACTIVE_SUMMARY_CHAIN")
    wire("revoke_recipient_equals_publisher", "cc.grant.revoke.v1",
         _mutate(revoke, ["body", "recipient"], low),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_CHAIN")

    # ------------------------------------------------------------------
    # 9. Projection bodies
    # ------------------------------------------------------------------
    wire("projection_seq_1_is_a_delta", "projection_bodies",
         _mutate(_mutate(delta, ["seq"], 1), ["previous_event_id"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_WITH_DETAIL_GRANT")
    wire("projection_wrong_grant_event_id", "projection_bodies",
         _mutate(detail_snap, ["body", "grant_event_id"], "44" * 32),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID")
    wire("projection_row_shape_disagrees_with_fields", "projection_bodies",
         _mutate(detail_snap, ["body", "fields"], ["board_config_id"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID",
         note="the row members follow fields exactly; a leftover member is a schema fault")
    narrowed_rows = _mutate(
        _mutate(detail_snap, ["body", "fields"], ["board_config_id"]),
        ["body", "payload", "sessions"],
        [{"board_config_id": b.board_config_id, "session_uuid": b.session_1}])
    wire("projection_fields_do_not_equal_the_grant", "projection_bodies", narrowed_rows,
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID",
         note="internally consistent, but the fields no longer equal the grant")
    wire("projection_mismatching_boundary", "projection_bodies",
         _mutate(_mutate(detail_snap, ["body", "prospective_from"], 1786000201),
                 ["body", "payload", "prospective_from"], 1786000201),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID")
    wire("projection_delta_zero_operations", "projection_bodies",
         _mutate(delta, ["body", "ops"], []),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT")
    wire("projection_delta_51_operations", "projection_bodies",
         _mutate(delta, ["body", "ops"], [delta["body"]["ops"][0]] * 51),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT")
    wire("projection_snapshot_before_grant_is_held", "projection_bodies", detail_snap,
         o.HELD, "HOLD_GRANT_MISSING", preload="ACTIVE_HEADS_ONLY")
    wire("projection_authored_by_the_other_endpoint", "projection_bodies",
         _mutate(detail_snap, ["author"], high),
         o.REJECT_NO_SLOT, "AUTHOR_BINDING_INVALID",
         authenticated_sender=high,
         note="projection.author must equal the referenced grant's publisher")
    wire("projection_summary_with_detail_boundary", "projection_bodies",
         _mutate(json.loads(b.events["projection_summary_snapshot"]["content"]),
                 ["body", "prospective_from"], 1786000200),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT")

    # ------------------------------------------------------------------
    # 10. Board and problem (producer-side derivation refusals)
    # ------------------------------------------------------------------
    def derivation(name, payload, note):
        state(name, "board_and_problem", "derivation", payload,
              o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", note)

    derivation("board_with_catalogue_revision",
               {"kind": "board", "board": {**b_board(b), "catalogue_revision": 7}},
               "no catalogue dimension exists in v1")
    derivation("board_moonboard_with_product_id",
               {"kind": "board", "board": {"angle_mdeg": 40000, "ecosystem": "MOONBOARD",
                                           "layout_id": 1, "product_id": 1, "size_id": 7,
                                           "type": "cc.board", "v": 1}},
               "product_id/size_id are forbidden for MOONBOARD")
    derivation("board_missing_product_id_for_kilter",
               {"kind": "board", "board": {"angle_mdeg": 40000, "ecosystem": "KILTER",
                                           "layout_id": 1, "type": "cc.board", "v": 1}},
               "product_id/size_id are required for every non-MOONBOARD ecosystem")
    derivation("board_angle_out_of_range",
               {"kind": "board", "board": {**b_board(b), "angle_mdeg": 90001}},
               "angle_mdeg is 0..90000")
    derivation("problem_missing_hold_fingerprint",
               {"kind": "problem", "problem": {k: v for k, v in b.problem.items()
                                               if k != "hold_fingerprint"}},
               "the hold fingerprint is mandatory for every problem type")
    derivation("problem_provider_id_grammar",
               {"kind": "problem", "problem": {**b.problem, "provider_problem_id": "Crimp Line"}},
               "provider_problem_id is 1..64 chars of ^[0-9a-f-]+$ and never a display name")
    derivation("problem_with_catalogue_revision",
               {"kind": "problem", "problem": {**b.problem, "catalogue_revision": 7}},
               "no catalogue dimension exists in v1")
    derivation("holds_duplicate_position_conflicting_roles",
               {"kind": "holds", "ecosystem": "KILTER",
                "frames": [[[1164, 12], [1164, 13]]]},
               "a duplicate position with conflicting roles makes the ascent ineligible")

    # ------------------------------------------------------------------
    # 11. Summary payloads
    # ------------------------------------------------------------------
    summary_snap = json.loads(b.events["projection_summary_snapshot"]["content"])
    wire("summary_ungranted_token_present", "summary_payloads",
         _mutate(summary_snap, ["body", "payload", "streak_days"], 7),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT",
         note="the payload carries exactly the granted tokens and nothing else")
    wire("summary_fields_do_not_equal_the_grant", "summary_payloads",
         _mutate(_mutate(summary_snap, ["body", "fields"], ["all_time_send_count"]),
                 ["body", "payload", "hardest_grade_milli"], None, delete=True),
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="ACTIVE_SUMMARY_GRANT",
         note="internally consistent, but the fields no longer equal the grant")
    wire("summary_granted_token_missing", "summary_payloads",
         _mutate(summary_snap, ["body", "payload", "hardest_grade_milli"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT")
    wire("summary_grade_out_of_range", "summary_payloads",
         _mutate(summary_snap, ["body", "payload", "hardest_grade_milli"], 9000),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT")
    wire("summary_send_count_out_of_range", "summary_payloads",
         _mutate(summary_snap, ["body", "payload", "all_time_send_count"], 1_000_001),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT")
    wire("summary_unknown_payload_member", "summary_payloads",
         _mutate(summary_snap, ["body", "payload", "streak"], 3),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_SUMMARY_GRANT")

    # ------------------------------------------------------------------
    # 12. Logbook detail rows — including the 900-second boundary
    # ------------------------------------------------------------------
    row_path = ["body", "payload", "sessions", 0]
    wire("detail_start_not_900_aligned", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["started_at_utc"], 1786003345),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID",
         note="the wire value is always floor900; an unrounded value is invalid")
    wire("detail_start_below_prospective_from", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["started_at_utc"], o.floor900(1786000250)),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID",
         note="floor900(1786000250)=1785999600 is 600 s before the boundary and is refused")
    wire("detail_start_exactly_one_bucket_early", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["started_at_utc"], o.floor900(1786000200) ),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_duration_not_multiple_of_15", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["duration_minutes"], 77),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_duration_below_minimum", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["duration_minutes"], 0),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_sends_truncated_companion", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["sends_truncated"], True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_sends_unsorted", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["sends"],
                 [{"grade_milli": 21000, "problem_hash": "ff" * 32},
                  {"grade_milli": 21000, "problem_hash": "0f" * 32}]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_sends_101_rows", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["sends"],
                 [{"grade_milli": 21000, "problem_hash": f"{i:064x}"} for i in range(101)]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_sessions_101_rows", "logbook_detail_rows",
         _mutate(detail_snap, ["body", "payload", "sessions"],
                 _many_sessions(detail_snap, 101)),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_sessions_unsorted", "logbook_detail_rows",
         _mutate(detail_snap, ["body", "payload", "sessions"],
                 list(reversed(_many_sessions(detail_snap, 2)))),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("detail_row_missing_granted_member", "logbook_detail_rows",
         _mutate(detail_snap, row_path + ["board_config_id"], None, delete=True),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")

    # ------------------------------------------------------------------
    # 13. Plan detail rows
    # ------------------------------------------------------------------
    wire("plan_null_at_sequence_1", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan"], None),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_free_text_title", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "title"], "Spring block"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_exercise_shaped_row", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "sessions", 0, "exercise"], "hangboard"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_payload_level_plan_uuid", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan_uuid"], b.plan_uuid),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_focus_areas_unknown_token", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "focus_areas"], ["cardio", "power"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_focus_areas_nine_values", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "focus_areas"],
                 sorted(o.FOCUS_AREAS) + ["core"]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_sessions_fifteen_rows", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "sessions"],
                 [dict(plan_snap["body"]["payload"]["plan"]["sessions"][0],
                       day_of_week=i % 7) for i in range(15)]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_session_unknown_member", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "sessions", 0, "notes"], "easy"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_session_rpe_out_of_range", "plan_detail_rows",
         _mutate(plan_snap, ["body", "payload", "plan", "sessions", 0, "target_rpe_deci"], 101),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_PLAN_GRANT")
    wire("plan_session_float_rpe", "plan_detail_rows", None,
         o.BOUNDED_DIGEST_ONLY, "CONTENT_JSON_INVALID",
         raw=b.events["projection_plan_snapshot"]["content"].replace(
             '"target_rpe_deci":80', '"target_rpe_deci":8.0e1', 1),
         preload="ACTIVE_PLAN_GRANT")

    # ------------------------------------------------------------------
    # 14. Delta operations
    # ------------------------------------------------------------------
    wire("delta_unknown_operation_name", "delta_operations",
         _mutate(delta, ["body", "ops", 0, "op"], "PATCH_SESSION"),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT")
    wire("delta_delete_session_extra_member", "delta_operations",
         _mutate(delta, ["body", "ops"],
                 [{"op": "DELETE_SESSION", "reason": "x", "session_uuid": b.session_1}]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT")
    wire("delta_scope_incompatible_operation", "delta_operations",
         _mutate(delta, ["body", "ops"], [{"op": "UPSERT_PLAN", "plan": None}]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT")
    wire("delta_result_exceeds_100_sessions", "delta_operations",
         _mutate(delta, ["body", "ops"],
                 [{"op": "UPSERT_SESSION", "row": row}
                  for row in _many_sessions(detail_snap, 50)][:50]),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID", preload="ACTIVE_DETAIL_SNAPSHOT_FULL",
         note="the resulting state is checked before the commit; the whole delta is refused")

    # ------------------------------------------------------------------
    # 15. Removed shapes
    # ------------------------------------------------------------------
    for member in ("part", "part_index", "part_count", "base_seq"):
        wire(f"removed_shape_{member}", "removed_shapes",
             _mutate(a1_env, ["body", member], 1 if member != "part" else "AA"),
             o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("removed_shape_projection_request_body", "removed_shapes",
         _mutate(a1_env, ["body"], {"scope": "LOGBOOK_SUMMARY",
                                    "type": "cc.projection.request.v1", "v": 1}),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    wire("removed_shape_connection_intent_body", "removed_shapes",
         _mutate(a1_env, ["body"], {"peer": high, "type": "cc.connection.intent.v1", "v": 1}),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")
    carrier("removed_shape_kind_1221_carrier", "removed_shapes",
            raw_carrier(kind=1221), low, o.DROP_BEFORE_PARSE)
    wire("removed_shape_catalogue_revision_member", "removed_shapes",
         _mutate(detail_snap, ["body", "payload", "catalogue_revision"], 7),
         o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")

    # ------------------------------------------------------------------
    # 16. Payload prohibition — one concrete case per forbidden member
    # ------------------------------------------------------------------
    for member in PROHIBITED_MEMBERS:
        wire(f"prohibited_member_{member}", "payload_prohibition",
             _mutate(detail_snap, ["body", "payload", "sessions", 0, member], "x"),
             o.REJECT_NO_SLOT, "BODY_SCHEMA_INVALID")

    # ------------------------------------------------------------------
    # 17. Component profile
    # ------------------------------------------------------------------
    base_topology = {
        "phase": "ACTIVE",
        "endpoint_low": low,
        "endpoint_high": high,
        "acceptance_applied": True,
        "mdk_lifecycle": "Stable",
        "signed_lifecycle": "active",
        "lifecycle_byte": "0x00",
        "group_context_required": sorted(o.REQUIRED_COMPONENT_IDS),
        "group_context_state": list(o.GROUP_CONTEXT_STATE_IDS),
        "leaf_dictionaries": [["0x0001", "0x0002", "0x8009"], ["0x0001", "0x0002", "0x8009"]],
        "admin_accounts": [low, high],
        "leaf_accounts": [low, high],
    }
    for missing in o.REQUIRED_COMPONENT_IDS:
        payload = json.loads(json.dumps(base_topology))
        payload["group_context_required"] = [c for c in payload["group_context_required"]
                                             if c != missing]
        state(f"profile_required_id_missing_{missing}", "component_profile", "topology",
              payload, o.TERMINAL, "TOPOLOGY_INVALID")
    for forbidden in o.FORBIDDEN_COMPONENT_IDS:
        payload = json.loads(json.dumps(base_topology))
        payload["group_context_state"] = sorted(payload["group_context_state"] + [forbidden])
        note = ("an absent 0x8005 and a present 0x8005 carrying zero are different "
                "canonical states") if forbidden == "0x8005" else None
        state(f"profile_forbidden_id_present_{forbidden}", "component_profile", "topology",
              payload, o.TERMINAL, "TOPOLOGY_INVALID", note)
    payload = json.loads(json.dumps(base_topology))
    payload["group_context_state"] = sorted(payload["group_context_state"] + ["0x80ff"])
    state("profile_unknown_component_id", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID")
    payload = json.loads(json.dumps(base_topology))
    payload["group_context_state"] = sorted(payload["group_context_state"] + ["0x8009"])
    state("profile_0x8009_in_group_context", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID",
          "the account-identity proof is LeafNode-only at the MDK pin")
    payload = json.loads(json.dumps(base_topology))
    payload["group_context_has_0x0002"] = True
    state("profile_0x0002_in_group_context", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID",
          "a GroupContext safe_aad entry would enable framing MDK does not implement")
    payload = json.loads(json.dumps(base_topology))
    payload["leaf_dictionaries"] = [["0x0001", "0x0002"], ["0x0001", "0x0002", "0x8009"]]
    state("profile_leaf_missing_0x8009", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID")
    payload = json.loads(json.dumps(base_topology))
    payload["admin_accounts"] = [low]
    state("profile_active_missing_high_admin", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID",
          "after promotion the admin policy must be exactly both endpoints")
    payload = json.loads(json.dumps(base_topology))
    payload["phase"] = "PROVISIONING_INVITED"
    payload["admin_accounts"] = [low, high]
    payload["leaf_accounts"] = [low, high]
    state("profile_provisioning_premature_high_admin", "component_profile", "topology", payload,
          o.TERMINAL, "TOPOLOGY_INVALID",
          "promotion before the canonical acceptance is not the expected provisioning state")

    # ------------------------------------------------------------------
    # 18. Bootstrap and provisioning
    # ------------------------------------------------------------------
    payload = json.loads(json.dumps(base_topology))
    payload["actual_creator"] = high
    state("bootstrap_created_by_endpoint_high", "bootstrap_and_provisioning", "topology",
          payload, o.TERMINAL, "WRONG_CREATOR")
    payload = json.loads(json.dumps(base_topology))
    payload["duplicate_nonterminal_groups"] = ["3f1c9d7e", "0a2b4c6d"]
    state("bootstrap_two_nonterminal_groups", "bootstrap_and_provisioning", "topology",
          payload, o.TERMINAL, "DUPLICATE_GENERATION")
    state("bootstrap_attempt_id_reused_after_abort", "bootstrap_and_provisioning", "attempt",
          {"attempt_id": b.marker["attempt_id"], "reused_after_abort": True},
          o.TERMINAL, "ATTEMPT_ID_REUSED")
    state("bootstrap_attempt_id_derived_from_clock", "bootstrap_and_provisioning", "attempt",
          {"attempt_id": b.marker["attempt_id"], "derived_from_observable_input": True},
          o.TERMINAL, "ATTEMPT_ID_NOT_CSPRNG")
    state("bootstrap_attempt_id_not_uuid4", "bootstrap_and_provisioning", "attempt",
          {"attempt_id": b.gen1}, o.TERMINAL, "ATTEMPT_ID_NOT_CSPRNG")
    state("bootstrap_crash_retry_created_second_group", "bootstrap_and_provisioning", "attempt",
          {"attempt_id": b.marker["attempt_id"], "second_group_for_same_attempt": True},
          o.TERMINAL, "DUPLICATE_GENERATION")
    wire("provisioning_offer_before_acceptance", "bootstrap_and_provisioning", a1_env,
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="PROVISIONING",
         note="rejected outright, not merely held; sending it is equally forbidden")
    wire("provisioning_grant_before_acceptance", "bootstrap_and_provisioning", grant_summary,
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="PROVISIONING")
    wire("provisioning_projection_before_acceptance", "bootstrap_and_provisioning", detail_snap,
         o.REJECT_NO_SLOT, "DEPENDENCY_INVALID", preload="PROVISIONING")

    # ------------------------------------------------------------------
    # 19. Reducer and topology
    # ------------------------------------------------------------------
    wire("equal_sequence_conflict_terminalises", "reducer_and_topology",
         json.loads(b.events["A2_conflict"]["content"]),
         o.TERMINAL, "EQUAL_SEQUENCE_CONFLICT", preload="ACTIVE_A1_A2")
    wire("cross_publisher_same_grant_id", "reducer_and_topology",
         _cross_publisher_grant(b), o.TERMINAL, "GRANT_ID_COLLISION",
         authenticated_sender=high, preload="ACTIVE_WITH_DETAIL_GRANT",
         note="B reuses A's grant id; the ids are unique generation-wide, never shared")
    wire("fresh_snapshot_cannot_heal_a_terminal_generation", "reducer_and_topology",
         detail_snap, o.TERMINAL, "EQUAL_SEQUENCE_CONFLICT", preload="TERMINAL_EQUAL_SEQUENCE")
    state("invalidation_single_match", "reducer_and_topology", "invalidation",
          {"source_message_id": "11" * 32,
           "matched_inner_event_ids": [b.events["projection_detail_snapshot"]["inner_event_id"]]},
          o.TERMINAL, "STORED_EVENT_INVALIDATED")
    state("invalidation_zero_match", "reducer_and_topology", "invalidation",
          {"source_message_id": "11" * 32, "matched_inner_event_ids": []},
          o.TERMINAL, "SOURCE_MAPPING_MISSING")
    state("invalidation_multi_match", "reducer_and_topology", "invalidation",
          {"source_message_id": "11" * 32,
           "matched_inner_event_ids": [b.events["A1"]["inner_event_id"],
                                       b.events["B1"]["inner_event_id"]]},
          o.TERMINAL, "SOURCE_MAPPING_AMBIGUOUS")
    payload = json.loads(json.dumps(base_topology))
    payload["mdk_quarantined"] = True
    state("mdk_quarantine_is_terminal", "reducer_and_topology", "topology", payload,
          o.TERMINAL, "MDK_QUARANTINE")
    payload = json.loads(json.dumps(base_topology))
    payload["mdk_lifecycle"] = "Unrecoverable"
    state("mdk_unrecoverable_is_terminal", "reducer_and_topology", "topology", payload,
          o.TERMINAL, "MDK_UNRECOVERABLE")
    payload = json.loads(json.dumps(base_topology))
    payload.update({"phase": "DISBANDED", "signed_lifecycle": "disbanded",
                    "lifecycle_byte": "0x01", "admin_accounts": [low],
                    "leaf_accounts": [low]})
    state("authenticated_disband_is_terminal", "reducer_and_topology", "topology", payload,
          o.TERMINAL, "LIFECYCLE_NOT_ACTIVE",
          "the disband commit itself removes every leaf but the committer's and reduces "
          "the admin policy to the committer")
    for commit_class in o.AUTHORITY_COMMITS:
        state(f"commit_rollback_{commit_class}_after_activation", "reducer_and_topology",
              "rollback",
              {"event": "GroupStateInvalidated", "commit_class": commit_class,
               "generation_state": "ACTIVE",
               "invalidated_commit_id": "55" * 32},
              o.TERMINAL, "COMMIT_ROLLBACK_AFTER_ACTIVATION")
    state("held_events_exceed_slot_bound", "reducer_and_topology", "held_bound",
          {"slot": "offer_A", "held_events": o.BOUNDS["held_events_per_slot"] + 1},
          o.TERMINAL, "STORAGE_BOUND_EXCEEDED")
    state("held_bytes_exceed_generation_bound", "reducer_and_topology", "held_bound",
          {"slot": "offer_A", "held_bytes": o.BOUNDS["held_bytes_per_generation"] + 1},
          o.TERMINAL, "STORAGE_BOUND_EXCEEDED")
    state("plan_detail_slots_exceed_publisher_bound", "reducer_and_topology", "held_bound",
          {"plan_detail_slots": o.BOUNDS["plan_detail_slots_per_publisher"] + 1},
          o.TERMINAL, "STORAGE_BOUND_EXCEEDED")

    # ------------------------------------------------------------------
    # 20. Adapter runtime gates — the R5 and R6 findings as executable rules.
    #     These are local fail-closed rules, so they hold regardless of which
    #     spike proved what: R6 refuted several R5 absence claims without making
    #     any of the dispositions below safe to relax.
    # ------------------------------------------------------------------
    inner_revoke = b.events["grant_summary_revoke"]["inner_event_id"]
    state("receipt_binds_two_source_ids_to_one_inner_id", "adapter_runtime_gates", "receipt",
          {"intent_id": "intent-neg-1", "inner_event_id": inner_revoke,
           "source_message_ids": ["aa" * 32, "bb" * 32], "retry_kind": "none"},
          o.TERMINAL, "RETRY_NOT_TRANSPORT_IDENTICAL",
          "two distinct source ids for one inner id break the single-valued correlation "
          "that wire §8.4's exact invalidation mapping depends on")
    state("receipt_retry_mints_a_new_transport_id", "adapter_runtime_gates", "receipt",
          {"intent_id": "intent-neg-2", "inner_event_id": inner_revoke,
           "source_message_ids": ["aa" * 32], "retry_kind": "new_transport_id"},
          o.TERMINAL, "RETRY_NOT_TRANSPORT_IDENTICAL",
          "the removed R5 payload requeue: a re-encrypted send is a new event, never a "
          "retry of the same inner id. R6 proved the pin can re-drive the identical "
          "message; it proved nothing that would make a new transport id a retry")
    state("ingress_live_lag_without_closed_replay", "adapter_runtime_gates", "ingress",
          {"live_lagged": True, "durable_replay_available": True,
           "replay_gap_closed": False, "pruned_before_ack": False},
          o.HELD, "HOLD_REPLAY_INCOMPLETE",
          "a lagged live subscription proves nothing about completeness until durable "
          "replay closes the gap")
    state("ingress_gap_already_pruned_by_retention", "adapter_runtime_gates", "ingress",
          {"live_lagged": True, "durable_replay_available": True,
           "replay_gap_closed": False, "pruned_before_ack": True},
          o.TERMINAL, "PROJECTION_AUTHORITY_INVALID",
          "retention removed the bytes replay would have needed; nothing is reconstructed")
    state("projection_reads_the_pruned_raw_store_as_authority", "adapter_runtime_gates",
          "projection_authority",
          {"authority": "mdk_raw", "raw_pruned": True, "host_acked": True},
          o.TERMINAL, "PROJECTION_AUTHORITY_INVALID",
          "R5 criterion E: active group retention prunes raw custom rows, so the raw app "
          "store is never long-lived CruxCoach state")
    state("projection_item_pruned_before_host_ack", "adapter_runtime_gates",
          "projection_authority",
          {"authority": "cruxcoach_durable", "raw_pruned": True, "host_acked": False},
          o.TERMINAL, "PROJECTION_AUTHORITY_INVALID",
          "pre-ACK durability is the whole point of the dedicated inbox")

    covered = {case["area"] for case in cases}
    missing = [area for area in AREAS if area not in covered]
    if missing:
        raise SystemExit(f"negative corpus does not cover: {missing}")

    return {
        "schema": "cc.fixture.negative.v1",
        "protocol": o.NAMESPACE + "/v1",
        "note": (
            "Every case is concrete input plus exactly one expected disposition from "
            "the closed five, with a reason code from that disposition's closed set. "
            "Evaluated by the Python spec oracle only; no product code runs."
        ),
        "dispositions": list(o.DISPOSITIONS),
        "areas": list(AREAS),
        "cases": cases,
    }


def b_board(b) -> dict:
    return dict(json.loads(json.dumps(b.problem["board"])))


def _many_sessions(detail_snap: dict, count: int) -> list:
    template = detail_snap["body"]["payload"]["sessions"][0]
    rows = []
    for i in range(count):
        row = json.loads(json.dumps(template))
        row["session_uuid"] = f"{i:08x}-0000-8000-8000-000000000000"
        rows.append(row)
    return sorted(rows, key=lambda r: r["session_uuid"])


def _cross_publisher_grant(b) -> dict:
    """B publishes a grant reusing A's grant id."""
    grant = json.loads(b.events["grant_detail_backdated"]["content"])
    body = dict(grant["body"])
    body["publisher"] = b.high
    body["recipient"] = b.low
    body["scope"] = "LOGBOOK_SUMMARY"
    body["fields"] = ["all_time_send_count"]
    body.pop("not_before", None)
    grant["author"] = b.high
    grant["body"] = dict(sorted(body.items()))
    grant["object_id"] = o.grant_slot(b.gen1, b.high, "LOGBOOK_SUMMARY")[2]
    grant["seq"] = 1
    grant.pop("previous_event_id", None)
    grant["issued_at"] = body["issued_at"]
    return dict(sorted(grant.items()))
