package com.cruxcoach.board

import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.domain.board.QuantumOverlapIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuantumOverlapFilterTest {
    private val index = QuantumOverlapIndex(setOf(1, 2, 3))

    @Test
    fun `no-overlap admits only disjoint climbs`() {
        assertTrue(index.matches(setOf(4, 5), QuantumOverlapFilter.NONE))
        assertFalse(index.matches(setOf(3, 4), QuantumOverlapFilter.NONE))
    }

    @Test
    fun `at-most-one admits exactly zero or one shared placement`() {
        assertTrue(index.matches(setOf(3, 4), QuantumOverlapFilter.AT_MOST_ONE))
        assertFalse(index.matches(setOf(2, 3), QuantumOverlapFilter.AT_MOST_ONE))
    }

    @Test
    fun `off and unknown persisted values fail open to the full catalogue`() {
        assertTrue(index.matches(setOf(1, 2, 3), QuantumOverlapFilter.OFF))
        assertEquals(QuantumOverlapFilter.OFF, QuantumOverlapFilter.fromWire("future-mode"))
        assertEquals(QuantumOverlapFilter.OFF, QuantumOverlapFilter.fromWire(null))
    }

    @Test
    fun `overlaps count unique placements`() {
        assertEquals(2, index.overlapCount(setOf(1, 2, 9)))
    }
}
