package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §8: the second factor a sharing backup needs.
 *
 * Control of the Nostr signer alone must not be enough to restore a sharing
 * backup, so a restore also requires this code. It is shown once, meant to be
 * written down, and is never derived from the key — a stolen phone with an
 * unlocked signer still cannot bring a backup back on its own.
 *
 * The alphabet leaves out characters that are misread or mistyped when a code
 * is copied off paper (`O`/`0`, `I`/`1`/`L`, `U`/`V`), and a checksum group
 * catches the single-character slip that survives that.
 */
object SharingRecoveryCode {

    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    private const val GROUPS = 6
    private const val GROUP_LEN = 4
    private const val PAYLOAD_LEN = (GROUPS - 1) * GROUP_LEN

    /** Formats [entropy] as a grouped code with a trailing checksum group. */
    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.isNotEmpty()) { "recovery code needs entropy" }
        val payload = buildString {
            for (i in 0 until PAYLOAD_LEN) {
                val b = entropy[i % entropy.size].toInt() and 0xff
                append(ALPHABET[(b + i * 31) % ALPHABET.length])
            }
        }
        return (payload + checksum(payload)).chunked(GROUP_LEN).joinToString("-")
    }

    /**
     * Accepts the code as written down — lower case, spaces instead of dashes,
     * stray separators. What it does not accept is a wrong code.
     */
    fun isValid(code: String): Boolean {
        val normalised = normalise(code)
        if (normalised.length != GROUPS * GROUP_LEN) return false
        if (normalised.any { it !in ALPHABET }) return false
        val payload = normalised.take(PAYLOAD_LEN)
        return normalised.drop(PAYLOAD_LEN) == checksum(payload)
    }

    fun normalise(code: String): String =
        code.uppercase().filter { it in ALPHABET }

    private fun checksum(payload: String): String {
        // Position-weighted so a transposition changes the result too.
        var acc = 0
        payload.forEachIndexed { index, c -> acc = (acc * 31 + ALPHABET.indexOf(c) * (index + 1) + 7) % 1_000_003 }
        return buildString {
            var v = acc
            repeat(GROUP_LEN) {
                append(ALPHABET[v % ALPHABET.length])
                v /= ALPHABET.length
            }
        }
    }
}
