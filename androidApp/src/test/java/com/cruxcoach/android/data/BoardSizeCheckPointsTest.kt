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

    @Test fun `offered only for Aurora-protocol boards with more than one size`() {
        assertTrue(BoardSizeCheckPoints.offered(BoardBrand.KILTER, 2))
        assertTrue(BoardSizeCheckPoints.offered(BoardBrand.TENSION, 3))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.KILTER, 1))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.MOONBOARD, 5))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.QUANTUM, 5))
        assertFalse(BoardSizeCheckPoints.offered(BoardBrand.SHINYWALL, 5))
    }
}
