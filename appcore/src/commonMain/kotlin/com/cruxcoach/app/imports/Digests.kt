package com.cruxcoach.app.imports

/**
 * SHA-1 and MD5, in portable Kotlin.
 *
 * Neither is used as a security primitive here: they only reproduce the two
 * name-based UUID schemes the Android importers already wrote into user
 * databases — UUIDv5 (SHA-1) for MoonBoard catalogue ids and UUIDv3 (MD5, via
 * `java.util.UUID.nameUUIDFromBytes`) for Aurora draft climbs. Changing either
 * would orphan every row an Android device already stored, so they are ported
 * rather than replaced. Payload hashing uses SHA-256 from the platform.
 */
internal object Sha1 {
    fun digest(data: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = -0x10325477 // 0xEFCDAB89
        var h2 = -0x67452302 // 0x98BADCFE
        var h3 = 0x10325476
        var h4 = -0x3C2D1E10 // 0xC3D2E1F0

        val block = IntArray(80)
        val padded = padBigEndian(data)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                block[i] = ((padded[p].toInt() and 0xff) shl 24) or
                    ((padded[p + 1].toInt() and 0xff) shl 16) or
                    ((padded[p + 2].toInt() and 0xff) shl 8) or
                    (padded[p + 3].toInt() and 0xff)
            }
            for (i in 16 until 80) {
                block[i] = (block[i - 3] xor block[i - 8] xor block[i - 14] xor block[i - 16]).rotl(1)
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = -0x70E44324 } // 0x8F1BBCDC
                    else -> { f = b xor c xor d; k = -0x359D3E2A } // 0xCA62C1D6
                }
                val temp = a.rotl(5) + f + e + k + block[i]
                e = d; d = c; c = b.rotl(30); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            offset += 64
        }
        return intsToBigEndianBytes(intArrayOf(h0, h1, h2, h3, h4))
    }
}

internal object Md5 {
    private val SHIFTS = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** floor(abs(sin(i + 1)) * 2^32), the constant table from RFC 1321. */
    private val K = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x0944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f,
    )

    fun digest(data: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = -0x10325477
        var c0 = -0x67452302
        var d0 = 0x10325476

        val padded = padLittleEndian(data)
        val block = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                block[i] = (padded[p].toInt() and 0xff) or
                    ((padded[p + 1].toInt() and 0xff) shl 8) or
                    ((padded[p + 2].toInt() and 0xff) shl 16) or
                    ((padded[p + 3].toInt() and 0xff) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val temp = d
                d = c
                c = b
                b += (a + f + K[i] + block[g]).rotl(SHIFTS[i])
                a = temp
            }
            a0 += a; b0 += b; c0 += c; d0 += d
            offset += 64
        }
        return intsToLittleEndianBytes(intArrayOf(a0, b0, c0, d0))
    }
}

/** Merkle-Damgård padding with a big-endian 64-bit bit length (SHA-1). */
private fun padBigEndian(data: ByteArray): ByteArray {
    val bitLength = data.size.toLong() * 8
    val padLength = ((55 - data.size) % 64 + 64) % 64 + 1
    val out = ByteArray(data.size + padLength + 8)
    data.copyInto(out)
    out[data.size] = 0x80.toByte()
    for (i in 0 until 8) {
        out[out.size - 1 - i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
    }
    return out
}

/** Same padding with a little-endian bit length (MD5). */
private fun padLittleEndian(data: ByteArray): ByteArray {
    val bitLength = data.size.toLong() * 8
    val padLength = ((55 - data.size) % 64 + 64) % 64 + 1
    val out = ByteArray(data.size + padLength + 8)
    data.copyInto(out)
    out[data.size] = 0x80.toByte()
    for (i in 0 until 8) {
        out[data.size + padLength + i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
    }
    return out
}

private fun intsToBigEndianBytes(words: IntArray): ByteArray {
    val out = ByteArray(words.size * 4)
    words.forEachIndexed { index, word ->
        out[index * 4] = (word ushr 24).toByte()
        out[index * 4 + 1] = (word ushr 16).toByte()
        out[index * 4 + 2] = (word ushr 8).toByte()
        out[index * 4 + 3] = word.toByte()
    }
    return out
}

private fun intsToLittleEndianBytes(words: IntArray): ByteArray {
    val out = ByteArray(words.size * 4)
    words.forEachIndexed { index, word ->
        out[index * 4] = word.toByte()
        out[index * 4 + 1] = (word ushr 8).toByte()
        out[index * 4 + 2] = (word ushr 16).toByte()
        out[index * 4 + 3] = (word ushr 24).toByte()
    }
    return out
}

private fun Int.rotl(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))
