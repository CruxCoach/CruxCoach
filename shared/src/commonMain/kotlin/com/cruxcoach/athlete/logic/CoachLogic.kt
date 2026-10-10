package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FingerPreference
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import kotlin.math.abs
import kotlin.math.min

/** How personal the coach's suggestions can be with what it knows (FEAT-071). */
enum class CoachLevel { BASIS, PERSONAL, PRECISE }

/** One piece the coach is still missing, most useful first. */
enum class CoachStep { GOAL, WEEK, EQUIPMENT, START_VALUE_PULL, START_VALUE_FINGER, EXPERIENCE, PREFERENCES, GRADE }

data class Completeness(
    val level: CoachLevel,
    /** 0–100. */
    val score: Int,
    /** Missing steps, most useful first. */
    val missing: List<CoachStep>,
) {
    val next: CoachStep? get() = missing.firstOrNull()
}

object CoachLogic {

    val PULL_SLUGS = setOf("pull.pull_up", "pull.weighted_pull_up")
    val FINGER_SLUGS = setOf("finger.max_hang", "finger.one_arm_pickup", "finger.two_arm_pickup")

    private val WEIGHTS = mapOf(
        CoachStep.GOAL to 15, CoachStep.WEEK to 15, CoachStep.EQUIPMENT to 10,
        CoachStep.START_VALUE_PULL to 15, CoachStep.START_VALUE_FINGER to 15,
        CoachStep.EXPERIENCE to 15, CoachStep.PREFERENCES to 10, CoachStep.GRADE to 5,
    )

    /**
     * What is known and what would help next. Finger start values are not
     * asked from climbers the guardrails keep away from max finger load
     * (growing, under a year of climbing) — their weight counts as done.
     */
    fun completeness(profile: AthleteProfile, benchmarks: List<Benchmark>, logbook: LogbookSummary): Completeness {
        val c = profile.coach
        val slugs = benchmarks.map { it.exerciseSlug }.toSet()
        val fingerAsked = fingerMaxAllowed(c)
        val done = buildSet {
            if (c.goal != null) add(CoachStep.GOAL)
            if (c.climbingDays.isNotEmpty() || c.trainingDaysPerWeek != null) add(CoachStep.WEEK)
            if (profile.equipmentConfigured) add(CoachStep.EQUIPMENT)
            if (slugs.any { it in PULL_SLUGS }) add(CoachStep.START_VALUE_PULL)
            if (!fingerAsked || slugs.any { it in FINGER_SLUGS }) add(CoachStep.START_VALUE_FINGER)
            if (c.experience != null || c.ageBand != null) add(CoachStep.EXPERIENCE)
            if (c.focus.isNotEmpty() || c.fingerPreference != null || c.intensityStyle != null || c.variety != null) add(CoachStep.PREFERENCES)
            if (c.currentGrade != null || logbook.hasGrades) add(CoachStep.GRADE)
        }
        val score = done.sumOf { WEIGHTS[it] ?: 0 }.coerceIn(0, 100)
        val missing = CoachStep.entries.filter { it !in done }
        val bothValues = CoachStep.START_VALUE_PULL in done && CoachStep.START_VALUE_FINGER in done
        val level = when {
            score >= 80 && bothValues -> CoachLevel.PRECISE
            score >= 40 -> CoachLevel.PERSONAL
            else -> CoachLevel.BASIS
        }
        return Completeness(level, score, missing)
    }

    /** Max hangs and campus work only after enough climbing and not while growing. */
    fun fingerMaxAllowed(c: CoachProfile): Boolean =
        c.ageBand != AgeBand.UNDER_16 && c.ageBand != AgeBand.Y16_17 &&
            c.experience != ExperienceBand.UNDER_1 && c.experience != ExperienceBand.Y1_2

    /** Masters athletes get more recovery between finger days. */
    fun needsLongerRecovery(c: CoachProfile): Boolean = c.ageBand == AgeBand.Y40_54 || c.ageBand == AgeBand.Y55_PLUS

    /** CoachGoal → the engine's broader AthleteGoal (weight loss is never set from here). */
    fun athleteGoalFor(goal: CoachGoal): com.cruxcoach.athlete.model.AthleteGoal = when (goal) {
        CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT, CoachGoal.EVENT -> com.cruxcoach.athlete.model.AthleteGoal.PERFORM
        CoachGoal.BUILD_STRENGTH -> com.cruxcoach.athlete.model.AthleteGoal.BUILD_STRENGTH
        CoachGoal.STAY_HEALTHY, CoachGoal.COMEBACK -> com.cruxcoach.athlete.model.AthleteGoal.MAINTAIN
    }
}

/**
 * Proposes a standard week from the coach answers and the logbook's real
 * rhythm: the usual climbing days become board days, off-wall sessions go to
 * the days furthest from climbing, one mobility day if there is room, the
 * rest are rest days. The athlete confirms or edits — nothing is applied
 * without a tap.
 */
object WeekPlanSuggester {

