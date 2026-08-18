#!/usr/bin/env python3
"""Prove one UUID survives the complete browser-to-detail navigation chain."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


NAV_START = re.compile(r"NAV START: .*?ClimbDetail\((?P<uuid>random:)?(?P<value>[0-9A-Fa-f-]{16,64})\)")
LOAD_START = re.compile(r"loadClimb start \((?P<value>[0-9A-Fa-f-]{16,64})\)")
LOAD_COMPLETE = re.compile(r"loadClimb complete \((?P<value>[0-9A-Fa-f-]{16,64})\)")
NAV_COMPLETE = re.compile(r"NAV COMPLETE: BoardClimbDetail\((?P<value>[0-9A-Fa-f-]{16,64})\)")


def canonical(value: str) -> str:
    normalized = value.replace("-", "").lower()
    if not re.fullmatch(r"[0-9a-f]{16,64}", normalized):
        raise ValueError(f"invalid entity identifier {value!r}")
    return normalized


def redacted(value: str) -> str:
    normalized = canonical(value)
    return normalized[:12] + "…"


@dataclass
class Candidate:
    identifier: str
    start_line: int
    load_start_line: int | None = None
    load_complete_line: int | None = None


def verify(logcat: Path, minimum_chains: int) -> dict[str, object]:
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        raise ValueError(f"cannot read Logcat: {exc}") from exc
    active: Candidate | None = None
    chains: list[dict[str, object]] = []
    errors: list[str] = []

    for line_no, line in enumerate(lines, 1):
        match = NAV_START.search(line)
        if match:
            if active is not None:
                errors.append(f"line {line_no}: new navigation before prior chain completed")
            active = Candidate(canonical(match.group("value")), line_no)
            continue
        if active is None:
            continue

        match = LOAD_START.search(line)
        if match:
            value = canonical(match.group("value"))
            if value != active.identifier:
                errors.append(f"line {line_no}: load-start UUID differs from tap UUID")
            elif active.load_start_line is not None:
                errors.append(f"line {line_no}: duplicate load-start for one navigation")
            else:
                active.load_start_line = line_no
            continue

        match = LOAD_COMPLETE.search(line)
        if match:
            value = canonical(match.group("value"))
            if active.load_start_line is None:
                errors.append(f"line {line_no}: load-complete appeared before load-start")
            if value != active.identifier:
                errors.append(f"line {line_no}: load-complete UUID differs from tap UUID")
            else:
                active.load_complete_line = line_no
            continue

        match = NAV_COMPLETE.search(line)
        if match:
            value = canonical(match.group("value"))
            if active.load_start_line is None or active.load_complete_line is None:
                errors.append(f"line {line_no}: detail rendered before a complete load chain")
            if value != active.identifier:
                errors.append(f"line {line_no}: rendered-detail UUID differs from tap UUID")
            if not errors or (
                active.load_start_line is not None
                and active.load_complete_line is not None
                and value == active.identifier
            ):
                chains.append(
                    {
                        "entity_prefix": redacted(active.identifier),
                        "tap_line": active.start_line,
                        "load_start_line": active.load_start_line,
                        "load_complete_line": active.load_complete_line,
                        "render_line": line_no,
                    }
                )
            active = None

    if active is not None:
        errors.append("Logcat ended with an incomplete browser-to-detail chain")
    if len(chains) < minimum_chains:
        errors.append(f"found {len(chains)} complete chains; need at least {minimum_chains}")
    return {
        "valid": not errors,
        "minimum_chains": minimum_chains,
        "complete_chains": len(chains),
        "chains": chains,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--minimum-chains", type=int, required=True)
    args = parser.parse_args()
    if args.minimum_chains < 1:
        parser.error("--minimum-chains must be positive")
    try:
        result = verify(args.logcat, args.minimum_chains)
    except ValueError as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
