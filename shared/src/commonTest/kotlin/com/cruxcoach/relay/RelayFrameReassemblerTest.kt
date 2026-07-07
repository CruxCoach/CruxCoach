package com.cruxcoach.relay

import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.relay.RelayFrameReassembler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RelayFrameReassemblerTest {

    private val encoder = BoardPacketEncoder(apiLevel = 3)

    /** Encode a climb the way the official app would, then flatten its BLE
     *  chunks into one raw byte stream (what actually arrives over GATT). */
    private fun climbStream(holds: List<Pair<Int, Int>>): ByteArray =
        encoder.encodeClimb(holds).flatMap { it.toList() }.toByteArray()

    private fun feedInChunks(r: RelayFrameReassembler, stream: ByteArray, chunk: Int) =
        buildList {
            var i = 0
            while (i < stream.size) {
                val end = minOf(i + chunk, stream.size)
                addAll(r.offer(stream.copyOfRange(i, end)))
                i = end
            }
        }

    @Test
    fun onlyPacket_singleClimb_reassemblesWithHoldCountAndFaithfulBytes() {
        val holds = (10 until 15).map { it to 0x1C }
        val stream = climbStream(holds)
        val out = RelayFrameReassembler().offer(stream)

        assertEquals(1, out.size)
        val climb = out[0]
        assertEquals(holds.size, climb.holdCount)
        // Faithful: forwarded bytes == exactly what the app sent.
        assertContentEquals(stream, climb.rawBytes)
        // Re-chunk respects the board MTU and loses nothing.
        assertTrue(climb.chunks.all { it.size <= BoardPacketEncoder.BLE_MTU })
        assertContentEquals(stream, climb.chunks.flatMap { it.toList() }.toByteArray())
    }

    @Test
    fun multiPacket_firstMiddleLast_reassemblesToOneClimb() {
        // 200 holds > 84/packet -> FIRST + MIDDLE + LAST.
        val holds = (0 until 200).map { (it + 1) to 0x1F }
        val stream = climbStream(holds)
        val out = RelayFrameReassembler().offer(stream)

        assertEquals(1, out.size)
        assertEquals(200, out[0].holdCount)
        assertContentEquals(stream, out[0].rawBytes)
    }

    @Test
    fun partialWrites_acrossManyOffers_stillYieldOneClimb() {
        val holds = (0 until 120).map { (it + 1) to 0xE3 }
        val stream = climbStream(holds)
        // 7-byte dribbles straddle packet boundaries.
        val out = feedInChunks(RelayFrameReassembler(), stream, chunk = 7)
        assertEquals(1, out.size)
        assertEquals(120, out[0].holdCount)
        assertContentEquals(stream, out[0].rawBytes)
    }

    @Test
    fun framesHash_isChunkingInsensitive_butHoldSensitive() {
        val holds = (0 until 100).map { (it + 1) to 0x1C }
        val stream = climbStream(holds)

        val whole = RelayFrameReassembler().offer(stream).single().framesHash
        val dribbled = feedInChunks(RelayFrameReassembler(), stream, chunk = 3).single().framesHash
        assertEquals(whole, dribbled) // same climb, different write boundaries

        // Change one hold's colour -> different content -> different hash.
        val changed = climbStream(holds.mapIndexed { i, h -> if (i == 0) h.first to 0xF4 else h })
        val changedHash = RelayFrameReassembler().offer(changed).single().framesHash
        assertNotEquals(whole, changedHash)
    }

    @Test
    fun twoClimbsBackToBack_inOneStream_yieldTwoClimbs() {
        val a = climbStream(listOf(1 to 0x1C, 2 to 0x1F))
        val b = climbStream(listOf(3 to 0xE3, 4 to 0xF4, 5 to 0x1C))
        val out = RelayFrameReassembler().offer(a + b)
        assertEquals(2, out.size)
        assertEquals(2, out[0].holdCount)
        assertEquals(3, out[1].holdCount)
    }

    @Test
    fun leadingGarbage_isSkipped_andClimbStillParses() {
        val stream = climbStream(listOf(7 to 0x1C, 8 to 0x1F))
        val noisy = byteArrayOf(0x00, 0x7F, 0x03) + stream
        val out = RelayFrameReassembler().offer(noisy)
        assertEquals(1, out.size)
        assertContentEquals(stream, out[0].rawBytes)
    }

    @Test
    fun danglingFirstWithoutLast_isDroppedWhenAnOnlyArrives() {
        val r = RelayFrameReassembler()
        // A COMPLETE FIRST packet of a 100-hold climb (FIRST + LAST), then the
        // client abandons it and sends a complete ONLY climb — only the ONLY
        // climb should surface, the dangling FIRST is discarded.
        val stream = climbStream((0 until 100).map { (it + 1) to 0x1C })
        val firstPacketLen = (stream[1].toInt() and 0xFF) + 5 // 01 len cs 02 <payload> 03
        assertTrue(r.offer(stream.copyOfRange(0, firstPacketLen)).isEmpty()) // FIRST only, no climb yet
        val only = climbStream(listOf(9 to 0x1F, 10 to 0xE3))
        val out = r.offer(only)
        assertEquals(1, out.size)
        assertEquals(2, out[0].holdCount)
    }
}
