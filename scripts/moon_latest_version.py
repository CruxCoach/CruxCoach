#!/usr/bin/env python3
"""Extract the newest numeric Moon release from apkeep's version listing."""

import argparse
import json
from pathlib import Path
import re


VERSION = re.compile(r"(?<![0-9.])(\d+)\.(\d+)\.(\d+)(?![0-9.])")


def latest_version(text: str) -> str:
    versions = {(int(a), int(b), int(c)) for a, b, c in VERSION.findall(text)}
    if not versions:
        raise ValueError("no numeric Moon versions found")
    return ".".join(str(part) for part in max(versions))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listing", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--github-output")
    args = parser.parse_args()
    baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    latest = latest_version(Path(args.listing).read_text(encoding="utf-8"))
    reviewed = baseline["reviewed_version_name"]
    is_new = tuple(map(int, latest.split("."))) > tuple(map(int, reviewed.split(".")))
    print(f"latest Moon version: {latest}; reviewed: {reviewed}; new: {str(is_new).lower()}")
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as output:
            print(f"latest_version={latest}", file=output)
            print(f"new_version={str(is_new).lower()}", file=output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
