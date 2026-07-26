#!/usr/bin/env python3
"""Fail-closed detection of new package crashes/ANRs in root-scoped evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ENTRY_BLOCK_RE = re.compile(
    r"ApplicationExitInfo #\d+:\s*(?P<body>.*?)"
    r"(?=\n\s*ApplicationExitInfo #\d+:|\Z)",
    re.DOTALL,
)
TIMESTAMP_PID_RE = re.compile(r"timestamp=(?P<timestamp>.*?)\s+pid=(?P<pid>\d+)(?:\s|$)")
PROCESS_RE = re.compile(r"(?:^|\n)\s*process=(?P<process>\S+)")
REASON_RE = re.compile(r"\breason=(?P<code>\d+)\s+\((?P<reason>[^)]+)\)")
EXIT_DUMP_MARKERS = (
    "ACTIVITY MANAGER PROCESS EXIT INFO",
    "Historical Process Exit",
    "ApplicationExitInfo",
    "No historical process exit records",
)
BAD_REASON_CODES = {4, 5, 6}
BAD_REASON_NAMES = {"CRASH", "CRASH_NATIVE", "ANR"}

ExitEntry = tuple[str, str, int, str]


def read_nonempty(path: Path, label: str, errors: list[str]) -> str:
    try:
        if not path.is_file():
            errors.append(f"{label} evidence is missing")
            return ""
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        errors.append(f"{label} evidence cannot be read: {exc}")
        return ""
    if not text.strip():
        errors.append(f"{label} evidence is empty")
    return text


def parse_exit_info_text(
    text: str, package: str, label: str, errors: list[str]
) -> set[ExitEntry]:
    if text and not any(marker in text for marker in EXIT_DUMP_MARKERS):
        errors.append(f"{label} is not a recognizable activity exit-info dump")

    entries: set[ExitEntry] = set()
    malformed = 0
    for block_match in ENTRY_BLOCK_RE.finditer(text):
        body = block_match.group("body")
        timestamp_pid = TIMESTAMP_PID_RE.search(body)
        process_match = PROCESS_RE.search(body)
        reason_match = REASON_RE.search(body)
        if not (timestamp_pid and process_match and reason_match):
            malformed += 1
            continue
        process = process_match.group("process")
        if process == package or process.startswith(package + ":"):
            entries.add(
                (
                    timestamp_pid.group("timestamp").strip(),
                    timestamp_pid.group("pid"),
                    int(reason_match.group("code")),
                    reason_match.group("reason").strip(),
                )
            )
    if malformed:
        errors.append(f"{label} contains {malformed} malformed ApplicationExitInfo block(s)")
    return entries


def target_process_pattern(package: str) -> str:
    return rf"{re.escape(package)}(?::[A-Za-z0-9_.-]+)?(?![A-Za-z0-9_.:-])"


def detect_logcat_findings(logcat: str, package: str) -> list[str]:
    target = target_process_pattern(package)
    findings: list[str] = []
    if re.search(rf"\bANR in\s+{target}", logcat):
        findings.append("package ANR")
    if re.search(rf"\bam_anr\b[^\n]*{target}", logcat):
        findings.append("package am_anr event")

    lines = logcat.splitlines()
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION:" in line:
            block = "\n".join(lines[index : index + 16])
            if re.search(rf"\bProcess:\s*{target}", block):
                findings.append("package fatal exception")
                break
    for index, line in enumerate(lines):
        if "Fatal signal" in line:
            block = "\n".join(lines[max(0, index - 40) : index + 41])
            if re.search(rf"\bCmdline:\s*{target}", block):
                findings.append("package native fatal signal")
                break
    return findings


def check_health(package: str, before_path: Path, after_path: Path, logcat_path: Path) -> dict[str, object]:
    evidence_errors: list[str] = []
    before_text = read_nonempty(before_path, "exit-info-before", evidence_errors)
    after_text = read_nonempty(after_path, "exit-info-after", evidence_errors)
    logcat = read_nonempty(logcat_path, "scoped logcat", evidence_errors)

    before = parse_exit_info_text(before_text, package, "exit-info-before", evidence_errors)
    after = parse_exit_info_text(after_text, package, "exit-info-after", evidence_errors)
    new_entries = sorted(after - before)
    bad_exits = [
        entry
        for entry in new_entries
        if entry[2] in BAD_REASON_CODES or entry[3].upper() in BAD_REASON_NAMES
    ]
    logcat_findings = detect_logcat_findings(logcat, package)
    healthy = not evidence_errors and not bad_exits and not logcat_findings
    return {
        "package": package,
        "healthy": healthy,
        "evidence_errors": evidence_errors,
        "new_exit_entries": [
            {
                "timestamp": entry[0],
                "pid": entry[1],
                "reason_code": entry[2],
                "reason": entry[3],
            }
            for entry in new_entries
        ],
        "bad_exit_entries": [
            {
                "timestamp": entry[0],
                "pid": entry[1],
                "reason_code": entry[2],
                "reason": entry[3],
            }
            for entry in bad_exits
        ],
        "logcat_findings": logcat_findings,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--logcat", type=Path, required=True)
    args = parser.parse_args()

    result = check_health(args.package, args.before, args.after, args.logcat)
    print(json.dumps(result, indent=2))
    return 0 if result["healthy"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
