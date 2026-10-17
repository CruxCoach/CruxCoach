#!/usr/bin/env python3
"""Summarise an Xcode result bundle as public GitHub annotations and export its screenshots.

Job logs and artifacts need a GitHub login, annotations do not. So every test of the
simulator run is listed in one notice (what passed, what was skipped) and each failure
becomes an error annotation carrying its message (the UI tests put the screen's
accessibility tree into it). Screenshot attachments are copied to OUT_DIR under the
name the test gave them, for the workflow to upload.

Usage: ci_xcresult_summary.py RESULT.xcresult OUT_DIR
"""
import json
import pathlib
import shutil
import subprocess
import sys

LIMIT = 3800


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def run(*args: str) -> str:
    return subprocess.run(["xcrun", "xcresulttool", *args], capture_output=True, text=True, check=True).stdout


def walk(node: dict, suite: str, out: list) -> None:
    kind = node.get("nodeType", "")
    name = node.get("name", "")
    if kind == "Test Case":
        messages = [c.get("name", "") for c in node.get("children", []) if c.get("nodeType") == "Failure Message"]
        out.append((f"{suite}/{name}", node.get("result", "?"), messages))
        return
    for child in node.get("children", []):
        walk(child, name if kind == "Test Suite" else suite, out)


def summarise(bundle: str) -> int:
    try:
        tree = json.loads(run("get", "test-results", "tests", "--path", bundle))
    except (subprocess.CalledProcessError, json.JSONDecodeError, FileNotFoundError) as error:
        print(f"::notice title=Simulator tests::could not read {bundle}: {error}")
        return 0
    cases: list = []
    for node in tree.get("testNodes", []):
        walk(node, "", cases)
    passed = [c for c in cases if c[1] == "Passed"]
    failed = [c for c in cases if c[1] == "Failed"]
    skipped = [c for c in cases if c[1] == "Skipped"]
    lines = [f"{len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped"]
    lines += [f"{result}: {name}" for name, result, _ in cases]
    print(f"::notice title=Simulator tests::{escape(chr(10).join(lines)[:LIMIT])}")
    for name, _, messages in failed:
        print(f"::error title={escape(name)}::{escape(chr(10).join(messages)[:LIMIT] or 'failed')}")
    return 0


def export_screenshots(bundle: str, out_dir: str) -> None:
    raw = pathlib.Path(out_dir) / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    try:
        run("export", "attachments", "--path", bundle, "--output-path", str(raw))
    except (subprocess.CalledProcessError, FileNotFoundError) as error:
        print(f"::notice title=Screenshots::export failed: {error}")
        return
    manifest = raw / "manifest.json"
    if not manifest.exists():
        return
    count = 0
    for test in json.loads(manifest.read_text()):
        for item in test.get("attachments", []):
            source = raw / item.get("exportedFileName", "")
            if not source.is_file():
                continue
            name = item.get("suggestedHumanReadableName") or source.name
            shutil.copy(source, pathlib.Path(out_dir) / name)
            count += 1
    shutil.rmtree(raw, ignore_errors=True)
    print(f"::notice title=Screenshots::{count} simulator screenshots exported")


if __name__ == "__main__":
    summarise(sys.argv[1])
    if len(sys.argv) > 2:
        export_screenshots(sys.argv[1], sys.argv[2])
    sys.exit(0)
