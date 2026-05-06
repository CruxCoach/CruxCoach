package com.cruxcoach.android.aurora

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Aurora email exports mix two timestamp formats — strict ISO 8601
 * (`"2024-01-15T10:30:00Z"`) and a space-separated variant
 * (`"2024-01-15 10:30:00"`) without a timezone. The `external_id` hash
 * (FEAT-005 §5.1) consumes timestamps as a string, so two forms of the
 * same instant must collapse to a single canonical representation
 * before hashing, otherwise re-importing a file written by a different
 * Aurora client surfaces phantom duplicates.
 *
 * Mirrors the timestamp-normalisation step in
 * `boardsesh/packages/web/app/lib/data-sync/aurora/json-import.ts:131-141`
 * (Apache 2.0):
 *
 * 1. Trim, replace any internal space with `T`.
 * 2. If no explicit timezone offset (`Z`, `+HH:MM`, `-HH:MM`) is
 *    present, append `Z` (assume UTC) — Aurora exports without a
 *    timezone are observed to be already-UTC despite the missing
 *    suffix.
 * 3. Parse via `java.time.Instant`; sub-second precision finer than
 *    milliseconds is silently truncated by the formatter.
 * 4. Re-serialise via `Instant.toString()` for a canonical
 *    `YYYY-MM-DDTHH:MM:SSZ` (or with millis suffix when present).
 */
object AuroraTimestamp {

    private val ISO_INSTANT = DateTimeFormatter.ISO_INSTANT

    /**
     * Normalise [raw] to canonical `Instant.toString()` form. Returns
     * null when the input is malformed beyond rescue — caller treats
     * the row as un-importable and increments the parse-fail counter.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Step 1+2: replace inner space with T, append Z if no timezone.
        val withT = trimmed.replace(' ', 'T')
        val withTimezone = if (hasTimezoneSuffix(withT)) withT else "${withT}Z"

        // Step 3+4: parse + canonicalise.
        return try {
            Instant.parse(withTimezone).toString()
        } catch (e: DateTimeParseException) {
            // Fallback path: a few exports observed in the wild use
            // `YYYY-MM-DDTHH:MM:SS.SSSSSS` (six-digit microseconds)
            // which Instant.parse rejects on some Android / JVM
            // combos. Truncate to milliseconds and retry once.
            val truncated = truncateSubSecondToMillis(withTimezone) ?: return null
            try {
                Instant.parse(truncated).toString()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    private fun hasTimezoneSuffix(s: String): Boolean {
        if (s.endsWith('Z') || s.endsWith('z')) return true
        val tail = s.takeLast(6)
        return tail.matches(Regex("[+-]\\d{2}:\\d{2}")) ||
            s.takeLast(5).matches(Regex("[+-]\\d{4}"))
    }

    /** Trim sub-second digits past 3 (millis), preserving any trailing
     *  timezone marker. Returns null when the shape isn't recognised. */
    private fun truncateSubSecondToMillis(s: String): String? {
        val dotIndex = s.indexOf('.')
        if (dotIndex < 0) return null
        // Find where the fractional seconds end — at any of the
        // timezone markers.
        val tailStart = run {
            val z = s.indexOf('Z', dotIndex)
            val plus = s.indexOf('+', dotIndex)
            val minus = s.indexOf('-', dotIndex)
            listOf(z, plus, minus).filter { it > 0 }.minOrNull() ?: s.length
        }
        val frac = s.substring(dotIndex + 1, tailStart)
        if (frac.length <= 3) return null  // already millis or shorter
        val truncatedFrac = frac.take(3)
        return s.substring(0, dotIndex + 1) + truncatedFrac + s.substring(tailStart)
    }
}
