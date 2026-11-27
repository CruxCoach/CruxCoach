#!/usr/bin/env python3
"""Build the bundled Shiny Wall wall images (assets/board_images/shinywall/).

The official Shiny Wall app draws a Standard wall from one cut-out photo per
hold, placed on its grid; it has no photo of a whole wall. This script does
the same for CruxCoach: for every variant it places each hold's photo at the
hold's position in the bundled geometry and writes one image per product size,
`board_<size id>.webp`, where the board renderer already looks for a brand's
photos. A missing file falls back to the drawn hold dots, so deleting the
folder removes the photos again.

The image covers exactly the variant's board area of the geometry bundle
(build_shinywall_geometry.py: edge_left/right/bottom/top), so the app's hold
markers land on the photographed holds by construction.

Inputs: the geometry JSON (format "cruxcoach-shinywall-geometry-v1", hold
names = the app's hold photo names) and the folder with those hold photos
(`assets/holds/*.png` of the locally unpacked app). Needs Pillow.
Usage: scripts/build_shinywall_images.py GEOMETRY.json HOLDS_DIR [--out DIR]
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_shinywall_geometry import MARGIN, SIZE_BASE, UNIT, VARIANT_SLOTS, _scaled, validate  # noqa: E402

# Output pixels per stored geometry unit (one cell = UNIT units = 60 px).
PX_PER_UNIT = 3
# Width in pixels of a hold photo that spans one cell in the app's assets.
SOURCE_CELL_PX = 75
# Dark, neutral wall behind the light holds; baked in so the photo reads the
# same in light and dark mode. Not a Shiny Wall brand colour.
BACKGROUND = (38, 38, 42)
WEBP_QUALITY = 82


class ImageBuildError(ValueError):
    pass


@dataclass(frozen=True)
class PlacedHold:
    name: str
    center_x: float
    center_y: float


@dataclass(frozen=True)
class VariantImage:
    code: str
    size_id: int
    width: int
    height: int
    holds: list[PlacedHold]


def layout(code: str, variant: dict) -> VariantImage:
    """Pixel size and hold centres of one variant's image.

    Uses the geometry bundle's edges and coordinate scaling, with the origin
    at the image's top left (y grows downwards)."""
    columns = variant["grid"]["columns"]
    present = [h for h in variant["holds"] if h.get("present", True) is not False]
    edge_left, edge_right = -MARGIN, _scaled(columns - 1) + MARGIN
    edge_bottom, edge_top = -MARGIN, max(_scaled(h["y"]) for h in present) + MARGIN
    holds = []
    for hold in sorted(present, key=lambda h: h["index"]):
        name = hold.get("name")
        if not isinstance(name, str) or not name:
            raise ImageBuildError(f"{code}: hold {hold['index']} has no photo name")
        holds.append(PlacedHold(
            name=name,
            center_x=(_scaled(hold["x"]) - edge_left) * PX_PER_UNIT,
            center_y=(edge_top - _scaled(hold["y"])) * PX_PER_UNIT,
        ))
    return VariantImage(
        code=code,
        size_id=SIZE_BASE + VARIANT_SLOTS[code],
        width=(edge_right - edge_left) * PX_PER_UNIT,
        height=(edge_top - edge_bottom) * PX_PER_UNIT,
        holds=holds,
    )


def render(image: VariantImage, holds_dir: Path):
    """Composes one variant's wall from the hold photos (a Pillow image)."""
    from PIL import Image

    canvas = Image.new("RGBA", (image.width, image.height), BACKGROUND + (255,))
    scale = UNIT * PX_PER_UNIT / SOURCE_CELL_PX
    cache: dict[str, Image.Image] = {}
    for hold in image.holds:
        tile = cache.get(hold.name)
        if tile is None:
            path = holds_dir / f"{hold.name}.png"
            if not path.is_file():
                raise ImageBuildError(f"{image.code}: no photo {path.name} for a hold")
            source = Image.open(path).convert("RGBA")
            size = (max(1, round(source.width * scale)), max(1, round(source.height * scale)))
            tile = cache[hold.name] = source.resize(size, Image.LANCZOS)
        canvas.alpha_composite(tile, (
            round(hold.center_x - tile.width / 2),
            round(hold.center_y - tile.height / 2),
        ))
    return canvas.convert("RGB")


def build(doc: dict, holds_dir: Path, out_dir: Path) -> dict[str, tuple[int, int]]:
    """Writes one image per variant; returns the pixel size per variant."""
    variants = validate(doc)
    out_dir.mkdir(parents=True, exist_ok=True)
    sizes = {}
    for code in sorted(variants, key=VARIANT_SLOTS.get):
        image = layout(code, variants[code])
        render(image, holds_dir).save(
            out_dir / f"board_{image.size_id}.webp", "WEBP", quality=WEBP_QUALITY, method=6,
        )
        sizes[code] = (image.width, image.height)
    return sizes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("geometry", type=Path)
    parser.add_argument("holds_dir", type=Path)
    parser.add_argument(
        "--out", type=Path,
        default=Path(__file__).resolve().parent.parent / "androidApp/src/main/assets/board_images/shinywall",
    )
    args = parser.parse_args(argv)
    try:
        sizes = build(json.loads(args.geometry.read_text()), args.holds_dir, args.out)
    except (ImageBuildError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for code, (w, h) in sizes.items():
        print(f"{code}: {w}x{h}")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
