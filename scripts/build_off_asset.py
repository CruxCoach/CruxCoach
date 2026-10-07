#!/usr/bin/env python3
"""Builds androidApp/src/main/assets/fuel/off_products.db.zst from Open Food Facts.

Keeps every product sold in a German-speaking (DE, AT, CH) or
English-speaking country (GB, IE, US, CA, AU, NZ) that has a name and
plausible energy, protein, carbohydrate and fat per 100 g (energy within
25 % or 40 kcal of 4/4/9 kcal per gram of the macros). Columns: barcode,
German name, English name (either may be empty, never both), first brand,
kcal, protein, carbs, fat, serving in g, regions (1 = German-speaking,
2 = English-speaking, 3 = both), serving label as printed on the pack.

The APK gets the finished SQLite table (zstd 19, 8 MiB window, decoded by
the bundled zstd library); the phone only adds the FTS index – unpacking and
importing 680,000 rows took 3+ minutes on a Nokia 6.1. `--out` writes the
same rows as TSV for the update channel (cruxcoach-blossom-sync).

Source: Open Food Facts, https://world.openfoodfacts.org/data — database
under the Open Database License (ODbL), contents under the Database Contents
License. The extract is a derivative database and is offered under the ODbL.

    python3 scripts/build_off_asset.py                       # downloads the export (~1.3 GB)
    python3 scripts/build_off_asset.py --csv products.csv.gz
    python3 scripts/build_off_asset.py --from-tsv off_products.tsv.zst    # asset from a TSV extract
    python3 scripts/build_off_asset.py --csv products.csv.gz --out /tmp/food/off_products.tsv.zst
"""

from __future__ import annotations

import argparse
import csv
import datetime
import gzip
import io
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

EXPORT = "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"
ASSET = Path(__file__).resolve().parent.parent / "androidApp/src/main/assets/fuel/off_products.db.zst"
# Schema of OffRepository (keep in step): the barcode is the integer row key.
SCHEMA = (
    "CREATE TABLE product(code INTEGER PRIMARY KEY, name_de TEXT NOT NULL, name_en TEXT NOT NULL, brand TEXT NOT NULL, "
    "kcal REAL NOT NULL, protein REAL NOT NULL, carbs REAL NOT NULL, fat REAL NOT NULL, serving REAL, regions INTEGER NOT NULL, "
    "serving_label TEXT)",
    "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
)
# Lets the app see a newer extract after an update without decompressing it.
VERSION_FILE = ASSET.with_name("off_products.version")
REGIONS = {
    1: ("en:germany", "en:austria", "en:switzerland"),
    2: ("en:united-kingdom", "en:ireland", "en:united-states", "en:canada", "en:australia", "en:new-zealand"),
}


def number(row: list[str], ix: dict[str, int], col: str) -> float | None:
    i = ix.get(col)
    if i is None or i >= len(row) or not row[i]:
        return None
    try:
        return float(row[i])
    except ValueError:
        return None


GRAMS_ONLY = re.compile(r"^\s*\d+(?:[.,]\d+)?\s*g\s*$", re.IGNORECASE)


def clean(text: str, limit: int) -> str:
    return " ".join(text.replace("\t", " ").split())[:limit]


def fmt(v: float) -> str:
    return f"{round(v, 2):g}"


def extract(stream: io.TextIOBase) -> tuple[list[str], int, dict[int, int]]:
    csv.field_size_limit(10**9)
    reader = csv.reader(stream, delimiter="\t", quoting=csv.QUOTE_NONE)
    header = next(reader)
    ix = {h: i for i, h in enumerate(header)}
    rows: dict[str, str] = {}
    per_region = {1: 0, 2: 0, 3: 0}
    total = 0

    def col(row: list[str], name: str) -> str:
        i = ix.get(name)
        return row[i].strip() if i is not None and i < len(row) else ""

    for row in reader:
        total += 1
        countries = col(row, "countries_tags")
        regions = sum(bit for bit, tags in REGIONS.items() if any(t in countries for t in tags))
        if not regions:
            continue
        main, lang = col(row, "product_name"), col(row, "lang")
        name_de = clean(col(row, "product_name_de") or (main if lang == "de" else ""), 120)
        name_en = clean(col(row, "product_name_en") or (main if lang == "en" else ""), 120)
        if not name_de and not name_en:
            # Another language only: show it under the English name.
            name_en = clean(main, 120)
        if name_de == name_en:
            name_de = ""
        code = col(row, "code")
        if not (name_de or name_en) or not code.isdigit():
            continue
        kcal, p, c, f = (number(row, ix, k) for k in ("energy-kcal_100g", "proteins_100g", "carbohydrates_100g", "fat_100g"))
        if None in (kcal, p, c, f):
            continue
        if not (0 <= kcal <= 950 and all(0 <= v <= 100 for v in (p, c, f)) and p + c + f <= 105):
            continue
        if abs(4 * p + 4 * c + 9 * f - kcal) > max(40, 0.25 * kcal):
            continue
        brand = clean(row[ix["brands"]].split(",")[0], 60) if ix["brands"] < len(row) else ""
        serving = number(row, ix, "serving_quantity")
        serving_text = fmt(serving) if serving and 0 < serving <= 2000 else ""
        # The label's own words for a serving ("1 cup (30 g)", "1 Riegel (40 g)", "330 ml");
        # a bare gram figure adds nothing to serving_g.
        label = clean(col(row, "serving_size"), 40) if serving_text else ""
        if GRAMS_ONLY.match(label):
            label = ""
        rows[code] = "\t".join([code, name_de, name_en, brand, fmt(kcal), fmt(p), fmt(c), fmt(f), serving_text,
                                 str(regions), label])
        per_region[regions] += 1
    return [rows[k] for k in sorted(rows)], total, per_region


