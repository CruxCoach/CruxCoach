// Port of androidApp/.../athlete/BodyReminders.kt (0e1bad0c6) for the iOS app: the weigh-in on
// chosen weekdays and the monthly measuring round. Android enqueues one work request for the next
// occurrence and its worker enqueues the following one; nothing of ours runs when an iOS local
// notification fires, so the next few occurrences are scheduled ahead as one-shot requests with
// stable ids, and every change (and every app start, via the host) schedules them again.
// Android's worker skips a reminder when the value of that day is already logged; here an
// occurrence on a day whose value is already logged is not scheduled.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.MetricGroup
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

internal object BodyReminderPlan {
    const val WEIGH_ID = "body_weigh_"
    const val MEASURE_ID = "body_measure_"
    /** Weigh-ins scheduled ahead: at least a week even when every day is chosen. */
    const val WEIGH_AHEAD = 8
    const val MEASURE_AHEAD = 2

    private fun at(day: LocalDate, minutes: Int): LocalDateTime {
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        return LocalDateTime(day, LocalTime(m / 60, m % 60))
    }

    private fun later(a: LocalDateTime, b: LocalDateTime, zone: TimeZone): Boolean =
        a.toInstant(zone).epochSeconds > b.toInstant(zone).epochSeconds + 30

    /** Android `nextWeigh`: the next chosen weekday at the chosen time, strictly in the future; null without weekdays. */
    fun nextWeigh(now: LocalDateTime, days: Set<Int>, minutes: Int, zone: TimeZone): LocalDateTime? {
        val valid = days.filter { it in 1..7 }.toSet()
        if (valid.isEmpty()) return null
        for (offset in 0..7) {
            val candidate = at(now.date.plus(DatePeriod(days = offset)), minutes)
            if (candidate.date.dayOfWeek.isoDayNumber in valid && later(candidate, now, zone)) return candidate
        }
        return null
    }

    /** Android `nextMeasure`: the chosen day (1–28, so every month has it) of this or next month at the chosen time. */
    fun nextMeasure(now: LocalDateTime, dayOfMonth: Int, minutes: Int, zone: TimeZone): LocalDateTime {
        val day = dayOfMonth.coerceIn(1, 28)
        val thisMonth = at(LocalDate(now.year, now.month, day), minutes)
        if (later(thisMonth, now, zone)) return thisMonth
        val next = LocalDate(now.year, now.month, 1).plus(DatePeriod(months = 1))
        return at(LocalDate(next.year, next.month, day), minutes)
    }

    /** The next [count] weigh-ins from [now]; a day in [skipDays] (already logged) is left out. */
    fun upcomingWeigh(now: LocalDateTime, days: Set<Int>, minutes: Int, zone: TimeZone, count: Int, skipDays: Set<LocalDate>): List<LocalDateTime> {
        val out = mutableListOf<LocalDateTime>()
        var from = now
        while (out.size < count) {
            val next = nextWeigh(from, days, minutes, zone) ?: break
            if (next.date !in skipDays) out += next
            // Continue from just after this occurrence.
            from = LocalDateTime(next.date, LocalTime(23, 59, 59))
        }
        return out
    }

    fun upcomingMeasure(now: LocalDateTime, dayOfMonth: Int, minutes: Int, zone: TimeZone, count: Int, skipDays: Set<LocalDate>): List<LocalDateTime> {
        val out = mutableListOf<LocalDateTime>()
        var from = now
        repeat(count + 1) {
            if (out.size >= count) return out
            val next = nextMeasure(from, dayOfMonth, minutes, zone)
            if (next.date !in skipDays) out += next
            from = LocalDateTime(next.date, LocalTime(23, 59, 59))
        }
        return out
    }

    fun circumferenceKeys(): Set<String> =
        BodyMetric.entries.filter { it.group == MetricGroup.CIRCUMFERENCE }.map { it.key }.toSet()
}

/**
 * Schedules the body reminders of [AthleteProfile] with the host's [NotificationScheduler]: the
 * pending ones of both kinds are replaced on every call, so switching a reminder off cancels it.
 */
internal class BodyReminderScheduler(
    private val service: AthleteService,
    private val scheduler: NotificationScheduler,
    private val nowMillis: () -> Long,
) {
    /** Call on the IO dispatcher (it reads the athlete database). */
    fun reschedule(profile: AthleteProfile) {
        val zone = TimeZone.currentSystemDefault()
        val nowInstant = kotlin.time.Instant.fromEpochMilliseconds(nowMillis())
        val now = nowInstant.toLocalDateTime(zone)
        val today = now.date
        val repo = service.repo
        val weighedToday = repo.latest(BodyMetric.WEIGHT.key)?.day == today.toString()
        val measuredToday = BodyReminderPlan.circumferenceKeys().any { repo.latest(it)?.day == today.toString() }
        val weigh = if (!profile.weighReminderEnabled) emptyList() else BodyReminderPlan.upcomingWeigh(now, profile.weighReminderDays,
            profile.weighReminderMinutes, zone, BodyReminderPlan.WEIGH_AHEAD, if (weighedToday) setOf(today) else emptySet())
        val measure = if (!profile.measureReminderEnabled) emptyList() else BodyReminderPlan.upcomingMeasure(now,
            profile.measureReminderDayOfMonth, profile.weighReminderMinutes, zone, BodyReminderPlan.MEASURE_AHEAD,
            if (measuredToday) setOf(today) else emptySet())
        apply(BodyReminderPlan.WEIGH_ID, BodyReminderPlan.WEIGH_AHEAD, weigh, nowInstant.epochSeconds, zone,
            "trr_notify_weigh_title", "trr_notify_weigh_text")
        apply(BodyReminderPlan.MEASURE_ID, BodyReminderPlan.MEASURE_AHEAD, measure, nowInstant.epochSeconds, zone,
            "trr_notify_measure_title", "trr_notify_measure_text")
    }

    private fun apply(prefix: String, slots: Int, times: List<LocalDateTime>, nowSeconds: Long, zone: TimeZone, title: String, body: String) {
        for (i in 0 until slots) {
            val id = prefix + i
            val at = times.getOrNull(i)
            val after = at?.let { (it.toInstant(zone).epochSeconds - nowSeconds).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }
            if (after == null || after < 1) scheduler.cancel(id) else scheduler.schedule(id, after, title, body)
        }
    }
}
