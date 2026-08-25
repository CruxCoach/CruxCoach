#!/usr/bin/env python3
"""Fail-closed contract check for the reusable predecessor-upgrade fixture."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any, NoReturn


EXPECTED_LAYOUTS = {
    ("kilter", 1),
    ("kilter", 8),
    ("moonboard", 2),
    ("moonboard", 5),
    ("moonboard", 6),
    ("tension", 9),
    ("tension", 10),
    ("tension", 11),
    ("grasshopper", 1),
    ("decoy", 2),
    ("soill", 1),
    ("touchstone", 1),
}
UUID = re.compile(
    r"^(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$"
)


def fail(message: str) -> NoReturn:
    raise ValueError(message)


def rows(root: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = root.get(key)
    if not isinstance(value, list) or not all(isinstance(row, dict) for row in value):
        fail(f"{key} must be an array of objects")
    return value


def validate(path: Path) -> dict[str, Any]:
    root = json.loads(path.read_text(encoding="utf-8"))
    if root.get("version") != 3 or root.get("app") != "CruxCoach":
        fail("expected a CruxCoach v3 envelope")
    if root.get("nostrPubkey") is not None:
        fail("the reusable fixture must not bind to a Nostr identity")

    ascents = rows(root, "boardAscents")
    bids = rows(root, "boardBids")
    lists = rows(root, "climbLists")
    if len(ascents) != 12 or len(bids) != 4 or len(lists) != 4:
        fail("expected exactly 12 sends, 4 attempts and 4 lists")
    if rows(root, "boardClimbs") or rows(root, "boardClimbStats"):
        fail("catalogue-backed fixture must not contain synthetic own climbs")

    layouts = {(row.get("boardBrand"), row.get("layoutId")) for row in ascents}
    if layouts != EXPECTED_LAYOUTS:
        fail(f"board/layout coverage drift: {sorted(layouts)}")

    climb_ids: list[str] = []
    log_ids: list[str] = []
    for label, collection in (("ascent", ascents), ("bid", bids)):
        for row in collection:
            climb_uuid = row.get("climbUuid")
            log_uuid = row.get("uuid")
            if not isinstance(climb_uuid, str) or not UUID.fullmatch(climb_uuid):
                fail(f"{label} has an invalid catalogue climb UUID")
            if not isinstance(log_uuid, str) or not UUID.fullmatch(log_uuid):
                fail(f"{label} has an invalid log UUID")
            if not isinstance(row.get("climbName"), str) or not row["climbName"].strip():
                fail(f"{label} is missing its real catalogue name")
            if label == "ascent" and not isinstance(row.get("climbFrames"), str):
                fail("ascent is missing catalogue frames")
            climb_ids.append(climb_uuid)
            log_ids.append(log_uuid)
    if len(log_ids) != len(set(log_ids)):
        fail("log UUIDs must be unique for idempotent re-import")

    by_name = {row.get("name"): row for row in lists}
    if set(by_name) != {"Favoriten", "Ignoriert", "Release Upgrade Mix", "Release Subboards"}:
        fail("list identity drift")
    all_send_ids = {str(row["climbUuid"]) for row in ascents}
    project_ids = {str(row["climbUuid"]) for row in bids}
    if len(project_ids) != 4 or project_ids & all_send_ids:
        fail("all four open projects must be distinct real climbs without sends")
    if set(by_name["Release Upgrade Mix"].get("entries", [])) != all_send_ids:
        fail("Release Upgrade Mix must contain every real send climb")
    if len(by_name["Release Subboards"].get("entries", [])) != 8:
        fail("Release Subboards must retain all Kilter/Moon/Tension variants")
    for row in lists:
        entries = row.get("entries")
        if not isinstance(entries, list) or not set(entries).issubset(all_send_ids):
            fail(f"{row.get('name')} contains a non-catalogue fixture UUID")

    populated_hidden = {
        key: len(rows(root, key))
        for key in ("assessments", "bodyStats", "workoutLogs", "climbLogs", "trainingPlans", "boardSessions")
    }
    if root.get("profile") is None or any(count == 0 for count in populated_hidden.values()):
        fail("future-visible backup categories must stay populated")

    return {
        "status": "PASS",
        "ascents": len(ascents),
        "bids": len(bids),
        "lists": len(lists),
        "boardLayoutPairs": len(layouts),
        "uniqueClimbs": len(all_send_ids | project_ids),
        "brands": sorted({str(row["boardBrand"]) for row in ascents}),
        "hiddenCategoriesPopulated": populated_hidden,
    }


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(
        "flows/fixtures/cruxcoach-release-upgrade-baseline-v3.json"
    )
    try:
        report = validate(path)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"validate_release_upgrade_fixture: FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
