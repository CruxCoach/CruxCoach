package com.cruxcoach.util

import kotlin.time.Clock
import kotlinx.datetime.*

object DateTimeUtil {

    fun now(): LocalDate {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    fun nowIso(): String {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).toString()
    }

    fun todayIso(): String {
        return now().toString()
    }

    fun parseDate(dateString: String): LocalDate {
        return LocalDate.parse(dateString)
    }

    fun daysBetween(start: String, end: String): Int {
        val startDate = parseDate(start)
        val endDate = parseDate(end)
        return startDate.daysUntil(endDate)
    }

    fun weeksBetween(start: String, end: String): Int {
        return daysBetween(start, end) / 7
    }

    fun addDays(date: String, days: Int): String {
        val localDate = parseDate(date)
        val result = localDate.plus(days, DateTimeUnit.DAY)
        return result.toString()
    }

    fun addWeeks(date: String, weeks: Int): String {
        return addDays(date, weeks * 7)
    }

    fun dayOfWeek(date: String): Int {
        val localDate = parseDate(date)
        return localDate.dayOfWeek.isoDayNumber
    }

    fun startOfWeek(date: String): String {
        val localDate = parseDate(date)
        val daysSinceMonday = localDate.dayOfWeek.isoDayNumber - 1
        return localDate.minus(daysSinceMonday, DateTimeUnit.DAY).toString()
    }

    fun endOfWeek(date: String): String {
        val startOfWeek = startOfWeek(date)
        return addDays(startOfWeek, 6)
    }

    fun isThisWeek(date: String): Boolean {
        val today = todayIso()
        val weekStart = startOfWeek(today)
        val weekEnd = endOfWeek(today)
        return date in weekStart..weekEnd
    }

    /** Parse an ISO LocalDateTime string (from [nowIso]) to epoch milliseconds, or null on failure. */
    fun isoToEpochMs(isoDateTime: String): Long? {
        return try {
            val localDateTime = LocalDateTime.parse(isoDateTime)
            val tz = TimeZone.currentSystemDefault()
            localDateTime.toInstant(tz).toEpochMilliseconds()
        } catch (_: Exception) {
            null
        }
    }
}
