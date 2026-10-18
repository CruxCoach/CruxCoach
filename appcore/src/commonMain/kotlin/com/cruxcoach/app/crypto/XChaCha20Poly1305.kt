package com.cruxcoach.app.crypto

import com.cruxcoach.app.nostr.ChaCha20

/**
 * AEAD_XCHACHA20_POLY1305 (draft-irtf-cfrg-xchacha-03): HChaCha20 derives a
 * subkey from the first 16 nonce bytes, then the RFC 8439 ChaCha20-Poly1305
 * construction runs with nonce `0x00000000 || nonce[16..24]`.
 *
 * NIP-49 needs exactly this. CryptoKit only offers the 12-byte-nonce variant
 * and the platform contract does not expose it, so the AEAD is assembled here
 * from the ChaCha20 the NIP-44 code already uses plus a Poly1305 below.
 */
internal object XChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    /** `ciphertext || tag`, or null for wrong key/nonce sizes. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray? {
        val (subkey, nonce12) = subkey(key, nonce) ?: return null
        try {
            val ciphertext = ChaCha20.xor(subkey, nonce12, 1, plaintext) ?: return null
            val tag = tag(subkey, nonce12, aad, ciphertext) ?: return null
            return ciphertext + tag
        } finally {
            subkey.fill(0)
        }
    }

    /** The plaintext, or null when the tag does not authenticate (wrong key, tampered data). */
    fun open(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray? {
        if (ciphertextAndTag.size < TAG_SIZE) return null
        val (subkey, nonce12) = subkey(key, nonce) ?: return null
        try {
            val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - TAG_SIZE)
            val expected = tag(subkey, nonce12, aad, ciphertext) ?: return null
            var diff = 0
            for (i in 0 until TAG_SIZE) {
                diff = diff or (expected[i].toInt() xor ciphertextAndTag[ciphertext.size + i].toInt())
            }
            if (diff != 0) return null
            return ChaCha20.xor(subkey, nonce12, 1, ciphertext)
        } finally {
            subkey.fill(0)
        }
    }

    private fun subkey(key: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray>? {
        if (key.size != KEY_SIZE || nonce.size != NONCE_SIZE) return null
        val subkey = HChaCha20.derive(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(12)
        nonce.copyInto(nonce12, 4, 16, 24)
        return subkey to nonce12
    }

    private fun tag(key: ByteArray, nonce12: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val block0 = ChaCha20.xor(key, nonce12, 0, ByteArray(64)) ?: return null
        val polyKey = block0.copyOfRange(0, 32)
        block0.fill(0)
        val macData = ByteArray(padded(aad.size) + padded(ciphertext.size) + 16)
        aad.copyInto(macData, 0)
        ciphertext.copyInto(macData, padded(aad.size))
        val lengths = padded(aad.size) + padded(ciphertext.size)
        writeLong(macData, lengths, aad.size.toLong())
        writeLong(macData, lengths + 8, ciphertext.size.toLong())
        val tag = Poly1305.mac(polyKey, macData)
        polyKey.fill(0)
        return tag
    }

    private fun padded(length: Int): Int = (length + 15) / 16 * 16

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) target[offset + i] = (value ushr (8 * i)).toByte()
    }
}

/** HChaCha20 (draft-irtf-cfrg-xchacha-03 §2.2): 20 ChaCha rounds, no feed-forward, rows 0 and 3 out. */
internal object HChaCha20 {
    fun derive(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        val x = IntArray(16)
        x[0] = 0x61707865; x[1] = 0x3320646e; x[2] = 0x79622d32; x[3] = 0x6b206574
        for (i in 0..7) x[4 + i] = readLittleEndian(key, i * 4)
        for (i in 0..3) x[12 + i] = readLittleEndian(nonce16, i * 4)
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0..3) writeLittleEndian(out, i * 4, x[i])
        for (i in 0..3) writeLittleEndian(out, 16 + i * 4, x[12 + i])
        x.fill(0)
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotl(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotl(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotl(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotl(7)
    }

    private fun Int.rotl(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))
}

/**
 * Poly1305 (RFC 8439 §2.5) with 26-bit limbs (the poly1305-donna layout):
 * every product fits a signed 64-bit Long and the final reduction is a
 * mask select, so no branch depends on the key or the message.
 */
internal object Poly1305 {
    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32)
        val r0 = readLittleEndianU(key, 0) and 0x3ffffff
        val r1 = (readLittleEndianU(key, 3) ushr 2) and 0x3ffff03
        val r2 = (readLittleEndianU(key, 6) ushr 4) and 0x3ffc0ff
        val r3 = (readLittleEndianU(key, 9) ushr 6) and 0x3f03fff
        val r4 = (readLittleEndianU(key, 12) ushr 8) and 0x00fffff
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val block = ByteArray(17)
        var offset = 0
        while (offset < message.size) {
            val take = minOf(16, message.size - offset)
            block.fill(0)
            message.copyInto(block, 0, offset, offset + take)
            // The "1" bit above the block; for a full block it is bit 128 (hibit 1 shl 24 in limb 4).
            block[take] = 1
            h0 += readLittleEndianU(block, 0) and 0x3ffffff
            h1 += (readLittleEndianU(block, 3) ushr 2) and 0x3ffffff
            h2 += (readLittleEndianU(block, 6) ushr 4) and 0x3ffffff
            h3 += (readLittleEndianU(block, 9) ushr 6) and 0x3ffffff
            h4 += (readLittleEndianU(block, 12) ushr 8) or ((block[16].toLong() and 0xff) shl 24)

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and 0x3ffffff
            d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
            d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
            d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
            d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
            h1 += c
            offset += take
        }
        block.fill(0)

        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c

        // g = h + 5 - 2^130; keep g when it is non-negative (h >= p).
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        val selectG = (g4 ushr 63) - 1 // all ones when g4 >= 0
        val selectH = selectG.inv()
        h0 = (h0 and selectH) or (g0 and selectG)
        h1 = (h1 and selectH) or (g1 and selectG)
        h2 = (h2 and selectH) or (g2 and selectG)
        h3 = (h3 and selectH) or (g3 and selectG)
        h4 = (h4 and selectH) or (g4 and selectG)

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        val out = ByteArray(16)
        var f = w0 + readLittleEndianU(key, 16)
        writeLittleEndian(out, 0, f.toInt())
        f = w1 + readLittleEndianU(key, 20) + (f ushr 32)
        writeLittleEndian(out, 4, f.toInt())
        f = w2 + readLittleEndianU(key, 24) + (f ushr 32)
        writeLittleEndian(out, 8, f.toInt())
        f = w3 + readLittleEndianU(key, 28) + (f ushr 32)
        writeLittleEndian(out, 12, f.toInt())
        return out
    }

    private fun readLittleEndianU(source: ByteArray, offset: Int): Long =
        readLittleEndian(source, offset).toLong() and 0xffffffffL
}

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
