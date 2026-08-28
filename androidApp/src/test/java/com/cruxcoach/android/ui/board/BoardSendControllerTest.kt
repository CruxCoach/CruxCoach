package com.cruxcoach.android.ui.board

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerPlanKey
import com.cruxcoach.android.ble.BoardLayerBoardIdentity
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.android.ble.QuantumControllerState
import com.cruxcoach.android.ble.planKey
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.SessionVisibility
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
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
    fun `only an explicit detail light overrides a private MoonBoard playlist`() = runTest {
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
            coEvery { sendMoonBoardClimb(any(), any(), any()) } returns true
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns flowOf(BoardBrand.MOONBOARD.wireValue)
            every { boardLayoutId } returns flowOf(MoonBoardVariant.MOONBOARD_2016.layoutId.toInt())
            every { moonBoardLedMode } returns flowOf(MoonBoardLedMode.BELOW)
        }
        val queueManager = mockk<SessionQueueManager>(relaxed = true)
        every { queueManager.state } returns MutableStateFlow(
            SessionQueueState(
                role = SessionRole.HOST,
                isPlaylist = true,
                visibility = SessionVisibility.LOCAL_ONLY,
                visibilityRequested = SessionVisibility.LOCAL_ONLY,
            )
        )
        val controller = BoardSendController(
            scope = this,
            state = state,
            boardRepository = mockk(relaxed = true),
            personalBoardRepo = mockk(relaxed = true),
            bleConnection = bleConnection,
            userPreferences = preferences,
            climbAdvertiser = mockk(relaxed = true),
            sessionQueueManager = queueManager,
            isSharingEnabled = { false },
            boardLayerManager = mockk(relaxed = true),
        )

        controller.sendToBoard()
        advanceUntilIdle()
        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }

        controller.sendToBoard(userInitiated = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        verify(exactly = 1) { queueManager.markExternalBoardWrite(moonClimb.uuid, 40) }
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
                every { assignPreviewIfCurrent(any(), null) } answers {
                    layerState.value = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        layers = listOf(firstArg()),
                    )
                    true
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
    fun `Quantum assignment cannot overwrite a plan replaced during route lookup`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                name = "Delayed preview",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val original = BoardClimbLayer(
                slot = 2,
                climbUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                routeUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                climbName = "Original plan",
                angle = 40,
                userUuid = "99999999-8888-4777-8666-555555555555",
                color = BoardLayerManager.LAYER_COLORS[2],
                holds = listOf(BoardHold(1, 12)),
                status = com.cruxcoach.android.ble.BoardLayerStatus.PREVIEW,
            )
            val replacement = original.copy(
                climbUuid = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
                routeUuid = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
                climbName = "Newer plan",
                planToken = "replacement-token",
            )
            val layerState = MutableStateFlow(BoardLayerState(
                brand = BoardBrand.QUANTUM,
                layers = listOf(original),
            ))
            val layers = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns layerState
                every { layerForClimb(climb.uuid) } returns null
                every { identityForSlot(2) } returns original.userUuid
                every { assignPreviewIfCurrent(any(), original.planKey()) } answers {
                    layerState.value.layers.single().planKey() == secondArg<BoardLayerPlanKey>()
                }
            }
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getQuantumExternalRouteUuid(climb.uuid) } answers {
                    // Simulates another detail/playlist surface replacing the
                    // slot while buildQuantumLayer is suspended on disk work.
                    layerState.value = layerState.value.copy(layers = listOf(replacement))
                    climb.uuid
                }
            }
            val detailState = MutableStateFlow(ClimbDetailState(
                climb = climb,
                holds = listOf(BoardHold(10, 12), BoardHold(20, 14)),
                angle = 40,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                selectedBoardLayerSlot = 2,
                selectedBoardLayerColor = BoardLayerManager.LAYER_COLORS[2],
            ))
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = repository,
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = mockk(relaxed = true),
                userPreferences = mockk(relaxed = true),
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layers,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.assignCurrentToBoardLayer()
            advanceUntilIdle()

            assertEquals(replacement.planKey(), layerState.value.layers.single().planKey())
            assertEquals(R.string.board_layer_error_state_unavailable, detailState.value.ble.error)
            verify(exactly = 1) {
                layers.assignPreviewIfCurrent(any(), original.planKey())
            }
        }

    @Test
    fun `explicit target never silently moves a climb already assigned to another identity`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val existing = BoardClimbLayer(
                slot = 0,
                climbUuid = climb.uuid,
                routeUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                climbName = climb.name,
                angle = 40,
                userUuid = "10000000-0000-4000-8000-000000000000",
                color = BoardLayerManager.LAYER_COLORS[0],
                holds = listOf(BoardHold(10, 1)),
                status = com.cruxcoach.android.ble.BoardLayerStatus.CONFIRMED,
                confirmedRouteUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                confirmedColor = BoardLayerManager.LAYER_COLORS[0],
            )
            val detailState = MutableStateFlow(
                ClimbDetailState(
                    climb = climb,
                    holds = existing.holds,
                    selectedBoardLayerSlot = 2,
                    selectedBoardLayerColor = BoardLayerManager.LAYER_COLORS[2],
                    ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                ),
            )
            val layers = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns MutableStateFlow(
                    BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(existing)),
                )
                every { layerForClimb(climb.uuid) } returns existing
            }
            val ble = mockk<BoardBleConnection>(relaxed = true)
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = mockk(relaxed = true),
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = ble,
                userPreferences = mockk(relaxed = true),
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layers,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.assignCurrentToBoardLayer()
            advanceUntilIdle()

            assertEquals(
                R.string.quantum_layer_already_assigned_error,
                detailState.value.ble.error,
            )
            verify(exactly = 0) { layers.assignPreviewIfCurrent(any(), any()) }

            detailState.update { it.copy(ble = it.ble.copy(error = null)) }
            controller.sendToBoard()
            advanceUntilIdle()

            assertEquals(
                R.string.quantum_layer_already_assigned_error,
                detailState.value.ble.error,
            )
            coVerify(exactly = 0) {
                ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any())
            }
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
            val expectedBoard = BoardLayerBoardIdentity("quantum:serial:ser-1", 9201)
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
                every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
                every { quantumControllerState } returns MutableStateFlow(
                    QuantumControllerState(authoritative = true, authoritativeRevision = 1),
                )
                // The rack is staged for the board on the link — the ordinary
                // case. Sending a rack staged for a different board is its own
                // test in BoardLayerBoardBindingTest.
                every { connectedBoardDescriptor } returns MutableStateFlow(
                    DiscoveredBoard(
                        displayName = "Quantum", serial = "SER-1", apiLevel = 3,
                        address = "AA:BB:CC:DD:EE:FF", rssi = -40,
                        boardBrand = BoardBrand.QUANTUM,
                    )
                )
                every { connectedQuantumModel } returns MutableStateFlow(QuantumBoardModel.XL)
                coEvery {
                    sendClimb(
                        holds, any(), any(), routeId, userId,
                        BoardLayerManager.LAYER_COLORS[1], any(), expectedBoard,
                        BoardBrand.QUANTUM,
                    )
                } returns true
                coEvery { refreshQuantumState() } returns true
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
                every { isBoundTo(any()) } returns true
                every { layerForClimb(climb.uuid) } returns null
                every { nextAvailableSlot(BoardBrand.QUANTUM, 1) } returns 1
                every { identityForSlot(1) } returns userId
                every { defaultColor(1) } returns BoardLayerManager.LAYER_COLORS[1]
                every { hasControllerCapacityFor(1, any()) } returns true
                every { beginProjection(any<BoardLayerPlanKey>()) } returns true
                every { assignPreviewIfCurrent(any(), null) } answers {
                    layerState.value = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        board = expectedBoard,
                        layers = listOf(firstArg()),
                    )
                    true
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
                    any(),
                    expectedBoard,
                    BoardBrand.QUANTUM,
                )
            }
            coVerify(exactly = 2) { ble.refreshQuantumState() }
            verify(exactly = 1) {
                layerManager.assignPreviewIfCurrent(
                    match<BoardClimbLayer> {
                        it.slot == 1 && it.userUuid == userId && it.routeUuid == routeId &&
                            it.color == BoardLayerManager.LAYER_COLORS[1]
                    },
                    null,
                )
                layerManager.beginProjection(match<BoardLayerPlanKey> { it.slot == 1 })
                layerManager.confirmProjection(match<BoardLayerPlanKey> { it.slot == 1 })
            }
        }

    @Test
    fun `Quantum encoder exception cannot strand a sending layer or spinner`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val holds = listOf(BoardHold(10, 1))
            val expectedBoard = BoardLayerBoardIdentity("quantum:serial:ser-1", 9201)
            val layer = BoardClimbLayer(
                slot = 0,
                climbUuid = climb.uuid,
                routeUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                climbName = climb.name,
                angle = 40,
                userUuid = "10000000-0000-4000-8000-000000000000",
                color = BoardLayerManager.LAYER_COLORS[0],
                holds = holds,
                status = com.cruxcoach.android.ble.BoardLayerStatus.PREVIEW,
            )
            val detailState = MutableStateFlow(
                ClimbDetailState(
                    climb = climb,
                    holds = holds,
                    ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                ),
            )
            val layerState = MutableStateFlow(
                BoardLayerState(
                    brand = BoardBrand.QUANTUM,
                    board = expectedBoard,
                    layers = listOf(layer),
                ),
            )
            val layers = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns layerState
                every { isBoundTo(expectedBoard) } returns true
                every { hasControllerCapacityFor(0, any()) } returns true
                every { beginProjection(any<BoardLayerPlanKey>()) } answers {
                    layerState.update { current ->
                        current.copy(
                            layers = current.layers.map {
                                it.copy(status = com.cruxcoach.android.ble.BoardLayerStatus.SENDING)
                            },
                        )
                    }
                    true
                }
                every { failProjection(any<BoardLayerPlanKey>()) } answers {
                    layerState.update { current ->
                        current.copy(
                            layers = current.layers.map {
                                it.copy(status = com.cruxcoach.android.ble.BoardLayerStatus.FAILED)
                            },
                        )
                    }
                    true
                }
            }
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getPlacementLedMap(9201, BoardBrand.QUANTUM.wireValue) } returns
                    mapOf(10 to 100)
            }
            val ble = mockk<BoardBleConnection>(relaxed = true) {
                every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
                every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
                every { quantumControllerState } returns MutableStateFlow(
                    QuantumControllerState(authoritative = true, authoritativeRevision = 1),
                )
                every { connectedBoardDescriptor } returns MutableStateFlow(
                    DiscoveredBoard(
                        displayName = "Quantum",
                        serial = "SER-1",
                        apiLevel = 3,
                        address = "AA:BB:CC:DD:EE:FF",
                        rssi = -40,
                        boardBrand = BoardBrand.QUANTUM,
                    ),
                )
                every { connectedQuantumModel } returns MutableStateFlow(QuantumBoardModel.XL)
                coEvery { refreshQuantumState() } returns true
                coEvery {
                    sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), BoardBrand.QUANTUM)
                } throws IllegalStateException("encoder failed")
            }
            val preferences = mockk<UserPreferences>(relaxed = true) {
                every { boardBrand } returns flowOf(BoardBrand.QUANTUM.wireValue)
                every { boardProductSizeId } returns flowOf(9201)
            }
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = repository,
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = ble,
                userPreferences = preferences,
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layers,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.sendBoardLayer(0)
            advanceUntilIdle()

            assertFalse(detailState.value.ble.isSending)
            assertEquals(R.string.board_send_error_generic, detailState.value.ble.error)
            assertEquals(
                com.cruxcoach.android.ble.BoardLayerStatus.FAILED,
                layerState.value.layers.single().status,
            )
        }

    @Test
    fun `Quantum removal hydration exception always lowers the spinner`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val expectedBoard = BoardLayerBoardIdentity("quantum:serial:ser-1", 9201)
            val confirmed = BoardClimbLayer(
                slot = 0,
                climbUuid = climb.uuid,
                routeUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                climbName = climb.name,
                angle = 40,
                userUuid = "10000000-0000-4000-8000-000000000000",
                color = BoardLayerManager.LAYER_COLORS[0],
                holds = listOf(BoardHold(10, 1)),
                status = com.cruxcoach.android.ble.BoardLayerStatus.CONFIRMED,
                confirmedRouteUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                confirmedColor = BoardLayerManager.LAYER_COLORS[0],
                confirmedHolds = listOf(BoardHold(10, 1)),
            )
            val detailState = MutableStateFlow(
                ClimbDetailState(
                    climb = climb,
                    holds = confirmed.holds,
                    ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
                ),
            )
            val layerState = MutableStateFlow(
                BoardLayerState(
                    brand = BoardBrand.QUANTUM,
                    board = expectedBoard,
                    layers = listOf(confirmed),
                ),
            )
            val layers = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns layerState
                every { isBoundTo(expectedBoard) } returns true
                every { failProjection(any<BoardLayerPlanKey>()) } answers {
                    layerState.update { current ->
                        current.copy(
                            layers = current.layers.map {
                                it.copy(status = com.cruxcoach.android.ble.BoardLayerStatus.FAILED)
                            },
                        )
                    }
                    true
                }
            }
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getPlacementLedMap(9201, BoardBrand.QUANTUM.wireValue) } throws
                    IllegalStateException("catalogue failed")
            }
            val ble = mockk<BoardBleConnection>(relaxed = true) {
                every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
                every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
                every { quantumControllerState } returns MutableStateFlow(
                    QuantumControllerState(authoritative = true, authoritativeRevision = 1),
                )
                every { connectedBoardDescriptor } returns MutableStateFlow(
                    DiscoveredBoard(
                        displayName = "Quantum",
                        serial = "SER-1",
                        apiLevel = 3,
                        address = "AA:BB:CC:DD:EE:FF",
                        rssi = -40,
                        boardBrand = BoardBrand.QUANTUM,
                    ),
                )
                every { connectedQuantumModel } returns MutableStateFlow(QuantumBoardModel.XL)
                coEvery { refreshQuantumState() } returns true
            }
            val preferences = mockk<UserPreferences>(relaxed = true) {
                every { boardBrand } returns flowOf(BoardBrand.QUANTUM.wireValue)
                every { boardProductSizeId } returns flowOf(9201)
            }
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = repository,
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = ble,
                userPreferences = preferences,
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layers,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.removeBoardLayer(0)
            advanceUntilIdle()

            assertFalse(detailState.value.ble.isSending)
            assertEquals(R.string.board_send_error_generic, detailState.value.ble.error)
            coVerify(exactly = 0) {
                ble.removeQuantumLayer(any(), any(), any())
            }
        }

    @Test fun `only a private local playlist permits detail layer management`() {
        assertTrue(localQuantumLayerManagementAllowed(SessionQueueState()))
        assertTrue(localQuantumLayerManagementAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = true,
            visibility = SessionVisibility.LOCAL_ONLY,
            visibilityRequested = SessionVisibility.LOCAL_ONLY,
        )))
        assertFalse(localQuantumLayerManagementAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = true,
            visibility = SessionVisibility.LOCAL_ONLY,
            visibilityRequested = SessionVisibility.JOINABLE,
        )))
        assertFalse(localQuantumLayerManagementAllowed(SessionQueueState(
            role = SessionRole.PARTICIPANT,
            isPlaylist = true,
        )))
        assertFalse(localQuantumLayerManagementAllowed(SessionQueueState(isConnecting = true)))
    }

    @Test fun `only a private local host playlist permits explicit detail light`() {
        assertTrue(privatePlaylistDetailLightAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = true,
            visibility = SessionVisibility.LOCAL_ONLY,
            visibilityRequested = SessionVisibility.LOCAL_ONLY,
        )))
        assertFalse(privatePlaylistDetailLightAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = true,
            visibility = SessionVisibility.LOCAL_ONLY,
            visibilityRequested = SessionVisibility.JOINABLE,
        )))
        assertFalse(privatePlaylistDetailLightAllowed(SessionQueueState(
            role = SessionRole.PARTICIPANT,
            isPlaylist = true,
        )))
        assertFalse(privatePlaylistDetailLightAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = false,
        )))
        assertFalse(privatePlaylistDetailLightAllowed(SessionQueueState(
            role = SessionRole.HOST,
            isPlaylist = true,
            isConnecting = true,
        )))
    }

    @Test fun `automatic Quantum send fails closed for unknown foreign holds`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.QUANTUM.wireValue,
                layoutId = 9101,
            )
            val detailState = MutableStateFlow(ClimbDetailState(
                climb = climb,
                holds = listOf(BoardHold(10, 1)),
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            ))
            val layerState = MutableStateFlow(BoardLayerState(
                brand = BoardBrand.QUANTUM,
                externalLayers = listOf(ExternalBoardLayer(
                    routeUuid = "unknown-route",
                    userUuid = "foreign-user",
                    color = BoardLayerManager.LAYER_COLORS[0],
                    remainingSeconds = 10,
                    holds = null,
                )),
            ))
            val layers = mockk<BoardLayerManager>(relaxed = true) {
                every { state } returns layerState
                every { layerForClimb(climb.uuid) } returns null
            }
            val queue = mockk<SessionQueueManager>(relaxed = true) {
                every { state } returns MutableStateFlow(SessionQueueState())
            }
            val controller = BoardSendController(
                scope = this,
                state = detailState,
                boardRepository = mockk(relaxed = true),
                personalBoardRepo = mockk(relaxed = true),
                bleConnection = mockk(relaxed = true),
                userPreferences = mockk(relaxed = true),
                climbAdvertiser = mockk(relaxed = true),
                sessionQueueManager = queue,
                isSharingEnabled = { false },
                boardLayerManager = layers,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            controller.sendToBoard(automaticLayer = true)
            advanceUntilIdle()

            assertEquals(R.string.board_layer_error_external_unknown, detailState.value.ble.error)
            verify(exactly = 0) { layers.assignPreviewIfCurrent(any(), any()) }
        }
    /**
     * Review 2, finding 1. The pass-2 fence identified a variant by climb and
     * angle, and a mirror flip changes neither — so an unmirrored send came
     * back and reported the mirrored view as lit. The fence now identifies a
     * variant by the holds that actually go to the wall, which is what mirror,
     * frame stepping and anything else that swaps the hold set all change.
     */
    @Test
    fun `a mirror flip during the write cannot be reported as a mirrored send`() =
        runTest(UnconfinedTestDispatcher()) {
            val climb = moonClimb.copy(
                uuid = "11111111-2222-3333-4444-555555555555",
                boardBrand = BoardBrand.KILTER.wireValue,
                layoutId = 1,
            )
            val unmirrored = listOf(BoardHold(10, 12))
            val mirrored = listOf(BoardHold(90, 12))
            val detailState = MutableStateFlow(ClimbDetailState(
                isLoading = false,
                climb = climb,
                holds = unmirrored,
                angle = 40,
                isMirrored = false,
                ble = BoardSendState(connectionState = ConnectionState.CONNECTED),
            ))
            val personal = mockk<PersonalBoardRepository>(relaxed = true)
            val repository = mockk<BoardRepository>(relaxed = true) {
                every { getPlacementLedMap(any(), BoardBrand.KILTER.wireValue) } returns
                    mapOf(10 to 100, 90 to 900)
                every { getRoleColorMapForBrand(BoardBrand.KILTER.wireValue) } returns mapOf(12 to 1)
            }
            val ble = mockk<BoardBleConnection>(relaxed = true) {
                every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
                every { connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
                // The user flips the mirror while the controller is still
                // answering — exactly what toggleMirror() writes, reset included.
                coEvery {
                    sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), BoardBrand.KILTER)
                } answers {
                    detailState.update { current ->
                        current.copy(
                            isMirrored = true,
                            holds = mirrored,
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

            assertTrue(detailState.value.isMirrored)
            assertFalse(
                "the unmirrored holds are on the wall; the mirrored view was never sent",
                detailState.value.ble.success,
            )
            assertFalse("and the spinner still has to come down", detailState.value.ble.isSending)
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
                coEvery {
                    sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), BoardBrand.KILTER)
                } answers {
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
