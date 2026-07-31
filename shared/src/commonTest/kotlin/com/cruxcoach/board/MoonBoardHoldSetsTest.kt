package com.cruxcoach.board

import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Freezes the MoonBoard hold-set universes and their `hsm` bit order
 * (FEAT-049 §3.2/§3.3).
 *
 * The expectations below are WRITTEN OUT BY HAND from the spec, never derived
 * from [MoonBoardHoldSets] itself. A test that re-runs the production
 * derivation would agree with any table, including a wrong one — and this
 * particular table is permanent: a stored user selection is interpreted
 * through the bit order, so a changed set id would silently reinterpret every
 * persisted preference on every device.
 */
class MoonBoardHoldSetsTest {

    /** One variant's expected universe: set ids, names and per-set hold counts
     *  in bit order (bit 0 first). */
    private class Expectation(
        val variant: MoonBoardVariant,
        val layoutId: Long,
        val setIds: List<Long>,
        val names: List<String>,
        val holdCounts: List<Int>,
    ) {
        val cellCount: Int get() = holdCounts.sum()
    }

    private val expectations = listOf(
        Expectation(
            MoonBoardVariant.MOONBOARD_2010, layoutId = 1L,
            setIds = listOf(1L),
            names = listOf("Original School Holds"),
            holdCounts = listOf(40),
        ),
        Expectation(
            MoonBoardVariant.MOONBOARD_2016, layoutId = 2L,
            setIds = listOf(2L, 3L, 4L),
            names = listOf("Hold Set A", "Hold Set B", "Original School Holds"),
            holdCounts = listOf(50, 50, 40),
        ),
        Expectation(
            MoonBoardVariant.MOONBOARD_2024, layoutId = 3L,
            setIds = listOf(5L, 6L, 7L, 8L, 9L, 10L),
            names = listOf(
                "Hold Set D", "Hold Set E", "Hold Set F",
                "Wooden Holds", "Wooden Holds B", "Wooden Holds C",
            ),
            holdCounts = listOf(39, 41, 40, 31, 23, 24),
        ),
        Expectation(
            MoonBoardVariant.MASTERS_2017, layoutId = 4L,
            setIds = listOf(11L, 12L, 13L, 14L, 16L),
            names = listOf(
                "Hold Set A", "Hold Set B", "Hold Set C",
                "Original School Holds", "Wooden Holds",
            ),
            holdCounts = listOf(40, 40, 52, 34, 32),
        ),
        Expectation(
            MoonBoardVariant.MASTERS_2019, layoutId = 5L,
            setIds = listOf(17L, 18L, 19L, 21L, 22L, 23L),
            names = listOf(
                "Hold Set A", "Hold Set B", "Original School Holds",
                "Wooden Holds", "Wooden Holds B", "Wooden Holds C",
            ),
            holdCounts = listOf(40, 40, 38, 32, 24, 24),
        ),
        Expectation(
            MoonBoardVariant.MINI_2020, layoutId = 6L,
            setIds = listOf(24L, 25L, 26L, 27L),
            names = listOf(
                "Original School Holds", "Wooden Holds",
                "Wooden Holds B", "Wooden Holds C",
            ),
            holdCounts = listOf(40, 32, 24, 24),
        ),
        Expectation(
            MoonBoardVariant.MINI_2025, layoutId = 7L,
            setIds = listOf(28L, 29L, 30L, 31L),
            names = listOf(
                "Hold Set F", "Original School Holds",
                "Wooden Holds B", "Wooden Holds C",
            ),
            holdCounts = listOf(40, 40, 24, 24),
        ),
    )

    @Test
    fun everyVariantIsCovered() {
        assertEquals(
            MoonBoardVariant.entries.toSet(),
            expectations.map { it.variant }.toSet(),
        )
    }

    @Test
    fun setIdsPerVariant_matchTheSpecTable() {
        expectations.forEach { e ->
            assertEquals(e.layoutId, e.variant.layoutId, "${e.variant}: layout id drifted")
            assertEquals(
                e.setIds,
                MoonBoardHoldSets.setIdsFor(e.variant),
                "${e.variant}: set-id universe (order is the hsm bit order)",
            )
        }
    }

