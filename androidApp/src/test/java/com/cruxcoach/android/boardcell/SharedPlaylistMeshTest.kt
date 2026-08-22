package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The shared playlist as it actually travels: real coordinators, real wire
 * frames, and a network that can lose, reorder and duplicate them.
 *
 * Everything below goes through [BoardCellMeshTransport], so a test that
 * passes here is a statement about the protocol rather than about one pure
 * function.
 */
class SharedPlaylistMeshTest {

    @After fun reset() = BoardCellScopeRegistry.resetForTest()

    /** 2026-08-17T12:00:00Z. */
    private val wallClock = 1_786_968_000_000L
    private val board = PhysicalBoardId("board-shared")
    private val cell = BoardCellId.forPhysical(board)

    private class MemoryStore : BoardCellDurableStore {
        val snapshots = mutableMapOf<PhysicalBoardId, BoardCellSnapshot>()
        val intents = mutableMapOf<PhysicalBoardId, BoardWriteIntent>()
        val acks = LinkedHashMap<String, BoardCommandAck>()
        override fun persistSnapshot(snapshot: BoardCellSnapshot) {
            snapshots[snapshot.physicalBoardId] = snapshot
        }
        override fun clearSnapshot(boardId: PhysicalBoardId) { snapshots.remove(boardId) }
        override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot; acks[ack.commandId] = ack
        }
        override fun persistIntent(intent: BoardWriteIntent) { intents[intent.physicalBoardId] = intent }
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) {
            intents[intent.physicalBoardId] = intent.copy(
                state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED)
        }
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot
            intents.remove(intent.physicalBoardId); acks[ack.commandId] = ack
        }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) {
            if (intents[boardId]?.commandId == commandId) intents.remove(boardId)
        }
        override fun pendingIntent(boardId: PhysicalBoardId) = intents[boardId]
        override fun commandAck(commandId: String) = acks[commandId]
    }

    /** One in-flight frame, so a test can drop, duplicate or reorder it. */
    private data class Packet(val from: String, val to: String, val bytes: ByteArray)

    private inner class Node(val id: String, val network: Network, store: MemoryStore = MemoryStore()) {
        val store = store
        val link = object : AuthenticatedMeshLink {
            override val localNpub = id
            override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
                if (network.partitioned.contains(id) || network.partitioned.contains(authenticatedPeerNpub))
                    return false
                network.inFlight += Packet(id, authenticatedPeerNpub, payload)
                return true
            }
            override fun directAuthenticatedPeers() = network.nodes.keys - id
            override fun activeRealmId() = cell.value
        }
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator(id, transport, store,
            settleMs = 0, heartbeatTimeoutMs = 1_000_000,
            wallClockEpochMs = { network.clock })

        init {
            transport.attach(coordinator)
            // What BoardCellManager does in production: the controller
            // serializes an inbound command and answers its sender.
            transport.onPlaylistCommand = { inbound ->
                val ack = coordinator.applyPlaylistCommand(
                    board, network.monotonic, inbound.senderId, inbound.command)
                if (ack != null) transport.publishCommandAck(inbound.senderId, ack)
            }
            transport.onProjectionRequest = { inbound ->
                val result = coordinator.projectSemantically(
                    board, inbound.request, network.monotonic,
                ) { true }
                val ack = when (result) {
                    is ProjectionResult.Committed -> result.ack
                    is ProjectionResult.Duplicate -> result.ack
                    is ProjectionResult.Refused -> result.ack
                    is ProjectionResult.BoardWriteFailed -> result.ack
                }
                if (ack != null) transport.publishCommandAck(inbound.senderId, ack)
            }
        }

        fun playlist(): BoardPlaylistState = coordinator.snapshot(board)!!.playlist
        fun snapshot(): BoardCellSnapshot = coordinator.snapshot(board)!!
    }

    private inner class Network {
        val nodes = LinkedHashMap<String, Node>()
        val inFlight = mutableListOf<Packet>()
        val partitioned = mutableSetOf<String>()
        val dropped = mutableListOf<Packet>()
        var clock = wallClock
        var monotonic = 1_000L

        /** Frames this delivery pass should silently lose. */
        var drop: (Packet) -> Boolean = { false }
        /** Frames this delivery pass should deliver twice. */
        var duplicate: (Packet) -> Boolean = { false }
        /** Deliver in reverse order, which is the cheapest real reordering. */
        var reverse = false

        fun node(id: String) = nodes.getValue(id)

        suspend fun deliver(rounds: Int = 40) {
            repeat(rounds) {
                if (inFlight.isEmpty()) return
                val batch = inFlight.toList().let { if (reverse) it.reversed() else it }
                inFlight.clear()
                batch.forEach { packet ->
                    if (drop(packet)) { dropped += packet; return@forEach }
                    val target = nodes[packet.to] ?: return@forEach
                    monotonic += 1
                    target.transport.receive(packet.from, packet.bytes, monotonic)
                    if (duplicate(packet)) {
                        monotonic += 1
                        target.transport.receive(packet.from, packet.bytes, monotonic)
                    }
                }
            }
        }
    }

    /** A settled cell with [others] admitted; the first id is the controller. */
    private suspend fun mesh(controller: String, vararg others: String): Network {
        val network = Network()
        network.nodes[controller] = Node(controller, network)
        others.forEach { network.nodes[it] = Node(it, network) }
        val host = network.node(controller)
        host.coordinator.beginClaim(board, cell, network.monotonic)
        host.coordinator.settle(board, network.monotonic)
        network.inFlight.clear()
        others.forEach { host.coordinator.joinMember(board, it, network.monotonic) }
        network.deliver()
        return network
    }

    private fun Node.compose(
        commandId: String,
        vararg ops: BoardPlaylistOp,
    ): BoardPlaylistCommand {
        val snapshot = snapshot()
        return BoardPlaylistCommand(commandId, snapshot.playlistRevision,
            snapshot.playlist.clearGeneration, ops.toList())
    }

    /** Submit as a member would: over the wire, to whoever is the controller. */
    private suspend fun Node.submit(command: BoardPlaylistCommand): Boolean =
        transport.sendPlaylistCommand(snapshot(), command)

    private suspend fun Node.commitLocally(command: BoardPlaylistCommand): BoardCommandAck? =
        coordinator.applyPlaylistCommand(board, network.monotonic, id, command)

    private fun Network.entryIds() = nodes.values.map { it.playlist().entries.map { e -> e.entryId } }

    private fun Network.assertConverged() {
        val hashes = nodes.values.map { it.snapshot().stateHash }.toSet()
        assertEquals("replicas disagree: ${entryIds()}", 1, hashes.size)
        nodes.values.forEach { assertTrue(it.snapshot().hasValidHash()) }
    }

    // ===== Every member takes part, with no join and no host =====

    @Test fun `the cell creates its playlist and every admitted member receives it`() = runTest {
        val network = mesh("controller", "nokia", "pixel")

        val sessionId = network.node("controller").playlist().sessionId
        assertNotNull("the playlist is created with the cell", sessionId)
        network.nodes.values.forEach {
            assertEquals(sessionId, it.playlist().sessionId)
            assertTrue(it.playlist().entries.isEmpty())
        }
        network.assertConverged()
    }

    @Test fun `a member that is not the controller edits the shared playlist`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")

        assertTrue(nokia.submit(nokia.compose("command-nokia-01",
            BoardPlaylistOp.Add("e1", "climb-a", 40, 120),
            BoardPlaylistOp.Add("e2", "climb-b", 45))))
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e1", "e2"), nokia.playlist().entries.map { it.entryId })
        assertEquals("e1", nokia.playlist().selectedEntryId)
        assertEquals(120, nokia.playlist().entries[0].restAfterSeconds)
        // The controller serializes and nothing else: it holds no product role
        // in the result at all.
        assertEquals(nokia.playlist(), network.node("controller").playlist())
    }

    @Test fun `participant detail sends one atomic request without stale add ordering`() = runTest {
        val network = mesh("controller", "nokia")
        val host = network.node("controller")
        val nokia = network.node("nokia")
        host.commitLocally(host.compose(
            "seed-detail-race",
            BoardPlaylistOp.Add("old", "old-climb", 40),
            BoardPlaylistOp.SetCurrent("old"),
        ))
        network.deliver()
        val base = nokia.snapshot()
        val request = BoardProjectionRequest(
            commandId = "detail-atomic-request",
            projection = BoardProjection("new-climb", 40),
            baseSequence = base.sequence,
            baseProjection = base.projection,
            basePlaylistRevision = base.playlistRevision,
            entryId = "nokia-occurrence",
            materializeEntry = true,
            placeAfterCurrent = true,
        )

        assertTrue(nokia.transport.sendProjectionRequest(base, request))
        network.deliver()

        network.assertConverged()
        val after = nokia.snapshot()
        assertEquals(base.playlistRevision + 1, after.playlistRevision)
        assertEquals(listOf("old", "nokia-occurrence"),
            after.playlist.entries.map { it.entryId })
        assertEquals("nokia-occurrence", after.playlist.currentEntryId)
        assertEquals(BoardProjection("new-climb", 40), after.projection)
    }

    @Test fun `a device admitted later receives the playlist in its welcome snapshot`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        nokia.submit(nokia.compose("command-nokia-01", BoardPlaylistOp.Add("e1", "climb-a", 40)))
        network.deliver()

        network.nodes["latecomer"] = Node("latecomer", network)
        network.node("controller").coordinator.joinMember(board, "latecomer", network.monotonic)
        network.node("controller").coordinator.snapshot(board)?.let {
            network.node("controller").transport.publishSnapshot(it)
        }
        network.deliver()

        assertEquals(listOf("e1"), network.node("latecomer").playlist().entries.map { it.entryId })
        network.assertConverged()
    }

    // ===== Concurrency and duplicates =====

    @Test fun `two members editing from the same revision both land`() = runTest {
        val network = mesh("controller", "nokia", "pixel")
        val nokia = network.node("nokia")
        val pixel = network.node("pixel")

        // Composed against the identical base — the normal case, not a race
        // anybody has to lose.
        val fromNokia = nokia.compose("command-nokia-01", BoardPlaylistOp.Add("n1", "climb-a", 40))
        val fromPixel = pixel.compose("command-pixel-01", BoardPlaylistOp.Add("p1", "climb-b", 40))
        nokia.submit(fromNokia)
        pixel.submit(fromPixel)
        network.deliver()

        network.assertConverged()
        assertEquals(setOf("n1", "p1"), nokia.playlist().entries.map { it.entryId }.toSet())
    }

    @Test fun `two members adding the same climb produce two unambiguous entries`() = runTest {
        val network = mesh("controller", "nokia", "pixel")
        val nokia = network.node("nokia")
        val pixel = network.node("pixel")

        nokia.submit(nokia.compose("command-nokia-01", BoardPlaylistOp.Add("n1", "zombie", 40)))
        pixel.submit(pixel.compose("command-pixel-01", BoardPlaylistOp.Add("p1", "zombie", 40)))
        network.deliver()

        val entries = nokia.playlist().entries
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.climbUuid == "zombie" })
        assertEquals(setOf("n1", "p1"), entries.map { it.entryId }.toSet())
        network.assertConverged()

        // And each of them can now be removed on its own, which is the whole
        // reason an occurrence has an identity.
        pixel.submit(pixel.compose("command-pixel-02", BoardPlaylistOp.Remove("n1")))
        network.deliver()
        assertEquals(listOf("p1"), nokia.playlist().entries.map { it.entryId })
        network.assertConverged()
    }

    @Test fun `simultaneous remove move and current on one list converge`() = runTest {
        val network = mesh("controller", "nokia", "pixel")
        val nokia = network.node("nokia")
        val pixel = network.node("pixel")
        nokia.submit(nokia.compose("command-seed",
            BoardPlaylistOp.Add("e1", "a", 40),
            BoardPlaylistOp.Add("e2", "b", 40),
            BoardPlaylistOp.Add("e3", "c", 40)))
        network.deliver()

        // Three people, one base revision, three incompatible-looking intents.
        nokia.submit(nokia.compose("command-remove", BoardPlaylistOp.Remove("e2")))
        pixel.submit(pixel.compose("command-move",
            BoardPlaylistOp.Move("e3", BoardPlaylistAnchor.Head)))
        network.node("controller").commitLocally(network.node("controller")
            .compose("command-current", BoardPlaylistOp.SetSelection("e3")))
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e3", "e1"), nokia.playlist().entries.map { it.entryId })
        assertEquals("e3", nokia.playlist().selectedEntryId)
    }

    // ===== Loss, reordering and duplication =====

    @Test fun `a lost event is repaired from a snapshot without losing the edit`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "a", 40)))
        network.deliver()

        // The delta never reaches nokia.
        network.drop = { packet ->
            packet.to == "nokia" && messageOf(packet) is BoardCellWireMessage.Event
        }
        nokia.submit(nokia.compose("command-lost", BoardPlaylistOp.Add("e2", "b", 40)))
        network.deliver()
        assertEquals(listOf("e1"), nokia.playlist().entries.map { it.entryId })
        assertTrue(network.dropped.isNotEmpty())

        // The next delta reveals the gap, and the repair is immediate: the
        // replica asks for a snapshot rather than waiting for a maintenance
        // tick to notice.
        network.drop = { false }
        nokia.submit(nokia.compose("command-after", BoardPlaylistOp.Add("e3", "c", 40)))
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e1", "e2", "e3"), nokia.playlist().entries.map { it.entryId })
    }

    @Test fun `reordered deltas are repaired rather than applied out of order`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "a", 40)))
        network.deliver()

        // Two commits, delivered back to front.
        network.reverse = true
        network.node("controller").commitLocally(network.node("controller")
            .compose("command-one", BoardPlaylistOp.Add("e2", "b", 40)))
        network.node("controller").commitLocally(network.node("controller")
            .compose("command-two", BoardPlaylistOp.Add("e3", "c", 40)))
        network.deliver()
        network.reverse = false
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e1", "e2", "e3"), nokia.playlist().entries.map { it.entryId })
    }

    @Test fun `duplicated frames change nothing`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        network.duplicate = { true }

        nokia.submit(nokia.compose("command-nokia-01",
            BoardPlaylistOp.Add("e1", "a", 40), BoardPlaylistOp.Add("e2", "a", 40)))
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e1", "e2"), nokia.playlist().entries.map { it.entryId })
        assertEquals(1L, nokia.snapshot().playlistRevision)
    }

    @Test fun `a resent command with the same id commits exactly once`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val command = nokia.compose("command-retried", BoardPlaylistOp.Add("e1", "a", 40))

        nokia.submit(command)
        network.deliver()
        // The ack was lost, so the sender's sub-second retry loop resends the
        // identical command.
        nokia.submit(command)
        network.deliver()
        nokia.submit(command)
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e1"), nokia.playlist().entries.map { it.entryId })
        assertEquals(1L, nokia.snapshot().playlistRevision)
    }

    // ===== Partition =====

    @Test fun `a partitioned member keeps its copy but is not told it is in sync`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "a", 40)))
        network.deliver()
        val beforePartition = nokia.snapshot().stateHash

        network.partitioned += "nokia"
        network.node("controller").commitLocally(network.node("controller")
            .compose("command-alone", BoardPlaylistOp.Add("e2", "b", 40)))
        network.deliver()

        // The controller moved on; the partitioned replica is untouched and,
        // crucially, still knows the last time it heard from the controller.
        assertEquals(beforePartition, nokia.snapshot().stateHash)
        assertEquals(listOf("e1", "e2"),
            network.node("controller").playlist().entries.map { it.entryId })
        val silence = nokia.coordinator.controllerSilentForMs(board, network.monotonic + 30_000)
        assertNotNull(silence)
        assertTrue("a partitioned replica must be able to notice", silence!! >= 30_000)

        // Healing repairs it without anybody re-issuing the edit.
        network.partitioned.clear()
        network.node("controller").transport.antiEntropy()
        network.deliver()
        network.assertConverged()
        assertEquals(listOf("e1", "e2"), nokia.playlist().entries.map { it.entryId })
    }

    // ===== Controller handover =====

    @Test fun `an in-flight command survives a controller handover and lands once`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val old = network.node("controller")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "a", 40)))
        network.deliver()

        // The command is composed and sent while the transfer is under way, so
        // the old controller cannot serialize it.
        val inFlight = nokia.compose("command-in-flight", BoardPlaylistOp.Add("e2", "b", 40))
        val transfer = old.coordinator.prepareHandover(board, "nokia", network.monotonic,
            "transfer-0001")
        assertNotNull(transfer)
        nokia.submit(inFlight)
        network.deliver()
        assertEquals(listOf("e1"), nokia.playlist().entries.map { it.entryId })

        old.coordinator.sourceReleased(board, "transfer-0001", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        network.deliver()
        assertEquals("nokia", nokia.snapshot().controllerId)

        // The retry carries the original command id and is now serialized by
        // the new controller — the sender never had to know a handover happened.
        val ack = nokia.commitLocally(inFlight.copy(
            basePlaylistRevision = nokia.snapshot().playlistRevision,
            baseClearGeneration = nokia.playlist().clearGeneration))
        network.deliver()
        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        assertEquals(listOf("e1", "e2"), nokia.playlist().entries.map { it.entryId })

        // And a second retry of the same id changes nothing.
        assertEquals(BoardCommandStatus.COMMITTED, nokia.commitLocally(inFlight)!!.status)
        assertEquals(listOf("e1", "e2"), nokia.playlist().entries.map { it.entryId })
        network.deliver()
        network.assertConverged()
    }

    @Test fun `a command already committed by the old controller is not applied again by the new one`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val old = network.node("controller")
        val command = nokia.compose("command-before-handover", BoardPlaylistOp.Add("e1", "a", 40))
        nokia.submit(command)
        network.deliver()
        assertEquals(listOf("e1"), nokia.playlist().entries.map { it.entryId })

        old.coordinator.prepareHandover(board, "nokia", network.monotonic, "transfer-0001")
        network.deliver()
        old.coordinator.sourceReleased(board, "transfer-0001", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        network.deliver()

        // The new controller inherited the idempotency window in the snapshot
        // it adopted, so a retry that crosses the handover is answered, not
        // re-applied.
        val ack = nokia.commitLocally(command)

        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        assertEquals(listOf("e1"), nokia.playlist().entries.map { it.entryId })
    }

    // ===== Restart =====

    @Test fun `a restarted controller keeps the playlist and refuses to double-apply`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val command = nokia.compose("command-before-restart",
            BoardPlaylistOp.Add("e1", "a", 40, 60), BoardPlaylistOp.Add("e2", "b", 40))
        nokia.submit(command)
        network.deliver()
        val before = network.node("controller").snapshot()

        val store = network.node("controller").store
        val restarted = BoardCellCoordinator("controller", NoOpBoardCellTransport, store,
            settleMs = 0, heartbeatTimeoutMs = 1_000_000, wallClockEpochMs = { network.clock })
        assertTrue(restarted.restoreTrustedSnapshot(store.snapshots.getValue(board), 10_000)
            is BoardCellApplyResult.Applied)

        assertEquals(before.playlist, restarted.snapshot(board)!!.playlist)
        assertEquals(before.stateHash, restarted.snapshot(board)!!.stateHash)
        val ack = restarted.applyPlaylistCommand(board, 10_001, "nokia", command)
        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        assertEquals(listOf("e1", "e2"),
            restarted.snapshot(board)!!.playlist.entries.map { it.entryId })
    }

    // ===== Clear =====

    @Test fun `clearing empties every replica and drops the edits already in flight`() = runTest {
        val network = mesh("controller", "nokia", "pixel")
        val nokia = network.node("nokia")
        val pixel = network.node("pixel")
        nokia.submit(nokia.compose("command-seed",
            BoardPlaylistOp.Add("e1", "a", 40), BoardPlaylistOp.Add("e2", "b", 40)))
        network.deliver()

        // Composed before the clear, delivered after it.
        val stale = pixel.compose("command-stale", BoardPlaylistOp.Add("e3", "c", 40))
        nokia.submit(nokia.compose("command-clear", BoardPlaylistOp.Clear()))
        network.deliver()
        assertTrue(pixel.playlist().entries.isEmpty())
        assertEquals(1L, pixel.playlist().clearGeneration)

        pixel.submit(stale)
        network.deliver()

        network.assertConverged()
        assertTrue("a pre-clear edit must not resurrect one entry of a deleted list",
            pixel.playlist().entries.isEmpty())
        assertEquals(BoardCommandStatus.REJECTED_CONFLICT,
            network.node("controller").store.acks.getValue("command-stale").status)

        // A freshly composed edit works normally again.
        pixel.submit(pixel.compose("command-fresh", BoardPlaylistOp.Add("e4", "d", 40)))
        network.deliver()
        assertEquals(listOf("e4"), pixel.playlist().entries.map { it.entryId })
        network.assertConverged()
    }

    // ===== Selection is not projection =====

    /**
     * Stepping through the list is not taking the wall. Somebody looking at
     * what is coming up must not change the climb the person on the board is
     * currently working on.
     */
    @Test fun `adding and selecting never write the physical board`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val controller = network.node("controller")

        nokia.submit(nokia.compose("command-seed",
            BoardPlaylistOp.Add("e1", "climb-a", 40),
            BoardPlaylistOp.Add("e2", "climb-b", 40)))
        network.deliver()
        nokia.submit(nokia.compose("command-select", BoardPlaylistOp.SetSelection("e2")))
        network.deliver()

        network.assertConverged()
        assertEquals("e2", controller.playlist().selectedEntryId)
        // The board is exactly as it was: nothing prepared a write intent and
        // nothing became the confirmed projection.
        assertNull(controller.snapshot().projection)
        assertNull(controller.store.pendingIntent(board))
        assertNull(controller.store.intents[board])
    }

    /**
     * The selected entry and the climb the board last confirmed are two
     * separate canonical facts, and a projection moves only the second.
     */
    @Test fun `a member cannot claim the board is showing an occurrence`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "climb-a", 40)))
        network.deliver()

        // Nothing was written to any wall; this is a bare claim that it was.
        nokia.submit(nokia.compose("command-claim", BoardPlaylistOp.SetCurrent("e1")))
        network.deliver()

        network.nodes.values.forEach {
            assertNull("only the controller confirms what the board shows",
                it.playlist().currentEntryId)
        }
        network.assertConverged()
    }

    @Test fun `an explicit send moves the confirmed projection and not the selection`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val controller = network.node("controller")
        nokia.submit(nokia.compose("command-seed",
            BoardPlaylistOp.Add("e1", "climb-a", 40),
            BoardPlaylistOp.Add("e2", "climb-b", 40)))
        network.deliver()

        var writes = 0
        val entry = controller.playlist().selectedEntry()!!
        val result = controller.coordinator.project(board,
            BoardProjection(entry.climbUuid, entry.angle), network.monotonic,
            "projection-0001", null) { writes++; true }
        network.deliver()

        assertTrue(result is ProjectionResult.Committed)
        assertEquals(1, writes)
        assertEquals("climb-a", controller.snapshot().projection?.climbUuid)
        assertEquals("e1", controller.playlist().selectedEntryId)
        // And the selection can now move away from it without the wall
        // following along.
        nokia.submit(nokia.compose("command-select", BoardPlaylistOp.SetSelection("e2")))
        network.deliver()
        assertEquals("e2", nokia.playlist().selectedEntryId)
        assertEquals("climb-a", nokia.snapshot().projection?.climbUuid)
        assertEquals(1, writes)
        network.assertConverged()
    }

    /**
     * A controller that inherits a playlist it never projected leaves the wall
     * alone. Somebody may well be on the climb that is up there.
     */
    @Test fun `a new controller does not project the playlist it inherits`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val old = network.node("controller")
        nokia.submit(nokia.compose("command-seed", BoardPlaylistOp.Add("e1", "climb-a", 40)))
        network.deliver()

        old.coordinator.prepareHandover(board, "nokia", network.monotonic, "transfer-0001")
        network.deliver()
        old.coordinator.sourceReleased(board, "transfer-0001", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        network.deliver()

        assertEquals("nokia", nokia.snapshot().controllerId)
        assertNull("the wall must be left exactly as it was",
            nokia.snapshot().projection)
        assertNull(nokia.store.intents[board])
    }

    // ===== Scale =====

    /**
     * Forty phones on one board is the shape a busy gym session actually
     * takes, and it is where an approach that broadcast the whole playlist per
     * edit stops working. Every member edits once, from its own stale-ish
     * base, and every replica has to end up byte-identical.
     */
    @Test fun `forty members editing at once converge on one playlist`() = runTest {
        val members = (1..39).map { "member-%02d".format(it) }
        val network = mesh("controller", *members.toTypedArray())
        assertEquals(40, network.nodes.size)
        assertEquals(40, network.node("controller").snapshot().members.size)

        members.forEachIndexed { index, member ->
            val node = network.node(member)
            node.submit(node.compose("command-%02d".format(index),
                BoardPlaylistOp.Add("entry-%02d".format(index), "climb-%02d".format(index % 7), 40)))
        }
        network.deliver(rounds = 200)

        network.assertConverged()
        val entries = network.node("controller").playlist().entries
        assertEquals(39, entries.size)
        assertEquals(39, entries.map { it.entryId }.toSet().size)
        // The same climb appears many times over, and every occurrence is
        // separately addressable.
        assertTrue(entries.count { it.climbUuid == "climb-00" } > 1)
        assertEquals(entries.first().entryId, network.node("member-20").playlist().selectedEntryId)
    }

    @Test fun `a forty member playlist still fits one canonical snapshot frame`() = runTest {
        val members = (1..39).map { "member-%02d".format(it) }
        val network = mesh("controller", *members.toTypedArray())
        val controller = network.node("controller")
        controller.commitLocally(controller.compose("bulk-load",
            *(0 until BoardPlaylistPolicy.MAX_OPS_PER_COMMAND).map {
                BoardPlaylistOp.Add(BoardPlaylistEntryId.random(),
                    "00000000-0000-4000-8000-00000000%04d".format(it), 40, 60)
            }.toTypedArray()))
        network.deliver(rounds = 200)

        network.assertConverged()
        val frame = BoardCellWireCodec.encode(BoardCellWireFrame(
            messageId = "message-id-0001", senderId = "controller", realmId = cell.value,
            cellId = cell, physicalBoardId = board, epoch = controller.snapshot().epoch,
            controllerTerm = controller.snapshot().controllerTerm,
            message = BoardCellWireMessage.Snapshot(controller.snapshot())))
        assertTrue("snapshot frame was ${frame.size} bytes",
            frame.size <= BoardCellMeshTransport.MAX_WIRE_BYTES)
        assertEquals(BoardPlaylistPolicy.MAX_OPS_PER_COMMAND,
            network.node("member-39").playlist().entries.size)
    }

    private fun messageOf(packet: Packet): BoardCellWireMessage =
        BoardCellWireCodec.decode(packet.bytes).message

    // ===== A relay guest's write, across a controller handover =====
    //
    // The identity of the whole operation is decided at ingress: one operation
    // id for the board write, one entry id for the occurrence. Both survive a
    // retry, a relay restart and a handover, which is what makes "exactly one
    // occurrence" a property of the protocol rather than of good timing.

    /** The relay host's half: write the wall, then record what everybody sees. */
    private suspend fun Node.relayGuestWrite(
        operation: com.cruxcoach.android.data.RelayInboundGate.Operation,
        climbUuid: String,
        angle: Int,
        boardWrites: MutableList<String>,
    ): Boolean {
        val projection = BoardProjection(climbUuid, angle)
        val result = coordinator.projectExternal(
            boardId = board,
            nowMonotonicMs = network.monotonic,
            commandId = operation.operationId,
            boardWrite = { boardWrites += "${operation.operationId}:$climbUuid"; true },
            identify = { projection },
        )
        if (result !is ProjectionResult.Committed && result !is ProjectionResult.Duplicate) return false
        val ops = BoardPlaylistOps.completeLightNow(
            playlist(), operation.entryId, climbUuid, angle, landed = true,
        )
        if (ops.isEmpty()) return true
        return commitLocally(compose("adopt-${operation.operationId}", *ops.toTypedArray())) != null
    }

    /**
     * One relay host: its own gate, exactly as each device has its own
     * `CruxRelayManager`. Nothing is shared between two of these but the cell.
     */
    private inner class RelayHost(val node: Node) {
        val gate = com.cruxcoach.android.data.RelayInboundGate()

        /**
         * A guest write arriving at this host, exactly as `CruxRelayManager`
         * handles one: the intention is looked up in **canonical state**, and
         * only minted when there is none open.
         *
         * That is what carries a retry across a handover — the successor has
         * its own gate and has never seen the write, but the cell has the
         * record — and what keeps two guests apart, because the guest is part
         * of what "the same request" means.
         */
        fun ingest(
            climbUuid: String,
            angle: Int,
            framesHash: Long,
            nowMs: Long,
            guestAddress: String,
        ): com.cruxcoach.android.data.RelayInboundGate.Decision {
            val identity = com.cruxcoach.android.data.RelayIngressIdentity
            val fingerprint = identity.fingerprint(
                cellId = node.snapshot().cellId.value,
                climbUuid = climbUuid, angle = angle, framesHash = framesHash,
            )
            val guestKey = identity.guestKey(guestAddress)
            val playlist = node.playlist()
            // Nobody is attached to this host in the test harness, so a
            // reconnecting guest's orphaned intention is adoptable — which is
            // the production case this models: the old address is gone.
            val operation = identity.openIntent(playlist, fingerprint, guestKey, nowMs, emptySet())
                ?: identity.newIntent(fingerprint, guestKey, nowMs)
            val projection = node.snapshot().projection
            val landed = playlist.currentEntryId == operation.entryId &&
                playlist.entry(operation.entryId) != null &&
                projection?.climbUuid == climbUuid && projection.angle == angle
            return gate.evaluate(
                mode = com.cruxcoach.android.data.RelayInboundClimbMode.PROJECT_NOW,
                identity = com.cruxcoach.android.data.RelayInboundGate.Identity.NAMED,
                climbUuid = climbUuid,
                angle = angle,
                climbBrand = null,
                connectedBrand = null,
                nowMs = nowMs,
                operation = operation,
                canonicallyLanded = landed,
            )
        }

        /**
         * What the manager does the moment the gate accepts — and waits for.
         *
         * The return value is the barrier: production must not write the wall
         * until the intention is canonical, because a handover in between
         * leaves the successor with nothing to find.
         */
        suspend fun publishIntent(
            operation: com.cruxcoach.android.data.RelayInboundGate.Operation,
        ): Boolean = node.commitLocally(node.compose(
            "intent-${operation.entryId}",
            *BoardPlaylistOps.recordRelayOperation(operation).toTypedArray(),
        ))?.status == BoardCommandStatus.COMMITTED
    }

    private fun operationOf(
        decision: com.cruxcoach.android.data.RelayInboundGate.Decision,
    ): com.cruxcoach.android.data.RelayInboundGate.Operation = when (decision) {
        is com.cruxcoach.android.data.RelayInboundGate.Decision.ProjectNow -> decision.operation
        is com.cruxcoach.android.data.RelayInboundGate.Decision.AppendToEnd -> decision.operation
        is com.cruxcoach.android.data.RelayInboundGate.Decision.AlreadyDelivered ->
            decision.operation
        is com.cruxcoach.android.data.RelayInboundGate.Decision.Refused ->
            fail("the guest write was refused: ${decision.reason}").let { error("unreachable") }
    }

    @Test fun `a guest write that lands is one occurrence for the whole cell`() = runTest {
        val network = mesh("controller", "nokia")
        val host = RelayHost(network.node("controller"))
        val writes = mutableListOf<String>()

        val operation = operationOf(
            host.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(host.publishIntent(operation))
        assertTrue(host.node.relayGuestWrite(operation, "climb-guest", 40, writes))
        host.gate.markLanded(operation, wallClock)
        network.deliver()

        network.assertConverged()
        network.nodes.values.forEach {
            assertEquals(listOf(operation.entryId), it.playlist().entries.map { e -> e.entryId })
            assertEquals(operation.entryId, it.playlist().currentEntryId)
        }
    }

    /**
     * The case this whole mechanism exists for, with nothing lent between the
     * two controllers: each has its own gate, the successor has never seen the
     * write, and the guest reconnects from a different BLE address before
     * re-sending. If the identity were minted locally — which it was — the
     * successor would derive a second occurrence here.
     */
    @Test fun `a retry across a controller handover stays one occurrence`() = runTest {
        val network = mesh("controller", "nokia")
        val old = network.node("controller")
        val nokia = network.node("nokia")
        val oldHost = RelayHost(old)
        val newHost = RelayHost(nokia)
        val writes = mutableListOf<String>()

        val operation = operationOf(
            oldHost.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(oldHost.publishIntent(operation))
        assertTrue(old.relayGuestWrite(operation, "climb-guest", 40, writes))
        oldHost.gate.markLanded(operation, wallClock)
        network.deliver()

        old.coordinator.prepareHandover(board, "nokia", network.monotonic, "transfer-0001")
        network.deliver()
        old.coordinator.sourceReleased(board, "transfer-0001", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0001", network.monotonic)
        network.deliver()
        assertEquals("nokia", nokia.snapshot().controllerId)

        // The relay moved with the board. The guest reconnected — new address —
        // and re-sent the same climb, and the successor's gate has never heard
        // of any of it.
        val retryDecision = newHost.ingest(
            "climb-guest", 40, framesHash = 77L, nowMs = wallClock + 2_500, guestAddress = "BB:02",
        )
        // The cell itself says it already landed, which is the ACK state the
        // successor's own ledger cannot have. So: no second write to the wall,
        // no second occurrence — and a *success*, because the climb the guest
        // is asking for is on the board. Answering their retry with an error
        // was what invited a contradictory second action.
        assertTrue(
            retryDecision is com.cruxcoach.android.data.RelayInboundGate.Decision.AlreadyDelivered,
        )
        assertEquals(
            "under the ids the first attempt used",
            operation.entryId, operationOf(retryDecision).entryId,
        )
        assertEquals("the wall is written once, by one controller", 1, writes.size)

        network.assertConverged()
        network.nodes.values.forEach {
            assertEquals(
                "the retry must not add a second occurrence",
                listOf(operation.entryId),
                it.playlist().entries.map { e -> e.entryId },
            )
            assertEquals(operation.entryId, it.playlist().currentEntryId)
        }
    }

    /**
     * The same handover, but the first attempt never landed. Now the successor
     * *must* act — and still under the identity the failed attempt had, so the
     * result is one occurrence rather than one per controller.
     */
    @Test fun `a retry after a failed write is served by the new controller under the same ids`() = runTest {
        val network = mesh("controller", "nokia")
        val old = network.node("controller")
        val nokia = network.node("nokia")
        val oldHost = RelayHost(old)
        val newHost = RelayHost(nokia)
        val writes = mutableListOf<String>()

        val first = operationOf(
            oldHost.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        // The intention is canonical before the wall is touched; the write
        // itself then failed, so nothing else about it is.
        assertTrue(oldHost.publishIntent(first))
        network.deliver()
        oldHost.gate.markFailed(first, wallClock + 100)

        old.coordinator.prepareHandover(board, "nokia", network.monotonic, "transfer-0002")
        network.deliver()
        old.coordinator.sourceReleased(board, "transfer-0002", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0002", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0002", network.monotonic)
        network.deliver()
        assertEquals("nokia", nokia.snapshot().controllerId)

        val retry = operationOf(
            newHost.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock + 2_500, guestAddress = "BB:02"),
        )
        // The ids are what the wall and the list are addressed by, and they
        // survive: the successor adopted the intention the cell was carrying.
        // The guest key is rebound to the address they came back on, which is
        // the point — it is how the next retry is recognised too.
        assertEquals("the successor serves the same operation", first.operationId, retry.operationId)
        assertEquals("and the same occurrence", first.entryId, retry.entryId)
        assertNotEquals("under the address they reconnected with", first.guestKey, retry.guestKey)
        assertTrue(nokia.relayGuestWrite(retry, "climb-guest", 40, writes))
        network.deliver()

        network.assertConverged()
        network.nodes.values.forEach {
            assertEquals(listOf(first.entryId), it.playlist().entries.map { e -> e.entryId })
            assertEquals(first.entryId, it.playlist().currentEntryId)
        }
    }

    /** Twice on the same controller is deduplicated before the wall is touched. */
    /**
     * The barrier, from the wrong side: a member cannot publish an intention,
     * so a device that is no longer the controller gets a refusal — and a
     * refusal must stop the write rather than being ignored.
     */
    @Test fun `an intention refused by the cell precedes no board write`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val host = RelayHost(nokia)
        val writes = mutableListOf<String>()

        val operation = operationOf(
            host.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        // nokia is a member, not the controller: the op is controller-only.
        val published = host.publishIntent(operation)

        assertFalse("a member may not record an ingress", published)
        // Production returns here rather than writing; the assertion is that
        // there is nothing to write *with* — no canonical intention exists.
        network.deliver()
        assertTrue(writes.isEmpty())
        network.nodes.values.forEach { assertTrue(it.playlist().relayOperations.isEmpty()) }
    }

    /** And the intention really is on every replica before the wall is touched. */
    @Test fun `the intention is canonical everywhere before the write`() = runTest {
        val network = mesh("controller", "nokia")
        val host = RelayHost(network.node("controller"))

        val operation = operationOf(
            host.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(host.publishIntent(operation))
        network.deliver()

        network.nodes.values.forEach {
            assertEquals(
                listOf(operation.entryId),
                it.playlist().relayOperations.map { record -> record.entryId },
            )
        }
        network.assertConverged()
    }

    /**
     * The success answer never reached the guest and their address rotated.
     *
     * Both facts at once, which is the ordinary shape of a lost ACK over BLE:
     * the link dropped, which is *why* the answer was lost and why the address
     * is new. Their retry has to find the same request — anything else mints a
     * second occurrence for one climb.
     */
    @Test fun `a lost success ack is replayed after an address rotation`() = runTest {
        val network = mesh("controller", "nokia")
        val host = RelayHost(network.node("controller"))
        val writes = mutableListOf<String>()

        val operation = operationOf(
            host.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(host.publishIntent(operation))
        assertTrue(host.node.relayGuestWrite(operation, "climb-guest", 40, writes))
        assertTrue(host.publishIntent(operation.copy(landed = true)))
        network.deliver()

        // Same person, new address, same climb — inside the reconnect window.
        val retry = host.ingest(
            "climb-guest", 40, framesHash = 77L,
            nowMs = wallClock + 2_000, guestAddress = "BB:02",
        )

        assertTrue(
            "their climb is on the wall, so this is a success",
            retry is com.cruxcoach.android.data.RelayInboundGate.Decision.AlreadyDelivered,
        )
        assertEquals(operation.entryId, operationOf(retry).entryId)
        assertEquals("and the wall is written once", 1, writes.size)
        network.assertConverged()
        network.nodes.values.forEach {
            assertEquals(listOf(operation.entryId), it.playlist().entries.map { e -> e.entryId })
        }
    }

    /** The same, with the board changing hands in between. */
    @Test fun `a lost success ack is replayed by the new controller`() = runTest {
        val network = mesh("controller", "nokia")
        val old = network.node("controller")
        val nokia = network.node("nokia")
        val oldHost = RelayHost(old)
        val newHost = RelayHost(nokia)
        val writes = mutableListOf<String>()

        val operation = operationOf(
            oldHost.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(oldHost.publishIntent(operation))
        assertTrue(old.relayGuestWrite(operation, "climb-guest", 40, writes))
        assertTrue(oldHost.publishIntent(operation.copy(landed = true)))
        network.deliver()

        old.coordinator.prepareHandover(board, "nokia", network.monotonic, "transfer-0003")
        network.deliver()
        old.coordinator.sourceReleased(board, "transfer-0003", network.monotonic)
        network.deliver()
        nokia.coordinator.targetReady(board, "board-connection-proof")
        network.deliver()
        old.coordinator.completeHandover(board, "transfer-0003", network.monotonic)
        nokia.coordinator.completeHandover(board, "transfer-0003", network.monotonic)
        network.deliver()
        assertEquals("nokia", nokia.snapshot().controllerId)

        val retry = newHost.ingest(
            "climb-guest", 40, framesHash = 77L,
            nowMs = wallClock + 2_000, guestAddress = "BB:02",
        )

        assertTrue(
            retry is com.cruxcoach.android.data.RelayInboundGate.Decision.AlreadyDelivered,
        )
        assertEquals(operation.entryId, operationOf(retry).entryId)
        assertEquals(1, writes.size)
        network.assertConverged()
        network.nodes.values.forEach {
            assertEquals(listOf(operation.entryId), it.playlist().entries.map { e -> e.entryId })
        }
    }

    @Test fun `a retry on the same controller writes the board once`() = runTest {
        val network = mesh("controller", "nokia")
        val host = RelayHost(network.node("controller"))
        val writes = mutableListOf<String>()

        val operation = operationOf(
            host.ingest("climb-guest", 40, framesHash = 77L, nowMs = wallClock, guestAddress = "AA:01"),
        )
        assertTrue(host.publishIntent(operation))
        assertTrue(host.node.relayGuestWrite(operation, "climb-guest", 40, writes))
        assertTrue(host.node.relayGuestWrite(operation, "climb-guest", 40, writes))
        network.deliver()

        assertEquals(1, writes.size)
        network.assertConverged()
        assertEquals(listOf(operation.entryId), host.node.playlist().entries.map { it.entryId })
    }
}
