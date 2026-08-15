package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.boardcell.BoardCellWriteGateway
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardSendControllerTest {

    private val moonClimb = ClimbWithStats(
        uuid = "moon-climb-1",
        layoutId = MoonBoardVariant.MOONBOARD_2016.layoutId,
        setterUsername = "setter",
        name = "Moon test",
        frames = "p1r42p2r43p3r44",
        framesCount = 1,
        difficultyAverage = 10.0,
        qualityAverage = null,
        ascensionistCount = null,
        boardBrand = BoardBrand.MOONBOARD.wireValue,
    )

    @Test
    fun `successful MoonBoard send applies LED mode and records volatile last climb`() = runTest {
        val climb = moonClimb
        val state = MutableStateFlow(
            ClimbDetailState(
                isLoading = false,
                climb = climb,
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            )
        )
        val bleConnection = mockk<BoardBleConnection>(relaxed = true) {
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.MOONBOARD)
            coEvery { sendMoonBoardClimb(any(), any(), any()) } returns true
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf(BoardBrand.MOONBOARD.wireValue)
            every { boardLayoutId } returns flowOf(MoonBoardVariant.MOONBOARD_2016.layoutId.toInt())
            every { moonBoardLedMode } returns flowOf(MoonBoardLedMode.ABOVE)
        }
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true) {
            every { advertiseClimb(any(), any(), any(), any()) } returns "started"
        }
        val queueManager = mockk<SessionQueueManager>(relaxed = true)
        every { queueManager.state } returns MutableStateFlow(SessionQueueState())
        val controller = BoardSendController(
            scope = this,
            state = state,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true),
            bleConnection = bleConnection,
            userPreferences = preferences,
            climbAdvertiser = advertiser,
            sessionQueueManager = queueManager,
            isSharingEnabled = { true },
            boardCellWriteGateway = BoardCellWriteGateway { _, write -> write() },
        )

        controller.sendToBoard()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            bleConnection.sendMoonBoardClimb(
                climb.frames,
                MoonBoardVariant.MOONBOARD_2016,
                MoonBoardLedMode.ABOVE,
            )
        }
        verify(exactly = 1) {
            advertiser.advertiseClimb(
                climbUuid = climb.uuid,
                angle = 40,
                sharingEnabled = true,
                projectionSurvivesDisconnect = false,
            )
        }
    }

    @Test
    fun `failed MoonBoard send is not recorded as projected`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow(
            ClimbDetailState(
                isLoading = false,
                climb = moonClimb,
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            )
        )
        val bleConnection = mockk<BoardBleConnection>(relaxed = true) {
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.MOONBOARD)
            coEvery { sendMoonBoardClimb(any(), any(), any()) } returns false
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf(BoardBrand.MOONBOARD.wireValue)
            every { boardLayoutId } returns flowOf(MoonBoardVariant.MOONBOARD_2016.layoutId.toInt())
            every { moonBoardLedMode } returns flowOf(MoonBoardLedMode.BELOW)
        }
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        val queueManager = mockk<SessionQueueManager>(relaxed = true)
        every { queueManager.state } returns MutableStateFlow(SessionQueueState())
        val controller = BoardSendController(
            scope = this,
            state = state,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true),
            bleConnection = bleConnection,
            userPreferences = preferences,
            climbAdvertiser = advertiser,
            sessionQueueManager = queueManager,
            isSharingEnabled = { true },
            boardCellWriteGateway = BoardCellWriteGateway { _, write -> write() },
        )

        controller.sendToBoard()
        advanceUntilIdle()

        verify(exactly = 0) { advertiser.advertiseClimb(any(), any(), any(), any()) }
    }
}
