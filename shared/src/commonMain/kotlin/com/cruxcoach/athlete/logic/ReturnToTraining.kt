package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import kotlinx.datetime.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** Why a gentler re-entry applies. */
enum class ReturnReason {
    /** 10–20 days without training or climbing. */
    BREAK_SHORT,
    /** 21 days or more without training or climbing. */
    BREAK_LONG,
    /** An illness pause of three days or more just ended. */
    AFTER_ILLNESS,
    /** A finger injury healed within the last four weeks: fingers only, everything else as usual. */
    FINGER_INJURY_HEALED,
}

/**
 * A running re-entry ramp. [setsFactor] scales the planned sets of main work
 * (the whole session, or only finger work when [fingerOnly]); [noMaxFinger]
 * keeps maximal finger loading out until the tissue is used to load again.
 */
data class ReturnState(
    val daysOff: Int,
    /** 1-based week of the ramp. */
    val week: Int,
    val setsFactor: Double,
    val noMaxFinger: Boolean,
    val fingerOnly: Boolean,
    val reason: ReturnReason,
    val weeksTotal: Int = 1,
    /** First day of the ramp: the first training after the gap, or today when nothing followed yet. */
    val returnDay: LocalDate? = null,
    /** Training days completed since [returnDay], today excluded. */
    val trainingDaysSinceReturn: Int = 0,
)

/**
 * Coming back after a break, MCI-style but for climbers: whole-body ramps
 * after ten or 21 days off (or a longer illness), and a finger-specific
 * re-entry after a healed finger injury. Rest stays rest: an open pause or an
 * active injury is handled by the pause and injury modes, not here.
 */
object ReturnToTraining {

    const val SHORT_BREAK_DAYS = 10
    const val LONG_BREAK_DAYS = 21
    /** Completed training days after which a break ramp has done its job. */
    const val DAYS_TO_END_RAMP = 4
    /** An illness pause shorter than this is a cold, not a break. */
    const val MIN_ILLNESS_DAYS = 3
    const val LONG_ILLNESS_DAYS = 7
    const val FINGER_REENTRY_DAYS = 28

    /**
     * The ramp for [today], or null when none applies.
     *
     * @param maxFingerBefore max-finger work was part of the training before
     *   the break; only then does the second week of a long ramp allow it again.
     */
    fun state(
        activities: Map<String, DayActivity>,
        pauses: List<PausePeriod>,
        injuries: List<Injury>,
        today: LocalDate,
        maxFingerBefore: Boolean = false,
    ): ReturnState? {
        // Still paused (ill, injured, on holiday): nothing to ramp yet.
        if (pauses.any { it.endDay == null && parse(it.startDay)?.let { s -> s <= today } == true }) return null
        val trainedDays = activities.values.filter { it.trained }
            .mapNotNull { parse(it.day) }.filter { it <= today }.distinct().sorted()
        val breakRamp = breakRamp(trainedDays, today, maxFingerBefore) ?: illnessRamp(pauses, trainedDays, today, maxFingerBefore)
        val fingerRamp = fingerRamp(injuries, today)
        return when {
            breakRamp == null -> fingerRamp
            fingerRamp == null -> breakRamp
            // Both: the whole-body ramp, and fingers stay off the limit regardless of its week.
            else -> breakRamp.copy(noMaxFinger = true)
        }
    }

    // ── Helpers for the integrator ───────────────────────────────────

    /** Whether the ramp touches work of [def] at all (a finger-only ramp leaves the rest alone). */
    fun applies(state: ReturnState?, def: ExerciseDefinition): Boolean =
        state != null && (!state.fingerOnly || isFingerWork(def))

    fun allowsMaxFinger(state: ReturnState?): Boolean = state?.noMaxFinger != true

    /** Planned sets of main work under the ramp; warm-ups and tests stay as planned. */
    fun scaleSets(sets: Int, state: ReturnState?, def: ExerciseDefinition, warmupOrTest: Boolean = false): Int {
        if (state == null || warmupOrTest || !applies(state, def)) return sets
        return max(1, (sets * state.setsFactor).roundToInt())
    }

    /**
     * A routine item under the ramp, or null when it has to go (maximal
     * finger work while the ramp keeps fingers off the limit).
     */
    fun applyToItem(item: RoutineItem, def: ExerciseDefinition, state: ReturnState?): RoutineItem? {
        if (state == null || item.warmup || item.test) return item
        if (state.noMaxFinger && ReadinessModifiers.isMaxFinger(def)) return null
        return item.copy(sets = scaleSets(item.sets, state, def))
    }

    fun isFingerWork(def: ExerciseDefinition): Boolean = LoadDomain.FINGER in def.domains

