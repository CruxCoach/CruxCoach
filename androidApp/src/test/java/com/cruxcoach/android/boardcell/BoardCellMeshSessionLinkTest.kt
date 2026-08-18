package com.cruxcoach.android.boardcell

import com.cruxcoach.android.mesh.MeshEnvelope
import com.cruxcoach.android.mesh.MeshOwner
import com.cruxcoach.android.mesh.MeshOwners
import com.cruxcoach.android.mesh.MeshProtocols
import com.cruxcoach.android.mesh.MeshRealmId
import com.cruxcoach.android.mesh.MeshRealmSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BoardCell wire only ever speaks into the realm session it is bound to. */
class BoardCellMeshSessionLinkTest {
    private class FakeSession(
        override val realmId: MeshRealmId,
        override val owner: MeshOwner = MeshOwners.BOARD_CELL,
        override val localPeerId: String = "npub-local",
    ) : MeshRealmSession {
        val inbox = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 16)
        val peers = MutableStateFlow<Set<String>>(emptySet())
        val sent = mutableListOf<Triple<String, String, ByteArray>>()
        val subscribed = mutableListOf<String>()
        var closed = false

        override val authenticatedPeers: StateFlow<Set<String>> get() = peers
        override val incoming: Flow<MeshEnvelope> get() = inbox
        override fun subscribe(protocol: String): Flow<MeshEnvelope> {
            subscribed += protocol
            return inbox.filter { it.protocol == protocol }
        }
        override fun send(peer: String, protocol: String, payload: ByteArray): Boolean {
            sent += Triple(peer, protocol, payload)
            return true
        }
        override fun recycleTransport(reason: String) = true
        override fun settleMembership() = Unit
        override fun close() { closed = true }
    }

    @Test fun `an unbound link is inert`() {
        val link = BoardCellMeshSessionLink()

        assertEquals("", link.localNpub)
        assertNull(link.activeRealmId())
        assertEquals(emptySet<String>(), link.directAuthenticatedPeers())
        assertFalse(link.send("npub-remote", byteArrayOf(1)))
    }

    @Test fun `every send carries the board cell protocol`() {
        val session = FakeSession(MeshRealmId("cell-a"))
        val link = BoardCellMeshSessionLink().apply { bind(session) }

        assertTrue(link.send("npub-remote", byteArrayOf(4, 2)))

        val (peer, protocol, payload) = session.sent.single()
        assertEquals("npub-remote", peer)
        assertEquals(MeshProtocols.BOARD_CELL, protocol)
        assertArrayEquals(byteArrayOf(4, 2), payload)
    }

    @Test fun `realm identity and peers come from the bound session`() {
        val session = FakeSession(MeshRealmId("cell-a")).apply { peers.value = setOf("npub-remote") }
        val link = BoardCellMeshSessionLink().apply { bind(session) }

        assertEquals("cell-a", link.activeRealmId())
        assertEquals("npub-local", link.localNpub)
        assertEquals(setOf("npub-remote"), link.directAuthenticatedPeers())

        link.bind(null)

        assertNull(link.activeRealmId())
        assertEquals(emptySet<String>(), link.directAuthenticatedPeers())
    }

    @Test fun `inbound frames follow a realm change and only carry board cell traffic`() = runTest {
        val first = FakeSession(MeshRealmId("cell-a"))
        val second = FakeSession(MeshRealmId("cell-b"))
        val link = BoardCellMeshSessionLink()
        val received = mutableListOf<MeshEnvelope>()
        link.incoming.onEach { received += it }.launchIn(backgroundScope)
        runCurrent()

        link.bind(first)
        runCurrent()
        first.inbox.emit(envelope(first.realmId, MeshProtocols.BOARD_CELL, byteArrayOf(1)))
        first.inbox.emit(envelope(first.realmId, MeshProtocols.COMPETITION, byteArrayOf(2)))
        runCurrent()

        assertEquals(listOf(MeshProtocols.BOARD_CELL), first.subscribed)
        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(1), received.single().payload)

        link.bind(second)
        runCurrent()
        first.inbox.emit(envelope(first.realmId, MeshProtocols.BOARD_CELL, byteArrayOf(3)))
        second.inbox.emit(envelope(second.realmId, MeshProtocols.BOARD_CELL, byteArrayOf(4)))
        runCurrent()

        // The superseded realm's traffic stops at the rebind.
        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(4), received.last().payload)
        assertEquals(MeshRealmId("cell-b"), received.last().realmId)
    }

    private fun envelope(realm: MeshRealmId, protocol: String, payload: ByteArray) =
        MeshEnvelope(realm, "npub-remote", protocol, payload)
}
