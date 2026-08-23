package com.cruxcoach.board

import com.cruxcoach.domain.board.QuantumLaneOccupancy
import com.cruxcoach.domain.board.QuantumLaneSource
import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.domain.board.QuantumOverlapIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "What still fits on this wall", as a browse filter.
 *
 * The distinction being tested is the one that is easy to get wrong: this
 * filter measures against *confirmed* layers, not against the effective rack
 * the backlog plans with. Its promise is about the controller, and a preview
 * has not taken a hold.
 */
class QuantumOverlapFilterTest {

    private fun confirmed(lane: Int, placements: Set<Int>?) = QuantumLaneOccupancy(
        lane = lane,
        source = QuantumLaneSource.CONFIRMED,
        routeKey = "route-$lane",
        placements = placements,
    )

    @Test
    fun `zero overlap admits only climbs that share nothing`() {
        val index = QuantumOverlapIndex(litPlacements = setOf(1, 2, 3))

        assertTrue(index.matches(setOf(4, 5), QuantumOverlapFilter.NONE))
        assertFalse(index.matches(setOf(3, 4), QuantumOverlapFilter.NONE))
    }

    @Test
    fun `at most one is a discovery filter and does not promise a send`() {
        val index = QuantumOverlapIndex(litPlacements = setOf(1, 2, 3))

        assertTrue(index.matches(setOf(3, 4), QuantumOverlapFilter.AT_MOST_ONE))
        assertFalse(index.matches(setOf(2, 3), QuantumOverlapFilter.AT_MOST_ONE))
        assertFalse(
            QuantumOverlapFilter.AT_MOST_ONE.impliesSendable,
            "one diode still cannot carry two colours",
        )
        assertTrue(QuantumOverlapFilter.NONE.impliesSendable)
    }

    @Test
    fun `overlap counts unique placements`() {
        val index = QuantumOverlapIndex(litPlacements = setOf(10, 20, 30))

        assertEquals(2, index.overlapCount(setOf(10, 20, 40)))
        assertEquals(0, index.overlapCount(emptySet()))
    }

    @Test
    fun `off admits everything`() {
        val index = QuantumOverlapIndex(litPlacements = setOf(1))

        assertTrue(index.matches(setOf(1), QuantumOverlapFilter.OFF))
        assertFalse(QuantumOverlapFilter.OFF.active)
    }

    @Test
    fun `an empty wall makes the filter inert rather than trivially true`() {
        val index = QuantumOverlapIndex.of(emptyList())

        assertTrue(index.inert, "narrowing nothing is not the same as narrowing")
        assertTrue(index.complete)
    }

    @Test
    fun `only confirmed layers light the index`() {
        val rack = listOf(
            confirmed(0, setOf(1, 2)),
            QuantumLaneOccupancy(
                lane = 1, source = QuantumLaneSource.PREVIEW, placements = setOf(99),
            ),
            QuantumLaneOccupancy(
                lane = 2, source = QuantumLaneSource.SENDING, placements = setOf(98),
            ),
            QuantumLaneOccupancy(
                lane = -1, source = QuantumLaneSource.FOREIGN, placements = setOf(3),
            ),
        )

        val index = QuantumOverlapIndex.of(rack)

        assertEquals(setOf(1, 2, 3), index.litPlacements)
        assertTrue(index.matches(setOf(99, 98), QuantumOverlapFilter.NONE), "plans hold nothing")
    }

    @Test
    fun `an unresolvable confirmed layer marks the index incomplete`() {
        val index = QuantumOverlapIndex.of(
            listOf(confirmed(0, setOf(1)), confirmed(1, null)),
        )

        assertFalse(index.complete, "a free send cannot be promised on partial knowledge")
        // It still narrows on what is known; it simply must not be presented
        // as a guarantee.
        assertEquals(setOf(1), index.litPlacements)
        assertFalse(index.matches(setOf(1), QuantumOverlapFilter.NONE))
    }

    @Test
    fun `an unknown wire value falls back to off rather than to a filter`() {
        assertEquals(QuantumOverlapFilter.OFF, QuantumOverlapFilter.fromWire(null))
        assertEquals(QuantumOverlapFilter.OFF, QuantumOverlapFilter.fromWire("SOMETHING_NEW"))
        assertEquals(QuantumOverlapFilter.NONE, QuantumOverlapFilter.fromWire("NONE"))
        assertEquals(QuantumOverlapFilter.AT_MOST_ONE, QuantumOverlapFilter.fromWire("AT_MOST_ONE"))
    }
}
