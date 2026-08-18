#!/usr/bin/env python3
"""Fail closed unless every required per-root evidence capture succeeded."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


REQUIRED = (
    "device-unlocked-before",
    "exit-info-before",
    "exit-info-before-nonempty",
    "logcat-clear",
    "logcat-dump",
    "logcat-dump-nonempty",
    "exit-info-after",
    "exit-info-after-nonempty",
    "maestro-hierarchy-command",
    "maestro-hierarchy-nonempty",
    "screencap-command",
    "screencap-png-validation",
)


def verify(ledger: Path) -> dict[str, object]:
    try:
        with ledger.open(encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source, delimiter="\t")
            if reader.fieldnames != ["artifact", "status", "detail"]:
                raise ValueError("unexpected evidence-capture.tsv header")
            rows = list(reader)
    except OSError as exc:
        raise ValueError(f"cannot read evidence ledger: {exc}") from exc
    seen: dict[str, dict[str, str]] = {}
    errors: list[str] = []
    for row in rows:
        artifact = row["artifact"]
        if artifact in seen:
            errors.append(f"duplicate evidence row: {artifact}")
            continue
        seen[artifact] = row
    for artifact in REQUIRED:
        row = seen.get(artifact)
        if row is None:
            errors.append(f"missing required evidence row: {artifact}")
        elif row["status"] != "PASS":
            errors.append(f"required evidence failed: {artifact}")
    unexpected = sorted(set(seen) - set(REQUIRED))
    if unexpected:
        errors.append("unexpected evidence rows: " + ", ".join(unexpected))
    return {
        "valid": not errors,
        "required_count": len(REQUIRED),
        "pass_count": sum(seen.get(name, {}).get("status") == "PASS" for name in REQUIRED),
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.ledger)
    except ValueError as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
