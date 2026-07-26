#!/usr/bin/env python3
"""Prove clipboard share UUID == warm/cold openLink UUID == rendered UUID."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


VALUE = r"[0-9A-Fa-f-]{8,64}"
SHARE = re.compile(rf"SHARE SOURCE: ClimbDetail\((?P<value>{VALUE})\)")
DEEP_START = re.compile(rf"NAV START: DeepLink .*?ClimbDetail\((?P<value>{VALUE})\)")
LOAD_START = re.compile(rf"loadClimb start \((?P<value>{VALUE})\)")
LOAD_COMPLETE = re.compile(rf"loadClimb complete \((?P<value>{VALUE})\)")
NAV_COMPLETE = re.compile(rf"NAV COMPLETE: BoardClimbDetail\((?P<value>{VALUE})\)")
UNKNOWN = "00000000000000000000000000000000"


def canonical(value: str) -> str:
    normalized = value.replace("-", "").lower()
    if not re.fullmatch(r"[0-9a-f]{8,64}", normalized):
        raise ValueError(f"invalid UUID-like identifier {value!r}")
    return normalized


def redacted(value: str) -> str:
    return canonical(value)[:12] + "…"


@dataclass
class Chain:
    identifier: str
    start_line: int
    load_start_line: int | None = None
    load_complete_line: int | None = None


def verify(logcat: Path, expected_roundtrips: int) -> dict[str, object]:
    lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    shares = [(line_no, canonical(match.group("value")))
              for line_no, line in enumerate(lines, 1)
              if (match := SHARE.search(line))]
    errors: list[str] = []
    if len(shares) != 1:
        errors.append(f"found {len(shares)} share-source markers; expected exactly 1")
    source = shares[0][1] if len(shares) == 1 else None
    active: Chain | None = None
    completed: list[dict[str, object]] = []
    unknown_chains = 0

    for line_no, line in enumerate(lines, 1):
        match = DEEP_START.search(line)
        if match:
            if active is not None:
                errors.append(f"line {line_no}: new deep link before prior chain completed")
            active = Chain(canonical(match.group("value")), line_no)
            continue
        if active is None:
            continue
        match = LOAD_START.search(line)
        if match:
            value = canonical(match.group("value"))
            if value != active.identifier:
                errors.append(f"line {line_no}: deep-link/load-start UUID mismatch")
            elif active.load_start_line is not None:
                errors.append(f"line {line_no}: duplicate load-start")
            else:
                active.load_start_line = line_no
            continue
        match = LOAD_COMPLETE.search(line)
        if match:
            value = canonical(match.group("value"))
            if value != active.identifier:
                errors.append(f"line {line_no}: deep-link/load-complete UUID mismatch")
            elif active.load_start_line is None:
                errors.append(f"line {line_no}: load-complete before load-start")
            else:
                active.load_complete_line = line_no
            continue
        match = NAV_COMPLETE.search(line)
        if match:
            value = canonical(match.group("value"))
            if active.identifier == UNKNOWN:
                errors.append("unknown fallback unexpectedly rendered a climb")
            if source is not None and active.identifier != source:
                errors.append(f"line {line_no}: rendered deep-link UUID differs from share source")
            if value != active.identifier:
                errors.append(f"line {line_no}: navigation-complete UUID mismatch")
            if active.load_start_line is None or active.load_complete_line is None:
                errors.append(f"line {line_no}: render before complete load chain")
            if (source is not None and active.identifier == source and
                    value == active.identifier and active.load_start_line is not None and
                    active.load_complete_line is not None):
                completed.append({
                    "entity_prefix": redacted(active.identifier),
                    "deep_link_line": active.start_line,
                    "load_start_line": active.load_start_line,
                    "load_complete_line": active.load_complete_line,
                    "render_line": line_no,
                })
            active = None

    if active is not None:
        if active.identifier == UNKNOWN and active.load_start_line is not None and active.load_complete_line is None:
            unknown_chains = 1
        else:
            errors.append("Logcat ended with an incomplete non-fallback deep-link chain")
    if len(completed) != expected_roundtrips:
        errors.append(f"found {len(completed)} complete source-matching roundtrips; expected {expected_roundtrips}")
    if unknown_chains != 1:
        errors.append("did not prove exactly one non-rendering unknown-UUID fallback")
    return {
        "valid": not errors,
        "share_source_prefix": redacted(source) if source else None,
        "expected_roundtrips": expected_roundtrips,
        "complete_roundtrips": len(completed),
        "unknown_fallbacks": unknown_chains,
        "chains": completed,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--expected-roundtrips", type=int, required=True)
    args = parser.parse_args()
    if args.expected_roundtrips < 1:
        parser.error("--expected-roundtrips must be positive")
    try:
        result = verify(args.logcat, args.expected_roundtrips)
    except (OSError, ValueError) as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
