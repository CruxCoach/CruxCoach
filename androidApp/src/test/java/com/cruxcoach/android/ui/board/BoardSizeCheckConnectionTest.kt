package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BoardWrite
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The size check keeps the link while it runs and leaves the board dark, also after a drop. */
class BoardSizeCheckConnectionTest {

    private val board = DiscoveredBoard("Kilter Board#1", "1", 3, "AA:BB", -50)
    private val otherBoard = board.copy(address = "CC:DD")
    private val state = MutableStateFlow(ConnectionState.CONNECTED)
    private val descriptor = MutableStateFlow<DiscoveredBoard?>(board)
    private val writes = MutableStateFlow(0L)
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val leds = listOf(1 to 0xFF)

    init {
        every { ble.connectionState } returns state
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { ble.connectedBoardDescriptor } returns descriptor
        every { ble.boardWrites } returns writes
        coEvery { ble.sendRawFrame(any(), any()) } answers { BoardWrite(true, ++writes.value) }
        coEvery { ble.clearBoardWrite(any()) } answers { BoardWrite(true, ++writes.value) }
        every { ble.hasOtherKeepAliveOwners(any()) } returns false
    }

    private fun dropLink() {
        state.value = ConnectionState.DISCONNECTED
        descriptor.value = null
    }

    @Test fun `the link is held for the check and let go afterwards`() {
        val link = BoardSizeCheckConnection(ble)
        link.hold()
        link.release()
        verify { ble.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        verify { ble.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
    }

    @Test fun `closing clears the board the check lit`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        assertTrue(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `nothing lit means nothing to clear`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
    }

    @Test fun `after a dropped link it reconnects once, clears and disconnects again`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `a board that does not come back is given up on`() = runTest {
        val link = BoardSizeCheckConnection(ble, reconnectTimeoutMs = 1_000L)
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers { state.value = ConnectionState.CONNECTING }
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `another board connected meanwhile keeps what it shows`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `it knows whether its frame is still on the board`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        assertFalse(link.showsCheckFrame())
        link.light(BoardBrand.KILTER, leds)
        assertTrue(link.showsCheckFrame())
        writes.value++ // a climb, the relay, a clear
        assertFalse(link.showsCheckFrame())
    }

    @Test fun `a board sent over since keeps the other sender's frame`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        writes.value++ // a session sends its climb
        assertTrue(link.clear(BoardBrand.KILTER), "nothing of the check is left to clear")
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
    }

    @Test fun `a board sent over before the link dropped is not reconnected to`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        writes.value++
        link.noteWrites() // the view model notes every write as it happens
        dropLink()
        assertTrue(link.clear(BoardBrand.KILTER))
        verify(exactly = 0) { ble.connect(any(), any()) }
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
    }

    @Test fun `a send that failed half way is cleared`() = runTest {
        coEvery { ble.sendRawFrame(any(), any()) } answers { BoardWrite(false, ++writes.value) }
        val link = BoardSizeCheckConnection(ble)
        assertFalse(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.showsCheckFrame(), "what the board shows is the check's")
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
    }

    @Test fun `a send that never reached the board leaves nothing to clear`() = runTest {
        coEvery { ble.sendRawFrame(any(), any()) } returns BoardWrite.NONE
        val link = BoardSizeCheckConnection(ble)
        assertFalse(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
    }

    @Test fun `a write to another board leaves the first one to be cleared`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard
        writes.value++ // the neighbouring wall shows a climb
        link.noteWrites()
        descriptor.value = board // back on the first wall
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
    }

    @Test fun `a clear that failed is tried again, unless the board was sent over since`() = runTest {
        coEvery { ble.clearBoardWrite(any()) } answers { BoardWrite(false, ++writes.value) }
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        assertFalse(link.clear(BoardBrand.KILTER))
        assertTrue(link.showsCheckFrame(), "the failed clear was the check's own write")
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 2) { ble.clearBoardWrite(BoardBrand.KILTER) }

        writes.value++ // the phone was locked, a session sent its climb
        link.noteWrites()
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 2) { ble.clearBoardWrite(BoardBrand.KILTER) }
    }

    @Test fun `the check's own clear is not someone else's frame`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        assertTrue(link.clear(BoardBrand.KILTER))
        assertTrue(link.showsCheckFrame())
        writes.value++
        assertFalse(link.showsCheckFrame())
    }

    @Test fun `closing while the board reconnects waits for it and clears it`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        state.value = ConnectionState.CONNECTING
        val cleared = async { link.clear(BoardBrand.KILTER) }
        runCurrent()
        coVerify(exactly = 0) { ble.clearBoardWrite(any()) }
        state.value = ConnectionState.CONNECTED
        assertTrue(cleared.await())
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
        verify(exactly = 0) { ble.disconnect() } // the user asked for that connection
    }

    @Test fun `a board lit before the link moved is cleared once the link is free`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard // dropped, then the user connected the neighbouring wall
        link.light(BoardBrand.KILTER, leds)
        assertFalse(link.clear(BoardBrand.KILTER), "the first wall cannot be reached without dropping the second")
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER) }
        verify(exactly = 0) { ble.connect(any(), any()) }

        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        every { ble.disconnect() } answers { dropLink() }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 2) { ble.clearBoardWrite(BoardBrand.KILTER) }
        verify(exactly = 0) { ble.connect(otherBoard, any()) }
    }

    @Test fun `a session that wants the board after the reconnect keeps it connected`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        every { ble.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK) } returns true
        assertTrue(link.clear(BoardBrand.KILTER))
        verify(exactly = 0) { ble.disconnect() }
    }
}
