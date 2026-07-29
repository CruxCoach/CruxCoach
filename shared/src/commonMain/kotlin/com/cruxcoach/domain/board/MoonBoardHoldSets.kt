package com.cruxcoach.domain.board

/**
 * The hold sets each MoonBoard generation is assembled from, and which grid
 * cell belongs to which set (FEAT-049).
 *
 * A MoonBoard is not one fixed wall: every generation is a partition of the
 * board into individually purchasable hold sets, and owners routinely mount
 * only some of them. This object carries both halves of that model:
 *
 *  - the SET UNIVERSE per variant — the ids whose ascending order defines the
 *    `climbs.hsm` bit ranks (see [HoldSetMask]) and the rows the hold-set
 *    picker offers;
 *  - the CELL MAP — which grid hold id belongs to which set. It is used to
 *    DRAW a set onto the board art, and to derive `hsm` for a single locally
 *    authored or peer-received climb.
 *
 * The cell map is deliberately NOT used to recompute the catalogue's `hsm`.
 * That value is derived once in the catalogue build pipeline and shipped in
 * the chunk; `mergeSnapshotClimbs()` would overwrite any device-side result on
 * the next sync anyway. The two uses are independent and must stay that way.
 *
 * **Set-id space.** These are BoardSesh's ids, adopted verbatim. They are
 * unique across all seven layouts (1..31) and are the keys of the cell map.
 * They are NOT Moon's official `apiId` space (where Original School Holds is 3
 * on every board) and NOT Aurora's `placements.set_id` — three different
 * numbers for the same physical set. CruxCoach never joins against Moon's or
 * Aurora's data on these ids, so the divergence is harmless, but the spaces
 * must never be mixed.
 *
 * **Screw-on Feet is absent on purpose** (BoardSesh ids 15 on layout 4 and 20
 * on layout 5). It exists as board art, but it covers no cell of the map and
 * appears in no problem's hold-set list — the official app tracks foot rules
 * in a separate table. It is a render layer, not a problem-relevant set, and
 * must never reach the picker.
 *
 * **Attribution.** The cell map and the set names are derived from BoardSesh
 * (https://github.com/boardsesh/boardsesh) — `MOONBOARD_CELL_SETS` and
 * `MOONBOARD_SETS` in `packages/shared/board-config` — which is licensed
 * under the Apache License 2.0. See THIRD_PARTY_LICENSES.md.
 */
object MoonBoardHoldSets {

    /**
     * One selectable hold set. [displayName] is the product name Moon's own
     * app uses; it stays English in every locale, like any product name. (An
     * invoice may word it differently — "Wood Holds - Set A" for what the app
     * calls "Wooden Holds" — but the app wording is what a user recognises.)
     */
    data class HoldSet(val id: Long, val displayName: String)

    /**
     * The variant's hold sets, ordered by set id ascending — which is exactly
     * the bit order [HoldSetMask.excludedMask] assigns, so the list index of a
     * set is its `hsm` bit index.
     *
     * That order is load-bearing and permanent: a stored user selection is
     * interpreted through it, so changing an id would silently reinterpret
     * every persisted preference.
     */
    fun setsFor(variant: MoonBoardVariant): List<HoldSet> =
        holdSets.getValue(variant)

    /** Set-id universe for [HoldSetMask.excludedMask]'s `layoutSetIds`. */
    fun setIdsFor(variant: MoonBoardVariant): List<Long> =
        setIds.getValue(variant)

    /**
     * True when the variant is built from more than one set, i.e. when the
     * user has a choice worth offering. False for MoonBoard 2010, whose single
     * Original School Holds set leaves nothing to select.
     */
    fun isSelectable(variant: MoonBoardVariant): Boolean =
        setsFor(variant).size > 1

    /** The full cell map for [variant]: grid hold id -> set id. */
    fun cellSets(variant: MoonBoardVariant): Map<Int, Long> =
        cellMaps.getValue(variant)

    /**
     * The grid hold ids that [setId] covers on [variant] — the holds the
     * picker preview rings. Empty for an id that is not part of the variant.
     */
    fun holdIdsFor(variant: MoonBoardVariant, setId: Long): Set<Int> =
        holdIdsBySet.getValue(variant)[setId].orEmpty()

    /**
     * The `hsm` value for a single climb that uses [holdIds] on [variant]:
     * one bit per hold set the climb draws from.
     *
     * Returns 0 — UNKNOWN, which passes every mask — when the climb uses a
     * cell the map does not carry, rather than a mask with that hold's set
     * missing. A climb wrongly shown costs a user less than one wrongly
     * hidden, so an incomplete answer must fall back to leniency instead of
     * claiming the climb needs nothing.
     */
    fun maskForHoldIds(variant: MoonBoardVariant, holdIds: Collection<Int>): Long {
        if (holdIds.isEmpty()) return 0L
        val cells = cellSets(variant)
        val universe = setIdsFor(variant)
        var mask = 0L
        for (holdId in holdIds) {
            val setId = cells[holdId] ?: return 0L
            val bit = universe.indexOf(setId)
            if (bit < 0) return 0L
            mask = mask or (1L shl bit)
        }
        return mask
    }

