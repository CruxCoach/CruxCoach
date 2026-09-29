#!/usr/bin/env python3
"""Build the index of Kilter climbs whose canonical id is compact LOWERCASE.

Kilter keys a legacy climb by 32 hex characters without hyphens, and it
compares them case-sensitively: a log that names the climb in the other case
is rejected with HTTP 500 (verified live on 2026-09-29). Most legacy ids are
uppercase, about a quarter are lowercase, and every climb exists in exactly
one spelling. The app's catalogue stores every uuid lowercased, so the spelling
Kilter expects is lost on the device; this index restores it for the upload.

The set is closed: since the 2026-03-26 migration Kilter creates only dashed
uuids, and the few compact ids created later come from CruxCoach itself, which
the app recognizes on its own.

Input: `/api/climbs/all/<productLayoutUuid>` snapshots as written by
cruxcoach-blossom-sync `backfill_nomatch.py fetch` (climbs_all_<n>.json.gz).
Output format (big-endian):
    magic  b"KLID"
    u8     version (1)
    u32    count
    i64[]  first 64 bits of each id, sorted as signed values, unique

Usage:
    build_lowercase_climb_index.py SNAPSHOT_DIR OUTPUT_FILE
"""
import glob
import gzip
import json
import os
import re
import struct
import sys

HEX32_LOWER = re.compile(r"[0-9a-f]{32}")
HEX32_UPPER = re.compile(r"[0-9A-F]{32}")
DASHED = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def key64(hex32: str) -> int:
    value = int(hex32[:16], 16)
    return value - (1 << 64) if value >= (1 << 63) else value


def climbs(path):
    with gzip.open(path) as fh:
        data = json.load(fh)
    if isinstance(data, list):
        return data
    return data.get("climbs") or next((v for v in data.values() if isinstance(v, list)), [])


def main(snapshot_dir: str, output: str) -> int:
    files = sorted(glob.glob(os.path.join(snapshot_dir, "climbs_all_*.json.gz")))
    if not files:
        print(f"no climbs_all_*.json.gz in {snapshot_dir}", file=sys.stderr)
        return 1
    lower, other = set(), set()
    counts = {"lower": 0, "upper": 0, "dashed": 0, "other": 0}
    for path in files:
        for climb in climbs(path):
            uuid = (climb.get("climbUuid") or climb.get("uuid") or "").strip()
            if HEX32_LOWER.fullmatch(uuid):
                lower.add(key64(uuid))
                counts["lower"] += 1
            elif HEX32_UPPER.fullmatch(uuid):
                other.add(key64(uuid.lower()))
                counts["upper"] += 1
            elif DASHED.fullmatch(uuid):
                other.add(key64(uuid.replace("-", "").lower()))
                counts["dashed"] += 1
            else:
                counts["other"] += 1
    clash = lower & other
    if clash:
        print(f"{len(clash)} 64-bit prefixes shared with non-lowercase ids; widen the key", file=sys.stderr)
        return 2
    keys = sorted(lower)
    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
    with open(output, "wb") as fh:
        fh.write(b"KLID")
        fh.write(struct.pack(">BI", 1, len(keys)))
        fh.write(struct.pack(f">{len(keys)}q", *keys))
    print(json.dumps({"files": len(files), **counts, "indexed": len(keys),
                      "bytes": os.path.getsize(output)}))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(64)
    sys.exit(main(sys.argv[1], sys.argv[2]))
