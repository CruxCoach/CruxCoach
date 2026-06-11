package com.cruxcoach.board

import com.cruxcoach.domain.board.HoldSetMask
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
        // No set data for the layout (e.g. MoonBoard) → mask 0 (filter off).
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
}
