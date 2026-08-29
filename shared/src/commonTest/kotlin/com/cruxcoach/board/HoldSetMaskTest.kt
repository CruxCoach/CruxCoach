package com.cruxcoach.board

import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests for the hsm exclusion-mask computation backing the
 * hold-set leg of the always-on "fits my board" browse filter.
 *
 * Ground truth (verified empirically against the Tension RE DB 5000/5000 and
 * the Kilter chunk): the bit index of a set in climbs.hsm is the RANK of its
 * set_id within the layout's distinct set ids sorted ascending.
 */
class HoldSetMaskTest {

    @Test
    fun rankMapping_followsAscendingSetIdOrder() {
        // Tension TB2: sets {8,9,10,11} → 8=bit0, 9=bit1, 10=bit2, 11=bit3.
        val layout = listOf(8L, 9L, 10L, 11L)
        // Size missing set 9 → bit1.
        assertEquals(0b0010L, HoldSetMask.excludedMask(layout, listOf(8L, 10L, 11L)))
        // Size missing sets 8 and 11 → bit0 + bit3 (matches a climb on
        // {8,11} carrying hsm 9 being excluded: 9 & 9 != 0).
        assertEquals(0b1001L, HoldSetMask.excludedMask(layout, listOf(9L, 10L)))
    }

    @Test
    fun kilterHomewall_mainlineLikeSize() {
        // Kilter Homewall layout 8: universe {26,27,28,29}. A size carrying
        // only {26,28,29} (8x12 Mainline shape) excludes 27 = bit1.
        val layout = listOf(26L, 27L, 28L, 29L)
        assertEquals(0b0010L, HoldSetMask.excludedMask(layout, listOf(26L, 28L, 29L)))
        // 7x10 carrying only {26} of the layout's {26,27} sub-universe.
        assertEquals(0b0010L, HoldSetMask.excludedMask(listOf(26L, 27L), listOf(26L)))
    }

    @Test
    fun sizeCarriesEverySet_masksNothing() {
        assertEquals(0L, HoldSetMask.excludedMask(listOf(26L, 27L), listOf(26L, 27L)))
    }

    @Test
    fun emptyLayoutUniverse_filterOff() {
        // No set data for the layout at all → mask 0 (filter off).
        assertEquals(0L, HoldSetMask.excludedMask(emptyList(), listOf(26L)))
    }

    @Test
    fun emptySizeSets_filterOff_lenient() {
        // Unknown size data must NOT exclude everything — stay lenient.
        assertEquals(0L, HoldSetMask.excludedMask(listOf(26L, 27L), emptyList()))
    }

    @Test
    fun unsortedAndDuplicatedInput_isNormalized() {
        // Ranking sorts ascending and dedupes; input order must not matter.
        val layout = listOf(11L, 8L, 10L, 9L, 8L)
        assertEquals(0b0010L, HoldSetMask.excludedMask(layout, listOf(8L, 10L, 11L)))
    }

    @Test
    fun sizeSetsOutsideTheUniverse_areIgnored() {
        // A stray on-size set id not in the layout universe has no rank and
        // therefore no bit — only universe membership decides the mask.
        assertEquals(0L, HoldSetMask.excludedMask(listOf(26L, 27L), listOf(26L, 27L, 99L)))
    }

    // ── MoonBoard universes (FEAT-049) ─────────────────────────
    // The second axis is the user's owned sets, not a product size — but the
    // bit rule is the same one, and these cases are where it would break.

    @Test
    fun moonBoard_allSetsOwned_masksNothing() {
        // Level 1 ("complete setup") is exactly "every set selected", and must
        // leave browse results byte-identical to the pre-FEAT-049 behaviour.
        MoonBoardVariant.entries.forEach { variant ->
            val universe = MoonBoardHoldSets.setIdsFor(variant)
            assertEquals(0L, HoldSetMask.excludedMask(universe, universe), variant.name)
        }
    }

    @Test
    fun moonBoard_woodenHoldsMissingOn2019_masksBit3() {
        // The board from issue #9: a Masters 2019 without Wooden Holds (21).
        val universe = MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MASTERS_2019)
        assertEquals(0b001000L, HoldSetMask.excludedMask(universe, universe - 21L))
    }

    @Test
    fun moonBoard_noStoredSelection_isLenient() {
        // An absent (or defensively emptied) preference means "all sets", and
        // the empty-ownership guard already resolves it to mask 0. Both the
        // caller and this leg must agree, or a fresh install would hide the
        // whole catalogue.
        val universe = MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MASTERS_2019)
        assertEquals(0L, HoldSetMask.excludedMask(universe, emptyList()))
    }

    @Test
    fun moonBoard_2016SelectionOn2017Universe_cannotLeakBits() {
        // Edge case 2: the set-id spaces are disjoint per layout, so even a
        // stale 2016 selection applied to a 2017 universe excludes everything
        // it does not name rather than silently matching by rank.
        val universe2017 = MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MASTERS_2017)
        val stale2016 = MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MOONBOARD_2016)
        assertEquals(0b11111L, HoldSetMask.excludedMask(universe2017, stale2016))
    }
}
