package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The size check keeps the link while it runs and leaves the board dark, also after a drop. */
class BoardSizeCheckConnectionTest {

    private val board = DiscoveredBoard("Kilter Board#1", "1", 3, "AA:BB", -50)
    private val otherBoard = board.copy(address = "CC:DD")
    private val state = MutableStateFlow(ConnectionState.CONNECTED)
    private val descriptor = MutableStateFlow<DiscoveredBoard?>(board)
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val leds = listOf(1 to 0xFF)

    init {
        every { ble.connectionState } returns state
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { ble.connectedBoardDescriptor } returns descriptor
        coEvery { ble.sendRawLeds(any(), any()) } returns true
        coEvery { ble.clearBoard(any()) } returns true
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
        coVerify(exactly = 1) { ble.clearBoard(BoardBrand.KILTER) }
        verify(exactly = 0) { ble.connect(any(), any()) }
    }

    @Test fun `nothing lit means nothing to clear`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        assertTrue(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoard(any()) }
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
        coVerify(exactly = 1) { ble.clearBoard(BoardBrand.KILTER) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `a board that does not come back is given up on`() = runTest {
        val link = BoardSizeCheckConnection(ble, reconnectTimeoutMs = 1_000L)
        link.light(BoardBrand.KILTER, leds)
        dropLink()
        every { ble.connect(board, 1) } answers { state.value = ConnectionState.CONNECTING }
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoard(any()) }
        verify(exactly = 1) { ble.disconnect() }
    }

    @Test fun `another board connected meanwhile keeps what it shows`() = runTest {
        val link = BoardSizeCheckConnection(ble)
        link.light(BoardBrand.KILTER, leds)
        descriptor.value = otherBoard
        assertFalse(link.clear(BoardBrand.KILTER))
        coVerify(exactly = 0) { ble.clearBoard(any()) }
        verify(exactly = 0) { ble.connect(any(), any()) }
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
