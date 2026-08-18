# Bundled Board Layout Images

This folder bundles board-layout imagery for both Kilter and MoonBoard.
Kilter images are described first; the **MoonBoard board images** section
at the end covers `moonboard_*.webp` + `moonboard_*.json` and has a
separately documented, mixed origin.

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
| `mini_moonboard_2020.webp` + `mini_moonboard_2020.json` | Mini MoonBoard 2020 |
| `moonboard_2024.webp` + `moonboard_2024.json` | MoonBoard 2024 |
| `moonboard_2010_base.png` + one hold layer + `moonboard_2010.json` | MoonBoard 2010 |
| `mini_moonboard_2025_base.png` + four hold layers + `mini_moonboard_2025.json` | Mini MoonBoard 2025 |

`moonboard_<variant>.webp` is the board image; `moonboard_<variant>.json`
is a per-hold coordinate map. They are decoded together on-device by
`MoonBoardAssetCache` (see
`androidApp/src/main/java/com/cruxcoach/android/ui/board/MoonBoardAsset.kt`)
and consumed by `MoonBoardVisualization`.

MoonBoard 2010 and Mini MoonBoard 2025 retain the official app's layered
representation: one board plate plus the transparent hold-set images that make
up the complete configuration. CruxCoach always draws all listed layers; they
are implementation assets, not user-selectable hold sets. If any required
image fails to decode, the renderer falls back to the correctly sized
procedural grid.

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

## Grid and BLE-map provenance

Moon's official LED installation guide starts the active chain at A1 and
routes it up and down the columns in a zig-zag. It specifies 198 active
positions for a standard board (200-light chain, two spares) and 132 for a
Mini (134-light chain, two spares). `MoonBoardFrameEncoder` implements this
same A1-first serpentine mapping with a per-variant height of 18 or 12 rows.

Source: <https://moonclimbing.com/media/moonboard-pdf/NewMB_LED_Instructions_may2024.pdf>

The mapping is documentation- and catalogue-validated. A physical integration
run against both configurations remains a release/hardware check. Current
CruxCoach BLE transport supports Nordic UART controllers; an original legacy
RedBear controller must be upgraded to a supported controller before use.

## Origin — MoonBoard

The WebP images for the 2016, Masters 2017, Masters 2019, Mini 2020 and 2024
configurations are CruxCoach-created renders. The PNG plate/layer bundles for
MoonBoard 2010 and Mini MoonBoard 2025 are exceptions: they were extracted
unchanged from the official MoonBoard Android app (`1.3.56`) because those
complete configurations were not present in the existing asset pipeline.

CruxCoach holds the rights to its own image files, but not to the underlying
board designs or the official 2010/2025 PNGs. The MoonBoard product, hardware
layouts, official imagery and MoonBoard name are the property of **Moon
Climbing Ltd**. The assets are bundled solely as referential material needed
to identify and operate the corresponding board offline. CruxCoach is not
affiliated with, endorsed by, or officially connected to Moon Climbing Ltd.

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

1. Drop the base image, optional fixed overlay layers, and coordinate JSON here.
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
Android app (the `com.auroraclimbing.*` family): the `product_sizes`
background plate alpha-composited with every `product_sizes_layouts_sets`
hold-set image for that (size, layout) — the same layering the official app
renders — then re-encoded as WebP to reduce APK size. They are **not**
CruxCoach's original work. The raw app bundles are never stored in this
repository; only the processed WebP files are committed.

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

The assets are produced by an out-of-repo pipeline
(`~/aurora-re/composite_board_images.py`):

1. Obtain the board's official app and read its bundled `db.sqlite3`.
2. For each `is_listed = 1` `product_size` and each of its layouts, alpha-stack
   the `product_sizes` background plate + **every** `product_sizes_layouts_sets`
   image for that (size, layout) — the same layering the official app does — so
   all hold sprites show, not just one set's. (Stacking only the base set left
   the board sparse with big gaps.)
3. Re-encode as WebP (quality ~80, capped at 1080px wide) and drop it at
   `board_images/<brand>/board_<product_size_id>.webp`, or
   `board_<size>_<layout>.webp` when a size carries more than one layout
   (Tension TB2 Mirror/Spray, Decoy layout 1/2). For multi-layout sizes the
   densest layout's composite is also written as the size-only fallback.

The renderer (`boardImageCandidatePaths`) tries the layout-specific path first
(the active layout comes from the size's `boardImages` set list), then the
size-only path, then falls back to a placements-only view if neither asset is
present. So no code change is needed for a new size, and a board not yet
regenerated keeps working off its old single-set image.
