package com.cruxcoach.domain.board

/**
 * MoonBoard configurations supported by CruxCoach (FEAT-027).
 *
 * Unlike Kilter — one board offered in several physical SIZES — the
 * MoonBoard family is several DISTINCT boards: each variant has its own
 * hold set, and a problem set for one (variant, angle) is a different
 * climb from "the same holds" on another. The catalogue dump carries
 * them as separate problems, and the importer writes one climb row per
 * (problem, angle) accordingly.
 *
 * Each entry is a complete, fixed hold configuration, not a selectable
 * collection of hold sets. MoonBoard 2010 and Mini MoonBoard 2025 therefore
 * sit alongside the existing configurations as their own catalogue/render/BLE
 * identities. Standard boards use an 11x18 grid (198 LED positions); Mini
 * boards use 11x12 (132 positions).
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
     * Standard MoonBoards are 18; Mini boards are 12.
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
        // BLE serpentine multiplier is 12, not 18. Physical-controller
        // integration remains a release check separate from the grid map.
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
    ),
    MINI_2025(
        // The 2025 Mini is a complete configuration made from Hold Set F,
        // Original School Holds and Wooden Holds B/C. The constituent sets
        // are deliberately not exposed as independent user choices.
        layoutId = 7L,
        displayName = "Mini MoonBoard 2025",
        angles = listOf(40),
        gridRows = 12,
    ),
    MOONBOARD_2010(
        // Legacy Original School Holds configuration. layout_id=1 collides
        // with Kilter Original, so callers resolving a persisted board choice
        // must also carry BoardBrand; see fromBoardSelection().
        layoutId = 1L,
        displayName = "MoonBoard 2010",
        angles = listOf(40),
        gridRows = 18,
    );

    companion object {
        /**
         * Universal column count plus the standard-board row count. Mini
         * variants override the row count through [gridRows].
         */
        const val GRID_COLUMNS = 11
        const val GRID_ROWS = 18

        /** Resolve a MoonBoard catalogue `layout_id` to its variant. Layout 1
         *  is ambiguous globally because Kilter Original uses it as well. */
        fun fromLayoutId(layoutId: Long): MoonBoardVariant? =
            entries.firstOrNull { it.layoutId == layoutId }

        /** Resolve a persisted board selection without crossing brand-owned
         *  id spaces. This is required for layout 1 (MoonBoard 2010 / Kilter
         *  Original) and is the safe choice whenever [boardBrand] is known. */
        fun fromBoardSelection(layoutId: Long, boardBrand: BoardBrand): MoonBoardVariant? =
            if (boardBrand == BoardBrand.MOONBOARD) fromLayoutId(layoutId) else null
    }
}