def barcode_key(code: str) -> int | None:
    """OffTable.barcodeKey: 1–18 digits, leading zeros dropped, never 0."""
    if not code or len(code) > 18 or not code.isdigit():
        return None
    return int(code) or None


def build_database(lines: list[str], version: str, path: Path) -> int:
    """The app's product table, ready to use; the phone only adds the FTS index.

    Same rules as OffRepository's TSV import (later rows win on a shared key),
    so a bundled database and an update from the TSV channel hold the same data.
    """
    import sqlite3

    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    db.execute("PRAGMA page_size = 4096")
    for statement in SCHEMA:
        db.execute(statement)
    rows = []
    for line in lines:
        f = line.split("\t")
        key = barcode_key(f[0])
        if key is None or len(f) < 8 or not (f[1] or f[2]):
            continue
        serving = float(f[8]) if len(f) > 8 and f[8] else None
        rows.append((key, f[1], f[2], f[3], float(f[4]), float(f[5]), float(f[6]), float(f[7]),
                     serving if serving and serving > 0 else None,
                     int(f[9]) if len(f) > 9 and f[9] else 0, (f[10] if len(f) > 10 else "") or None))
    db.executemany("INSERT OR REPLACE INTO product VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
    db.executemany("INSERT INTO meta VALUES (?, ?)", [("version", version), ("fts", "0")])
    db.commit()
    count = db.execute("SELECT count(*) FROM product").fetchone()[0]
    db.execute("VACUUM")
    db.close()
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--csv", help="local copy of the export (.csv or .csv.gz); default downloads it")
    parser.add_argument("--out", help="write the TSV extract here instead of the app asset (for a Blossom update, "
                                      "see cruxcoach-blossom-sync/food_products_blossom_upload.py)")
    parser.add_argument("--from-tsv", help="build the app asset from an existing TSV extract (.tsv.zst) "
                                           "instead of reading the export")
    args = parser.parse_args()

    if args.from_tsv:
        text = subprocess.run(["zstd", "-dc", args.from_tsv], capture_output=True, check=True).stdout.decode("utf-8")
        version = next(line.split(":", 1)[1].strip() for line in text.splitlines() if line.startswith("# version:"))
        lines = [line for line in text.splitlines() if line and not line.startswith("#")]
        write_asset(lines, version)
        return

    if args.csv:
        raw = open(args.csv, "rb")
    else:
        raw = urllib.request.urlopen(EXPORT, timeout=120)
    binary = gzip.GzipFile(fileobj=raw) if (args.csv or EXPORT).endswith(".gz") else raw
    lines, total, per_region = extract(io.TextIOWrapper(binary, encoding="utf-8", errors="replace", newline=""))

    today = datetime.date.today().isoformat()
    header = [
        "# Open Food Facts, https://world.openfoodfacts.org — Open Database License (ODbL) 1.0,",
        "# contents: Database Contents License (DbCL) 1.0. Extract by CruxCoach (scripts/build_off_asset.py).",
        f"# version: {today}",
        f"# products: {len(lines)} of {total} (German-speaking {per_region[1]}, English-speaking {per_region[2]}, "
        f"both {per_region[3]}; plausible energy, protein, carbohydrate, fat per 100 g)",
        "# code\tname_de\tname_en\tbrand\tkcal\tprotein_g\tcarbs_g\tfat_g\tserving_g\tregions\tserving_label",
    ]
    if args.out:
        data = ("\n".join(header + lines) + "\n").encode("utf-8")
        out = Path(args.out).resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["zstd", "-q", "-f", "-19", "-o", str(out)], input=data, check=True)
        print(f"wrote {len(lines)} products {per_region} ({len(data) / 1e6:.1f} MB raw, "
              f"{out.stat().st_size / 1e6:.1f} MB zstd) to {out}")
    else:
        write_asset(lines, today)


def write_asset(lines: list[str], version: str) -> None:
    """The bundled asset: the finished table as SQLite, zstd 19 (8 MiB window)."""
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "off_products.db"
        count = build_database(lines, version, db_path)
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["zstd", "-q", "-f", "-19", str(db_path), "-o", str(ASSET)], check=True)
        raw = db_path.stat().st_size
    VERSION_FILE.write_text(f"{version} {len(lines)}\n", encoding="utf-8")
    print(f"wrote {count} products ({raw / 1e6:.1f} MB SQLite, {ASSET.stat().st_size / 1e6:.1f} MB zstd) to {ASSET}")


if __name__ == "__main__":
    if sys.version_info < (3, 9):
        sys.exit("Python 3.9+ required")
    main()
