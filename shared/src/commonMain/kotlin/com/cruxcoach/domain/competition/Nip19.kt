package com.cruxcoach.domain.competition

/**
 * bech32 and the NIP-19 entities this feature needs.
 *
 * In `:shared` rather than the Android layer for two reasons. It is protocol
 * logic, so it belongs with the rest of the protocol and is pinned by the same
 * cross-client vectors the website asserts against. And it is testable: Quartz
 * ships class files for a newer JVM than the unit tests run on, so anything
 * that reaches for its NIP-19 parser cannot be covered by a JVM test at all.
 *
 * The app still uses Quartz everywhere it already did; this exists for the
 * competition paths, where a link arriving from a stranger's QR code is exactly
 * the input that has to be parsed strictly and proved to be parsed strictly.
 *
 * Source: https://github.com/nostr-protocol/nips/blob/master/19.md (2026-08-09)
 */
object Nip19 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top shr i) and 1 == 1) checksum = checksum xor GENERATOR[i]
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): List<Int> = buildList {
        for (char in hrp) add(char.code shr 5)
        add(0)
        for (char in hrp) add(char.code and 31)
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var accumulator = 0
        var bits = 0
        val out = mutableListOf<Int>()
        val max = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || (value shr from) != 0) return null
            accumulator = (accumulator shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((accumulator shr bits) and max)
            }
        }
        if (pad) {
            if (bits > 0) out.add((accumulator shl (to - bits)) and max)
        } else if (bits >= from || ((accumulator shl (to - bits)) and max) != 0) {
            return null
        }
        return out
    }

    fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes.map { it.toInt() and 0xff }, 8, 5, true)
            ?: throw IllegalArgumentException("bech32: cannot convert payload")
        val checksum = polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1
        val combined = data + (0 until 6).map { (checksum shr (5 * (5 - it))) and 31 }
        return hrp + "1" + combined.joinToString("") { CHARSET[it].toString() }
    }

    data class Decoded(val hrp: String, val bytes: ByteArray) {
        // ByteArray needs these by hand; without them two identical payloads
        // compare unequal, which is a very confusing test failure.
        override fun equals(other: Any?): Boolean =
            other is Decoded && hrp == other.hrp && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * hrp.hashCode() + bytes.contentHashCode()
    }

    /** The raw 5-bit words, checksum verified. BOLT11 needs these: its payload
     *  is a stream of word-counted tagged fields, and converting to bytes first
     *  throws away exactly the alignment that stream depends on. */
    fun decodeWords(value: String): Pair<String, List<Int>>? {
        if (value != value.lowercase() && value != value.uppercase()) return null
        val lower = value.lowercase()
        val split = lower.lastIndexOf('1')
        if (split < 1 || split + 7 > lower.length) return null
        val hrp = lower.substring(0, split)
        val data = mutableListOf<Int>()
        for (index in (split + 1) until lower.length) {
            val position = CHARSET.indexOf(lower[index])
            if (position == -1) return null
            data.add(position)
        }
        if (polymod(hrpExpand(hrp) + data) != 1) return null
        return hrp to data.subList(0, data.size - 6).toList()
    }

    /** 5-bit words back to bytes, for a field that is a whole number of bytes. */
    fun wordsToBytes(words: List<Int>): ByteArray? =
        convertBits(words, 5, 8, false)?.map { it.toByte() }?.toByteArray()

    /** @return null on a bad checksum, mixed case, or an impossible length. */
    fun decode(value: String): Decoded? {
        if (value != value.lowercase() && value != value.uppercase()) return null
        val lower = value.lowercase()
        val split = lower.lastIndexOf('1')
        if (split < 1 || split + 7 > lower.length) return null
        val hrp = lower.substring(0, split)
        val data = mutableListOf<Int>()
        for (index in (split + 1) until lower.length) {
            val position = CHARSET.indexOf(lower[index])
            if (position == -1) return null
            data.add(position)
        }
        if (polymod(hrpExpand(hrp) + data) != 1) return null
        val converted = convertBits(data.subList(0, data.size - 6), 5, 8, false) ?: return null
        return Decoded(hrp, ByteArray(converted.size) { converted[it].toByte() })
    }

    /** An addressable-event pointer: `(kind, author, d-tag)` plus optional relay hints. */
    data class NAddr(
        val identifier: String,
        val pubkey: String,
        val kind: Int,
        val relays: List<String> = emptyList(),
    )

    fun encodeNaddr(address: NAddr): String {
        val parts = mutableListOf<ByteArray>()
        fun tlv(type: Int, value: ByteArray) {
            require(value.size <= 255) { "bech32 TLV value too long" }
            parts.add(byteArrayOf(type.toByte(), value.size.toByte()))
            parts.add(value)
        }
        tlv(0, address.identifier.encodeToByteArray())
        for (relay in address.relays) tlv(1, relay.encodeToByteArray())
        tlv(2, hexToBytes(address.pubkey))
        tlv(
            3,
            byteArrayOf(
                ((address.kind shr 24) and 0xff).toByte(),
                ((address.kind shr 16) and 0xff).toByte(),
                ((address.kind shr 8) and 0xff).toByte(),
                (address.kind and 0xff).toByte(),
            ),
        )
        val total = parts.sumOf { it.size }
        val payload = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(payload, offset)
            offset += part.size
        }
        return encode("naddr", payload)
    }

    fun decodeNaddr(value: String): NAddr? {
        val decoded = decode(value) ?: return null
        if (decoded.hrp != "naddr") return null
        var index = 0
        var identifier: String? = null
        var pubkey: String? = null
        var kind: Int? = null
        val relays = mutableListOf<String>()
        val bytes = decoded.bytes
        while (index + 1 < bytes.size) {
            val type = bytes[index].toInt() and 0xff
            val length = bytes[index + 1].toInt() and 0xff
            index += 2
            if (index + length > bytes.size) return null
            val value2 = bytes.copyOfRange(index, index + length)
            index += length
            when (type) {
                0 -> identifier = value2.decodeToString()
                1 -> relays.add(value2.decodeToString())
                2 -> if (length == 32) pubkey = bytesToHex(value2) else return null
                3 -> if (length == 4) {
                    kind = ((value2[0].toInt() and 0xff) shl 24) or
                        ((value2[1].toInt() and 0xff) shl 16) or
                        ((value2[2].toInt() and 0xff) shl 8) or
                        (value2[3].toInt() and 0xff)
                } else {
                    return null
                }
                else -> Unit // Unknown TLV types are skipped, as NIP-19 requires.
            }
        }
        if (identifier == null || pubkey == null || kind == null) return null
        return NAddr(identifier, pubkey, kind, relays)
    }

    fun encodeNpub(pubkeyHex: String): String = encode("npub", hexToBytes(pubkeyHex))

    fun decodeNpub(value: String): String? {
        val decoded = decode(value) ?: return null
        if (decoded.hrp != "npub" || decoded.bytes.size != 32) return null
        return bytesToHex(decoded.bytes)
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "not a hex string" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val hex = value.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}
