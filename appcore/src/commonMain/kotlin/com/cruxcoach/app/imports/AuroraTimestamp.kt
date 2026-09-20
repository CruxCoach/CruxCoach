package com.cruxcoach.app.imports

import kotlin.time.Instant

/**
 * Port of Android `AuroraTimestamp`.
 *
 * Aurora email exports mix strict ISO 8601 (`2024-01-15T10:30:00Z`) with a
 * space-separated, offset-less variant (`2024-01-15 10:30:00`). The
 * `external_id` hash consumes the timestamp as a *string*, so two spellings of
 * the same instant must collapse to one canonical form or a file written by a
 * different Aurora client re-imports as phantom duplicates.
 */
internal object AuroraTimestamp {
    private val OFFSET_WITH_COLON = Regex("[+-]\\d{2}:\\d{2}")
    private val OFFSET_WITHOUT_COLON = Regex("[+-]\\d{4}")

    /** Canonical form, or null when the input is malformed beyond rescue. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_LENGTH) return null

        val withT = trimmed.replace(' ', 'T')
        val withTimezone = if (hasTimezoneSuffix(withT)) withT else withT + "Z"
        // Sub-second precision finer than milliseconds is capped *before*
        // parsing: the printer round-trips whatever precision it was given, so
        // the same instant spelled with micros and with millis would otherwise
        // canonicalise to two different strings and dedup twice.
        val canonical = truncateSubSecondToMillis(withTimezone) ?: withTimezone
        return try {
            Instant.parse(canonical).toString()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun hasTimezoneSuffix(value: String): Boolean {
        if (value.endsWith('Z') || value.endsWith('z')) return true
        return OFFSET_WITH_COLON.matches(value.takeLast(6)) ||
            OFFSET_WITHOUT_COLON.matches(value.takeLast(5))
    }

    private fun truncateSubSecondToMillis(value: String): String? {
        val dotIndex = value.indexOf('.')
        if (dotIndex < 0) return null
        val tailStart = listOf(
            value.indexOf('Z', dotIndex),
            value.indexOf('+', dotIndex),
            value.indexOf('-', dotIndex),
        ).filter { it > 0 }.minOrNull() ?: value.length
        if (tailStart <= dotIndex) return null
        val fraction = value.substring(dotIndex + 1, tailStart)
        if (fraction.length <= 3) return null
        return value.substring(0, dotIndex + 1) + fraction.take(3) + value.substring(tailStart)
    }

    /** A timestamp is a fixed-shape token; anything longer is hostile input. */
    private const val MAX_LENGTH = 64
}
