#!/usr/bin/env python3
"""Emit the compiler errors and the tail of each CI log as GitHub annotations.

Workflow job logs need admin rights on the repository, so a failed run is
otherwise undiagnosable from the API. Annotations are public. The tail of a
Gradle or xcodebuild log carries the "What went wrong" block; Swift and Kotlin
compiler errors sit further up, so every distinct `error:` (Swift/clang) and
`e:` (Kotlin) line is collected first and packed into a few annotations
(GitHub keeps at most 10 error annotations per step).
"""
import pathlib
import re
import sys

LINES = 60
LIMIT = 3500
MAX_ERROR_ANNOTATIONS = 6
ERROR_LINE = re.compile(r"(error: |^e: |\*\* BUILD FAILED|Testing failed:)")
WORKSPACE = re.compile(r"/Users/runner/work/[^/]+/[^/]+/")


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def errors_of(lines: list) -> list:
    seen, found = set(), []
    for line in lines:
        if ERROR_LINE.search(line):
            short = WORKSPACE.sub("", line.strip())
            if short not in seen:
                seen.add(short)
                found.append(short)
    return found


def main(paths: list) -> int:
    budget = MAX_ERROR_ANNOTATIONS
    for pattern in paths:
        for log in sorted(pathlib.Path("/").glob(pattern.lstrip("/"))):
            try:
                lines = log.read_text(errors="replace").splitlines()
            except OSError as error:
                print(f"::notice::could not read {log}: {error}")
                continue
            chunk = ""
            for line in errors_of(lines):
                if len(chunk) + len(line) + 1 > LIMIT:
                    if budget > 0:
                        print(f"::error title={escape(log.name)} errors::{escape(chunk)}")
                        budget -= 1
                    chunk = ""
                chunk += line + "\n"
            if chunk and budget > 0:
                print(f"::error title={escape(log.name)} errors::{escape(chunk)}")
                budget -= 1
            body = "\n".join(lines[-LINES:])[-LIMIT:]
            print(f"::error title={escape(log.name)}::{escape(body)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
