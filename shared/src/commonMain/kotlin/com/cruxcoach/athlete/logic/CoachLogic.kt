package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingContext
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FingerPreference
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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
            if (c.focus.isNotEmpty() || c.fingerPreference != null || c.intensityStyle != null || c.variety != null ||
                c.exerciseChoice != null) add(CoachStep.PREFERENCES)
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
 *
 * The same week can be planned around what the athlete already decided ([around], [forDays]): the day planner shows
 * it for the days left open (owner 2026-11-22: "what the coach has in mind", not "the coach decides").
 *
 * The athlete's level shapes the week as it shapes the recovery rules ([DayPlans.recoveryTier]): an advanced
 * climber's finger day may follow a hard climbing day (only a limit day keeps it a day away), an elite climber's
 * any, and an elite week holds up to [ELITE_FINGER_DAYS] finger days. The day before a climbing day stays free of
 * finger work at every level, as on today's card.
 */
object WeekPlanSuggester {

    /** What the athlete decided for one week; the coach plans the other days around it ([around]). */
    data class Fixed(
        /** Weekday (1 = Monday) to the athlete's own entry: a workout, a climbing or board day, a rest day. */
        val entries: Map<Int, String> = emptyMap(),
        /** Usual climbing weekdays the athlete freed this week: no climbing is proposed there, anything else is. */
        val freed: Set<Int> = emptySet(),
        /** Weekdays within a pause: nothing is proposed there; the other days stay what they would be without it. */
        val paused: Set<Int> = emptySet(),
    )

    /** A plan entry is a training: not a rest day, not climbing, not the short mobility session. */
    fun isTraining(entry: String): Boolean =
        entry != PLAN_REST && entry != PLAN_FREE && entry != PLAN_BOARD && entry != MOBILITY && !DayPlans.isClimb(entry)

    /** Finger days in an elite climber's week, the athlete's own and the coach's together ([WeeklyVolume.targets]). */
    const val ELITE_FINGER_DAYS = 3

    fun suggest(
        coach: CoachProfile,
        logbook: LogbookSummary,
        routines: List<Routine>,
        equipment: Set<EquipmentV2>,
        injuries: List<Injury>,
        tier: DayPlans.Tier = DayPlans.Tier.STANDARD,
    ): Map<Int, String> = around(coach, logbook, routines, equipment, injuries, Fixed(), tier) { false }

