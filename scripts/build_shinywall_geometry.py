#!/usr/bin/env python3
"""Build the bundled Shiny Wall hold geometry (assets/board_geometry/shinywall.sqlite3).

Shiny Wall's catalogue chunk carries routes only. The hold positions of the
Standard variants ship in the APK instead, so own and community climbs can be
drawn and edited without a download and a corrected table reaches every
install with the next update (the app replaces bundle-owned geometry whenever
the bundle changes, see BundledBoardGeometry.BUNDLE_OWNED).

Input: a geometry JSON in the "cruxcoach-shinywall-geometry-v1" format
(variants -> hold_count, grid, holds[index, name, col, row, x, y, present];
origin bottom left, x/y in cell units). A hold with "present": false exists
in the controller's state vector but has no hold on the wall (a placeholder
index); it gets no placement, so it is never drawn or selectable and the
encoder always sends it as off. Output: the app's own geometry schema,
brand-namespaced, with ids that match ShinyWallVariant in the app:

* layout 9300 + slot, product size 9400 + slot (slot 1..6 for st0..st5)
* placement_id = slot * 1_000_000 + hold index
* x/y scaled to integers; every variant gets its own x region so the
  variants never overlap when the app loads all placements of the brand.

Only numbers go into the file: no images, names of holds are not stored.
Usage: scripts/build_shinywall_geometry.py GEOMETRY.json [--out PATH]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

FORMAT = "cruxcoach-shinywall-geometry-v1"
BRAND = "shinywall"
PRODUCT_ID = 93
LAYOUT_BASE = 9300
SIZE_BASE = 9400
PLACEMENT_STRIDE = 1_000_000
# One cell in stored coordinates (small-hold rows sit a quarter cell apart, so
# 20 keeps them on integers), and the x offset between variant regions.
UNIT = 20
REGION = 500
MARGIN = UNIT // 2

VARIANT_SLOTS = {"st0": 1, "st1": 2, "st2": 3, "st3": 4, "st4": 5, "st5": 6}
VARIANT_NAMES = {
    "st0": "Standard Mini",
    "st1": "Standard",
    "st2": "Standard Max",
    "st3": "Standard Mini Full",
    "st4": "Standard Full",
    "st5": "Standard Max Full",
}
ROLES = [
    (12, "start", "00FF00", "00DD00"),
    (13, "hand", "00B4FF", "00B4FF"),
    (14, "top", "FF00FF", "FF00FF"),
    (15, "foot", "FFA000", "FFA000"),
]

SCHEMA = """
CREATE TABLE placements (
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    placement_id INTEGER NOT NULL,
    hole_id INTEGER NOT NULL,
    set_id INTEGER NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    PRIMARY KEY (board_brand, placement_id)
);
CREATE TABLE product_sizes (
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    edge_left INTEGER NOT NULL,
    edge_right INTEGER NOT NULL,
    edge_bottom INTEGER NOT NULL,
    edge_top INTEGER NOT NULL,
    image_filename TEXT,
    PRIMARY KEY (board_brand, id)
);
CREATE TABLE board_images (
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    id INTEGER NOT NULL,
    product_size_id INTEGER NOT NULL,
    layout_id INTEGER NOT NULL,
    set_id INTEGER NOT NULL,
    image_filename TEXT NOT NULL,
    PRIMARY KEY (board_brand, id)
);
CREATE TABLE leds (
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    hole_id INTEGER NOT NULL,
    product_size_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (board_brand, hole_id, product_size_id)
);
CREATE TABLE placement_roles (
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    id INTEGER NOT NULL,
    name TEXT,
    led_color TEXT,
    screen_color TEXT,
    PRIMARY KEY (board_brand, id)
);
CREATE TABLE bundle_source (
    board_brand TEXT NOT NULL PRIMARY KEY,
    manifest_event_id TEXT NOT NULL,
    manifest_created_at INTEGER NOT NULL,
    chunk_name TEXT NOT NULL,
    chunk_sha256 TEXT NOT NULL
);
"""


class GeometryError(ValueError):
    pass


def _scaled(value: float) -> int:
    return int(round(value * UNIT))


def validate(doc: dict) -> dict:
    """Checks the geometry document and returns its variants by code."""
    if doc.get("format") != FORMAT:
        raise GeometryError(f"unexpected format {doc.get('format')!r}")
    variants = doc.get("variants")
    if not isinstance(variants, dict) or not variants:
        raise GeometryError("no variants")
    for code, variant in variants.items():
        if code not in VARIANT_SLOTS:
            raise GeometryError(f"unknown variant {code!r}")
        count = variant.get("hold_count")
        holds = variant.get("holds")
        grid = variant.get("grid") or {}
        if not isinstance(count, int) or count <= 0:
            raise GeometryError(f"{code}: hold_count missing")
        if not isinstance(holds, list) or len(holds) != count:
            raise GeometryError(f"{code}: {len(holds or [])} holds for hold_count {count}")
        indices = sorted(h.get("index") for h in holds)
        if indices != list(range(count)):
            raise GeometryError(f"{code}: hold indices are not 0..{count - 1} without gaps")
        if not isinstance(grid.get("columns"), int) or not isinstance(grid.get("rows"), int):
            raise GeometryError(f"{code}: grid columns/rows missing")
        present = [h for h in holds if h.get("present", True) is not False]
        if not present:
            raise GeometryError(f"{code}: no hold has a position")
        for h in present:
            for key in ("x", "y"):
                if not isinstance(h.get(key), (int, float)):
                    raise GeometryError(f"{code}: hold {h.get('index')} has no {key}")
            if not (-1 <= h["x"] <= grid["columns"] and -1 <= h["y"] <= grid["rows"]):
                raise GeometryError(f"{code}: hold {h['index']} lies outside its grid")
    return variants


def build(doc: dict, out: Path, source_sha256: str) -> dict[str, int]:
    """Writes the geometry bundle; returns placements per variant."""
    variants = validate(doc)
    if out.exists():
        out.unlink()
    out.parent.mkdir(parents=True, exist_ok=True)
    counts: dict[str, int] = {}
    con = sqlite3.connect(out)
    try:
        con.executescript(SCHEMA)
        for code in sorted(variants, key=VARIANT_SLOTS.get):
            variant = variants[code]
            slot = VARIANT_SLOTS[code]
            offset = slot * REGION
            columns = variant["grid"]["columns"]
            present = [h for h in variant["holds"] if h.get("present", True) is not False]
            top = max(_scaled(h["y"]) for h in present)
            con.execute(
                "INSERT INTO product_sizes VALUES (?,?,?,?,?,?,?,?,NULL)",
                (
                    BRAND, SIZE_BASE + slot, PRODUCT_ID, VARIANT_NAMES[code],
                    offset - MARGIN, offset + _scaled(columns - 1) + MARGIN,
                    -MARGIN, top + MARGIN,
                ),
            )
            for hold in sorted(present, key=lambda h: h["index"]):
                placement = slot * PLACEMENT_STRIDE + hold["index"]
                con.execute(
                    "INSERT INTO placements VALUES (?,?,?,?,?,?)",
                    (BRAND, placement, placement, slot, offset + _scaled(hold["x"]), _scaled(hold["y"])),
                )
            counts[code] = len(present)
        con.executemany(
            "INSERT INTO placement_roles VALUES (?,?,?,?,?)",
            [(BRAND, rid, name, led, screen) for rid, name, led, screen in ROLES],
        )
        con.execute(
            "INSERT INTO bundle_source VALUES (?,?,?,?,?)",
            (BRAND, f"derived:{source_sha256[:16]}", 0, "shinywall_geometry_v1", source_sha256),
        )
        con.commit()
        con.execute("VACUUM")
    finally:
        con.close()
    return counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("geometry", type=Path)
    parser.add_argument(
        "--out", type=Path,
        default=Path(__file__).resolve().parent.parent
        / "androidApp/src/main/assets/board_geometry/shinywall.sqlite3",
    )
    args = parser.parse_args(argv)
    raw = args.geometry.read_bytes()
    try:
        counts = build(json.loads(raw), args.out, hashlib.sha256(raw).hexdigest())
    except GeometryError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for code, n in counts.items():
        print(f"{code}: {n} holds")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
