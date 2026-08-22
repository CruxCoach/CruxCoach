package com.cruxcoach.android.data

import android.app.Application
import android.bluetooth.le.AdvertisingSetCallback
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.MeshMembershipTransition
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The relay's lifecycle, driven through the real manager rather than the pure
 * capacity policy.
 *
 * Everything the policy says was already true and the relay still behaved
 * wrongly: a healthy offer tore itself down on the next reconciliation, and a
 * guest occupying the single guaranteed slot left the advertisement up. Both
 * are lifecycle bugs, so they need the lifecycle to show up in a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CruxRelayLifecycleTest {

    @get:Rule
    val mockkRule = io.mockk.junit4.MockKRule(this)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private val board = DiscoveredBoard(
        displayName = "Kilter Board",
        serial = "1234",
        apiLevel = 3,
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -50,
        boardBrand = BoardBrand.KILTER,
    )
    private val physical = PhysicalBoardId("kilter:ble:AA:BB:CC:DD:EE:FF")
    private val me = "node-me"

    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    private val meshPeers = MutableStateFlow<Set<String>>(emptySet())
    private val connectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 8)
    private var guests = 0
    /** Wall clock the manager reads. */
    private var clockMs = 1_786_968_000_000L
    /** Monotonic clock the grace deadline is measured on. */
    private var monotonicClockMs = 10_000L
    /** Set to block the canonical claim publication mid-reconcile. */
    private var claimGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var advertisingFails = false
    private var advertisingCalls = 0
    private var advertisingStopped = 0
    private val claims = mutableListOf<com.cruxcoach.android.boardcell.BoardCellRelayState>()

    private lateinit var relayServer: RelayGattServer
    private lateinit var advertiser: ClimbBleAdvertiser
    private lateinit var bleConnection: BoardBleConnection
    private lateinit var boardCellManager: BoardCellManager
    private lateinit var manager: CruxRelayManager

    private fun snapshot(
        controller: String = me,
        availability: BoardCellAvailability = BoardCellAvailability.ACTIVE,
    ) = BoardCellSnapshot(
        cellId = BoardCellId.forPhysical(physical),
        physicalBoardId = physical,
        epoch = 1,
        sequence = 4,
        controllerId = controller,
        controllerTerm = 1,
        lineageId = "lineage",
        members = setOf(me, "node-other"),
        availability = availability,
    ).withComputedHash()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Shadows.shadowOf(context).grantPermissions(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )

        relayServer = mockk(relaxed = true) {
            every { climbs } returns MutableSharedFlow()
            every { writes } returns MutableSharedFlow()
            every { this@mockk.connectionEvents } returns this@CruxRelayLifecycleTest.connectionEvents
            every { getConnectedCount() } answers { guests }
            coEvery { start() } returns true
        }
        advertiser = mockk(relaxed = true) {
            coEvery { startRelayAdvertising() } answers {
                advertisingCalls++
                if (advertisingFails) "failed" else "started"
            }
            coEvery { awaitRelayAdvertisingStart() } returns AdvertisingSetCallback.ADVERTISE_SUCCESS
            every { stopRelayAdvertising() } answers { advertisingStopped++ }
        }
        bleConnection = mockk(relaxed = true) {
            every { this@mockk.connectionState } returns this@CruxRelayLifecycleTest.connectionState
            every { connectedBoard } returns board
            every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        }
        boardCellManager = mockk(relaxed = true) {
            every { this@mockk.snapshots } returns this@CruxRelayLifecycleTest.snapshots
            every { snapshot() } answers { this@CruxRelayLifecycleTest.snapshots.value }
            every { localNodeId() } returns me
            every { membershipTransition } returns MutableStateFlow(MeshMembershipTransition.IDLE)
            // The claim travels through the coordinator and the mesh, so it
            // suspends — and a test can hold it there to see what the relay is
            // doing meanwhile.
            coEvery { publishRelayState(any()) } coAnswers {
                claims += firstArg<com.cruxcoach.android.boardcell.BoardCellRelayState>()
                claimGate?.await()
                true
            }
        }
        val meshRuntime = mockk<FipsMeshRuntime>(relaxed = true) {
            every { directAuthenticatedPeers } returns meshPeers
        }
        manager = CruxRelayManager(
            context = context,
            relayServer = relayServer,
            advertiser = advertiser,
            bleConnection = bleConnection,
            projectionCoordinator = mockk(relaxed = true),
            boardCellManager = boardCellManager,
            userPreferences = mockk(relaxed = true),
            gattBridge = mockk(relaxed = true),
            boardRepository = mockk<BoardRepository>(relaxed = true),
            fipsMeshRuntime = meshRuntime,
            nowMs = { clockMs },
            monotonicMs = { monotonicClockMs },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Become the controller of an active cell with the board connected. */
    private suspend fun kotlinx.coroutines.test.TestScope.startOffering() {
        snapshots.value = snapshot()
        advanceUntilIdle()
    }

    @Test
    fun `a controller with a board starts offering`() = runTest(dispatcher) {
        startOffering()

        assertTrue(manager.state.value.enabled)
        assertTrue(manager.state.value.advertising)
        assertEquals(1, advertisingCalls)
    }

    /**
     * The blocker: nothing changed, so nothing should happen. Before this the
     * next reconciliation found no suppression reason, fell through to the
     * stop path and tore down a relay that was working — and pass 6 made the
     * relay wake that reconciliation itself by publishing its own claim.
     */
    @Test
    fun `a healthy relay survives further reconciliations`() = runTest(dispatcher) {
        startOffering()

        // Exactly what publishing the relay claim does: a new canonical
        // snapshot, which wakes the collector that decides the relay's fate.
        repeat(3) {
            snapshots.value = snapshots.value?.copy(sequence = snapshots.value!!.sequence + 1)
                ?.withComputedHash()
            advanceUntilIdle()
        }

        assertTrue("the relay must still be up", manager.state.value.enabled)
        assertTrue("and still advertising", manager.state.value.advertising)
        assertEquals("without re-advertising each time", 1, advertisingCalls)
        io.mockk.coVerify(exactly = 0) { relayServer.stop() }
    }

    /**
     * Five peers leave exactly one guaranteed slot. The guest who takes it
     * fills it, and an advertisement that stays up after that is an invitation
     * the server will refuse at accept time.
     */
    @Test
    fun `a guest taking the last slot withdraws the advertisement`() = runTest(dispatcher) {
        meshPeers.value = (1..5).map { "peer-$it" }.toSet()
        startOffering()
        assertTrue(manager.state.value.advertising)

        guests = 1
        connectionEvents.emit(GattConnectionEvent.Connected("GG:11"))
        advanceUntilIdle()

        assertFalse("no free slot means no offer", manager.state.value.advertising)
        assertEquals(0, manager.state.value.availableSlots)
        io.mockk.coVerify(exactly = 0) { relayServer.stop() }
    }

    /** And it comes back when the guest leaves — the relay never stopped. */
    @Test
    fun `the advertisement returns when the slot frees up`() = runTest(dispatcher) {
        meshPeers.value = (1..5).map { "peer-$it" }.toSet()
        startOffering()
        guests = 1
        connectionEvents.emit(GattConnectionEvent.Connected("GG:11"))
        advanceUntilIdle()
        assertFalse(manager.state.value.advertising)

        guests = 0
        connectionEvents.emit(GattConnectionEvent.Disconnected("GG:11"))
        advanceUntilIdle()

        assertTrue(manager.state.value.advertising)
        assertTrue(manager.state.value.enabled)
    }

    /**
     * A blip is not a departure: the offer goes, the server stays, and nothing
     * may be acknowledged while the wall cannot be reached.
     */
    @Test
    fun `a board blip withdraws the offer and keeps the server`() = runTest(dispatcher) {
        startOffering()

        connectionState.value = ConnectionState.DISCONNECTED
        advanceUntilIdle()

        assertFalse(manager.state.value.advertising)
        assertFalse("a dark wall may not acknowledge a write", manager.mayAcknowledgeInboundWrite())
        io.mockk.coVerify(exactly = 0) { relayServer.stop() }
    }

    @Test
    fun `a reconnect inside the window advertises again`() = runTest(dispatcher) {
        startOffering()
        connectionState.value = ConnectionState.DISCONNECTED
        advanceUntilIdle()
        assertFalse(manager.state.value.advertising)

        connectionState.value = ConnectionState.CONNECTED
        advanceUntilIdle()

        assertTrue(manager.state.value.advertising)
        assertTrue(manager.mayAcknowledgeInboundWrite())
    }

    /** Past the window it is a departure, and the whole relay goes. */
    @Test
    fun `a board that does not come back stops the relay`() = runTest(dispatcher) {
        startOffering()

        connectionState.value = ConnectionState.DISCONNECTED
        // Only the immediate reconciliation: the link has just gone, so this
        // is the grace state and the manager schedules its own re-check.
        runCurrent()
        assertFalse(manager.state.value.advertising)

        // Now the window really passes, and the re-check finds a departure.
        monotonicClockMs += CruxRelayOwnershipPolicy.GRACE_MS
        advanceUntilIdle()

        assertFalse(manager.state.value.advertising)
        io.mockk.coVerify(atLeast = 1) { relayServer.stop() }
    }

    /** Losing the lease is immediate: a member does not front the board. */
    @Test
    fun `losing the controller lease stops the relay`() = runTest(dispatcher) {
        startOffering()

        snapshots.value = snapshot(controller = "node-other")
        advanceUntilIdle()

        assertFalse(manager.state.value.enabled)
        assertEquals(RelaySuppression.NOT_CONTROLLER, manager.state.value.suppression)
        io.mockk.coVerify(atLeast = 1) { relayServer.stop() }
    }

    // ── The invitation is withdrawn before anything that waits ────────────

    /**
     * The blocker: the claim publication suspends, and it used to run *before*
     * the advertisement came down. A slow or stuck mesh therefore left a relay
     * that had just lost its board sitting there connectable.
     */
    @Test
    fun `a board loss withdraws the offer even while the claim is still publishing`() =
        runTest(dispatcher) {
            startOffering()
            assertTrue(manager.state.value.advertising)

            // Hold the canonical publication open for the rest of the test.
            claimGate = kotlinx.coroutines.CompletableDeferred()
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()

            assertFalse("nothing may still be inviting guests", manager.state.value.advertising)
            assertFalse(
                "and nothing may be accepted from one who got in",
                manager.mayAcknowledgeInboundWrite(),
            )
        }

    /** The same for a full relay: no room means no invitation, publish or not. */
    @Test
    fun `a full relay withdraws the offer before publishing`() = runTest(dispatcher) {
        meshPeers.value = (1..5).map { "peer-$it" }.toSet()
        startOffering()

        claimGate = kotlinx.coroutines.CompletableDeferred()
        guests = 1
        connectionEvents.emit(GattConnectionEvent.Connected("GG:11"))
        runCurrent()

        assertFalse(manager.state.value.advertising)
    }

    // ── What the cell is told ─────────────────────────────────────────────

    /** A claim says "offered" only when an advertisement is genuinely out. */
    @Test
    fun `an advertising start failure is retracted canonically`() = runTest(dispatcher) {
        advertisingFails = true

        startOffering()

        assertFalse(manager.state.value.advertising)
        assertTrue("the cell was told something", claims.isNotEmpty())
        assertTrue(
            "and never that a relay is on offer",
            claims.none { it.offered },
        )
    }

    @Test
    fun `a live advertisement is published as offered`() = runTest(dispatcher) {
        startOffering()

        assertTrue(manager.state.value.advertising)
        assertTrue(claims.any { it.offered })
    }

    // ── The grace window is a ceiling ─────────────────────────────────────

    /**
     * Eight seconds is the contract's maximum, so at eight seconds the board
     * is gone. The previous check was scheduled at 8 s + 250 ms and measured
     * against the wall clock, which could be stepped either way underneath it.
     */
    @Test
    fun `the board is lost at exactly eight seconds, not later`() = runTest(dispatcher) {
        startOffering()

        connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()

        // One millisecond short: still a blip.
        monotonicClockMs += CruxRelayOwnershipPolicy.GRACE_MS - 1
        assertEquals(
            RelayBoardLinkHealth.GRACE,
            CruxRelayOwnershipPolicy.health(
                ConnectionState.DISCONNECTED, CruxRelayOwnershipPolicy.GRACE_MS - 1,
            ),
        )
        io.mockk.coVerify(exactly = 0) { relayServer.stop() }

        // At the deadline: gone, and the relay is down.
        monotonicClockMs += 1
        advanceUntilIdle()

        assertEquals(
            RelayBoardLinkHealth.LOST,
            CruxRelayOwnershipPolicy.health(
                ConnectionState.DISCONNECTED, CruxRelayOwnershipPolicy.GRACE_MS,
            ),
        )
        io.mockk.coVerify(atLeast = 1) { relayServer.stop() }
    }

    /**
     * The claim published *before* the start says the relay is not advertising,
     * because at that moment it is not. Nothing published the state it reached,
     * so the cell was left believing there is no relay when there is one.
     */
    @Test
    fun `the achieved advertising state is published after the start`() = runTest(dispatcher) {
        startOffering()

        assertTrue(manager.state.value.advertising)
        assertTrue("the cell was told the relay is up", claims.last().offered)
    }

    /**
     * The case that made it visible: a retry after a failed start. The
     * pre-start claim is unchanged from last time, so it commits nothing and
     * wakes no reconcile — only publishing the achieved state says anything.
     */
    @Test
    fun `a retry after a failed start publishes the relay it managed to open`() =
        runTest(dispatcher) {
            advertisingFails = true
            startOffering()
            assertFalse(manager.state.value.advertising)
            assertTrue(claims.none { it.offered })

            // The radio comes good and the next reconciliation tries again.
            advertisingFails = false
            snapshots.value = snapshots.value?.copy(sequence = snapshots.value!!.sequence + 1)
                ?.withComputedHash()
            advanceUntilIdle()

            assertTrue(manager.state.value.advertising)
            assertTrue("the recovered relay is canonical", claims.last().offered)
        }
}
