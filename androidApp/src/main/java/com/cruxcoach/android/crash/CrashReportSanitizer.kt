package com.cruxcoach.android.crash

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Allow-list renderer for the only crash content that can leave the device.
 * Exception messages and source-file paths are intentionally absent: both can
 * contain URLs, identifiers, tokens, imported names, or remote peer text.
 */
internal object CrashReportSanitizer {
    fun renderStack(throwable: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        var causeDepth = 0
        while (current != null && causeDepth < MAX_CAUSES && seen.add(current)) {
            if (causeDepth > 0) append("Caused by: ")
            appendLine(safeSymbol(current.javaClass.name))

            val frames = current.stackTrace
            for (frame in frames.take(MAX_FRAMES_PER_CAUSE)) {
                append("\tat ")
                append(safeSymbol(frame.className))
                append('.')
                append(safeSymbol(frame.methodName))
                when {
                    frame.isNativeMethod -> appendLine("(native)")
                    frame.lineNumber >= 0 -> appendLine("(line ${frame.lineNumber})")
                    else -> appendLine("(line unknown)")
                }
            }
            if (frames.size > MAX_FRAMES_PER_CAUSE) appendLine("\t… frames omitted")
            current = current.cause
            causeDepth++
        }
        if (current != null) appendLine("… causes omitted")
    }

    private fun safeSymbol(raw: String): String = buildString(minOf(raw.length, MAX_SYMBOL_CHARS)) {
        for (char in raw.take(MAX_SYMBOL_CHARS)) {
            append(if (char.isLetterOrDigit() || char in "_.$<>") char else '_')
        }
    }.ifEmpty { "unknown" }

    private const val MAX_CAUSES = 12
    private const val MAX_FRAMES_PER_CAUSE = 128
    private const val MAX_SYMBOL_CHARS = 200
}
