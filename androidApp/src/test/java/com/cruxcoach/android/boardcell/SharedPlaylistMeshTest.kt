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
        assertEquals("e1", nokia.playlist().currentEntryId)
        assertEquals(120, nokia.playlist().entries[0].restAfterSeconds)
        // The controller serializes and nothing else: it holds no product role
        // in the result at all.
        assertEquals(nokia.playlist(), network.node("controller").playlist())
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
            .compose("command-current", BoardPlaylistOp.SetCurrent("e3")))
        network.deliver()

        network.assertConverged()
        assertEquals(listOf("e3", "e1"), nokia.playlist().entries.map { it.entryId })
        assertEquals("e3", nokia.playlist().currentEntryId)
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
        nokia.submit(nokia.compose("command-select", BoardPlaylistOp.SetCurrent("e2")))
        network.deliver()

        network.assertConverged()
        assertEquals("e2", controller.playlist().currentEntryId)
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
    @Test fun `an explicit send moves the confirmed projection and not the selection`() = runTest {
        val network = mesh("controller", "nokia")
        val nokia = network.node("nokia")
        val controller = network.node("controller")
        nokia.submit(nokia.compose("command-seed",
            BoardPlaylistOp.Add("e1", "climb-a", 40),
            BoardPlaylistOp.Add("e2", "climb-b", 40)))
        network.deliver()

        var writes = 0
        val entry = controller.playlist().currentEntry()!!
        val result = controller.coordinator.project(board,
            BoardProjection(entry.climbUuid, entry.angle), network.monotonic,
            "projection-0001", null) { writes++; true }
        network.deliver()

        assertTrue(result is ProjectionResult.Committed)
        assertEquals(1, writes)
        assertEquals("climb-a", controller.snapshot().projection?.climbUuid)
        assertEquals("e1", controller.playlist().currentEntryId)
        // And the selection can now move away from it without the wall
        // following along.
        nokia.submit(nokia.compose("command-select", BoardPlaylistOp.SetCurrent("e2")))
        network.deliver()
        assertEquals("e2", nokia.playlist().currentEntryId)
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
        assertEquals(entries.first().entryId, network.node("member-20").playlist().currentEntryId)
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
}
