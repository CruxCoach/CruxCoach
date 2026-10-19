package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * What is planned on a date (owner 2026-10-19: plan climbing days, build the rest of the training around them, move
 * days – "no time that day: rest; a volume boulder day tomorrow and a limit day two days later").
 *
 * The standard week ([AthleteProfile.weekPlan]) is the template; [AthleteProfile.datePlan] overrides single dates.
 * Entries: a routine id, [PLAN_BOARD], [PLAN_REST], or a climbing day "climb:<kind>:<intensity>" with a
 * [ClimbingDayKind] and a [ClimbIntensity] in lower case ("climb:gym_boulder:limit").
 */
object DayPlans {

    const val CLIMB_PREFIX = "climb:"

    /** Overrides older than this are dropped when the plan is written. */
    private const val KEEP_PAST_DAYS = 14

    data class Climb(val kind: ClimbingDayKind, val intensity: ClimbIntensity) {
        val entry: String get() = CLIMB_PREFIX + kind.name.lowercase() + ":" + intensity.name.lowercase()
    }

    /** The climbing day an entry plans, or null for anything else. */
    fun climbOf(entry: String?): Climb? {
        if (entry == null || !entry.startsWith(CLIMB_PREFIX)) return null
        val parts = entry.removePrefix(CLIMB_PREFIX).split(':')
        val kind = parts.getOrNull(0)?.let { k -> ClimbingDayKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
        val intensity = parts.getOrNull(1)?.let { i -> ClimbIntensity.entries.firstOrNull { it.name.equals(i, ignoreCase = true) } }
        return Climb(kind ?: ClimbingDayKind.GYM_BOULDER, intensity ?: ClimbIntensity.VOLUME)
    }

    fun isClimb(entry: String?): Boolean = climbOf(entry) != null

    /** What [date] plans: its own entry, else the standard week's; null = nothing planned (the coach decides). */
    fun entry(profile: AthleteProfile, date: LocalDate): String? {
        val own = profile.datePlan[date.toString()]
        if (own != null) return own.takeIf { it != PLAN_FREE }
        return profile.weekPlan[date.dayOfWeek.isoDayNumber]
    }

    /** [date] has its own entry instead of the standard week's. */
    fun isOverridden(profile: AthleteProfile, date: LocalDate): Boolean = profile.datePlan.containsKey(date.toString())

    /** A climbing or board day is planned on [date] (the plan, not what was logged). */
    fun climbingPlanned(profile: AthleteProfile, date: LocalDate): Boolean {
        val e = entry(profile, date)
        return e == PLAN_BOARD || isClimb(e) || (e == null && !isOverridden(profile, date) && date.dayOfWeek.isoDayNumber in profile.coach.climbingDays)
    }

    /** The climbing intensity planned on [date]: a climbing day's, hard for a board day, null otherwise. */
    fun climbIntensity(profile: AthleteProfile, date: LocalDate): ClimbIntensity? {
        val e = entry(profile, date)
        return climbOf(e)?.intensity ?: if (e == PLAN_BOARD) ClimbIntensity.HARD else null
    }

    /**
     * [profile] with [date] planned as [entry]; null goes back to the standard week. An entry equal to the standard
     * week's needs no override. Old overrides are dropped on the way.
     */
    fun withDay(profile: AthleteProfile, date: LocalDate, entry: String?, today: LocalDate): AthleteProfile {
        val plan = cleaned(profile.datePlan, today).toMutableMap()
        val template = profile.weekPlan[date.dayOfWeek.isoDayNumber]
        // "Free" over a usual climbing weekday says something (no climbing that day): it stays.
        val usualClimb = template == null && date.dayOfWeek.isoDayNumber in profile.coach.climbingDays
        when {
            entry == null -> plan.remove(date.toString())
            entry == template || (entry == PLAN_FREE && template == null && !usualClimb) -> plan.remove(date.toString())
            else -> plan[date.toString()] = entry
        }
        return profile.copy(datePlan = plan)
    }

    /** Swaps what [from] and [to] plan ("move this day"), each as its own date entry. */
    fun swap(profile: AthleteProfile, from: LocalDate, to: LocalDate, today: LocalDate): AthleteProfile {
        if (from == to) return profile
        val a = entry(profile, from) ?: PLAN_FREE
        val b = entry(profile, to) ?: PLAN_FREE
        return withDay(withDay(profile, from, b, today), to, a, today)
    }

    private fun cleaned(plan: Map<String, String>, today: LocalDate): Map<String, String> {
        val oldest = today.minus(DatePeriod(days = KEEP_PAST_DAYS)).toString()
        return plan.filterKeys { it >= oldest }
    }

    // ── The days around the climbing: what the coach will do (preview) ──────────

    /** What a day of the preview stands for; free days say how the coach fits them around the climbing. */
    enum class Hint {
        REST, CLIMB, BOARD, ROUTINE, PAUSED,
        /** The day after a hard or limit day: recovery, no maximal finger work. */
        AFTER_HARD,
        /** The day before a limit day: legs and core, fingers and pulling stay fresh. */
        BEFORE_LIMIT,
        /** The day before a hard day: no finger training. */
        BEFORE_HARD,
        /** Nothing around it: the coach decides on the day (finger strength possible). */
        OPEN,
    }

    data class Day(
        val date: LocalDate,
        /** The entry in force ([entry]); null = free. */
        val entry: String?,
        /** The date has its own entry instead of the standard week's. */
        val overridden: Boolean,
        val hint: Hint,
        /** How hard the climbing of the day is: planned, or logged for today and before. */
        val hardness: ClimbIntensity?,
    )

    enum class WarningKind {
        /** Two hard or limit days in a row: the fingers need about two days. */
        HARD_BACK_TO_BACK,
        /** A planned training with finger work the day before a hard day. */
        FINGERS_BEFORE_HARD,
    }

    /** [date] is the later day of the pair, [other] the earlier. */
    data class Warning(val kind: WarningKind, val date: LocalDate, val other: LocalDate)

    private fun hard(i: ClimbIntensity?) = i == ClimbIntensity.HARD || i == ClimbIntensity.LIMIT

    private fun paused(pauses: List<PausePeriod>, day: LocalDate): Boolean {
        val d = day.toString()
        return pauses.any { p -> p.startDay <= d && p.endDay.let { it == null || it >= d } }
    }

    /**
     * [days] days from [from]: the plan in force and, for free days, how the coach shapes them around the climbing.
     * [logged] is the climbing already logged (intensity, null when unknown) for today and before: it counts over
     * the plan, as the coach's recovery rules do.
     */
    fun preview(
        profile: AthleteProfile, from: LocalDate, days: Int, today: LocalDate,
        pauses: List<PausePeriod> = emptyList(),
        logged: Map<LocalDate, ClimbIntensity?> = emptyMap(),
    ): List<Day> {
        fun hardness(d: LocalDate): ClimbIntensity? {
            if (paused(pauses, d)) return null
            if (d <= today && logged.containsKey(d)) return logged[d] ?: ClimbIntensity.HARD
            if (d < today) return null
            return if (climbingPlanned(profile, d)) climbIntensity(profile, d) ?: ClimbIntensity.HARD else null
        }
        return (0 until days).map { offset ->
            val d = from.plus(DatePeriod(days = offset))
            val e = entry(profile, d)
            val h = hardness(d)
            val hint = when {
                paused(pauses, d) -> Hint.PAUSED
                e == PLAN_REST -> Hint.REST
                isClimb(e) -> Hint.CLIMB
                e == PLAN_BOARD -> Hint.BOARD
                e != null -> Hint.ROUTINE
                climbingPlanned(profile, d) -> Hint.CLIMB
                hard(hardness(d.minus(DatePeriod(days = 1)))) -> Hint.AFTER_HARD
                hardness(d.plus(DatePeriod(days = 1))) == ClimbIntensity.LIMIT -> Hint.BEFORE_LIMIT
                hard(hardness(d.plus(DatePeriod(days = 1)))) -> Hint.BEFORE_HARD
                else -> Hint.OPEN
            }
            Day(d, e, isOverridden(profile, d), hint, h)
        }
    }

    /**
     * What to point out in [days] from today on (advice, nothing is locked): hard days back to back, finger training
     * before a hard day. [fingerWork] says whether a planned entry trains the fingers.
     */
    fun warnings(days: List<Day>, today: LocalDate, fingerWork: (String) -> Boolean): List<Warning> =
        days.zipWithNext().mapNotNull { (a, b) ->
            when {
                b.date < today -> null
                hard(a.hardness) && hard(b.hardness) -> Warning(WarningKind.HARD_BACK_TO_BACK, b.date, a.date)
                a.date >= today && a.hint == Hint.ROUTINE && a.entry != null && hard(b.hardness) && fingerWork(a.entry) ->
                    Warning(WarningKind.FINGERS_BEFORE_HARD, b.date, a.date)
                else -> null
            }
        }

    /**
     * A free day for the training [entry] that had to give way on [from] (owner 2026-10-19: "the rest adjusts"):
     * the first day within a week after it, not before today, with nothing planned and no climbing; a training
     * with finger work also not right before or after a hard day, any other not right before a limit day.
     */
    fun freeDayFor(
        profile: AthleteProfile, from: LocalDate, today: LocalDate, fingerWork: Boolean,
        pauses: List<PausePeriod> = emptyList(),
    ): LocalDate? {
        val start = maxOf(from.plus(DatePeriod(days = 1)), today)
        return preview(profile, start, 7, today, pauses).firstOrNull { d ->
            d.entry == null && d.hint != Hint.CLIMB && d.hint != Hint.PAUSED && when {
                fingerWork -> d.hint == Hint.OPEN
                else -> d.hint != Hint.BEFORE_LIMIT
            }
        }?.date
    }
}
