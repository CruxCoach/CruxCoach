---
status: backlog
---
# Feature Spec: Multi-Heatmap per Board (backlog)

> **Status:** Backlog — captured 2026-05-14. No release target.
> The Logbook stats sheet currently aggregates *every* logbook
> entry onto **one** heatmap canvas keyed by the user's currently-
> configured board (Settings → Board). For users with a mixed
> logbook (Aurora-imported climbs from a different physical
> board, community climbs across multiple Kilter SKUs, own
> climbs from before they switched their primary board), the
> aggregate heatmap mis-positions every cross-board hold —
> placement IDs are layout-and-size-aware, so re-projecting a
> Homewall placement onto an Original 12×12 canvas (or
> vice-versa) lands holds in the wrong cells. This spec splits
> the heatmap into **one canvas per physical board variant**
> the user actually has climbs on.
>
> **Depends on:**
> - The per-climb board picker shipped in v0.1.4 (the
>   `canRenderClimbOnSize` + `getProductSizeForClimbRender`
>   pair on `BoardRepository`). The same edge-containment
>   logic groups logbook entries by source board.
> - The bundled board-image asset set (one WebP per
>   `product_size_id` under `androidApp/src/main/assets/
>   board_images/`). No new images required as long as we
>   restrict the per-board rendering to bundled sizes; an
>   unbundled-source group falls back to a "[size N — no
>   bundled image]" placeholder card with the count + grade
>   pyramid only.
>
> **Relates to:**
> - FEAT-005 (Aurora JSON Import) — the dominant source of
>   cross-board logbook entries; without this spec the v0.1.4
>   Aurora-import release ships with a known-confusing
>   stats-sheet visual.
> - FEAT-008 (Kilter Own-Climb Import + Backup Extension) —
>   any user with own climbs from a different physical board
>   than their current setting hits the same aggregation bug.
> - FEAT-016 (Kilter Homewall Support) — Homewall-vs-Original
>   is the most common cross-board mix; this spec is what
>   makes the v0.1.7 Homewall sells without a stats-sheet
>   regression.

---

## 1. Overview

`BoardLogbookViewModel.preloadStats` walks every ascent + bid
in the secure DB, parses each climb's `frames` placement
list, and counts hits per `placement_id`. Those counts get
fed into a single `KilterBoardVisualization` instance with
`heatmapMode = HeatmapMode.PERSONAL` and the user's preferred
`(productSizeId, layoutId)` pair as the canvas. The
visualization translates each placement's `(x, y)` against
the canvas's `BoardSize.edge_*`, then draws a coloured circle
at that pixel.

The bug: a placement only has one `(x, y)` if its source
layout matches the canvas's layout. A placement from a
*different* layout might have the same numeric ID but mean a
different physical hole — projecting it onto the wrong
canvas paints the heatmap on a hole that the user has never
touched.

This spec fixes the visual semantics by **bucketing logbook
entries by physical source board** before computing the
heatmap, then rendering one canvas per bucket. Each canvas
is honest: it shows hold popularity *for that board*, with no
cross-board aliasing.

### 1.1 Goals

- The Logbook stats sheet shows **N heatmap cards** (one
  per distinct source board the user has logged on), each
  card carrying:
  - The board's WebP image rendered with that board's edges.
  - The hold heatmap computed from *only* the climbs whose
    source board (per `getProductSizeForClimbRender`) maps
    to this card's `product_size_id`.
  - A small caption: `"Homewall 10×12 (with kickboard) · N climbs"`.
- Cards are sorted by climb-count descending so the user's
  primary board lands first; a "Show all boards" expander
  hides the tail when the user has 4+ boards.
- Climbs without recorded edges (= falls through the
  `c.edge_left IS NOT NULL` guard) are grouped into an
  explicit "Unbekanntes Board" bucket so they're surfaced
  rather than silently rendered against the wrong canvas.
- Aggregate stats *outside* the heatmap (grade pyramid,
  sends-over-time chart, distribution histogram) keep
  treating the logbook as one set — those visualisations
  are board-agnostic and grouping them by board would
  bury the real signal (the user is interested in their
  total send count, not their send count per board variant).
- Existing tests for the heatmap path keep passing — adding
  a per-board grouping is a presentation change, not a
  domain-model change.

### 1.2 Non-Goals

