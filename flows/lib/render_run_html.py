#!/usr/bin/env python3
"""Render the runner's TSV index as safe, self-contained HTML."""

from __future__ import annotations

import argparse
import csv
import html
from pathlib import Path
from urllib.parse import quote


REQUIRED_COLUMNS = {
    "repeat",
    "sequence",
    "flow",
    "result",
    "maestro_exit",
    "infrastructure_retries",
    "evidence",
}


def escaped(value: object) -> str:
    return html.escape(str(value), quote=True)


def render(results: Path, run_dir: Path, destination: Path, cleanup_result: str) -> None:
    if cleanup_result not in {"PASS", "FAIL"}:
        raise ValueError("cleanup result must be PASS or FAIL")
    try:
        with results.open(encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source, delimiter="\t")
            if reader.fieldnames is None or set(reader.fieldnames) != REQUIRED_COLUMNS:
                raise ValueError("unexpected results.tsv header")
            rows = list(reader)
    except OSError as exc:
        raise ValueError(f"cannot read results.tsv: {exc}") from exc
    if not rows:
        raise ValueError("results.tsv contains no roots")

    table_rows = []
    for row in rows:
        evidence = Path(row["evidence"])
        try:
            relative = evidence.resolve().relative_to(run_dir.resolve())
        except ValueError as exc:
            raise ValueError("result evidence path escapes run directory") from exc
        href = quote(relative.as_posix() + "/report.html", safe="/-_.")
        css = "passed" if row["result"] == "PASS" else "failed"
        table_rows.append(
            "<tr>"
            f"<td>{escaped(row['repeat'])}</td>"
            f"<td>{escaped(row['sequence'])}</td>"
            f'<td><a href="{escaped(href)}">{escaped(row["flow"])}</a></td>'
            f'<td class="{css}">{escaped(row["result"])}</td>'
            f"<td>{escaped(row['maestro_exit'])}</td>"
            f"<td>{escaped(row['infrastructure_retries'])}</td>"
            "</tr>"
        )
    passed = sum(row["result"] == "PASS" for row in rows)
    failed = len(rows) - passed
    overall = "PASS" if failed == 0 and cleanup_result == "PASS" else "FAIL"
    cleanup_css = "passed" if cleanup_result == "PASS" else "failed"
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>CruxCoach E2E — {overall}</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 2rem auto; max-width: 1000px; padding: 0 1rem; }}
    table {{ border-collapse: collapse; width: 100%; }}
    th, td {{ border-bottom: 1px solid #8886; padding: .55rem; text-align: left; }}
    .passed {{ color: #2da765; font-weight: 800; }}
    .failed {{ color: #e25c5c; font-weight: 800; }}
  </style>
</head>
<body>
  <h1>CruxCoach E2E run</h1>
  <p><strong>{overall}</strong> — {passed} passed, {failed} failed, {len(rows)} total roots; cleanup/restore <span class="{cleanup_css}">{escaped(cleanup_result)}</span>.</p>
  <table>
    <thead><tr><th>Repeat</th><th>Seq</th><th>Flow</th><th>Result</th><th>Maestro exit</th><th>Infra retries</th></tr></thead>
    <tbody>{''.join(table_rows)}</tbody>
  </table>
</body>
</html>
"""
    destination.write_text(document, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cleanup-result", required=True)
    args = parser.parse_args()
    try:
        render(args.results, args.run_dir, args.output, args.cleanup_result)
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
