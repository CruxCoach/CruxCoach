package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardClimbLayer
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
import com.cruxcoach.domain.board.BoardHold
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
import org.junit.Assert.assertTrue

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
            boardLayerManager = mockk<BoardLayerManager>(relaxed = true),
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
            boardLayerManager = mockk<BoardLayerManager>(relaxed = true),
            boardCellWriteGateway = BoardCellWriteGateway { _, write -> write() },
        )

        controller.sendToBoard()
        advanceUntilIdle()

        verify(exactly = 0) { advertiser.advertiseClimb(any(), any(), any(), any()) }
    }

    @Test
    fun `explicit Quantum send allocates independent identity color and confirms layer`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                name = "Quantum test",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
                frames = "p10r1p20r2p30r3",
            )
            val holds = listOf(BoardHold(10, 1), BoardHold(20, 2), BoardHold(30, 3))
            val state = MutableStateFlow(ClimbDetailState(
                isLoading = false,
                climb = climb,
                holds = holds,
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                selectedBoardLayerSlot = 1,
                selectedBoardLayerColor = 0xff8c00.toInt(),
            ))
            val routeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            val userId = "99999999-8888-7777-6666-555555555555"
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getPlacementLedMap(9201, BoardBrand.QUANTUM.wireValue) } returns
                    mapOf(10 to 100, 20 to 200, 30 to 300)
                every { getRoleColorMapForBrand(BoardBrand.QUANTUM.wireValue) } returns emptyMap()
                every { getQuantumExternalRouteUuid(climb.uuid) } returns routeId
            }
            val ble = mockk<BoardBleConnection>(relaxed = true) {
                // Brand guard is covered independently; null means an older
                // descriptor without an inferred family and lets this test
                // focus on the Quantum layer payload.
                every { connectedBoardBrand } returns MutableStateFlow(null)
                coEvery {
                    sendClimb(holds, any(), any(), routeId, userId, 0xff8c00.toInt())
                } returns true
            }
            val preferences = mockk<UserPreferences>(relaxed = true) {
                every { boardBrand } returns flowOf(BoardBrand.QUANTUM.wireValue)
                every { boardProductSizeId } returns flowOf(9201)
            }
            val queue = mockk<SessionQueueManager>(relaxed = true)
            every { queue.state } returns MutableStateFlow(SessionQueueState())
            val layerManager = mockk<BoardLayerManager>(relaxed = true)
            every { layerManager.state } returns MutableStateFlow(BoardLayerState())
            with(layerManager) {
                every { layerForClimb(climb.uuid) } returns null
                every { nextAvailableSlot(BoardBrand.QUANTUM, 1) } returns 1
                every { identityForSlot(1) } returns userId
            }
            val controller = BoardSendController(
                scope = this,
                state = state,
                boardRepository = repository,
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = ble,
                userPreferences = preferences,
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layerManager,
                boardCellWriteGateway = BoardCellWriteGateway { _, write -> write() },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.sendToBoard()
            advanceUntilIdle()

            assertTrue(state.value.nearby.debugInfo, state.value.ble.success)
            coVerify(exactly = 1) {
                ble.sendClimb(holds, any(), any(), routeId, userId, 0xff8c00.toInt())
            }
            verify(exactly = 1) {
                layerManager.beginProjection(match<BoardClimbLayer> {
                    it.slot == 1 && it.userUuid == userId && it.routeUuid == routeId &&
                        it.color == 0xff8c00.toInt()
                })
                layerManager.confirmProjection(1)
            }
        }
}
