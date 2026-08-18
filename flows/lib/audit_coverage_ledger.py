#!/usr/bin/env python3
"""Reject coverage-ledger automation references that do not exist."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


CODE_SPAN = re.compile(r"`([^`]+)`")
FLOW_LIKE = re.compile(r"^[a-z][a-z0-9-]*$")
CLASSES = {"A", "B", "C", "M", "O", "P"}
NON_AUTOMATION = {"planned"}


def audit(ledger: Path, flows_dir: Path) -> dict[str, object]:
    roots = {
        path.stem
        for path in flows_dir.glob("*.yaml")
        if path.name != "config.yaml"
    }
    subflows_dir = flows_dir / "subflows"
    subflows = {path.stem for path in subflows_dir.glob("*.yaml")}
    errors: list[str] = []
    references: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    row_count = 0

    for line_number, line in enumerate(
        ledger.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.split("|")[1:-1]]
        if len(cells) != 5 or cells[0] in {"ID", "---"}:
            continue
        assertion_id, _, mapping, classification, _ = cells
        if classification not in CLASSES:
            errors.append(
                f"line {line_number}: {assertion_id} has invalid class {classification!r}"
            )
            continue
        row_count += 1
        if assertion_id in seen_ids:
            errors.append(f"line {line_number}: duplicate assertion ID {assertion_id}")
        seen_ids.add(assertion_id)

        for token in CODE_SPAN.findall(mapping):
            if token in roots:
                references.append(
                    {"assertion": assertion_id, "kind": "root", "name": token}
                )
            elif token in subflows:
                references.append(
                    {"assertion": assertion_id, "kind": "subflow", "name": token}
                )
            elif token.endswith(".sh"):
                if not (flows_dir / token).is_file():
                    errors.append(
                        f"line {line_number}: {assertion_id} names missing host script {token}"
                    )
                else:
                    references.append(
                        {"assertion": assertion_id, "kind": "host-script", "name": token}
                    )
            elif token.endswith(".py"):
                if not (flows_dir / "lib" / token).is_file():
                    errors.append(
                        f"line {line_number}: {assertion_id} names missing audit script {token}"
                    )
            elif FLOW_LIKE.fullmatch(token) and token not in NON_AUTOMATION:
                errors.append(
                    f"line {line_number}: {assertion_id} names phantom automation {token}"
                )

    return {
        "valid": not errors,
        "ledger_rows": row_count,
        "root_count": len(roots),
        "subflow_count": len(subflows),
        "automation_references": references,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--flows-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.ledger, args.flows_dir)
    except OSError as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
