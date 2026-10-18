package com.cruxcoach.app.crypto

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

internal actual object UnicodeNormalizer {
    /** Foundation's NFKC (`precomposedStringWithCompatibilityMapping`). */
    @Suppress("CAST_NEVER_SUCCEEDS")
    actual fun nfkc(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
}
