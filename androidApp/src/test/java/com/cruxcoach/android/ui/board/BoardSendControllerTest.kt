package com.cruxcoach.android.ui.board

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoardSendControllerTest {

    private val holds = listOf(BoardHold(10, 12), BoardHold(20, 13))
    private val climb = ClimbWithStats(
        uuid = "climb-1",
        layoutId = 1,
        setterUsername = "setter",
        name = "Send me",
        frames = "p10r12p20r13",
        framesCount = 1,
        difficultyAverage = 17.5,
        qualityAverage = 2.0,
        ascensionistCount = 3,
        boardBrand = "kilter",
    )

    @Test
    fun `successful send crosses repository BLE history and advertising boundaries`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val uiState = MutableStateFlow(
            ClimbDetailState(
                climb = climb,
                angle = 40,
                holds = holds,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            ),
        )
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getPlacementLedMap(1, "kilter") } returns mapOf(10 to 100)
            every { getRoleColorMapForBrand("kilter") } returns mapOf(12 to 0x1c, 13 to 0x03)
        }
        val personal = mockk<PersonalBoardRepository>(relaxed = true)
        val connection = mockk<BoardBleConnection>(relaxed = true) {
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
            coEvery { sendClimb(any(), any(), any()) } returns true
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf("kilter")
            every { boardProductSizeId } returns flowOf(1)
        }
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        val queue = mockk<SessionQueueManager>(relaxed = true) {
            every { state } returns MutableStateFlow(SessionQueueState())
        }
        val controller = BoardSendController(
            scope = this,
            state = uiState,
            boardRepository = repository,
            personalBoardRepo = personal,
            bleConnection = connection,
            userPreferences = preferences,
            climbAdvertiser = advertiser,
            sessionQueueManager = queue,
            isSharingEnabled = { true },
            ioDispatcher = dispatcher,
            nowIso = { "2026-07-16T12:00:00" },
        )

        controller.sendToBoard()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            connection.sendClimb(
                holds,
                mapOf(10 to 100),
                mapOf(12 to 0x1c, 13 to 0x03),
            )
            personal.recordClimbHistory(
                climbUuid = "climb-1",
                climbName = "Send me",
                angle = 40,
                difficultyAverage = 17.5,
                boardBrand = "kilter",
                layoutId = 1,
                climbedAt = "2026-07-16T12:00:00",
                recordedAt = "2026-07-16T12:00:00",
            )
        }
        verify(exactly = 1) { advertiser.advertiseClimb("climb-1", 40, true) }
        assertTrue(uiState.value.ble.success)
        assertFalse(uiState.value.ble.isSending)
        assertEquals(R.string.board_send_warning_holds_not_lit, uiState.value.ble.warning)
        assertEquals(null, uiState.value.ble.error)
    }

    @Test
    fun `all holds unmapped refuses empty frame before BLE write`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val uiState = MutableStateFlow(
            ClimbDetailState(
                climb = climb,
                angle = 40,
                holds = holds,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            ),
        )
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getPlacementLedMap(1, "kilter") } returns mapOf(99 to 999)
            every { getRoleColorMapForBrand("kilter") } returns emptyMap()
        }
        val personal = mockk<PersonalBoardRepository>(relaxed = true)
        val connection = mockk<BoardBleConnection>(relaxed = true) {
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf("kilter")
            every { boardProductSizeId } returns flowOf(1)
            every { ledHoldColors } returns flowOf(com.cruxcoach.android.data.LedHoldColors())
        }
        val queue = mockk<SessionQueueManager>(relaxed = true) {
            every { state } returns MutableStateFlow(SessionQueueState())
        }
        val controller = BoardSendController(
            scope = this,
            state = uiState,
            boardRepository = repository,
            personalBoardRepo = personal,
            bleConnection = connection,
            userPreferences = preferences,
            climbAdvertiser = mockk(relaxed = true),
            sessionQueueManager = queue,
            isSharingEnabled = { false },
            ioDispatcher = dispatcher,
        )

        controller.sendToBoard()
        advanceUntilIdle()

        coVerify(exactly = 0) { connection.sendClimb(any(), any(), any()) }
        coVerify(exactly = 0) { personal.recordClimbHistory(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(R.string.board_send_error_climb_off_board, uiState.value.ble.error)
        assertFalse(uiState.value.ble.isSending)
        assertFalse(uiState.value.ble.success)
    }
}
