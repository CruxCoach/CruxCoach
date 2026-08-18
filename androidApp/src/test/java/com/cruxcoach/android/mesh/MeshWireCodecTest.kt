package com.cruxcoach.android.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshWireCodecTest {
    private val realm = MeshRealmId("cell-42")

    @Test fun `realm and protocol survive the round trip`() {
        val payload = byteArrayOf(1, 2, 3, 0, -7)

        val decoded = MeshWireCodec.decode(
            MeshWireCodec.encode(realm, MeshProtocols.BOARD_CELL, payload),
        )

        assertEquals(realm, decoded?.realmId)
        assertEquals(MeshProtocols.BOARD_CELL, decoded?.protocol)
        assertArrayEquals(payload, decoded?.payload)
    }

    @Test fun `an empty payload is a valid envelope`() {
        val decoded = MeshWireCodec.decode(
            MeshWireCodec.encode(realm, MeshProtocols.COMPETITION, ByteArray(0)),
        )

        assertEquals(MeshProtocols.COMPETITION, decoded?.protocol)
        assertEquals(0, decoded?.payload?.size)
    }

    @Test fun `the link-local admission prefix is never an application envelope`() {
        // "CCJ1" plus a plausible body: the transport handles it, routing must not.
        val joinHello = byteArrayOf(0x43, 0x43, 0x4a, 0x31) + """{"realmId":"cell-42"}""".encodeToByteArray()

        assertNull(MeshWireCodec.decode(joinHello))
    }

    @Test fun `unframed and truncated bytes decode to nothing`() {
        assertNull(MeshWireCodec.decode(ByteArray(0)))
        assertNull(MeshWireCodec.decode("plain board cell frame".encodeToByteArray()))
        val framed = MeshWireCodec.encode(realm, MeshProtocols.BOARD_CELL, byteArrayOf(9))
        assertNull(MeshWireCodec.decode(framed.copyOfRange(0, framed.size - 3)))
    }

    @Test fun `a mis-tagged length never yields a silently repaired realm`() {
        val framed = MeshWireCodec.encode(realm, MeshProtocols.BOARD_CELL, byteArrayOf(9))
        val shifted = framed.copyOf().also { it[4] = (realm.value.length + 2).toByte() }

        val decoded = MeshWireCodec.decode(shifted)

        // Either rejected outright or decoded to a different realm — never
        // silently to the realm it claims to be.
        if (decoded != null) assert(decoded.realmId != realm || decoded.protocol != MeshProtocols.BOARD_CELL)
    }

    @Test fun `an unknown but well-formed protocol still decodes so routing can log it`() {
        val decoded = MeshWireCodec.decode(MeshWireCodec.encode(realm, "gossip/v9", byteArrayOf(1)))

        assertEquals("gossip/v9", decoded?.protocol)
    }

    @Test fun `protocol grammar rejects control characters and oversized tags`() {
        assert(MeshProtocols.isWellFormed(MeshProtocols.BOARD_CELL))
        assert(MeshProtocols.isWellFormed(MeshProtocols.COMPETITION))
        assert(!MeshProtocols.isWellFormed(""))
        assert(!MeshProtocols.isWellFormed("BoardCell/v1"))
        assert(!MeshProtocols.isWellFormed("board cell/v1"))
        assert(!MeshProtocols.isWellFormed("x".repeat(MeshProtocols.MAX_LENGTH + 1)))
    }
}
