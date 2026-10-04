package com.cruxcoach.athlete.logic

import kotlinx.datetime.LocalDate
import kotlin.math.pow

/**
 * Exponentially smoothed body weight (Hacker's Diet method, smoothing 0.9).
 *
 * Daily weight swings by a kilo or more from water and food; the trend moves
 * only when the underlying weight does. Gaps are handled by compounding the
 * smoothing per missed day (`α = 1 − 0.9^gap`), so a week without weighing
 * pulls the trend as far as seven daily entries would — not further.
 */
object TrendWeight {

    data class Point(val day: LocalDate, val raw: Double, val trend: Double)

    fun compute(points: List<Pair<LocalDate, Double>>, smoothing: Double = 0.9): List<Point> {
        var trend: Double? = null
        var lastDay: LocalDate? = null
        return points.sortedBy { it.first }.map { (day, raw) ->
            val previous = trend
            val next = if (previous == null || lastDay == null) raw else {
                val gap = (day.toEpochDays() - lastDay!!.toEpochDays()).toLong().coerceAtLeast(1L)
                val alpha = 1.0 - smoothing.pow(gap.toDouble())
                previous + alpha * (raw - previous)
            }
            trend = next
            lastDay = day
            Point(day, raw, next)
        }
    }

    /**
     * Trend change in kg per week over roughly [windowDays]; null until the
     * series spans at least a week, because shorter spans are noise.
     */
    fun weeklyRate(points: List<Point>, windowDays: Int = 14): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val start = points.lastOrNull { daysBetween(it.day, last.day) >= windowDays } ?: points.first()
        val days = daysBetween(start.day, last.day)
        if (days < 7) return null
        return (last.trend - start.trend) / days * 7.0
    }

    /** Relative trend change (fraction, e.g. −0.03) over [days], or null without enough history. */
    fun relativeChange(points: List<Point>, days: Int): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val start = points.lastOrNull { daysBetween(it.day, last.day) >= days } ?: return null
        if (start.trend <= 0.0) return null
        return (last.trend - start.trend) / start.trend
    }

    internal fun daysBetween(a: LocalDate, b: LocalDate): Long = (b.toEpochDays() - a.toEpochDays()).toLong()
}
