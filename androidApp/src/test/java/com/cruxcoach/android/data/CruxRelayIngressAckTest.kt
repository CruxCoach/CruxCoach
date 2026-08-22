package com.cruxcoach.android.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.android.ble.RelayInboundClimb
import com.cruxcoach.android.ble.RelayInboundWrite
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.MeshMembershipTransition
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.android.boardcell.ProjectionResult
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.relay.CompleteClimb
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The other half of the ATT contract: the verdicts the relay produces.
 *
 * `RelayGattAckContractTest` pins what the server does with a verdict;
 * this pins which verdict each business decision yields. Everything below
 * runs *after* the bytes have arrived — the board, layout and angle checks,
 * the rate limit, deduplication and the canonical intent barrier — and every
 * one of them used to be invisible to the guest, because the response had gone
 * out as a success before any of it ran.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CruxRelayIngressAckTest {

    @get:Rule
    val mockkRule = io.mockk.junit4.MockKRule(this)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private val me = "node-me"
    private val physical = PhysicalBoardId("kilter:ble:AA:BB:CC:DD:EE:FF")
    private val board = DiscoveredBoard(
        displayName = "Kilter Board", serial = "1", apiLevel = 3,
        address = "AA:BB:CC:DD:EE:FF", rssi = -50, boardBrand = BoardBrand.KILTER,
    )

    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    private val climbs = MutableSharedFlow<RelayInboundClimb>(extraBufferCapacity = 8)

    private var clockMs = 1_786_968_000_000L
    private var identified: BoardProjection? = BoardProjection("climb-a", 40)
    private var layoutId = 1
    /** What the catalogue says the guest's climb belongs to. */
    private var climbLayoutId = 1L
    private var boardAngle = 40
    private var intentAccepted = true
    private var projectionSucceeds = true

    private lateinit var relayServer: RelayGattServer
    private lateinit var bridge: SessionGattBridge
    private lateinit var manager: CruxRelayManager

    private fun snapshot() = BoardCellSnapshot(
        cellId = BoardCellId.forPhysical(physical), physicalBoardId = physical,
        epoch = 1, sequence = 4, controllerId = me, controllerTerm = 1,
        lineageId = "lineage", members = setOf(me), availability = BoardCellAvailability.ACTIVE,
        playlist = BoardPlaylistState(sessionId = 7),
    ).withComputedHash()

    private fun inbound(requestId: Int, framesHash: Long = 77L) = RelayInboundClimb(
        deviceAddress = "GG:01",
        climb = CompleteClimb(
            rawBytes = byteArrayOf(1, 2, 3), chunks = listOf(byteArrayOf(1, 2, 3)),
            framesHash = framesHash, holdCount = 5,
        ),
        pendingResponse = requestId,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Shadows.shadowOf(context).grantPermissions(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        relayServer = mockk(relaxed = true) {
            every { this@mockk.climbs } returns this@CruxRelayIngressAckTest.climbs
            every { writes } returns MutableSharedFlow<RelayInboundWrite>()
            every { connectionEvents } returns MutableSharedFlow<GattConnectionEvent>()
            every { getConnectedCount() } returns 0
            every { connectedAddresses() } returns emptySet()
            coEvery { start() } returns true
        }
        val bleConnection = mockk<BoardBleConnection>(relaxed = true) {
            every { this@mockk.connectionState } returns this@CruxRelayIngressAckTest.connectionState
            every { connectedBoard } returns board
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
            coEvery { sendRawChunks(any()) } returns true
        }
        val boardCellManager = mockk<BoardCellManager>(relaxed = true) {
            every { this@mockk.snapshots } returns this@CruxRelayIngressAckTest.snapshots
            every { snapshot() } answers { this@CruxRelayIngressAckTest.snapshots.value }
            every { playlist() } answers { this@CruxRelayIngressAckTest.snapshots.value?.playlist }
            every { localNodeId() } returns me
            every { membershipTransition } returns MutableStateFlow(MeshMembershipTransition.IDLE)
            coEvery { projectExternal(any(), any(), any(), any()) } answers {
                // Duplicate counts as delivered — the wall already shows it —
                // and needs only an ack, so the success path is reachable
                // without standing up a whole envelope.
                if (projectionSucceeds) ProjectionResult.Duplicate(mockk(relaxed = true))
                else ProjectionResult.Refused("board refused")
            }
        }
        val projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true) {
            coEvery { identifyExternal(any()) } answers { identified }
        }
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getClimbByUuid(any(), any()) } answers {
                val climbLayout = this@CruxRelayIngressAckTest.climbLayoutId
                mockk<ClimbWithStats>(relaxed = true) {
                    every { boardBrand } returns "kilter"
                    every { layoutId } returns climbLayout
                }
            }
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { relayInboundClimbMode } returns MutableStateFlow(RelayInboundClimbMode.PROJECT_NOW)
            every { boardLayoutId } returns
                MutableStateFlow(this@CruxRelayIngressAckTest.layoutId)
            every { boardAngle } returns
                MutableStateFlow(this@CruxRelayIngressAckTest.boardAngle)
        }
        bridge = mockk(relaxed = true) {
            coEvery { recordRelayIntent(any()) } answers { intentAccepted }
        }
        manager = CruxRelayManager(
            context = context,
            relayServer = relayServer,
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true) {
                coEvery { startRelayAdvertising() } returns "started"
                coEvery { awaitRelayAdvertisingStart() } returns
                    android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
            },
            bleConnection = bleConnection,
            projectionCoordinator = projectionCoordinator,
            boardCellManager = boardCellManager,
            userPreferences = preferences,
            gattBridge = bridge,
            boardRepository = repository,
            fipsMeshRuntime = mockk<FipsMeshRuntime>(relaxed = true) {
                every { directAuthenticatedPeers } returns MutableStateFlow(emptySet())
            },
            nowMs = { clockMs },
            monotonicMs = { 10_000L },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun kotlinx.coroutines.test.TestScope.relayRunning() {
        snapshots.value = snapshot()
        advanceUntilIdle()
    }

    /** A climb this board cannot show: the guest is told, not thanked. */
    @Test
    fun `an angle mismatch is refused at the ATT layer`() = runTest(dispatcher) {
        identified = BoardProjection("climb-a", 25)
        relayRunning()

        climbs.emit(inbound(requestId = 21))
        advanceUntilIdle()

        coVerify { relayServer.settle(21, false) }
    }

    /** The same family, a different layout: the holds are somewhere else. */
    @Test
    fun `a layout mismatch is refused at the ATT layer`() = runTest(dispatcher) {
        climbLayoutId = 8L   // the connected board is layout 1
        relayRunning()

        climbs.emit(inbound(requestId = 22))
        advanceUntilIdle()

        coVerify { relayServer.settle(22, false) }
    }

    /**
     * The rate limit: two different writes in quick succession. The second is
     * paced, and pacing is a refusal the guest can see.
     */
    @Test
    fun `a rate-limited write is refused at the ATT layer`() = runTest(dispatcher) {
        relayRunning()

        climbs.emit(inbound(requestId = 31, framesHash = 1L))
        advanceUntilIdle()
        identified = BoardProjection("climb-b", 40)
        clockMs += 100
        climbs.emit(inbound(requestId = 32, framesHash = 2L))
        advanceUntilIdle()

        coVerify { relayServer.settle(32, false) }
    }

    /**
     * The canonical barrier. Without a committed intention this device has no
     * right to write the wall for the guest, and the guest is told so.
     */
    @Test
    fun `a refused intent barrier is refused at the ATT layer`() = runTest(dispatcher) {
        intentAccepted = false
        relayRunning()

        climbs.emit(inbound(requestId = 41))
        advanceUntilIdle()

        coVerify { relayServer.settle(41, false) }
    }

    /** And a board that cannot be reached at all. */
    @Test
    fun `a write with no usable board path is refused at the ATT layer`() = runTest(dispatcher) {
        relayRunning()
        connectionState.value = ConnectionState.DISCONNECTED
        advanceUntilIdle()

        climbs.emit(inbound(requestId = 51))
        advanceUntilIdle()

        coVerify { relayServer.settle(51, false) }
    }

    /**
     * The terminal record rides in the same command as the occurrence.
     *
     * Publishing it separately afterwards meant a refusal — or a stop, or a
     * handover — left the request open in canonical state, and an open request
     * is one a later send by the same guest is folded back onto.
     */
    @Test
    fun `the append carries the terminal record with it`() = runTest(dispatcher) {
        relayRunning()

        climbs.emit(inbound(requestId = 61))
        advanceUntilIdle()

        coVerify {
            bridge.adoptProjectedEntry(
                any(), any(), any(), any(),
                completing = match { it != null && it.entryId.isNotBlank() },
                onTerminal = any(),
            )
        }
    }

    /** And nothing publishes it on its own any more. */
    @Test
    fun `no separate terminal publication is made`() = runTest(dispatcher) {
        relayRunning()

        climbs.emit(inbound(requestId = 62))
        advanceUntilIdle()

        // Exactly one intent publication: the opening barrier. The terminal
        // half is part of the occurrence command.
        coVerify(exactly = 1) { bridge.recordRelayIntent(any()) }
    }
}
