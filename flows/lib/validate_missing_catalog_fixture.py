#!/usr/bin/env python3
"""Validate the public-safe, deliberately unresolved list fixture."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


EXPECTED_NAME = "E2E-MISSING-CATALOG-022"
EXPECTED_UUID = "e2200220-ffff-4fff-8fff-000000000001"
EXPECTED_TIMESTAMP = "2026-07-24T12:00:00Z"
FORBIDDEN_KEY = re.compile(
    r"(?:nsec|private|secret|mnemonic|seed|password|token|credential)", re.I
)
FORBIDDEN_VALUE = re.compile(r"\bnsec1[023456789acdefghjklmnpqrstuvwxyz]+\b", re.I)
ALLOWED_ROOT_KEYS = {"version", "app", "exportedAt", "climbLists"}


def _walk(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if FORBIDDEN_KEY.search(key):
                raise ValueError("credential-like field name is present")
            _walk(child)
    elif isinstance(value, list):
        for child in value:
            _walk(child)
    elif isinstance(value, str) and FORBIDDEN_VALUE.search(value):
        raise ValueError("nsec-shaped value is present")


def validate(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise ValueError("fixture is missing")
    if path.stat().st_size > 8_192:
        raise ValueError("fixture is unexpectedly large")
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"not valid UTF-8 JSON ({type(exc).__name__})") from exc
    if not isinstance(root, dict):
        raise ValueError("top level is not an object")
    _walk(root)
    if set(root) != ALLOWED_ROOT_KEYS:
        raise ValueError("unexpected root field is present")
    if root.get("version") != 3 or root.get("app") != "CruxCoach":
        raise ValueError("not the expected CruxCoach v3 envelope")
    if root.get("exportedAt") != EXPECTED_TIMESTAMP:
        raise ValueError("fixture timestamp changed")
    lists = root.get("climbLists")
    if not isinstance(lists, list) or len(lists) != 1 or not isinstance(lists[0], dict):
        raise ValueError("fixture must contain exactly one list")
    expected_list = {
        "name": EXPECTED_NAME,
        "isBuiltin": False,
        "createdAt": EXPECTED_TIMESTAMP,
        "entries": [EXPECTED_UUID],
    }
    if lists[0] != expected_list:
        raise ValueError("controlled list shape changed")
    return {
        "validation": "PASS",
        "listCount": 1,
        "membershipCount": 1,
        "containsIdentity": False,
        "containsSensitiveFields": False,
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: validate_missing_catalog_fixture.py FIXTURE.json")
    try:
        report = validate(Path(sys.argv[1]))
    except ValueError as exc:
        raise SystemExit(f"fixture validation failed: {exc}") from exc
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
