#!/usr/bin/env python3
"""Static fail-closed invariants for the public Maestro runner."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def audit_text(text: str) -> dict[str, object]:
    errors: list[str] = []
    required = (
        '[[ "$value" =~ ^deviceLocked=0$ ]] || return 1',
        'record_evidence_capture "$capture_ledger" device-unlocked-before PASS exact-deviceLocked=0',
        'if adb_target shell dumpsys activity exit-info "$PACKAGE_NAME"',
        'record_evidence_capture "$capture_ledger" exit-info-before-nonempty PASS nonempty',
        'if adb_target logcat -b all -c',
        'while ((pre_maestro_capture_ok && attempt <= max_attempts)); do',
        'Maestro not started: mandatory pre-root evidence capture failed.',
        'abort_nostr_suite=1',
        'if ((nostr_live_selected)) && [[ "$last_flow_result" != "PASS" ]]; then',
        'if ((pre_maestro_capture_ok)); then',
        '--evidence-capture "$evidence_status"',
        'verify_evidence_capture.py',
    )
    for fragment in required:
        if fragment not in text:
            errors.append(f"missing runner safety fragment: {fragment}")
    if text.count("pre_maestro_capture_ok=0") < 4:
        errors.append(
            "device lock, exit-info command/content, and logcat-clear failures must close the pre-Maestro gate"
        )
    try:
        lock_state = text.index('if capture_exact_device_unlocked')
        exit_before = text.index('if adb_target shell dumpsys activity exit-info "$PACKAGE_NAME"')
        logcat_clear = text.index('if adb_target logcat -b all -c', exit_before)
        gate = text.index('if ((pre_maestro_capture_ok == 0)); then', logcat_clear)
        maestro_loop = text.index('while ((pre_maestro_capture_ok && attempt <= max_attempts)); do', gate)
        maestro_call = text.index('bounded_maestro "$MAESTRO_TEST_TIMEOUT_SECONDS"', maestro_loop)
        if not (lock_state < exit_before < logcat_clear < gate < maestro_loop < maestro_call):
            errors.append("pre-Maestro evidence gate is out of order")
    except ValueError:
        errors.append("cannot prove pre-Maestro evidence-gate ordering")
    forbidden = (
        "dismiss-keyguard",
        'adb_target logcat -b all -c >/dev/null 2>&1 || true',
        '> "$flow_dir/exit-info-before.txt" 2>/dev/null || true',
        '[[ "$flow_name" == "release-fresh-onboarding" && "$last_flow_result" != "PASS" ]]',
    )
    for fragment in forbidden:
        if fragment in text:
            errors.append("runner still ignores a mandatory evidence failure")
    return {"valid": not errors, "errors": errors}


def audit(path: Path) -> dict[str, object]:
    try:
        return audit_text(path.read_text(encoding="utf-8"))
    except OSError as exc:
        return {"valid": False, "errors": [f"cannot read runner: {exc}"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.runner)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
