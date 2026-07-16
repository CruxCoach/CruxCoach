package com.cruxcoach.android.util

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formats an ISO storage date for display in the active locale. */
fun formatIsoDate(
    isoDate: String,
    locale: Locale = Locale.getDefault(),
): String = try {
    LocalDate.parse(isoDate.take(10))
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
} catch (_: Exception) {
    isoDate.take(10)
}

/** Formats an epoch timestamp with locale-specific short date and time. */
fun formatEpochDateTime(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis)
    .atZone(zoneId)
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))

/** Locale-aware compact month/day label for charts and range chips. */
fun formatDayMonth(
    date: LocalDate,
    locale: Locale = Locale.getDefault(),
): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "Md")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
