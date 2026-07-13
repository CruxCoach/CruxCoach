package com.cruxcoach.domain.board

/**
 * The MoonBoard board variants CruxCoach supports in v0.2.0 (FEAT-027).
 *
 * Unlike Kilter — one board offered in several physical SIZES — the
 * MoonBoard family is several DISTINCT boards: each variant has its own
 * hold set, and a problem set for one (variant, angle) is a different
 * climb from "the same holds" on another. The catalogue dump carries
 * them as separate problems, and the importer writes one climb row per
 * (problem, angle) accordingly.
 *
 * v0.2.0 ships all four variants present in the spookykat 2023-01-30
 * dump: the three 11x18 boards plus Mini 2020 (11x12 sub-grid, hold
 * IDs 1..132). Mini's frames are encoded with the same universal
 * formula (`(row-1)*11 + col + 1`) so the dump's hold IDs land in
 * 12..132 (rows 2..12, no row 1).
 *
 * MoonBoard 2024 (layout 3, the 198-hold 11x18 set released after the
 * dump) ships too — its catalogue is synced via the board-data sync like
 * the other variants. It reuses the standard 11x18 grid (same coord
 * arithmetic as 2016/2017/2019), so no new frame/render code is needed.
 * Mini 2025 (smaller grid again) remains deferred to 0.2.x — see FEAT-027 §3.
 *
 * Mini 2020 caveat: the procedural-grid fallback + the BLE wire
 * encoder still assume 11x18 ([MoonBoardFrameEncoder],
 * [MoonBoardVisualization]). Detail-screen rendering uses the bundled
 * coord-map so it shows correctly; the fallback only triggers on
 * decode failure, and Mini-hardware BLE testing isn't in 0.2.0 scope.
 * Per-variant grid dims are a 0.2.x polish.
 *
 * [layoutId] matches `climbs.layout_id` in the board DB, assigned by the
 * MoonBoard importer from the hold-setup → layout mapping dictated by the
 * MoonBoard hardware.
 */
enum class MoonBoardVariant(
    val layoutId: Long,
    val displayName: String,
    /** Wall angles the variant's catalogue is set at (degrees). */
    val angles: List<Int>,
    /**
     * Rows of bolt positions on the physical board — the per-column
     * height that the BLE wire-format serpentine arithmetic walks.
     * Standard MoonBoards are 18; Mini 2020 is 12.
     */
    val gridRows: Int,
) {
    MOONBOARD_2016(
        // The official MoonBoard catalogue sets 2016 problems at BOTH 25° and
        // 40° (the 40° set is the bulk; a smaller 25° set exists), so the picker
        // offers both. A climb's real angle is climb_stats.angle — an angle with
        // no imported content for this board simply browses empty until the
        // catalogue carrying it is synced.
        layoutId = 2L,
        displayName = "MoonBoard 2016",
        angles = listOf(25, 40),
        gridRows = 18,
    ),
    MASTERS_2017(
        layoutId = 4L,
        displayName = "MoonBoard Masters 2017",
        angles = listOf(25, 40),
        gridRows = 18,
    ),
    MASTERS_2019(
        layoutId = 5L,
        displayName = "MoonBoard Masters 2019",
        angles = listOf(25, 40),
        gridRows = 18,
    ),
    MINI_2020(
        layoutId = 6L,
        displayName = "Mini MoonBoard 2020",
        angles = listOf(40),
        // Mini physically has 12 rows (1..12). The dump uses rows 2..12;
        // row 1 is included in the coord-map for completeness so a
        // future climb that uses row-1 holds still has a position. The
        // BLE serpentine multiplier is 12, not 18; dynamic-capture
        // against a real Mini board still pending.
        gridRows = 12,
    ),
    MINI_2025(
        // Mini MoonBoard 2025 (BoardSesh layout 7, holdsetup 22; hold sets
        // 28-31: Hold Set F, Original School Holds, Wooden Holds B/C). Same
        // 11-column footprint as Mini 2020 but the catalogue uses the FULL
        // row range 1..12 (2020's problems only populate rows 2..12).
        // 40°-fixed: a 600-climb live sample of the official catalogue
        // (2026-07-13) was 100% angle=40, matching the fixed-angle Mini
        // hardware. ~4.9k climbs. No bundled board image yet — renders via
        // the procedural grid until the board-image pipeline delivers the
        // CruxCoach-original render + coord-map.
        layoutId = 7L,
        displayName = "Mini MoonBoard 2025",
        angles = listOf(40),
        gridRows = 12,
    ),
    MOONBOARD_2024(
        // The 2024 198-hold set (BoardSesh "moonboard" layoutId 3). 198 =
        // 11x18 — the same grid as 2016/2017/2019, so the standard 11x18
        // coord-map + frame encoder apply unchanged. 2024 is an ADJUSTABLE
        // board: the official catalogue sets problems at both 25° and 40°
        // (the earlier 40°-only assumption held only while the sole source
        // was ~19 BoardSesh user climbs — all 40°). Now that the full official
        // catalogue is available it carries a substantial 25° set, so the
        // picker offers both; the real per-climb angle comes from
        // climb_stats.angle, and an angle with no synced content browses empty.
        layoutId = 3L,
        displayName = "MoonBoard 2024",
        angles = listOf(25, 40),
        gridRows = 18,
    );

    companion object {
        /**
         * Hold grid — uniform across every v0.2.0 variant (columns A-K,
         * rows 1-18, row 1 at the bottom). When the smaller Mini variants
         * land in 0.2.x these become per-variant fields.
         */
        const val GRID_COLUMNS = 11
        const val GRID_ROWS = 18

        /** Resolve a board-DB `layout_id` to its variant, or null if the
         *  layout isn't a v0.2.0-supported MoonBoard variant. */
        fun fromLayoutId(layoutId: Long): MoonBoardVariant? =
            entries.firstOrNull { it.layoutId == layoutId }
    }
}
