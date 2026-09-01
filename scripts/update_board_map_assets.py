#!/usr/bin/env python3
"""Refresh the in-app offline board-map snapshot from cruxcoach-pages.

The website pipeline is the canonical curator for venue grouping, exclusions,
board corrections, verified links, nearest-city labels and place aliases.  The
Android app bundles its generated artifacts so map search stays useful offline.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pages-repo", type=Path, required=True)
    args = parser.parse_args()

    pages = args.pages_repo.resolve()
    source_dir = pages / "boards" / "data"
    geojson = source_dir / "boards.geojson"
    cities = source_dir / "cities.json"
    meta = source_dir / "boards.meta.json"
    for path in (geojson, cities, meta):
        if not path.is_file():
            raise SystemExit(f"missing Pages map artifact: {path}")

    board_data = json.loads(geojson.read_text(encoding="utf-8"))
    city_data = json.loads(cities.read_text(encoding="utf-8"))
    metadata = json.loads(meta.read_text(encoding="utf-8"))
    features = board_data.get("features")
    places = city_data.get("cities")
    if board_data.get("type") != "FeatureCollection" or not isinstance(features, list):
        raise SystemExit("boards.geojson is not a GeoJSON FeatureCollection")
    if not isinstance(places, list):
        raise SystemExit("cities.json has no cities array")
    if metadata.get("venue_features") != len(features):
        raise SystemExit("boards.meta.json venue count does not match boards.geojson")

    destination = Path(__file__).resolve().parents[1] / "androidApp" / "src" / "main" / "assets" / "board_map"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(geojson, destination / "boards.geojson")
    shutil.copyfile(cities, destination / "cities.json")
    shutil.copyfile(meta, destination / "boards.meta.json")
    print(
        f"Updated Android board map: {len(features)} venues, {len(places)} places "
        f"from {pages}"
    )


if __name__ == "__main__":
    main()
