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
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardRelayOperation
import com.cruxcoach.android.boardcell.MeshMembershipTransition
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.android.boardcell.ProjectionResult
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The unframed transport, held to the same contract as the framed one.
 *
 * MoonBoard speaks an ASCII Nordic-UART stream, so there is no Aurora packet
 * grouping and no climb identity to recover — and the relay used to treat that
 * as licence: the write was forwarded with no identity, no deduplication, no
 * routing mode, no deadline and no canonical record, and the GATT server
 * reported success before the board write and the canonical commit behind it
 * had even started.
 *
 * A guest write on somebody else's wall is a guest write on somebody else's
 * wall, whatever the transport underneath it happens to be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CruxRelayRawIngressTest {

    @get:Rule
    val mockkRule = io.mockk.junit4.MockKRule(this)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private val me = "node-me"
    private val physical = PhysicalBoardId("moonboard:ble:AA:BB:CC:DD:EE:FF")
    private val board = DiscoveredBoard(
        displayName = "MoonBoard A", serial = "1", apiLevel = 3,
        address = "AA:BB:CC:DD:EE:FF", rssi = -50, boardBrand = BoardBrand.MOONBOARD,
    )

    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    private val writes = MutableSharedFlow<RelayInboundWrite>(extraBufferCapacity = 8)

    private var clockMs = 1_786_968_000_000L
    private var routingMode = RelayInboundClimbMode.PROJECT_NOW
    private var projectionSucceeds = true
    private var landedRecordAccepted = true
    private var boardWrites = 0
    private val commandsWritten = mutableSetOf<String>()
    private val monotonic get() = 10_000L + dispatcher.scheduler.currentTime

    private var canonicalPlaylist = BoardPlaylistState(sessionId = 7)

    private lateinit var relayServer: RelayGattServer
    private lateinit var boardCellManager: BoardCellManager
    private lateinit var manager: CruxRelayManager

    private fun snapshot() = BoardCellSnapshot(
        cellId = BoardCellId.forPhysical(physical), physicalBoardId = physical,
        epoch = 1, sequence = 4, controllerId = me, controllerTerm = 1,
        lineageId = "lineage", members = setOf(me), availability = BoardCellAvailability.ACTIVE,
        playlist = BoardPlaylistState(sessionId = 7),
    ).withComputedHash()

    /** A write as the GATT server hands it over, deadline included. */
    private fun inbound(
        requestId: Int,
        bytes: ByteArray = byteArrayOf(0x2A, 0x31, 0x2C, 0x32, 0x23),
        address: String = "GG:01",
    ) = RelayInboundWrite(
        deviceAddress = address,
        value = bytes,
        pendingResponse = requestId,
        deadlineAtMs = monotonic + RelayGattServer.RELAY_OPERATION_DEADLINE_MS,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Shadows.shadowOf(context).grantPermissions(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        relayServer = mockk(relaxed = true) {
            every { climbs } returns MutableSharedFlow<RelayInboundClimb>()
            every { this@mockk.writes } returns this@CruxRelayRawIngressTest.writes
            every { connectionEvents } returns MutableSharedFlow<GattConnectionEvent>()
            every { getConnectedCount() } returns 0
            every { connectedAddresses() } returns emptySet()
            coEvery { start() } returns true
        }
        val bleConnection = mockk<BoardBleConnection>(relaxed = true) {
            every { this@mockk.connectionState } returns this@CruxRelayRawIngressTest.connectionState
            every { connectedBoard } returns board
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.MOONBOARD)
            coEvery { sendRawChunks(any()) } returns true
        }
        boardCellManager = mockk(relaxed = true) {
            every { this@mockk.snapshots } returns this@CruxRelayRawIngressTest.snapshots
            every { snapshot() } answers { this@CruxRelayRawIngressTest.snapshots.value }
            every { playlist() } answers { canonicalPlaylist }
            every { localNodeId() } returns me
            every { membershipTransition } returns MutableStateFlow(MeshMembershipTransition.IDLE)
            coEvery { projectExternal(any(), any(), any(), any()) } coAnswers {
                // The canonical serializer deduplicates by command id, so a
                // retry of the same operation reaches the wall only once.
                if (commandsWritten.add(arg<String>(2))) boardWrites++
                if (projectionSucceeds) ProjectionResult.Duplicate(mockk(relaxed = true))
                else ProjectionResult.Refused("board refused")
            }
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { relayInboundClimbMode } answers {
                MutableStateFlow(this@CruxRelayRawIngressTest.routingMode)
            }
            every { boardLayoutId } returns MutableStateFlow(1)
            every { boardAngle } returns MutableStateFlow(40)
        }
        val bridge = mockk<SessionGattBridge>(relaxed = true) {
            coEvery { recordRelayIntent(any()) } answers {
                val operation = firstArg<BoardRelayOperation>()
                val accepted = !operation.landed || landedRecordAccepted
                if (accepted) {
                    canonicalPlaylist = BoardPlaylistPolicy.apply(
                        canonicalPlaylist,
                        BoardPlaylistOps.recordRelayOperation(operation),
                    )
                }
                accepted
            }
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
            projectionCoordinator = mockk(relaxed = true),
            boardCellManager = boardCellManager,
            userPreferences = preferences,
            gattBridge = bridge,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            fipsMeshRuntime = mockk<FipsMeshRuntime>(relaxed = true) {
                every { directAuthenticatedPeers } returns MutableStateFlow(emptySet())
            },
            nowMs = { clockMs },
            monotonicMs = { monotonic },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun kotlinx.coroutines.test.TestScope.relayRunning() {
        snapshots.value = snapshot()
        advanceUntilIdle()
    }

    /** The forwarded write goes to the wall, and "finished" is canonical. */
    @Test
    fun `a raw write is delivered and canonically finished`() = runTest(dispatcher) {
        relayRunning()

        writes.emit(inbound(requestId = 201))
        advanceUntilIdle()

        coVerify { relayServer.settle(201, true) }
        assertEquals("the wall was written", 1, boardWrites)
        assertTrue("the request is canonically finished",
            canonicalPlaylist.relayOperations.single().landed)
    }

    /** A transport failure is the guest's answer, not something they discover. */
    @Test
    fun `a raw write the board refuses is answered negative`() = runTest(dispatcher) {
        projectionSucceeds = false
        relayRunning()

        writes.emit(inbound(requestId = 202))
        advanceUntilIdle()

        coVerify { relayServer.settle(202, false) }
        assertTrue("nothing is recorded as finished",
            canonicalPlaylist.relayOperations.none { it.landed })
    }

    /**
     * The retry. There was no identity at all before this, so the same bytes
     * arriving again went to the board again.
     */
    @Test
    fun `a repeated raw write does not write the board twice`() = runTest(dispatcher) {
        relayRunning()
        writes.emit(inbound(requestId = 203))
        advanceUntilIdle()

        clockMs += 5_000
        writes.emit(inbound(requestId = 204))
        advanceUntilIdle()

        coVerify { relayServer.settle(204, true) }
        assertEquals("the wall is written once", 1, boardWrites)
        assertEquals("one operation, not two", 1, canonicalPlaylist.relayOperations.size)
    }

    /**
     * A retry after a terminal record this device could not commit: the wall
     * already has the bytes, so it is not written again, and the guest gets
     * the success their first attempt was denied.
     */
    @Test
    fun `a retry after a refused terminal record lands once`() = runTest(dispatcher) {
        landedRecordAccepted = false
        relayRunning()
        writes.emit(inbound(requestId = 205))
        advanceUntilIdle()
        coVerify { relayServer.settle(205, false) }

        landedRecordAccepted = true
        clockMs += 5_000
        writes.emit(inbound(requestId = 206))
        advanceUntilIdle()

        coVerify { relayServer.settle(206, true) }
        assertEquals("the wall is written once for the two attempts", 1, boardWrites)
    }

    /**
     * `APPEND_TO_END` means the wall is not taken by inbound relay climbs. A
     * raw write has no occurrence to queue, so the honest answer is a refusal
     * rather than projecting it anyway.
     */
    @Test
    fun `a raw write does not take the wall when the group asked for the queue`() =
        runTest(dispatcher) {
            routingMode = RelayInboundClimbMode.APPEND_TO_END
            relayRunning()

            writes.emit(inbound(requestId = 207))
            advanceUntilIdle()

            coVerify { relayServer.settle(207, false) }
            assertEquals("the wall was not written", 0, boardWrites)
        }

    /**
     * Two different commands in quick succession are two commands — one
     * MoonBoard problem spans several writes, so pacing them as if each were a
     * command would drop a command's own tail.
     */
    @Test
    fun `consecutive raw writes are not paced apart`() = runTest(dispatcher) {
        relayRunning()

        writes.emit(inbound(requestId = 208, bytes = byteArrayOf(0x2A, 0x31)))
        advanceUntilIdle()
        clockMs += 50
        writes.emit(inbound(requestId = 209, bytes = byteArrayOf(0x2C, 0x32, 0x23)))
        advanceUntilIdle()

        coVerify { relayServer.settle(209, true) }
        assertEquals("both halves reached the wall", 2, boardWrites)
    }
}
