#!/usr/bin/env python3
"""Print failing JUnit test cases as GitHub annotations.

Workflow job logs need a GitHub login; annotations are public, so a failed run
stays diagnosable from the API alone.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def main() -> int:
    failures = 0
    for xml in sorted(ROOT.glob("*/build/test-results/**/*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.findall("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            failures += 1
            title = f"{case.get('classname')}.{case.get('name')}"
            body = (problem.get("message") or "") + "\n" + (problem.text or "")
            print(f"::error title={escape(title)[:200]}::{escape(body.strip())[:3000]}")
    if failures == 0:
        print("::notice::No failing test case found in the JUnit reports; the failure is outside the tests.")
    else:
        print(f"::notice::{failures} failing test case(s) reported above.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
