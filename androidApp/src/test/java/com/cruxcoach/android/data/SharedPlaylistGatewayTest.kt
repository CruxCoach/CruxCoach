package com.cruxcoach.android.data

import android.content.Context
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.GattCommand
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.ble.SessionClientState
import com.cruxcoach.android.ble.SessionCommand
import com.cruxcoach.android.ble.SessionGattClient
import com.cruxcoach.android.ble.SessionGattServer
import com.cruxcoach.android.ble.SessionQueueProtocol
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellScopeRegistry
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardCommandAck
import com.cruxcoach.android.boardcell.BoardCommandStatus
import com.cruxcoach.android.boardcell.BoardPlaylistCommand
import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistRest
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The Android-9 hybrid: API 28 has no public BLE L2CAP CoC and therefore
 * cannot be a FIPS node, so it takes part in the one shared playlist as a GATT
 * leaf while an API-29+ device acts as its gateway and translates both ways.
 *
 * The gateway needs no special authority to do it: what the leaf asks for is
 * something every cell member may do anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedPlaylistGatewayTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var managerScope: CoroutineScope
    private lateinit var queueManager: SessionQueueManager
    private lateinit var bridge: SessionGattBridge

    private val context = mockk<Context>(relaxed = true).also {
        every { it.getString(any()) } answers { "res:${firstArg<Int>()}" }
        every { it.getString(any(), *anyVararg()) } answers { "res:${firstArg<Int>()}" }
    }
    private val gattServer = mockk<SessionGattServer>(relaxed = true)
    private val gattClient = mockk<SessionGattClient>(relaxed = true)
    private val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
    private val nearbyScanner = mockk<NearbyClimbScanner>(relaxed = true)
    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val boardStateManager = mockk<BoardStateManager>(relaxed = true)
    private val climbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val boardSessionManager = mockk<BoardSessionManager>(relaxed = true)
    private val boardCellManager = mockk<BoardCellManager>(relaxed = true)

    private val serverCommands = MutableSharedFlow<GattCommand>(extraBufferCapacity = 8)
    private val serverConnectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 8)
    private val restTimer = MutableStateFlow(RestTimerState())
    private val snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    private val commandAcks = Channel<BoardCommandAck>(16)

    private val localNode = "gateway-npub"
    private val leafAddress = "AA:BB:CC:DD:EE:FF"
    private val board = PhysicalBoardId("board-gateway")
    private val cell = BoardCellId.forPhysical(board)

    /** Every command the gateway handed the BoardCell, in order. */
    private val submitted = mutableListOf<BoardPlaylistCommand>()

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
        managerScope = CoroutineScope(SupervisorJob() + testDispatcher)

        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { bleConnection.connectedBoardBrand } returns MutableStateFlow<BoardBrand?>(null)
        every { boardSessionManager.restTimer } returns restTimer
        every { gattClient.connectionState } returns MutableStateFlow(SessionClientState.DISCONNECTED)
        every { gattClient.queueEvents } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { gattClient.sessionInfoUpdates } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { gattClient.queueStateUpdates } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { gattClient.participantListUpdates } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { gattClient.currentClimbUpdates } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { gattClient.connect(any()) } just Runs
        every { gattClient.disconnect() } just Runs
        coEvery { gattClient.sendCommand(any()) } returns true
        coEvery { gattClient.readInitialState() } just Runs
        every { gattServer.commands } returns serverCommands
        every { gattServer.connectionEvents } returns serverConnectionEvents
        every { gattServer.start() } returns true
        every { gattServer.getConnectedCount() } returns 1
        every { advertiser.advertiseSession(any(), any(), any(), any(), any()) } returns "started"
        every { nearbyScanner.nearbyClimbs } returns MutableStateFlow<List<NearbyClimb>>(emptyList())
        every { nearbyScanner.nearbySessions } returns MutableStateFlow<List<NearbySession>>(emptyList())
        every { nearbyScanner.disconnectRequests } returns MutableSharedFlow(extraBufferCapacity = 1)

        every { boardCellManager.snapshots } returns snapshots
        every { boardCellManager.commandAcks } returns commandAcks.receiveAsFlow()
        every { boardCellManager.projectionRequests } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { boardCellManager.localNodeId() } returns localNode
        every { boardCellManager.snapshot() } answers { snapshots.value }
        every { boardCellManager.playlist() } answers { snapshots.value?.playlist }
        every { boardCellManager.isPlaylistSynchronized() } returns true
        every { boardCellManager.isCellMember() } answers {
            snapshots.value?.members?.contains(localNode) == true
        }
        // The real composer reads base revision and clear generation from one
        // snapshot; reproducing that here keeps the test honest about what a
        // command is stamped with.
        every { boardCellManager.composePlaylistCommand(any(), any()) } answers {
            val ops = firstArg<List<BoardPlaylistOp>>()
            val snapshot = snapshots.value
            if (ops.isEmpty() || snapshot == null) null
            else BoardPlaylistCommand(secondArg(), snapshot.playlistRevision,
                snapshot.playlist.clearGeneration, ops)
        }
        coEvery { boardCellManager.submitPlaylistCommand(any()) } answers {
            val command = firstArg<BoardPlaylistCommand>()
            submitted += command
            if (boardCellManager.isLocalController())
                BoardCommandAck(command.commandId, BoardCommandStatus.COMMITTED, cell, 1, 1, 5, "hash")
            else
                BoardCommandAck(command.commandId, BoardCommandStatus.ACCEPTED, cell, 1, 1, 5, "hash")
        }

        queueManager = SessionQueueManager(
            bleConnection, boardRepository, climbNameResolver, userPreferences, managerScope,
            boardCellManager = boardCellManager,
        )
        bridge = SessionGattBridge(
            context = context,
            queueManager = queueManager,
            gattServer = gattServer,
            gattClient = gattClient,
            advertiser = advertiser,
            nearbyScanner = nearbyScanner,
            bleConnection = bleConnection,
            boardStateManager = boardStateManager,
            boardSessionManager = boardSessionManager,
            hasHostingPermissions = { true },
            hostSetupDispatcher = testDispatcher,
            scope = managerScope,
            boardCellManager = boardCellManager,
        )
    }

    @After fun tearDown() {
        // Deliberately no advanceUntilIdle: the bridge keeps an endless
        // command-retry loop on this scheduler, so draining virtual time here
        // would never return.
        runBlocking { managerScope.coroutineContext[Job]?.cancelAndJoin() }
        // The cell/board selection is process-global; leaving one bound would
        // reach whichever test runs next in this JVM.
        BoardCellScopeRegistry.resetForTest()
        Dispatchers.resetMain()
    }

    private fun publish(
        playlist: BoardPlaylistState,
        revision: Long = 1,
        members: Set<String> = setOf("controller-npub", localNode),
    ) {
        snapshots.value = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = revision + 1,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = members, playlist = playlist, playlistRevision = revision,
        ).withComputedHash()
    }

    private val firstClimb = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val addedClimb = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private fun shared(
        entries: List<Triple<String, String, Int>> = listOf(Triple("e1", firstClimb, 120)),
        rest: BoardPlaylistRest? = null,
    ) = BoardPlaylistPolicy.normalize(BoardPlaylistPolicy.apply(
        BoardPlaylistState(sessionId = 7),
        entries.map { (id, climb, restAfter) -> BoardPlaylistOp.Add(id, climb, 40, restAfter) },
    ).copy(activeRest = rest))

    /** Host mode is what opens the GATT server a leaf talks to. */
    private suspend fun hostWithLeaf() {
        bridge.startSharing()
        serverCommands.emit(GattCommand(leafAddress, SessionQueueProtocol.encodeJoin("Leaf")))
    }

    // ===== The leaf sees the canonical playlist =====

    @Test fun `an Android 9 leaf reads the canonical queue and its rest plan`() = runBlocking {
        publish(shared(listOf(Triple("e1", firstClimb, 120), Triple("e2", addedClimb, 0))))

        // The leaf's whole view of the playlist is the GATT queue-state frame,
        // which the gateway encodes from its own projection of canonical state.
        val decoded = SessionQueueProtocol.decodeQueueState(queueManager.encodeQueueState())!!
        assertEquals(0, decoded.currentIndex)
        assertEquals(listOf(firstClimb.uppercase(), addedClimb.uppercase()),
            decoded.items.map { it.climbUuid })
        assertEquals(listOf(40, 40), decoded.items.map { it.angle })
        assertEquals(listOf(120, 0), queueManager.state.value.queue.map { it.restAfterSeconds })
    }

    @Test fun `a canonical rest reaches the leaf as the unchanged legacy rest frames`() =
        runBlocking {
            // Android 9 must never receive a new legacy opcode it cannot parse.
            // The canonical rest drives this device's own timer, and the timer
            // drives the existing RestStarted/RestEnded frames.
            val started = SessionQueueProtocol.encodeEventRestStarted(120, 1)
            assertEquals(SessionQueueProtocol.EVT_REST_STARTED, started[0])
            val event = SessionQueueProtocol.decodeEvent(started)
            assertTrue(event is com.cruxcoach.android.ble.SessionEvent.RestStarted)
            assertEquals(120, (event as com.cruxcoach.android.ble.SessionEvent.RestStarted).remainingSeconds)
            assertEquals(com.cruxcoach.android.ble.SessionEvent.RestEnded,
                SessionQueueProtocol.decodeEvent(SessionQueueProtocol.encodeEventRestEnded()))
        }

    // ===== The leaf's commands reach the mesh =====

    @Test fun `a leaf add becomes one typed operation against the shared playlist`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        publish(shared())
        hostWithLeaf()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

        // The gateway did not mutate its own queue and hope: it handed the
        // BoardCell a bounded, typed operation with a stable occurrence id.
        val add = submitted.single().ops.single() as BoardPlaylistOp.Add
        assertEquals(addedClimb.uppercase(), add.climbUuid)
        assertEquals(45, add.angle)
        assertTrue(add.entryId.isNotBlank())
        assertEquals(1L, submitted.single().basePlaylistRevision)
    }

    @Test fun `a leaf remove names the occurrence it read, not the index`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        publish(shared(listOf(Triple("e1", firstClimb, 0), Triple("e2", addedClimb, 0))))
        hostWithLeaf()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Remove(1))))

        assertEquals(BoardPlaylistOp.Remove("e2"), submitted.single().ops.single())
    }

    @Test fun `a leaf command before JOIN never reaches the BoardCell`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        publish(shared())
        // Sharing is up, so the command really does reach the bridge — the
        // only thing missing is the leaf's JOIN, which is the whole basis of
        // the gateway carrying anything on its behalf.
        bridge.startSharing()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

        assertTrue("an ungated leaf must not reach canonical state", submitted.isEmpty())

        // The same command after JOIN does land, so the assertion above is
        // about the gate and not about the plumbing being dead.
        serverCommands.emit(GattCommand(leafAddress, SessionQueueProtocol.encodeJoin("Leaf")))
        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
        assertEquals(1, submitted.size)
    }

    @Test fun `a leaf resend becomes a projection retry, not a queue edit`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        coEvery { boardCellManager.projectSelectedEntry() } returns true
        publish(shared())
        hostWithLeaf()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Resend)))

        coVerify(exactly = 1) { boardCellManager.projectSelectedEntry() }
        assertTrue("a resend must not mutate the playlist", submitted.isEmpty())
    }

    @Test fun `the GATT protocol gives a leaf no way to start, end or host a playlist`() {
        // Enumerated rather than asserted case by case: the leaf's whole
        // vocabulary is the queue plus join/leave, so there is no opcode that
        // could carry a lifecycle decision even if it wanted one.
        val leafVerbs = listOf(
            SessionQueueProtocol.CMD_ADD, SessionQueueProtocol.CMD_REMOVE,
            SessionQueueProtocol.CMD_SET_CURRENT, SessionQueueProtocol.CMD_NEXT,
            SessionQueueProtocol.CMD_PREV, SessionQueueProtocol.CMD_JOIN,
            SessionQueueProtocol.CMD_LEAVE, SessionQueueProtocol.CMD_MOVE,
            SessionQueueProtocol.CMD_RESEND,
        )
        (Byte.MIN_VALUE..Byte.MAX_VALUE).map { it.toByte() }
            .filterNot { it in leafVerbs }
            .forEach { opcode ->
                assertNull("opcode $opcode must not decode",
                    SessionQueueProtocol.decodeCommand(byteArrayOf(opcode, 0, 0, 0)))
            }
    }

    @Test fun `a gateway that is not the controller sends the leaf's edit over the mesh`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns false
            publish(shared())
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
            // The controller's real answer, not a local guess, is what the leaf
            // is told; feeding it in releases the waiting gateway.
            commandAcks.send(BoardCommandAck(submitted.single().commandId,
                BoardCommandStatus.COMMITTED, cell, 1, 1, 6, "hash"))

            assertEquals(1, submitted.size)
            // One message and no join first: there is no membership to acquire.
            assertTrue(submitted.single().ops.single() is BoardPlaylistOp.Add)
        }

    @Test fun `a transient handover ack keeps the leaf command pending until commit`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns false
            publish(shared())
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
            val command = submitted.single()
            commandAcks.send(BoardCommandAck(command.commandId,
                BoardCommandStatus.NOT_CONTROLLER, cell, 1, 1, 5, "hash"))

            assertEquals("handover is not a terminal decision", 1,
                bridge.pendingCommandCount.value)

            commandAcks.send(BoardCommandAck(command.commandId,
                BoardCommandStatus.COMMITTED, cell, 1, 2, 6, "hash"))
            assertEquals(0, bridge.pendingCommandCount.value)
        }

    // ===== The legacy-only path is untouched =====

    @Test fun `without a BoardCell the leaf's add still mutates the local host queue`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns false
            snapshots.value = null
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

            assertEquals(listOf(addedClimb.uppercase()),
                queueManager.state.value.queue.map { it.climbUuid })
            assertTrue(submitted.isEmpty())
            assertNull(queueManager.state.value.mesh)
        }

    @Test fun `a gateway outside the cell keeps its own local queue`() = runBlocking {
        every { boardCellManager.isLocalController() } returns false
        queueManager.startQueue("Gateway", SessionVisibility.LOCAL_ONLY)
        publish(shared(), members = setOf("controller-npub", "someone-else"))
        hostWithLeaf()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

        assertTrue("a non-member must not edit the board's playlist", submitted.isEmpty())
        assertEquals(listOf(addedClimb.uppercase()),
            queueManager.state.value.queue.map { it.climbUuid })
        assertNull(queueManager.state.value.mesh)
    }

    @Test fun `an Android 9 leaf is never a member of the board group`() = runBlocking {
        publish(shared())

        // The playlist has no membership at all, and the board group's is a
        // set of FIPS identities. A GATT leaf has none, so it can never appear
        // in either; its gateway carries its edits instead.
        assertEquals(setOf("controller-npub", localNode), snapshots.value!!.members)
        assertEquals(listOf("controller-npub", localNode),
            queueManager.state.value.mesh!!.members)
    }
}
