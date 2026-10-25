package com.cruxcoach.android.sharing.p2p

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Fragmentation of one exchange container over GATT writes/notifications.
 *
 * ```text
 * fragment := index:u16 last:u8 payload
 * message  := length:u32 bytes        (split over the payloads, index 0..n)
 * ```
 *
 * The receiver requires contiguous indices from 0, the announced length, and
 * a bound; anything else discards the message (the channel layer retransmits).
 * The advertisement carries rotating beacons only (manufacturer data).
 */
object ShareBleFraming {
    const val HEADER = 3
    /** ATT header of a write or notification. */
    const val ATT_OVERHEAD = 3
    const val COMPANY_ID = 0xFFFF
    val MAGIC = byteArrayOf(0x43, 0x43, 0x53, 0x31) // "CCS1"

    fun fragments(message: ByteArray, mtu: Int): List<ByteArray> {
        val size = (mtu - ATT_OVERHEAD - HEADER).coerceAtLeast(8)
        val framed = ByteBuffer.allocate(4 + message.size).putInt(message.size).put(message).array()
        val chunks = framed.toList().chunked(size).map { it.toByteArray() }
        require(chunks.size <= 0xFFFF) { "ble_message_too_large" }
        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER + chunk.size)
                .putShort(index.toShort())
                .put(if (index == chunks.lastIndex) 1 else 0)
                .put(chunk)
                .array()
        }
    }

    class Reassembler(private val max: Int) {
        private val buffer = ByteArrayOutputStream()
        private var next = 0
        private var expected = -1

        /** Returns the complete message, null while incomplete; throws on a violation. */
        fun add(fragment: ByteArray): ByteArray? {
            require(fragment.size > HEADER) { "ble_fragment" }
            val b = ByteBuffer.wrap(fragment)
            val index = b.short.toInt() and 0xFFFF
            val last = b.get().toInt() == 1
            require(index == next) { "ble_fragment_order" }
            next++
            buffer.write(fragment, HEADER, fragment.size - HEADER)
            if (expected < 0 && buffer.size() >= 4) {
                expected = ByteBuffer.wrap(buffer.toByteArray(), 0, 4).int
                require(expected in 1..max) { "ble_message_size" }
            }
            require(buffer.size() <= max + 4) { "ble_message_size" }
            if (!last) return null
            val all = buffer.toByteArray()
            require(expected >= 0 && all.size == expected + 4) { "ble_message_length" }
            return all.copyOfRange(4, all.size)
        }
    }

    /** Manufacturer data: magic, a reserved flags byte (0), up to two beacons (device, then contact code or invitation). */
    fun advertisement(hello: ShareHello): ByteArray {
        val beacons = hello.beacons.take(2).map { hex -> ByteArray(8) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(0)
        beacons.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun parseAdvertisement(data: ByteArray?): ShareHello? {
        if (data == null || data.size < MAGIC.size + 1 + 8 || data.size > MAGIC.size + 1 + 16) return null
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val beacons = data.copyOfRange(MAGIC.size + 1, data.size).toList().chunked(8)
            .filter { it.size == 8 }
            .map { chunk -> chunk.joinToString("") { "%02x".format(it) } }
        return ShareHello(beacons)
    }
}