    /**
     * The week for the days [fixed] leaves open, planned around the athlete's own days: his climbing days count as
     * climbing days, his trainings as training days of the week (so the coach proposes fewer of its own, and none he
     * already planned), max finger work keeps a day's distance from every climbing day, and there is one finger day
     * a week, his or the coach's ([fingerWork] says which of his entries trains the fingers). Without anything fixed
     * this is [suggest].
     *
     * [tier] ([DayPlans.recoveryTier]) loosens this where today's card does: an advanced climber's finger day may
     * follow a climbing day unless it was his limit day, an elite climber's any, and an elite week holds up to
     * [ELITE_FINGER_DAYS] finger days – at most every second training, never two days in a row. The day before a
     * climbing day takes no finger day at any level – the card keeps the fingers fresh for it. Where none of the
     * coach's training days takes the finger day but a free day does, a training moves there.
     */
    fun around(
        coach: CoachProfile,
        logbook: LogbookSummary,
        routines: List<Routine>,
        equipment: Set<EquipmentV2>,
        injuries: List<Injury>,
        fixed: Fixed,
        tier: DayPlans.Tier = DayPlans.Tier.STANDARD,
        fingerWork: (String) -> Boolean,
    ): Map<Int, String> {
        val active = injuries.filter { it.isActive }
        val climbingPaused = active.any { it.climbingPaused }
        val usual = coach.climbingDays.ifEmpty { logbook.usualClimbingDays }.filter { it in 1..7 }.toSortedSet()
        val own = fixed.entries.filter { (day, entry) -> day in 1..7 && entry != PLAN_FREE }
        val taken: Set<Int> = own.keys
        val ownClimbing: Set<Int> = own.filterValues { it == PLAN_BOARD || DayPlans.isClimb(it) }.keys
        // A mobility day of his own is no training day of the week, as the coach's own is none.
        val ownTraining: Map<Int, String> = own.filterValues { isTraining(it) }
        // The climbing days the coach counts on: the usual ones the athlete left as they are.
        val proposed: Set<Int> = if (climbingPaused) emptySet() else usual.filter { it !in taken && it !in fixed.freed }.toSet()
        val climbing: Set<Int> = proposed + ownClimbing
        val total = (coach.trainingDaysPerWeek ?: defaultTotal(usual.size)).coerceIn(1, 7)
        val offWallCount = if (climbingPaused) total.coerceAtMost(5)
            else (total - climbing.size).coerceIn(0, 7 - climbing.size)
        val open = (1..7).filter { it !in climbing && it !in taken }

        // Off-wall days as far as possible from climbing days, from the athlete's own trainings and from each other.
        val chosen = mutableListOf<Int>()
        repeat((offWallCount - ownTraining.size).coerceIn(0, open.size)) {
            val pick = open.filter { it !in chosen }
                .maxWithOrNull(compareBy<Int> { d -> distanceTo(d, climbing + ownTraining.keys + chosen) }.thenBy { -it })
                ?: return@repeat
            chosen += pick
        }
        chosen.sort()

        val all = rotation(coach, routines, equipment, active, climbingPaused)
        // What the athlete planned himself is not proposed a second time. One finger day a week, his or the coach's;
        // up to three at the elite level (one in a comeback, as in the weekly targets).
        val elite = tier == DayPlans.Tier.ELITE
        val ownFingerDays: Set<Int> = ownTraining.filterValues { fingerWork(it) }.keys
        val fingerDays = ((if (elite && coach.goal != CoachGoal.COMEBACK) ELITE_FINGER_DAYS else 1) - ownFingerDays.size).coerceAtLeast(0)
        val left = all.filter { if (it == FINGER) fingerDays > 0 && (elite || it !in ownTraining.values) else it !in ownTraining.values }
        val other = left.firstOrNull { it != FINGER } ?: all.firstOrNull { it != FINGER } ?: PULL
        val rotation = left.ifEmpty { listOf(other) }
        val plan = mutableMapOf<Int, String>()
        val climbingEntry = climbingEntry(coach, logbook, equipment)
        proposed.forEach { plan[it] = climbingEntry }
        fun before(day: Int) = (day + 5) % 7 + 1
        fun after(day: Int) = day % 7 + 1
        val entries: List<String> = if (tier == DayPlans.Tier.STANDARD) {
            var finger = 0
            val list = chosen.indices.map { index ->
                var entry = rotation[index % rotation.size]
                if (entry == FINGER && finger > 0) entry = other
                if (entry == FINGER) finger++
                entry
            }.toMutableList()
            // No max finger work the day after a climbing day, and none the day before one: the day planner warns
            // about exactly that, so the coach's own week must not start with it (phone check 2026-11-21). The finger
            // day moves to an off-wall day that fits; only without one it gives way (review 2026-11-21: it was dropped
            // although Saturday was free).
            fun fingerFits(day: Int) = before(day) !in climbing && after(day) !in climbing
            val at = list.indexOf(FINGER)
            if (at >= 0 && !fingerFits(chosen[at])) {
                val to = chosen.indices.firstOrNull { it != at && fingerFits(chosen[it]) }
                if (to == null) list[at] = other else { list[at] = list[to]; list[to] = FINGER }
            }
            list
        } else {
            // An advanced or elite climber: the finger days go where today's card would train them. The day before a
            // climbing day it keeps the fingers fresh at every level; the day after one it holds them back only as
            // the level's recovery asks ([ClimbingLoad.recoveryHours]: advanced after a limit day, elite not at all);
            // and never on two days in a row, the athlete's own finger work included.
            val heldBack: Set<Int> = if (elite) emptySet() else own.filterValues { DayPlans.climbOf(it)?.intensity == ClimbIntensity.LIMIT }.keys
            val fingerAt = ownFingerDays.toMutableSet()
            fun fits(day: Int) = after(day) !in climbing && before(day) !in heldBack && before(day) !in fingerAt && after(day) !in fingerAt
            // The athlete's own routine leads the week, as at every level; after it a finger day where one fits – at
            // most every second training, so the others keep their place – and the rest in the rotation's order.
            val lead = rotation.first().takeIf { it != FINGER }
            val others = rotation.filter { it != FINGER }.ifEmpty { listOf(other) }
            var next = 0
            var placed = 0
            val list = mutableListOf<String>()
            chosen.forEachIndexed { index, day ->
                val finger = FINGER in rotation && placed < fingerDays && !(index == 0 && lead != null) &&
                    list.lastOrNull() != FINGER && fits(day)
                val entry = if (finger) FINGER else others[next++ % others.size]
                if (entry == FINGER || fingerWork(entry)) { fingerAt += day; placed++ }
                list += entry
            }
            // None of the coach's training days takes a finger day, but a free day does: the last training moves there.
            if (FINGER in rotation && fingerDays > 0 && placed == 0) {
                val free = open.firstOrNull { it !in chosen && fits(it) }
                val from = chosen.indices.lastOrNull { !(it == 0 && lead != null) }
                if (free != null && from != null) { chosen[from] = free; list[from] = FINGER }
            }
            list
        }
        chosen.forEachIndexed { index, day -> plan[day] = entries[index] }
        val free = (1..7).filter { it !in plan && it !in taken }
        val mobility = MOBILITY !in own.values && free.size >= 2 &&
            (FocusArea.MOBILITY in coach.focus || CoachLogic.needsLongerRecovery(coach) || free.size >= 3)
        if (mobility) {
            // Mobility on the free day closest after the hardest cluster, i.e. the last climbing day.
            val anchor = climbing.maxOrNull() ?: chosen.maxOrNull() ?: 7
            val day = free.minBy { (it - anchor + 7) % 7 }
            plan[day] = MOBILITY
        }
        free.filter { it !in plan }.forEach { plan[it] = PLAN_REST }
        // A pause takes its days out; it does not push the week's trainings into the days after it.
        return plan.filterKeys { it !in fixed.paused }.toSortedMap()
    }

