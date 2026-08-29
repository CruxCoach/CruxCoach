#!/usr/bin/env python3
"""Regenerate the FEAT-049 hold-set figures.

One image per MoonBoard variant: a small-multiple grid with one panel per
selectable hold set — i.e. one panel per checkbox the picker will offer. The
set's own holds are ringed; the board art underneath keeps every other hold
visible, so the partition reads directly.

Each panel is a SINGLE-SERIES figure on purpose. Identity comes from the panel
title, never from colour, so no categorical palette is involved and the figures
survive any colour-vision deficiency. Do not "improve" this by colouring the
sets differently in one combined view: with up to six sets interleaved across
the board every pair is adjacent, and no six-hue palette clears the
colour-blindness separation floor in that arrangement.

Inputs
  - CruxCoach's own board art + coordinate JSON, in-repo:
    androidApp/src/main/assets/board_images/<variant>.{json,webp,png}
  - The cell map from a BoardSesh checkout (Apache-2.0):
    packages/shared/board-config/src/{generated/moonboard-cell-sets.ts,
    moonboard-config.ts}

Usage
  BOARDSESH_SRC=/path/to/boardsesh/packages/shared/board-config/src \
    python3 docs/specs/0.2.2/img/render-hold-sets.py

Requires Pillow. Run from the repository root.
"""
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

ASSETS = os.environ.get(
    "BOARD_IMAGES", "androidApp/src/main/assets/board_images")
BOARDSESH = os.environ.get("BOARDSESH_SRC")
OUT = os.environ.get("FEAT049_OUT", "docs/specs/0.2.2/img")

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# Light chart surface + ink; accent is categorical slot 1. Only ever one hue
# on screen at a time (single series per panel).
SURFACE = (252, 252, 251)
INK = (11, 11, 11)
INK_2 = (82, 81, 78)
ACCENT = (42, 120, 214)

PANEL_W = 300
COLS = 3
PAD = 18
CAP_H = 40

VARIANTS = [
    ("moonboard_2016", 2, "MoonBoard 2016"),
    ("moonboard_2017", 4, "MoonBoard Masters 2017"),
    ("moonboard_2019", 5, "MoonBoard Masters 2019"),
    ("moonboard_2024", 3, "MoonBoard 2024"),
    ("mini_moonboard_2020", 6, "Mini MoonBoard 2020"),
    ("mini_moonboard_2025", 7, "Mini MoonBoard 2025"),
    ("moonboard_2010", 1, "MoonBoard 2010"),
]

LAYOUT_KEY = {
    1: "moonboard-2010", 2: "moonboard-2016", 3: "moonboard-2024",
    4: "moonboard-masters-2017", 5: "moonboard-masters-2019",
    6: "mini-moonboard-2020", 7: "mini-moonboard-2025",
}


def cell_sets():
    """layoutId -> {holdId: setId}, parsed from the generated TS map."""
    src = open(f"{BOARDSESH}/generated/moonboard-cell-sets.ts").read()
    body = src[src.index("{", src.index("MOONBOARD_CELL_SETS")):]
    out, cur = {}, None
    for line in body.splitlines():
        m = re.match(r"\s*(\d+):\s*\{\s*$", line)
        if m:
            cur = int(m.group(1))
            out[cur] = {}
            continue
        m = re.match(r"\s*(\d+):\s*(\d+),", line)
        if m and cur is not None:
            out[cur][int(m.group(1))] = int(m.group(2))
    return out


def set_names():
    """layoutKey -> {setId: display name}."""
    src = open(f"{BOARDSESH}/moonboard-config.ts").read()
    body = src[src.index("MOONBOARD_SETS"):]
    out, cur = {}, None
    for line in body.splitlines():
        m = re.match(r"\s*'([a-z0-9-]+)':", line)
        if m:
            cur = m.group(1)
        for sid, name in re.findall(
                r"\{\s*id:\s*(\d+),\s*name:\s*'([^']+)'", line):
            out.setdefault(cur, {})[int(sid)] = name
    return out


def font(path, size):
    return ImageFont.truetype(path, size)


