package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCellWireTest {
    private class Link(
        override val localNpub: String,
        var direct: Set<String> = emptySet(),
    ) : AuthenticatedMeshLink {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
            sent += authenticatedPeerNpub to payload
            return true
        }
        override fun directAuthenticatedPeers(): Set<String> = direct
    }

    @Test fun `discovery claim from transit peer is rejected`() = runTest {
        val link = Link("host")
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("host", transport, settleMs = 0))
        val claim = BoardCellClaim(PhysicalBoardId("board"), BoardCellId("cell"),
            "remote", 1, 1)
        val result = transport.receive("remote",
            BoardCellWireCodec.encode(BoardCellWireMessage.DirectClaim(claim)))
        assertTrue(result is BoardCellApplyResult.Rejected)
    }

    @Test fun `session command requires current member cell epoch and sequence`() = runTest {
        val link = Link("host", setOf("member"))
        val transport = BoardCellMeshTransport(link)
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0)
        transport.attach(coordinator)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1)
        coordinator.settle(board, 1)
        coordinator.joinMember(board, "member")
        val snapshot = coordinator.snapshot(board)!!
        transport.rememberSnapshot(snapshot)
        val accepted = mutableListOf<ByteArray>()
        transport.onSessionCommand = { _, payload -> accepted += payload }

        fun message(sequence: Long) = BoardCellWireCodec.encode(BoardCellWireMessage.SessionCommand(
            "id-$sequence", snapshot.cellId, board, snapshot.epoch, sequence, byteArrayOf(7)))

        val rejected = transport.receive("member", message(snapshot.sequence + 1))
        assertTrue(rejected is BoardCellApplyResult.Rejected)
        transport.receive("member", message(snapshot.sequence))
        transport.receive("member", message(snapshot.sequence)) // command-id dedupe
        assertEquals(1, accepted.size)
    }

    @Test fun `event without join snapshot requests full state from authenticated source`() = runTest {
        val link = Link("member")
        val transport = BoardCellMeshTransport(link)
        transport.attach(BoardCellCoordinator("member", transport, settleMs = 0))
        val initial = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"),
            1, 0, "controller", 100, setOf("controller", "member")).withComputedHash()
        val event = BoardCellEvent.ProjectCommitted(BoardProjection("climb", 40))
        val next = BoardCellReplica.reduce(initial, event, 1)
        val envelope = BoardCellEnvelope(initial.cellId, initial.physicalBoardId, initial.epoch,
            1, initial.stateHash, event, next.stateHash)

        val result = transport.receive("controller",
            BoardCellWireCodec.encode(BoardCellWireMessage.Event(envelope)))

        assertTrue(result is BoardCellApplyResult.NeedSnapshot)
        assertEquals("controller", link.sent.single().first)
        assertTrue(BoardCellWireCodec.decode(link.sent.single().second) is
            BoardCellWireMessage.SnapshotRequest)
    }
}
