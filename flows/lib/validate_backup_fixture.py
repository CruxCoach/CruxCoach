#!/usr/bin/env python3
"""Fail closed if the committed legacy-import fixture stops being public-safe."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


EXPECTED_ASCENTS = {
    "e2200140-0001-4001-8001-000000000001",
    "e2200140-0002-4002-8002-000000000002",
    "e2200140-0003-4003-8003-000000000003",
}
EXPECTED_BID = "e2200140-0004-4004-8004-000000000004"
EXPECTED_LISTS = {"e2e_legacy_014_alpha", "e2e_legacy_014_beta"}
FAKE_PUBLIC_KEY = "a" * 64
FORBIDDEN_KEY = re.compile(r"(?:nsec|private|secret|mnemonic|seed|password|token)", re.I)
FORBIDDEN_VALUE = re.compile(r"\bnsec1[023456789acdefghjklmnpqrstuvwxyz]+\b", re.I)
MODERN_LOG_FIELDS = {
    "boardBrand",
    "layoutId",
    "synced",
    "isBenchmark",
    "gymUuid",
    "wallUuid",
    "productLayoutUuid",
    "externalId",
}
MODERN_LIST_FIELDS = {
    "externalId",
    "description",
    "color",
    "kind",
    "generatorParams",
    "playlistEntries",
    "playbackOrder",
    "playbackAdvance",
    "playbackRestSeconds",
}


def fail(message: str) -> None:
    raise SystemExit(f"fixture validation failed: {message}")


def walk(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key != "nostrPubkey" and FORBIDDEN_KEY.search(key):
                fail("credential-like field name is present")
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)
    elif isinstance(value, str) and FORBIDDEN_VALUE.search(value):
        fail("nsec-shaped value is present")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: validate_backup_fixture.py FIXTURE.json")

    path = Path(sys.argv[1])
    if not path.is_file():
        fail("file is missing")
    if path.stat().st_size > 32_768:
        fail("file is unexpectedly large")

    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"not valid UTF-8 JSON ({type(exc).__name__})")

    if not isinstance(root, dict):
        fail("top level is not an object")
    walk(root)

    if root.get("version") != 3 or root.get("app") != "CruxCoach":
        fail("not the expected CruxCoach 0.1.4 v3 envelope")
    if root.get("nostrPubkey") != FAKE_PUBLIC_KEY:
        fail("public identity is not the documented fake value")

    ascents = root.get("boardAscents")
    bids = root.get("boardBids")
    lists = root.get("climbLists")
    if not isinstance(ascents, list) or not isinstance(bids, list) or not isinstance(lists, list):
        fail("required collections are missing")
    if {row.get("uuid") for row in ascents if isinstance(row, dict)} != EXPECTED_ASCENTS:
        fail("ascent IDs/count changed")
    if len(bids) != 1 or not isinstance(bids[0], dict) or bids[0].get("uuid") != EXPECTED_BID:
        fail("attempt ID/count changed")
    if {row.get("name") for row in lists if isinstance(row, dict)} != EXPECTED_LISTS:
        fail("list names/count changed")
    if sorted(row.get("quality") for row in ascents if row.get("quality") is not None) != [4, 5]:
        fail("4-star/5-star regression values changed")
    if any(MODERN_LOG_FIELDS.intersection(row) for row in [*ascents, *bids]):
        fail("modern log field added; fixture no longer represents the legacy field set")
    if any(MODERN_LIST_FIELDS.intersection(row) for row in lists):
        fail("modern list field added; fixture no longer represents the legacy field set")
    if any(row.get("isBuiltin") is not False or row.get("entries") != [] for row in lists):
        fail("fixture lists must remain empty custom cleanup markers")

    print("PASS: sanitized 0.1.4 fixture is structurally exact and contains no secret marker")


if __name__ == "__main__":
    main()
