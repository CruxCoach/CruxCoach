#!/usr/bin/env python3
"""Build the table of catalogue climbs that Kilter keeps under another id.

CruxCoach's Kilter catalogue still carries compact ids that Kilter's own
catalogue (`/api/climbs/all/<productLayoutUuid>`) no longer lists. For most of
them Kilter has the very same climb (identical holds and roles, same name) under
a new id, mostly a dashed one from the 2026-03-26 migration. A log naming the
old id is refused with HTTP 500 (verified live on 2026-10-03: "A Bigger Squeeze"
is refused as 1be3d36a… in both cases and accepted as 0D9A1ED7…), so the upload
offers the id in this table as its last candidate. It is a fallback, never a
substitute: a few old ids are still accepted although `/climbs/all` omits them.

A pair is written only when the holds match exactly (hole and role, roles of the
42–45 sets mapped to 12–15) and the names agree; among several such Kilter
climbs the one by the same setter wins, then a listed one, then the oldest.

Inputs:
    BOARD_DB      a CruxCoach board catalogue (tables `climbs`, `placements`)
    SNAPSHOT_DIR  climbs_all_<n>.json.gz as written by cruxcoach-blossom-sync
                  `backfill_nomatch.py fetch`
Output: one line per old id, sorted, `<old 32-hex lowercase>\\t<Kilter id as stored>`.

Usage:
    build_climb_alias_index.py BOARD_DB SNAPSHOT_DIR OUTPUT_FILE
"""
import glob
import gzip
import json
import os
import re
import sqlite3
import sys

HEX32 = re.compile(r"[0-9a-f]{32}")
TEXT_HOLD = re.compile(r"p(\d+)r(\d+)")
CONCAT_HOLD = re.compile(r"h(\d+)p(\d+)")


def role(r: int) -> int:
    return r - 30 if r >= 42 else r


def catalogue_holds(frames: bytes, hole_of: dict):
    if not frames:
        return None
    if frames[:1] == b"p":
        pairs = [(int(p), int(r)) for p, r in TEXT_HOLD.findall(frames.decode("ascii", "replace"))]
    elif len(frames) % 3 == 0 and frames[:1] != b"\xff":
        pairs = [(frames[i] | (frames[i + 1] << 8), frames[i + 2]) for i in range(0, len(frames), 3)]
    else:
        return None
    if not pairs or any(p not in hole_of for p, _ in pairs):
        return None
    return frozenset((hole_of[p], role(r)) for p, r in pairs)


def snapshot_climbs(snapshot_dir: str):
    for path in sorted(glob.glob(os.path.join(snapshot_dir, "climbs_all_*.json.gz"))):
        with gzip.open(path) as fh:
            data = json.load(fh)
        yield from (data if isinstance(data, list) else data.get("climbs", []))


def norm(name) -> str:
    return (name or "").strip().lower()


def main(board_db: str, snapshot_dir: str, output: str) -> int:
    db = sqlite3.connect(board_db)
    hole_of = dict(db.execute("SELECT placement_id, hole_id FROM placements WHERE board_brand = 'kilter'"))
    rows = db.execute(
        "SELECT uuid, name, setter_username, frames FROM climbs "
        "WHERE board_brand = 'kilter' AND origin = 'kilter' AND length(uuid) = 32"
    ).fetchall()
    snapshot = list(snapshot_climbs(snapshot_dir))
    if not snapshot:
        print(f"no climbs_all_*.json.gz in {snapshot_dir}", file=sys.stderr)
        return 1
    listed_ids = {c["climbUuid"].replace("-", "").lower() for c in snapshot}
    missing = {}
    for uuid, name, setter, frames in rows:
        if HEX32.fullmatch(uuid) and uuid not in listed_ids:
            raw = frames.encode() if isinstance(frames, str) else bytes(frames or b"")  # text rows from the CAST migration
            holds = catalogue_holds(raw, hole_of)
            if holds:
                missing.setdefault(holds, []).append((uuid, norm(name), (setter or "").lower()))
    candidates = {}
    for climb in snapshot:
        holds = frozenset((int(h), role(int(r))) for h, r in CONCAT_HOLD.findall(climb.get("climbConcat") or ""))
        if holds in missing and not climb.get("isDeleted"):
            candidates.setdefault(holds, []).append(climb)
    aliases = {}
    for holds, olds in missing.items():
        for uuid, name, setter in olds:
            same = [c for c in candidates.get(holds, []) if norm(c.get("name")) == name]
            if not same:
                continue
            same.sort(key=lambda c: ((c.get("username") or "").lower() != setter, not c.get("isListed"), c.get("createdAt") or ""))
            aliases[uuid] = same[0]["climbUuid"]
    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
    with open(output, "w", encoding="ascii", newline="\n") as fh:
        for old in sorted(aliases):
            fh.write(f"{old}\t{aliases[old]}\n")
    unmatched = sum(len(v) for v in missing.values()) - len(aliases)
    print(json.dumps({"catalogue_compact": len(rows), "not_listed_by_kilter": sum(len(v) for v in missing.values()),
                      "aliases": len(aliases), "without_counterpart": unmatched, "bytes": os.path.getsize(output)}))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        sys.exit(64)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3]))
