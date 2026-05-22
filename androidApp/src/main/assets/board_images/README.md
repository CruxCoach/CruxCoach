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

`moonboard_<variant>.webp` is the board image; `moonboard_<variant>.json`
is a per-hold coordinate map. They are decoded together on-device by
`MoonBoardAssetCache` (see
`androidApp/src/main/java/com/cruxcoach/android/ui/board/MoonBoardAsset.kt`)
and consumed by `MoonBoardVisualization`.

Variants without a bundled image (Masters 2017 / 2019) fall back to the
procedural 11x18 grid.

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

The MoonBoard board image is **CruxCoach's own work** — created in-house,
full rights held by the project. It is **not** extracted from the
official MoonBoard app's asset bundle and shares none of its pixels.
This deliberately avoids the third-party-asset exposure that the Kilter
images carry (see *Origin* above) and resolves FEAT-027 §10's
"hold image rendering" open question in favour of an original asset.

The MoonBoard product and its hardware layouts are the property of
**Moon Climbing Ltd**. CruxCoach is not affiliated with, endorsed by, or
officially connected to Moon Climbing Ltd.

## Updating — MoonBoard

The image + coordinate map are generated by an out-of-repo asset
pipeline (board photo/render → perspective fit → bolt-hole lattice fit →
per-hold centroid detection → `moonboard_<variant>.webp` + `.json`).
When a new variant's image is produced:

1. Drop `moonboard_<variant>.webp` + `moonboard_<variant>.json` here.
2. Map the variant to its asset base name in `assetBaseName()` in
   `MoonBoardAsset.kt`.
