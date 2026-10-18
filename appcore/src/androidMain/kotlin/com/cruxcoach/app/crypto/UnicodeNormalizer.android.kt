package com.cruxcoach.app.crypto

internal actual object UnicodeNormalizer {
    actual fun nfkc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
}
