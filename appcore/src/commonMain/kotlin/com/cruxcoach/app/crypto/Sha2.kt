package com.cruxcoach.app.crypto

/**
 * SHA-256 and SHA-512 (FIPS 180-4) and HMAC (RFC 2104) in portable Kotlin.
 *
 * The platform [com.cruxcoach.app.platform.Hashing] offers SHA-256 only, and the
 * key importers (BIP-39 seeds, BIP-32 derivation, scrypt) run as pure functions
 * on a worker thread, so they carry their own. These are only used on secrets
 * the user typed in (a password, recovery words): no lookup depends on the
 * input, and every intermediate buffer is zeroed before it is dropped.
 */
internal object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    private val IV = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    const val BLOCK_SIZE = 64
    const val DIGEST_SIZE = 32

    fun digest(vararg parts: ByteArray): ByteArray {
        val h = IV.copyOf()
        val w = IntArray(64)
        val total = parts.sumOf { it.size.toLong() }
        val buffer = ByteArray(BLOCK_SIZE)
        var filled = 0
        for (part in parts) {
            for (b in part) {
                buffer[filled++] = b
                if (filled == BLOCK_SIZE) {
                    compress(h, w, buffer)
                    filled = 0
                }
            }
        }
        buffer[filled++] = 0x80.toByte()
        if (filled > BLOCK_SIZE - 8) {
            buffer.fill(0, filled)
            compress(h, w, buffer)
            filled = 0
        }
        buffer.fill(0, filled)
        val bits = total * 8
        for (i in 0..7) buffer[BLOCK_SIZE - 1 - i] = (bits ushr (8 * i)).toByte()
        compress(h, w, buffer)
        val out = ByteArray(DIGEST_SIZE)
        for (i in 0..7) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        h.fill(0)
        w.fill(0)
        buffer.fill(0)
        return out
    }

    private fun compress(h: IntArray, w: IntArray, block: ByteArray) {
        for (i in 0..15) {
            w[i] = ((block[i * 4].toInt() and 0xff) shl 24) or
                ((block[i * 4 + 1].toInt() and 0xff) shl 16) or
                ((block[i * 4 + 2].toInt() and 0xff) shl 8) or
                (block[i * 4 + 3].toInt() and 0xff)
        }
        for (i in 16..63) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}

internal object Sha512 {
    private val K = longArrayOf(
        0x428a2f98d728ae22L, 0x7137449123ef65cdL,
        0xb5c0fbcfec4d3b2fuL.toLong(), 0xe9b5dba58189dbbcuL.toLong(),
        0x3956c25bf348b538L, 0x59f111f1b605d019L,
        0x923f82a4af194f9buL.toLong(), 0xab1c5ed5da6d8118uL.toLong(),
        0xd807aa98a3030242uL.toLong(), 0x12835b0145706fbeL,
        0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
        0x72be5d74f27b896fL, 0x80deb1fe3b1696b1uL.toLong(),
        0x9bdc06a725c71235uL.toLong(), 0xc19bf174cf692694uL.toLong(),
        0xe49b69c19ef14ad2uL.toLong(), 0xefbe4786384f25e3uL.toLong(),
        0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
        0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L,
        0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
        0x983e5152ee66dfabuL.toLong(), 0xa831c66d2db43210uL.toLong(),
        0xb00327c898fb213fuL.toLong(), 0xbf597fc7beef0ee4uL.toLong(),
        0xc6e00bf33da88fc2uL.toLong(), 0xd5a79147930aa725uL.toLong(),
        0x06ca6351e003826fL, 0x142929670a0e6e70L,
        0x27b70a8546d22ffcL, 0x2e1b21385c26c926L,
        0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
        0x650a73548baf63deL, 0x766a0abb3c77b2a8L,
        0x81c2c92e47edaee6uL.toLong(), 0x92722c851482353buL.toLong(),
        0xa2bfe8a14cf10364uL.toLong(), 0xa81a664bbc423001uL.toLong(),
        0xc24b8b70d0f89791uL.toLong(), 0xc76c51a30654be30uL.toLong(),
        0xd192e819d6ef5218uL.toLong(), 0xd69906245565a910uL.toLong(),
        0xf40e35855771202auL.toLong(), 0x106aa07032bbd1b8L,
        0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L,
        0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
        0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL,
        0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
        0x748f82ee5defb2fcL, 0x78a5636f43172f60L,
        0x84c87814a1f0ab72uL.toLong(), 0x8cc702081a6439ecuL.toLong(),
        0x90befffa23631e28uL.toLong(), 0xa4506cebde82bde9uL.toLong(),
        0xbef9a3f7b2c67915uL.toLong(), 0xc67178f2e372532buL.toLong(),
        0xca273eceea26619cuL.toLong(), 0xd186b8c721c0c207uL.toLong(),
        0xeada7dd6cde0eb1euL.toLong(), 0xf57d4f7fee6ed178uL.toLong(),
        0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L,
        0x113f9804bef90daeL, 0x1b710b35131c471bL,
        0x28db77f523047d84L, 0x32caab7b40c72493L,
        0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
        0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL,
        0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L,
    )
    private val IV = longArrayOf(
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73buL.toLong(),
        0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1uL.toLong(),
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fuL.toLong(),
        0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    )
    const val BLOCK_SIZE = 128
    const val DIGEST_SIZE = 64

