package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BoardSizeCheckSessionTest {

    private fun size(id: Int) = BoardSize(id.toLong(), 1L, "size $id", 0, 144, 0, 156, null)

    private class FakeSource(
        val sizeList: List<BoardSize>,
        var ready: Boolean = true,
        var lightWorks: Boolean = true,
    ) : BoardSizeCheckSession.Source {
        val lit = mutableListOf<List<Int>>()
        var clears = 0
        val applied = mutableListOf<Int>()
        override suspend fun sizes(brand: BoardBrand, sizeId: Int) = sizeList
        override suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?) = 1
        override suspend fun points(brand: BoardBrand, size: BoardSize, layoutId: Int?) =
            // Distinct LEDs per size, so a test can tell which size is on the board.
            (0 until 3).map { BoardSizeCheckPoint(it, it.toLong(), it.toLong(), size.id.toInt() * 100 + it) }
        override fun ready(brand: BoardBrand) = ready
        override suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean {
            assertTrue(leds.all { it.second == BoardSizeCheckPoints.LIGHT_COLOR })
            if (lightWorks) lit += leds.map { it.first }
            return lightWorks
        }
        override suspend fun clear(brand: BoardBrand): Boolean { clears++; return true }
        override suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int) { applied += sizeId }
    }

    @Test fun `opening lights the saved size when connected`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        val state = session.state.value
        assertEquals(BoardSizeCheckStep.ASK, state.step)
        assertEquals(10, state.size?.id?.toInt())
        assertTrue(state.lit)
        assertEquals(listOf(listOf(1000, 1001, 1002)), source.lit)
    }

    @Test fun `without a board nothing is sent until it connects`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)), ready = false)
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        assertTrue(source.lit.isEmpty())
        assertFalse(session.state.value.lit)
        source.ready = true
        session.lightIfDark()
        session.lightIfDark() // already lit: no second frame
        assertEquals(1, source.lit.size)
    }

    @Test fun `yes on the current size confirms without changing anything`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        assertEquals(10, session.confirm(applyChange = true))
        assertEquals(BoardSizeCheckStep.CONFIRMED, session.state.value.step)
        assertFalse(session.state.value.confirmedChange)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun `no, another size, yes applies the other size only on yes`() = runTest {
        val source = FakeSource(listOf(size(10), size(7), size(27)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        session.reject()
        assertEquals(BoardSizeCheckStep.PICK_OTHER, session.state.value.step)
        assertEquals(listOf(7, 27), session.state.value.otherSizes.map { it.id.toInt() })
        assertTrue(10 in session.state.value.tried)
        session.test(7)
        assertEquals(listOf(700, 701, 702), source.lit.last())
        assertEquals(BoardSizeCheckStep.ASK, session.state.value.step)
        assertTrue(source.applied.isEmpty())
        assertEquals(7, session.confirm(applyChange = true))
        assertTrue(session.state.value.confirmedChange)
        assertEquals(listOf(7), source.applied)
    }

    @Test fun `the picker gets the confirmed size and applies it itself`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        session.reject()
        session.test(7)
        assertEquals(7, session.confirm(applyChange = false))
        assertTrue(source.applied.isEmpty())
    }

    @Test fun `closing clears only what the check lit`() = runTest {
        val dark = FakeSource(listOf(size(10), size(7)), ready = false)
        BoardSizeCheckSession(dark).apply { open(BoardBrand.KILTER, 10, null); close() }
        assertEquals(0, dark.clears)

        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        session.close()
        session.close()
        assertEquals(1, source.clears)
        assertFalse(session.state.value.lit)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun `switching size without a board clears the old frame`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        source.ready = false
        session.reject()
        session.test(7)
        assertEquals(1, source.clears)
        assertFalse(session.state.value.lit)
        assertEquals(7, session.state.value.size?.id?.toInt())
    }

    @Test fun `a failed send is reported and blocks no retry`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)), lightWorks = false)
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        assertTrue(session.state.value.sendFailed)
        assertFalse(session.state.value.lit)
        source.lightWorks = true
        session.light()
        assertFalse(session.state.value.sendFailed)
        assertTrue(session.state.value.lit)
    }

    @Test fun `no sizes with LED data means no check`() = runTest {
        val source = FakeSource(emptyList())
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        assertEquals(BoardSizeCheckStep.NO_DATA, session.state.value.step)
        assertNull(session.confirm(applyChange = true))
        assertTrue(source.lit.isEmpty() && source.applied.isEmpty())
    }
}
