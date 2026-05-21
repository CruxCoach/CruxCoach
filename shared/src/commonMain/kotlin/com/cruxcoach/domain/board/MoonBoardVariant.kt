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
 * v0.2.0 ships the three 11x18-grid variants present in the spookykat
 * 2023-01-30 dump. Mini MoonBoard 2020 / 2025 (smaller grid, own
 * encoder + renderer) and Masters 2024 (released after the dump, no
 * catalogue data) are deferred to 0.2.x — see FEAT-027 §3.
 *
 * [layoutId] matches `climbs.layout_id` in the board DB, assigned by the
 * MoonBoard importer (`build_moonboard_db.py`) per BoardSesh's
 * `HOLDSETUP_TO_LAYOUT` mapping (Apache-2.0; see that repo's NOTICE).
 */
enum class MoonBoardVariant(
    val layoutId: Long,
    val displayName: String,
    /** Wall angles the variant's catalogue is set at (degrees). */
    val angles: List<Int>,
    /**
     * Hold sets the variant can be fitted with — the picker's second tier.
     * Display-only in v0.2.0 (the community catalogue dump is not
     * hold-set-partitioned, so this does not scope the browser). Names
     * mirror BoardSesh's firmware layout table (FEAT-027 §3); the first
     * entry is the conventional default.
     */
    val holdSets: List<String>,
) {
    MOONBOARD_2016(
        layoutId = 2L,
        displayName = "MoonBoard 2016",
        angles = listOf(40),
        holdSets = listOf("Hold Set A", "Hold Set B", "Original School Holds"),
    ),
    MASTERS_2017(
        layoutId = 4L,
        displayName = "MoonBoard Masters 2017",
        angles = listOf(25, 40),
        holdSets = listOf(
            "Hold Set A", "Hold Set B", "Hold Set C",
            "Original School Holds", "Screw-on Feet", "Wooden Holds",
        ),
    ),
    MASTERS_2019(
        layoutId = 5L,
        displayName = "MoonBoard Masters 2019",
        angles = listOf(25, 40),
        holdSets = listOf(
            "Hold Set A", "Hold Set B", "Original School Holds",
            "Screw-on Feet", "Wooden Holds", "Wooden Holds B", "Wooden Holds C",
        ),
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
