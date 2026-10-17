package com.cruxcoach.util

private val NUMBER_CATEGORIES = setOf(
    CharCategory.DECIMAL_DIGIT_NUMBER,
    CharCategory.LETTER_NUMBER,
    CharCategory.OTHER_NUMBER,
)

// Same classes as the JVM regex for every BMP character (\p{L} = isLetter, \p{N} = Nd/Nl/No).
actual fun replaceNonWordRuns(text: String, alsoKeep: String, replacement: String): String {
    val out = StringBuilder(text.length)
    var inRun = false
    for (c in text) {
        if (c.isLetter() || c.category in NUMBER_CATEGORIES || c in alsoKeep) {
            out.append(c)
            inRun = false
        } else if (!inRun) {
            out.append(replacement)
            inRun = true
        }
    }
    return out.toString()
}
