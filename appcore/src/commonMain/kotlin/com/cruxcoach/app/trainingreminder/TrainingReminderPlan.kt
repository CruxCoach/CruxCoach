// Port of androidApp/.../athlete/TrainingReminders.kt and its WeekPlanReminderWorker (0e1bad0c6)
// for the iOS app. Android enqueues one work request per day that decides at the chosen time from
// the current week plan. Nothing of ours runs when an iOS local notification fires, so the next
// planned days are scheduled ahead as one-shot requests with stable ids (like the body reminders),
// and every change of the reminder, the week plan, a pause or a training schedules them again
// (the host calls it at app start and when the app returns to the foreground).
// Android's quiet rules, as far as they can be known ahead: rest and free days and, unless wanted,
// board and climbing days get nothing; a date's own plan counts before the standard week's; an open pause schedules nothing; today is skipped once trained or with
// a training running; a routine deleted since it was planned is skipped ("say nothing rather than
// something wrong"). Android names the routine when the reminder fires; here it is named when the
// reminder is scheduled (app start, return to the foreground, a change of the plan, a pause or a
// training), so a routine renamed in between keeps its old name in reminders until then.
package com.cruxcoach.app.trainingreminder

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.notify.NotificationRoutes
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** One planned day worth a reminder: when, and the plan entry (a routine id, [PLAN_BOARD] or a climbing day). */
internal data class PlannedReminder(val at: LocalDateTime, val entry: String)

internal object TrainingReminderPlan {
    const val ID = "training_day_"
    /** Reminders scheduled ahead: at least a week even when every day is planned. */
    const val AHEAD = 8
    /** How far ahead planned days are looked for (two weeks of the repeating plan). */
    const val HORIZON_DAYS = 14
    /** Android `WeekPlanReminderWorker`: "Today: <routine>" over "Tap for your suggestion …". */
    const val TITLE_KEY = "trw_notify_title"
    const val BODY_KEY = "trw_notify_text"
    /** The routine name of a board day. */
    const val BOARD_KEY = "trw_notify_board"
    /** The name of a planned climbing day (Android 570820eee). */
    const val CLIMB_KEY = "trw_notify_climb"
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
    fun plannedEntry(profile: AthleteProfile, isoWeekday: Int): String? = worthReminding(profile, profile.weekPlan[isoWeekday])

    /** The same for a date: its own entry (a moved day, "rest today") before the standard week (owner 2026-10-19). */
    fun plannedEntry(profile: AthleteProfile, date: LocalDate): String? = worthReminding(profile, DayPlans.entry(profile, date))

    /** Climbing entries count as climbing days ([AthleteProfile.trainingReminderOnClimbingDays]). */
    private fun worthReminding(profile: AthleteProfile, entry: String?): String? = when {
        entry == null || entry == PLAN_REST -> null
        entry == PLAN_BOARD || DayPlans.isClimb(entry) -> entry.takeIf { profile.trainingReminderOnClimbingDays }
        else -> entry
    }

    /** Whether a routine entry still names a routine (built-in key or own routine id). */
    fun routineExists(entry: String, ownRoutineIds: Set<String>): Boolean =
        if (entry.startsWith(BUILTIN)) BuiltinRoutines.byKey(entry.removePrefix(BUILTIN)) != null else entry in ownRoutineIds

    /**
     * Android `WeekPlanReminderWorker`'s title for a plan [entry]: the board session, a built-in
     * routine's localized name (else its stored name), an own routine's name. Null for a routine that
     * no longer exists — that day gets no reminder rather than a wrong one.
     */
    fun title(entry: String, ownRoutines: List<Routine>): TrText? {
        val name: TrText = when {
            entry == PLAN_BOARD -> Tx.t(BOARD_KEY)
            DayPlans.isClimb(entry) -> Tx.t(CLIMB_KEY)
            entry.startsWith(BUILTIN) ->
                BuiltinRoutines.byKey(entry.removePrefix(BUILTIN))?.let { Tx.routineName(it) } ?: return null
            else -> ownRoutines.firstOrNull { it.id == entry }?.let { Tx.plain(it.name) } ?: return null
        }
        return Tx.t(TITLE_KEY, Tx.x(name))
    }

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
            val entry = plannedEntry(profile, day) ?: continue
            if (entry != PLAN_BOARD && !DayPlans.isClimb(entry) && !routineExists(entry, ownRoutineIds)) continue
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
        val own = if (!profile.trainingReminderEnabled) emptyList() else repo.routines()
        val planned = if (!profile.trainingReminderEnabled || repo.openPause() != null) emptyList() else {
            val today = now.date
            val skipToday = repo.openWorkout() != null ||
                runCatching { service.activities(1)[today.toString()]?.trained == true }.getOrDefault(false)
            TrainingReminderPlan.upcoming(now, profile, zone, TrainingReminderPlan.AHEAD,
                if (skipToday) setOf(today) else emptySet(), own.map { it.id }.toSet())
        }
        val body = Tx.t(TrainingReminderPlan.BODY_KEY)
        for (i in 0 until TrainingReminderPlan.AHEAD) {
            val id = TrainingReminderPlan.ID + i
            val next = planned.getOrNull(i)
            val after = next?.let {
                (it.at.toInstant(zone).epochSeconds - nowInstant.epochSeconds).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }
            val title = next?.let { TrainingReminderPlan.title(it.entry, own) }
            // Android opens Today from this reminder (`navigate_to` = "today").
            if (after == null || after < 1 || title == null) scheduler.cancel(id)
            else scheduler.scheduleText(id, after, title, body, NotificationRoutes.TODAY)
        }
    }
}
