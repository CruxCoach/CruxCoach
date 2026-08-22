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
import kotlinx.coroutines.test.advanceTimeBy
import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistOps
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardRelayOperation
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
import kotlinx.coroutines.test.runCurrent
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
    /** What the catalogue could establish about the guest's bytes. */
    private var writeIdentity: RelayWriteIdentity = RelayWriteIdentity.Named("climb-a", 40)
    private var layoutId = 1
    /** What the catalogue says the guest's climb belongs to. */
    private var climbLayoutId = 1L
    private var boardAngle = 40
    private var routingMode = RelayInboundClimbMode.PROJECT_NOW
    private var intentAccepted = true
    /** Whether the controller commits the terminal `landed` record of a write
     *  that has no occurrence to carry it. */
    private var landedRecordAccepted = true
    /** Whether the controller commits the joint occurrence + landed batch. */
    private var terminalCommitAccepted = true
    private var projectionSucceeds = true
    /** How long the board takes to answer, in virtual time. */
    private var boardWriteDelayMs = 0L
    /** How many times the wall was actually written. */
    private var boardWrites = 0
    private val relayOperationsWritten = mutableSetOf<String>()
    /** Monotonic time the manager reads, driven by the test scheduler. */
    private val monotonic get() = 10_000L + dispatcher.scheduler.currentTime

    /**
     * Canonical playlist state, as the controller would really hold it.
     *
     * The relay's identity comes out of the cell, so a mock that never records
     * anything makes every retry re-mint its ids — which is a property of the
     * mock and not of the code. This keeps the one piece of canonical state the
     * ingress reads.
     */
    private var canonicalPlaylist = BoardPlaylistState(sessionId = 7)

    private lateinit var relayServer: RelayGattServer
    private lateinit var bridge: SessionGattBridge
    private lateinit var boardCellManager: BoardCellManager
    private lateinit var manager: CruxRelayManager

    private fun snapshot(controller: String = me) = BoardCellSnapshot(
        cellId = BoardCellId.forPhysical(physical), physicalBoardId = physical,
        epoch = 1, sequence = 4, controllerId = controller, controllerTerm = 1,
        lineageId = "lineage", members = setOf(me), availability = BoardCellAvailability.ACTIVE,
        playlist = BoardPlaylistState(sessionId = 7),
    ).withComputedHash()

    /**
     * A write as the GATT server hands it over — including the deadline it set
     * when the bytes arrived, which is the one everything downstream is timed
     * against.
     */
    private fun inbound(
        requestId: Int,
        framesHash: Long = 77L,
        address: String = "GG:01",
        deadlineAtMs: Long = monotonic + RelayGattServer.RELAY_OPERATION_DEADLINE_MS,
    ) = RelayInboundClimb(
        deviceAddress = address,
        climb = CompleteClimb(
            rawBytes = byteArrayOf(1, 2, 3), chunks = listOf(byteArrayOf(1, 2, 3)),
            framesHash = framesHash, holdCount = 5,
        ),
        pendingResponse = requestId,
        deadlineAtMs = deadlineAtMs,
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
        boardCellManager = mockk(relaxed = true) {
            every { this@mockk.snapshots } returns this@CruxRelayIngressAckTest.snapshots
            every { snapshot() } answers { this@CruxRelayIngressAckTest.snapshots.value }
            every { playlist() } answers { canonicalPlaylist }
            every { localNodeId() } returns me
            every { membershipTransition } returns MutableStateFlow(MeshMembershipTransition.IDLE)
            coEvery { projectExternal(any(), any(), any(), any()) } coAnswers {
                // The board takes as long as the test says it does.
                if (boardWriteDelayMs > 0) kotlinx.coroutines.delay(boardWriteDelayMs)
                // The canonical serializer deduplicates by command id, so a
                // retry of the same operation reaches the wall only once.
                if (relayOperationsWritten.add(arg<String>(2))) boardWrites++
                // Duplicate counts as delivered — the wall already shows it —
                // and needs only an ack, so the success path is reachable
                // without standing up a whole envelope.
                if (projectionSucceeds) ProjectionResult.Duplicate(mockk(relaxed = true))
                else ProjectionResult.Refused("board refused")
            }
        }
        val projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true) {
            coEvery { identifyExternal(any()) } answers { writeIdentity }
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
            every { relayInboundClimbMode } answers {
                MutableStateFlow(this@CruxRelayIngressAckTest.routingMode)
            }
            every { boardLayoutId } returns
                MutableStateFlow(this@CruxRelayIngressAckTest.layoutId)
            every { boardAngle } returns
                MutableStateFlow(this@CruxRelayIngressAckTest.boardAngle)
        }
        bridge = mockk(relaxed = true) {
            coEvery { recordRelayIntent(any()) } answers {
                val terminal = firstArg<BoardRelayOperation>().landed
                val intentAccepted = if (terminal) landedRecordAccepted else intentAccepted
                if (intentAccepted) {
                    canonicalPlaylist = BoardPlaylistPolicy.apply(
                        canonicalPlaylist,
                        BoardPlaylistOps.recordRelayOperation(firstArg()),
                    )
                }
                intentAccepted
            }
            // The controller committed it, occurrence and terminal record
            // together — without this the terminal callback never fires and
            // nothing is ever recorded as landed.
            every {
                appendSharedPlaylistEntry(any(), any(), any(), any(), any(), any())
            } answers {
                arg<BoardRelayOperation?>(4)?.let { operation ->
                    canonicalPlaylist = BoardPlaylistPolicy.apply(
                        canonicalPlaylist,
                        BoardPlaylistOps.recordRelayOperation(operation, landed = true),
                    )
                }
                lastArg<((Boolean) -> Unit)?>()?.invoke(true)
            }
            every {
                adoptProjectedEntry(any(), any(), any(), any(), any(), any())
            } answers {
                // Refusing is a real outcome — a revision conflict, a stop, a
                // handover — and a mock that always commits leaves the path
                // that matters most untested.
                val committed = terminalCommitAccepted
                if (committed) {
                    arg<BoardRelayOperation?>(4)?.let { operation ->
                        canonicalPlaylist = BoardPlaylistPolicy.apply(
                            canonicalPlaylist,
                            BoardPlaylistOps.recordRelayOperation(operation, landed = true),
                        )
                    }
                }
                lastArg<((Boolean) -> Unit)?>()?.invoke(committed)
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
            projectionCoordinator = projectionCoordinator,
            boardCellManager = boardCellManager,
            userPreferences = preferences,
            gattBridge = bridge,
            boardRepository = repository,
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

    /** A climb this board cannot show: the guest is told, not thanked. */
    @Test
    fun `an angle mismatch is refused at the ATT layer`() = runTest(dispatcher) {
        writeIdentity = RelayWriteIdentity.Named("climb-a", 25)
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
        writeIdentity = RelayWriteIdentity.Named("climb-b", 40)
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
        // Only the immediate reconciliation: inside the grace window the
        // server is still up, which is exactly the state where a write must
        // not be acknowledged as delivered.
        runCurrent()

        climbs.emit(inbound(requestId = 51))
        runCurrent()

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

    // ── When the board takes longer than the guest's deadline ─────────────

    /**
     * A legitimate multi-chunk write can outlive the ATT window: one BLE chunk
     * alone may wait five seconds. The guest is told it failed — the
     * transaction cannot be held open forever — and the climb still reaches
     * the wall, so canonical state records it. What must not happen is the
     * combination the old code produced: an error the guest retries into a
     * *second* action.
     */
    @Test
    fun `a board write that outlives the deadline still lands, and the retry succeeds`() =
        runTest(dispatcher) {
            boardWriteDelayMs = RelayGattServer.RELAY_OPERATION_DEADLINE_MS + 2_000
            relayRunning()

            climbs.emit(inbound(requestId = 91))
            advanceUntilIdle()

            // The occurrence was adopted: the wall really is showing it, so
            // canonical state has to agree with the room.
            coVerify {
                bridge.adoptProjectedEntry(any(), any(), any(), any(), any(), any())
            }

            // And the guest's retry is answered as delivered rather than as a
            // duplicate error, so it cannot become a contradictory second act.
            clockMs += 5_000
            climbs.emit(inbound(requestId = 92))
            advanceUntilIdle()

            coVerify { relayServer.settle(92, true) }
        }

    /**
     * The fence: once the guest has been told it failed, this device does not
     * *start* a board write on their behalf. A write already under way is a
     * different matter and is never cancelled — half a climb is a state the
     * board protocol cannot undo.
     */
    @Test
    fun `an operation past its deadline does not start a board write`() = runTest(dispatcher) {
        relayRunning()
        // The intent barrier itself takes longer than the whole window.
        coEvery { bridge.recordRelayIntent(any()) } coAnswers {
            kotlinx.coroutines.delay(RelayGattServer.RELAY_OPERATION_DEADLINE_MS + 1_000)
            true
        }

        climbs.emit(inbound(requestId = 93))
        advanceUntilIdle()

        coVerify(exactly = 0) { boardCellManager.projectExternal(any(), any(), any(), any()) }
        coVerify { relayServer.settle(93, false) }
    }

    // ── A success answer that never reached the guest ─────────────────────

    /** Same address: the retry finds the landed record and is told so. */
    @Test
    fun `a lost success ack is replayed on the same address`() = runTest(dispatcher) {
        relayRunning()
        climbs.emit(inbound(requestId = 101))
        advanceUntilIdle()

        clockMs += 5_000
        climbs.emit(inbound(requestId = 102))
        advanceUntilIdle()

        coVerify { relayServer.settle(102, true) }
        // Exactly one board write for the two attempts.
        coVerify(exactly = 1) { boardCellManager.projectExternal(any(), any(), any(), any()) }
    }

    // ── The deadline is the guest's, not this device's ────────────────────

    /**
     * The write waited in the flow before this device looked at it.
     *
     * The manager used to start its own twenty seconds *here*, after the hop,
     * the catalogue lookup and the preference reads — so it could be inside
     * "its" window while the guest had already been told the write failed, and
     * write a board for somebody who had given up. The deadline now arrives
     * with the write.
     */
    @Test
    fun `a write delayed on its way to the collector is already out of time`() =
        runTest(dispatcher) {
            relayRunning()
            // Set when the bytes arrived; the queue then held them too long.
            val arrivedAt = monotonic
            advanceTimeBy(RelayGattServer.RELAY_OPERATION_DEADLINE_MS + 1_000)

            climbs.emit(
                inbound(
                    requestId = 111,
                    deadlineAtMs = arrivedAt + RelayGattServer.RELAY_OPERATION_DEADLINE_MS,
                ),
            )
            advanceUntilIdle()

            coVerify(exactly = 0) { boardCellManager.projectExternal(any(), any(), any(), any()) }
            coVerify { relayServer.settle(111, false) }
        }

    /**
     * The same fence on the other routing branch. Adding an occurrence for
     * somebody who has been told their write failed is the same lie in a
     * quieter place — and this branch had no check at all.
     */
    @Test
    fun `an append whose barrier outlives the deadline adds nothing`() = runTest(dispatcher) {
        routingMode = RelayInboundClimbMode.APPEND_TO_END
        relayRunning()
        coEvery { bridge.recordRelayIntent(any()) } coAnswers {
            kotlinx.coroutines.delay(RelayGattServer.RELAY_OPERATION_DEADLINE_MS + 1_000)
            true
        }

        climbs.emit(inbound(requestId = 112))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            bridge.appendSharedPlaylistEntry(any(), any(), any(), any(), any(), any())
        }
        coVerify { relayServer.settle(112, false) }
    }

    // ── A landed request stays landed, whatever the wall does now ─────────

    /**
     * The group moved on, which is the ordinary thing for a group to do.
     *
     * The success path used to be derived from the wall — is this occurrence
     * still the current one, is the projection still exactly it — so the next
     * climb erased it. A retry then wrote the board again.
     */
    @Test
    fun `a landed request is replayed after the group has moved on`() = runTest(dispatcher) {
        relayRunning()
        climbs.emit(inbound(requestId = 121))
        advanceUntilIdle()

        // Somebody sends the next climb: current and projection both move.
        canonicalPlaylist = BoardPlaylistPolicy.apply(
            canonicalPlaylist,
            listOf(BoardPlaylistOp.Add("later", "climb-b", 40)),
        )
        snapshots.value = snapshots.value?.copy(
            projection = BoardProjection("climb-b", 40), projectionKnown = true,
        )?.withComputedHash()

        clockMs += 5_000
        climbs.emit(inbound(requestId = 122))
        advanceUntilIdle()

        coVerify { relayServer.settle(122, true) }
        coVerify(exactly = 1) { boardCellManager.projectExternal(any(), any(), any(), any()) }
    }

    /** Under `APPEND_TO_END` the occurrence is never current, so this was the
     *  branch where the replay never worked at all. */
    @Test
    fun `a landed append is replayed rather than queued twice`() = runTest(dispatcher) {
        routingMode = RelayInboundClimbMode.APPEND_TO_END
        relayRunning()
        climbs.emit(inbound(requestId = 131))
        advanceUntilIdle()

        clockMs += 5_000
        climbs.emit(inbound(requestId = 132))
        advanceUntilIdle()

        coVerify { relayServer.settle(132, true) }
        coVerify(exactly = 1) {
            bridge.appendSharedPlaylistEntry(any(), any(), any(), any(), any(), any())
        }
    }

    /** And the same on a rotated address, which is how a lost ACK usually looks. */
    @Test
    fun `a landed request is replayed to a guest on a new address`() = runTest(dispatcher) {
        relayRunning()
        climbs.emit(inbound(requestId = 141, address = "GG:01"))
        advanceUntilIdle()

        clockMs += 5_000
        climbs.emit(inbound(requestId = 142, address = "HH:02"))
        advanceUntilIdle()

        coVerify { relayServer.settle(142, true) }
        coVerify(exactly = 1) { boardCellManager.projectExternal(any(), any(), any(), any()) }
    }

    // ── The link dropping while an accepted operation is in flight ────────
    //
    // The sequence the contract forbids, and the one the old tests missed —
    // they only covered a write that *arrived* after the link was gone:
    //
    //     accepted while healthy → operation runs → board link drops
    //     → advertising and admission withdrawn → grace → success ACK
    //
    // Health was checked where the operation started and never again, and
    // every step in between suspends.

    /** The link goes while the intent barrier is travelling the mesh. */
    @Test
    fun `a link lost during the intent barrier withholds the success`() = runTest(dispatcher) {
        relayRunning()
        coEvery { bridge.recordRelayIntent(any()) } coAnswers {
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()
            true
        }

        climbs.emit(inbound(requestId = 201))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(201, true) }
    }

    /** The link goes while the chunks are going out. */
    @Test
    fun `a link lost during the board write withholds the success`() = runTest(dispatcher) {
        relayRunning()
        coEvery { boardCellManager.projectExternal(any(), any(), any(), any()) } coAnswers {
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()
            ProjectionResult.Duplicate(mockk(relaxed = true))
        }

        climbs.emit(inbound(requestId = 202))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(202, true) }
        coVerify { relayServer.settle(202, false) }
    }

    /** And the climb that did reach the wall is still recorded. */
    @Test
    fun `a write that landed as the link went is still canonical`() = runTest(dispatcher) {
        relayRunning()
        coEvery { boardCellManager.projectExternal(any(), any(), any(), any()) } coAnswers {
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()
            ProjectionResult.Duplicate(mockk(relaxed = true))
        }

        climbs.emit(inbound(requestId = 203))
        advanceUntilIdle()

        assertTrue(
            "the occurrence is on the list even though the guest was not told",
            canonicalPlaylist.relayOperations.any { it.landed },
        )
    }

    /**
     * Which is what makes the withheld ACK recoverable: once the board is back,
     * the same request replays its success with no second write.
     */
    @Test
    fun `the withheld success is replayed after the board comes back`() = runTest(dispatcher) {
        relayRunning()
        coEvery { boardCellManager.projectExternal(any(), any(), any(), any()) } coAnswers {
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()
            ProjectionResult.Duplicate(mockk(relaxed = true))
        }
        climbs.emit(inbound(requestId = 204))
        advanceUntilIdle()
        coVerify { relayServer.settle(204, false) }

        // Reconnect inside the grace window, then the guest retries.
        connectionState.value = ConnectionState.CONNECTED
        runCurrent()
        clockMs += 5_000
        climbs.emit(inbound(requestId = 205))
        advanceUntilIdle()

        coVerify { relayServer.settle(205, true) }
        coVerify(exactly = 1) { boardCellManager.projectExternal(any(), any(), any(), any()) }
    }

    /** The append branch answers late too, and is fenced the same way. */
    @Test
    fun `a link lost before the append callback withholds the success`() = runTest(dispatcher) {
        routingMode = RelayInboundClimbMode.APPEND_TO_END
        relayRunning()
        every {
            bridge.appendSharedPlaylistEntry(any(), any(), any(), any(), any(), any())
        } answers {
            arg<BoardRelayOperation?>(4)?.let { operation ->
                canonicalPlaylist = BoardPlaylistPolicy.apply(
                    canonicalPlaylist,
                    BoardPlaylistOps.recordRelayOperation(operation, landed = true),
                )
            }
            connectionState.value = ConnectionState.DISCONNECTED
            runCurrent()
            lastArg<((Boolean) -> Unit)?>()?.invoke(true)
        }

        climbs.emit(inbound(requestId = 206))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(206, true) }
        assertTrue(canonicalPlaylist.relayOperations.any { it.landed })
    }

    /** Grace expiring is not a different answer — it is the same one, later. */
    @Test
    fun `a success is still withheld once the grace window expires`() = runTest(dispatcher) {
        relayRunning()
        coEvery { boardCellManager.projectExternal(any(), any(), any(), any()) } coAnswers {
            connectionState.value = ConnectionState.DISCONNECTED
            advanceTimeBy(CruxRelayOwnershipPolicy.GRACE_MS + 100)
            ProjectionResult.Duplicate(mockk(relaxed = true))
        }

        climbs.emit(inbound(requestId = 207))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(207, true) }
    }

    /** A handover mid-operation is the other half of the fence. */
    @Test
    fun `a lease lost during the operation withholds the success`() = runTest(dispatcher) {
        relayRunning()
        coEvery { boardCellManager.projectExternal(any(), any(), any(), any()) } coAnswers {
            snapshots.value = snapshot(controller = "node-other")
            runCurrent()
            ProjectionResult.Duplicate(mockk(relaxed = true))
        }

        climbs.emit(inbound(requestId = 208))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(208, true) }
    }

    // ── The answer waits for the joint commit ─────────────────────────────

    /**
     * The board took the bytes and the controller refused the batch that would
     * have recorded them.
     *
     * The old order answered the guest as soon as the wall had the climb, which
     * produces the one combination nobody recovers from: board written, guest
     * told success, canonical playlist with no occurrence and no `landed` — so
     * nobody has any reason to retry. "Both or neither" has to include the
     * answer.
     */
    @Test
    fun `a refused terminal commit is not reported as success`() = runTest(dispatcher) {
        terminalCommitAccepted = false
        relayRunning()

        climbs.emit(inbound(requestId = 301))
        advanceUntilIdle()

        coVerify(exactly = 0) { relayServer.settle(301, true) }
        coVerify { relayServer.settle(301, false) }
        assertTrue("nothing was recorded", canonicalPlaylist.relayOperations.none { it.landed })
    }

    /**
     * And the retry makes it good: the same ids, exactly one occurrence, and no
     * second write — the canonical serializer refuses to put the same operation
     * on the wall twice.
     */
    @Test
    fun `the retry after a refused commit lands once, without writing again`() =
        runTest(dispatcher) {
            terminalCommitAccepted = false
            relayRunning()
            climbs.emit(inbound(requestId = 302))
            advanceUntilIdle()
            coVerify { relayServer.settle(302, false) }

            // Same bytes, same guest: the same operation, retried.
            terminalCommitAccepted = true
            clockMs += 5_000
            climbs.emit(inbound(requestId = 303))
            advanceUntilIdle()

            coVerify { relayServer.settle(303, true) }
            val recorded = canonicalPlaylist.relayOperations
            assertEquals("one operation, not two", 1, recorded.size)
            assertTrue(recorded.single().landed)
            assertEquals(
                "the wall is written once for the two attempts",
                1, boardWrites,
            )
        }

    /** The guest is answered after the record exists, not before it. */
    @Test
    fun `the success follows the commit rather than the board write`() = runTest(dispatcher) {
        relayRunning()

        climbs.emit(inbound(requestId = 304))
        advanceUntilIdle()

        coVerify { relayServer.settle(304, true) }
        assertTrue(canonicalPlaylist.relayOperations.single().landed)
    }

    // ── A write with no name is still a write with rules ──────────────────

    /**
     * An unlisted or mirrored climb reaches the wall — that is what a relay is
     * for — but it reaches it as an operation: derived identity, canonical
     * record, and "finished" committed before the guest is told.
     */
    @Test
    fun `an unnamed climb is delivered and canonically finished`() = runTest(dispatcher) {
        writeIdentity = RelayWriteIdentity.Anonymous
        relayRunning()

        climbs.emit(inbound(requestId = 141))
        advanceUntilIdle()

        coVerify { relayServer.settle(141, true) }
        assertEquals("the wall was written", 1, boardWrites)
        assertTrue("the request is canonically finished",
            canonicalPlaylist.relayOperations.single().landed)
        assertTrue("there is nothing to put on the list", canonicalPlaylist.entries.isEmpty())
    }

    /**
     * The retry. The ids used to be made out of the clock, so the same bytes
     * arriving again were a different operation and wrote the wall a second
     * time.
     */
    @Test
    fun `a repeated unnamed write neither re-writes the wall nor mints a second operation`() =
        runTest(dispatcher) {
            writeIdentity = RelayWriteIdentity.Anonymous
            relayRunning()
            climbs.emit(inbound(requestId = 142))
            advanceUntilIdle()

            clockMs += 5_000
            climbs.emit(inbound(requestId = 143))
            advanceUntilIdle()

            coVerify { relayServer.settle(143, true) }
            assertEquals("the wall is written once", 1, boardWrites)
            assertEquals("one operation, not two", 1, canonicalPlaylist.relayOperations.size)
        }

    /** LEDs this board does not have: refused, and the wall never touched. */
    @Test
    fun `a climb written for another board never reaches the wall`() = runTest(dispatcher) {
        writeIdentity = RelayWriteIdentity.ForeignBoard
        relayRunning()

        climbs.emit(inbound(requestId = 144))
        advanceUntilIdle()

        coVerify { relayServer.settle(144, false) }
        assertEquals("the wall was not written", 0, boardWrites)
    }

    /** Nothing decidable at all — and a wall is not changed on nothing. */
    @Test
    fun `an unreadable write never reaches the wall`() = runTest(dispatcher) {
        writeIdentity = RelayWriteIdentity.Undecidable
        relayRunning()

        climbs.emit(inbound(requestId = 145))
        advanceUntilIdle()

        coVerify { relayServer.settle(145, false) }
        assertEquals("the wall was not written", 0, boardWrites)
    }

    /**
     * `APPEND_TO_END` means the wall is not taken. A write with no occurrence
     * to queue used to be projected under it anyway, which is that setting's
     * exact opposite.
     */
    @Test
    fun `an unnamed climb does not take the wall when the group asked for the queue`() =
        runTest(dispatcher) {
            routingMode = RelayInboundClimbMode.APPEND_TO_END
            writeIdentity = RelayWriteIdentity.Anonymous
            relayRunning()

            climbs.emit(inbound(requestId = 146))
            advanceUntilIdle()

            coVerify { relayServer.settle(146, false) }
            assertEquals("the wall was not written", 0, boardWrites)
        }

    /**
     * "Both or neither" for a write that cannot have an occurrence: the
     * `landed` record is the terminal half, so a refused one is answered
     * negative — and the retry still writes the wall exactly once.
     */
    @Test
    fun `an unnamed delivery whose terminal record is refused is not reported as delivered`() =
        runTest(dispatcher) {
            writeIdentity = RelayWriteIdentity.Anonymous
            landedRecordAccepted = false
            relayRunning()

            climbs.emit(inbound(requestId = 147))
            advanceUntilIdle()

            coVerify { relayServer.settle(147, false) }
            assertEquals(1, boardWrites)

            landedRecordAccepted = true
            clockMs += 5_000
            climbs.emit(inbound(requestId = 148))
            advanceUntilIdle()

            coVerify { relayServer.settle(148, true) }
            assertEquals("the wall is written once for the two attempts", 1, boardWrites)
            assertEquals("one operation, not two", 1, canonicalPlaylist.relayOperations.size)
        }
}
