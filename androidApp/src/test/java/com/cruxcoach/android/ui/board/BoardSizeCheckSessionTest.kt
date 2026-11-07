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
        val holdsBySize: Map<Int, List<BoardSizeCheckPoint>> = emptyMap(),
    ) : BoardSizeCheckSession.Source {
        val lit = mutableListOf<List<Int>>()
        var clears = 0
        val applied = mutableListOf<Int>()
        override suspend fun sizes(brand: BoardBrand, sizeId: Int) = sizeList
        override suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?) = 1
        override suspend fun holds(brand: BoardBrand, size: BoardSize, layoutId: Int?) =
            holdsBySize[size.id.toInt()]
                // Distinct LEDs per size, so a test can tell which size is on the board.
                ?: (0 until 3).map { BoardSizeCheckPoint(it, it.toLong(), it.toLong(), size.id.toInt() * 100 + it) }
        override fun ready(brand: BoardBrand) = ready
        override suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean {
            assertTrue(leds.all { it.second == BoardSizeCheckPoints.LIGHT_COLOR })
            if (lightWorks) lit += leds.map { it.first }.sorted()
            return lightWorks
        }
        override suspend fun clear(brand: BoardBrand): Boolean { clears++; return true }
        override suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int) { applied += sizeId }
    }

    /** Tension Board 1 in small: Half Kickboard (2) and No Kickboard (3) are Full Wall (1) minus rows, same LEDs. */
    private fun hold(id: Int, x: Long, y: Long) = BoardSizeCheckPoint(id, x, y, id)
    private val noKickboard = (1..9).map { hold(it, (it - 1) % 3 * 10L, 20L + (it - 1) / 3 * 10L) }
    private val halfKickboard = noKickboard + listOf(hold(10, 0, 10), hold(11, 20, 10))
    private val fullWall = halfKickboard + hold(12, 10, 5)
    private fun tension(ready: Boolean = true) = FakeSource(
        listOf(size(1), size(2), size(3)), ready = ready,
        holdsBySize = mapOf(1 to fullWall, 2 to halfKickboard, 3 to noKickboard),
    )

    @Test fun `opening lights the saved size when connected`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        val state = session.state.value
        assertEquals(BoardSizeCheckStep.ASK, state.step)
        assertEquals(10, state.size?.id?.toInt())
        assertTrue(state.lit)
        assertEquals(listOf(listOf(1000, 1001, 1002)), source.lit)
        assertNull(state.startsWithLookAlike)
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

    @Test fun `switching size without a board leaves the old frame for closing to clear`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        session.reject()
        source.ready = false
        session.boardGone()
        session.test(7)
        assertEquals(0, source.clears)
        assertFalse(session.state.value.lit)
        assertEquals(7, session.state.value.size?.id?.toInt())
        session.close()
        assertEquals(1, source.clears, "the link dropped, but the frame is still on the board")
    }

    @Test fun `after the link drops the holds are lit again once the board is back`() = runTest {
        val source = FakeSource(listOf(size(10), size(7)))
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.KILTER, 10, null)
        source.ready = false
        session.boardGone()
        assertFalse(session.state.value.canConfirm && session.state.value.lit)
        source.ready = true
        session.lightIfDark()
        assertEquals(2, source.lit.size)
        assertTrue(session.state.value.lit)
    }

    @Test fun `a larger kit with the same LEDs is ruled out before a smaller size can be confirmed`() = runTest {
        val source = tension()
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.TENSION, 2, null)
        val start = session.state.value
        assertEquals(1, start.size?.id?.toInt(), "Half Kickboard starts with Full Wall")
        assertEquals(2, start.startsWithLookAlike?.id?.toInt())
        assertTrue(12 in start.points.map { it.placementId }, "Full Wall lights a hold Half Kickboard lacks")
        assertTrue(start.otherSizes.isEmpty(), "both smaller kits wait until Full Wall is ruled out")
        session.test(2) // not yet: Full Wall is still open
        assertEquals(1, session.state.value.size?.id?.toInt())

        session.reject()
        assertEquals(listOf(2), session.state.value.otherSizes.map { it.id.toInt() })
        assertNull(session.state.value.startsWithLookAlike)
        session.test(2)
        assertTrue(session.state.value.canConfirm)
        assertEquals(2, session.confirm(applyChange = true))
        assertFalse(session.state.value.confirmedChange)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun `the smallest kit is offered once both larger ones are ruled out`() = runTest {
        val source = tension()
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.TENSION, 3, null)
        assertEquals(1, session.state.value.size?.id?.toInt())
        session.reject()
        session.test(2)
        session.reject()
        assertEquals(listOf(1, 3), session.state.value.otherSizes.map { it.id.toInt() })
        session.test(3)
        assertEquals(3, session.confirm(applyChange = true))
    }

    @Test fun `before the larger kit is ruled out, yes can only confirm the kit`() = runTest {
        val source = tension()
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.TENSION, 2, null)
        session.test(2) // Half Kickboard while Full Wall is open: refused
        assertEquals(1, session.state.value.size?.id?.toInt())
        assertEquals(1, session.confirm(applyChange = true), "the board lit Full Wall's holds, its kickboard hold too")
        assertEquals(listOf(1), source.applied)
    }

    @Test fun `a no to holds that are not lit rules nothing out`() = runTest {
        val source = tension().apply { lightWorks = false }
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.TENSION, 2, null)
        session.reject()
        assertEquals(BoardSizeCheckStep.ASK, session.state.value.step)
        assertTrue(session.state.value.rejected.isEmpty())
        assertTrue(session.state.value.otherSizes.isEmpty(), "Half Kickboard stays locked")
        assertNull(session.confirm(applyChange = true))

        source.lightWorks = true
        session.light()
        session.reject()
        assertEquals(listOf(2), session.state.value.otherSizes.map { it.id.toInt() })
    }

    @Test fun `another frame over the holds blocks the answer until they are lit again`() = runTest {
        val source = tension()
        val session = BoardSizeCheckSession(source)
        session.open(BoardBrand.TENSION, 2, null)
        session.replaced()
        assertFalse(session.state.value.lit)
        assertTrue(session.state.value.replaced)
        session.reject()
        assertTrue(session.state.value.rejected.isEmpty())
        assertNull(session.confirm(applyChange = true))

        session.light()
        assertTrue(session.state.value.lit)
        assertFalse(session.state.value.replaced)
        session.reject()
        assertEquals(BoardSizeCheckStep.PICK_OTHER, session.state.value.step)
    }

    @Test fun `closing leaves nothing of the finished check for the next sheet`() = runTest {
        val session = BoardSizeCheckSession(FakeSource(listOf(size(10), size(7))))
        session.open(BoardBrand.KILTER, 10, null)
        session.confirm(applyChange = true)
        session.close()
        val state = session.state.value
        assertEquals(BoardSizeCheckStep.LOADING, state.step)
        assertNull(state.confirmedSizeId)
        assertNull(state.size)
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
