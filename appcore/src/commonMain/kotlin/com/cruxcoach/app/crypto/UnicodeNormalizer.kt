package com.cruxcoach.app.crypto

/**
 * NFKC, which NIP-49 requires for the password so that the same password
 * typed on another device (decomposed accents, compatibility characters)
 * derives the same key. Kotlin's common library has no normalizer.
 */
internal expect object UnicodeNormalizer {
    fun nfkc(text: String): String
}
