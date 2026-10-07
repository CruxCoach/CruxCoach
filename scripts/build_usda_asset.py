#!/usr/bin/env python3
"""Builds androidApp/src/main/assets/fuel/usda_sr_legacy.tsv from USDA FoodData Central.

Source: U.S. Department of Agriculture, Agricultural Research Service.
FoodData Central, SR Legacy (April 2018), https://fdc.nal.usda.gov/.
Public domain, published under CC0 1.0; FoodData Central asks to be named
as the source.

Download: https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip

Same layout as the BLS extract so the app reads both with one parser:
code (FDC id), German name (empty), English name, kcal, protein, fat,
available carbohydrate (by difference minus fibre, as in BLS and on EU labels),
then iron mg, calcium mg, vitamin D µg (empty when not analysed) and household
portions as "cup=81|tbsp=5.1" grams per one unit.

    python3 -I scripts/build_usda_asset.py FoodData_Central_sr_legacy_food_csv_2018-04.zip
"""

from __future__ import annotations

import csv
import hashlib
import io
import sys
import zipfile
from pathlib import Path

ASSET = Path(__file__).resolve().parent.parent / "androidApp/src/main/assets/fuel/usda_sr_legacy.tsv"

KCAL, PROTEIN, FAT, CARBS, FIBRE = "1008", "1003", "1004", "1005", "1079"
IRON, CALCIUM, VITAMIN_D = "1089", "1087", "1114"
WANTED = {KCAL, PROTEIN, FAT, CARBS, FIBRE, IRON, CALCIUM, VITAMIN_D}
# Weights already covered by the app's own units.
SKIP_PORTIONS = ("oz", "lb", "g ", "gram", "fl oz")
MAX_PORTIONS = 5


def fmt(value: float) -> str:
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return text or "0"


def rows(archive: zipfile.ZipFile, name: str):
    member = next(n for n in archive.namelist() if n.endswith("/" + name) or n == name)
    with archive.open(member) as raw:
        yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8"))


def clean(text: str, limit: int) -> str:
    return " ".join(text.replace("\t", " ").replace("|", "/").replace("=", "-").split())[:limit]


def main(zip_path: str) -> None:
    raw = Path(zip_path).read_bytes()
    archive = zipfile.ZipFile(io.BytesIO(raw))

    names = {r["fdc_id"]: r["description"] for r in rows(archive, "food.csv") if r["data_type"] == "sr_legacy_food"}
    nutrients: dict[str, dict[str, float]] = {}
    for r in rows(archive, "food_nutrient.csv"):
        if r["nutrient_id"] in WANTED and r["fdc_id"] in names and r["amount"] != "":
            nutrients.setdefault(r["fdc_id"], {})[r["nutrient_id"]] = float(r["amount"])

    portions: dict[str, list[tuple[str, float]]] = {}
    for r in rows(archive, "food_portion.csv"):
        fdc = r["fdc_id"]
        if fdc not in names:
            continue
        label = clean(r["modifier"] or r["portion_description"], 30)
        try:
            amount, grams = float(r["amount"] or 1), float(r["gram_weight"])
        except ValueError:
            continue
        if not label or amount <= 0 or grams <= 0 or label.lower().startswith(SKIP_PORTIONS):
            continue
        found = portions.setdefault(fdc, [])
        if len(found) < MAX_PORTIONS and all(existing != label for existing, _ in found):
            found.append((label, grams / amount))

    lines = [
        "# U.S. Department of Agriculture, Agricultural Research Service. FoodData Central: SR Legacy (2018-04). https://fdc.nal.usda.gov/",
        "# Public domain (CC0 1.0). Extract by CruxCoach (scripts/build_usda_asset.py): per 100 g, available carbohydrate = by difference - fibre.",
        f"# Source archive sha256: {hashlib.sha256(raw).hexdigest()}",
        "# code\tname_de\tname_en\tkcal\tprotein_g\tfat_g\tcarbs_g\tiron_mg\tcalcium_mg\tvitamin_d_ug\tportions",
    ]
    kept = 0
    for fdc in sorted(names, key=int):
        n = nutrients.get(fdc, {})
        if KCAL not in n or PROTEIN not in n or FAT not in n:
            continue
        carbs = max(0.0, n.get(CARBS, 0.0) - n.get(FIBRE, 0.0))
        micro = [fmt(n[k]) if k in n else "" for k in (IRON, CALCIUM, VITAMIN_D)]
        portion_text = "|".join(f"{label}={fmt(g)}" for label, g in portions.get(fdc, []))
        lines.append("\t".join([fdc, "", clean(names[fdc], 160), fmt(n[KCAL]), fmt(n[PROTEIN]), fmt(n[FAT]), fmt(carbs), *micro, portion_text]))
        kept += 1
    ASSET.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {kept} of {len(names)} foods to {ASSET} ({ASSET.stat().st_size / 1e6:.2f} MB)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
