package com.cruxcoach.domain.sharing

/**
 * BIP-173 bech32 decoding, which is the encoding NIP-19 uses for `npub1…`.
 *
 * Written here rather than delegated to the Nostr library already on the
 * classpath for one reason: that library compiles its NIP-19 parser to a newer
 * bytecode level than the unit-test JVM runs, so using it would leave the whole
 * npub path untestable. An untested identity parser is exactly what let a peer
 * identity into the ledger that no signature could ever verify against, so it
 * is worth the sixty lines to have one that tests can actually execute.
 *
 * Decode only — nothing here needs to produce bech32 — and bech32, not
 * bech32m: NIP-19 predates the bech32m variant and does not use it.
 */
object Bech32 {

    data class Decoded(val hrp: String, val data: ByteArray) {
        // ByteArray in a data class needs these to compare by content.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Decoded && hrp == other.hrp && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * hrp.hashCode() + data.contentHashCode()
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val CHECKSUM_LENGTH = 6
    private const val MAX_LENGTH = 90

    /** `null` for anything that is not a well-formed, checksummed bech32 string. */
    @Suppress("ReturnCount")
    fun decode(input: String): Decoded? {
        if (input.isEmpty() || input.length > MAX_LENGTH) return null
        if (input.any { it.code < 33 || it.code > 126 }) return null

        // The specification forbids mixing cases, because the checksum is
        // computed over one canonical case.
        val hasLower = input.any { it in 'a'..'z' }
        val hasUpper = input.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null

        val lower = input.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + CHECKSUM_LENGTH + 1 > lower.length) return null

        val hrp = lower.substring(0, separator)
        val dataPart = lower.substring(separator + 1)

        val values = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val index = CHARSET.indexOf(dataPart[i])
            if (index < 0) return null
            values[i] = index
        }

        if (polymod(hrpExpand(hrp) + values.toList()) != 1) return null

        val payload = convertBits(values.dropLast(CHECKSUM_LENGTH), from = 5, to = 8) ?: return null
        return Decoded(hrp, payload)
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in values) {
            val top = checksum shr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) checksum = checksum xor generator[i]
            }
        }
        return checksum
    }

    /**
     * Regroups 5-bit values into 8-bit bytes. Rejects a leftover that carries
     * information or a non-zero pad, so a truncated payload cannot decode to a
     * shorter key that happens to look plausible.
     */
    private fun convertBits(values: List<Int>, from: Int, to: Int): ByteArray? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        val maxValue = (1 shl to) - 1
        for (value in values) {
            if (value < 0 || (value shr from) != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += ((acc shr bits) and maxValue).toByte()
            }
        }
        if (bits >= from) return null
        if ((acc shl (to - bits)) and maxValue != 0) return null
        return out.toByteArray()
    }
}
