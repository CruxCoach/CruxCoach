package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.SetType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Areas the week is planned in; finger work is counted in sessions, the rest in hard sets. */
enum class VolumeArea { FINGER, PULL, PUSH, LEGS, CORE, ANTAGONIST, MOBILITY }

/**
 * What one ISO week should hold for an area: hard (work, non-warm-up) sets
 * in [minSets]..[maxSets] spread over at least [minSessions] days. For
 * [VolumeArea.FINGER] the session count is the target (board climbing counts)
 * and [maxSets] caps the off-wall finger sets.
 */
data class AreaTarget(
    val area: VolumeArea,
    val minSets: Int,
    val maxSets: Int,
    val minSessions: Int,
    /** The athlete set this area's target themselves (owner 2026-10-10); [recommended] is what it replaced. */
    val own: Boolean = false,
    val recommended: AreaTarget? = null,
) {
    val isOff: Boolean get() = minSets == 0 && maxSets == 0 && minSessions == 0
}

enum class AreaStatus { BELOW, ON_TRACK, DONE, OVER }

/**
 * The week so far for one area. [doneSets] are logged hard sets; [sessions]
 * the days with such sets (finger days at least 48 h apart);
 * [climbingCredit] what climbing added — finger sessions for FINGER, set
 * equivalents for PULL and CORE. [expectedByNow] is the share of the minimum
 * a steady week would have reached by today.
 */
data class AreaProgress(
    val area: VolumeArea,
    val doneSets: Int,
    val sessions: Int,
    val target: AreaTarget,
    val climbingCredit: Double,
    val expectedByNow: Double = 0.0,
) {
    /** Counted against the target: sessions for fingers, sets (plus climbing credit) otherwise. */
    val effective: Double get() = if (area == VolumeArea.FINGER) sessions + climbingCredit else doneSets + climbingCredit

    /** The minimum for the week in the unit of [effective]. */
    val goal: Int get() = if (area == VolumeArea.FINGER) target.minSessions else target.minSets

    val remaining: Double get() = max(0.0, goal - effective)

    val status: AreaStatus get() = when {
        target.isOff -> AreaStatus.DONE
        area == VolumeArea.FINGER && target.maxSets > 0 && doneSets > target.maxSets -> AreaStatus.OVER
        area != VolumeArea.FINGER && effective > target.maxSets -> AreaStatus.OVER
        effective >= goal -> AreaStatus.DONE
        effective >= expectedByNow -> AreaStatus.ON_TRACK
        else -> AreaStatus.BELOW
    }
}

/**
 * MCI plans volume per muscle group per week; climbers need it per climbing
 * structure, with the wall counted. Targets are hard sets per ISO week in a
 * build week at three training days, then scaled:
 *
 * | Area | Sets | Sessions | Why |
 * |---|---|---|---|
 * | Finger | ≤ 12 off-wall | 2 | two quality finger days a week, ≥ 48 h apart; hard board days count |
 * | Pull | 6–12 | 1 | strength support for climbing; hard board days give some credit |
 * | Push | 4–8 | 1 | balance against all the pulling |
 * | Antagonist | 6–12 | 2 | shoulder and elbow health, best spread over two days |
 * | Core | 4–10 | 1 | body tension; hard board days give a little credit |
 * | Legs | 3–8 | 1 | maintenance, not a bodybuilding block |
 * | Mobility | 2–6 | 1 | hips and shoulders for climbing positions |
 *
 * Scaling: block phase ([TrainingBlocks.setsFactor]), goal, focus areas,
 * experience and age band, training days per week (2 → ×0.7 … 5+ → ×1.3) and
 * injuries. Young climbers and first-year climbers get no off-wall finger
 * sets (their finger stimulus is climbing), and a paused finger injury
 * halves the finger cap (healthy side only); both hands hurt → no finger target.
 */
object WeeklyVolume {

    private data class Base(val min: Int, val max: Int, val sessions: Int)