    @Test
    fun screwOnFeet_appearsForNoVariant() {
        // Ids 15 (layout 4) and 20 (layout 5). They exist as board art and as
        // MOONBOARD_SETS entries, but cover no cell and belong to no problem's
        // hold-set list — a foot rule, not a problem-relevant set.
        MoonBoardVariant.entries.forEach { variant ->
            val ids = MoonBoardHoldSets.setIdsFor(variant)
            assertFalse(15L in ids, "$variant: Screw-on Feet (15) must not be selectable")
            assertFalse(20L in ids, "$variant: Screw-on Feet (20) must not be selectable")
        }
    }

    @Test
    fun displayNames_areTheOfficialAppsProductNames() {
        expectations.forEach { e ->
            assertEquals(
                e.names,
                MoonBoardHoldSets.setsFor(e.variant).map { it.displayName },
                "${e.variant}: set names",
            )
        }
    }

    @Test
    fun perSetHoldCounts_matchTheRenderedFigures() {
        expectations.forEach { e ->
            assertEquals(
                e.holdCounts,
                e.setIds.map { MoonBoardHoldSets.holdIdsFor(e.variant, it).size },
                "${e.variant}: per-set hold counts",
            )
        }
    }

    @Test
    fun cellCountsPerVariant_matchTheSpecTable() {
        // 40 + 140 + 198 + 198 + 198 + 120 + 128 = 1022 cells in total.
        val expectedTotals = listOf(40, 140, 198, 198, 198, 120, 128)
        assertEquals(expectedTotals, expectations.map { it.cellCount })
        expectations.forEachIndexed { index, e ->
            assertEquals(
                expectedTotals[index],
                MoonBoardHoldSets.cellSets(e.variant).size,
                "${e.variant}: cell-map size",
            )
        }
        assertEquals(
            1022,
            MoonBoardVariant.entries.sumOf { MoonBoardHoldSets.cellSets(it).size },
        )
    }

    @Test
    fun holdSetsPartitionTheBoard_noCellInTwoSets() {
        expectations.forEach { e ->
            val perSet = e.setIds.flatMap { MoonBoardHoldSets.holdIdsFor(e.variant, it) }
            assertEquals(
                perSet.size, perSet.toSet().size,
                "${e.variant}: a cell may belong to exactly one hold set",
            )
            assertEquals(
                perSet.toSet(),
                MoonBoardHoldSets.cellSets(e.variant).keys,
                "${e.variant}: cell map and per-set lists must cover the same cells",
            )
        }
    }

    @Test
    fun cellIds_stayInsideTheVariantsGrid() {
        MoonBoardVariant.entries.forEach { variant ->
            val max = MoonBoardVariant.GRID_COLUMNS * variant.gridRows
            MoonBoardHoldSets.cellSets(variant).keys.forEach { holdId ->
                assertTrue(
                    holdId in 1..max,
                    "$variant: cell $holdId outside 1..$max",
                )
            }
        }
    }

    @Test
    fun onlyMoonBoard2010_hasNoChoiceToOffer() {
        MoonBoardVariant.entries.forEach { variant ->
            assertEquals(
                variant != MoonBoardVariant.MOONBOARD_2010,
                MoonBoardHoldSets.isSelectable(variant),
                "$variant: selectable iff it has more than one hold set",
            )
        }
    }

    // ── §3.3 bit table ─────────────────────────────────────────
    // Hard-coded expected masks. Deselecting exactly one set must produce the
    // literal power of two below — the same numbers a reader can count off the
    // §3.3 table, not a value recomputed from the map.