    // ── Cell map ───────────────────────────────────────────────
    // Generated from BoardSesh's MOONBOARD_CELL_SETS (Apache-2.0, see the
    // class KDoc). Hold ids use the same 1-based `(row-1) * 11 + col + 1`
    // numbering as the `frames` column's `p{holdId}` token and as
    // MoonBoardFrameEncoder — which is what makes the map usable directly.
    // Cells absent from a layout carry no hold in any set (unused positions).

    private class HoldSetCells(
        val setId: Long,
        val displayName: String,
        val holdIds: IntArray,
    )

    // MoonBoard 2010 (layout 1) — 1 set, 40 cells.
    private val LAYOUT_1 = listOf(
        HoldSetCells(1L, "Original School Holds", intArrayOf( // bit 0, 40 holds
            26, 35, 39, 40, 43, 48, 62, 70, 74, 76, 80, 84, 90, 93, 97, 102, 105, 107,
            115, 119, 120, 123, 124, 127, 131, 136, 139, 145, 150, 152, 160, 163, 168,
            170, 175, 190, 191, 193, 195, 197,
        )),
    )

    // MoonBoard 2016 (layout 2) — 3 sets, 140 cells.
    private val LAYOUT_2 = listOf(
        HoldSetCells(2L, "Hold Set A", intArrayOf( // bit 0, 50 holds
            18, 21, 24, 42, 45, 48, 50, 55, 58, 64, 65, 71, 72, 79, 84, 87, 90, 92, 98,
            102, 104, 105, 109, 112, 113, 115, 118, 119, 121, 122, 125, 127, 128, 134,
            137, 139, 141, 146, 147, 149, 155, 159, 167, 171, 174, 180, 189, 192, 194,
            198,
        )),
        HoldSetCells(3L, "Hold Set B", intArrayOf( // bit 1, 50 holds
            40, 47, 52, 54, 57, 60, 62, 70, 75, 76, 80, 82, 85, 89, 93, 95, 97, 99,
            101, 106, 107, 108, 114, 116, 123, 126, 129, 131, 135, 138, 140, 142, 144,
            148, 150, 152, 154, 156, 158, 161, 163, 168, 170, 173, 175, 176, 183, 188,
            191, 196,
        )),
        HoldSetCells(4L, "Original School Holds", intArrayOf( // bit 2, 40 holds
            26, 35, 53, 59, 61, 66, 68, 69, 73, 74, 77, 81, 83, 86, 88, 91, 94, 96,
            100, 103, 110, 111, 117, 120, 124, 130, 132, 133, 136, 143, 151, 153, 157,
            160, 162, 166, 169, 172, 190, 195,
        )),
    )

    // MoonBoard 2024 (layout 3) — 6 sets, 198 cells.
    private val LAYOUT_3 = listOf(
        HoldSetCells(5L, "Hold Set D", intArrayOf( // bit 0, 39 holds
            1, 9, 28, 35, 39, 40, 45, 55, 57, 59, 70, 72, 79, 86, 90, 96, 97, 98, 100,
            102, 113, 123, 125, 128, 135, 143, 144, 152, 154, 160, 164, 179, 183, 185,
            187, 189, 193, 194, 196,
        )),
        HoldSetCells(6L, "Hold Set E", intArrayOf( // bit 1, 41 holds
            6, 7, 14, 22, 24, 25, 31, 32, 37, 42, 46, 51, 52, 77, 78, 83, 93, 101, 104,
            107, 108, 109, 119, 121, 129, 132, 134, 139, 146, 148, 149, 151, 158, 161,
            166, 171, 177, 181, 190, 195, 198,
        )),
        HoldSetCells(7L, "Hold Set F", intArrayOf( // bit 2, 40 holds
            4, 11, 13, 19, 23, 29, 38, 44, 47, 49, 50, 54, 60, 62, 65, 73, 74, 75, 80,
            82, 84, 89, 92, 105, 110, 111, 115, 120, 124, 127, 138, 140, 147, 156, 157,
            162, 168, 175, 176, 182,
        )),
        HoldSetCells(8L, "Wooden Holds", intArrayOf( // bit 3, 31 holds
            2, 3, 5, 8, 10, 12, 15, 16, 18, 20, 26, 27, 33, 43, 56, 68, 71, 76, 81, 85,
            91, 99, 133, 136, 159, 167, 174, 180, 184, 191, 192,
        )),
        HoldSetCells(9L, "Wooden Holds B", intArrayOf( // bit 4, 23 holds
            21, 34, 58, 63, 67, 69, 94, 114, 117, 118, 122, 126, 141, 142, 150, 163,
            165, 169, 172, 173, 178, 188, 197,
        )),
        HoldSetCells(10L, "Wooden Holds C", intArrayOf( // bit 5, 24 holds
            17, 30, 36, 41, 48, 53, 61, 64, 66, 87, 88, 95, 103, 106, 112, 116, 130,
            131, 137, 145, 153, 155, 170, 186,
        )),
    )

