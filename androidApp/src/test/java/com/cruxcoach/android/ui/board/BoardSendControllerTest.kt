package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardClimbLayer
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
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

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
        )

        controller.sendToBoard()
        advanceUntilIdle()

        verify(exactly = 0) { advertiser.advertiseClimb(any(), any(), any(), any()) }
    }

    @Test
    fun `assigning a Quantum layer changes only the local rack`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                name = "Local preview",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val holds = listOf(BoardHold(10, 1), BoardHold(20, 2), BoardHold(30, 3))
            val detailState = MutableStateFlow(ClimbDetailState(
                climb = climb,
                holds = holds,
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                selectedBoardLayerSlot = 2,
                selectedBoardLayerColor = BoardLayerManager.LAYER_COLORS[2],
            ))
            val layerState = MutableStateFlow(BoardLayerState())
            val layerManager = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns layerState
                every { layerForClimb(climb.uuid) } returns null
                every { identityForSlot(2) } returns "99999999-8888-7777-6666-555555555555"
                every { assignPreview(any()) } answers {
                    layerState.value = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        layers = listOf(firstArg()),
                    )
                }
            }
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getQuantumExternalRouteUuid(climb.uuid) } returns climb.uuid
            }
            val ble = mockk<BoardBleConnection>(relaxed = true)
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = repository,
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = ble,
                userPreferences = mockk(relaxed = true),
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layerManager,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.assignCurrentToBoardLayer()
            advanceUntilIdle()

            val preview = layerState.value.layers.single()
            assertEquals(2, preview.slot)
            assertEquals(BoardLayerManager.LAYER_COLORS[2], preview.color)
            assertEquals(com.cruxcoach.android.ble.BoardLayerStatus.PREVIEW, preview.status)
            coVerify(exactly = 0) { ble.sendClimb(any(), any(), any(), any(), any(), any()) }
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
                selectedBoardLayerColor = BoardLayerManager.LAYER_COLORS[1],
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
                    sendClimb(holds, any(), any(), routeId, userId, BoardLayerManager.LAYER_COLORS[1])
                } returns true
            }
            val preferences = mockk<UserPreferences>(relaxed = true) {
                every { boardBrand } returns flowOf(BoardBrand.QUANTUM.wireValue)
                every { boardProductSizeId } returns flowOf(9201)
            }
            val queue = mockk<SessionQueueManager>(relaxed = true)
            every { queue.state } returns MutableStateFlow(SessionQueueState())
            val layerManager = mockk<BoardLayerManager>(relaxed = true)
            val layerState = MutableStateFlow(BoardLayerState())
            every { layerManager.state } returns layerState
            with(layerManager) {
                every { layerForClimb(climb.uuid) } returns null
                every { nextAvailableSlot(BoardBrand.QUANTUM, 1) } returns 1
                every { identityForSlot(1) } returns userId
                every { defaultColor(1) } returns BoardLayerManager.LAYER_COLORS[1]
                every { hasControllerCapacityFor(1, any()) } returns true
                every { assignPreview(any()) } answers {
                    layerState.value = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        layers = listOf(firstArg()),
                    )
                }
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
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.sendToBoard()
            advanceUntilIdle()

            assertTrue(state.value.nearby.debugInfo, state.value.ble.success)
            coVerify(exactly = 1) {
                ble.sendClimb(
                    holds,
                    any(),
                    any(),
                    routeId,
                    userId,
                    BoardLayerManager.LAYER_COLORS[1],
                )
            }
            verify(exactly = 1) {
                layerManager.assignPreview(match<BoardClimbLayer> {
                    it.slot == 1 && it.userUuid == userId && it.routeUuid == routeId &&
                        it.color == BoardLayerManager.LAYER_COLORS[1]
                })
                layerManager.beginProjection(1)
                layerManager.confirmProjection(1)
            }
        }
    /**
     * The window cancellation cannot close.
     *
     * A send is a chain of suspensions — preference reads, an LED-map query,
     * the BLE write. Cancelling it stops everything still suspended, but a job
     * already past its last suspension point runs to its next statement, and
     * that statement is the one that says "sent". After an angle change that
     * claim lands on a climb variant whose holds were never on the wall.
     */
    @Test
    fun `a send that finishes after an angle change cannot mark the new angle sent`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.KILTER.wireValue,
                layoutId = 1,
            )
            val holds = listOf(BoardHold(10, 12))
            val detailState = MutableStateFlow(ClimbDetailState(
                isLoading = false,
                climb = climb,
                holds = holds,
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            ))
            val personal = mockk<PersonalBoardRepository>(relaxed = true)
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getPlacementLedMap(any(), BoardBrand.KILTER.wireValue) } returns mapOf(10 to 100)
                every { getRoleColorMapForBrand(BoardBrand.KILTER.wireValue) } returns mapOf(12 to 1)
            }
            val ble = mockk<BoardBleConnection>(relaxed = true) {
                every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
                every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
                // The user changes the angle while the controller is still
                // answering: by the time this returns, the screen has moved on.
                coEvery { sendClimb(any(), any(), any(), any(), any(), any()) } answers {
                    // Exactly what onAngleSelected() writes, including its
                    // reset of the send flags.
                    detailState.update { current ->
                        current.copy(
                            angle = 45,
                            holds = listOf(BoardHold(20, 12)),
                            ble = current.ble.copy(isSending = false, success = false, error = null),
                        )
                    }
                    true
                }
            }
            val preferences = mockk<UserPreferences>(relaxed = true) {
                every { boardBrand } returns flowOf(BoardBrand.KILTER.wireValue)
                every { boardProductSizeId } returns flowOf(10)
            }
            val queue = mockk<SessionQueueManager>(relaxed = true)
            every { queue.state } returns MutableStateFlow(SessionQueueState())
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = repository,
                personalBoardRepo = personal,
                bleConnection = ble,
                userPreferences = preferences,
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = mockk(relaxed = true),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.sendToBoard()
            advanceUntilIdle()

            assertEquals(45, detailState.value.angle)
            assertFalse(
                "40° succeeded, not 45° — the screen must not claim the new angle is lit",
                detailState.value.ble.success,
            )
            assertFalse(detailState.value.ble.isSending)
            coVerify(exactly = 0) { personal.recordClimbHistory(any(), any(), 45L, any(), any(), any(), any(), any()) }
        }

}
