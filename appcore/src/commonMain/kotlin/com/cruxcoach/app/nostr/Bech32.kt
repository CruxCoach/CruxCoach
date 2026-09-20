package com.cruxcoach.app.nostr

/**
 * BIP-173 bech32 (not bech32m) with the length limit lifted, which is what
 * NIP-19 uses: an `naddr` carrying relays is routinely longer than 90 chars.
 *
 * Decoding is strict on purpose — these strings arrive from deep links and
 * pasted text. Uppercase and mixed case are rejected rather than normalised:
 * everything Nostr puts on the wire is lowercase, and accepting the other
 * forms would mean two spellings of the same identity.
 */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Bounds the work an untrusted string can cause; far above any real naddr. */
    const val MAX_LENGTH = 4096

    private fun polymod(values: IntArray): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor generator[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return out
    }

    /** [data] is 5-bit groups. Null when the input cannot form a valid bech32 string. */
    fun encode(hrp: String, data: ByteArray): String? {
        if (hrp.isEmpty() || hrp.any { it !in 'a'..'z' }) return null
        if (data.any { it < 0 || it > 31 }) return null
        if (hrp.length + 1 + data.size + 6 > MAX_LENGTH) return null
        val values = hrpExpand(hrp) + IntArray(data.size) { data[it].toInt() } + IntArray(6)
        val checksum = polymod(values) xor 1
        val out = StringBuilder(hrp).append('1')
        for (b in data) out.append(CHARSET[b.toInt()])
        for (i in 0..5) out.append(CHARSET[(checksum ushr (5 * (5 - i))) and 31])
        return out.toString()
    }

    class Decoded(val hrp: String, val data: ByteArray)

    /** Null on any deviation: bad case, bad charset, bad checksum, missing separator. */
    fun decode(text: String): Decoded? {
        if (text.length < 8 || text.length > MAX_LENGTH) return null
        for (c in text) if (c.code < 33 || c.code > 126 || c in 'A'..'Z') return null
        val separator = text.lastIndexOf('1')
        if (separator < 1 || separator + 7 > text.length) return null
        val hrp = text.substring(0, separator)
        if (hrp.any { it !in 'a'..'z' }) return null
        val data = ByteArray(text.length - separator - 1)
        for (i in data.indices) {
            val index = CHARSET.indexOf(text[separator + 1 + i])
            if (index < 0) return null
            data[i] = index.toByte()
        }
        val values = hrpExpand(hrp) + IntArray(data.size) { data[it].toInt() }
        if (polymod(values) != 1) return null
        return Decoded(hrp, data.copyOfRange(0, data.size - 6))
    }

    fun toBase5(bytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(bytes.size * 8 / 5 + 1)
        var accumulator = 0
        var bits = 0
        for (b in bytes) {
            accumulator = (accumulator shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add(((accumulator ushr bits) and 31).toByte())
            }
        }
        if (bits > 0) out.add(((accumulator shl (5 - bits)) and 31).toByte())
        return out.toByteArray()
    }

    /** Null on non-canonical padding (leftover bits set, or a whole wasted group). */
    fun fromBase5(data: ByteArray): ByteArray? {
        val out = ArrayList<Byte>(data.size * 5 / 8)
        var accumulator = 0
        var bits = 0
        for (b in data) {
            val value = b.toInt()
            if (value < 0 || value > 31) return null
            accumulator = (accumulator shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((accumulator ushr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5) return null
        if (((accumulator shl (8 - bits)) and 0xff) != 0) return null
        return out.toByteArray()
    }
}