    private val BASE = mapOf(
        VolumeArea.FINGER to Base(0, 12, 2),
        VolumeArea.PULL to Base(6, 12, 1),
        VolumeArea.PUSH to Base(4, 8, 1),
        VolumeArea.ANTAGONIST to Base(6, 12, 2),
        VolumeArea.CORE to Base(4, 10, 1),
        VolumeArea.LEGS to Base(3, 8, 1),
        VolumeArea.MOBILITY to Base(2, 6, 1),
    )

    /** Climbing credit per board/climbing day by intensity: finger sessions. */
    private fun fingerCredit(intensity: ClimbIntensity?, minutes: Int, efforts: Int): Double = when (intensity) {
        ClimbIntensity.LIMIT, ClimbIntensity.HARD -> 1.0
        ClimbIntensity.VOLUME -> 0.5
        ClimbIntensity.LIGHT -> 0.0
        // Ungraded climbing: a real session counts half.
        null -> if (minutes >= 45 || efforts >= 20) 0.5 else 0.0
    }

    /** Set equivalents a climbing day adds to pull and core work. */
    private fun pullCredit(intensity: ClimbIntensity?): Double = when (intensity) {
        ClimbIntensity.LIMIT, ClimbIntensity.HARD -> 2.0
        ClimbIntensity.VOLUME -> 1.0
        else -> 0.0
    }

    private fun coreCredit(intensity: ClimbIntensity?): Double = when (intensity) {
        ClimbIntensity.LIMIT, ClimbIntensity.HARD -> 1.0
        ClimbIntensity.VOLUME -> 0.5
        else -> 0.0
    }

    fun targets(profile: AthleteProfile, block: BlockState?, injuries: List<Injury>, trainingDaysPerWeek: Int): List<AreaTarget> {
        val coach = profile.coach
        val phaseFactor = block?.let { TrainingBlocks.setsFactor(it.phase) } ?: 1.0
        val days = trainingDaysPerWeek.coerceIn(1, 7)
        val dayFactor = when {
            days <= 2 -> 0.7
            days == 3 -> 1.0
            days == 4 -> 1.15
            else -> 1.3
        }
        val experienceFactor = when (coach.experience) {
            ExperienceBand.UNDER_1 -> 0.7
            ExperienceBand.Y1_2 -> 0.85
            else -> 1.0
        }
        val ageFactor = when (coach.ageBand) {
            AgeBand.UNDER_16, AgeBand.Y16_17 -> 0.8
            AgeBand.Y40_54 -> 0.9
            AgeBand.Y55_PLUS -> 0.8
            else -> 1.0
        }
        val strength = coach.goal == CoachGoal.BUILD_STRENGTH || (coach.goal == null && profile.goal == AthleteGoal.BUILD_STRENGTH)
        val maintain = coach.goal == null && profile.goal == AthleteGoal.MAINTAIN
        val active = injuries.filter { it.isActive }
        val fingerInjuries = active.filter { it.region == InjuryRegion.FINGER }
        val bothHandsFinger = fingerInjuries.any { it.side == null || it.side == InjurySide.BOTH }
        val climbingPaused = active.any { it.climbingPaused }

        return VolumeArea.entries.map { area ->
            val base = BASE.getValue(area)
            var f = phaseFactor * dayFactor * experienceFactor * ageFactor
            when (coach.goal) {
                CoachGoal.STAY_HEALTHY -> f *= when (area) {
                    VolumeArea.ANTAGONIST, VolumeArea.MOBILITY -> 1.25
                    VolumeArea.PULL -> 0.8
                    else -> 1.0
                }
                CoachGoal.COMEBACK -> f *= 0.7
                CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT -> if (area == VolumeArea.PULL) f *= 1.1
                else -> Unit
            }
            if (strength && area in setOf(VolumeArea.PULL, VolumeArea.PUSH, VolumeArea.LEGS)) f *= 1.25
            if (maintain) f *= 0.8
            val focus = coach.focus
            f *= when {
                area == VolumeArea.PULL && FocusArea.PULL_STRENGTH in focus -> 1.3
                area == VolumeArea.PULL && FocusArea.POWER in focus -> 1.15
                area == VolumeArea.CORE && FocusArea.CORE in focus -> 1.4
                area == VolumeArea.MOBILITY && FocusArea.MOBILITY in focus -> 1.5
                area == VolumeArea.ANTAGONIST && FocusArea.PREVENTION in focus -> 1.3
                area == VolumeArea.FINGER && FocusArea.FINGER_STRENGTH in focus -> 1.25
                else -> 1.0
            }
            // Injuries: shoulder → less pressing, elbow → less pulling.
            if (active.any { it.region == InjuryRegion.SHOULDER } && area == VolumeArea.PUSH) f *= 0.5
            if (active.any { it.region == InjuryRegion.ELBOW } && area == VolumeArea.PULL) f *= 0.6

            if (phaseFactor == 0.0) return@map AreaTarget(area, 0, 0, 0)
            var minSets = (base.min * f).roundToInt()
            var maxSets = max(minSets, (base.max * f).roundToInt())
            var sessions = base.sessions
            if (area == VolumeArea.FINGER) {
                val youth = coach.ageBand == AgeBand.UNDER_16 || coach.ageBand == AgeBand.Y16_17
                val novice = coach.experience == ExperienceBand.UNDER_1
                sessions = when {
                    bothHandsFinger -> 0
                    coach.goal == CoachGoal.COMEBACK -> 1
                    block?.phase == BlockPhase.DELOAD || block?.phase == BlockPhase.TAPER -> 1
                    (coach.goal == CoachGoal.CLIMB_HARDER || coach.goal == CoachGoal.PROJECT) && days >= 5 &&
                        (coach.experience == ExperienceBand.Y3_5 || coach.experience == ExperienceBand.OVER_5) &&
                        coach.ageBand != AgeBand.Y40_54 && coach.ageBand != AgeBand.Y55_PLUS -> 3
                    else -> 2
                }
                minSets = 0
                maxSets = when {
                    bothHandsFinger || youth || novice -> 0
                    fingerInjuries.isNotEmpty() && climbingPaused -> maxSets / 2
                    else -> maxSets
                }
            } else {
                if (area == VolumeArea.ANTAGONIST && phaseFactor >= 0.8) sessions = 2
                if (minSets == 0) sessions = 0
            }
            AreaTarget(area, minSets, maxSets, sessions)
        }.map { withOwn(it, profile) }
    }

