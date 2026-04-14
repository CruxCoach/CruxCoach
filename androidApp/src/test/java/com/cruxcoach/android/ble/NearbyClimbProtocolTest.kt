package com.cruxcoach.android.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [NearbyClimbProtocol] — BLE manufacturer data encode/decode.
 * Ensures the binary protocol remains stable and correct.
 */
class NearbyClimbProtocolTest {

    // ── ClimbData round-trip (UUID format) ───────────────────────

    @Test
    fun `encodeClimbData + decode round-trips UUID with hyphens`() {
        val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val angle = 40

        val encoded = NearbyClimbProtocol.encodeClimbData(uuid, angle)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.ClimbData>(decoded)
        assertEquals(uuid, decoded.climbUuid)
        assertEquals(angle, decoded.angle)
    }

    @Test
    fun `encodeClimbData + decode round-trips UUID without hyphens uppercase`() {
        val uuid = "A1B2C3D4E5F67890ABCDEF1234567890"
        val angle = 25

        val encoded = NearbyClimbProtocol.encodeClimbData(uuid, angle)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.ClimbData>(decoded)
        assertEquals(uuid, decoded.climbUuid)
        assertEquals(angle, decoded.angle)
    }

    @Test
    fun `encodeClimbData preserves angle boundary values`() {
        for (angle in listOf(0, 1, 40, 70)) {
            val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            val encoded = NearbyClimbProtocol.encodeClimbData(uuid, angle)
            val decoded = NearbyClimbProtocol.decode(encoded)
            assertIs<NearbyPayload.ClimbData>(decoded)
            assertEquals(angle, decoded.angle, "angle=$angle failed")
        }
    }

    // ── ClimbData round-trip (string format for numeric IDs) ────

    @Test
    fun `encodeClimbData + decode round-trips numeric string ID`() {
        val climbId = "12345678"
        val angle = 45

        val encoded = NearbyClimbProtocol.encodeClimbData(climbId, angle)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.ClimbData>(decoded)
        assertEquals(climbId, decoded.climbUuid)
        assertEquals(angle, decoded.angle)
    }

    @Test
    fun `encodeClimbData handles max length string ID`() {
        // 17 chars is the max for string encoding (24 byte budget - 7 header)
        val climbId = "12345678901234567"
        val encoded = NearbyClimbProtocol.encodeClimbData(climbId, 30)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.ClimbData>(decoded)
        assertEquals(climbId, decoded.climbUuid)
    }

    // ── LastClimb round-trip ─────────────────────────────────────

    @Test
    fun `encodeLastClimb + decode produces LastClimb payload`() {
        val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val angle = 40

        val encoded = NearbyClimbProtocol.encodeLastClimb(uuid, angle)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.LastClimb>(decoded)
        assertEquals(uuid, decoded.climbUuid)
        assertEquals(angle, decoded.angle)
    }

    @Test
    fun `encodeLastClimb with string ID produces LastClimb payload`() {
        val climbId = "99887766"
        val encoded = NearbyClimbProtocol.encodeLastClimb(climbId, 55)
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.LastClimb>(decoded)
        assertEquals(climbId, decoded.climbUuid)
        assertEquals(55, decoded.angle)
    }

    // ── DisconnectRequest ────────────────────────────────────────

    @Test
    fun `encodeDisconnectRequest + decode round-trips`() {
        val encoded = NearbyClimbProtocol.encodeDisconnectRequest()
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.DisconnectRequest>(decoded)
    }

    @Test
    fun `disconnect request payload is exactly 6 bytes`() {
        val encoded = NearbyClimbProtocol.encodeDisconnectRequest()
        assertEquals(6, encoded.size)
    }

    // ── BoardConnected ───────────────────────────────────────────

    @Test
    fun `encodeBoardConnected + decode round-trips`() {
        val encoded = NearbyClimbProtocol.encodeBoardConnected()
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.BoardConnected>(decoded)
    }

