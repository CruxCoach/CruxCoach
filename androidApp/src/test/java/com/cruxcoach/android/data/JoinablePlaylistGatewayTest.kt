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
import com.cruxcoach.android.ble.QueueItem
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
import com.cruxcoach.android.boardcell.BoardPlaylistAuthority
import com.cruxcoach.android.boardcell.BoardPlaylistEntry
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
import io.mockk.verify
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
 * cannot be a FIPS node, so it takes part in the one canonical playlist as a
 * GATT leaf while an API-29+ device acts as its gateway and translates both
 * ways.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinablePlaylistGatewayTest {

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
        every { boardCellManager.sessionCommands } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { boardCellManager.localNodeId() } returns localNode
        every { boardCellManager.snapshot() } answers { snapshots.value }
        every { boardCellManager.playlist() } answers { snapshots.value?.playlist }

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

    private fun publish(playlist: BoardPlaylistState, revision: Long = 1) {
        snapshots.value = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = revision + 1,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub", localNode),
            playlist = playlist, playlistRevision = revision,
        ).withComputedHash()
    }

    private fun joinable(
        items: List<BoardPlaylistEntry> = listOf(BoardPlaylistEntry("climber-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 40)),
        rests: List<Int> = listOf(120),
        rest: BoardPlaylistRest? = null,
        host: String = localNode,
        members: List<String> = listOf(localNode),
    ) = BoardPlaylistPolicy.normalize(BoardPlaylistState(
        sessionId = 7, currentIndex = 0, items = items, restAfterSeconds = rests,
        hostId = host, members = members, activeRest = rest))

    /** Host mode is what opens the GATT server a leaf talks to. */
    private suspend fun hostWithLeaf() {
        bridge.startSharing()
        serverCommands.emit(GattCommand(leafAddress, SessionQueueProtocol.encodeJoin("Leaf")))
    }

    private val addedClimb = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    // ===== The leaf sees the canonical playlist =====

    @Test fun `an Android 9 leaf reads the canonical queue and its rest plan`() = runBlocking {
        publish(joinable(
            items = listOf(BoardPlaylistEntry("climber-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 40), BoardPlaylistEntry("climber-b", addedClimb, 45)),
            rests = listOf(120, 0)))

        // The leaf's whole view of the playlist is the GATT queue-state frame,
        // which the gateway encodes from its own projection of canonical state.
        val decoded = SessionQueueProtocol.decodeQueueState(queueManager.encodeQueueState())!!
        assertEquals(0, decoded.currentIndex)
        assertEquals(listOf("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA".replace("-", ""),
            addedClimb.uppercase()), decoded.items.map { it.climbUuid })
        assertEquals(listOf(40, 45), decoded.items.map { it.angle })
        // The rest plan is canonical state now, so the gateway holds the real
        // seconds rather than the zero a migrated host used to see.
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

    /** Captures what the bridge hands the BoardCell for a local commit. */
    private class LocalCommit {
        val authority = slot<BoardPlaylistAuthority>()
        val applied = slot<(BoardPlaylistState, Boolean) -> BoardPlaylistState?>()
    }

    private fun stubLocalCommit(manager: BoardCellManager): LocalCommit {
        val capture = LocalCommit()
        coEvery {
            manager.commitLocalSessionCommand(any(), any(), capture(capture.authority),
                capture(capture.applied))
        } answers {
            BoardCommandAck("committed-command", BoardCommandStatus.COMMITTED, cell, 1, 1, 5, "hash")
        }
        return capture
    }

    @Test fun `a leaf add is committed into BoardCell by a gateway that is the controller`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns true
            every { boardCellManager.isPlaylistMember() } returns true
            val capture = stubLocalCommit(boardCellManager)
            publish(joinable())
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

            // The gateway did not mutate its own queue and hope: it handed the
            // BoardCell a derivation of canonical state.
            assertTrue("gateway must commit into BoardCell", capture.applied.isCaptured)
            val next = capture.applied.captured(snapshots.value!!.playlist, true)!!
            assertEquals(2, next.items.size)
            assertEquals(BoardPlaylistEntry(localNode, addedClimb.uppercase(), 45), next.items[1])
            // Playlist host and membership are untouched by a leaf's edit.
            assertEquals(localNode, next.hostId)
            assertEquals(listOf(localNode), next.members)
            // The gateway is a playlist member in its own right here, so it
            // needs no proxy authority.
            assertEquals(BoardPlaylistAuthority.MEMBER, capture.authority.captured)
        }

    @Test fun `a joined leaf commits even when its gateway never joined the playlist`() =
        runBlocking {
            // This is the shape the product rule describes: the API-28 device
            // takes part through a gateway that is only the technical
            // controller and is not itself in the playlist.
            every { boardCellManager.isLocalController() } returns true
            every { boardCellManager.isPlaylistMember() } returns false
            val capture = stubLocalCommit(boardCellManager)
            // The gateway runs its own GATT session for the leaf — that is
            // what makes it a gateway — while the canonical playlist belongs
            // to somebody else entirely and this device never joined it.
            queueManager.startQueue("Gateway", SessionVisibility.JOINABLE)
            publish(joinable(host = "someone-else", members = listOf("someone-else")))
            assertNull("the gateway must not be following the playlist",
                queueManager.state.value.mesh)
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

            assertTrue("the leaf's edit must reach canonical state",
                capture.applied.isCaptured)
            assertEquals(BoardPlaylistAuthority.GATEWAY_PROXY, capture.authority.captured)
            val next = capture.applied.captured(snapshots.value!!.playlist, true)!!
            assertEquals(BoardPlaylistEntry(localNode, addedClimb.uppercase(), 45), next.items[1])
            // The leaf changed the queue and nothing else: the playlist host
            // and its membership are exactly as they were.
            assertEquals("someone-else", next.hostId)
            assertEquals(listOf("someone-else"), next.members)
        }

    @Test fun `a leaf command before JOIN never reaches the BoardCell`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        every { boardCellManager.isPlaylistMember() } returns false
        val capture = stubLocalCommit(boardCellManager)
        queueManager.startQueue("Gateway", SessionVisibility.JOINABLE)
        publish(joinable(host = "someone-else", members = listOf("someone-else")))
        // Sharing is up, so the command really does reach the bridge — the
        // only thing missing is the leaf's JOIN, which is the whole basis of
        // the gateway's proxy authority.
        bridge.startSharing()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))

        assertFalse("an ungated leaf must not reach canonical state", capture.applied.isCaptured)

        // The same command after JOIN does land, so the assertion above is
        // about the gate and not about the plumbing being dead.
        serverCommands.emit(GattCommand(leafAddress, SessionQueueProtocol.encodeJoin("Leaf")))
        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
        assertTrue("a joined leaf must reach canonical state", capture.applied.isCaptured)
        assertEquals(BoardPlaylistAuthority.GATEWAY_PROXY, capture.authority.captured)
    }

    @Test fun `a leaf resend becomes a bounded projection retry, not a queue edit`() = runBlocking {
        every { boardCellManager.isLocalController() } returns true
        every { boardCellManager.isPlaylistMember() } returns false
        val capture = stubLocalCommit(boardCellManager)
        coEvery { boardCellManager.retryProjectionForLeaf(any()) } returns
            BoardCommandAck("retry-command", BoardCommandStatus.COMMITTED, cell, 1, 1, 7, "hash")
        publish(joinable())
        hostWithLeaf()

        serverCommands.emit(GattCommand(leafAddress,
            SessionQueueProtocol.encodeCommand(SessionCommand.Resend)))

        coVerify(exactly = 1) { boardCellManager.retryProjectionForLeaf(any()) }
        assertFalse("a resend must not mutate the queue", capture.applied.isCaptured)
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

    @Test fun `a playlist-member gateway that is not the controller sends its own command`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns false
            every { boardCellManager.isPlaylistMember() } returns true
            val sentCommandId = slot<String>()
            every {
                boardCellManager.sendSessionCommand(any(), any(), capture(sentCommandId))
            } answers { sentCommandId.captured }
            publish(joinable())
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
            // The controller's real answer, not a local guess, is what the leaf
            // is told; feeding it in releases the waiting gateway.
            commandAcks.send(BoardCommandAck(sentCommandId.captured,
                BoardCommandStatus.COMMITTED, cell, 1, 1, 6, "hash"))

            assertTrue("command must travel over FIPS", sentCommandId.isCaptured)
            // A member sends under its own identity; no proxy claim is made.
            verify(exactly = 0) { boardCellManager.sendLeafSessionCommand(any(), any(), any()) }
            assertEquals(1, queueManager.state.value.queue.size)
        }

    @Test fun `a non-member gateway that is not the controller proxies in one message`() =
        runBlocking {
            every { boardCellManager.isLocalController() } returns false
            every { boardCellManager.isPlaylistMember() } returns false
            val sentCommandId = slot<String>()
            every {
                boardCellManager.sendLeafSessionCommand(any(), any(), capture(sentCommandId))
            } answers { sentCommandId.captured }
            queueManager.startQueue("Gateway", SessionVisibility.JOINABLE)
            publish(joinable(host = "someone-else", members = listOf("someone-else")))
            hostWithLeaf()

            serverCommands.emit(GattCommand(leafAddress,
                SessionQueueProtocol.encodeCommand(SessionCommand.Add(addedClimb, 45))))
            commandAcks.send(BoardCommandAck(sentCommandId.captured,
                BoardCommandStatus.COMMITTED, cell, 1, 1, 6, "hash"))

            assertTrue("the leaf's edit must travel as a proxied command",
                sentCommandId.isCaptured)
            // No join-then-send: the gateway lends its authenticated hop, not
            // its membership. The old sequence raced — the controller could
            // commit the edit before the join and refuse it as "not a playlist
            // member" — and it also made the gateway a full member, so it could
            // inherit the host role and lose its own queue.
            coVerify(exactly = 0) { boardCellManager.submitPlaylistControl(any()) }
            verify(exactly = 0) { boardCellManager.sendSessionCommand(any(), any(), any()) }
            assertNull("the gateway must not have joined the playlist",
                queueManager.state.value.mesh)
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
            assertNull(queueManager.state.value.mesh)
        }

    @Test fun `an Android 9 leaf is never handed the playlist host role`() = runBlocking {
        publish(joinable())

        // Playlist host is a canonical FIPS identity. A GATT leaf has none, so
        // it can never appear as one; its gateway vouches for it instead.
        assertEquals(localNode, snapshots.value!!.playlist.hostId)
        assertEquals(listOf(localNode), snapshots.value!!.playlist.members)
        assertTrue(queueManager.state.value.mesh!!.isHost)
    }
}
