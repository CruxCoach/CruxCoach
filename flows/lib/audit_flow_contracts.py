#!/usr/bin/env python3
"""Audit suite tags and explicit state/idempotence contracts."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


ALLOWED_CONTRACTS = {
    "self-cleaning",
    "destructive-reset",
    "recipient-retained-one-shot",
    "manual-prereq",
}


def flow_tags(path: Path) -> set[str]:
    tags: set[str] = set()
    in_tags = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line == "---":
            break
        if line == "tags:":
            in_tags = True
            continue
        if in_tags:
            if line.startswith("  - "):
                tags.add(line[4:].strip())
            elif line and not line.startswith(" "):
                in_tags = False
    return tags


def suite_names(path: Path) -> list[str]:
    names = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        value = raw.split("#", 1)[0].strip()
        if value:
            names.append(value)
    return names


def contracts(path: Path) -> dict[str, str]:
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if reader.fieldnames != ["flow", "contract", "note"]:
            raise ValueError("state-contracts.tsv has an unexpected header")
        result: dict[str, str] = {}
        for row in reader:
            name = row["flow"]
            contract = row["contract"]
            if not name or not row["note"]:
                raise ValueError("state contract rows need flow, contract, and note")
            if name in result:
                raise ValueError(f"duplicate state contract for {name}")
            if contract not in ALLOWED_CONTRACTS:
                raise ValueError(f"invalid state contract for {name}: {contract}")
            result[name] = contract
    return result


def audit(flows_dir: Path, release_suite: Path, nostr_suite: Path, contract_file: Path) -> dict[str, object]:
    errors: list[str] = []
    roots = {path.stem: path for path in flows_dir.glob("*.yaml") if path.name != "config.yaml"}
    tags = {name: flow_tags(path) for name, path in roots.items()}
    try:
        declared = contracts(contract_file)
    except (OSError, ValueError) as exc:
        return {"valid": False, "errors": [str(exc)]}

    mutating = {name for name, values in tags.items() if "mutates-state" in values}
    for name in sorted(mutating - declared.keys()):
        errors.append(f"mutates-state root lacks a contract: {name}")
    for name in sorted(declared.keys() - mutating):
        errors.append(f"contract does not name a mutates-state root: {name}")

    release = suite_names(release_suite)
    for name in release:
        if name not in roots:
            errors.append(f"release-gate names a missing root: {name}")
            continue
        if "release-gate" not in tags[name]:
            errors.append(f"release-gate root lacks release-gate tag: {name}")
        if "manual-prereq" in tags[name] or "irreversible-external" in tags[name]:
            errors.append(f"release-gate contains a conditional/irreversible root: {name}")
        if name in mutating and declared.get(name) not in {"self-cleaning", "destructive-reset"}:
            errors.append(f"release-gate mutating root has unsafe contract: {name}")

    expected_nostr = ["release-fresh-onboarding", "nostr-dm-delivery", "nostr-dm-force-stop"]
    actual_nostr = suite_names(nostr_suite)
    if actual_nostr != expected_nostr:
        errors.append("nostr-live suite order/content differs from the one-shot contract")
    for name in expected_nostr[1:]:
        if declared.get(name) != "recipient-retained-one-shot":
            errors.append(f"Nostr DM root lacks recipient-retained one-shot contract: {name}")
        required = {"irreversible-external", "live-external", "network", "mutates-state"}
        if not required.issubset(tags.get(name, set())):
            errors.append(f"Nostr DM root lacks required tags: {name}")
        if name in release:
            errors.append(f"Nostr DM root leaked into release-gate: {name}")

    return {
        "valid": not errors,
        "root_count": len(roots),
        "release_gate_count": len(release),
        "mutating_root_count": len(mutating),
        "contract_count": len(declared),
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--flows-dir", type=Path, required=True)
    parser.add_argument("--release-suite", type=Path, required=True)
    parser.add_argument("--nostr-suite", type=Path, required=True)
    parser.add_argument("--contracts", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.flows_dir, args.release_suite, args.nostr_suite, args.contracts)
    except (OSError, ValueError) as exc:
        result = {"valid": False, "errors": [str(exc)]}
    print(json.dumps(result, indent=2))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
