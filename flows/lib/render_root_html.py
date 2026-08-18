#!/usr/bin/env python3
"""Render one root's complete, script-free E2E verdict."""

from __future__ import annotations

import argparse
import html
from pathlib import Path
from urllib.parse import quote


VALID = {"PASS", "FAIL", "SKIP"}
COMPONENTS = (
    ("Maestro invocation", "maestro", "attempt evidence"),
    ("Artifact/evidence capture", "evidence_capture", "evidence-capture.tsv"),
    ("Structured JUnit", "junit", "junit-only.html"),
    ("JUnit HTML rendering", "junit_html", "junit-only.html"),
    ("Crash/process health", "process_health", "process-health.json"),
    ("Scoped Logcat expectations", "expectations", "expectations.tsv"),
    ("UUID/entity proof", "identity_proof", "identity-proof.json"),
    ("Root post-hook", "post_hook", "post-hook evidence"),
)


def escaped(value: object) -> str:
    return html.escape(str(value), quote=True)


def render(
    destination: Path,
    *,
    flow: str,
    result: str,
    maestro_exit: int,
    infrastructure_retries: int,
    statuses: dict[str, str],
) -> None:
    if result not in {"PASS", "FAIL"}:
        raise ValueError("root result must be PASS or FAIL")
    expected_keys = {key for _, key, _ in COMPONENTS}
    if set(statuses) != expected_keys:
        raise ValueError("unexpected root component set")
    invalid = {value for value in statuses.values() if value not in VALID}
    if invalid:
        raise ValueError(f"invalid component status: {sorted(invalid)}")
    derived = "FAIL" if any(value == "FAIL" for value in statuses.values()) else "PASS"
    if derived != result:
        raise ValueError("root result disagrees with component statuses")

    rows: list[str] = []
    for label, key, artifact in COMPONENTS:
        status = statuses[key]
        css = status.lower()
        if status == "SKIP":
            evidence = "not required for this root"
        elif artifact.endswith((".html", ".json", ".tsv")):
            href = quote(artifact, safe="/-_.")
            evidence = f'<a href="{escaped(href)}">{escaped(artifact)}</a>'
        else:
            evidence = escaped(artifact)
        rows.append(
            "<tr>"
            f"<td>{escaped(label)}</td>"
            f'<td class="{css}">{escaped(status)}</td>'
            f"<td>{evidence}</td>"
            "</tr>"
        )
    css = "pass" if result == "PASS" else "fail"
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{escaped(flow)} — root {escaped(result)}</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 2rem auto; max-width: 1000px; padding: 0 1rem; }}
    table {{ border-collapse: collapse; width: 100%; }}
    th, td {{ border-bottom: 1px solid #8886; padding: .55rem; text-align: left; }}
    .pass, .passed {{ color: #2da765; font-weight: 800; }}
    .fail, .failed {{ color: #e25c5c; font-weight: 800; }}
    .skip {{ color: #c09a28; font-weight: 800; }}
  </style>
</head>
<body>
  <h1>{escaped(flow)}</h1>
  <p class="{css}">ROOT {escaped(result)}</p>
  <p>Maestro exit {escaped(maestro_exit)}; bounded infrastructure retries {escaped(infrastructure_retries)}.</p>
  <table>
    <thead><tr><th>Component</th><th>Verdict</th><th>Evidence</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</body>
</html>
"""
    destination.write_text(document, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--flow", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--maestro-exit", type=int, required=True)
    parser.add_argument("--infrastructure-retries", type=int, required=True)
    for _, key, _ in COMPONENTS:
        parser.add_argument(f"--{key.replace('_', '-')}", required=True)
    args = parser.parse_args()
    statuses = {key: getattr(args, key) for _, key, _ in COMPONENTS}
    try:
        render(
            args.output,
            flow=args.flow,
            result=args.result,
            maestro_exit=args.maestro_exit,
            infrastructure_retries=args.infrastructure_retries,
            statuses=statuses,
        )
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
