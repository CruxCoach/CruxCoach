package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold

/**
 * Canonical hash of a climb's hold layout for duplicate detection.
 *
 * Algorithm (FEAT-003 §4.3):
 *   1. Parse `frames` into (placementId, roleId) pairs
 *   2. Sort by placementId ascending
 *   3. Concatenate as `p{pid}r{role}…`
 *   4. Prefix with `layout:{layoutId}:` and SHA-256
 *
 * **layoutId IS in the hash** — same placement_ids on different layouts
 * are physically different climbs.
 *
 * **angle is NOT in the hash** — same holds at different angles is the
 * same climb (mirrors Aurora's climb + climb_stat split).
 */
object FramesHash {
    fun of(frames: String, layoutId: Long): String =
        sha256Hex(framesHashInput(frames, layoutId).encodeToByteArray())
}

/** Build the canonical pre-hash input. Pure function, shared across platforms. */
internal fun framesHashInput(frames: String, layoutId: Long): String {
    val holds = BoardClimbParser.parseFrames(frames)
        .sortedBy { it.placementId }
        .joinToString("") { h: BoardHold -> "p${h.placementId}r${h.roleId}" }
    return "layout:$layoutId:$holds"
}

/** Dependency-free SHA-256 so the canonical community identifier is portable. */
internal fun sha256Hex(input: ByteArray): String {
    val constants = intArrayOf(
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
    val state = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLength = input.size.toLong() * 8L
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    val words = IntArray(64)
    for (offset in padded.indices step 64) {
        for (index in 0 until 16) {
            val byteOffset = offset + index * 4
            words[index] =
                ((padded[byteOffset].toInt() and 0xff) shl 24) or
                    ((padded[byteOffset + 1].toInt() and 0xff) shl 16) or
                    ((padded[byteOffset + 2].toInt() and 0xff) shl 8) or
                    (padded[byteOffset + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val s0 = words[index - 15].rotateRight(7) xor
                words[index - 15].rotateRight(18) xor (words[index - 15] ushr 3)
            val s1 = words[index - 2].rotateRight(17) xor
                words[index - 2].rotateRight(19) xor (words[index - 2] ushr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0 until 64) {
            val sigma1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = h + sigma1 + choose + constants[index] + words[index]
            val sigma0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = sigma0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
    return state.joinToString("") { word ->
        word.toUInt().toString(16).padStart(8, '0')
    }
}
