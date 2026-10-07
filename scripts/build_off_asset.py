#!/usr/bin/env python3
"""Builds androidApp/src/main/assets/fuel/off_products.tsv.zst from Open Food Facts.

Keeps every product sold in a German-speaking (DE, AT, CH) or
English-speaking country (GB, IE, US, CA, AU, NZ) that has a name and
plausible energy, protein, carbohydrate and fat per 100 g (energy within
25 % or 40 kcal of 4/4/9 kcal per gram of the macros). Columns: barcode,
German name, English name (either may be empty, never both), first brand,
kcal, protein, carbs, fat, serving in g, regions (1 = German-speaking,
2 = English-speaking, 3 = both), serving label as printed on the pack. Sorted by barcode, zstd level 19 (8 MiB
window), decoded in the app by the bundled zstd library.

Source: Open Food Facts, https://world.openfoodfacts.org/data — database
under the Open Database License (ODbL), contents under the Database Contents
License. The extract is a derivative database and is offered under the ODbL.

    python3 scripts/build_off_asset.py                       # downloads the export (~1.3 GB)
    python3 scripts/build_off_asset.py --csv products.csv.gz
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
ASSET = Path(__file__).resolve().parent.parent / "androidApp/src/main/assets/fuel/off_products.tsv.zst"
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--csv", help="local copy of the export (.csv or .csv.gz); default downloads it")
    parser.add_argument("--out", help="write the extract here instead of the app asset (for a Blossom update, "
                                      "see cruxcoach-blossom-sync/food_products_blossom_upload.py)")
    args = parser.parse_args()

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
    data = ("\n".join(header + lines) + "\n").encode("utf-8")
    asset = Path(args.out).resolve() if args.out else ASSET
    asset.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["zstd", "-q", "-f", "-19", "-o", str(asset)], input=data, check=True)
    if not args.out:
        VERSION_FILE.write_text(f"{today} {len(lines)}\n", encoding="utf-8")
    print(f"wrote {len(lines)} products {per_region} ({len(data) / 1e6:.1f} MB raw, "
          f"{asset.stat().st_size / 1e6:.1f} MB zstd) to {asset}")


if __name__ == "__main__":
    if sys.version_info < (3, 9):
        sys.exit("Python 3.9+ required")
    main()
