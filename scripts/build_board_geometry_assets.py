#!/usr/bin/env python3
"""Build the bundled per-board hold geometry the app seeds without a catalogue.

Community climbs arrive live over Nostr and carry only `frames` (placement ids
plus roles). Drawing them, fitting them to a board size and lighting them on
the board needs that board's geometry: placements (x/y per placement), product
sizes, board images, LED addresses and placement roles. The app used to get it
only with the board's full catalogue, so a board the user did not download
showed community climbs on an empty wall and could not light them.

This script fetches the same signed catalogue manifests the app uses, verifies
them exactly like the app does (manifest pubkey, event id, BIP-340 signature,
d-tag, chunk SHA-256), and writes each board's geometry into a small SQLite
file under androidApp/src/main/assets/board_geometry/. The rows are produced
with the importer's own SQL, so a seeded board looks exactly like one whose
catalogue was imported. The app only seeds a board that has no geometry yet;
a later catalogue import replaces the rows.

Quantum is left out on purpose: its snapshot is an authorised eWalls export,
and whether that authorisation covers shipping it inside the APK is a product
decision. MoonBoard needs nothing here; its geometry is bundled separately.

Needs Python 3.10+, the `websockets` package and the `zstd` CLI.
Usage: scripts/build_board_geometry_assets.py [--out DIR] [--board kilter ...]
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

import websockets

# Mirrors BlossomSyncManager.MANIFEST_PUBKEY and NostrConfig.MANIFEST_RELAYS.
MANIFEST_PUBKEY = "70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d"
MANIFEST_RELAYS = [
    "wss://relay.primal.net",
    "wss://relay.damus.io",
    "wss://nostr-pub.wellorder.net",
    "wss://nos.lol",
    "wss://nostr.oxtr.dev",
    "wss://blossom.cruxcoach.org/nostr",
]
# Kilter keeps the historical d-tag; the other Aurora boards use
# "cruxcoach/<wire>-db" (AuroraCatalogueSync).
BOARDS = {
    "kilter": "cruxcoach/board-db",
    "tension": "cruxcoach/tension-db",
    "grasshopper": "cruxcoach/grasshopper-db",
    "decoy": "cruxcoach/decoy-db",
    "soill": "cruxcoach/soill-db",
    "touchstone": "cruxcoach/touchstone-db",
}
MAX_CHUNK_BYTES = 512 * 1024 * 1024

# Target schema, copied from shared/.../db/board/Board.sq.
TARGET_SCHEMA = """
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

# ── BIP-340 Schnorr verification (secp256k1), as Nostr events use it ────────
_P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
_G = (
    0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
    0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8,
)


def _point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and a[1] != b[1]:
        return None
    if a == b:
        lam = 3 * a[0] * a[0] * pow(2 * a[1], _P - 2, _P) % _P
    else:
        lam = (b[1] - a[1]) * pow(b[0] - a[0], _P - 2, _P) % _P
    x = (lam * lam - a[0] - b[0]) % _P
    return x, (lam * (a[0] - x) - a[1]) % _P


def _point_mul(point, n):
    result = None
    for i in range(256):
        if (n >> i) & 1:
            result = _point_add(result, point)
        point = _point_add(point, point)
    return result


