package com.cruxcoach.app.nostr

/**
 * ChaCha20 (RFC 8439, IETF profile: 96-bit nonce, 32-bit block counter).
 *
 * NIP-44 v2 needs the RAW stream cipher, not an AEAD, and neither CryptoKit
 * nor the JVM exposes that, so it is implemented here. The cipher is pure
 * ARX — add, xor, rotate on fixed-size words — so it is constant time by
 * construction: there is no table lookup and no branch that depends on key,
 * nonce or plaintext. Only the message length steers control flow.
 */
internal object ChaCha20 {
    private const val ROUNDS = 20
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    /** XORs the keystream into a copy of [data]. Null when key/nonce sizes are wrong. */
    fun xor(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12) return null
        val out = data.copyOf()
        val state = IntArray(16)
        state[0] = SIGMA[0]; state[1] = SIGMA[1]; state[2] = SIGMA[2]; state[3] = SIGMA[3]
        for (i in 0..7) state[4 + i] = littleEndianInt(key, i * 4)
        for (i in 0..2) state[13 + i] = littleEndianInt(nonce, i * 4)
        val working = IntArray(16)
        val block = ByteArray(64)
        var offset = 0
        var blockCounter = counter
        while (offset < out.size) {
            state[12] = blockCounter
            core(state, working)
            for (i in 0..15) writeLittleEndian(block, i * 4, working[i] + state[i])
            val take = minOf(64, out.size - offset)
            for (i in 0 until take) out[offset + i] = (out[offset + i].toInt() xor block[i].toInt()).toByte()
            offset += take
            blockCounter++
        }
        block.fill(0)
        working.fill(0)
        state.fill(0)
        return out
    }

    private fun core(state: IntArray, working: IntArray) {
        state.copyInto(working)
        var round = 0
        while (round < ROUNDS) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
            round += 2
        }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

    private fun littleEndianInt(source: ByteArray, offset: Int): Int =
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
