package com.cruxcoach.app.community

/**
 * ISO-8601 instant conversion for community-climb rows and Nostr `created_at`
 * values, and the monotonic clamp Android's `CommunityEventTime.kt` applies.
 *
 * Android uses `java.time.Instant`; `commonMain` has to reach iOS, so the two
 * conversions are implemented here with the standard civil-from-days /
 * days-from-civil algorithms. The output shape is exactly
 * `Instant.ofEpochSecond(x).toString()` — `YYYY-MM-DDTHH:MM:SSZ`, second
 * precision, no fraction — so rows written by this app and by Android's are
 * byte-identical.
 */
internal object CommunityEventTime {

    private const val SECONDS_PER_DAY = 86_400L

    /** `2026-09-20T08:41:23Z`. Matches `java.time.Instant.ofEpochSecond` exactly. */
    fun isoFromEpochSeconds(epochSeconds: Long): String {
        val days = epochSeconds.floorDiv(SECONDS_PER_DAY)
        val secondOfDay = epochSeconds - days * SECONDS_PER_DAY
        val (year, month, day) = civilFromDays(days)
        val hour = secondOfDay / 3600
        val minute = (secondOfDay / 60) % 60
        val second = secondOfDay % 60
        return buildString {
            append(pad(year, if (year < 0) 5 else 4))
            append('-'); append(pad(month.toLong(), 2))
            append('-'); append(pad(day.toLong(), 2))
            append('T'); append(pad(hour, 2))
            append(':'); append(pad(minute, 2))
            append(':'); append(pad(second, 2))
            append('Z')
        }
    }

    /**
     * Inverse of [isoFromEpochSeconds], tolerant of the fractional seconds and
     * numeric offsets other writers emit. Null for anything else — including the
     * catalogue's space-separated `YYYY-MM-DD HH:MM:SS`, which `Instant.parse`
     * also rejects. Callers treat null as "no usable timestamp", never as zero.
     */
    fun epochSecondsFromIso(iso: String): Long? {
        val text = iso.trim()
        // Shortest accepted form: 1970-01-01T00:00:00 (19 chars).
        if (text.length < 19 || text[10] != 'T') return null
        val year = text.substring(0, 4).toIntOrNull() ?: return null
        if (text[4] != '-' || text[7] != '-') return null
        val month = text.substring(5, 7).toIntOrNull() ?: return null
        val day = text.substring(8, 10).toIntOrNull() ?: return null
        if (text[13] != ':' || text[16] != ':') return null
        val hour = text.substring(11, 13).toIntOrNull() ?: return null
        val minute = text.substring(14, 16).toIntOrNull() ?: return null
        val second = text.substring(17, 19).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        var rest = text.substring(19)
        if (rest.startsWith('.')) {
            // Fractions are dropped, not rounded: the column stores whole seconds.
            var i = 1
            while (i < rest.length && rest[i].isDigit()) i++
            if (i == 1) return null
            rest = rest.substring(i)
        }
        val offsetSeconds = when {
            rest == "Z" || rest == "z" -> 0L
            rest.isEmpty() -> 0L
            rest.length == 6 && (rest[0] == '+' || rest[0] == '-') && rest[3] == ':' -> {
                val h = rest.substring(1, 3).toIntOrNull() ?: return null
                val m = rest.substring(4, 6).toIntOrNull() ?: return null
                if (h > 23 || m > 59) return null
                val magnitude = h * 3600L + m * 60L
                if (rest[0] == '+') magnitude else -magnitude
            }
            else -> return null
        }
        val days = daysFromCivil(year, month, day)
        return days * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second - offsetSeconds
    }

    /**
     * Monotonic `created_at` for a replaceable community-climb event.
     *
     * Publish / edit / delete share one stable d-tag, so successive events MUST
     * strictly advance or "newest wins" resolves differently on the live path and
     * in the server-side bundle (Android FEAT-039 audit BUG-1). Clamping to
     * `max(now, prior + 1)` keeps every emit strictly ahead of the last one even
     * across a same-second republish or a backward clock step.
     */
    fun monotonicCreatedAtSeconds(nowSeconds: Long, priorIso: String?): Long {
        val prior = priorIso?.let { epochSecondsFromIso(it) }
        return if (prior != null) maxOf(nowSeconds, prior + 1) else nowSeconds
    }

    private fun pad(value: Long, width: Int): String {
        val negative = value < 0
        val digits = (if (negative) -value else value).toString().padStart(width, '0')
        return if (negative) "-$digits" else digits
    }

    /** Howard Hinnant's `civil_from_days`, proleptic Gregorian. */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Int, Int> {
        val z = daysSinceEpoch + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        return Triple(if (m <= 2L) y + 1L else y, m.toInt(), d.toInt())
    }

    /** Howard Hinnant's `days_from_civil`, the exact inverse of [civilFromDays]. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400L
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153L * mp + 2L) / 5L + day - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146_097L + doe - 719_468L
    }
}