def board_art(meta, assets):
    """The complete board: base plus every hold-set overlay.

    MoonBoard 2010 and Mini 2025 ship as a bare base image with one
    transparent PNG per hold set, listed in the JSON's `overlays`. Drawing
    only `image` for those two yields an empty board — rings floating over
    plain plywood — while the other five have their holds baked into a single
    composite. Flatten everything so all seven look like the same feature.
    """
    img = Image.open(f"{assets}/{meta['image']}").convert("RGBA")
    for name in meta.get("overlays", []):
        layer = Image.open(f"{assets}/{name}").convert("RGBA")
        if layer.size != img.size:
            layer = layer.resize(img.size, Image.LANCZOS)
        img = Image.alpha_composite(img, layer)
    return img


def panel(board, holds, mapping, set_id, w):
    h = int(w / board.width * board.height)
    img = board.resize((w, h), Image.LANCZOS).convert("RGB")
    # Recede the photo enough that the rings read as foreground, but not so far
    # that the holds stop being identifiable — seeing WHICH holds a set covers
    # is the whole point.
    img = ImageEnhance.Color(img).enhance(0.6)
    img = Image.blend(img, Image.new("RGB", img.size, SURFACE), 0.22)
    img = ImageEnhance.Contrast(img).enhance(1.12)

    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = max(4, int(w * 0.030))

    for hold_id, sid in mapping.items():
        if sid != set_id:
            continue
        pos = holds.get(hold_id)
        if pos is None:
            continue
        cx, cy = pos[0] * w, pos[1] * h
        d.ellipse([cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2],
                  fill=(255, 255, 255, 210))
        d.ellipse([cx - r, cy - r, cx + r, cy + r],
                  fill=ACCENT + (70,), outline=ACCENT + (255,),
                  width=max(2, r // 4))

    return Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")


def render(variant, layout_id, title, maps, names):
    meta = json.load(open(f"{ASSETS}/{variant}.json"))
    holds = {h["holdId"]: (h["x"], h["y"]) for h in meta["holds"]}
    board = board_art(meta, ASSETS)
    mapping = maps[layout_id]
    nm = names[LAYOUT_KEY[layout_id]]

    # Bit order == set ids ascending — exactly HoldSetMask.excludedMask's rule,
    # so the captions double as the authority for the bit table in §3.3.
    sets = sorted(set(mapping.values()))
    counts = {s: sum(1 for v in mapping.values() if v == s) for s in sets}

    panels = [panel(board, holds, mapping, s, PANEL_W) for s in sets]
    ph = panels[0].height
    cols = min(COLS, len(sets))
    rows = (len(sets) + cols - 1) // cols

    head = 62
    W = PAD + cols * (PANEL_W + PAD)
    H = head + PAD + rows * (ph + CAP_H + PAD)
    canvas = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(canvas)

    d.text((PAD, 16), title, font=font(FONT_B, 21), fill=INK)
    d.text((PAD, 42), f"layout_id {layout_id} · {len(sets)} hold sets · "
                      f"{len(mapping)} holds · bit order = set id ascending",
           font=font(FONT, 13), fill=INK_2)

    for i, (s, p) in enumerate(zip(sets, panels)):
        cx = PAD + (i % cols) * (PANEL_W + PAD)
        cy = head + PAD + (i // cols) * (ph + CAP_H + PAD)
        canvas.paste(p, (cx, cy))
        d.rectangle([cx, cy, cx + PANEL_W - 1, cy + ph - 1],
                    outline=(226, 226, 221), width=1)
        d.text((cx, cy + ph + 8), f"bit {i} · {nm.get(s, f'set {s}')}",
               font=font(FONT_B, 14), fill=INK)
        d.text((cx, cy + ph + 25), f"set id {s} · {counts[s]} holds",
               font=font(FONT, 12), fill=INK_2)

    os.makedirs(OUT, exist_ok=True)
    path = f"{OUT}/feat-049-{variant}.png"
    canvas.save(path, optimize=True)
    print(f"{path}  {W}x{H}  {os.path.getsize(path) // 1024} KB  sets={sets}")


if __name__ == "__main__":
    if not BOARDSESH:
        sys.exit("set BOARDSESH_SRC to <boardsesh>/packages/shared/board-config/src")
    maps, names = cell_sets(), set_names()
    for v, lid, t in VARIANTS:
        render(v, lid, t, maps, names)
