#!/usr/bin/env python3
"""Builds androidApp/src/main/assets/fuel/bls_4_0_macros.tsv from BLS 4.0.

Source: Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS),
Version 4.0 — Deutsche Nährstoffdatenbank. Karlsruhe.
DOI: 10.25826/Data20251217-134202-0, licence CC BY 4.0.
Download: https://www.blsdb.de → "Download BLS-Daten" → BLS_4_0_2025_DE.zip.

Energy (kcal), protein, fat and available carbohydrate per 100 g, plus iron,
calcium and vitamin D for the weekly watch items (empty where BLS has no
value). Requires openpyxl.

    python3 scripts/build_bls_asset.py BLS_4_0_2025_DE.zip
"""

from __future__ import annotations

import hashlib
import io
import sys
import zipfile
from pathlib import Path

ASSET = Path(__file__).resolve().parent.parent / "androidApp/src/main/assets/fuel/bls_4_0_macros.tsv"
DATA_FILE = "BLS_4_0_Daten_2025_DE.xlsx"
COLUMNS = {
    "kcal": "ENERCC Energie (Kilokalorien) [kcal/100g]",
    "protein": "PROT625 Protein (Nx6,25) [g/100g]",
    "fat": "FAT Fett [g/100g]",
    "carbs": "CHO Kohlenhydrate, verfügbar [g/100g]",
}
MICROS = {
    "iron": "FE Eisen [mg/100g]",
    "calcium": "CA Calcium [mg/100g]",
    "vitamin_d": "VITD Vitamin D [µg/100g]",
}


def number(value: object) -> str:
    # Traces ("TR"), values below the limit of detection or quantification
    # ("<LOD", "<LOQ") and "-" count as zero for a food log.
    if isinstance(value, str) and (value.strip().startswith("<") or value.strip() in ("TR", "-")):
        return "0"
    text = f"{float(value):.2f}".rstrip("0").rstrip(".")
    return text or "0"


def micro(value: object) -> str:
    # Unlike the macros, a missing micronutrient stays unknown instead of zero.
    if value is None or (isinstance(value, str) and value.strip() in ("", "-")):
        return ""
    return number(value)


def clean(text: object) -> str:
    return " ".join(str(text).replace("\t", " ").split())


def main(zip_path: str) -> None:
    import openpyxl  # imported late so the CI unittest discovery never needs it

    raw = Path(zip_path).read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        name = next(n for n in archive.namelist() if n.endswith(DATA_FILE))
        workbook = openpyxl.load_workbook(io.BytesIO(archive.read(name)), read_only=True)
    rows = workbook.worksheets[0].iter_rows(values_only=True)
    header = next(rows)
    index = {key: header.index(column) for key, column in {**COLUMNS, **MICROS}.items()}

    lines = [
        "# Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS), Version 4.0 — Deutsche Nährstoffdatenbank. Karlsruhe.",
        "# DOI: 10.25826/Data20251217-134202-0 · licence CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)",
        "# Extract by CruxCoach (scripts/build_bls_asset.py): energy, protein, fat, available carbohydrate, iron, calcium, vitamin D per 100 g edible portion.",
        f"# Source archive sha256: {hashlib.sha256(raw).hexdigest()}",
        "# code\tname_de\tname_en\tkcal\tprotein_g\tfat_g\tcarbs_g\tiron_mg\tcalcium_mg\tvitamin_d_ug",
    ]
    count = 0
    for row in rows:
        if not row[0]:
            continue
        values = [row[index[key]] for key in ("kcal", "protein", "fat", "carbs")]
        if any(v is None for v in values):
            continue
        micros = [micro(row[index[key]]) for key in MICROS]
        lines.append("\t".join([clean(row[0]), clean(row[1]), clean(row[2]), *(number(v) for v in values), *micros]))
        count += 1
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {count} foods to {ASSET}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