    // MoonBoard Masters 2017 (layout 4) — 5 sets, 198 cells.
    private val LAYOUT_4 = listOf(
        HoldSetCells(11L, "Hold Set A", intArrayOf( // bit 0, 40 holds
            2, 8, 11, 13, 17, 23, 29, 33, 35, 39, 43, 50, 53, 60, 62, 73, 76, 79, 82,
            86, 92, 96, 102, 107, 111, 115, 130, 135, 143, 145, 150, 155, 165, 166,
            174, 180, 183, 187, 188, 195,
        )),
        HoldSetCells(12L, "Hold Set B", intArrayOf( // bit 1, 40 holds
            3, 6, 12, 20, 26, 28, 30, 38, 40, 42, 45, 48, 59, 65, 69, 72, 78, 84, 88,
            90, 99, 101, 106, 118, 121, 125, 127, 134, 139, 142, 148, 151, 158, 162,
            172, 176, 179, 184, 191, 193,
        )),
        HoldSetCells(13L, "Hold Set C", intArrayOf( // bit 2, 52 holds
            1, 5, 9, 15, 18, 21, 22, 24, 27, 31, 34, 37, 44, 52, 56, 61, 66, 71, 75,
            81, 83, 85, 94, 98, 103, 104, 108, 112, 117, 120, 122, 124, 129, 137, 141,
            144, 146, 147, 153, 154, 160, 163, 168, 170, 175, 177, 181, 182, 186, 189,
            192, 197,
        )),
        HoldSetCells(14L, "Original School Holds", intArrayOf( // bit 3, 34 holds
            4, 7, 10, 14, 16, 19, 25, 32, 36, 41, 47, 55, 57, 63, 68, 80, 87, 89, 105,
            109, 114, 116, 132, 133, 138, 149, 152, 157, 167, 171, 178, 185, 194, 198,
        )),
        HoldSetCells(16L, "Wooden Holds", intArrayOf( // bit 4, 32 holds
            46, 49, 51, 54, 58, 64, 67, 70, 74, 77, 91, 93, 95, 97, 100, 110, 113, 119,
            123, 126, 128, 131, 136, 140, 156, 159, 161, 164, 169, 173, 190, 196,
        )),
    )

    // MoonBoard Masters 2019 (layout 5) — 6 sets, 198 cells.
    private val LAYOUT_5 = listOf(
        HoldSetCells(17L, "Hold Set A", intArrayOf( // bit 0, 40 holds
            4, 8, 10, 12, 18, 20, 24, 26, 28, 31, 34, 37, 41, 44, 46, 56, 62, 65, 68,
            71, 83, 86, 89, 103, 110, 124, 126, 129, 141, 144, 153, 165, 167, 171, 173,
            181, 182, 187, 191, 196,
        )),
        HoldSetCells(18L, "Hold Set B", intArrayOf( // bit 1, 40 holds
            1, 5, 7, 13, 14, 17, 19, 21, 22, 23, 27, 30, 39, 43, 47, 49, 52, 61, 78,
            80, 87, 88, 93, 95, 99, 106, 112, 115, 135, 158, 161, 163, 166, 174, 178,
            183, 186, 188, 190, 195,
        )),
        HoldSetCells(19L, "Original School Holds", intArrayOf( // bit 2, 38 holds
            2, 3, 6, 9, 11, 15, 16, 29, 33, 35, 36, 40, 50, 53, 55, 59, 69, 73, 74, 76,
            81, 101, 109, 117, 121, 122, 136, 143, 148, 151, 156, 169, 175, 177, 180,
            184, 193, 198,
        )),
        HoldSetCells(21L, "Wooden Holds", intArrayOf( // bit 3, 32 holds
            38, 42, 58, 64, 66, 67, 70, 72, 75, 85, 90, 91, 94, 102, 108, 113, 116,
            119, 123, 130, 131, 132, 134, 138, 142, 147, 150, 170, 172, 176, 192, 194,
        )),
        HoldSetCells(22L, "Wooden Holds B", intArrayOf( // bit 4, 24 holds
            48, 57, 60, 77, 79, 84, 96, 100, 105, 111, 120, 125, 128, 133, 137, 146,
            152, 154, 162, 164, 168, 179, 189, 197,
        )),
        HoldSetCells(23L, "Wooden Holds C", intArrayOf( // bit 5, 24 holds
            25, 32, 45, 51, 54, 63, 82, 92, 97, 98, 104, 107, 114, 118, 127, 139, 140,
            145, 149, 155, 157, 159, 160, 185,
        )),
    )