    /** Sessions a week an own finger target may ask for, and hard sets a week for the other areas. */
    val OWN_FINGER_SESSIONS = 0..5
    val OWN_SETS = 0..60

    /**
     * The athlete's own target in place of the recommended range: finger in
     * sessions a week, the other areas as an exact number of hard sets.
     */
    fun withOwn(t: AreaTarget, p: AthleteProfile): AreaTarget {
        val own = p.ownWeeklyTargets[t.area.name] ?: return t
        if (own <= 0) return AreaTarget(t.area, 0, 0, 0, own = true, recommended = t)
        return if (t.area == VolumeArea.FINGER) {
            t.copy(minSessions = own, maxSets = t.maxSets.takeIf { it > 0 } ?: BASE.getValue(t.area).max, own = true, recommended = t)
        } else {
            t.copy(minSets = own, maxSets = own, minSessions = max(1, t.minSessions), own = true, recommended = t)
        }
    }

    /** The area an exercise trains, from its catalogue category and load domains; null for warm-ups and climbing. */
    fun areaOf(def: ExerciseDefinition): VolumeArea? = when (def.category) {
        ExerciseCategoryV2.FINGER -> VolumeArea.FINGER
        ExerciseCategoryV2.PULL -> VolumeArea.PULL
        ExerciseCategoryV2.PUSH -> VolumeArea.PUSH
        ExerciseCategoryV2.ANTAGONIST -> VolumeArea.ANTAGONIST
        ExerciseCategoryV2.CORE -> VolumeArea.CORE
        ExerciseCategoryV2.LEGS -> VolumeArea.LEGS
        ExerciseCategoryV2.MOBILITY -> VolumeArea.MOBILITY
        ExerciseCategoryV2.POWER -> if (LoadDomain.LOWER_BODY in def.domains) VolumeArea.LEGS else VolumeArea.PULL
        ExerciseCategoryV2.ENDURANCE -> if (LoadDomain.FINGER in def.domains) VolumeArea.FINGER else null
        ExerciseCategoryV2.WARMUP, ExerciseCategoryV2.TECHNIQUE -> null
    }

