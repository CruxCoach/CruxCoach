package com.cruxcoach.android.mesh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realm and protocol isolation, decided before any feature decoder sees bytes. */
class MeshMessageRouterTest {
    private val board = MeshRealmId("cell-a")
    private val neighbour = MeshRealmId("cell-b")

    private class Sink : MeshMessageRouter.Handler {
        val received = mutableListOf<MeshEnvelope>()
        override suspend fun deliver(envelope: MeshEnvelope) { received += envelope }
    }

    private fun frame(
        transportRealm: MeshRealmId,
        envelopeRealm: MeshRealmId = transportRealm,
        protocol: String = MeshProtocols.BOARD_CELL,
        payload: ByteArray = byteArrayOf(7),
        sender: String = "npub-remote",
    ) = MeshInboundFrame(transportRealm.value, sender,
        MeshWireCodec.encode(envelopeRealm, protocol, payload))

    @Test fun `a registered handler receives its own realm and protocol`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        val result = router.route(board, frame(board, payload = byteArrayOf(1, 2)))

        assertEquals(MeshRouteResult.DELIVERED, result)
        assertEquals(1, sink.received.size)
        assertEquals(board, sink.received.single().realmId)
        assertEquals("npub-remote", sink.received.single().sender)
        assertEquals(MeshProtocols.BOARD_CELL, sink.received.single().protocol)
    }

    @Test fun `competition frames never reach the board cell handler`() = runTest {
        val router = MeshMessageRouter()
        val boardSink = Sink()
        val competitionSink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, boardSink)
        router.register("comp", board, MeshProtocols.COMPETITION, competitionSink)

        router.route(board, frame(board, protocol = MeshProtocols.COMPETITION, payload = byteArrayOf(9)))

        assertTrue(boardSink.received.isEmpty())
        assertEquals(1, competitionSink.received.size)
    }

    @Test fun `a frame from a foreign realm is dropped, not delivered`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        val result = router.route(board, frame(neighbour))

        assertEquals(MeshRouteResult.FOREIGN_REALM, result)
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `an envelope claiming another realm than it arrived on is dropped`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        val result = router.route(board, frame(transportRealm = board, envelopeRealm = neighbour))

        assertEquals(MeshRouteResult.TRANSPORT_REALM_MISMATCH, result)
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `a handler of another realm is not a fallback`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", neighbour, MeshProtocols.BOARD_CELL, sink)

        // The realm is live and the protocol is known, but the only handler
        // belongs to a different realm.
        val result = router.route(board, frame(board))

        assertEquals(MeshRouteResult.NO_HANDLER, result)
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `an unknown protocol is dropped instead of offered to any decoder`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        val result = router.route(board, frame(board, protocol = "gossip/v9"))

        assertEquals(MeshRouteResult.UNKNOWN_PROTOCOL, result)
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `a protocol outside the catalogue cannot even be registered`() = runTest {
        val router = MeshMessageRouter()

        assertTrue(!router.register("rogue", board, "gossip/v9", Sink()))
        assertEquals(emptySet<String>(), router.protocols(board))
    }

    @Test fun `unframed payloads never reach a feature`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        val result = router.route(board,
            MeshInboundFrame(board.value, "npub-remote", "raw board cell json".encodeToByteArray()))

        assertEquals(MeshRouteResult.UNDECODABLE, result)
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `nothing is routed while no realm is live`() = runTest {
        val router = MeshMessageRouter()
        val sink = Sink()
        router.register("board", board, MeshProtocols.BOARD_CELL, sink)

        assertEquals(MeshRouteResult.FOREIGN_REALM, router.route(null, frame(board)))
        assertTrue(sink.received.isEmpty())
    }

    @Test fun `unregistering a session stops its delivery without touching the others`() = runTest {
        val router = MeshMessageRouter()
        val leaving = Sink()
        val staying = Sink()
        router.register(leaving, board, MeshProtocols.BOARD_CELL, leaving)
        router.register(staying, board, MeshProtocols.BOARD_CELL, staying)

        router.unregister(leaving)
        val result = router.route(board, frame(board))

        assertEquals(MeshRouteResult.DELIVERED, result)
        assertTrue(leaving.received.isEmpty())
        assertEquals(1, staying.received.size)
    }
}
