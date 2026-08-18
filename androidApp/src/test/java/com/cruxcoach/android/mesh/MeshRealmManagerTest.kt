package com.cruxcoach.android.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realm ownership, isolation and lifecycle end to end, with a recording
 * transport in place of the radio.
 */
class MeshRealmManagerTest {
    private val board = MeshRealmId("cell-a")
    private val neighbour = MeshRealmId("cell-b")
    private val boardMeta = MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-a", "Kilter")
    private val neighbourMeta = MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-b")

    private class FakePort : MeshTransportPort {
        override var localPeerId: String = "npub-local"
        override val authenticatedPeers = MutableStateFlow<Set<String>>(emptySet())
        val frames = MutableSharedFlow<MeshInboundFrame>(extraBufferCapacity = 64)
        override val inbound: Flow<MeshInboundFrame> get() = frames

        val runtimeOwners = linkedSetOf<MeshOwner>()
        val activated = mutableListOf<MeshRealmId>()
        val ended = mutableListOf<MeshRealmId>()
        val recycled = mutableListOf<Pair<MeshRealmId, String>>()
        val settled = mutableListOf<MeshRealmId>()
        val sent = mutableListOf<Pair<String, ByteArray>>()
        var activationSucceeds = true
        var live: MeshRealmId? = null

        override fun acquireRuntime(owner: MeshOwner) { runtimeOwners += owner }
        override fun releaseRuntime(owner: MeshOwner) { runtimeOwners -= owner }
        override fun activate(realmId: MeshRealmId, metadata: MeshRealmMetadata): Boolean {
            if (!activationSucceeds) return false
            activated += realmId
            live = realmId
            return true
        }
        override fun end(realmId: MeshRealmId) { ended += realmId; live = null }
        override fun recycle(realmId: MeshRealmId, reason: String): Boolean {
            recycled += realmId to reason
            return true
        }
        override fun settleMembership(realmId: MeshRealmId) { settled += realmId }
        override fun send(peer: String, payload: ByteArray): Boolean {
            sent += peer to payload
            return true
        }
    }

    /**
     * [runCurrent] rather than advanceUntilIdle: the manager's collectors live
     * in [TestScope.backgroundScope], which advanceUntilIdle deliberately skips.
     */
    private fun TestScope.manager(port: FakePort): MeshRealmManager =
        DefaultMeshRealmManager(port, backgroundScope).also { runCurrent() }

    private suspend fun denialOf(acquisition: suspend () -> Unit): MeshRealmUnavailableException = try {
        acquisition()
        throw AssertionError("expected the acquisition to be denied")
    } catch (denied: MeshRealmUnavailableException) {
        denied
    }

    // --- ownership and reference counting ---------------------------------

    @Test fun `the first acquire activates and the last release ends the realm`() = runTest {
        val port = FakePort()
        val manager = manager(port)

        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertEquals(listOf(board), port.activated)
        assertEquals(setOf(MeshOwners.BOARD_CELL), port.runtimeOwners)
        assertEquals(board, manager.activeRealm.value)

        session.close()

        assertEquals(listOf(board), port.ended)
        assertTrue(port.runtimeOwners.isEmpty())
        assertNull(manager.activeRealm.value)
    }

    @Test fun `repeated acquires share one session and need matching releases`() = runTest {
        val port = FakePort()
        val manager = manager(port)

        val first = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val second = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val third = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, port.activated.size)

        manager.release(MeshOwners.BOARD_CELL, board)
        manager.release(MeshOwners.BOARD_CELL, board)
        assertEquals(board, manager.activeRealm.value)
        assertTrue(port.ended.isEmpty())