    fun digest(vararg parts: ByteArray): ByteArray {
        val h = IV.copyOf()
        val w = LongArray(80)
        val total = parts.sumOf { it.size.toLong() }
        val buffer = ByteArray(BLOCK_SIZE)
        var filled = 0
        for (part in parts) {
            for (b in part) {
                buffer[filled++] = b
                if (filled == BLOCK_SIZE) {
                    compress(h, w, buffer)
                    filled = 0
                }
            }
        }
        buffer[filled++] = 0x80.toByte()
        if (filled > BLOCK_SIZE - 16) {
            buffer.fill(0, filled)
            compress(h, w, buffer)
            filled = 0
        }
        buffer.fill(0, filled)
        // 128-bit length; inputs here never exceed 2^61 bytes, so the high half is zero.
        val bits = total * 8
        for (i in 0..7) buffer[BLOCK_SIZE - 1 - i] = (bits ushr (8 * i)).toByte()
        compress(h, w, buffer)
        val out = ByteArray(DIGEST_SIZE)
        for (i in 0..7) for (j in 0..7) out[i * 8 + j] = (h[i] ushr (56 - 8 * j)).toByte()
        h.fill(0)
        w.fill(0)
        buffer.fill(0)
        return out
    }

    private fun compress(h: LongArray, w: LongArray, block: ByteArray) {
        for (i in 0..15) {
            var v = 0L
            for (j in 0..7) v = (v shl 8) or (block[i * 8 + j].toLong() and 0xff)
            w[i] = v
        }
        for (i in 16..79) {
            val s0 = w[i - 15].rotateRight(1) xor w[i - 15].rotateRight(8) xor (w[i - 15] ushr 7)
            val s1 = w[i - 2].rotateRight(19) xor w[i - 2].rotateRight(61) xor (w[i - 2] ushr 6)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0..79) {
            val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private fun Long.rotateRight(bits: Int): Long = (this ushr bits) or (this shl (64 - bits))
}

/** HMAC (RFC 2104) over [Sha256] or [Sha512]. */
internal class Hmac private constructor(
    private val blockSize: Int,
    private val hash: (Array<out ByteArray>) -> ByteArray,
) {
    fun mac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val k = ByteArray(blockSize)
        if (key.size > blockSize) {
            val hashed = hash(arrayOf(key))
            hashed.copyInto(k)
            hashed.fill(0)
        } else {
            key.copyInto(k)
        }
        val ipad = ByteArray(blockSize) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { (k[it].toInt() xor 0x5c).toByte() }
        val inner = hash(arrayOf(ipad, *data))
        val out = hash(arrayOf(opad, inner))
        k.fill(0)
        ipad.fill(0)
        opad.fill(0)
        inner.fill(0)
        return out
    }

    companion object {
        val SHA256 = Hmac(Sha256.BLOCK_SIZE) { Sha256.digest(*it) }
        val SHA512 = Hmac(Sha512.BLOCK_SIZE) { Sha512.digest(*it) }
    }
}
