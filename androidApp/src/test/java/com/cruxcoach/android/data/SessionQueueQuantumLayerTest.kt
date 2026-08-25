package com.cruxcoach.android.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.QuantumControllerState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBoardModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playlist-specific Quantum boundary: local playback may use the rack, but
 * it must never fall back to the anonymous vendor user or overwrite state it
 * cannot prove safe. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionQueueQuantumLayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var ble: BoardBleConnection
    private lateinit var repository: BoardRepository
    private lateinit var layers: BoardLayerManager
    private lateinit var queue: SessionQueueManager
    private lateinit var controllerState: MutableStateFlow<QuantumControllerState>

    private val routeUuid = "11111111-2222-4333-8444-555555555555"
    private val climbUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val descriptor = DiscoveredBoard(
        displayName = "Quantum#test",
        serial = "quantum-test",
        apiLevel = 1,
        address = "00:11:22:33:44:55",
        rssi = -40,
        boardBrand = BoardBrand.QUANTUM,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context.getSharedPreferences("board_layer_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        ble = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        layers = BoardLayerManager(context)
        controllerState = MutableStateFlow(QuantumControllerState())

        every { ble.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
        every { ble.connectedBoardDescriptor } returns MutableStateFlow(descriptor)
        every { ble.connectedQuantumModel } returns MutableStateFlow(QuantumBoardModel.M)
        every { ble.connectedBoard } returns descriptor
        every { ble.quantumControllerState } returns controllerState
        every { repository.getClimbByUuid(any(), any()) } returns quantumClimb(climbUuid)
        every { repository.getPlacementLedMap(9203, BoardBrand.QUANTUM.wireValue) } returns
            mapOf(19_100_001 to 1, 19_100_002 to 2)
        every { repository.getRoleColorMapForBrand(BoardBrand.QUANTUM.wireValue) } returns emptyMap()
        every { repository.getQuantumExternalRouteUuid(climbUuid) } returns routeUuid

        val preferences = mockk<UserPreferences>(relaxed = true).also {
            every { it.boardProductSizeId } returns flowOf(9203)
            every { it.singleConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
            every { it.multiConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
            every { it.moonBoardLedMode } returns flowOf(MoonBoardLedMode.BELOW)
        }
        queue = SessionQueueManager(
            bleConnection = ble,
            boardRepository = repository,
            climbNameResolver = mockk(relaxed = true),
            userPreferences = preferences,
            scope = scope,
            boardLayerManager = layers,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `local playlist sends with a stable owned identity and confirms its rack slot`() {
        controllerState.value =
            QuantumControllerState(authoritative = true, authoritativeRevision = 1)
        coEvery { ble.refreshQuantumState() } returns true
        coEvery {
            ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        queue.loadPlaylist("Local playlist", listOf(QueueItem(climbUuid, 30)))

        val projected = runBlocking {
            withTimeout(2_000) {
                layers.state.first { state ->
                    state.layers.singleOrNull()?.status == BoardLayerStatus.CONFIRMED
                }.layers.single()
            }
        }
        assertEquals(routeUuid, projected.routeUuid)
        assertEquals(BoardLayerStatus.CONFIRMED, projected.status)
        assertNotEquals(QuantumBoardPacketEncoder.ZERO_UUID, projected.userUuid)
        assertTrue(queue.state.value.isPlaylist)
        assertEquals(SessionVisibility.LOCAL_ONLY, queue.state.value.visibility)
        coVerify(exactly = 2) { ble.refreshQuantumState() }
        coVerify(exactly = 1) {
            ble.sendClimb(
                holds = any(),
                placementToLed = any(),
                roleColors = emptyMap(),
                routeId = routeUuid,
                quantumUserId = projected.userUuid,
                quantumColor = projected.color,
                expectedQuantumPlayers = any(),
                expectedQuantumBoard = layers.state.value.board,
            )
        }
    }

    @Test
    fun `unknown foreign controller route blocks playlist write conservatively`() {
        val foreign = QuantumActivePlayer(
            routeId = "99999999-8888-4777-8666-555555555555",
            userId = "12345678-1234-4234-8234-123456789abc",
            remainingSeconds = 120,
            color = 0x123456,
        )
        controllerState.value =
            QuantumControllerState(
                players = listOf(foreign),
                authoritative = true,
                authoritativeRevision = 1,
            )
        every {
            repository.getQuantumClimbByExternalRoute(foreign.routeId, "m", false)
        } returns null
        coEvery { ble.refreshQuantumState() } returns true

        queue.loadPlaylist("Local playlist", listOf(QueueItem(climbUuid, 30)))

        verify(timeout = 2_000) {
            repository.getQuantumClimbByExternalRoute(foreign.routeId, "m", false)
        }
        assertEquals(1, layers.state.value.externalLayers.size)
        assertTrue(layers.state.value.layers.isEmpty())
        coVerify(exactly = 0) {
            ble.sendClimb(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `known foreign controller holds hydrate and block playlist overlap`() {
        val foreign = QuantumActivePlayer(
            routeId = "99999999-8888-4777-8666-555555555555",
            userId = "12345678-1234-4234-8234-123456789abc",
            remainingSeconds = 120,
            color = 0x123456,
        )
        controllerState.value = QuantumControllerState(
            players = listOf(foreign),
            authoritative = true,
            authoritativeRevision = 1,
        )
        every {
            repository.getQuantumClimbByExternalRoute(foreign.routeId, "m", false)
        } returns quantumClimb("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff").copy(
            name = "Known foreign route",
            frames = "p19100001r12",
        )
        coEvery { ble.refreshQuantumState() } returns true

        queue.loadPlaylist("Local playlist", listOf(QueueItem(climbUuid, 30)))

        verify(timeout = 2_000) {
            repository.getQuantumClimbByExternalRoute(foreign.routeId, "m", false)
        }
        val external = layers.state.value.externalLayers.single()
        assertEquals("Known foreign route", external.climbName)
        assertEquals(listOf(BoardHold(19_100_001, 12)), external.holds)
        coVerify(exactly = 0) {
            ble.sendClimb(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `owned direct UUID proof does not hydrate a foreign duplicate route`() {
        val sharedRoute = climbUuid
        val owned = QuantumActivePlayer(
            routeId = sharedRoute,
            userId = layers.identityForSlot(0),
            remainingSeconds = 120,
            color = 0x00ffff,
        )
        val foreign = owned.copy(
            userId = "12345678-1234-4234-8234-123456789abc",
            color = 0x123456,
        )
        controllerState.value = QuantumControllerState(
            players = listOf(owned, foreign),
            authoritative = true,
            authoritativeRevision = 1,
        )
        every {
            repository.getQuantumClimbByExternalRoute(sharedRoute, "m", true)
        } returns quantumClimb(climbUuid)
        every {
            repository.getQuantumClimbByExternalRoute(sharedRoute, "m", false)
        } returns null
        coEvery { ble.refreshQuantumState() } returns true

        queue.loadPlaylist("Local playlist", listOf(QueueItem(climbUuid, 30)))

        verify(timeout = 2_000) {
            repository.getQuantumClimbByExternalRoute(sharedRoute, "m", true)
            repository.getQuantumClimbByExternalRoute(sharedRoute, "m", false)
        }
        assertEquals(listOf(BoardHold(19_100_001, 12), BoardHold(19_100_002, 14)),
            layers.state.value.layers.single().confirmedHolds)
        assertNull(layers.state.value.externalLayers.single().holds)
        coVerify(exactly = 0) {
            ble.sendClimb(any(), any(), any(), any(), any(), any())
        }
    }

    private fun quantumClimb(uuid: String) = ClimbWithStats(
        uuid = uuid,
        layoutId = 9103,
        setterUsername = "setter",
        name = "Quantum playlist climb",
        frames = "p19100001r12p19100002r14",
        framesCount = 1,
        difficultyAverage = 15.0,
        qualityAverage = 3.0,
        ascensionistCount = 1,
        boardBrand = BoardBrand.QUANTUM.wireValue,
    )
}