    // Mini MoonBoard 2020 (layout 6) — 4 sets, 120 cells.
    private val LAYOUT_6 = listOf(
        HoldSetCells(24L, "Original School Holds", intArrayOf( // bit 0, 40 holds
            12, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24, 29, 31, 32, 37, 40, 43, 46, 53,
            58, 60, 63, 67, 76, 80, 81, 84, 86, 90, 93, 99, 100, 102, 106, 108, 116,
            122, 128, 131, 132,
        )),
        HoldSetCells(25L, "Wooden Holds", intArrayOf( // bit 1, 32 holds
            13, 28, 33, 34, 45, 47, 49, 50, 52, 57, 62, 64, 70, 72, 75, 79, 82, 85, 87,
            88, 91, 95, 97, 101, 103, 105, 109, 119, 121, 123, 126, 130,
        )),
        HoldSetCells(26L, "Wooden Holds B", intArrayOf( // bit 2, 24 holds
            27, 35, 44, 48, 51, 65, 68, 69, 74, 78, 83, 92, 104, 111, 112, 113, 114,
            117, 118, 120, 124, 125, 127, 129,
        )),
        HoldSetCells(27L, "Wooden Holds C", intArrayOf( // bit 3, 24 holds
            25, 26, 30, 36, 38, 39, 41, 42, 54, 55, 56, 59, 61, 66, 71, 73, 77, 89, 94,
            96, 98, 107, 110, 115,
        )),
    )

    // Mini MoonBoard 2025 (layout 7) — 4 sets, 128 cells.
    private val LAYOUT_7 = listOf(
        HoldSetCells(28L, "Hold Set F", intArrayOf( // bit 0, 40 holds
            18, 23, 30, 33, 36, 38, 39, 43, 52, 55, 56, 59, 60, 63, 66, 69, 70, 74, 77,
            79, 80, 82, 86, 92, 94, 97, 102, 107, 108, 112, 114, 115, 116, 117, 120,
            122, 126, 128, 131, 132,
        )),
        HoldSetCells(29L, "Original School Holds", intArrayOf( // bit 1, 40 holds
            2, 5, 6, 7, 10, 11, 12, 14, 15, 16, 19, 22, 24, 25, 26, 28, 29, 31, 32, 34,
            35, 42, 47, 51, 54, 61, 67, 72, 76, 84, 87, 90, 99, 100, 103, 106, 113,
            118, 125, 129,
        )),
        HoldSetCells(30L, "Wooden Holds B", intArrayOf( // bit 2, 24 holds
            1, 13, 27, 44, 45, 46, 49, 58, 62, 64, 65, 73, 78, 83, 89, 91, 96, 101,
            109, 110, 111, 119, 127, 130,
        )),
        HoldSetCells(31L, "Wooden Holds C", intArrayOf( // bit 3, 24 holds
            17, 20, 21, 37, 40, 41, 48, 50, 53, 57, 68, 71, 75, 81, 85, 88, 93, 95, 98,
            104, 105, 121, 123, 124,
        )),
    )

    private val cells: Map<MoonBoardVariant, List<HoldSetCells>> = mapOf(
        MoonBoardVariant.MOONBOARD_2010 to LAYOUT_1,
        MoonBoardVariant.MOONBOARD_2016 to LAYOUT_2,
        MoonBoardVariant.MOONBOARD_2024 to LAYOUT_3,
        MoonBoardVariant.MASTERS_2017 to LAYOUT_4,
        MoonBoardVariant.MASTERS_2019 to LAYOUT_5,
        MoonBoardVariant.MINI_2020 to LAYOUT_6,
        MoonBoardVariant.MINI_2025 to LAYOUT_7,
    )

    private val holdSets: Map<MoonBoardVariant, List<HoldSet>> =
        cells.mapValues { (_, sets) -> sets.map { HoldSet(it.setId, it.displayName) } }

    private val setIds: Map<MoonBoardVariant, List<Long>> =
        cells.mapValues { (_, sets) -> sets.map { it.setId } }

    private val holdIdsBySet: Map<MoonBoardVariant, Map<Long, Set<Int>>> =
        cells.mapValues { (_, sets) -> sets.associate { it.setId to it.holdIds.toSet() } }

    private val cellMaps: Map<MoonBoardVariant, Map<Int, Long>> =
        cells.mapValues { (_, sets) ->
            buildMap { sets.forEach { set -> set.holdIds.forEach { put(it, set.setId) } } }
        }
}
