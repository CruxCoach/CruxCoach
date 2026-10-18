package com.cruxcoach.app.render

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuantumRenderTest {
    private fun size(id: Long, brand: BoardBrand = BoardBrand.QUANTUM) =
        BoardSize(id, 91, "Quantum", 0, 12_000, 0, 12_000, null, brand)

    @Test
    fun `normalized points are the phone canvas mapping divided by its width`() {
        for (model in QuantumBoardModel.entries) {
            for ((x, y) in listOf(50_000L to 50_000L, 10_000L to 90_000L, 68_000L to 50_000L, 3_500L to 12_250L)) {
                val canvas = quantumBoardPoint(model, x, y, 393f, compactPhone = false, tablet = false)
                val unit = QuantumRender.normalized(model, x, y)
                assertEquals(canvas.x / 393f, unit.x, absoluteTolerance = 1e-5f, message = "$model x")
                assertEquals(canvas.y / 393f, unit.y, absoluteTolerance = 1e-5f, message = "$model y")
            }
        }
        // The Android fixture point (400 px, centre of the XL) in unit space.
        val xl = QuantumRender.normalized(QuantumBoardModel.XL, 50_000, 50_000)
        assertEquals(210.39186f / 400f, xl.x, absoluteTolerance = 1e-5f)
        assertEquals(183.37204f / 400f, xl.y, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `the model comes from the product size first and the layout second`() {
        assertEquals(QuantumBoardModel.M, QuantumRender.model(size(9203), 9101))
        assertEquals(QuantumBoardModel.S, QuantumRender.model(null, 9104))
        assertEquals(QuantumBoardModel.BELAY, QuantumRender.model(size(1), 9105))
        assertNull(QuantumRender.model(size(9203, BoardBrand.KILTER), 9103), "another brand's size is never Quantum")
        assertNull(QuantumRender.model(null, 1))
    }

    @Test
    fun `the zone box is the bounding box of the four mapped corners`() {
        val zone = BoardZone(minX = 20_000, maxX = 60_000, minY = 30_000, maxY = 80_000)
        val rect = QuantumRender.zoneRect(QuantumBoardModel.BELAY, zone)
        val corners = listOf(20_000L to 30_000L, 20_000L to 80_000L, 60_000L to 30_000L, 60_000L to 80_000L)
            .map { (x, y) -> QuantumRender.normalized(QuantumBoardModel.BELAY, x, y) }
        assertEquals(listOf(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y }), rect)
        assertTrue(rect[0] < rect[2] && rect[1] < rect[3])
    }

    @Test
    fun `points off the square image are not visible`() {
        assertTrue(QuantumRender.isVisible(BoardPoint(0f, 1f)))
        assertFalse(QuantumRender.isVisible(BoardPoint(-0.01f, 0.5f)))
        assertFalse(QuantumRender.isVisible(BoardPoint(0.5f, 1.01f)))
    }
}
