package com.cruxcoach.android.util

/**
 * Render untrusted peer/server text as one bounded logcat line.
 *
 * Besides C0/DEL controls, neutralise Unicode line separators and bidi
 * controls: those do not execute code, but they can forge or visually reorder
 * evidence in exported bug reports and terminal log viewers.
 */
internal fun String.forLog(maxChars: Int = 200): String {
    if (maxChars <= 0) return ""
    var truncated = false
    val cleaned = buildString(minOf(length, maxChars)) {
        for (char in this@forLog) {
            if (length == maxChars) {
                truncated = true
                break
            }
            append(
                when {
                    char.isISOControl() -> LOG_REPLACEMENT
                    char == '\u2028' || char == '\u2029' -> LOG_REPLACEMENT
                    char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069' -> LOG_REPLACEMENT
                    else -> char
                },
            )
        }
    }
    if (!truncated) return cleaned
    return if (maxChars == 1) "…" else cleaned.dropLast(1) + "…"
}

private const val LOG_REPLACEMENT = '·'
