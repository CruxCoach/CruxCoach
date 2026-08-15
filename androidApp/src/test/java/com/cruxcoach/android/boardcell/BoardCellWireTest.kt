package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCellWireTest {
    private class AckStore : BoardCellDurableStore {
        private val acks = mutableMapOf<String, BoardCommandAck>()
        override fun persistSnapshot(snapshot: BoardCellSnapshot) = Unit
        override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun persistIntent(intent: BoardWriteIntent) = Unit
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = Unit
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) = Unit
        override fun pendingIntent(boardId: PhysicalBoardId): BoardWriteIntent? = null
        override fun commandAck(commandId: String): BoardCommandAck? = acks[commandId]
    }

    private class Link(
        override val localNpub: String,
        var direct: Set<String> = emptySet(),
        private val realm: String = "cell",
    ) : AuthenticatedMeshLink {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
            sent += authenticatedPeerNpub to payload; return true
        }
        override fun directAuthenticatedPeers() = direct
        override fun activeRealmId() = realm
    }

    private fun frame(sender: String, message: BoardCellWireMessage, epoch: Long = 1, term: Long = 1,
        id: String = "message-0001") = BoardCellWireCodec.encode(BoardCellWireFrame(
        messageId = id, senderId = sender, realmId = "cell", cellId = BoardCellId("cell"),
        physicalBoardId = PhysicalBoardId("board"), epoch = epoch, controllerTerm = term, message = message))

    @Test fun `discovery claim from transit peer and forged sender are rejected`() = runTest {
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("host", transport, settleMs = 0))
        val claim = BoardCellClaim(PhysicalBoardId("board"), BoardCellId("cell"), "remote", 1, "lineage")
        assertTrue(transport.receive("remote", frame("remote", BoardCellWireMessage.DirectClaim(claim))) is BoardCellApplyResult.Rejected)
        assertTrue(transport.receive("attacker", frame("remote", BoardCellWireMessage.DirectClaim(claim), id = "message-0002")) is BoardCellApplyResult.Rejected)
    }

    @Test fun `wire rejects unsupported version bounds wrong realm and replay`() = runTest {
        val link = Link("host", setOf("remote"))
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("host", transport, settleMs = 0))
        val claim = BoardCellClaim(PhysicalBoardId("board"), BoardCellId("cell"), "remote", 1, "lineage")
        val valid = frame("remote", BoardCellWireMessage.DirectClaim(claim))
        assertNull(transport.receive("remote", valid))
        assertTrue(transport.receive("remote", valid) is BoardCellApplyResult.IgnoredStale)
        val decoded = BoardCellWireCodec.decode(valid)
        val wrongVersion = BoardCellWireCodec.encode(decoded.copy(version = 1, messageId = "message-v1"))
        assertTrue(transport.receive("remote", wrongVersion) is BoardCellApplyResult.Rejected)
        val wrongRealm = BoardCellWireCodec.encode(decoded.copy(realmId = "neighbor", messageId = "message-realm"))
        assertTrue(transport.receive("remote", wrongRealm) is BoardCellApplyResult.Rejected)
        assertTrue(transport.receive("remote", ByteArray(BoardCellMeshTransport.MAX_WIRE_BYTES + 1)) is BoardCellApplyResult.Rejected)
    }

    @Test fun `session command requires member term and exact base and returns correlated acknowledgements`() = runTest {
        val link = Link("host", setOf("member"))
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val accepted = mutableListOf<InboundSessionCommand>()
        transport.onSessionCommand = { accepted += it }

        val stale = frame("member", BoardCellWireMessage.SessionCommand("stale-command", snapshot.playlistRevision + 1, byteArrayOf(7)),
            epoch = snapshot.epoch, term = snapshot.controllerTerm, id = "message-stale")
        assertTrue(transport.receive("member", stale) is BoardCellApplyResult.Rejected)
        assertEquals(BoardCommandStatus.REJECTED_STALE,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)

        val context = BoardPlaylistCommandContext(null, BoardPlaylistCommandKind.ADD)
        val good = frame("member", BoardCellWireMessage.SessionCommand("good-command",
            snapshot.playlistRevision, byteArrayOf(8), context),
            epoch = snapshot.epoch, term = snapshot.controllerTerm, id = "message-good")
        assertNull(transport.receive("member", good))
        assertEquals("good-command", accepted.single().commandId)
        assertEquals(context, accepted.single().context)
        assertEquals(BoardCommandStatus.ACCEPTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
    }

    @Test fun `durable terminal acknowledgement supersedes cached accepted retry`() = runTest {
        val link = Link("host", setOf("member")); val transport = BoardCellMeshTransport(link)
        val store = AckStore()
        val coordinator = BoardCellCoordinator("host", transport, store, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!; transport.rememberSnapshot(snapshot)
        val commandId = "accepted-then-committed"
        val command = BoardCellWireMessage.SessionCommand(commandId, snapshot.playlistRevision, byteArrayOf(8))

        assertNull(transport.receive("member", frame("member", command,
            snapshot.epoch, snapshot.controllerTerm, "message-first"), 2))
        assertEquals(BoardCommandStatus.ACCEPTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
        assertNotNull(coordinator.replacePlaylist(board, BoardPlaylistState(42, 0), 3,
            commandId, snapshot.sequence))

        assertNull(transport.receive("member", frame("member", command,
            snapshot.epoch, snapshot.controllerTerm, "message-retry"), 4))
        assertEquals(BoardCommandStatus.COMMITTED,
            (BoardCellWireCodec.decode(link.sent.last().second).message as BoardCellWireMessage.CommandAck).value.status)
    }

    @Test fun `reordered event freezes and requests full snapshot`() = runTest {
        val link = Link("member")
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("member", transport, settleMs = 0)
        transport.attach(coordinator)
        val initial = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 0,
            "controller", lineageId = "lineage", members = setOf("controller", "member")).withComputedHash()
        coordinator.restoreTrustedSnapshot(initial, 10); transport.rememberSnapshot(initial)
        val event = BoardCellEvent.ProjectCommitted(BoardProjection("later", 40), "command")
        val next = BoardCellReplica.reduce(initial, event, 2)
        val envelope = BoardCellEnvelope(initial.cellId, initial.physicalBoardId, initial.epoch,
            initial.controllerTerm, 2, initial.stateHash, event, next.stateHash)
        val result = transport.receive("controller", frame("controller", BoardCellWireMessage.Event(envelope),
            epoch = 1, term = 1, id = "message-gap"), 11)
        assertTrue(result is BoardCellApplyResult.NeedSnapshot)
        assertTrue(BoardCellWireCodec.decode(link.sent.last().second).message is BoardCellWireMessage.SnapshotRequest)
    }
}