    /**
     * Counts the ISO week starting [weekStart]: logged hard sets per area,
     * days with them, and what climbing added. Finger days closer than 48 h
     * count once, so two finger days in a row are one session.
     */
    fun progress(
        weekSets: List<Pair<LocalDate, ExerciseSet>>,
        activities: Map<String, DayActivity>,
        catalog: ExerciseCatalog,
        weekStart: LocalDate,
        targets: List<AreaTarget>,
        today: LocalDate? = null,
    ): List<AreaProgress> {
        val weekEnd = weekStart.plus(DatePeriod(days = 6))
        val inWeek = weekSets.filter { (day, set) ->
            day >= weekStart && day <= weekEnd && set.isCompleted && set.setType == SetType.WORK
        }
        val byArea = inWeek.mapNotNull { (day, set) -> areaOf(catalog.fallbackFor(set.exerciseSlug))?.let { Triple(it, day, set) } }
            .groupBy { it.first }
        val climbingDays = activities.values.mapNotNull { a ->
            val day = runCatching { LocalDate.parse(a.day) }.getOrNull() ?: return@mapNotNull null
            if (day < weekStart || day > weekEnd || (a.climbingMinutes <= 0 && a.climbingEfforts <= 0)) null else day to a
        }
        val elapsed = today?.let { ((it.toEpochDays() - weekStart.toEpochDays()).toInt() + 1).coerceIn(0, 7) } ?: 7
        return targets.map { target ->
            val entries = byArea[target.area].orEmpty()
            val setDays = entries.map { it.second }.toSet()
            var sessions = setDays.size
            var credit = 0.0
            when (target.area) {
                VolumeArea.FINGER -> {
                    // Greedy over the week: a day counts when it is ≥ 2 days after the last counted one.
                    val weights = mutableMapOf<LocalDate, Double>()
                    setDays.forEach { weights[it] = 1.0 }
                    climbingDays.forEach { (day, a) ->
                        val w = fingerCredit(a.climbIntensity, a.climbingMinutes, a.climbingEfforts)
                        if (w > 0.0 && (weights[day] ?: 0.0) < w) weights[day] = w
                    }
                    var last: LocalDate? = null
                    sessions = 0
                    weights.keys.sorted().forEach { day ->
                        val prev = last
                        if (prev == null || day.toEpochDays() - prev.toEpochDays() >= 2) {
                            if (day in setDays) sessions++ else credit += weights.getValue(day)
                            last = day
                        }
                    }
                }
                VolumeArea.PULL -> credit = climbingDays.sumOf { (_, a) -> pullCredit(a.climbIntensity) }
                VolumeArea.CORE -> credit = climbingDays.sumOf { (_, a) -> coreCredit(a.climbIntensity) }
                else -> Unit
            }
            val goal = if (target.area == VolumeArea.FINGER) target.minSessions else target.minSets
            AreaProgress(
                area = target.area,
                doneSets = entries.size,
                sessions = sessions,
                target = target,
                climbingCredit = credit,
                expectedByNow = goal * elapsed / 7.0,
            )
        }
    }

    /**
     * How strongly today's session should lean towards each area: what is
     * still missing relative to the weekly minimum, more urgent as the week
     * runs out (0 … 3). Areas over their maximum get −1; done areas 0.
     */
    fun deficitWeights(progress: List<AreaProgress>, today: LocalDate, weekStart: LocalDate): Map<VolumeArea, Double> {
        val dayIndex = (today.toEpochDays() - weekStart.toEpochDays()).toInt().coerceIn(0, 6)
        val daysLeft = 7 - dayIndex
        return progress.associate { p ->
            val weight = when {
                p.target.isOff -> 0.0
                p.status == AreaStatus.OVER -> -1.0
                p.goal <= 0 -> 0.0
                else -> min(3.0, (p.remaining / p.goal) * (7.0 / daysLeft))
            }
            p.area to weight
        }
    }

    /** Monday of the ISO week of [day]. */
    fun weekStartOf(day: LocalDate): LocalDate = ConsistencyStreak.weekStart(day)
}
