package com.cruxcoach.app.crypto

/** PBKDF2 (RFC 8018), used for BIP-39 seeds (HMAC-SHA512) and inside scrypt (HMAC-SHA256). */
internal object Pbkdf2 {
    fun derive(hmac: Hmac, hashSize: Int, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        val out = ByteArray(length)
        val blocks = (length + hashSize - 1) / hashSize
        val index = ByteArray(4)
        for (block in 1..blocks) {
            index[0] = (block ushr 24).toByte()
            index[1] = (block ushr 16).toByte()
            index[2] = (block ushr 8).toByte()
            index[3] = block.toByte()
            var u = hmac.mac(password, salt, index)
            val t = u.copyOf()
            for (i in 2..iterations) {
                val next = hmac.mac(password, u)
                u.fill(0)
                u = next
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val offset = (block - 1) * hashSize
            t.copyInto(out, offset, 0, minOf(hashSize, length - offset))
            u.fill(0)
            t.fill(0)
        }
        return out
    }

    fun hmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        derive(Hmac.SHA512, Sha512.DIGEST_SIZE, password, salt, iterations, length)

    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        derive(Hmac.SHA256, Sha256.DIGEST_SIZE, password, salt, iterations, length)
}

/**
 * scrypt (RFC 7914) with Salsa20/8.
 *
 * Memory is `128 * r * N` bytes and is allocated in one piece, so callers must
 * bound `N` before calling: an attacker-chosen ncryptsec names its own cost.
 */
internal object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, logN: Int, r: Int, p: Int, length: Int): ByteArray {
        require(logN in 1..30 && r >= 1 && p >= 1 && length >= 1)
        val n = 1 shl logN
        val words = 32 * r
        require(n.toLong() * words <= Int.MAX_VALUE)
        val b = Pbkdf2.hmacSha256(password, salt, 1, p * 128 * r)
        val x = IntArray(words)
        val y = IntArray(words)
        val v = IntArray(n * words)
        val t = IntArray(16)
        try {
            for (chunk in 0 until p) {
                val base = chunk * 128 * r
                for (i in 0 until words) x[i] = readLittleEndian(b, base + i * 4)
                roMix(x, y, v, t, r, n)
                for (i in 0 until words) writeLittleEndian(b, base + i * 4, x[i])
            }
            return Pbkdf2.hmacSha256(password, b, 1, length)
        } finally {
            b.fill(0)
            x.fill(0)
            y.fill(0)
            v.fill(0)
            t.fill(0)
        }
    }

    private fun roMix(x: IntArray, y: IntArray, v: IntArray, t: IntArray, r: Int, n: Int) {
        val words = 32 * r
        for (i in 0 until n) {
            x.copyInto(v, i * words)
            blockMix(x, y, t, r)
            y.copyInto(x)
        }
        val mask = n - 1
        for (i in 0 until n) {
            // Integerify: the first word of the last 64-byte block, little endian; N <= 2^30.
            val j = x[(2 * r - 1) * 16] and mask
            val offset = j * words
            for (k in 0 until words) x[k] = x[k] xor v[offset + k]
            blockMix(x, y, t, r)
            y.copyInto(x)
        }
    }

    /** BlockMix(B) into [out]: even sub-blocks first, then odd ones. */
    private fun blockMix(input: IntArray, out: IntArray, t: IntArray, r: Int) {
        input.copyInto(t, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0..15) t[k] = t[k] xor input[i * 16 + k]
            salsa208(t)
            t.copyInto(out, ((i shr 1) + (i and 1) * r) * 16)
        }
    }

    private fun salsa208(b: IntArray) {
        var x0 = b[0]; var x1 = b[1]; var x2 = b[2]; var x3 = b[3]
        var x4 = b[4]; var x5 = b[5]; var x6 = b[6]; var x7 = b[7]
        var x8 = b[8]; var x9 = b[9]; var x10 = b[10]; var x11 = b[11]
        var x12 = b[12]; var x13 = b[13]; var x14 = b[14]; var x15 = b[15]
        var round = 0
        while (round < 8) {
            x4 = x4 xor (x0 + x12).rotl(7); x8 = x8 xor (x4 + x0).rotl(9)
            x12 = x12 xor (x8 + x4).rotl(13); x0 = x0 xor (x12 + x8).rotl(18)
            x9 = x9 xor (x5 + x1).rotl(7); x13 = x13 xor (x9 + x5).rotl(9)
            x1 = x1 xor (x13 + x9).rotl(13); x5 = x5 xor (x1 + x13).rotl(18)
            x14 = x14 xor (x10 + x6).rotl(7); x2 = x2 xor (x14 + x10).rotl(9)
            x6 = x6 xor (x2 + x14).rotl(13); x10 = x10 xor (x6 + x2).rotl(18)
            x3 = x3 xor (x15 + x11).rotl(7); x7 = x7 xor (x3 + x15).rotl(9)
            x11 = x11 xor (x7 + x3).rotl(13); x15 = x15 xor (x11 + x7).rotl(18)
            x1 = x1 xor (x0 + x3).rotl(7); x2 = x2 xor (x1 + x0).rotl(9)
            x3 = x3 xor (x2 + x1).rotl(13); x0 = x0 xor (x3 + x2).rotl(18)
            x6 = x6 xor (x5 + x4).rotl(7); x7 = x7 xor (x6 + x5).rotl(9)
            x4 = x4 xor (x7 + x6).rotl(13); x5 = x5 xor (x4 + x7).rotl(18)
            x11 = x11 xor (x10 + x9).rotl(7); x8 = x8 xor (x11 + x10).rotl(9)
            x9 = x9 xor (x8 + x11).rotl(13); x10 = x10 xor (x9 + x8).rotl(18)
            x12 = x12 xor (x15 + x14).rotl(7); x13 = x13 xor (x12 + x15).rotl(9)
            x14 = x14 xor (x13 + x12).rotl(13); x15 = x15 xor (x14 + x13).rotl(18)
            round += 2
        }
        b[0] += x0; b[1] += x1; b[2] += x2; b[3] += x3
        b[4] += x4; b[5] += x5; b[6] += x6; b[7] += x7
        b[8] += x8; b[9] += x9; b[10] += x10; b[11] += x11
        b[12] += x12; b[13] += x13; b[14] += x14; b[15] += x15
    }

    private fun Int.rotl(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

    private fun readLittleEndian(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLittleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }
}
