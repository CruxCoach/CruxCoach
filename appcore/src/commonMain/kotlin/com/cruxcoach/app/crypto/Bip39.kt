package com.cruxcoach.app.crypto

/**
 * BIP-39 mnemonics: word-list and checksum validation, and the seed.
 *
 * Validation is stricter than Android's library, which derives a seed from any
 * text with 12–24 words: a typo there silently opens an empty account. Here an
 * unknown word or a wrong checksum is refused before anything is derived.
 */
internal object Bip39 {
    /** The word counts BIP-39 defines (128–256 bits of entropy). */
    val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    enum class Problem { WORD_COUNT, UNKNOWN_WORD, CHECKSUM }

    /**
     * The canonical sentence (lowercase words joined by one space), or the
     * [Problem]. English only: every published Nostr mnemonic tool uses it.
     */
    fun normalize(input: String): Pair<String?, Problem?> {
        val words = input.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase() }
        if (words.size !in VALID_WORD_COUNTS) return null to Problem.WORD_COUNT
        val list = Bip39English.words
        val indices = IntArray(words.size)
        for ((i, word) in words.withIndex()) {
            val index = list.binarySearch(word)
            if (index < 0) return null to Problem.UNKNOWN_WORD
            indices[i] = index
        }
        val matches = checksumMatches(indices)
        indices.fill(0)
        if (!matches) return null to Problem.CHECKSUM
        return words.joinToString(" ") to null
    }

    /** PBKDF2-HMAC-SHA512("mnemonic" + passphrase, 2048 rounds, 64 bytes) over the sentence. */
    fun seed(sentence: String, passphrase: String = ""): ByteArray {
        val password = sentence.encodeToByteArray()
        val salt = ("mnemonic" + passphrase).encodeToByteArray()
        try {
            return Pbkdf2.hmacSha512(password, salt, 2048, 64)
        } finally {
            password.fill(0)
            salt.fill(0)
        }
    }

    private fun checksumMatches(indices: IntArray): Boolean {
        val totalBits = indices.size * 11
        val checksumBits = totalBits / 33
        val entropyBytes = (totalBits - checksumBits) / 8
        val bits = BooleanArray(totalBits)
        for ((i, index) in indices.withIndex()) {
            for (b in 0 until 11) bits[i * 11 + b] = ((index ushr (10 - b)) and 1) == 1
        }
        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBytes * 8) {
            if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        val hash = Sha256.digest(entropy)
        var matches = true
        for (i in 0 until checksumBits) {
            val expected = ((hash[i / 8].toInt() ushr (7 - i % 8)) and 1) == 1
            if (bits[entropyBytes * 8 + i] != expected) matches = false
        }
        entropy.fill(0)
        hash.fill(0)
        bits.fill(false)
        return matches
    }

    private val WHITESPACE = Regex("\\s+")
}
