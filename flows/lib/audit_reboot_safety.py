#!/usr/bin/env python3
"""Static fail-closed invariants for the reboot-sensitive updater wrapper."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def audit_text(text: str) -> dict[str, object]:
    errors: list[str] = []
    required = (
        '[[ "$value" =~ ^deviceLocked=0$ ]] || return 1',
        '"$wrapper_dir/host/device-lock-before.txt"',
        '"$wrapper_dir/host/boot-count-before.txt"',
        '"$wrapper_dir/host/exit-info-before.txt"',
        '[[ ! -s "$wrapper_dir/host/exit-info-before.txt" ]]',
        '((boot_count_after == boot_count_before + 1))',
        'if ! adb_target logcat -b all -c',
        'if ! adb_target logcat -b all -d -v epoch',
        '[[ -s "$wrapper_dir/host/updater-post-reboot.log" ]]',
        '"$wrapper_dir/host/exit-info-after.txt"',
        '[[ ! -s "$wrapper_dir/host/exit-info-after.txt" ]]',
        'check_process_health.py',
        '"$wrapper_dir/host/process-health.json"',
    )
    for fragment in required:
        if fragment not in text:
            errors.append(f"missing reboot safety fragment: {fragment}")

    forbidden = (
        "dismiss-keyguard",
        'boot-count-before.txt" 2>/dev/null || true',
        'boot-count-after.txt" 2>/dev/null || true',
        'exit-info-before.txt" 2>/dev/null || true',
        'exit-info-after.txt" 2>/dev/null || true',
        'updater-post-reboot.log" 2>/dev/null || true',
    )
    for fragment in forbidden:
        if fragment in text:
            errors.append(f"reboot wrapper contains forbidden fail-open fragment: {fragment}")

    try:
        lock_before = text.index('"$wrapper_dir/host/device-lock-before.txt"')
        count_before = text.index('"$wrapper_dir/host/boot-count-before.txt"')
        exit_before = text.index('"$wrapper_dir/host/exit-info-before.txt"')
        reboot = text.index("if ! adb_target reboot", exit_before)
        count_after = text.index('"$wrapper_dir/host/boot-count-after.txt"', reboot)
        count_proof = text.index("((boot_count_after == boot_count_before + 1))", count_after)
        logcat_clear = text.index("if ! adb_target logcat -b all -c", count_proof)
        logcat_dump = text.index("if ! adb_target logcat -b all -d -v epoch", logcat_clear)
        exit_after = text.index('"$wrapper_dir/host/exit-info-after.txt"', logcat_dump)
        health = text.index("check_process_health.py", exit_after)
        if not (
            lock_before
            < count_before
            < exit_before
            < reboot
            < count_after
            < count_proof
            < logcat_clear
            < logcat_dump
            < exit_after
            < health
        ):
            errors.append("reboot, boot-count, and health evidence gates are out of order")
    except ValueError:
        errors.append("cannot prove reboot boot-count/crash-evidence ordering")

    return {"valid": not errors, "errors": errors}


def audit(path: Path) -> dict[str, object]:
    try:
        return audit_text(path.read_text(encoding="utf-8"))
    except OSError as exc:
        return {"valid": False, "errors": [f"cannot read reboot wrapper: {exc}"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.runner)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
