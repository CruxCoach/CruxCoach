#!/usr/bin/env python3
"""Prove NEWEST DESC/ASC/DESC using UUID and creation timestamp, not labels."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


MARKER = re.compile(
    r"BROWSE TOP uuid=(?P<uuid>[0-9A-Fa-f-]{8,64}) "
    r"createdAt=(?P<created>.*?) sort=(?P<sort>[A-Z_]+) dir=(?P<direction>ASC|DESC)"
)


def canonical(value: str) -> str:
    normalized = value.replace("-", "").lower()
    if not re.fullmatch(r"[0-9a-f]{8,64}", normalized):
        raise ValueError(f"invalid UUID-like identifier {value!r}")
    return normalized


def redacted(value: str) -> str:
    return canonical(value)[:12] + "…"


def verify(logcat: Path) -> dict[str, object]:
    lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    entries: list[dict[str, object]] = []
    for line_no, line in enumerate(lines, 1):
        match = MARKER.search(line)
        if not match or match.group("sort") != "NEWEST":
            continue
        entry = {
            "identifier": canonical(match.group("uuid")),
            "created_at": match.group("created"),
            "direction": match.group("direction"),
            "line": line_no,
        }
        if not entries or any(entry[key] != entries[-1][key] for key in ("identifier", "created_at", "direction")):
            entries.append(entry)

    errors: list[str] = []
    proof: tuple[dict[str, object], dict[str, object], dict[str, object]] | None = None
    for index in range(max(0, len(entries) - 2)):
        candidate = entries[index:index + 3]
        if [item["direction"] for item in candidate] == ["DESC", "ASC", "DESC"]:
            proof = (candidate[0], candidate[1], candidate[2])
            break
    if proof is None:
        errors.append("no consecutive NEWEST DESC/ASC/DESC marker sequence")
    else:
        descending, ascending, descending_again = proof
        if descending["identifier"] != descending_again["identifier"]:
            errors.append("restored DESC top UUID differs from initial DESC top UUID")
        if descending["identifier"] == ascending["identifier"]:
            errors.append("ASC and DESC top UUIDs are identical")
        if "<null>" in (descending["created_at"], ascending["created_at"], descending_again["created_at"]):
            errors.append("NEWEST proof contains a null creation timestamp")
        elif not (str(descending["created_at"]) > str(ascending["created_at"])):
            errors.append("DESC top creation timestamp is not newer than ASC top timestamp")

    public_entries = [
        {
            "entity_prefix": redacted(str(item["identifier"])),
            "created_at": item["created_at"],
            "direction": item["direction"],
            "line": item["line"],
        }
        for item in (proof or ())
    ]
    return {
        "valid": not errors,
        "newest_markers": len(entries),
        "sequence": public_entries,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logcat", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.logcat)
    except (OSError, ValueError) as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
