package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.PausePeriod
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Training days in one Monday-based week. */
data class WeekStat(val weekStart: LocalDate, val trainingDays: Int, val pausedDays: Int)

data class StreakState(
    /** Consecutive weeks on plan, including the current one once it is reached. */
    val weeks: Int,
    val jokers: Int,
    val currentWeekDays: Int,
    val goal: Int,
    val currentWeekDone: Boolean,
    /** The current week is (mostly) inside an illness/injury/holiday pause. */
    val currentWeekPaused: Boolean,
)

/**
 * Weekly consistency instead of a daily streak: climbers rest on purpose,
 * so rest days never break anything. A week counts when the weekly goal of
 * training days is reached. Weeks mostly spent ill, injured or on holiday
 * freeze the streak, and every four good weeks earn a joker (max. two) that
 * silently protects one missed week.
 */
object ConsistencyStreak {

    private const val PAUSE_FREEZE_DAYS = 4
    private const val WEEKS_PER_JOKER = 4
    private const val MAX_JOKERS = 2

    fun compute(weeks: List<WeekStat>, goal: Int): StreakState {
        val target = goal.coerceIn(1, 7)
        if (weeks.isEmpty()) return StreakState(0, 0, 0, target, false, false)
        val sorted = weeks.sortedBy { it.weekStart }
        var streak = 0
        var jokers = 0
        var goodRun = 0
        for (week in sorted.dropLast(1)) {
            when {
                week.trainingDays >= target -> {
                    streak++; goodRun++
                    if (goodRun % WEEKS_PER_JOKER == 0 && jokers < MAX_JOKERS) jokers++
                }
                week.pausedDays >= PAUSE_FREEZE_DAYS -> Unit
                jokers > 0 -> jokers--
                else -> { streak = 0; goodRun = 0 }
            }
        }
        val current = sorted.last()
        val done = current.trainingDays >= target
        return StreakState(
            weeks = if (done) streak + 1 else streak,
            jokers = jokers,
            currentWeekDays = current.trainingDays,
            goal = target,
            currentWeekDone = done,
            currentWeekPaused = current.pausedDays >= PAUSE_FREEZE_DAYS,
        )
    }

    fun weekStart(day: LocalDate): LocalDate = day.minus(DatePeriod(days = day.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal))

    /** Builds [WeekStat]s for the [weekCount] weeks ending with the week of [today]. */
    fun weeksFrom(trainingDays: Set<LocalDate>, pauses: List<PausePeriod>, today: LocalDate, weekCount: Int): List<WeekStat> {
        val currentStart = weekStart(today)
        return (weekCount - 1 downTo 0).map { back ->
            val start = currentStart.minus(DatePeriod(days = back * 7))
            val days = (0 until 7).map { start.plus(DatePeriod(days = it)) }
            WeekStat(
                weekStart = start,
                trainingDays = days.count { it in trainingDays },
                pausedDays = days.count { d -> pauses.any { it.covers(d, today) } },
            )
        }
    }

    private fun PausePeriod.covers(day: LocalDate, today: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(startDay) }.getOrNull() ?: return false
        val end = endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        return day >= start && day <= end
    }
}

/** Days per load domain in the last week vs. the three weeks before. */
data class DomainLoad(val domain: LoadDomain, val lastWeekDays: Int, val previousWeeklyAverage: Double) {
    /** A jump worth mentioning — information, not a ban. */
    val spike: Boolean get() = lastWeekDays >= 3 && lastWeekDays >= previousWeeklyAverage * 1.5 + 0.5
}

/**
 * Weekly load per structure (fingers, shoulders, …) from board days and
 * logged sets. Counts days, not volume: for connective tissue the number of
 * loading days is the robust, explainable signal.
 */
object LoadBalance {

    /** Domains a board/wall climbing day loads. */
    val CLIMBING_DOMAINS = setOf(LoadDomain.FINGER, LoadDomain.SKIN, LoadDomain.SHOULDER, LoadDomain.ELBOW)

    fun compute(
        today: LocalDate,
        climbingDays: Set<LocalDate>,
        sets: List<Pair<LocalDate, ExerciseSet>>,
        catalog: ExerciseCatalog,
    ): List<DomainLoad> {
        val perDomain = mutableMapOf<LoadDomain, MutableSet<LocalDate>>()
        climbingDays.forEach { d -> CLIMBING_DOMAINS.forEach { perDomain.getOrPut(it) { mutableSetOf() } += d } }
        sets.forEach { (day, set) ->
            if (!set.isCompleted) return@forEach
            catalog.fallbackFor(set.exerciseSlug).domains.forEach { perDomain.getOrPut(it) { mutableSetOf() } += day }
        }
        val lastWeekStart = today.minus(DatePeriod(days = 6))
        val earlierStart = today.minus(DatePeriod(days = 27))
        return LoadDomain.entries.map { domain ->
            val days = perDomain[domain].orEmpty()
            val last = days.count { it >= lastWeekStart && it <= today }
            val earlier = days.count { it >= earlierStart && it < lastWeekStart }
            DomainLoad(domain, last, earlier / 3.0)
        }
    }
}
