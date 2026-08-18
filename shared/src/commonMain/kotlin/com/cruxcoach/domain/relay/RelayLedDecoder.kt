package com.cruxcoach.domain.relay

import com.cruxcoach.domain.board.BoardPacketEncoder

/** One LED as it appeared on the wire. */
data class DecodedLed(
    val position: Int,
    /** Wire colour byte. RGB332 on @3; the 2-bit-per-channel @2 form otherwise. */
    val colorByte: Int,
)

/** LEDs of one complete climb plus the protocol level they arrived on. */
data class DecodedRelayFrame(
    val leds: List<DecodedLed>,
    val apiLevel: Int,
)

/**
 * Reads back what an official Aurora app wrote to CruxRelay's emulated board.
 *
 * The relay forwards those bytes verbatim and has, unlike a CruxCoach sender,
 * no climb identity to go with them — the packets carry LED positions and
 * colours, nothing else. Decoding them is the first half of putting a name back
 * on the "on the board" banner; [com.cruxcoach.domain.board.BoardPacketEncoder]
 * is the exact inverse of this and the round-trip is unit-tested against it.
 *
 * Pure Kotlin, no Android, and deliberately lenient: a packet it cannot make
 * sense of is skipped rather than failing the whole climb, because the forward
 * to the real board has already happened by then and a decode failure must
 * never look like a transport failure.
 */
object RelayLedDecoder {

    private const val FRAME_START: Byte = 0x01
    private const val FRAME_SEP: Byte = 0x02
    private const val FRAME_END: Byte = 0x03
    private const val FRAMING_OVERHEAD = 5 // 01 len cs 02 … 03
    private const val TYPE_INDEX = 4
    private const val PAYLOAD_START = 5

    private val API3_TYPES = setOf(
        BoardPacketEncoder.API3_ONLY, BoardPacketEncoder.API3_FIRST,
        BoardPacketEncoder.API3_MIDDLE, BoardPacketEncoder.API3_LAST,
    )
    private val API2_TYPES = setOf(
        BoardPacketEncoder.API2_ONLY, BoardPacketEncoder.API2_FIRST,
        BoardPacketEncoder.API2_MIDDLE, BoardPacketEncoder.API2_LAST,
    )

    /**
     * Decode the concatenated packets of ONE complete climb
     * ([com.cruxcoach.domain.relay.CompleteClimb.rawBytes]).
     *
     * Returns null when nothing decodable was found — an empty LED list is a
     * meaningful result of its own (the "clear the board" packet).
     */
    fun decode(rawBytes: ByteArray): DecodedRelayFrame? {
        val leds = ArrayList<DecodedLed>()
        var apiLevel = 0
        var i = 0
        while (i < rawBytes.size) {
            if (rawBytes[i] != FRAME_START) { i++; continue }
            if (i + FRAMING_OVERHEAD > rawBytes.size) break
            val dataLen = rawBytes[i + 1].toInt() and 0xFF
            val total = dataLen + FRAMING_OVERHEAD
            if (dataLen == 0 || i + total > rawBytes.size) { i++; continue }
            if (rawBytes[i + 3] != FRAME_SEP || rawBytes[i + total - 1] != FRAME_END) { i++; continue }

            val type = rawBytes[i + TYPE_INDEX]
            val payloadStart = i + PAYLOAD_START
            val payloadLen = dataLen - 1 // the type byte is part of dataLen
            when (type) {
                in API3_TYPES -> {
                    apiLevel = 3
                    var p = payloadStart
                    while (p + 2 < payloadStart + payloadLen) {
                        val lo = rawBytes[p].toInt() and 0xFF
                        val hi = rawBytes[p + 1].toInt() and 0xFF
                        leds.add(DecodedLed(lo or (hi shl 8), rawBytes[p + 2].toInt() and 0xFF))
                        p += 3
                    }
                }
                in API2_TYPES -> {
                    apiLevel = 2
                    var p = payloadStart
                    while (p + 1 < payloadStart + payloadLen) {
                        val lo = rawBytes[p].toInt() and 0xFF
                        val second = rawBytes[p + 1].toInt() and 0xFF
                        // @2 packs the two high position bits into the colour
                        // byte (BoardPacketEncoder.encodePositionAndColorV2).
                        leds.add(DecodedLed(lo or ((second and 0x03) shl 8), second))
                        p += 2
                    }
                }
                else -> Unit // unknown packet type — skip, keep what we have
            }
            i += total
        }
        if (apiLevel == 0) return null
        return DecodedRelayFrame(leds = leds, apiLevel = apiLevel)
    }
}
