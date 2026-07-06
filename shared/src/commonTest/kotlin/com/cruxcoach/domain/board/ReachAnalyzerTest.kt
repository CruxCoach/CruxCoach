package com.cruxcoach.domain.board

import com.cruxcoach.domain.board.ReachAnalyzer.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parity contract with the blossom-sync cron's reach_metric.py
 * PARITY_FIXTURES — identical inputs MUST produce identical outputs in
 * both codebases. Change them together or not at all.
 */
class ReachAnalyzerTest {

    @Test
    fun `parity fixtures match the cron implementation`() {
        assertNull(ReachAnalyzer.mstBottleneckGap(emptyList()))
        assertNull(ReachAnalyzer.mstBottleneckGap(listOf(Point(4.0, 4.0))))
        // Single gap.
        assertEquals(5.0, ReachAnalyzer.mstBottleneckGap(listOf(Point(0.0, 0.0), Point(3.0, 4.0)))!!, 1e-9)
        // Even ladder.
        assertEquals(
            4.0,
            ReachAnalyzer.mstBottleneckGap(listOf(Point(0.0, 0.0), Point(0.0, 4.0), Point(0.0, 8.0)))!!,
            1e-9,
        )
        // Isolated top hold.
        assertEquals(
            8.0,
            ReachAnalyzer.mstBottleneckGap(listOf(Point(0.0, 0.0), Point(0.0, 1.0), Point(0.0, 9.0)))!!,
            1e-9,
        )
        // Two tight pairs, big span — the case plain nearest-neighbour
        // gets wrong (it scores the 1.0 pair distance, not the move).
        assertEquals(
            10.0,
            ReachAnalyzer.mstBottleneckGap(
                listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 10.0), Point(1.0, 10.0))
            )!!,
            1e-9,
        )
    }

    @Test
    fun `climb reach takes the max across frames and skips empty ones`() {
        assertEquals(
            6.0,
            ReachAnalyzer.climbReach(
                listOf(
                    listOf(Point(0.0, 0.0), Point(0.0, 4.0)),
                    listOf(Point(0.0, 0.0), Point(0.0, 6.0)),
                )
            )!!,
            1e-9,
        )
        assertNull(ReachAnalyzer.climbReach(listOf(emptyList(), listOf(Point(1.0, 1.0)))))
    }
}
