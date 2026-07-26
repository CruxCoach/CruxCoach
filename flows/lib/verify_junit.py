#!/usr/bin/env python3
"""Validate a Maestro JUnit report and emit a compact JSON summary."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def fail(message: str, report: Path) -> int:
    print(json.dumps({"report": str(report), "valid": False, "error": message}))
    return 1


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_junit.py REPORT.xml", file=sys.stderr)
        return 2

    report = Path(sys.argv[1])
    if not report.is_file():
        return fail("report missing", report)

    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as exc:
        return fail(f"invalid XML: {exc}", report)

    cases = []
    for case in root.iter("testcase"):
        status = "passed"
        detail = None
        for node_name in ("failure", "error", "skipped"):
            node = case.find(node_name)
            if node is not None:
                status = node_name
                detail = node.get("message") or (node.text or "").strip().splitlines()[0:1]
                if isinstance(detail, list):
                    detail = detail[0] if detail else None
                break
        cases.append(
            {
                "class": case.get("classname"),
                "name": case.get("name"),
                "time_seconds": case.get("time"),
                "status": status,
                "detail": detail,
            }
        )

    counts = {
        "tests": len(cases),
        "passed": sum(case["status"] == "passed" for case in cases),
        "failures": sum(case["status"] == "failure" for case in cases),
        "errors": sum(case["status"] == "error" for case in cases),
        "skipped": sum(case["status"] == "skipped" for case in cases),
    }
    valid = counts["tests"] > 0
    passed = valid and counts["failures"] == counts["errors"] == counts["skipped"] == 0
    print(
        json.dumps(
            {
                "report": str(report),
                "valid": valid,
                "passed": passed,
                "counts": counts,
                "cases": cases,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
