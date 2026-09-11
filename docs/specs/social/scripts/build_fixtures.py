#!/usr/bin/env python3
"""Deterministically rebuild the FEAT-062 checked-in fixture files.

Every value is derived by `private_sharing_oracle` from the closed inputs at
the top of this file. Nothing is transcribed by hand, so a golden value can
only change when an input or a normative formula changes.

    python3 docs/specs/social/scripts/build_fixtures.py            # rewrite
    python3 docs/specs/social/scripts/build_fixtures.py --print    # dry run

The verifier (`verify_private_sharing.py`) re-derives the same values
independently of the stored files and additionally checks them against
`PRIVATE-SHARING-GOLDEN-VECTORS.md`, so a stale fixture file fails the build
rather than silently becoming the new truth.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import private_sharing_oracle as o  # noqa: E402

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"

# --------------------------------------------------------------------------
# Closed inputs
# --------------------------------------------------------------------------

A = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
B = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
G1 = "3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"
G2 = "0a2b4c6d8e0f10213243546576879809a1b2c3d4e5f60718293a4b5c6d7e8f90"
ATTEMPT_ID = "9f6c2d81-4b7e-4a3c-9d2f-16b83a7c5e40"
LOCAL_SESSION_1 = "018bcfe5-6800-7000-8000-0000000000aa"
LOCAL_SESSION_2 = "018bcfe5-6800-7000-8000-0000000000ab"
LOCAL_PLAN = "018bcfe5-6800-7000-8000-0000000000bb"

BOARD = {
    "angle_mdeg": 40000,
    "ecosystem": "KILTER",
    "layout_id": 1,
    "product_id": 1,
    "size_id": 7,
    "type": "cc.board",
    "v": 1,
}
FRAMES = [[(1164, 12), (1233, 13), (1392, 14)]]

T_ACCEPT = 1786000050
#: The competing acceptance of golden §7.0e. It differs from `acceptance` in its
#: `acceptance_revision` and its `issued_at`, so it is a second *structurally and
#: semantically valid* event at `seq = 1` of the acceptance slot rather than a
#: malformed one — which is precisely what wire §8.2's equal-sequence rule is
#: about, and what the corpus previously had no byte-exact input for.
T_ACCEPT_CONFLICT = 1786000060
T_A1 = 1786000100
T_A2 = 1786000150
T_CONFLICT = 1786000160
T_GRANT_DETAIL = 1786000180
T_B1 = 1786000200
T_GRANT_SUMMARY = 1786000300
T_GRANT_NARROW = 1786000400
T_REVOKE = 1786000500
T_SNAP_SUMMARY = 1786000600
T_SNAP_DETAIL = 1786000700
T_DELTA_DETAIL = 1786000800
T_GRANT_PLAN = 1786000900
T_SNAP_PLAN = 1786001000
T_DELTA_BOUNDARY = 1786001050
T_A3 = 1786001100
T_A4 = 1786001200
T_A5 = 1786001300

#: Grant ids are UUIDv7 whose 48 timestamp bits equal `issued_at * 1000`.
GRANT_SUMMARY = o.uuid7(T_GRANT_SUMMARY * 1000, 0x00A, 0x0000_0000_0000_0A)
GRANT_DETAIL = o.uuid7(T_GRANT_DETAIL * 1000, 0x00B, 0x0000_0000_0000_0B)
GRANT_NARROW = o.uuid7(T_GRANT_NARROW * 1000, 0x00C, 0x0000_0000_0000_0C)
GRANT_PLAN = o.uuid7(T_GRANT_PLAN * 1000, 0x00D, 0x0000_0000_0000_0D)

DETAIL_FIELDS = ["board_config_id", "duration_minutes", "sends", "started_at_utc"]
PLAN_FIELDS = ["focus_areas", "phase", "sessions", "sessions_per_week"]
SUMMARY_FIELDS = ["all_time_send_count", "hardest_grade_milli"]


class Builder:
    def __init__(self) -> None:
        self.low, self.high = o.sort_endpoints(A, B)
        assert (self.low, self.high) == (A, B)
        self.derivations: list[dict] = []
        self.events: dict[str, dict] = {}

    # -- derivations -----------------------------------------------------
    def record(self, name: str, triple, kind: str = "uuid8") -> str:
        preimage, digest, value = triple
        self.derivations.append(
            {"name": name, "kind": kind, "preimage_hex": preimage, "sha256": digest, "value": value}
        )
        return value

    def build_derivations(self) -> None:
        self.gen1 = self.record("generation_group1", o.generation_id(G1, self.low, self.high))
        self.gen2 = self.record("generation_group2", o.generation_id(G2, self.low, self.high))
        pair_pre, pair_sha = o.endpoint_pair_id(self.low, self.high)
        self.derivations.append(
            {
                "name": "endpoint_pair_id",
                "kind": "sha256",
                "preimage_hex": pair_pre,
                "sha256": pair_sha,
                "value": pair_sha,
            }
        )
        self.accept_slot = self.record(
            "acceptance_slot", o.acceptance_slot(self.gen1, self.high)
        )
        self.offer_a = self.record("offer_A", o.offer_slot(self.gen1, self.low))
        self.offer_b = self.record("offer_B", o.offer_slot(self.gen1, self.high))

        self.board_config_id = o.board_config_id(BOARD)
        self.hold_pre, self.hold_fp = o.hold_fingerprint("KILTER", FRAMES)
        self.problem = {
            "board": BOARD,
            "hold_fingerprint": self.hold_fp,
            "is_mirror": False,
            "provider_problem_id": "123",
            "type": "cc.problem",
            "v": 1,
        }
        self.problem_hash = o.problem_hash(self.problem)

        self.session_1 = self.record(
            "session_uuid_1",
            o.session_uuid(self.gen1, self.high, LOCAL_SESSION_1, self.board_config_id),
        )
        self.session_2 = self.record(
            "session_uuid_2",
            o.session_uuid(self.gen1, self.high, LOCAL_SESSION_2, self.board_config_id),
        )
        self.plan_uuid = self.record(
            "plan_uuid", o.plan_uuid(self.gen1, self.high, LOCAL_PLAN)
        )

        self.grant_slot_summary = self.record(
            "grant_slot_LOGBOOK_SUMMARY", o.grant_slot(self.gen1, self.low, "LOGBOOK_SUMMARY")
        )
        self.grant_slot_detail = self.record(
            "grant_slot_LOGBOOK_DETAIL", o.grant_slot(self.gen1, self.low, "LOGBOOK_DETAIL")
        )
        self.grant_slot_plan = self.record(
            "grant_slot_PLAN_DETAIL",
            o.grant_slot(self.gen1, self.low, "PLAN_DETAIL", self.plan_uuid),
        )
        self.proj_summary = self.record(
            f"projection_slot_{GRANT_SUMMARY}",
            o.projection_slot(self.gen1, self.low, GRANT_SUMMARY),
        )
        self.proj_detail = self.record(
            f"projection_slot_{GRANT_DETAIL}",
            o.projection_slot(self.gen1, self.low, GRANT_DETAIL),
        )
        self.proj_plan = self.record(
            f"projection_slot_{GRANT_PLAN}",
            o.projection_slot(self.gen1, self.low, GRANT_PLAN),
        )
        # Same grant id, other publisher: a distinct slot, never a shared one.
        self.proj_detail_by_b = self.record(
            f"projection_slot_cross_publisher_{GRANT_DETAIL}",
            o.projection_slot(self.gen1, self.high, GRANT_DETAIL),
        )

        marker = o.bootstrap_marker(ATTEMPT_ID, self.low, self.high)
        self.marker = marker
        self.marker_bytes = o.jcs(marker)
        self.component_0x8001 = o.bootstrap_component_0x8001(
            o.BOOTSTRAP_NAME, self.marker_bytes
        )

    # -- events ----------------------------------------------------------
    def envelope(self, author, object_id, seq, issued_at, body, generation=None, previous=None):
        env = {
            "author": author,
            "body": body,
            "endpoints": [self.low, self.high],
            "generation_id": generation or self.gen1,
            "issued_at": issued_at,
            "object_id": object_id,
            "seq": seq,
            "type": "cc.envelope.v1",
            "v": 1,
        }
        if previous is not None:
            env["previous_event_id"] = previous
        return env

    def emit(self, name: str, env: dict, full_event: bool = False, pubkey: str | None = None):
        content = o.jcs(env).decode("utf-8")
        author = pubkey or env["author"]
        event_id = o.nip01_event_id(
            author, env["issued_at"], o.CARRIER_KIND, o.CARRIER_TAGS, content
        )
        record = {
            "name": name,
            "author": author,
            "issued_at": env["issued_at"],
            "content": content,
            "content_size": len(content.encode("utf-8")),
            "content_sha256": o.sha256_hex(content.encode("utf-8")),
            "inner_event_id": event_id,
        }
        if full_event:
            full = o.marmot_app_event(event_id, author, env["issued_at"], content)
            record["full_event"] = full
            record["full_event_size"] = len(full.encode("utf-8"))
            record["full_event_sha256"] = o.sha256_hex(full.encode("utf-8"))
        self.events[name] = record
        return record

    def build_events(self) -> None:
        acc = self.emit(
            "acceptance",
            self.envelope(
                self.high,
                self.accept_slot,
                1,
                T_ACCEPT,
                {"acceptance_revision": 1, "type": "cc.generation.accept.v1", "v": 1},
            ),
            full_event=True,
        )
        self.emit(
            "acceptance_conflict",
            self.envelope(
                self.high,
                self.accept_slot,
                1,
                T_ACCEPT_CONFLICT,
                {"acceptance_revision": 2, "type": "cc.generation.accept.v1", "v": 1},
            ),
        )
        a1 = self.emit(
            "A1",
            self.envelope(
                self.low,
                self.offer_a,
                1,
                T_A1,
                {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
            ),
            full_event=True,
        )
        b1 = self.emit(
            "B1",
            self.envelope(
                self.high,
                self.offer_b,
                1,
                T_B1,
                {"offer": "FRIEND", "type": "cc.relationship.offer.v1", "v": 1},
            ),
        )
        a2 = self.emit(
            "A2",
            self.envelope(
                self.low,
                self.offer_a,
                2,
                T_A2,
                {"offer": "FRIEND", "type": "cc.relationship.offer.v1", "v": 1},
                previous=a1["inner_event_id"],
            ),
        )
        self.emit(
            "A2_conflict",
            self.envelope(
                self.low,
                self.offer_a,
                2,
                T_CONFLICT,
                {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
                previous=a1["inner_event_id"],
            ),
        )
        a3 = self.emit(
            "A3",
            self.envelope(
                self.low,
                self.offer_a,
                3,
                T_A3,
                {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
                previous=a2["inner_event_id"],
            ),
        )
        a4 = self.emit(
            "A4",
            self.envelope(
                self.low,
                self.offer_a,
                4,
                T_A4,
                {"offer": "FRIEND", "type": "cc.relationship.offer.v1", "v": 1},
                previous=a3["inner_event_id"],
            ),
        )
        self.emit(
            "A5",
            self.envelope(
                self.low,
                self.offer_a,
                5,
                T_A5,
                {"offer": "NONE", "type": "cc.relationship.offer.v1", "v": 1},
                previous=a4["inner_event_id"],
            ),
        )

        heads = [a2["inner_event_id"], b1["inner_event_id"]]

        gset1 = self.emit(
            "grant_summary_set",
            self.envelope(
                self.low,
                self.grant_slot_summary,
                1,
                T_GRANT_SUMMARY,
                {
                    "expires_at": T_GRANT_SUMMARY + o.GRANT_MAX_DURATION_S,
                    "fields": SUMMARY_FIELDS,
                    "grant_id": GRANT_SUMMARY,
                    "issued_at": T_GRANT_SUMMARY,
                    "offer_heads": heads,
                    "publisher": self.low,
                    "recipient": self.high,
                    "scope": "LOGBOOK_SUMMARY",
                    "type": "cc.grant.set.v1",
                    "v": 1,
                },
            ),
        )
        gset2 = self.emit(
            "grant_summary_narrowing",
            self.envelope(
                self.low,
                self.grant_slot_summary,
                2,
                T_GRANT_NARROW,
                {
                    "expires_at": T_GRANT_NARROW + o.GRANT_MAX_DURATION_S,
                    "fields": ["all_time_send_count"],
                    "grant_id": GRANT_NARROW,
                    "issued_at": T_GRANT_NARROW,
                    "offer_heads": heads,
                    "publisher": self.low,
                    "recipient": self.high,
                    "scope": "LOGBOOK_SUMMARY",
                    "supersedes_grant_id": GRANT_SUMMARY,
                    "type": "cc.grant.set.v1",
                    "v": 1,
                },
                previous=gset1["inner_event_id"],
            ),
        )
        self.emit(
            "grant_summary_revoke",
            self.envelope(
                self.low,
                self.grant_slot_summary,
                3,
                T_REVOKE,
                {
                    "grant_id": GRANT_NARROW,
                    "publisher": self.low,
                    "reason": "REVOKED_BY_USER",
                    "recipient": self.high,
                    "scope": "LOGBOOK_SUMMARY",
                    "type": "cc.grant.revoke.v1",
                    "v": 1,
                },
                previous=gset2["inner_event_id"],
            ),
        )
        gdetail = self.emit(
            "grant_detail_backdated",
            self.envelope(
                self.low,
                self.grant_slot_detail,
                1,
                T_GRANT_DETAIL,
                {
                    "expires_at": T_GRANT_DETAIL + o.GRANT_MAX_DURATION_S,
                    "fields": DETAIL_FIELDS,
                    "grant_id": GRANT_DETAIL,
                    "issued_at": T_GRANT_DETAIL,
                    "not_before": T_B1,
                    "offer_heads": heads,
                    "publisher": self.low,
                    "recipient": self.high,
                    "scope": "LOGBOOK_DETAIL",
                    "type": "cc.grant.set.v1",
                    "v": 1,
                },
            ),
        )
        self.emit(
            "projection_summary_snapshot",
            self.envelope(
                self.low,
                self.proj_summary,
                1,
                T_SNAP_SUMMARY,
                {
                    "as_of": T_SNAP_SUMMARY,
                    "fields": SUMMARY_FIELDS,
                    "grant_event_id": gset1["inner_event_id"],
                    "grant_id": GRANT_SUMMARY,
                    "payload": {
                        "all_time_send_count": 128,
                        "hardest_grade_milli": 21000,
                        "type": "cc.logbook_summary",
                        "v": 1,
                    },
                    "scope": "LOGBOOK_SUMMARY",
                    "type": "cc.projection.snapshot.v1",
                    "v": 1,
                },
            ),
        )

        session_row = {
            "board_config_id": self.board_config_id,
            "duration_minutes": 75,
            "sends": [{"grade_milli": 21000, "problem_hash": self.problem_hash}],
            "session_uuid": self.session_1,
            "started_at_utc": o.floor900(1786003345),
        }
        ldsnap = self.emit(
            "projection_detail_snapshot",
            self.envelope(
                self.low,
                self.proj_detail,
                1,
                T_SNAP_DETAIL,
                {
                    "as_of": T_SNAP_DETAIL,
                    "fields": DETAIL_FIELDS,
                    "grant_event_id": gdetail["inner_event_id"],
                    "grant_id": GRANT_DETAIL,
                    "payload": {
                        "prospective_from": T_B1,
                        "sessions": [session_row],
                        "type": "cc.logbook_detail",
                        "v": 1,
                    },
                    "prospective_from": T_B1,
                    "scope": "LOGBOOK_DETAIL",
                    "type": "cc.projection.snapshot.v1",
                    "v": 1,
                },
            ),
        )
        delta_row = dict(session_row, duration_minutes=90)
        lddelta = self.emit(
            "projection_detail_delta",
            self.envelope(
                self.low,
                self.proj_detail,
                2,
                T_DELTA_DETAIL,
                {
                    "as_of": T_DELTA_DETAIL,
                    "fields": DETAIL_FIELDS,
                    "grant_event_id": gdetail["inner_event_id"],
                    "grant_id": GRANT_DETAIL,
                    "ops": [{"op": "UPSERT_SESSION", "row": delta_row}],
                    "prospective_from": T_B1,
                    "scope": "LOGBOOK_DETAIL",
                    "type": "cc.projection.delta.v1",
                    "v": 1,
                },
                previous=ldsnap["inner_event_id"],
            ),
        )
        # The first sendable instant after the boundary: ceil900(1786000200).
        boundary_row = {
            "board_config_id": self.board_config_id,
            "duration_minutes": 60,
            "sends": [],
            "session_uuid": self.session_2,
            "started_at_utc": o.ceil900(T_B1),
        }
        self.emit(
            "projection_detail_delta_boundary",
            self.envelope(
                self.low,
                self.proj_detail,
                3,
                T_DELTA_BOUNDARY,
                {
                    "as_of": T_DELTA_BOUNDARY,
                    "fields": DETAIL_FIELDS,
                    "grant_event_id": gdetail["inner_event_id"],
                    "grant_id": GRANT_DETAIL,
                    "ops": [{"op": "UPSERT_SESSION", "row": boundary_row}],
                    "prospective_from": T_B1,
                    "scope": "LOGBOOK_DETAIL",
                    "type": "cc.projection.delta.v1",
                    "v": 1,
                },
                previous=lddelta["inner_event_id"],
            ),
        )

        pgrant = self.emit(
            "grant_plan_detail",
            self.envelope(
                self.low,
                self.grant_slot_plan,
                1,
                T_GRANT_PLAN,
                {
                    "expires_at": T_GRANT_PLAN + o.GRANT_MAX_DURATION_S,
                    "fields": PLAN_FIELDS,
                    "grant_id": GRANT_PLAN,
                    "issued_at": T_GRANT_PLAN,
                    "not_before": T_GRANT_PLAN,
                    "offer_heads": heads,
                    "publisher": self.low,
                    "recipient": self.high,
                    "resource_id": self.plan_uuid,
                    "scope": "PLAN_DETAIL",
                    "type": "cc.grant.set.v1",
                    "v": 1,
                },
            ),
        )
        self.emit(
            "projection_plan_snapshot",
            self.envelope(
                self.low,
                self.proj_plan,
                1,
                T_SNAP_PLAN,
                {
                    "as_of": T_SNAP_PLAN,
                    "fields": PLAN_FIELDS,
                    "grant_event_id": pgrant["inner_event_id"],
                    "grant_id": GRANT_PLAN,
                    "payload": {
                        "plan": {
                            "focus_areas": ["finger_strength", "power"],
                            "phase": "POWER",
                            "sessions": [
                                {
                                    "day_of_week": 1,
                                    "session_type": "POWER",
                                    "target_duration_min": 90,
                                    "target_rpe_deci": 80,
                                }
                            ],
                            "sessions_per_week": 3,
                        },
                        "prospective_from": T_GRANT_PLAN,
                        "type": "cc.plan_detail",
                        "v": 1,
                    },
                    "prospective_from": T_GRANT_PLAN,
                    "resource_id": self.plan_uuid,
                    "scope": "PLAN_DETAIL",
                    "type": "cc.projection.snapshot.v1",
                    "v": 1,
                },
            ),
        )

        self.emit(
            "wrong_generation",
            self.envelope(
                self.low,
                self.offer_a,
                1,
                T_A1,
                {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
                generation=self.gen2,
            ),
        )
        # The exact A1 content carried under inner pubkey B.
        a1_env = self.envelope(
            self.low,
            self.offer_a,
            1,
            T_A1,
            {"offer": "ACQUAINTANCE", "type": "cc.relationship.offer.v1", "v": 1},
        )
        self.emit("wrong_sender", a1_env, full_event=True, pubkey=self.high)

    # -- fixture files ---------------------------------------------------
    def positive(self) -> dict:
        return {
            "schema": "cc.fixture.positive.v1",
            "protocol": o.NAMESPACE + "/v1",
            "note": (
                "Spec oracle fixtures. Reproduced by "
                "scripts/verify_private_sharing.py. No product code is executed."
            ),
            "inputs": {
                "account_a": A,
                "account_b": B,
                "endpoint_low": self.low,
                "endpoint_high": self.high,
                "group_1_id_hex": G1,
                "group_2_id_hex": G2,
                "attempt_id": ATTEMPT_ID,
                "local_session_uuid_1": LOCAL_SESSION_1,
                "local_session_uuid_2": LOCAL_SESSION_2,
                "local_plan_uuid": LOCAL_PLAN,
                "board": BOARD,
                "hold_frames": [[list(h) for h in frame] for frame in FRAMES],
                "provider_problem_id": "123",
                "grant_ids": {
                    "LOGBOOK_SUMMARY": GRANT_SUMMARY,
                    "LOGBOOK_SUMMARY_narrowed": GRANT_NARROW,
                    "LOGBOOK_DETAIL": GRANT_DETAIL,
                    "PLAN_DETAIL": GRANT_PLAN,
                },
            },
            "derivations": self.derivations,
            "board_config_id": self.board_config_id,
            "hold_preimage_hex": self.hold_pre,
            "hold_fingerprint": self.hold_fp,
            "problem_hash": self.problem_hash,
            "bootstrap": {
                "name": o.BOOTSTRAP_NAME,
                "name_size": len(o.BOOTSTRAP_NAME.encode("utf-8")),
                "description": self.marker_bytes.decode("utf-8"),
                "description_size": len(self.marker_bytes),
                "description_sha256": o.sha256_hex(self.marker_bytes),
                "component_data_hex": self.component_0x8001.hex(),
                "component_data_size": len(self.component_0x8001),
                "component_data_sha256": o.sha256_hex(self.component_0x8001),
            },
            "events": self.events,
        }

    def negative(self) -> dict:
        from negative_corpus import build_negative_corpus  # local import, same dir

        return build_negative_corpus(self)

    def scenarios(self) -> dict:
        from reducer_scenarios import build_scenarios  # local import, same dir

        return build_scenarios(self)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--print", action="store_true", help="print instead of writing")
    args = parser.parse_args()

    builder = Builder()
    builder.build_derivations()
    builder.build_events()

    files = {
        "positive-vectors.json": builder.positive(),
        "negative-corpus.json": builder.negative(),
        "reducer-scenarios.json": builder.scenarios(),
    }
    for name, payload in files.items():
        text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        # The manifest rule of README.md hashes byte lengths, and this corpus is
        # full of multi-byte characters (§, —), so report encoded bytes rather
        # than the character count they are not equal to.
        size = len(text.encode("utf-8"))
        if args.print:
            print(f"===== {name} ({size} bytes) =====")
        else:
            (FIXTURES / name).write_text(text, encoding="utf-8")
            print(f"wrote {name} ({size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
