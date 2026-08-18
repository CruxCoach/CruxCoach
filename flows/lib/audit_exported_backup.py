#!/usr/bin/env python3
"""Fail-closed, secret-free audit of the controlled current-schema E2E export."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, NoReturn


FIRST_COMMENT = "E2E-BACKUP-45-ÄÖ-😀"
ATTEMPT_COMMENT = "E2E-BACKUP-ATTEMPT-50"
SECOND_COMMENT = "E2E-BACKUP-50-café-über-90°"
IGNORED_EXTERNAL_ID = "cruxcoach:builtin:ignored"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
NSEC = re.compile(r"(?i)^nsec1[02-9ac-hj-np-z]{20,}$")
SENSITIVE_KEY = re.compile(
    r"(?i)(?:^|[_-])(?:nsec|private(?:key)?|secret|seed|mnemonic|password|passwd|credential|access[_-]?token|refresh[_-]?token|auth[_-]?token)(?:$|[_-])"
)


def fail(message: str) -> NoReturn:
    raise ValueError(message)


def require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        fail(f"{label} must be an array")
    return value


def scan_secret_shape(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                fail(f"non-string key at {path}")
            if SENSITIVE_KEY.search(key):
                fail(f"sensitive key name at {path}.{key}")
            scan_secret_shape(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_secret_shape(child, f"{path}[{index}]")
    elif isinstance(value, str) and NSEC.fullmatch(value.strip()):
        fail(f"nsec-shaped value at {path}")


def one_by_comment(rows: list[Any], comment: str, label: str) -> dict[str, Any]:
    matches = [require_mapping(row, label) for row in rows if isinstance(row, dict) and row.get("comment") == comment]
    if len(matches) != 1:
        fail(f"expected exactly one {label} with controlled comment {comment!r}; found {len(matches)}")
    return matches[0]


def require_exact_keys(payload: dict[str, Any]) -> None:
    expected = {
        "version",
        "app",
        "exportedAt",
        "nostrPubkey",
        "profile",
        "assessments",
        "bodyStats",
        "workoutLogs",
        "climbLogs",
        "trainingPlans",
        "boardAscents",
        "boardBids",
        "boardSessions",
        "climbLists",
        "boardClimbs",
        "boardClimbStats",
    }
    actual = set(payload)
    if actual != expected:
        fail(f"unexpected backup envelope keys: missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")


def audit(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size <= 0:
        fail("exported backup is missing or empty")
    try:
        raw = path.read_text(encoding="utf-8")
        payload = require_mapping(json.loads(raw), "backup")
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"cannot parse UTF-8 JSON export: {exc}")

    scan_secret_shape(payload)
    require_exact_keys(payload)
    if payload.get("version") != 3 or payload.get("app") != "CruxCoach":
        fail("expected CruxCoach v3 backup envelope")
    pubkey = payload.get("nostrPubkey")
    if not isinstance(pubkey, str) or not HEX64.fullmatch(pubkey):
        fail("nostrPubkey must be one lowercase 64-hex public key")
    if not isinstance(payload.get("exportedAt"), str) or not payload["exportedAt"]:
        fail("exportedAt must be a non-empty string")

    for empty_key in (
        "assessments",
        "bodyStats",
        "workoutLogs",
        "climbLogs",
        "trainingPlans",
        "boardSessions",
        "boardClimbs",
        "boardClimbStats",
    ):
        if require_list(payload.get(empty_key), empty_key):
            fail(f"controlled export unexpectedly contains {empty_key}")
    if payload.get("profile") is not None:
        fail("controlled export unexpectedly contains profile data")

    ascents = require_list(payload.get("boardAscents"), "boardAscents")
    bids = require_list(payload.get("boardBids"), "boardBids")
    lists = require_list(payload.get("climbLists"), "climbLists")
    if len(ascents) != 2 or len(bids) != 1 or len(lists) != 2:
        fail(
            "controlled cardinality mismatch: "
            f"ascents={len(ascents)}, bids={len(bids)}, lists={len(lists)}"
        )

    first = one_by_comment(ascents, FIRST_COMMENT, "boardAscent")
    second = one_by_comment(ascents, SECOND_COMMENT, "boardAscent")
    attempt = one_by_comment(bids, ATTEMPT_COMMENT, "boardBid")

    if first.get("angle") != 45 or first.get("quality") != 5 or first.get("bidCount") != 1:
        fail("first send is not the controlled 45-degree, five-star, first-try row")
    if second.get("angle") != 50 or second.get("quality") != 4 or second.get("bidCount") != 1:
        fail("second send is not the controlled 50-degree, four-star, first-try row")
    if attempt.get("angle") != 50 or attempt.get("bidCount") != 3:
        fail("attempt is not the controlled 50-degree, three-attempt row")
    first_uuid = first.get("climbUuid")
    second_uuid = second.get("climbUuid")
    if not isinstance(first_uuid, str) or not first_uuid or not isinstance(second_uuid, str) or not second_uuid:
        fail("controlled rows need non-empty climbUuid values")
    if first_uuid == second_uuid:
        fail("favorite and ignored probes must use distinct climbs")
    if attempt.get("climbUuid") != second_uuid:
        fail("attempt and second send are not bound to the same climb")

    list_rows = [require_mapping(row, "climbList") for row in lists]
    favorites = [row for row in list_rows if row.get("isBuiltin") is True and row.get("externalId") is None]
    ignored = [row for row in list_rows if row.get("isBuiltin") is True and row.get("externalId") == IGNORED_EXTERNAL_ID]
    if len(favorites) != 1 or len(ignored) != 1:
        fail(f"expected one Favorites and one Ignored list; found {len(favorites)} and {len(ignored)}")
    favorite_entries = require_list(favorites[0].get("entries"), "Favorites.entries")
    ignored_entries = require_list(ignored[0].get("entries"), "Ignored.entries")
    if favorite_entries != [first_uuid]:
        fail("Favorites membership is not exactly the first controlled climb")
    if ignored_entries != [second_uuid]:
        fail("Ignored membership is not exactly the second controlled climb")

    return {
        "app": "CruxCoach",
        "audit": "PASS",
        "cardinality": {"boardAscents": 2, "boardBids": 1, "climbLists": 2},
        "envelopeVersion": 3,
        "ignoredExternalId": IGNORED_EXTERNAL_ID,
        "probes": {
            "favoriteSend": {"angle": 45, "quality": 5, "attempts": 1},
            "ignoredAttempt": {"angle": 50, "attempts": 3},
            "ignoredSend": {"angle": 50, "quality": 4, "attempts": 1},
        },
        "publicIdentity": "present-valid-not-retained",
        "secretShapeScan": "PASS",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("backup", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.backup)
    except ValueError as exc:
        print(f"audit_exported_backup: FAIL: {exc}", file=sys.stderr)
        return 1
    json.dump(report, sys.stdout, ensure_ascii=True, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