    // ── Gone ─────────────────────────────────────────────────────

    @Test
    fun `encodeGone + decode round-trips`() {
        val encoded = NearbyClimbProtocol.encodeGone()
        val decoded = NearbyClimbProtocol.decode(encoded)

        assertIs<NearbyPayload.Gone>(decoded)
    }

    // ── MAGIC header validation ──────────────────────────────────

    @Test
    fun `all payloads start with CRUX magic bytes`() {
        val payloads = listOf(
            NearbyClimbProtocol.encodeClimbData("a1b2c3d4-e5f6-7890-abcd-ef1234567890", 40),
            NearbyClimbProtocol.encodeLastClimb("a1b2c3d4-e5f6-7890-abcd-ef1234567890", 40),
            NearbyClimbProtocol.encodeDisconnectRequest(),
            NearbyClimbProtocol.encodeBoardConnected(),
            NearbyClimbProtocol.encodeGone()
        )
        val magic = byteArrayOf(0x43, 0x52, 0x55, 0x58) // "CRUX"
        for (payload in payloads) {
            assertTrue(payload.size >= 4, "payload too short")
            for (i in magic.indices) {
                assertEquals(magic[i], payload[i], "magic mismatch at index $i")
            }
        }
    }

    // ── Decode rejects garbage ───────────────────────────────────

    @Test
    fun `decode returns null for empty data`() {
        assertNull(NearbyClimbProtocol.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for data too short`() {
        assertNull(NearbyClimbProtocol.decode(byteArrayOf(0x43, 0x52)))
    }

    @Test
    fun `decode returns null for wrong magic`() {
        val bad = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x28)
        assertNull(NearbyClimbProtocol.decode(bad))
    }

    @Test
    fun `decode returns null for unknown type byte`() {
        val buf = byteArrayOf(0x43, 0x52, 0x55, 0x58, 0x7F.toByte(), 0x00)
        assertNull(NearbyClimbProtocol.decode(buf))
    }

    @Test
    fun `decode returns null for truncated UUID payload`() {
        // Valid magic + type 0x01 but only 10 bytes total (need 22)
        val buf = ByteArray(10)
        NearbyClimbProtocol.MAGIC.copyInto(buf, 0)
        buf[4] = 0x01
        assertNull(NearbyClimbProtocol.decode(buf))
    }

    // ── UUID format preservation ─────────────────────────────────

    @Test
    fun `hyphenated UUID stays hyphenated lowercase after round-trip`() {
        val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val encoded = NearbyClimbProtocol.encodeClimbData(uuid, 40)
        val decoded = NearbyClimbProtocol.decode(encoded) as NearbyPayload.ClimbData
        assertTrue(decoded.climbUuid.contains("-"), "should contain hyphens")
        assertEquals(uuid, decoded.climbUuid)
    }

    @Test
    fun `non-hyphenated UUID stays uppercase without hyphens after round-trip`() {
        val uuid = "A1B2C3D4E5F67890ABCDEF1234567890"
        val encoded = NearbyClimbProtocol.encodeClimbData(uuid, 40)
        val decoded = NearbyClimbProtocol.decode(encoded) as NearbyPayload.ClimbData
        assertTrue(!decoded.climbUuid.contains("-"), "should not contain hyphens")
        assertEquals(uuid, decoded.climbUuid)
    }

    // ── Payload size constraints ─────────────────────────────────

    @Test
    fun `UUID climb payload is exactly 22 bytes`() {
        val encoded = NearbyClimbProtocol.encodeClimbData(
            "a1b2c3d4-e5f6-7890-abcd-ef1234567890", 40
        )
        assertEquals(22, encoded.size)
    }

    @Test
    fun `string climb payload size is 7 + string length`() {
        val climbId = "42"
        val encoded = NearbyClimbProtocol.encodeClimbData(climbId, 40)
        assertEquals(7 + climbId.length, encoded.size)
    }
}
