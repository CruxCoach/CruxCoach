package com.cruxcoach.board

import com.cruxcoach.domain.board.CruxBoardFrameEncoder
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Capability
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Opcode
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Role
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.RouteHold
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.Status
import com.cruxcoach.domain.board.CruxBoardFrameEncoder.WifiMode
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-parity tests for [CruxBoardFrameEncoder] against the firmware's
 * `protocol.h` / `test/test_protocol/test_protocol.cpp`. The Kotlin encoder
 * MUST produce frames byte-identical to the ones the board decodes, so the
 * expected byte arrays here are hand-derived from the wire spec (little-endian)
 * and mirror the firmware's own Unity test vectors.
 */
class CruxBoardFrameEncoderTest {

    private fun ub(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ── Header helper (mirrors test_header_roundtrip) ────────────────────

    @Test
    fun parseHeaderRoundTrips() {
        // version=1, opcode=EFFECT(0x03), payloadLen=3 (LE) + 3 payload bytes.
        val frame = ub(1, Opcode.EFFECT, 3, 0, 0xAA, 0xBB, 0xCC)
        val hdr = CruxBoardFrameEncoder.parseHeader(frame)!!
        assertEquals(CruxBoardFrameEncoder.PROTOCOL_VERSION, hdr.version)
        assertEquals(Opcode.EFFECT, hdr.opcode)
        assertEquals(3, hdr.payloadLength)
    }

    @Test
    fun parseHeaderRejectsMalformedFrames() {
        // A well-formed CLEAR-with-2-payload frame is the baseline.
        val good = ub(1, Opcode.CLEAR, 2, 0, 0, 0)
        assertTrue(CruxBoardFrameEncoder.parseHeader(good) != null)

        assertNull(CruxBoardFrameEncoder.parseHeader(ub(1, Opcode.CLEAR)))        // short buffer
        assertNull(CruxBoardFrameEncoder.parseHeader(good.copyOf(good.size - 1))) // truncated
        assertNull(CruxBoardFrameEncoder.parseHeader(good + byteArrayOf(0)))      // trailing junk
        assertNull(CruxBoardFrameEncoder.parseHeader(ub(99, Opcode.CLEAR, 2, 0, 0, 0))) // bad version
        // length field beyond MAX_PAYLOAD (0xFFFF) is rejected.
        assertNull(CruxBoardFrameEncoder.parseHeader(ub(1, Opcode.OTA_CHUNK, 0xFF, 0xFF)))
    }

    // ── SET_ROUTE (mirrors test_route_roundtrip) ─────────────────────────

    @Test
    fun encodeSetRouteMultiHoldLittleEndian() {
        // Firmware vector: {2,START}, {300,HAND}, {77,FINISH}.
        // 300 = 0x012C → LE bytes 2C 01 exercises the u16 little-endian holdIndex.
        val frame = CruxBoardFrameEncoder.encodeSetRoute(
            listOf(
                RouteHold(2, Role.START),
                RouteHold(300, Role.HAND),
                RouteHold(77, Role.FINISH),
            ),
        )
        val expected = ub(
            1, Opcode.SET_ROUTE, 9, 0, // header: version, opcode, payloadLen=9 LE
            0x02, 0x00, Role.START,    // hold 2
            0x2C, 0x01, Role.HAND,     // hold 300 (LE)
            0x4D, 0x00, Role.FINISH,   // hold 77
        )
        assertContentEquals(expected, frame)

        val hdr = CruxBoardFrameEncoder.parseHeader(frame)!!
        assertEquals(Opcode.SET_ROUTE, hdr.opcode)
        assertEquals(9, hdr.payloadLength)
    }

    @Test
    fun encodeSetRouteEmptyIsBareHeader() {
        // routePayloadValid(0) == true on the firmware: empty route = clear.
        val frame = CruxBoardFrameEncoder.encodeSetRoute(emptyList())
        assertContentEquals(ub(1, Opcode.SET_ROUTE, 0, 0), frame)
    }

    // ── roleId → L1 role translation ─────────────────────────────────────

    @Test
    fun storedRoleIdsTranslateToL1Roles() {
        // Boulder ids (12-15).
        assertEquals(Role.START, Role.fromStoredRoleId(HoldRole.START))   // 12 → 0
        assertEquals(Role.HAND, Role.fromStoredRoleId(HoldRole.HAND))     // 13 → 1
        assertEquals(Role.FINISH, Role.fromStoredRoleId(HoldRole.FINISH)) // 14 → 3
        assertEquals(Role.FOOT, Role.fromStoredRoleId(HoldRole.FOOT))     // 15 → 2
        // Route ids (42-45) normalise to the same L1 roles.
        assertEquals(Role.START, Role.fromStoredRoleId(HoldRole.ROUTE_START))   // 42 → 0
        assertEquals(Role.HAND, Role.fromStoredRoleId(HoldRole.ROUTE_HAND))     // 43 → 1
        assertEquals(Role.FINISH, Role.fromStoredRoleId(HoldRole.ROUTE_FINISH)) // 44 → 3
        assertEquals(Role.FOOT, Role.fromStoredRoleId(HoldRole.ROUTE_FOOT))     // 45 → 2
        // FOOT (2) precedes FINISH (3) in the L1 numbering — guard the ordering.
        assertEquals(2, Role.FOOT)
        assertEquals(3, Role.FINISH)
    }

    @Test
    fun storedRoleIdTranslationRejectsUnknown() {
        assertFailsWith<IllegalArgumentException> { Role.fromStoredRoleId(99) }
    }

    @Test
    fun setRouteFromStoredRoleIdsProducesL1Bytes() {
        // Author frame roleIds (start 12, foot 15, finish 14) → L1 (0, 2, 3).
        val frame = CruxBoardFrameEncoder.encodeSetRoute(
            listOf(
                RouteHold(10, Role.fromStoredRoleId(HoldRole.START)),
                RouteHold(11, Role.fromStoredRoleId(HoldRole.FOOT)),
                RouteHold(12, Role.fromStoredRoleId(HoldRole.FINISH)),
            ),
        )
        val expected = ub(
            1, Opcode.SET_ROUTE, 9, 0,
            0x0A, 0x00, 0, // hold 10, START
            0x0B, 0x00, 2, // hold 11, FOOT
            0x0C, 0x00, 3, // hold 12, FINISH
        )
        assertContentEquals(expected, frame)
    }

    // ── STATE decode round-trip (mirrors test_state_roundtrip) ───────────

    @Test
    fun decodeStateRoundTrip() {
        // Same field values as the firmware's test_state_roundtrip.
        val caps = Capability.SET_ROUTE or Capability.OTA or Capability.IMU // bits 0,3,4 = 25
        val payload = ub(
            1,             // protocolVersion
            1, 4, 2,       // fwMajor/Minor/Patch
            25, 0, 0, 0,   // capabilities u32 LE (0x19)
            0x95, 0x01,    // angleDeciDeg i16 LE = 405
            200,           // brightness
            0xC8, 0x00,    // ledCount u16 LE = 200
            0xB4, 0x00,    // holdCount u16 LE = 180
            WifiMode.AP_STA,
            1,             // authRequired
        )
        val state = CruxBoardFrameEncoder.decodeState(payload)
        assertEquals(1, state.protocolVersion)
        assertEquals(1, state.fwMajor)
        assertEquals(4, state.fwMinor)
        assertEquals(2, state.fwPatch)
        assertEquals(caps.toLong(), state.capabilities)
        assertEquals(405, state.angleDeciDeg)
        assertEquals(200, state.brightness)
        assertEquals(200, state.ledCount)
        assertEquals(180, state.holdCount)
        assertEquals(WifiMode.AP_STA, state.wifiMode)
        assertTrue(state.authRequired)

        // hasCapability reflects the bitfield.
        assertTrue(state.hasCapability(Capability.SET_ROUTE))
        assertTrue(state.hasCapability(Capability.OTA))
        assertTrue(state.hasCapability(Capability.IMU))
        assertEquals(false, state.hasCapability(Capability.TOUCH))
    }

    @Test
    fun decodeStateSurvivesAngleUnknownSentinel() {
        // angleDeciDeg = kAngleUnknown (INT16_MIN = 0x8000) must decode negative.
        val payload = ub(
            1, 0, 0, 0, 0, 0, 0, 0,
            0x00, 0x80, // angleDeciDeg i16 LE = -32768
            0, 0, 0, 0, 0, WifiMode.AP_ONLY, 0,
        )
        val state = CruxBoardFrameEncoder.decodeState(payload)
        assertEquals(CruxBoardFrameEncoder.ANGLE_UNKNOWN, state.angleDeciDeg)
        assertEquals(-32768, state.angleDeciDeg)
    }

    @Test
    fun decodeStateRejectsBadLength() {
        assertFailsWith<IllegalArgumentException> {
            CruxBoardFrameEncoder.decodeState(ByteArray(16)) // one byte short
        }
    }

    // ── ACK decode (mirrors test_ack_encode) ─────────────────────────────

    @Test
    fun decodeAck() {
        // ACK echoing SET_ROUTE with ERR_RANGE.
        val info = CruxBoardFrameEncoder.decodeAck(ub(Opcode.SET_ROUTE, Status.ERR_RANGE))
        assertEquals(Opcode.SET_ROUTE, info.echoedOpcode)
        assertEquals(Status.ERR_RANGE, info.status)
    }

    @Test
    fun decodeAckRejectsBadLength() {
        assertFailsWith<IllegalArgumentException> {
            CruxBoardFrameEncoder.decodeAck(ub(Opcode.SET_ROUTE))
        }
    }

    // ── brightness / clear / ping / auth / effect ────────────────────────

    @Test
    fun encodeBrightness() {
        assertContentEquals(ub(1, Opcode.BRIGHTNESS, 1, 0, 200), CruxBoardFrameEncoder.encodeBrightness(200))
    }

    @Test
    fun encodeBrightnessRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { CruxBoardFrameEncoder.encodeBrightness(256) }
    }

    @Test
    fun encodeClearIsBareHeader() {
        assertContentEquals(ub(1, Opcode.CLEAR, 0, 0), CruxBoardFrameEncoder.encodeClear())
    }

    @Test
    fun encodePingIsBareHeader() {
        assertContentEquals(ub(1, Opcode.PING, 0, 0), CruxBoardFrameEncoder.encodePing())
    }

    @Test
    fun encodeAuthCarriesUtf8Token() {
        // "hi" → 0x68 0x69, payloadLen=2.
        assertContentEquals(ub(1, Opcode.AUTH, 2, 0, 0x68, 0x69), CruxBoardFrameEncoder.encodeAuth("hi"))
    }

    @Test
    fun encodeEffect() {
        // effectId=2 (PULSE), speed=1, intensity=3.
        assertContentEquals(
            ub(1, Opcode.EFFECT, 3, 0, 2, 1, 3),
            CruxBoardFrameEncoder.encodeEffect(2, 1, 3),
        )
    }
}