        manager.release(MeshOwners.BOARD_CELL, board)
        assertEquals(listOf(board), port.ended)
    }

    @Test fun `releaseAll collapses a whole reference stack`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        repeat(3) { manager.acquire(MeshOwners.SESSION, board, boardMeta) }
        manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        manager.releaseAll(MeshOwners.SESSION)

        assertNull(manager.session(MeshOwners.SESSION))
        assertEquals(setOf(MeshOwners.BOARD_CELL), port.runtimeOwners)
        assertEquals(board, manager.activeRealm.value)
    }

    @Test fun `releasing a realm this owner does not hold changes nothing`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        manager.release(MeshOwners.BOARD_CELL, neighbour)
        manager.release(MeshOwners.NEARBY_BOARD_CELL, board)

        assertEquals(board, manager.activeRealm.value)
        assertTrue(port.ended.isEmpty())
        assertTrue(manager.session(MeshOwners.BOARD_CELL) != null)
    }

    // --- concurrent and incompatible realms -------------------------------

    @Test fun `a second feature joins the live realm without restarting it`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val boardSession = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val competition = manager.acquire(MeshOwners.competition("comp-1"), board,
            MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-a"))

        assertEquals(1, port.activated.size)
        assertTrue(port.ended.isEmpty())
        assertEquals(board, competition.realmId)
        assertTrue(boardSession !== competition)
        assertEquals(setOf(MeshOwners.BOARD_CELL, MeshOwners.competition("comp-1")), port.runtimeOwners)
    }

    @Test fun `a foreign owner cannot switch the realm out from under the board`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val denied = denialOf { manager.acquire(MeshOwners.competition("comp-1"), neighbour, neighbourMeta) }

        assertEquals(MeshRealmDenial.REALM_CONFLICT, denied.denial)
        assertEquals(board, denied.active)
        assertEquals(listOf(board), port.activated)
        assertEquals(board, manager.activeRealm.value)
    }

    @Test fun `an incompatible scope is refused even for the same realm id`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val denied = denialOf {
            manager.acquire(MeshOwners.competition("comp-1"), board,
                MeshRealmMetadata(MeshRealmKind.COMPETITION, "cell-a"))
        }

        assertEquals(MeshRealmDenial.METADATA_CONFLICT, denied.denial)
        assertNull(manager.session(MeshOwners.competition("comp-1")))
    }

    @Test fun `re-targeting closes the superseded sessions instead of stranding them`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val boardSession = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val competition = manager.acquire(MeshOwners.competition("comp-1"), board, boardMeta)
        port.authenticatedPeers.value = setOf("npub-remote")
        runCurrent()

        val next = manager.acquire(MeshOwners.BOARD_CELL, neighbour, neighbourMeta)

        assertEquals(listOf(board, neighbour), port.activated)
        assertEquals(listOf(board), port.ended)
        assertEquals(neighbour, next.realmId)
        assertNull(manager.session(MeshOwners.competition("comp-1")))
        assertFalse(competition.send("npub-remote", MeshProtocols.COMPETITION, byteArrayOf(1)))
        assertFalse(boardSession.send("npub-remote", MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        assertEquals(emptySet<String>(), competition.authenticatedPeers.value)
        assertEquals(setOf(MeshOwners.BOARD_CELL), port.runtimeOwners)
    }

    @Test fun `a refused transport leaves no lease and no active realm`() = runTest {
        val port = FakePort().apply { activationSucceeds = false }
        val manager = manager(port)

        val denied = denialOf { manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta) }

        assertEquals(MeshRealmDenial.TRANSPORT_UNAVAILABLE, denied.denial)
        assertTrue(port.runtimeOwners.isEmpty())
        assertNull(manager.activeRealm.value)
        assertNull(manager.session(MeshOwners.BOARD_CELL))

        // A later attempt is not poisoned by the failed one.
        port.activationSucceeds = true
        assertEquals(board, manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta).realmId)
    }

    // --- routing and sending ----------------------------------------------

    @Test fun `each protocol reaches only its own subscriber`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val boardSession = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val competition = manager.acquire(MeshOwners.competition("comp-1"), board, boardMeta)
        val boardFrames = mutableListOf<MeshEnvelope>()
        val competitionFrames = mutableListOf<MeshEnvelope>()
        boardSession.subscribe(MeshProtocols.BOARD_CELL)
            .onEach { boardFrames += it }.launchIn(backgroundScope)
        competition.subscribe(MeshProtocols.COMPETITION)
            .onEach { competitionFrames += it }.launchIn(backgroundScope)
        runCurrent()

        port.frames.emit(inbound(board, MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        port.frames.emit(inbound(board, MeshProtocols.COMPETITION, byteArrayOf(2)))
        runCurrent()

        assertEquals(1, boardFrames.size)
        assertArrayEquals(byteArrayOf(1), boardFrames.single().payload)
        assertEquals(1, competitionFrames.size)
        assertArrayEquals(byteArrayOf(2), competitionFrames.single().payload)
    }

    @Test fun `foreign realms and unknown protocols never reach a subscriber`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val frames = mutableListOf<MeshEnvelope>()
        session.subscribe(MeshProtocols.BOARD_CELL).onEach { frames += it }.launchIn(backgroundScope)
        runCurrent()

        port.frames.emit(inbound(neighbour, MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        port.frames.emit(inbound(board, "gossip/v9", byteArrayOf(2)))
        port.frames.emit(MeshInboundFrame(board.value, "npub-remote", byteArrayOf(3, 4, 5)))
        port.frames.emit(MeshInboundFrame(board.value, "npub-remote",
            MeshWireCodec.encode(neighbour, MeshProtocols.BOARD_CELL, byteArrayOf(6))))
        runCurrent()

        assertTrue(frames.isEmpty())
    }

    @Test fun `a send is tagged with its own realm and protocol`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertTrue(session.send("npub-remote", MeshProtocols.BOARD_CELL, byteArrayOf(4, 2)))

        val (peer, bytes) = port.sent.single()
        val decoded = MeshWireCodec.decode(bytes)
        assertEquals("npub-remote", peer)
        assertEquals(board, decoded?.realmId)
        assertEquals(MeshProtocols.BOARD_CELL, decoded?.protocol)
        assertArrayEquals(byteArrayOf(4, 2), decoded?.payload)
    }

    @Test fun `a closed session and an uncatalogued protocol cannot send`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertFalse(session.send("npub-remote", "gossip/v9", byteArrayOf(1)))
        assertFalse(session.send("", MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        session.close()
        assertFalse(session.send("npub-remote", MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        assertTrue(port.sent.isEmpty())
    }

    // --- lifecycle --------------------------------------------------------

    @Test fun `authenticated peers follow the transport and stop at close`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        port.authenticatedPeers.value = setOf("npub-remote")
        runCurrent()
        assertEquals(setOf("npub-remote"), session.authenticatedPeers.value)

        session.close()
        assertEquals(emptySet<String>(), session.authenticatedPeers.value)
        port.authenticatedPeers.value = setOf("npub-remote", "npub-other")
        runCurrent()
        assertEquals(emptySet<String>(), session.authenticatedPeers.value)
    }

    @Test fun `transport controls stay realm scoped across the lifecycle`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertTrue(session.recycleTransport("explicit join"))
        session.settleMembership()
        assertEquals(listOf(board to "explicit join"), port.recycled)
        assertEquals(listOf(board), port.settled)

        session.close()

        assertFalse(session.recycleTransport("after close"))
        session.settleMembership()
        assertEquals(1, port.recycled.size)
        assertEquals(1, port.settled.size)
    }

    @Test fun `a re-acquired realm delivers to the new session, not the retired one`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val first = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val firstFrames = mutableListOf<MeshEnvelope>()
        first.subscribe(MeshProtocols.BOARD_CELL).onEach { firstFrames += it }.launchIn(backgroundScope)
        runCurrent()
        first.close()

        val second = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        val secondFrames = mutableListOf<MeshEnvelope>()
        second.subscribe(MeshProtocols.BOARD_CELL).onEach { secondFrames += it }.launchIn(backgroundScope)
        runCurrent()

        port.frames.emit(inbound(board, MeshProtocols.BOARD_CELL, byteArrayOf(8)))
        runCurrent()

        assertTrue(first !== second)
        assertTrue(firstFrames.isEmpty())
        assertEquals(1, secondFrames.size)
        assertEquals(listOf(board, board), port.activated)
    }

    @Test fun `a retired session cannot re-register itself for delivery`() = runTest {
        val port = FakePort()
        val manager = manager(port)
        val session = manager.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        session.close()
        manager.acquire(MeshOwners.NEARBY_BOARD_CELL, board, boardMeta)
        val leaked = mutableListOf<MeshEnvelope>()
        session.subscribe(MeshProtocols.BOARD_CELL).onEach { leaked += it }.launchIn(backgroundScope)
        runCurrent()

        port.frames.emit(inbound(board, MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        runCurrent()

        assertTrue(leaked.isEmpty())
    }

    private fun inbound(realm: MeshRealmId, protocol: String, payload: ByteArray) =
        MeshInboundFrame(realm.value, "npub-remote", MeshWireCodec.encode(realm, protocol, payload))
}
