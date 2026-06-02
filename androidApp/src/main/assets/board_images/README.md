# Bundled Board Layout Images

This folder bundles board-layout imagery for both Kilter and MoonBoard.
Kilter images are described first; the **MoonBoard board images** section
at the end covers `moonboard_*.webp` + `moonboard_*.json` and has a
different (CruxCoach-original) origin.

## Kilter Board Layout Images

These WebP images show the physical Kilter Board layouts for the
`product_size` IDs listed below. They are decoded on-device by
`BoardImageCache` (see
`androidApp/src/main/java/com/cruxcoach/android/ui/board/KilterBoardVisualization.kt`)
so CruxCoach can render a recognisable offline view of the board behind the
app's hold overlay.

**Kilter Board Original** (product_id=1):

| File | `product_size_id` |
|------|---:|
| `board_7.webp`  |  7 |
| `board_8.webp`  |  8 |
| `board_10.webp` | 10 |
| `board_14.webp` | 14 |
| `board_27.webp` | 27 |
| `board_28.webp` | 28 |

**Kilter Board Homewall** (product_id=7):

| File | `product_size_id` |
|------|---:|
| `board_17.webp` | 17 |
| `board_18.webp` | 18 |
| `board_19.webp` | 19 |
| `board_21.webp` | 21 |
| `board_22.webp` | 22 |
| `board_23.webp` | 23 |
| `board_24.webp` | 24 |
| `board_25.webp` | 25 |
| `board_26.webp` | 26 |
| `board_29.webp` | 29 |

## Origin

Each source image was extracted from the Kilter Board app's own data files
(`image_filename` column in the `product_sizes` table) and re-encoded as
WebP to reduce APK size. They are **not** CruxCoach's original work.

## Rights holder

The Kilter Board product, its hardware layouts, and the official layout
photography are the property of **Kilter Grips, LLC** (and previously
**Aurora Climbing**, who operated the Kilter Board app until March 2026).
CruxCoach is not affiliated with, endorsed by, sponsored by, or officially
connected to Kilter Grips, LLC or Aurora Climbing.

## Why they are bundled

CruxCoach is a companion app for the Kilter Board. Showing a recognisable
visual of the user's physical board behind the highlighted holds is core to
the app's function — without it, the hold overlay has no spatial reference.
Bundling (rather than fetching at runtime) keeps the app fully usable
offline, which is the expected UX when climbing in gyms with poor
connectivity.

The images are used:

- solely as referential / descriptive material to identify the corresponding
  Kilter Board product size, and
- in a non-commercial, open-source context.

This matches § 23(1) No. 3 MarkenG (referential use to indicate intended
purpose) under German law and the analogous nominative / referential fair-use
doctrine elsewhere. See `LEGAL.md` in the repository root for the broader
position on trademarks and interoperability.

## Removal requests

If you represent Kilter Grips, LLC (or any prior rights holder) and would
like any of these images removed or replaced, please contact the maintainer
via the channels listed in `SECURITY.md`, or open an issue on the Codeberg
repository. Removal or replacement will be handled promptly.

## Updating

When a new or renamed Kilter board size is released:

1. Add the new `board_<id>.webp` here (re-encode at comparable quality,
   e.g. `cwebp -q 80 input.png -o board_<id>.webp`).
2. Add the `product_size_id` to `BUNDLED_BOARD_SIZES` in
   `KilterBoardVisualization.kt`.
3. Update the table above.

---

# MoonBoard board images

| Files | MoonBoard variant |
|-------|-------------------|
| `moonboard_2016.webp` + `moonboard_2016.json` | MoonBoard 2016 |
| `moonboard_2017.webp` + `moonboard_2017.json` | MoonBoard Masters 2017 |
| `moonboard_2019.webp` + `moonboard_2019.json` | MoonBoard Masters 2019 |
| `mini_moonboard_2020.json` (coordinate map only) | MoonBoard Mini 2020 |

`moonboard_<variant>.webp` is the board image; `moonboard_<variant>.json`
is a per-hold coordinate map. They are decoded together on-device by
`MoonBoardAssetCache` (see
`androidApp/src/main/java/com/cruxcoach/android/ui/board/MoonBoardAsset.kt`)
and consumed by `MoonBoardVisualization`.

MoonBoard Mini 2020 ships a coordinate map only (no bundled board photo),
so it falls back to the procedural 11x18 grid; if decoding a bundled image
fails for any variant, the renderer also falls back to that grid.

## Coordinate map (`moonboard_<variant>.json`)

Unlike the Kilter renderer — which linearly maps board-unit hold
coordinates onto its image — the MoonBoard board image is **not** a
regularised crop, so hold positions are not interpolable. The JSON gives
every grid position's measured hold centre, normalized 0..1 over the
image:

```json
{
  "variant": "moonboard_2016",
  "image": "moonboard_2016.webp",
  "imageAspect": 0.6382,
  "grid": { "columns": 11, "rows": 18 },
  "holds": [ { "holdId": 1, "x": 0.122, "y": 0.881, "occupied": false }, ... ]
}
```