    fun suggest(
        coach: CoachProfile,
        logbook: LogbookSummary,
        routines: List<Routine>,
        equipment: Set<EquipmentV2>,
        injuries: List<Injury>,
    ): Map<Int, String> {
        val active = injuries.filter { it.isActive }
        val climbingPaused = active.any { it.climbingPaused }
        val usual = coach.climbingDays.ifEmpty { logbook.usualClimbingDays }.filter { it in 1..7 }.toSortedSet()
        val climbing: Set<Int> = if (climbingPaused) emptySet() else usual
        val total = (coach.trainingDaysPerWeek ?: defaultTotal(usual.size)).coerceIn(1, 7)
        val offWallCount = if (climbingPaused) total.coerceAtMost(5)
            else (total - climbing.size).coerceIn(0, 7 - climbing.size)

        // Off-wall days as far as possible from climbing days and from each other.
        val chosen = mutableListOf<Int>()
        repeat(offWallCount) {
            val pick = (1..7).filter { it !in climbing && it !in chosen }
                .maxWithOrNull(compareBy<Int> { d -> distanceTo(d, climbing + chosen) }.thenBy { -it })
                ?: return@repeat
            chosen += pick
        }
        chosen.sort()

        val rotation = rotation(coach, routines, equipment, active, climbingPaused)
        val plan = mutableMapOf<Int, String>()
        climbing.forEach { plan[it] = PLAN_BOARD }
        var finger = 0
        chosen.forEachIndexed { index, day ->
            // No max finger work the day after a climbing day.
            val afterClimbing = ((day + 5) % 7 + 1) in climbing
            var entry = rotation[index % rotation.size]
            if (entry == FINGER && (afterClimbing || finger > 0)) entry = rotation.firstOrNull { it != FINGER } ?: PULL
            if (entry == FINGER) finger++
            plan[day] = entry
        }
        val free = (1..7).filter { it !in plan }
        val mobility = free.size >= 2 && (FocusArea.MOBILITY in coach.focus || CoachLogic.needsLongerRecovery(coach) || free.size >= 3)
        if (mobility) {
            // Mobility on the free day closest after the hardest cluster, i.e. the last climbing day.
            val anchor = climbing.maxOrNull() ?: chosen.maxOrNull() ?: 7
            val day = free.minBy { (it - anchor + 7) % 7 }
            plan[day] = MOBILITY
        }
        (1..7).filter { it !in plan }.forEach { plan[it] = PLAN_REST }
        return plan.toSortedMap()
    }

    private const val FINGER = "builtin:" + BuiltinRoutines.FINGER_BASICS
    private const val PULL = "builtin:" + BuiltinRoutines.PULL_ANTAGONIST
    private const val LEGS = "builtin:" + BuiltinRoutines.LEGS_BASICS
    private const val CORE = "builtin:" + BuiltinRoutines.CORE_CLIMBER
    private const val MOBILITY = "builtin:" + BuiltinRoutines.MOBILITY_10
    private const val ANTAGONIST = "builtin:" + BuiltinRoutines.ANTAGONIST_15
    private const val INJURY = "builtin:" + BuiltinRoutines.INJURY_ONE_ARM

    private fun defaultTotal(climbingDays: Int): Int = when {
        climbingDays == 0 -> 3
        climbingDays <= 2 -> climbingDays + 2
        else -> climbingDays + 1
    }

    /** Circular weekday distance to the nearest day in [days]; 7 when there is none. */
    private fun distanceTo(day: Int, days: Collection<Int>): Int =
        days.minOfOrNull { d -> abs(d - day).let { min(it, 7 - it) } } ?: 7

    private fun rotation(
        coach: CoachProfile,
        routines: List<Routine>,
        equipment: Set<EquipmentV2>,
        injuries: List<Injury>,
        climbingPaused: Boolean,
    ): List<String> {
        if (climbingPaused) {
            val fingerHurt = injuries.any { it.region == InjuryRegion.FINGER || it.region == InjuryRegion.WRIST }
            return listOfNotNull(INJURY.takeIf { fingerHurt || EquipmentV2.satisfied(EquipmentV2.PICKUP_BLOCK, equipment) }, PULL, LEGS, CORE)
                .distinct()
        }
        val fingerOk = CoachLogic.fingerMaxAllowed(coach) && coach.fingerPreference != FingerPreference.NONE &&
            (EquipmentV2.HANGBOARD in equipment) && injuries.none { it.region == InjuryRegion.FINGER }
        val list = mutableListOf<String>()
        // The athlete's own standard workout comes first.
        routines.maxByOrNull { it.updatedAt }?.let { list += it.id }
        val focus = coach.focus
        if (fingerOk && (FocusArea.FINGER_STRENGTH in focus || focus.isEmpty() && coach.goal in setOf(CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT, CoachGoal.EVENT, null))) {
            list += FINGER
        }
        if (FocusArea.PULL_STRENGTH in focus || FocusArea.POWER in focus || focus.isEmpty()) list += PULL
        if (FocusArea.CORE in focus) list += CORE
        if (FocusArea.PREVENTION in focus) list += ANTAGONIST
        if (FocusArea.MOBILITY in focus) list += MOBILITY
        list += listOf(LEGS, CORE, PULL)
        return list.distinct()
    }
}

/** Where the coach stands with one exercise's starting value (the "wird gelernt" badge). */
object LearningState {

    /** Logged sessions after which a value counts as learnt (MCI: the first week). */
    const val SESSIONS_TO_LEARN = 2

    sealed interface State {
        /** Nothing known yet; the first session starts careful. */
        data object None : State
        /** Only a quick self-estimate; the first logged set replaces it. */
        data object Estimated : State
        data class Learning(val sessions: Int, val needed: Int) : State
        data object Known : State
    }

    fun of(slug: String, benchmarks: List<Benchmark>, workSessionCount: Int): State {
        val own = benchmarks.filter { it.exerciseSlug == slug }
        return when {
            own.any { it.source == BenchmarkSource.MANUAL || it.source == BenchmarkSource.TEST } -> State.Known
            workSessionCount >= SESSIONS_TO_LEARN -> State.Known
            workSessionCount > 0 -> State.Learning(workSessionCount, SESSIONS_TO_LEARN)
            own.any { it.source == BenchmarkSource.ESTIMATE } -> State.Estimated
            own.isNotEmpty() -> State.Known
            else -> State.None
        }
    }
}
