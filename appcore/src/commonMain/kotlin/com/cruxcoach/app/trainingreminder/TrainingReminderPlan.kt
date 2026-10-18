// Port of androidApp/.../athlete/TrainingReminders.kt and its WeekPlanReminderWorker (0e1bad0c6)
// for the iOS app. Android enqueues one work request per day that decides at the chosen time from
// the current week plan. Nothing of ours runs when an iOS local notification fires, so the next
// planned days are scheduled ahead as one-shot requests with stable ids (like the body reminders),
// and every change of the reminder, the week plan, a pause or a training schedules them again
// (the host calls it at app start and when the app returns to the foreground).
// Android's quiet rules, as far as they can be known ahead: rest and free days and, unless wanted,
// board days get nothing; an open pause schedules nothing; today is skipped once trained or with
// a training running; a routine deleted since it was planned is skipped ("say nothing rather than
// something wrong").
package com.cruxcoach.app.trainingreminder

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** One planned day worth a reminder: when, and the plan entry (a routine id or [PLAN_BOARD]). */
internal data class PlannedReminder(val at: LocalDateTime, val entry: String)

internal object TrainingReminderPlan {
    const val ID = "training_day_"
    /** Reminders scheduled ahead: at least a week even when every day is planned. */
    const val AHEAD = 8
    /** How far ahead planned days are looked for (two weeks of the repeating plan). */
    const val HORIZON_DAYS = 14
    /**
     * Android's title "Today: <routine>" carries the routine's name, but the host scheduler takes
     * plain string keys without arguments, so the name-free title of the same reminder is used.
     */
    const val TITLE_KEY = "training_reminder_title"
    const val BODY_KEY = "trw_notify_text"
    /** Android's default time when nothing was stored: 17:00. */
    const val DEFAULT_MINUTES = 17 * 60

    private const val BUILTIN = "builtin:"

    private fun at(day: LocalDate, minutes: Int): LocalDateTime {
        val m = minutes.coerceIn(0, 24 * 60 - 1)
        return LocalDateTime(day, LocalTime(m / 60, m % 60))
    }

    private fun later(a: LocalDateTime, b: LocalDateTime, zone: TimeZone): Boolean =
        a.toInstant(zone).epochSeconds > b.toInstant(zone).epochSeconds + 30

    /** Android `nextOccurrence`: today at the chosen time if still ahead (by more than 30 s), otherwise tomorrow. */
    fun nextOccurrence(now: LocalDateTime, minutes: Int, zone: TimeZone): LocalDateTime {
        val today = at(now.date, minutes)
        return if (later(today, now, zone)) today else at(now.date.plus(DatePeriod(days = 1)), minutes)
    }

    /**
     * Android `plannedEntry`: what the plan holds for [isoWeekday] worth a reminder — a routine id
     * ("builtin:<key>" or an own routine), [PLAN_BOARD], or null for a free or rest day (and for
     * board days when those are not wanted).
     */
    fun plannedEntry(profile: AthleteProfile, isoWeekday: Int): String? {
        val entry = profile.weekPlan[isoWeekday] ?: return null
        return when (entry) {
            PLAN_REST -> null
            PLAN_BOARD -> entry.takeIf { profile.trainingReminderOnClimbingDays }
            else -> entry
        }
    }

    /** Whether a routine entry still names a routine (built-in key or own routine id). */
    fun routineExists(entry: String, ownRoutineIds: Set<String>): Boolean =
        if (entry.startsWith(BUILTIN)) BuiltinRoutines.byKey(entry.removePrefix(BUILTIN)) != null else entry in ownRoutineIds

    /**
     * The next [count] planned days from [now] within [HORIZON_DAYS], at the chosen time; a day in
     * [skipDays] and a routine that no longer exists are left out. Empty while switched off.
     */
    fun upcoming(
        now: LocalDateTime,
        profile: AthleteProfile,
        zone: TimeZone,
        count: Int,
        skipDays: Set<LocalDate>,
        ownRoutineIds: Set<String>,
    ): List<PlannedReminder> {
        if (!profile.trainingReminderEnabled) return emptyList()
        val minutes = profile.trainingReminderMinutes
        val first = nextOccurrence(now, minutes, zone).date
        val out = mutableListOf<PlannedReminder>()
        for (offset in 0 until HORIZON_DAYS) {
            if (out.size >= count) break
            val day = first.plus(DatePeriod(days = offset))
            if (day in skipDays) continue
            val entry = plannedEntry(profile, day.dayOfWeek.isoDayNumber) ?: continue
            if (entry != PLAN_BOARD && !routineExists(entry, ownRoutineIds)) continue
            out += PlannedReminder(at(day, minutes), entry)
        }
        return out
    }
}

/**
 * Schedules the training-day reminders of [AthleteProfile] with the host's [NotificationScheduler]:
 * every slot is replaced on each call, so switching the reminder off (or a pause) cancels them.
 */
internal class TrainingReminderScheduler(
    private val service: AthleteService,
    private val scheduler: NotificationScheduler,
    private val nowMillis: () -> Long,
) {
    /** Call on the IO dispatcher (it reads the athlete database). */
    fun reschedule(profile: AthleteProfile) {
        val zone = TimeZone.currentSystemDefault()
        val nowInstant = kotlin.time.Instant.fromEpochMilliseconds(nowMillis())
        val now = nowInstant.toLocalDateTime(zone)
        val repo = service.repo
        val planned = if (!profile.trainingReminderEnabled || repo.openPause() != null) emptyList() else {
            val today = now.date
            val skipToday = repo.openWorkout() != null ||
                runCatching { service.activities(1)[today.toString()]?.trained == true }.getOrDefault(false)
            TrainingReminderPlan.upcoming(now, profile, zone, TrainingReminderPlan.AHEAD,
                if (skipToday) setOf(today) else emptySet(), repo.routines().map { it.id }.toSet())
        }
        for (i in 0 until TrainingReminderPlan.AHEAD) {
            val id = TrainingReminderPlan.ID + i
            val after = planned.getOrNull(i)?.let {
                (it.at.toInstant(zone).epochSeconds - nowInstant.epochSeconds).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }
            if (after == null || after < 1) scheduler.cancel(id)
            else scheduler.schedule(id, after, TrainingReminderPlan.TITLE_KEY, TrainingReminderPlan.BODY_KEY)
        }
    }
}
