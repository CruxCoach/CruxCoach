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
import kotlin.test.assertEquals
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
    private var time = 0L

    init {
        every { ble.connectionState } returns state
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { ble.connectedBoardDescriptor } returns descriptor
        every { ble.boardWrites } returns writes
        coEvery { ble.sendRawFrame(any(), any()) } answers { BoardWrite(true, ++writes.value) }
        // As under the write lock: a clear for a count that has moved on writes nothing.
        coEvery { ble.clearBoardWrite(any(), any()) } answers {
            val ifLastWrite = secondArg<Long?>()
            if (ifLastWrite != null && ifLastWrite != writes.value) BoardWrite.NONE else BoardWrite(true, ++writes.value)
        }
        every { ble.hasOtherKeepAliveOwners(any()) } returns false
    }

    private fun link(reconnectTimeoutMs: Long = BoardSizeCheckConnection.RECONNECT_TIMEOUT_MS) =
        BoardSizeCheckConnection(ble, reconnectTimeoutMs, now = { time })

    private fun dropLink() {
        state.value = ConnectionState.DISCONNECTED
        descriptor.value = null
    }

    @Test fun `the link is held for the check and let go afterwards`() {
        val link = link()
        link.hold()
        link.release()
        verify { ble.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        verify { ble.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
    }

    @Test fun `closing clears the board the check lit`() = runTest {
        val link = link()
        assertTrue(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `nothing lit means nothing to clear`() = runTest {
        val link = link()
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `after a dropped link it reconnects once, clears and disconnects again`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `a board that does not come back is given up on`() = runTest {
        val link = link(reconnectTimeoutMs = 1_000L)
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers { state.value = ConnectionState.CONNECTING }
        every { ble.disconnect() } answers { dropLink() }
        assertTrue(link.clear(BoardBrand.KILTER), "nothing is left for a later close")
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
        verify(exactly = 1) { ble.disconnect() }

        assertTrue(link.clear(BoardBrand.KILTER))
        verify(exactly = 1) { ble.connect(any(), any()) }
    }

    @Test fun `another board connected meanwhile keeps what it shows`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `it knows whether its frame is still on the board`() = runTest {
        val link = link()
        assertFalse(link.showsCheckFrame())
        link.light(BoardBrand.KILTER, leds)
        assertTrue(link.showsCheckFrame())
        writes.value++ // a climb, the relay, a clear
        assertFalse(link.showsCheckFrame())
    }

    @Test fun `a board sent over since keeps the other sender's frame`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        writes.value++ // a session sends its climb
        assertTrue(link.clear(BoardBrand.KILTER), "nothing of the check is left to clear")
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `a board sent over before the link dropped is not reconnected to`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        writes.value++
        link.noteWrites() // the view model notes every write as it happens
        dropLink()
        assertTrue(link.clear(BoardBrand.KILTER))
        verify(exactly = 0) { ble.connect(any(), any()) }
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `a send that failed half way is cleared`() = runTest {
        coEvery { ble.sendRawFrame(any(), any()) } answers { BoardWrite(false, ++writes.value) }
        val link = link()
        assertFalse(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.showsCheckFrame(), "what the board shows is the check's")
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
    }

    @Test fun `a send that never reached the board leaves nothing to clear`() = runTest {
        coEvery { ble.sendRawFrame(any(), any()) } returns BoardWrite.NONE
        val link = link()
        assertFalse(link.light(BoardBrand.KILTER, leds))
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `a write to another board leaves the first one to be cleared`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard
        writes.value++ // the neighbouring wall shows a climb
        link.noteWrites()
        descriptor.value = board // back on the first wall
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
    }

    @Test fun `a clear that failed is not tried again by a later close`() = runTest {
        coEvery { ble.clearBoardWrite(any(), any()) } answers { BoardWrite(false, ++writes.value) }
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        assertTrue(link.clear(BoardBrand.KILTER), "nothing is left for a later close")
        assertTrue(link.showsCheckFrame(), "the failed clear was the check's own write")
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
    }

    @Test fun `the check's own clear is not someone else's frame`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        assertTrue(link.clear(BoardBrand.KILTER))
        assertTrue(link.showsCheckFrame())
        writes.value++
        assertFalse(link.showsCheckFrame())
    }

    @Test fun `closing while the board reconnects waits for it and clears it`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        state.value = ConnectionState.CONNECTING
        val cleared = async { link.clear(BoardBrand.KILTER) }
        runCurrent()
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
        state.value = ConnectionState.CONNECTED
        assertTrue(cleared.await())
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 0) { ble.disconnect() } // the user asked for that connection
    }

    @Test fun `a board lit before the link moved is cleared once the link is free`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard // dropped, then the user connected the neighbouring wall
        link.light(BoardBrand.KILTER, leds)
        assertFalse(link.clear(BoardBrand.KILTER), "the first wall cannot be reached without dropping the second")
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 0) { ble.connect(any(), any()) }

        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        every { ble.disconnect() } answers { dropLink() }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 2) { ble.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 0) { ble.connect(otherBoard, any()) }
    }

    @Test fun `a session that wants the board after the reconnect keeps it connected`() = runTest {
        val link = link()
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

    @Test fun `a frame sent the moment the link is up again is not wiped`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        state.value = ConnectionState.CONNECTING
        val cleared = async { link.clear(BoardBrand.KILTER) }
        runCurrent()
        state.value = ConnectionState.CONNECTED
        writes.value++ // a session sends its climb before the clear gets the write lock
        assertTrue(cleared.await(), "the board is not the check's any more")
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, 1L) }
        assertEquals(2L, writes.value, "nothing was written over the session's climb")
    }

    @Test fun `a frame sent right after the reconnect is not wiped`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
            writes.value++ // the relay sends its climb on connect
        }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, 1L) }
        assertEquals(2L, writes.value)
    }

    @Test fun `a board gone for longer than the limit is not reconnected to`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        link.noteLink() // the view model notes every link change as it happens
        time = BoardSizeCheckConnection.GONE_LIMIT_MS + 1 // another phone may have used it meanwhile
        assertTrue(link.clear(BoardBrand.KILTER))
        verify(exactly = 0) { ble.connect(any(), any()) }
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `a board gone for less than the limit is reconnected to and cleared`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        link.noteLink()
        time = BoardSizeCheckConnection.GONE_LIMIT_MS - 1
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, 1L) }
    }

    @Test fun `a board back after the limit is not cleared either`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        link.noteLink()
        time = BoardSizeCheckConnection.GONE_LIMIT_MS + 1
        descriptor.value = board // the user connected it again
        state.value = ConnectionState.CONNECTED
        link.noteLink()
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
    }

    @Test fun `a board back within the limit keeps its points marked`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        link.noteLink()
        time = 10_000L
        descriptor.value = board
        state.value = ConnectionState.CONNECTED
        link.noteLink()
        time = 10_000L + BoardSizeCheckConnection.GONE_LIMIT_MS + 1 // the link stayed up since
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, 1L) }
    }

    @Test fun `in the background the board is disconnected and connected again on return`() {
        val link = link()
        link.hold()
        every { ble.disconnect() } answers { dropLink() }
        link.pause()
        verify(exactly = 1) { ble.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        verify(exactly = 1) { ble.disconnect() }
        link.resume()
        verify(exactly = 2) { ble.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        verify(exactly = 1) { ble.connect(board, any()) }
    }

    @Test fun `a session or the relay keeps its link in the background`() {
        every { ble.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK) } returns true
        val link = link()
        link.hold()
        link.pause()
        link.resume()
        verify(exactly = 0) { ble.disconnect() }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `a link made while the app was away stays as it is`() {
        val link = link()
        every { ble.disconnect() } answers { dropLink() }
        link.pause()
        descriptor.value = otherBoard
        state.value = ConnectionState.CONNECTED
        link.resume()
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `closing in the background forgets the board to connect again`() {
        val link = link()
        every { ble.disconnect() } answers { dropLink() }
        link.pause()
        link.release() // the sheet closed
        link.resume()
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `the limit counts at the moment of the clear`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        link.noteLink()
        time = BoardSizeCheckConnection.GONE_LIMIT_MS - 2_000L
        every { ble.connect(board, 1) } answers {
            time += 5_000L // the reconnect takes a while
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoardWrite(any(), any()) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `a connect of the user's that fails leaves the board to the reconnect`() = runTest {
        val link = link()
        link.light(BoardBrand.KILTER, leds)
        state.value = ConnectionState.CONNECTING
        val cleared = async { link.clear(BoardBrand.KILTER) }
        runCurrent()
        every { ble.connect(board, 1) } answers {
            descriptor.value = board
            state.value = ConnectionState.CONNECTED
        }
        dropLink() // the user's connect gave up
        assertTrue(cleared.await())
        verify(exactly = 1) { ble.connect(board, 1) }
        coVerify(exactly = 1) { ble.clearBoardWrite(BoardBrand.KILTER, 1L) }
    }

    @Test fun `back after the limit, the board may show something else`() {
        val link = link()
        every { ble.disconnect() } answers { dropLink() }
        link.pause()
        time = BoardSizeCheckConnection.GONE_LIMIT_MS + 1
        assertFalse(link.resume())
        verify(exactly = 1) { ble.connect(board, any()) }

        state.value = ConnectionState.CONNECTED
        descriptor.value = board
        link.pause()
        time += BoardSizeCheckConnection.GONE_LIMIT_MS
        assertTrue(link.resume())
    }
}