    /**
     * The coach's proposal for every day of [days] the athlete left open (a [DayPlans.preview]): each calendar week
     * is planned around what the athlete planned in it himself ([around]) – the standard week and single dates
     * alike. Next to a hard day the planner's own advice then decides ([DayPlans.Hint]; it knows what was logged and
     * looks across the week's end): no finger work the day before or after a hard day, legs and core before a limit
     * day. A training that does not fit its day changes places with one of the same week that does; only without
     * one it gives way. [tier] is the level [days] were previewed with ([DayPlans.recoveryTier]): the hints already
     * follow it, and so does the week ([around]); above the standard level a finger day also never follows another,
     * across the week's end either.
     * A proposal is the plan the coach would write; on the day itself today's card still goes by form and recovery.
     */
    fun forDays(
        profile: AthleteProfile,
        logbook: LogbookSummary,
        routines: List<Routine>,
        injuries: List<Injury>,
        days: List<DayPlans.Day>,
        pauses: List<PausePeriod> = emptyList(),
        tier: DayPlans.Tier = DayPlans.Tier.STANDARD,
        fingerWork: (String) -> Boolean,
    ): Map<LocalDate, String> {
        fun mondayOf(date: LocalDate) = date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))
        val weeks = mutableMapOf<LocalDate, Map<Int, String>>()
        fun week(monday: LocalDate): Map<Int, String> = weeks.getOrPut(monday) {
            val entries = mutableMapOf<Int, String>()
            val freed = mutableSetOf<Int>()
            val paused = mutableSetOf<Int>()
            for (weekday in 1..7) {
                val date = monday.plus(DatePeriod(days = weekday - 1))
                val entry = DayPlans.entry(profile, date)
                if (entry != null) entries[weekday] = entry else if (DayPlans.isOverridden(profile, date)) freed += weekday
                if (DayPlans.paused(pauses, date)) paused += weekday
            }
            around(profile.coach, logbook, routines, profile.equipment, injuries, Fixed(entries, freed, paused), tier, fingerWork)
        }
        val hints = days.associate { it.date to it.hint }
        val result = mutableMapOf<LocalDate, String>()
        for (day in days) {
            if (day.entry != null || day.hint == DayPlans.Hint.PAUSED) continue
            val proposal = week(mondayOf(day.date))[day.date.dayOfWeek.isoDayNumber] ?: continue
            result[day.date] = proposal
        }
        // What stands on a day now: the athlete's own entry, else the proposal.
        fun standing(date: LocalDate): String? =
            DayPlans.entry(profile, date) ?: result[date] ?: week(mondayOf(date))[date.dayOfWeek.isoDayNumber]
        fun climbing(date: LocalDate): Boolean = standing(date).let { it == PLAN_BOARD || DayPlans.isClimb(it) }
        fun fingerDay(date: LocalDate): Boolean = standing(date)?.let { fingerWork(it) } == true
        val several = tier != DayPlans.Tier.STANDARD
        // What the planner advises for the day.
        fun advised(date: LocalDate, entry: String): Boolean = !isTraining(entry) || when (hints[date]) {
            DayPlans.Hint.BEFORE_LIMIT -> entry == LEGS || entry == CORE
            DayPlans.Hint.AFTER_HARD, DayPlans.Hint.BEFORE_HARD -> !fingerWork(entry)
            else -> true
        }
        // Finger work that moves keeps the week's rules ([around]): never the day before a climbing day, planned or
        // proposed; at the standard level not the day after one either; above it not next to another finger day
        // ([partner] is the day it changes places with).
        fun clear(date: LocalDate, entry: String, partner: LocalDate): Boolean {
            if (!fingerWork(entry)) return true
            val yesterday = date.minus(DatePeriod(days = 1))
            val tomorrow = date.plus(DatePeriod(days = 1))
            if (climbing(tomorrow) || (!several && climbing(yesterday))) return false
            return !several || listOf(yesterday, tomorrow).none { it != partner && fingerDay(it) }
        }
        for (day in days) {
            val date = day.date
            val entry = result[date] ?: continue
            // Above the standard level a week can hold several finger days: none follows another.
            val follows = several && fingerWork(entry) && fingerDay(date.minus(DatePeriod(days = 1)))
            if (advised(date, entry) && !follows) continue
            val other = result.keys.firstOrNull { o ->
                val there = result.getValue(o)
                o != date && mondayOf(o) == mondayOf(date) && isTraining(there) &&
                    advised(date, there) && clear(date, there, o) && advised(o, entry) && clear(o, entry, date)
            }
            if (other != null) {
                result[date] = result.getValue(other)
                result[other] = entry
            } else {
                result[date] = if (day.hint == DayPlans.Hint.BEFORE_LIMIT) LEGS else PULL
            }
        }
        return result
    }

    /**
     * What a usual climbing day is planned as: a board session for those who climb on boards (a board at the place, a
     * board named in the setup, or board sessions in the logbook), else the kind of climbing they named – a day in the
     * gym is not a "board session" (phone check 2026-11-21).
     */
    internal fun climbingEntry(coach: CoachProfile, logbook: LogbookSummary, equipment: Set<EquipmentV2>): String {
        val contexts = coach.contexts
        val board = EquipmentV2.BOARD in equipment || ClimbingContext.HOME_BOARD in contexts || ClimbingContext.GYM_BOARD in contexts ||
            logbook.hasGrades || logbook.usualClimbingDays.isNotEmpty()
        if (board) return PLAN_BOARD
        val kind = when {
            contexts.isEmpty() || ClimbingContext.GYM_BOULDER in contexts -> ClimbingDayKind.GYM_BOULDER
            ClimbingContext.ROPE in contexts -> ClimbingDayKind.GYM_ROPE
            else -> ClimbingDayKind.OUTDOOR
        }
        return DayPlans.Climb(kind, ClimbIntensity.HARD).entry
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

    /** Logged sessions after which a value counts as learnt (about the first week). */
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
