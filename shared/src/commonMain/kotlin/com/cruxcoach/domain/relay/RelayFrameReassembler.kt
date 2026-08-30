package com.cruxcoach.domain.relay

import com.cruxcoach.domain.board.BoardPacketEncoder

/**
 * A complete climb reassembled from the Aurora BLE packet stream an official
 * Kilter/Aurora app writes to CruxRelay's emulated board characteristic.
 *
 * [rawBytes] is byte-identical to what the app sent (its packets concatenated),
 * so [chunks] — the same bytes re-split at [BoardPacketEncoder.BLE_MTU] for a
 * platform transport — forward the climb to the real board with ZERO
 * alteration (the board reassembles by the framing bytes, so chunk boundaries
 * are irrelevant). This is the CruxRelay faithful-pass-through invariant.
 */
data class CompleteClimb(
    val rawBytes: ByteArray,
    val chunks: List<ByteArray>,
    /** Stable 64-bit hash over the ordered hold data only (re-chunk
     *  insensitive, hold-change sensitive) — the PLAYLIST-mode dedup key. */
    val framesHash: Long,
    /** Hold count (hold data length / 3) for the generic queue label. */
    val holdCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is CompleteClimb && framesHash == other.framesHash &&
            rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = framesHash.hashCode()
}

/**
 * Reassembles the write-only Aurora packet stream into COMPLETE climbs.
 *
 * Inbound GATT writes are NOT packet-aligned (a climb spans many <=MTU writes
 * and one write can straddle packets), so bytes are buffered and split on the
 * framing `0x01 <dataLen> <checksum> 0x02 <type> <holdData...> 0x03`. Packets
 * are grouped by type into a complete climb — a single `ONLY`, or
 * `FIRST (MIDDLE)* LAST`. The relay acts only on a complete climb, so two
 * clients' interleaved writes can never corrupt each other's climb (each client
 * gets its own reassembler instance).
 *
 * Pure Kotlin (no Android / no java.*) so the framing is unit-tested against
 * the exact packet shape [BoardPacketEncoder] emits.
 */
