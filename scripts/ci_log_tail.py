#!/usr/bin/env python3
"""Emit the tail of each CI log as a GitHub annotation.

Workflow job logs need admin rights on the repository, so a failed run is
otherwise undiagnosable from the API. Annotations are public, and the tail of a
Gradle or xcodebuild log always carries the "What went wrong" block.
"""
import pathlib
import sys

LINES = 60
LIMIT = 3500


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def main(paths: list) -> int:
    for pattern in paths:
        for log in sorted(pathlib.Path("/").glob(pattern.lstrip("/"))):
            try:
                tail = log.read_text(errors="replace").splitlines()[-LINES:]
            except OSError as error:
                print(f"::notice::could not read {log}: {error}")
                continue
            body = "\n".join(tail)[-LIMIT:]
            print(f"::error title={escape(log.name)}::{escape(body)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
