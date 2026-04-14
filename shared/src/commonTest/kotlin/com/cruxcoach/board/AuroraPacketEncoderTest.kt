package com.cruxcoach.board

import com.cruxcoach.domain.board.AuroraPacketEncoder
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuroraPacketEncoderTest {

    private val encoder = AuroraPacketEncoder(apiLevel = 3)

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
            assertTrue(chunk.size <= AuroraPacketEncoder.BLE_MTU,
                "Chunk size ${chunk.size} exceeds BLE MTU ${AuroraPacketEncoder.BLE_MTU}")
        }
    }

    @Test
    fun roleToColorMapsCorrectly() {
        assertEquals(0x1C, AuroraPacketEncoder.roleToColor(HoldRole.START))  // Green
        assertEquals(0x1F, AuroraPacketEncoder.roleToColor(HoldRole.HAND))   // Cyan
        assertEquals(0xE3, AuroraPacketEncoder.roleToColor(HoldRole.FINISH)) // Magenta
        assertEquals(0xF4, AuroraPacketEncoder.roleToColor(HoldRole.FOOT))   // Orange
    }

    @Test
    fun encodeColorRgb() {
        // Green: (0,255,0) → r3=0, g3=7, b2=0 → 0b00011100 = 0x1C
        assertEquals(0x1C, AuroraPacketEncoder.encodeColor(0, 255, 0))
        // Blue: (0,0,255) → r3=0, g3=0, b2=3 → 0b00000011 = 0x03
        assertEquals(0x03, AuroraPacketEncoder.encodeColor(0, 0, 255))
        // Magenta: (255,0,255) → r3=7, g3=0, b2=3 → 0b11100011 = 0xE3
        assertEquals(0xE3, AuroraPacketEncoder.encodeColor(255, 0, 255))
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
        val api2Encoder = AuroraPacketEncoder(apiLevel = 2)
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
}
