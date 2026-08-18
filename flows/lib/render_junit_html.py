#!/usr/bin/env python3
"""Render a self-contained, script-free JUnit-only component report."""

from __future__ import annotations

import argparse
import html
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Case:
    classname: str
    name: str
    time_seconds: str
    status: str
    detail: str


def parse(report: Path) -> tuple[str, list[Case]]:
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f"cannot parse JUnit report: {exc}") from exc

    suite_name = root.get("name", "")
    cases: list[Case] = []
    for node in root.iter("testcase"):
        status = "passed"
        detail = ""
        for child_name in ("failure", "error", "skipped"):
            child = node.find(child_name)
            if child is not None:
                status = child_name
                detail = child.get("message", "")
                if child.text:
                    detail = "\n".join(part for part in (detail, child.text.strip()) if part)
                break
        cases.append(
            Case(
                classname=node.get("classname", ""),
                name=node.get("name", ""),
                time_seconds=node.get("time", ""),
                status=status,
                detail=detail,
            )
        )
    if not cases:
        raise ValueError("JUnit report contains no testcase")
    return suite_name, cases


def escaped(value: object) -> str:
    return html.escape(str(value), quote=True)


def render(report: Path, destination: Path) -> None:
    suite_name, cases = parse(report)
    counts = {
        status: sum(case.status == status for case in cases)
        for status in ("passed", "failure", "error", "skipped")
    }
    overall = "PASS" if counts["passed"] == len(cases) else "FAIL"
    rows = []
    for case in cases:
        detail = f"<pre>{escaped(case.detail)}</pre>" if case.detail else ""
        rows.append(
            "<tr>"
            f'<td><span class="status {escaped(case.status)}">{escaped(case.status)}</span></td>'
            f"<td>{escaped(case.classname)}</td>"
            f"<td>{escaped(case.name)}{detail}</td>"
            f"<td>{escaped(case.time_seconds)}</td>"
            "</tr>"
        )

    title = suite_name or report.name
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{escaped(title)} — JUnit-only {overall}</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 2rem auto; max-width: 1100px; padding: 0 1rem; }}
    .summary {{ display: flex; gap: 1rem; flex-wrap: wrap; margin: 1rem 0; }}
    .summary span, .status {{ border-radius: .35rem; padding: .2rem .5rem; font-weight: 700; }}
    .passed {{ background: #176b3a; color: white; }}
    .failure, .error {{ background: #9c2525; color: white; }}
    .skipped {{ background: #755b00; color: white; }}
    table {{ border-collapse: collapse; width: 100%; }}
    th, td {{ border-bottom: 1px solid #8886; padding: .55rem; text-align: left; vertical-align: top; }}
    pre {{ max-width: 70ch; overflow: auto; white-space: pre-wrap; }}
  </style>
</head>
<body>
  <h1>{escaped(title)} — JUnit-only</h1>
  <p><strong>JUnit-only {overall}</strong> — this is one component, not the root-flow verdict. The root report also evaluates Maestro exit, crashes, Logcat expectations, identity proofs, and post-hooks.</p>
  <div class="summary">
    <span class="passed">passed {counts['passed']}</span>
    <span class="failure">failures {counts['failure']}</span>
    <span class="error">errors {counts['error']}</span>
    <span class="skipped">skipped {counts['skipped']}</span>
  </div>
  <table>
    <thead><tr><th>Status</th><th>Class</th><th>Case</th><th>Seconds</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</body>
</html>
"""
    destination.write_text(document, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        render(args.report, args.destination)
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