    /**
     * Whether maximal finger work was part of the training in the [windowDays]
     * before [before] — the `maxFingerBefore` input of [state]. [sets] carry
     * the day they belong to.
     */
    fun maxFingerBefore(
        sets: List<Pair<LocalDate, ExerciseSet>>,
        catalog: ExerciseCatalog,
        before: LocalDate,
        windowDays: Int = 90,
    ): Boolean = sets.any { (day, set) ->
        day < before && daysBetween(day, before) <= windowDays && set.isCompleted && set.setType == SetType.WORK &&
            ReadinessModifiers.isMaxFinger(catalog.fallbackFor(set.exerciseSlug))
    }

    // ── Ramps ────────────────────────────────────────────────────────

    private fun breakRamp(sorted: List<LocalDate>, today: LocalDate, maxFingerBefore: Boolean): ReturnState? {
        if (sorted.isEmpty()) return null // a new athlete has nothing to return to
        val beforeToday = sorted.filter { it < today }
        val last = beforeToday.lastOrNull()
        // Nothing done since a long gap: today is the first day back.
        if (last != null) {
            val off = daysBetween(last, today) - 1
            if (off >= SHORT_BREAK_DAYS && today !in sorted) return ramp(off, today, today, 0, maxFingerBefore, breakReason(off))
        }
        // Training resumed: the most recent gap long enough to call a break.
        for (i in sorted.indices.reversed()) {
            if (i == 0) break
            val off = daysBetween(sorted[i - 1], sorted[i]) - 1
            if (off >= SHORT_BREAK_DAYS) {
                val returnDay = sorted[i]
                val done = sorted.count { it >= returnDay && it < today }
                return ramp(off, returnDay, today, done, maxFingerBefore, breakReason(off))
            }
        }
        return null
    }

    private fun illnessRamp(pauses: List<PausePeriod>, trained: List<LocalDate>, today: LocalDate, maxFingerBefore: Boolean): ReturnState? {
        val ill = pauses.filter { it.reason == PauseReason.ILLNESS && it.endDay != null }
            .mapNotNull { p -> parse(p.startDay)?.let { s -> parse(p.endDay!!)?.let { e -> s to e } } }
            .filter { (_, end) -> end < today }
            .maxByOrNull { it.second } ?: return null
        val (start, end) = ill
        val length = daysBetween(start, end) + 1
        if (length < MIN_ILLNESS_DAYS) return null
        val returnDay = trained.filter { it > end }.minOrNull() ?: today
        val done = trained.count { it >= returnDay && it < today }
        val weeks = if (length >= LONG_ILLNESS_DAYS) 2 else 1
        return ramp(length, returnDay, today, done, maxFingerBefore, ReturnReason.AFTER_ILLNESS, weeks)
    }

    private fun fingerRamp(injuries: List<Injury>, today: LocalDate): ReturnState? {
        // An injury still open is the injury mode's job.
        if (injuries.any { it.isActive && it.region == InjuryRegion.FINGER }) return null
        val healed = injuries.filter { it.region == InjuryRegion.FINGER }
            .mapNotNull { it.resolvedOn?.let(::parse) }
            .filter { it <= today && daysBetween(it, today) < FINGER_REENTRY_DAYS }
            .maxOrNull() ?: return null
        val since = daysBetween(healed, today)
        return ReturnState(
            daysOff = since, week = since / 7 + 1, setsFactor = 1.0, noMaxFinger = true, fingerOnly = true,
            reason = ReturnReason.FINGER_INJURY_HEALED, weeksTotal = FINGER_REENTRY_DAYS / 7, returnDay = healed,
        )
    }

    private fun ramp(
        daysOff: Int,
        returnDay: LocalDate,
        today: LocalDate,
        done: Int,
        maxFingerBefore: Boolean,
        reason: ReturnReason,
        weeksOverride: Int? = null,
    ): ReturnState? {
        val weeks = weeksOverride ?: if (reason == ReturnReason.BREAK_LONG) 2 else 1
        val week = daysBetween(returnDay, today) / 7 + 1
        if (week > weeks || done >= DAYS_TO_END_RAMP) return null
        val (factor, noMax) = when {
            weeks == 1 -> 0.7 to true
            week == 1 -> 0.6 to true
            else -> 0.8 to !maxFingerBefore
        }
        return ReturnState(daysOff, week, factor, noMax, fingerOnly = false, reason = reason, weeksTotal = weeks,
            returnDay = returnDay, trainingDaysSinceReturn = done)
    }

    private fun breakReason(off: Int) = if (off >= LONG_BREAK_DAYS) ReturnReason.BREAK_LONG else ReturnReason.BREAK_SHORT

    private fun daysBetween(a: LocalDate, b: LocalDate): Int = (b.toEpochDays() - a.toEpochDays()).toInt()

    private fun parse(day: String): LocalDate? = runCatching { LocalDate.parse(day.take(10)) }.getOrNull()
}
