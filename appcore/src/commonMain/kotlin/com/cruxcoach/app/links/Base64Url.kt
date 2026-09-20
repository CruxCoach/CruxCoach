package com.cruxcoach.app.links

/**
 * The base64url alphabet of `java.util.Base64.getUrlEncoder()/getUrlDecoder()`,
 * so share links stay byte-identical to the ones Android produces and accepts.
 * Encoding omits padding; decoding tolerates it but nothing else.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            }
            2 -> {
                val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
                out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }

    /** Null on any character outside the alphabet, on misplaced padding or on a dangling 6-bit tail. */
    fun decode(text: String): ByteArray? {
        var end = text.length
        // Padding is optional on the wire; at most two '=' may close the last quantum.
        var padding = 0
        while (end > 0 && text[end - 1] == '=' && padding < 2) {
            end--
            padding++
        }
        if (end > 0 && text[end - 1] == '=') return null
        if (padding > 0 && (end + padding) % 4 != 0) return null
        if (end % 4 == 1) return null

        val out = ByteArray(end * 3 / 4)
        var accumulator = 0
        var bits = 0
        var written = 0
        for (index in 0 until end) {
            val value = ALPHABET.indexOf(text[index])
            if (value < 0) return null
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((accumulator ushr bits) and 0xFF).toByte()
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }
}