def _lift_x(x):
    if x >= _P:
        return None
    y_sq = (pow(x, 3, _P) + 7) % _P
    y = pow(y_sq, (_P + 1) // 4, _P)
    if pow(y, 2, _P) != y_sq:
        return None
    return x, y if y % 2 == 0 else _P - y


def _tagged_hash(tag: str, msg: bytes) -> bytes:
    tag_hash = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(tag_hash + tag_hash + msg).digest()


def schnorr_verify(msg: bytes, pubkey: bytes, sig: bytes) -> bool:
    if len(msg) != 32 or len(pubkey) != 32 or len(sig) != 64:
        return False
    point = _lift_x(int.from_bytes(pubkey, "big"))
    r = int.from_bytes(sig[:32], "big")
    s = int.from_bytes(sig[32:], "big")
    if point is None or r >= _P or s >= _N:
        return False
    e = int.from_bytes(_tagged_hash("BIP0340/challenge", sig[:32] + pubkey + msg), "big") % _N
    big_r = _point_add(_point_mul(_G, s), _point_mul(point, _N - e))
    return big_r is not None and big_r[1] % 2 == 0 and big_r[0] == r


def event_id(event: dict) -> str:
    serialized = json.dumps(
        [0, event["pubkey"], event["created_at"], event["kind"], event["tags"], event["content"]],
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def verified_manifest(event: dict, d_tag: str) -> dict | None:
    """The app's checks: pubkey, event id, signature, d-tag, then the body."""
    if event.get("pubkey") != MANIFEST_PUBKEY or event.get("kind") != 30078:
        return None
    if event_id(event) != event.get("id"):
        return None
    if not schnorr_verify(bytes.fromhex(event["id"]), bytes.fromhex(event["pubkey"]), bytes.fromhex(event["sig"])):
        return None
    tags = [t for t in event.get("tags", []) if t and t[0] == "d"]
    if not tags or tags[0][1:2] != [d_tag]:
        return None
    manifest = json.loads(event["content"])
    if manifest.get("compression") != "zstd" or not manifest.get("chunks"):
        return None
    manifest["_event_id"] = event["id"]
    manifest["_event_created_at"] = event["created_at"]
    return manifest


async def _fetch_from_relay(relay: str, d_tag: str) -> list[dict]:
    flt = {"kinds": [30078], "authors": [MANIFEST_PUBKEY], "#d": [d_tag], "limit": 1}
    events = []
    try:
        async with websockets.connect(relay, open_timeout=10, close_timeout=2, max_size=4 * 1024 * 1024) as ws:
            await ws.send(json.dumps(["REQ", "geometry", flt]))
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
                if msg[0] == "EVENT":
                    events.append(msg[2])
                elif msg[0] in ("EOSE", "CLOSED"):
                    break
    except Exception as e:  # one relay failing must not stop the others
        print(f"  {relay}: {type(e).__name__}", file=sys.stderr)
    return events


def fetch_manifest(d_tag: str) -> dict:
    async def gather():
        return await asyncio.gather(*(_fetch_from_relay(r, d_tag) for r in MANIFEST_RELAYS))

    candidates = []
    for events in asyncio.run(gather()):
        for event in events:
            manifest = verified_manifest(event, d_tag)
            if manifest is not None:
                candidates.append(manifest)
    if not candidates:
        raise SystemExit(f"no verified manifest for {d_tag}")
    # NIP-01 ordering for parameterized-replaceable events: newest wins.
    return max(candidates, key=lambda m: (m["_event_created_at"], m["_event_id"]))


def download_chunk(chunk: dict, workdir: Path) -> Path:
    if chunk["size"] > MAX_CHUNK_BYTES:
        raise SystemExit(f"chunk {chunk['name']} too large: {chunk['size']}")
    compressed = workdir / f"{chunk['name']}.zst"
    for url in chunk["urls"]:
        if not url.startswith("https://"):
            continue
        try:
            with urllib.request.urlopen(url, timeout=60) as resp, open(compressed, "wb") as out:
                shutil.copyfileobj(resp, out)
        except Exception as e:
            print(f"  {url}: {type(e).__name__}", file=sys.stderr)
            continue
        digest = hashlib.sha256(compressed.read_bytes()).hexdigest()
        if digest == chunk["sha256"].lower():
            out_db = workdir / f"{chunk['name']}.sqlite3"
            subprocess.run(["zstd", "-d", "-q", "-f", "-o", str(out_db), str(compressed)], check=True)
            return out_db
        print(f"  {url}: sha256 mismatch", file=sys.stderr)
    raise SystemExit(f"could not download a verified copy of {chunk['name']}")


def _has_table(db: sqlite3.Connection, schema: str, name: str) -> bool:
    row = db.execute(f"SELECT COUNT(*) FROM {schema}.sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
    return row[0] > 0


def extract_kilter_meta(db: sqlite3.Connection) -> None:
    """BoardDatabaseImporter.importPlacements/ProductSizes/BoardImages/Leds.

    The importer reads these with Cursor.getLong, so integer columns are cast
    the same way here; rows are upserted like upsertPlacement & co.
    """
    if _has_table(db, "src", "aurora_placement"):
        placements = "SELECT placement_id, hole_id, set_id, x, y FROM src.aurora_placement"
    else:
        placements = (
            "SELECT p.id, p.hole_id, p.set_id, h.x, h.y FROM src.placements p "
            "JOIN src.holes h ON p.hole_id = h.id WHERE p.layout_id IN (SELECT id FROM src.layouts)"
        )
    db.execute(_kilter_placements_sql(placements))
    if _has_table(db, "src", "product_sizes"):
        sizes = "src.product_sizes WHERE is_listed = 1"
    else:
        sizes = "src.aurora_product_size"
    db.execute(
        "INSERT OR REPLACE INTO product_sizes(board_brand, id, product_id, name, "
        "edge_left, edge_right, edge_bottom, edge_top, image_filename) "
        "SELECT 'kilter', CAST(id AS INTEGER), CAST(product_id AS INTEGER), name, "
        "CAST(edge_left AS INTEGER), CAST(edge_right AS INTEGER), CAST(edge_bottom AS INTEGER), "
        f"CAST(edge_top AS INTEGER), image_filename FROM {sizes}"
    )
    if _has_table(db, "src", "product_sizes_layouts_sets"):
        images = "src.product_sizes_layouts_sets WHERE image_filename IS NOT NULL AND is_listed = 1"
    else:
        images = "src.aurora_board_image WHERE image_filename IS NOT NULL"
    db.execute(
        "INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) "
        "SELECT 'kilter', CAST(id AS INTEGER), CAST(product_size_id AS INTEGER), CAST(layout_id AS INTEGER), "
        f"CAST(set_id AS INTEGER), image_filename FROM {images}"
    )
    leds = "src.leds" if _has_table(db, "src", "leds") else "src.aurora_led"
    db.execute(
        "INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position) "
        "SELECT 'kilter', CAST(hole_id AS INTEGER), CAST(product_size_id AS INTEGER), "
        f"CAST(position AS INTEGER) FROM {leds}"
    )


def _kilter_placements_sql(select: str) -> str:
    # Wrap the importer's SELECT so every column gets the getLong cast.
    return (
        "INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y) "
        "SELECT 'kilter', CAST(q.a AS INTEGER), CAST(q.b AS INTEGER), CAST(q.c AS INTEGER), "
        "CAST(q.d AS INTEGER), CAST(q.e AS INTEGER) FROM ("
        + select.replace("SELECT placement_id, hole_id, set_id, x, y",
                         "SELECT placement_id AS a, hole_id AS b, set_id AS c, x AS d, y AS e")
                .replace("SELECT p.id, p.hole_id, p.set_id, h.x, h.y",
                         "SELECT p.id AS a, p.hole_id AS b, p.set_id AS c, h.x AS d, h.y AS e")
        + ") AS q"
    )


def extract_aurora_snapshot(db: sqlite3.Connection, brand: str) -> None:
    """BoardDatabaseImporter.importAuroraSnapshot, geometry block, verbatim."""
    db.execute(
        "INSERT OR REPLACE INTO product_sizes(board_brand, id, product_id, name, "
        "edge_left, edge_right, edge_bottom, edge_top, image_filename) "
        "SELECT ?, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename "
        "FROM src.product_sizes",
        (brand,),
    )
    db.execute(
        "INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) "
        "SELECT ?, id, product_size_id, layout_id, set_id, image_filename "
        "FROM src.product_sizes_layouts_sets WHERE image_filename IS NOT NULL",
        (brand,),
    )
    db.execute(
        "INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y) "
        "SELECT ?, p.id, p.hole_id, p.set_id, h.x, h.y FROM src.placements p JOIN src.holes h ON p.hole_id = h.id",
        (brand,),
    )
    db.execute(
        "INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position) "
        "SELECT ?, hole_id, product_size_id, position FROM src.leds",
        (brand,),
    )
    if _has_table(db, "src", "placement_roles"):
        db.execute(
            "INSERT OR REPLACE INTO placement_roles(board_brand, id, name, led_color, screen_color) "
            "SELECT ?, id, name, led_color, screen_color FROM src.placement_roles",
            (brand,),
        )


def build(brand: str, out_dir: Path, workdir: Path) -> None:
    d_tag = BOARDS[brand]
    print(f"{brand}: manifest {d_tag}")
    manifest = fetch_manifest(d_tag)
    if brand == "kilter":
        chunks = [c for c in manifest["chunks"] if c.get("type") == "meta" or c["name"] == "meta"]
    else:
        chunks = manifest["chunks"]
    if len(chunks) != 1:
        raise SystemExit(f"{brand}: expected exactly one geometry chunk, got {[c['name'] for c in chunks]}")
    chunk = chunks[0]
    source = download_chunk(chunk, workdir)

    target = workdir / f"{brand}.sqlite3"
    target.unlink(missing_ok=True)
    db = sqlite3.connect(target)
    db.executescript(TARGET_SCHEMA)
    db.execute("ATTACH DATABASE ? AS src", (str(source),))
    with db:
        if brand == "kilter":
            extract_kilter_meta(db)
            for marker in ("shared_syncs", "sync_states", "aurora_sync_state"):
                if _has_table(db, "src", marker):
                    rows = db.execute(f"SELECT COUNT(*) FROM src.{marker}").fetchone()[0]
                    print(f"  meta sync-state table {marker}: {rows} rows")
                    break
        else:
            extract_aurora_snapshot(db, brand)
        db.execute(
            "INSERT INTO bundle_source VALUES (?, ?, ?, ?, ?)",
            (brand, manifest["_event_id"], manifest["_event_created_at"], chunk["name"], chunk["sha256"].lower()),
        )
    db.execute("DETACH DATABASE src")
    counts = {t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
              for t in ("placements", "product_sizes", "board_images", "leds", "placement_roles")}
    if counts["placements"] == 0 or counts["product_sizes"] == 0 or counts["leds"] == 0:
        raise SystemExit(f"{brand}: incomplete geometry {counts}")
    db.execute("VACUUM")
    db.close()
    out = out_dir / f"{brand}.sqlite3"
    shutil.copyfile(target, out)
    print(f"  {counts} -> {out} ({out.stat().st_size} bytes)")


def main() -> None:
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=repo / "androidApp/src/main/assets/board_geometry")
    parser.add_argument("--board", action="append", choices=sorted(BOARDS), help="default: all")
    args = parser.parse_args()
    if shutil.which("zstd") is None:
        raise SystemExit("zstd CLI not found")
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for brand in args.board or sorted(BOARDS):
            build(brand, args.out, Path(tmp))


if __name__ == "__main__":
    main()
