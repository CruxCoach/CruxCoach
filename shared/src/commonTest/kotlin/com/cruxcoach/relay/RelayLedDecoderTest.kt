package com.cruxcoach.relay

import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.relay.RelayFrameReassembler
import com.cruxcoach.domain.relay.RelayLedDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decoder only has value if it is the exact inverse of what an official
 * app puts on the wire — which is what [BoardPacketEncoder] emits. So every
 * case here encodes first and asserts on the decode of those very bytes.
 */
class RelayLedDecoderTest {

    private fun wire(packets: List<ByteArray>): ByteArray {
        // The reassembler is what feeds the decoder in production; going
        // through it also proves the two agree on packet boundaries.
        val reassembler = RelayFrameReassembler()
        val complete = packets.fold(emptyList<com.cruxcoach.domain.relay.CompleteClimb>()) { acc, p ->
            acc + reassembler.offer(p)
        }
        assertEquals(1, complete.size, "expected exactly one complete climb")
        return complete.first().rawBytes
    }

    @Test
    fun decodes_api3_single_packet_climb() {
        val holds = listOf(1080 to 0x1C, 1131 to 0x1F, 1385 to 0xE3)
        val decoded = RelayLedDecoder.decode(wire(BoardPacketEncoder(apiLevel = 3).encodeClimb(holds)))

        assertNotNull(decoded)
        assertEquals(3, decoded.apiLevel)
        assertEquals(holds.map { it.first }, decoded.leds.map { it.position })
        assertEquals(holds.map { it.second }, decoded.leds.map { it.colorByte })
    }

    @Test
    fun decodes_api3_multi_packet_climb() {
        // > 84 holds forces FIRST / MIDDLE / LAST framing.
        val holds = (1..200).map { it to BoardPacketEncoder.COLOR_HAND }
        val decoded = RelayLedDecoder.decode(wire(BoardPacketEncoder(apiLevel = 3).encodeClimb(holds)))

        assertNotNull(decoded)
        assertEquals(200, decoded.leds.size)
        assertEquals(holds.map { it.first }, decoded.leds.map { it.position })
    }

    @Test
    fun decodes_api2_positions_including_the_10th_bit() {
        // 300 and 600 both need the two high bits that @2 packs into the
        // colour byte — the case a naive single-byte read gets wrong.
        val holds = listOf(12 to BoardPacketEncoder.COLOR_START, 300 to BoardPacketEncoder.COLOR_HAND,
            600 to BoardPacketEncoder.COLOR_FOOT)
        val decoded = RelayLedDecoder.decode(wire(BoardPacketEncoder(apiLevel = 2).encodeClimb(holds)))

        assertNotNull(decoded)
        assertEquals(2, decoded.apiLevel)
        assertEquals(listOf(12, 300, 600), decoded.leds.map { it.position })
    }

    @Test
    fun clear_board_packet_decodes_to_no_leds() {
        val decoded = RelayLedDecoder.decode(wire(BoardPacketEncoder(apiLevel = 3).encodeClimb(emptyList())))

        assertNotNull(decoded)
        assertTrue(decoded.leds.isEmpty(), "a clear-all write carries no LEDs")
    }

    @Test
    fun garbage_yields_null_rather_than_an_empty_climb() {
        assertNull(RelayLedDecoder.decode(byteArrayOf(0x77, 0x12, 0x00, 0x42)))
    }
}
