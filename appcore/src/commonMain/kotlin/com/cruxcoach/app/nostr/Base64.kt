package com.cruxcoach.app.nostr

/** Standard RFC 4648 base64 with padding — the encoding NIP-44 and Blossom auth use. */
internal object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val chunk = ((data[i].toInt() and 0xff) shl 16) or
                ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            out.append(ALPHABET[chunk ushr 18]).append(ALPHABET[(chunk ushr 12) and 63])
                .append(ALPHABET[(chunk ushr 6) and 63]).append(ALPHABET[chunk and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val chunk = (data[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[chunk ushr 18]).append(ALPHABET[(chunk ushr 12) and 63]).append("==")
            }
            2 -> {
                val chunk = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[chunk ushr 18]).append(ALPHABET[(chunk ushr 12) and 63])
                    .append(ALPHABET[(chunk ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** Strict: exact padding, no whitespace, no alternative alphabet. Null otherwise. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 != 0) return null
        var padding = 0
        while (padding < 2 && text.length > padding && text[text.length - 1 - padding] == '=') padding++
        val out = ByteArray(text.length / 4 * 3 - padding)
        var outIndex = 0
        var i = 0
        while (i < text.length) {
            var chunk = 0
            for (j in 0..3) {
                val c = text[i + j]
                val value = when {
                    c == '=' && i + 4 >= text.length && j >= 4 - padding -> 0
                    else -> ALPHABET.indexOf(c).also { if (it < 0) return null }
                }
                chunk = (chunk shl 6) or value
            }
            out[outIndex++] = (chunk ushr 16).toByte()
            if (outIndex < out.size) out[outIndex++] = ((chunk ushr 8) and 0xff).toByte()
            if (outIndex < out.size) out[outIndex++] = (chunk and 0xff).toByte()
            i += 4
        }
        return out
    }
}