- **Re-projecting cross-board placements** onto a target
  board (e.g. "interpret a Homewall placement as if it
  were on Original 12×12"). The placement ID space is
  not portable across layouts in any meaningful way —
  even if a numeric ID happens to match, the physical
  hole differs. Re-projection would lie about where the
  user actually climbed.
- **Per-board grade pyramid / time chart**. The aggregate
  view answers the user's primary question ("am I getting
  stronger?") and splitting it across boards introduces
  small-sample noise per facet. If a user wants per-board
  breakdowns, the existing time-interval picker + a
  later "filter by board" dropdown (separate spec)
  covers that.
- **Editing the source board for a logbook entry**. If
  the user mis-tagged a climb's board at log time, the
  fix is in the per-climb edit flow (already covered by
  the existing Climb Detail edit) — this spec doesn't
  add a "move climb to different board" UI to the stats
  sheet.
- **Unbundled-board image rendering**. Sizes outside
  `BUNDLED_BOARD_SIZES` (rare KO/Spray-only SKUs the
  current asset set doesn't carry) fall back to a
  count-only card; downloading + caching extra board
  images is a separate APK-size discussion.

## 2. UX

```
┌─ Statistik ─────────────────────────────────┐
│ Zeitraum: [Letzte 30 Tage ▾]                │
│                                              │
│ [Pyramide][Sends][Verteilung]                │
│                                              │
│ ── Heatmap pro Board ──                      │
│ ┌──────────────────────────┐                 │
│ │ Homewall 10×12  ·  87 Climbs │             │
│ │ [board image with heatmap]   │             │
│ └──────────────────────────┘                 │
│ ┌──────────────────────────┐                 │
│ │ Original 12×12  ·  32 Climbs │             │
│ │ [board image with heatmap]   │             │
│ └──────────────────────────┘                 │
│ ▸ Weitere Boards (2)                         │
└──────────────────────────────────────────────┘
```

Card order: descending by climb count. Tap-expand for the
"Weitere Boards" tail. Each card uses the same
`KilterBoardVisualization` Composable as today — the
per-card heatmap data is the only new wiring.

## 3. Implementation Sketch

### 3.1 Bucketing pass

Add to `BoardLogbookViewModel.preloadStats`:

```kotlin
// Group light ascents by their source-board product_size_id.
// `getProductSizeForClimbRender` already does the edge-containment
// lookup; cache one (uuid → sizeId?) map for the full logbook.
val sizeByUuid: Map<String, Int?> = withContext(Dispatchers.IO) {
    all.associate { it.climbUuid to
        boardRepository.getProductSizeForClimbRender(it.climbUuid)
    }
}
val perBoardHeatmaps: Map<Int?, Map<Int, Float>> =
    all.groupBy { sizeByUuid[it.climbUuid] }
        .mapValues { (_, ascents) ->
            HoldHeatmapComputer.compute(ascents.flatMap {
                BoardClimbParser.parseFrames(it.climbFrames)
            })
        }
```

Persist `perBoardHeatmaps` (and a parallel
`perBoardCounts: Map<Int?, Int>`) on the state.

### 3.2 Card list

Replace the single heatmap slot in `BoardStatsSheet` with a
`Column` of cards. Iterate the `perBoardHeatmaps` map sorted
by count descending, mapping each entry to a card composable
that:

- Loads the bundled WebP for that `productSizeId`
  (`BoardImageCache.getOrDecode`).
- Renders the heatmap data through the existing
  `KilterBoardVisualization(heatmapMode = HeatmapMode.PERSONAL,
   heatmapData = perBoardHeatmaps[id])` path.
- Caption format: `"<size.name> · ${count} ${if (count == 1) "Climb" else "Climbs"}"`.

The "null" key (climbs without a derivable source size)
becomes the "Unbekanntes Board" card and renders with the
user's preferred board image as background — explicitly so
the user knows it's a placeholder.

### 3.3 Performance

`getProductSizeForClimbRender` is a single SQLite SELECT per
climb; `preloadStats` already iterates the full logbook
(potentially thousands of rows). Add a single batch query
returning `(uuid → sizeId)` for the user's logbook to keep
the call count constant — sketch:

```sql
-- Per-uuid resolved render-size for a list of logbook climbs.
-- Same containment + EXISTS guards as getProductSizeForClimbRender,
-- but in one round-trip via a temp table or IN-clause batch.
getRenderSizeForLogbook:
SELECT c.uuid, MIN(ps.id) AS resolved_size_id
FROM climbs c
LEFT JOIN product_sizes ps ON ps.edge_left   <= c.edge_left
                          AND ps.edge_right  >= c.edge_right
                          AND ps.edge_bottom <= c.edge_bottom
                          AND ps.edge_top    >= c.edge_top
                          AND EXISTS (SELECT 1 FROM board_images bi
                                       WHERE bi.product_size_id = ps.id
                                         AND bi.layout_id       = c.layout_id)
WHERE c.uuid IN :uuids
GROUP BY c.uuid;
```

(SQLDelight needs the `IN`-clause variant; otherwise
`getProductSizeForClimbRender` per-climb is acceptable for
the typical logbook size — Logbook page is paginated to 50
entries, full preload at ≤2k entries is < 50 ms locally.)

### 3.4 Empty + edge cases

- **0 boards in logbook** (fresh install): heatmap section
  hides entirely — same as the current behaviour for an
  empty logbook.
- **All climbs from one board**: single card, no expander.
  Visually identical to the current single-canvas
  rendering for users who only ever climbed on one board.
- **All climbs lack edges**: single "Unbekanntes Board"
  card. Same effective rendering as today (just with an
  honest caption).

## 4. Risks

- **APK / asset coverage.** A user with logbook entries
  from a `product_size_id` we don't bundle a WebP for
  would get a placeholder card per such size. Mitigation:
  the count-only fallback is honest and the bundled set
  already covers Original + Homewall; rare KO/Spray-only
  SKUs are an out-of-scope concern.
- **Heatmap card sprawl.** A user with 5+ source boards
  ends up scrolling. The "Weitere Boards" expander caps
  the visible default at 3; the typical user has 1–2.
- **Stats consistency.** The grade pyramid + send chart
  stay aggregated; some users may expect the heatmap to
  match. The card caption (`N climbs`) makes the per-card
  scope explicit so the difference doesn't read as a bug.

## 5. Success Criteria

- Stats sheet shows one heatmap card per distinct source
  board in the user's logbook, count-sorted, with the
  bundled board image + the per-board heatmap.
- Aurora-imported Homewall climbs no longer paint hold
  hits on the user's currently-configured Original board
  (the v0.1.4 visual bug that motivated this spec).
- Aggregate non-heatmap stats (pyramid, time, distribution)
  are unchanged in shape and numbers.
- No new permission, no new asset, no new schema migration.
