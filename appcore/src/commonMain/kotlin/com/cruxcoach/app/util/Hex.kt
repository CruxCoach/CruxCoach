package com.cruxcoach.app.util

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

/** Strict: even length, hex digits only (either case). Null otherwise. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = hexValue(this[i * 2])
        val lo = hexValue(this[i * 2 + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** 64 lowercase hex characters: the only form Nostr ids and pubkeys take on the wire. */
fun String.isLowerHex64(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
