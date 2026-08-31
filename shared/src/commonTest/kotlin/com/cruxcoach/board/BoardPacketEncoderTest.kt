package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardPacketEncoderTest {

    private val encoder = BoardPacketEncoder(apiLevel = 3)

    @Test
    fun checksumIsOnesComplement() {
        val data = byteArrayOf(0x54, 0x10, 0x00, 0x1C)
        val cs = encoder.checksum(data)
        // Sum = 84 + 16 + 0 + 28 = 128 = 0x80
        // One's complement: ~0x80 & 0xFF = 0x7F = 127
        assertEquals(0x7F.toByte(), cs)
    }

    @Test
    fun encodeSingleHold() {
        // LED position 16 (0x10, 0x00), color green (0x1C)
        val chunks = encoder.encodeClimb(listOf(16 to 0x1C))
        assertTrue(chunks.isNotEmpty())

        // Reconstruct the full packet
        val packet = chunks.flatMap { it.toList() }
        assertEquals(0x01.toByte(), packet[0]) // Start marker
        assertEquals(0x03.toByte(), packet.last()) // End marker
        assertEquals(0x02.toByte(), packet[3]) // Separator

        // Payload is: [type=0x54, pos_lo=0x10, pos_hi=0x00, color=0x1C]
        assertEquals(0x54.toByte(), packet[4]) // 'T' = only packet
    }

    @Test
    fun encodeEmptyClimbForClear() {
        val chunks = encoder.encodeClear()
        assertTrue(chunks.isNotEmpty())

        val packet = chunks.flatMap { it.toList() }
        assertEquals(0x01.toByte(), packet[0])
        assertEquals(0x03.toByte(), packet.last())
        // Payload is just [type=0x54] (1 byte)
        assertEquals(1.toByte(), packet[1]) // dataLen = 1
    }

    @Test
    fun chunksRespectBleMtu() {
        // Create many holds to force chunking
        val holds = (0 until 50).map { it to 0x1C }
        val chunks = encoder.encodeClimb(holds)

        for (chunk in chunks) {
            assertTrue(chunk.size <= BoardPacketEncoder.BLE_MTU,
                "Chunk size ${chunk.size} exceeds BLE MTU ${BoardPacketEncoder.BLE_MTU}")
        }
    }

    @Test
    fun roleToColorMapsCorrectly() {
        assertEquals(0x1C, BoardPacketEncoder.roleToColor(HoldRole.START))  // Green
        assertEquals(0x1F, BoardPacketEncoder.roleToColor(HoldRole.HAND))   // Cyan
        assertEquals(0xE3, BoardPacketEncoder.roleToColor(HoldRole.FINISH)) // Magenta
        assertEquals(0xF4, BoardPacketEncoder.roleToColor(HoldRole.FOOT))   // Orange
    }

    @Test
    fun roleToColor_auroraCodes_mapToPaletteNotWhite() {
        // Aurora-family frames carry codes 1-4; the placement_roles-absent
        // fallback must light a real colour, never 0xFF white.
        assertEquals(0x1C, BoardPacketEncoder.roleToColor(1)) // start  → Green
        assertEquals(0x1F, BoardPacketEncoder.roleToColor(2)) // middle → Cyan (hand)
        assertEquals(0xE3, BoardPacketEncoder.roleToColor(3)) // finish → Magenta
        assertEquals(0xF4, BoardPacketEncoder.roleToColor(4)) // foot   → Orange
        // Genuinely unknown codes still fall back to white.
        assertEquals(0xFF, BoardPacketEncoder.roleToColor(99))
    }

    @Test
    fun encodeColorRgb() {
        // Green: (0,255,0) → r3=0, g3=7, b2=0 → 0b00011100 = 0x1C
        assertEquals(0x1C, BoardPacketEncoder.encodeColor(0, 255, 0))
        // Blue: (0,0,255) → r3=0, g3=0, b2=3 → 0b00000011 = 0x03
        assertEquals(0x03, BoardPacketEncoder.encodeColor(0, 0, 255))
        // Magenta: (255,0,255) → r3=7, g3=0, b2=3 → 0b11100011 = 0xE3
        assertEquals(0xE3, BoardPacketEncoder.encodeColor(255, 0, 255))
    }

    @Test
    fun encodeClimbFromHoldsWithMapping() {
        val holds = listOf(
            BoardHold(100, HoldRole.START),
            BoardHold(200, HoldRole.HAND)
        )
        val mapping = mapOf(100 to 10, 200 to 20) // placementId → ledPosition

        val chunks = encoder.encodeClimbFromHolds(holds, mapping)
        assertTrue(chunks.isNotEmpty())

        // Packet should contain 2 holds × 3 bytes = 6 bytes of hold data
        val packet = chunks.flatMap { it.toList() }
        // Total: 4 header + 1 type + 6 hold + 1 end = 12 bytes
        assertEquals(12, packet.size)
    }

    @Test
    fun encodeClimbFromHoldsSkipsMissingMappings() {
        val holds = listOf(
            BoardHold(100, HoldRole.START),
            BoardHold(999, HoldRole.HAND) // Not in mapping
        )
        val mapping = mapOf(100 to 10)

        val chunks = encoder.encodeClimbFromHolds(holds, mapping)
        val packet = chunks.flatMap { it.toList() }
        // Only 1 hold encoded: 4 header + 1 type + 3 hold + 1 end = 9 bytes
        assertEquals(9, packet.size)
    }

    @Test
    fun api2UsesCorrectPacketType() {
        val api2Encoder = BoardPacketEncoder(apiLevel = 2)
        val chunks = api2Encoder.encodeClimb(listOf(10 to 0x1C))
        val packet = chunks.flatMap { it.toList() }
        assertEquals(0x50.toByte(), packet[4]) // 'P' = API2 only packet
    }

    @Test
    fun highLedPositionEncodedCorrectly() {
        // Position 300 = 0x012C → lo=0x2C, hi=0x01
        val chunks = encoder.encodeClimb(listOf(300 to 0x03))
        val packet = chunks.flatMap { it.toList() }
        assertEquals(0x2C.toByte(), packet[5]) // pos low byte
        assertEquals(0x01.toByte(), packet[6]) // pos high byte
        assertEquals(0x03.toByte(), packet[7]) // color
    }

    // ── FEAT-031 @2 (API level < 3) power-scaling, against the BoardSesh spec ──
    // DEVICE-VERIFICATION OWED: these lock the wire bytes to the spec; the @2
    // path is NOT verified on real @2 hardware.

    @Test
    fun v3SingleHoldEncodesThreeBytesUnchanged() {
        // Lock the @3 wire format (must stay byte-identical): 3 bytes/LED,
        // API3_ONLY type. Position 16 → posLo 0x10, posHi 0x00, colour 0x1C.
        val packet = encoder.encodeClimb(listOf(16 to 0x1C)).flatMap { it.toList() }
        assertEquals(9, packet.size) // 4 header + type + 3 LED + end
        assertEquals(0x54.toByte(), packet[4]) // 'T' API3 ONLY
        assertEquals(0x10.toByte(), packet[5])
        assertEquals(0x00.toByte(), packet[6])
        assertEquals(0x1C.toByte(), packet[7]) // RGB332 colour unchanged
        assertEquals(0x03.toByte(), packet.last())
    }

    @Test
    fun kilterSimulatorClimbLocksTheSuccessfulApi3TransportBytes() {
        val holds = listOf(
            198 to 0xF4, 244 to 0xF4, 140 to 0xF4, 241 to 0xF4, 137 to 0xF4,
            206 to 0x1C, 257 to 0x1C, 260 to 0x1F, 159 to 0x1F, 284 to 0xF4,
            215 to 0x1F, 216 to 0x1F, 270 to 0x1F, 272 to 0xE3, 323 to 0xE3,
        )
        val expected =
            "012ef60254c600f4f400f48c00f4f100f48900f4" +
                "ce001c01011c04011f9f001f1c01f4d7001fd800" +
                "1f0e011f1001e34301e303"
        val expectedBytes = expected.chunked(2).map { it.toInt(16).toByte() }

        assertEquals(expectedBytes, encoder.encodeClimb(holds).flatMap { it.toList() })
    }

    @Test
    fun v2EncodesTwoBytesPerLedPerSpec() {
        // @2 hardware: 2 bytes/LED (not 3). Position 10, green (0x1C), full scale.
        // green 0x1C → (0,255,0); scaledColourV2 → (0,3,0); posHi 0.
        // colourByte = (0<<6)|(3<<4)|(0<<2)|0 = 0x30.
        val v2 = BoardPacketEncoder(apiLevel = 2)
        val packet = v2.encodeClimb(listOf(10 to 0x1C)).flatMap { it.toList() }
        assertEquals(8, packet.size) // 4 header + type + 2 LED + end
        assertEquals(0x03.toByte(), packet[1]) // dataLen = type + 2 LED bytes
        assertEquals(0x50.toByte(), packet[4]) // 'P' API2 ONLY
        assertEquals(0x0A.toByte(), packet[5]) // posLo = 10
        assertEquals(0x30.toByte(), packet[6]) // (g2=3)<<4 | posHi=0
        assertEquals(0x03.toByte(), packet.last())
    }

    @Test
    fun v2EncodePositionAndColourPacksPosHi() {
        // Position 300 = 0x12C → posLo 0x2C, posHi 1. Blue (0x03) → b2=3.
        // colourByte = (0<<6)|(0<<4)|(3<<2)|posHi(1) = 0x0D.
        val (lo, colourByte) = BoardPacketEncoder.encodePositionAndColorV2(300, 0x03, 1.0)!!
        assertEquals(0x2C.toByte(), lo)
        assertEquals(0x0D.toByte(), colourByte)
    }

    @Test
    fun v2SkipsPositionAbove10BitLimit() {
        assertNull(BoardPacketEncoder.encodePositionAndColorV2(1024, 0x1C, 1.0))
        assertNotNull(BoardPacketEncoder.encodePositionAndColorV2(1023, 0x1C, 1.0))
    }

    @Test
    fun v2ScaledColourMatchesSpec() {
        // floor(value * scale) >> 6  → 0..3
        assertEquals(3, BoardPacketEncoder.scaledColorV2(255, 1.0))
        assertEquals(2, BoardPacketEncoder.scaledColorV2(128, 1.0)) // 128>>6 = 2
        assertEquals(1, BoardPacketEncoder.scaledColorV2(255, 0.5)) // floor(127.5)=127>>6 = 1
        assertEquals(0, BoardPacketEncoder.scaledColorV2(0, 1.0))
        assertEquals(0, BoardPacketEncoder.scaledColorV2(255, 0.2)) // floor(51)>>6 = 0
    }

    @Test
    fun v2Rgb332DecodesToEightBit() {
        assertEquals(Triple(0, 255, 0), BoardPacketEncoder.rgb332ToRgb888(0x1C))   // green
        assertEquals(Triple(0, 0, 255), BoardPacketEncoder.rgb332ToRgb888(0x03))   // blue
        assertEquals(Triple(255, 0, 255), BoardPacketEncoder.rgb332ToRgb888(0xE3)) // magenta
    }

    @Test
    fun v2ComputeScaleFitsThePowerBudget() {
        // A typical climb fits at full brightness.
        assertEquals(1.0, BoardPacketEncoder.computeV2Scale(listOf(0x1C), ledsPerHold = 2), 0.0001)
        // BoardSesh boundary: a green LED contributes 0.1 power; ledsPerHold 2.
        // 90 → 2*9.0 = 18.0 ≤ 18 → 1.0; 91 → 18.2 > 18, so it drops to 0.6.
        assertEquals(1.0, BoardPacketEncoder.computeV2Scale(List(90) { 0x1C }, 2), 0.0001)
        assertEquals(0.6, BoardPacketEncoder.computeV2Scale(List(91) { 0x1C }, 2), 0.0001)
    }

    @Test
    fun ledsPerHoldByBrand() {
        assertEquals(2, BoardPacketEncoder.ledsPerHoldFor(BoardBrand.KILTER))
        assertEquals(1, BoardPacketEncoder.ledsPerHoldFor(BoardBrand.TENSION))
        assertEquals(1, BoardPacketEncoder.ledsPerHoldFor(BoardBrand.MOONBOARD))
    }
}
