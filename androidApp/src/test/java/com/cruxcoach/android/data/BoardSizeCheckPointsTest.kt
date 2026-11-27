package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BoardSizeCheckPointsTest {

    private fun placement(id: Int, x: Long, y: Long) = BoardPlacement(id.toLong(), id.toLong(), 1L, x, y)

    /** 3 × 3 holds: a kickboard row at y = 0 and main rows at y = 10 and 20. */
    private val grid = listOf(
        placement(1, 0, 0), placement(2, 10, 0), placement(3, 20, 0),
        placement(4, 0, 10), placement(5, 10, 10), placement(6, 20, 10),
        placement(7, 0, 20), placement(8, 10, 20), placement(9, 20, 20),
    )

    @Test fun `picks the four corners, the top centre and the centre`() {
        val leds = grid.associate { it.placementId.toInt() to it.placementId.toInt() * 10 }
        val points = BoardSizeCheckPoints.pick(grid, leds)
        assertEquals(listOf(1, 3, 7, 9, 8, 5), points.map { it.placementId })
        assertEquals(listOf(10, 30, 70, 90, 80, 50), points.map { it.led })
    }

    @Test fun `ignores placements without an LED in the size under test`() {
        val farAway = placement(99, 500, 500)
        val leds = grid.associate { it.placementId.toInt() to 1 }
        val points = BoardSizeCheckPoints.pick(grid + farAway, leds)
        assertFalse(points.any { it.placementId == 99 })
    }

    @Test fun `ties go to the smallest placement id`() {
        val twins = listOf(placement(20, 0, 0), placement(10, 0, 0))
        val points = BoardSizeCheckPoints.pick(twins, mapOf(10 to 1, 20 to 2))
        assertEquals(listOf(10), points.map { it.placementId })
    }

    @Test fun `no placements with LEDs gives no points`() {
        assertTrue(BoardSizeCheckPoints.pick(grid, emptyMap()).isEmpty())
        assertTrue(BoardSizeCheckPoints.pick(emptyList(), mapOf(1 to 1)).isEmpty())
    }

    /**
     * Two sizes of one product that share the kickboard LEDs and the start of the first
     * column, like Kilter 12 × 12 with kickboard and 12 × 14: the corners alone would move
     * only one point, the six points move three.
     */
    @Test fun `a taller size with the same kickboard still moves at least two points`() {
        fun numbered(rows: Int): Map<Pair<Long, Long>, Int> {
            val map = mutableMapOf<Pair<Long, Long>, Int>()
            listOf(0L, 10L, 20L).forEachIndexed { i, x -> map[x to 0L] = i } // kickboard: LEDs 0..2
            var led = 3
            for (x in listOf(0L, 10L, 20L)) for (row in 1..rows) map[x to row * 10L] = led++
            return map
        }
        val short = numbered(rows = 2)
        val tall = numbered(rows = 3)
        val placements = short.keys.mapIndexed { i, (x, y) -> placement(i + 1, x, y) }
        val ledOf = placements.associate { it.placementId.toInt() to short.getValue(it.x to it.y) }
        val points = BoardSizeCheckPoints.pick(placements, ledOf)
        val tallHoleOfLed = tall.entries.associate { (xy, led) -> led to xy }
        val moved = points.count { tallHoleOfLed[it.led] != (it.x to it.y) }
        assertTrue(moved >= 2, "only $moved of ${points.size} points move")
    }

    /**
     * Like Tension Board 1: Half Kickboard is Full Wall without one kickboard hold, with the
     * same LED numbers. Full Wall's six points all sit on holds Half Kickboard has too.
     */
    private fun hold(id: Int, x: Long, y: Long, led: Int) = BoardSizeCheckPoint(id, x, y, led)
    private val halfKickboard = listOf(
        hold(1, 0, 10, 1), hold(2, 10, 10, 2), hold(3, 20, 10, 3),
        hold(4, 0, 20, 4), hold(5, 10, 20, 5), hold(6, 20, 20, 6),
        hold(7, 0, 30, 7), hold(8, 10, 30, 8), hold(9, 20, 30, 9),
    )
    private val kickboardHold = hold(10, 10, 5, 10)
    private val fullWall = halfKickboard + kickboardHold

    private fun movedOn(other: List<BoardSizeCheckPoint>, points: List<BoardSizeCheckPoint>): Int {
        val holdOfLed = other.associate { it.led to (it.x to it.y) }
        return points.count { holdOfLed[it.led] != (it.x to it.y) }
    }

    @Test fun `a size that lights the same six holds as a smaller kit gets a hold the kit lacks`() {
        assertEquals(0, movedOn(halfKickboard, BoardSizeCheckPoints.plan(fullWall, emptyMap()).points))
        val plan = BoardSizeCheckPoints.plan(fullWall, mapOf(2 to halfKickboard))
        assertTrue(kickboardHold in plan.points)
        assertEquals(1, movedOn(halfKickboard, plan.points))
        assertTrue(plan.lookAlikes.isEmpty())
    }

    @Test fun `an LED subset with the same numbers cannot be told apart from the larger kit`() {
        val plan = BoardSizeCheckPoints.plan(halfKickboard, mapOf(1 to fullWall))
        assertEquals(setOf(1), plan.lookAlikes)
        assertEquals(0, movedOn(fullWall, plan.points))
    }

    @Test fun `sizes the six points already tell apart get no extra hold`() {
        val renumbered = fullWall.map { it.copy(led = it.led + 100) }
        val plan = BoardSizeCheckPoints.plan(fullWall, mapOf(3 to renumbered))
        assertEquals(BoardSizeCheckPoints.plan(fullWall, emptyMap()).points, plan.points)
        assertTrue(plan.lookAlikes.isEmpty())
    }

    @Test fun `offered only for Aurora-protocol boards with more than one size`() {
        assertTrue(BoardSizeCheckPoints.offered(BoardBrand.KILTER, 2))
        assertTrue(BoardSizeCheckPoints.offered(BoardBrand.TENSION, 3))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.KILTER, 1))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.MOONBOARD, 5))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.QUANTUM, 5))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.SHINYWALL, 5))
    }
}
