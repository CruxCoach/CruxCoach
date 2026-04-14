package com.cruxcoach.android.ble

import org.junit.Assert.*
import org.junit.Test

class NearbyClimbProtocolSessionTest {

    @Test
    fun `encodeSessionAdvertisement and decode roundtrip`() {
        val encoded = NearbyClimbProtocol.encodeSessionAdvertisement(
            sessionId = 12345,
            participantCount = 3,
            hostName = "Alice"
        )
        val payload = NearbyClimbProtocol.decode(encoded)
        assertTrue(payload is NearbyPayload.SessionAdvertisement)
        val session = payload as NearbyPayload.SessionAdvertisement
        assertEquals(12345, session.sessionId)
        assertEquals(3, session.participantCount)
        assertEquals("Alice", session.hostName)
    }

    @Test
    fun `session advertisement with empty name`() {
        val encoded = NearbyClimbProtocol.encodeSessionAdvertisement(0, 0, "")
        val payload = NearbyClimbProtocol.decode(encoded) as NearbyPayload.SessionAdvertisement
        assertEquals(0, payload.sessionId)
        assertEquals(0, payload.participantCount)
        assertEquals("", payload.hostName)
    }

    @Test
    fun `session advertisement with max name length truncates`() {
        val longName = "A".repeat(20) // 20 chars > 13 max
        val encoded = NearbyClimbProtocol.encodeSessionAdvertisement(1, 1, longName)
        val payload = NearbyClimbProtocol.decode(encoded) as NearbyPayload.SessionAdvertisement
        assertEquals(13, payload.hostName.length)
        assertEquals("A".repeat(13), payload.hostName)
    }

    @Test
    fun `session advertisement fits in BLE manufacturer data limit`() {
        val encoded = NearbyClimbProtocol.encodeSessionAdvertisement(
            Int.MAX_VALUE, 255, "MaxLen13Chars"
        )
        assertTrue(encoded.size <= 24) // BLE manufacturer data limit
    }

    @Test
    fun `session advertisement negative sessionId handled`() {
        val encoded = NearbyClimbProtocol.encodeSessionAdvertisement(-1, 0, "Test")
        val payload = NearbyClimbProtocol.decode(encoded) as NearbyPayload.SessionAdvertisement
        assertEquals(-1, payload.sessionId)
    }

    @Test
    fun `TYPE_SESSION does not collide with existing types`() {
        // Verify existing types still decode correctly
        val climbData = NearbyClimbProtocol.encodeClimbData(
            "550e8400-e29b-41d4-a716-446655440000", 40
        )
        assertTrue(NearbyClimbProtocol.decode(climbData) is NearbyPayload.ClimbData)

        val boardConnected = NearbyClimbProtocol.encodeBoardConnected()
        assertTrue(NearbyClimbProtocol.decode(boardConnected) is NearbyPayload.BoardConnected)

        val gone = NearbyClimbProtocol.encodeGone()
        assertTrue(NearbyClimbProtocol.decode(gone) is NearbyPayload.Gone)

        val disconnect = NearbyClimbProtocol.encodeDisconnectRequest()
        assertTrue(NearbyClimbProtocol.decode(disconnect) is NearbyPayload.DisconnectRequest)
    }
}
