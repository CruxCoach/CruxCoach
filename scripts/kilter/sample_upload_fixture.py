#!/usr/bin/env python3
"""Sample real Kilter climb ids for the log-upload simulation test.

KilterUploadContractSimulationTest runs the upload engine against an in-memory
Kilter that knows these climbs in their canonical spelling, so the id
handling is checked against real ids instead of invented ones: compact
UPPERCASE (the legacy majority), compact lowercase (the quarter 0.2.3 broke)
and dashed lowercase (every climb created since the 2026-03-26 migration).

Only the id and its product layout are written - no names, setters, user
uuids or other fields. Drafts are skipped (nobody logs them), as are the few
ids that are not hexadecimal. The sample is a deterministic, stratified draw
per spelling (fixed seed), so the same snapshot always yields the same file.

Input: `/api/climbs/all/<productLayoutUuid>` snapshots (climbs_all_<n>.json.gz),
as used by build_lowercase_climb_index.py.

Usage:
    sample_upload_fixture.py SNAPSHOT_DIR OUTPUT_FILE
"""
import glob
import gzip
import json
import os
import random
import re
import sys

SEED = "kilter-upload-simulation-2026-09-29"
SAMPLE = {"upper": 1200, "lower": 600, "dashed": 400}

PATTERNS = {
    "upper": re.compile(r"[0-9A-F]{32}"),
    "lower": re.compile(r"[0-9a-f]{32}"),
    "dashed": re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
}


def climbs(path):
    with gzip.open(path) as fh:
        data = json.load(fh)
    if isinstance(data, list):
        return data
    return data.get("climbs") or next((v for v in data.values() if isinstance(v, list)), [])


def spelling(uuid):
    return next((name for name, pattern in PATTERNS.items() if pattern.fullmatch(uuid)), None)


def main(snapshot_dir, output):
    files = sorted(glob.glob(os.path.join(snapshot_dir, "climbs_all_*.json.gz")))
    if not files:
        print(f"no climbs_all_*.json.gz in {snapshot_dir}", file=sys.stderr)
        return 1
    pool = {name: set() for name in SAMPLE}
    for path in files:
        for climb in climbs(path):
            if climb.get("isDraft") or climb.get("isDeleted"):
                continue
            uuid = (climb.get("climbUuid") or "").strip()
            layout = str(climb.get("productLayoutUuid") or "").strip()
            name = spelling(uuid)
            if name and layout.isdigit():
                pool[name].add((layout, uuid))
    lines = []
    counts = {}
    for name, size in SAMPLE.items():
        candidates = sorted(pool[name])
        if len(candidates) < size:
            print(f"only {len(candidates)} {name} ids, need {size}", file=sys.stderr)
            return 2
        picked = random.Random(f"{SEED}:{name}").sample(candidates, size)
        counts[name] = {"pool": len(candidates), "sampled": size}
        lines += sorted(f"{layout}\t{uuid}" for layout, uuid in picked)
    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
    with open(output, "w", encoding="ascii", newline="\n") as fh:
        fh.write("# Real Kilter climb ids (canonical spelling) and their product layout, nothing else.\n")
        fh.write("# Stratified sample of the /climbs/all snapshot of 2026-07-26 (drafts skipped):\n")
        fh.write("# " + ", ".join(f"{n} {c['sampled']} of {c['pool']}" for n, c in counts.items()) + f"; seed {SEED}.\n")
        fh.write("# Regenerate: python3 scripts/kilter/sample_upload_fixture.py SNAPSHOT_DIR THIS_FILE\n")
        fh.write("# Format: <productLayoutUuid> TAB <climbUuid>\n")
        fh.write("\n".join(lines) + "\n")
    print(json.dumps({"files": len(files), **counts, "bytes": os.path.getsize(output)}))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(64)
    sys.exit(main(sys.argv[1], sys.argv[2]))
