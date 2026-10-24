package com.cruxcoach.android.ui.board

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardCleaningState
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.LoggedHolds
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardCleaningViewModelTest {
    private val board = DiscoveredBoard("Kilter Board#1@3", "1", 3, "00:11:22:33:44:55", -40)
    private val entries = listOf(LoggedHolds(LocalDateTime.parse("2026-10-10T10:00"), setOf(5, 8)))
    private val relayState = MutableStateFlow(CruxRelayState())
    private val connection = mockk<BoardBleConnection>(relaxed = true) {
        every { cleaningState } returns MutableStateFlow(BoardCleaningState(available = true))
        every { connectedBoardDescriptor } returns MutableStateFlow(board)
        every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { cleaningTicket() } returns 7L
        coEvery { startCleaning(any()) } returns true
    }
    private val relay = mockk<CruxRelayManager> { every { state } returns relayState }
    private val log = mockk<BoardCleaningLog> { coEvery { load(any(), any()) } returns entries }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BoardCleaningViewModel(connection, relay, log)

    @Test fun `refresh hands today's logbook to the connection it was read for`() {
        viewModel().refresh()
        val today = LocalDate.now().toString()
        coVerifyOrder {
            connection.cleaningTicket()
            log.load(board, today)
            connection.updateCleaningLog(7L, today, entries)
        }
    }

    @Test fun `starting reads the logbook again before lighting`() {
        val viewModel = viewModel()
        viewModel.start()
        coVerifyOrder {
            log.load(board, any())
            connection.updateCleaningLog(7L, any(), entries)
            connection.startCleaning(board.address)
        }
        assertNull(viewModel.error.value)
    }

    @Test fun `board sharing blocks starting without a write`() {
        relayState.value = CruxRelayState(enabled = true)
        val viewModel = viewModel()
        viewModel.start()
        coVerify(exactly = 0) { connection.startCleaning(any()) }
        assertEquals(R.string.board_cleaning_error, viewModel.error.value)
    }

    @Test fun `a failed logbook read keeps the last collection`() {
        coEvery { log.load(any(), any()) } throws IllegalStateException("database closed")
        viewModel().refresh()
        verify(exactly = 0) { connection.updateCleaningLog(any(), any(), any()) }
    }
}