    @Test
    fun singleDeselection_producesTheSpecifiedBit() {
        // MoonBoard 2010 is absent on purpose: its single set cannot be
        // deselected without emptying the ownership list, which is the
        // "unknown board, stay lenient" case — see
        // singleSetVariant_cannotBeDeselectedIntoAnEmptyBoard below.
        val expectedBits: List<Pair<MoonBoardVariant, List<Pair<Long, Long>>>> = listOf(
            MoonBoardVariant.MOONBOARD_2016 to listOf(
                2L to 0b001L,        // bit 0 · Hold Set A
                3L to 0b010L,        // bit 1 · Hold Set B
                4L to 0b100L,        // bit 2 · Original School Holds
            ),
            MoonBoardVariant.MOONBOARD_2024 to listOf(
                5L to 0b000001L,     // bit 0 · Hold Set D
                6L to 0b000010L,     // bit 1 · Hold Set E
                7L to 0b000100L,     // bit 2 · Hold Set F
                8L to 0b001000L,     // bit 3 · Wooden Holds
                9L to 0b010000L,     // bit 4 · Wooden Holds B
                10L to 0b100000L,    // bit 5 · Wooden Holds C
            ),
            MoonBoardVariant.MASTERS_2017 to listOf(
                11L to 0b00001L,     // bit 0 · Hold Set A
                12L to 0b00010L,     // bit 1 · Hold Set B
                13L to 0b00100L,     // bit 2 · Hold Set C
                14L to 0b01000L,     // bit 3 · Original School Holds
                16L to 0b10000L,     // bit 4 · Wooden Holds
            ),
            MoonBoardVariant.MASTERS_2019 to listOf(
                17L to 0b000001L,    // bit 0 · Hold Set A
                18L to 0b000010L,    // bit 1 · Hold Set B
                19L to 0b000100L,    // bit 2 · Original School Holds
                21L to 0b001000L,    // bit 3 · Wooden Holds
                22L to 0b010000L,    // bit 4 · Wooden Holds B
                23L to 0b100000L,    // bit 5 · Wooden Holds C
            ),
            MoonBoardVariant.MINI_2020 to listOf(
                24L to 0b0001L,      // bit 0 · Original School Holds
                25L to 0b0010L,      // bit 1 · Wooden Holds
                26L to 0b0100L,      // bit 2 · Wooden Holds B
                27L to 0b1000L,      // bit 3 · Wooden Holds C
            ),
            MoonBoardVariant.MINI_2025 to listOf(
                28L to 0b0001L,      // bit 0 · Hold Set F
                29L to 0b0010L,      // bit 1 · Original School Holds
                30L to 0b0100L,      // bit 2 · Wooden Holds B
                31L to 0b1000L,      // bit 3 · Wooden Holds C
            ),
        )
        expectedBits.forEach { (variant, rows) ->
            val universe = MoonBoardHoldSets.setIdsFor(variant)
            rows.forEach { (deselected, expectedMask) ->
                assertEquals(
                    expectedMask,
                    HoldSetMask.excludedMask(universe, universe - deselected),
                    "$variant: deselecting set $deselected",
                )
            }
        }
    }

    @Test
    fun singleSetVariant_cannotBeDeselectedIntoAnEmptyBoard() {
        // Edge case 1/5: MoonBoard 2010 has one set, so the picker is hidden
        // for it — but if an empty selection ever reached the mask anyway, it
        // must read as "all sets", never as "exclude everything".
        val universe = MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MOONBOARD_2010)
        assertEquals(listOf(1L), universe)
        assertEquals(0L, HoldSetMask.excludedMask(universe, emptyList()))
    }

    @Test
    fun masters2019_workedExample_carriesHsm17() {
        // §3.3: "A climb using sets {A, Wooden B} on layout 5 carries
        // hsm = 0b010001 = 17."
        val variant = MoonBoardVariant.MASTERS_2019
        val setA = MoonBoardHoldSets.holdIdsFor(variant, 17L).first()
        val woodenB = MoonBoardHoldSets.holdIdsFor(variant, 22L).first()
        assertEquals(17L, MoonBoardHoldSets.maskForHoldIds(variant, listOf(setA, woodenB)))
    }

    // ── Per-row hsm derivation (§6.6) ──────────────────────────

    @Test
    fun maskForHoldIds_setsOneBitPerUsedSet() {
        val variant = MoonBoardVariant.MASTERS_2019
        val osh = MoonBoardHoldSets.holdIdsFor(variant, 19L).take(3)
        assertEquals(0b000100L, MoonBoardHoldSets.maskForHoldIds(variant, osh))
    }

    @Test
    fun maskForHoldIds_unknownCell_staysUnknownRatherThanClaimingNothing() {
        val variant = MoonBoardVariant.MOONBOARD_2016
        val known = MoonBoardHoldSets.holdIdsFor(variant, 2L).first()
        val unknown = (1..198).first { it !in MoonBoardHoldSets.cellSets(variant) }
        // 0 = UNKNOWN, which passes every mask. Emitting the known holds' bits
        // alone would claim the climb does not need `unknown`'s set and could
        // hide a climb the user can actually do.
        assertEquals(0L, MoonBoardHoldSets.maskForHoldIds(variant, listOf(known, unknown)))
    }

    @Test
    fun maskForHoldIds_noHolds_isUnknown() {
        assertEquals(
            0L,
            MoonBoardHoldSets.maskForHoldIds(MoonBoardVariant.MASTERS_2019, emptyList()),
        )
    }
}