`holdId` is 1-based, `(row - 1) * 11 + col + 1` (col A=0..K=10, row 1 at
the bottom) — the same id space `MoonBoardFrameEncoder` emits. All 198
positions are present; `occupied: false` entries (no hold in that set)
carry the plain grid node.

## Origin — MoonBoard

The MoonBoard board *image file* bundled here is **CruxCoach's own work** —
created in-house, **not** extracted from the official MoonBoard app's asset
bundle, and sharing none of its pixels. This deliberately avoids the
third-party-asset exposure that the Kilter images carry (see *Origin*
above) and resolves FEAT-027 §10's "hold image rendering" open question in
favour of an original asset.

CruxCoach holds the rights to its own image *file*, but **not** to the
underlying board design it portrays: the MoonBoard product, its hardware
hold layouts, and the MoonBoard name are the property of **Moon Climbing
Ltd**. The image is bundled solely as referential / descriptive material to
identify the user's physical MoonBoard, in a non-commercial, open-source
context — § 23(1) No. 3 MarkenG (referential use to indicate intended
purpose) and the analogous nominative fair-use doctrine elsewhere, the same
basis as the Kilter section above. CruxCoach is not affiliated with,
endorsed by, or officially connected to Moon Climbing Ltd.

## Removal requests — MoonBoard

If you represent Moon Climbing Ltd and would like the MoonBoard imagery
removed or replaced, please contact the maintainer via the channels listed
in `SECURITY.md`, or open an issue on the Codeberg repository. Removal or
replacement will be handled promptly.

## Updating — MoonBoard

The image + coordinate map are generated by an out-of-repo asset
pipeline (board photo/render → perspective fit → bolt-hole lattice fit →
per-hold centroid detection → `moonboard_<variant>.webp` + `.json`).
When a new variant's image is produced:

1. Drop `moonboard_<variant>.webp` + `moonboard_<variant>.json` here.
2. Map the variant to its asset base name in `assetBaseName()` in
   `MoonBoardAsset.kt`.

---

# Aurora-family board images

CruxCoach renders five further Aurora-protocol boards (FEAT-031). Their
background images live in per-brand subfolders:

| Folder | Board | `product_size` ids bundled |
|--------|-------|----------------------------|
| `tension/`     | Tension Board   | 1–9 |
| `grasshopper/` | Grasshopper Board | 2–6 |
| `decoy/`       | Decoy Board     | 1–3 |
| `soill/`       | So iLL Board    | 1–2 |
| `touchstone/`  | Touchstone Board | 1 |

Each file is `board_images/<brand>/board_<product_size_id>.webp`, decoded
on-device by the same `BoardImageCache` as the Kilter images (the cache is
keyed by the full asset path). The per-brand subfolder is required because
`product_size` ids collide across brands — every board numbers its sizes from
1 — while the Kilter set keeps the historical flat `board_<id>.webp` layout.

## Naming — why `product_size_id`, not `image_filename`

The Blossom catalogue chunk ships only the `image_filename` *string* (in
`product_sizes_layouts_sets`); the actual pixels are these bundled assets.
CruxCoach names them by `product_size.id` — not by `image_filename` — because
the renderer draws exactly one background per physical board size and looks it
up by size id, the same convention as the Kilter section above. A missing file
degrades gracefully to a placements-only view; it never crashes or blanks.

## Origin

Each source image was extracted from the corresponding board's own official
Android app (the `com.auroraclimbing.*` family): specifically the
`product_sizes_layouts_sets` image for that size's dominant layout (the layout
most of its climbs use) and base hold set — the standard configuration the
board ships with — then re-encoded as WebP to reduce APK size. They are
**not** CruxCoach's original work. The raw app bundles are never stored in
this repository; only the processed WebP files are committed.

## Rights holders

Each board product, its physical hold layout, the official layout imagery, and
the board name are the property of the respective board maker — **Tension
Climbing**, **Grasshopper**, **Decoy**, **So iLL**, and **Touchstone
Climbing** — and the board apps are published on the **Aurora Climbing** app
platform. CruxCoach is not affiliated with, endorsed by, sponsored by, or
officially connected to any of them.

The images are bundled solely as referential / descriptive material to
identify the user's physical board, in a non-commercial, open-source context —
§ 23(1) No. 3 MarkenG (referential use to indicate intended purpose) and the
analogous nominative / referential fair-use doctrine elsewhere, the same basis
as the Kilter and MoonBoard sections above. See `LEGAL.md`.

## Removal requests — Aurora-family boards

If you represent any of the rights holders above and would like the
corresponding imagery removed or replaced, please contact the maintainer via
the channels listed in `SECURITY.md`, or open an issue on the Codeberg
repository. Removal or replacement (per board) will be handled promptly.

## Updating — Aurora-family boards

The assets are produced by an out-of-repo pipeline:

1. Obtain the board's official app and read its bundled `db.sqlite3`.
2. For each `is_listed = 1` `product_size`, pick the
   `product_sizes_layouts_sets` image for the dominant layout and the base
   (lowest-id) hold set.
3. Re-encode as WebP (quality ~80) and drop it at
   `board_images/<brand>/board_<product_size_id>.webp`.

No code change is needed for a new size — the renderer attempts the
brand-namespaced path for every Aurora-family board and falls back to
placements-only when the asset is absent.