class RelayFrameReassembler(
    /** Hard cap on buffered bytes; a malformed stream that never yields a
     *  complete packet is dropped rather than growing without bound. */
    private val maxBufferBytes: Int = 8192,
) {
    private val buffer = ArrayList<Byte>()
    private val climbPackets = ArrayList<ByteArray>()
    private var inMultiPacket = false

    /**
     * Feed inbound bytes; returns every climb COMPLETED by this feed (usually
     * zero or one, but a single write may finish a queued climb and open the
     * next). Partial trailing bytes stay buffered for the next feed.
     */
    fun offer(bytes: ByteArray): List<CompleteClimb> {
        for (b in bytes) buffer.add(b)
        if (buffer.size > maxBufferBytes) {
            // Resync hard: drop everything up to the last plausible start.
            val lastStart = buffer.indexOfLast { it == FRAME_START }
            if (lastStart <= 0) { buffer.clear() } else {
                val kept = buffer.subList(lastStart, buffer.size).toList()
                buffer.clear(); buffer.addAll(kept)
            }
        }
        val completed = ArrayList<CompleteClimb>()
        while (true) {
            val packet = nextPacket() ?: break
            reduce(packet)?.let { completed.add(it) }
        }
        return completed
    }

    /** Drop all buffered + in-progress state (client disconnected / relay stop). */
    fun reset() {
        buffer.clear(); climbPackets.clear(); inMultiPacket = false
    }

    /**
     * Extract the next complete packet from the head of [buffer], consuming it.
     * Returns null when the buffer holds no full packet yet. Skips leading
     * garbage and resyncs one byte at a time on a malformed frame.
     */
    private fun nextPacket(): ByteArray? {
        while (buffer.isNotEmpty()) {
            if (buffer[0] != FRAME_START) { buffer.removeAt(0); continue }
            // Need at least the 4-byte header to read dataLen.
            if (buffer.size < HEADER_LEN + 1) return null // + at least the end byte
            val dataLen = buffer[1].toInt() and 0xFF
            val total = dataLen + FRAMING_OVERHEAD // 01 len cs 02 <payload> 03
            if (dataLen == 0 || buffer.size < total) {
                if (dataLen == 0) { buffer.removeAt(0); continue } // invalid, resync
                return null // wait for the rest of this packet
            }
            val sepOk = buffer[3] == FRAME_SEP
            val endOk = buffer[total - 1] == FRAME_END
            if (!sepOk || !endOk) { buffer.removeAt(0); continue } // resync
            val packet = ByteArray(total) { buffer[it] }
            repeat(total) { buffer.removeAt(0) }
            return packet
        }
        return null
    }

    /**
     * Fold one packet into the in-progress climb; returns a [CompleteClimb]
     * when this packet closes one, else null.
     */
    private fun reduce(packet: ByteArray): CompleteClimb? {
        val type = packet[TYPE_INDEX]
        return when {
            type in ONLY_TYPES -> {
                // An ONLY packet is a whole climb. Any dangling FIRST/MIDDLE
                // without a LAST is an abandoned climb — drop it.
                climbPackets.clear(); inMultiPacket = false
                complete(listOf(packet))
            }
            type in FIRST_TYPES -> {
                climbPackets.clear(); climbPackets.add(packet); inMultiPacket = true
                null
            }
            type in MIDDLE_TYPES -> {
                if (inMultiPacket) climbPackets.add(packet) // else orphan → ignore
                null
            }
            type in LAST_TYPES -> {
                if (!inMultiPacket) return null // orphan LAST → ignore
                climbPackets.add(packet)
                val group = climbPackets.toList()
                climbPackets.clear(); inMultiPacket = false
                complete(group)
            }
            else -> null // unknown type → ignore this packet
        }
    }

    private fun complete(packets: List<ByteArray>): CompleteClimb {
        // rawBytes = the app's packets verbatim (faithful forward).
        val totalSize = packets.sumOf { it.size }
        val raw = ByteArray(totalSize)
        var o = 0
        for (p in packets) { p.copyInto(raw, o); o += p.size }

        // holdData = each packet's payload minus its leading type byte,
        // concatenated in order. Framing/len/checksum are excluded so the
        // hash is insensitive to how the app chunked the climb.
        val hold = ArrayList<Byte>()
        for (p in packets) {
            val dataLen = p[1].toInt() and 0xFF
            // payload occupies [4 .. 4+dataLen-1]; [4] is the type byte.
            for (i in (PAYLOAD_INDEX + 1) until (PAYLOAD_INDEX + dataLen)) hold.add(p[i])
        }
        val holdArr = hold.toByteArray()

        return CompleteClimb(
            rawBytes = raw,
            chunks = chunk(raw, BoardPacketEncoder.BLE_MTU),
            framesHash = fnv1a64(holdArr),
            holdCount = holdArr.size / HOLD_BYTES,
        )
    }

    companion object {
        private const val FRAME_START: Byte = 0x01
        private const val FRAME_SEP: Byte = 0x02
        private const val FRAME_END: Byte = 0x03
        private const val HEADER_LEN = 4          // 01 len cs 02
        private const val FRAMING_OVERHEAD = 5    // header (4) + end (1)
        private const val PAYLOAD_INDEX = 4       // first payload byte (= type)
        private const val TYPE_INDEX = 4
        private const val HOLD_BYTES = 3          // pos_lo, pos_hi, color

        private val ONLY_TYPES = setOf(BoardPacketEncoder.API3_ONLY, BoardPacketEncoder.API2_ONLY)
        private val FIRST_TYPES = setOf(BoardPacketEncoder.API3_FIRST, BoardPacketEncoder.API2_FIRST)
        private val MIDDLE_TYPES = setOf(BoardPacketEncoder.API3_MIDDLE, BoardPacketEncoder.API2_MIDDLE)
        private val LAST_TYPES = setOf(BoardPacketEncoder.API3_LAST, BoardPacketEncoder.API2_LAST)

        /** FNV-1a 64-bit — deterministic, dependency-free, good enough as a
         *  collision-resistant-enough content key for dedup (not security). */
        internal fun fnv1a64(data: ByteArray): Long {
            var h = FNV_OFFSET
            for (b in data) {
                h = h xor (b.toLong() and 0xFF)
                h *= FNV_PRIME
            }
            return h
        }

        private fun chunk(bytes: ByteArray, size: Int): List<ByteArray> {
            if (bytes.isEmpty()) return emptyList()
            val out = ArrayList<ByteArray>((bytes.size + size - 1) / size)
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + size, bytes.size)
                out.add(bytes.copyOfRange(i, end))
                i = end
            }
            return out
        }

        private val FNV_OFFSET = 0xcbf29ce484222325uL.toLong()
        private const val FNV_PRIME = 0x100000001b3L
    }
}
