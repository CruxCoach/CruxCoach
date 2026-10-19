package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FingerPreference
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SetupState
import com.cruxcoach.domain.playlist.GeneratorType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What today's suggested training is about. */
enum class SuggestionFocus { PLANNED, BOARD_DAY, FINGER_STRENGTH, PULL_PUSH, LEGS_CORE, MOBILITY_RECOVERY, INJURY_SAFE, REST }

/** Why the suggestion looks the way it does; the first entry is the deciding one. */
enum class SuggestionReason {
    SICK,
    REST_READINESS,
    WEEK_PLAN,
    WEEK_PLAN_BOARD,
    WEEK_PLAN_REST,
    PLAN_FILTERED_FOR_INJURY,
    PLAN_REPLACED_FOR_INJURY,
    BOARD_SKIPPED_TODAY,
    /** A planned or chosen board day while the fingers recover: a light volume session instead of limit moves. */
    BOARD_LIGHT,
    /** Tomorrow is a board day or a usual climbing day: no finger focus today (audit P1-5). */
    HARD_DAY_TOMORROW,
    INJURY_CLIMBING_PAUSED,
    FINGERS_LOADED_RECENTLY,
    FINGERS_TIRED,
    SKIN_LOW,
    FINGERS_RESTED,
    NO_FINGER_EQUIPMENT,
    PULL_LONGER_AGO,
    LEGS_LONGER_AGO,
    LOW_ENERGY,
    GOAL_STRENGTH,
    FAVORITES_USED,
    SHORTENED_TO_TIME,
    // FEAT-071: load model, coach profile, blocks, learning
    RECOVERY_AFTER_LIMIT,
    RECOVERY_AFTER_HARD,
    RECOVERY_AFTER_VOLUME,
    FINGER_LOAD_RISING,
    SKIN_LOAD_RISING,
    GUARDRAIL_YOUTH,
    GUARDRAIL_NOVICE,
    GUARDRAIL_MASTERS,
    FINGER_PREFERENCE_NONE,
    FOCUS_AREAS,
    GOAL_PROJECT,
    GOAL_HEALTHY,
    GOAL_COMEBACK,
    CLIMBING_DAY,
    CLIMBING_DAY_ADDON,
    BLOCK_INTRO,
    BLOCK_DELOAD,
    BLOCK_TAPER,
    BLOCK_EVENT,
    PREFERENCES_LEARNED,
    NO_TIME_TODAY,
    LEVEL_EASIER,
    LEVEL_HARDER,
    /** One step easier for this kind of training, as the athlete kept it ([AthleteProfile.keptLevels]). */
    LEVEL_EASIER_KEPT,
    /** One step more demanding for this kind of training, as the athlete kept it. */
    LEVEL_HARDER_KEPT,
    /** The weekly volume plan: this area has the most left to do this week (see [WeeklyVolume]). */
    WEEKLY_TARGET,
    /** Back after a break: fewer sets and no maximal finger load for now (see [ReturnToTraining]). */
    RETURN_AFTER_BREAK,
    /** A finger injury healed recently: finger work stays light for a few weeks. */
    RETURN_FINGER,
    /** The athlete picked today's kind of training ([SuggestionInput.wish]). */
    CHOSEN_TODAY,
    /** What hurts today (check-in) is spared: exercises loading it stay out ([Soreness]). */
    SPARED_TODAY,
    /** A board day was chosen at a place without a wall or board. */
    NO_WALL_HERE,
    /** The athlete chose more time for today ([SuggestionInput.minutesToday]). */
    MORE_TIME_TODAY,
    /** More sets and exercises of the same kind fill the time. */
    EXTENDED_TO_TIME,
    /** A lighter week (intro, deload, taper, re-entry) keeps its fewer sets even with more time. */
    KEPT_SHORT_THIS_WEEK,
    /** No more fitting exercises (equipment, injuries, exclusions): shorter than the chosen time. */
    LESS_THAN_CHOSEN,
    /** The kind the athlete usually chooses on this weekday ([PreferenceLearning.usualChoices]). */
    USUAL_KIND,
    /** The duration the athlete usually chooses on this weekday. */
    USUAL_MINUTES,
    /** An exercise the athlete kept after a training leads its slot ([SuggestionInput.kept]). */
    KEPT_CHOICE,
    /** A climbing day is planned today (gym, rope, rock): its warm-up and at most a short block after it ([DayPlans]). */
    PLANNED_CLIMB,
    /** A hard or limit climbing day is planned while the fingers still recover: rather volume today (advice, no lock). */
    CLIMB_LIGHTER_ADVISED,
    /** A limit climbing day is planned tomorrow: legs and core today, the pulling muscles go in fresh. */
    LIMIT_TOMORROW,
    /** Yesterday a hard or limit climbing day was planned, nothing is logged: log it, then the coach plans the recovery. */
    PLANNED_CLIMB_NOT_LOGGED,
    /** The planned training has finger work and tomorrow is a hard day: advice only, the plan stays. */
    FINGERS_BEFORE_HARD_DAY,
}

/** How much the suggestion knows about the athlete. */
enum class Confidence { LOW, MEDIUM, HIGH }

enum class GuardrailKind { YOUTH, NOVICE, MASTERS }

/** The data a suggestion was built from, for the "Basierend auf …" line. */
sealed interface Evidence {
    /** Climbing [daysAgo] days ago (0 = today); [intensity] null when the logbook had no grades for it. */
    data class Climbing(val daysAgo: Int, val intensity: ClimbIntensity?, val efforts: Int, val minutes: Int) : Evidence
    data class WeekPlan(val entry: String) : Evidence
    data class CheckIn(val reason: ReadinessReason) : Evidence
    data class InjuryActive(val region: InjuryRegion, val side: InjurySide?) : Evidence
    /** Hurts today according to the check-in – spared, not an injury. */
    data class SoreToday(val region: InjuryRegion) : Evidence
    data class LoadTrendNote(val structure: LoadStructure, val trend: LoadTrend) : Evidence
    data class PerformanceValue(val benchmark: Benchmark) : Evidence
    data class BlockNote(val state: BlockState) : Evidence
    data class Favorites(val count: Int) : Evidence
    data class Affinity(val slugs: List<String>) : Evidence
    data class GoalNote(val goal: CoachGoal) : Evidence
    data class FocusNote(val areas: Set<FocusArea>) : Evidence
    data class Guardrail(val kind: GuardrailKind) : Evidence
    data class History(val weeks: Int, val logbookSends: Int) : Evidence
    /** Weekly plan: [done] of [target] this week — finger sessions for FINGER, hard sets otherwise. */
    data class WeeklyTarget(val area: VolumeArea, val done: Int, val target: Int) : Evidence
    /** Re-entry after [daysOff] days: week [week] of [weeksTotal]. */
    data class Return(val state: ReturnState) : Evidence
    /** Today [minutes] chosen instead of the usual [usual]. */
    data class TimeToday(val minutes: Int, val usual: Int) : Evidence
}

/** A board session to fill with the playlist generator. */
data class BoardPlan(val type: GeneratorType, val minutes: Int)

data class SuggestionInput(
    val catalog: ExerciseCatalog,
    val profile: AthleteProfile,
    val today: LocalDate,
    val readiness: Readiness,
    val injuries: List<Injury>,
    /** Board and logged training per day (last week is enough). */
    val activities: Map<LocalDate, DayActivity> = emptyMap(),
    /** Completed sets of the last days with the day they belong to (finger load). */
    val recentSets: List<Pair<LocalDate, ExerciseSet>> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val benchmarkSlugs: Set<String> = emptySet(),
    /** Last day each exercise was trained (any time). */
    val lastTrained: Map<String, LocalDate> = emptyMap(),
    /** The athlete's own routines, to resolve week-plan entries. */
    val routines: List<Routine> = emptyList(),
    /** "Another suggestion": changes the picks, never the safety rules. */
    val variant: Int = 0,
    // ── FEAT-071 ──
    /** Coach-setup answers; unanswered fields keep today's defaults. */
    val coach: CoachProfile = profile.coach,
    /** Acute vs chronic load per structure; null without a load model. */
    val loadStatus: LoadStatus? = null,
    /** Learned likes/dislikes (see [PreferenceLearning]); today's "keine Lust" can push far below. */
    val affinity: Map<String, Double> = emptyMap(),
    /** Never suggested ("nie vorschlagen"). */
    val excluded: Set<String> = profile.excludedExercises,
    /** Training cycle position (see [TrainingBlocks]). */
    val block: BlockState? = null,
    /** The athlete climbs today (already logged, a usual climbing weekday or a planned board day). */
    val climbingToday: Boolean = false,
    /** Yesterday's planned climbing intensity ([DayPlans]), null when nothing was planned or yesterday was a pause. */
    val plannedYesterday: ClimbIntensity? = null,
    /** Newest performance values, for the evidence line. */
    val benchmarks: List<Benchmark> = emptyList(),
    /** "zu leicht" (+1) / "zu schwer" (−1) for today; on top of the step kept for the kind ([AthleteProfile.keptLevels]). */
    val levelShift: Int = 0,
    /** "keine Zeit" shortens today's budget. */
    val budgetFactor: Double = 1.0,
    /** Sends behind the logbook anchors, for the confidence badge. */
    val logbookSends: Int = 0,
    /** Working grade of a graded board logbook; sets the level while no grade is saved (owner question 2026-10-17). */
    val logbookDifficulty: Double? = null,
    /** Exercises the athlete turned down recently: they rest while something else fits (see [PreferenceLearning.resting]). */
    val resting: Set<String> = emptySet(),
    /** Weeks with any logged training or climbing. */
    val historyWeeks: Int = 0,
    /** This week's volume per area (see [WeeklyVolume]); empty keeps the day-by-day rules. */
    val weekly: List<AreaProgress> = emptyList(),
    /** Monday of the week [weekly] counts; derived from [today] when null. */
    val weekStart: LocalDate? = null,
    /** Re-entry after a break or a healed finger injury (see [ReturnToTraining]); null when training normally. */
    val returnState: ReturnState? = null,
    /**
     * What the athlete wants today ("rather pull and push", owner 2026-10-15): wins over the week
     * plan and the rotation, never over illness, an injury or fingers that need their recovery.
     */
    val wish: SuggestionFocus? = null,
    /**
     * Minutes the athlete chose for today (owner 2026-10-16: "adjust the duration easily"): wins over the
     * preferred duration, "keine Zeit" and the low-energy shortening; rest days and the event stay short.
     */
    val minutesToday: Int? = null,
    /**
     * [wish] is the kind the athlete usually chooses on this weekday, not a choice made today
     * ([PreferenceLearning.usualChoices]): it only fills a free day – the week plan and climbing days
     * stay the athlete's own plan – and every safety rule applies as to a choice.
     */
    val wishLearned: Boolean = false,
    /** [minutesToday] is the athlete's usual duration on this weekday; being tired still shortens it. */
    val minutesLearned: Boolean = false,
    /**
     * Exercises the athlete kept after a training ("künftig so", [PreferenceLearning.keptEffect]), newest first: each
     * is the preferred pick of the slots it fits – through every safety filter, while it does not rest, and within
     * today's level when "easier"/"harder" was chosen today.
     */
    val kept: List<String> = emptyList(),
)

data class SessionSuggestion(
    val focus: SuggestionFocus,
    val routine: Routine,
    val reasons: List<SuggestionReason>,
    val estimatedMinutes: Int,
    /** The week-plan value that shaped the suggestion, if any. */
    val plannedEntry: String? = null,
    /** The data behind it, most relevant first. */
    val basedOn: List<Evidence> = emptyList(),
    val confidence: Confidence = Confidence.LOW,
    /** On a board day: the session the playlist generator should build. */
    val boardPlan: BoardPlan? = null,
    /** The items are the short block after climbing, not a full session. */
    val addOn: Boolean = false,
    /** The duration can be chosen for today (not on a rest day or the event itself). */
    val timeAdjustable: Boolean = true,
    /** Already shorter because of little sleep or energy: starting it takes no extra set off (audit W7). */
    val shortenedForFatigue: Boolean = false,
) {
    /** The whole session in minutes, the board part included. */
    val totalMinutes: Int get() = estimatedMinutes + (boardPlan?.minutes ?: 0)
}

/**
 * Today's training, for climbers: deterministic rules decide the
 * focus (week plan, readiness, injuries, how hard and how recent the climbing
 * was, load trends, the training block), slot templates shaped by the coach
 * profile decide the structure, and a transparent score picks exercises
 * (favourites, learned preferences, known performance values, variety, goal,
 * a small seeded jitter for "another suggestion"). Every decision leaves a
 * [SuggestionReason] and the data behind it an [Evidence] — and nothing here
 * can override the injury filter or the guardrails.
 */
object SessionSuggester {

    private enum class Slot { WARMUP, FINGER_MAIN, PULL, PUSH, POWER, ANTAGONIST, CORE, LEGS, MOBILITY }

    private const val REST_MINUTES = 10
    private const val ADDON_MINUTES = 15
    private const val SETUP_SECONDS_PER_EXERCISE = 60
    /** Main-work slots whose picks follow the athlete's level from below too. */
    private val LEVELLED_SLOTS = setOf(Slot.FINGER_MAIN, Slot.PULL, Slot.PUSH, Slot.POWER, Slot.LEGS)
    /** Learned affinity below which an exercise counts as turned down: no novelty bonus for it. */
    const val DISLIKED = -0.5
    /** A session within this many minutes of its time counts as fitting. */
    private const val GROW_TOLERANCE_MIN = 4
    /**
     * More time adds exercises only up to this many (warm-up not counted); after that only sets
     * grow. A 90-minute day listed 14 exercises ("+ 9 more", render review 2026-10-16).
     */
    const val MAX_GROWN_EXERCISES = 8

    /** Mutable working state of one suggestion. */
    private class Ctx(val input: SuggestionInput) {
        val reasons = mutableListOf<SuggestionReason>()
        /** Hints that never decide: they go after every other reason. */
        val notes = mutableListOf<SuggestionReason>()
        val evidence = mutableListOf<Evidence>()
        val coach get() = input.coach
    }

    fun suggest(input: SuggestionInput): SessionSuggestion {
        var s = suggestByRules(input).let {
            it.copy(
                timeAdjustable = it.focus != SuggestionFocus.REST && SuggestionReason.BLOCK_EVENT !in it.reasons,
                // The same condition as the time cut for fatigue in suggestByRules.
                shortenedForFatigue = SuggestionReason.LOW_ENERGY in it.reasons && (input.minutesToday == null || input.minutesLearned),
            )
        }
        // A chosen duration is said last: the card's first line keeps the reason that decided the kind of training.
        val chosen = input.minutesToday?.coerceIn(10, 180)
        val usual = input.profile.sessionMinutes
        if (chosen != null && chosen != usual && s.timeAdjustable) {
            s = if (input.minutesLearned) s.copy(reasons = (s.reasons + SuggestionReason.USUAL_MINUTES).distinct())
            else s.copy(
                reasons = (s.reasons + if (chosen < usual) SuggestionReason.NO_TIME_TODAY else SuggestionReason.MORE_TIME_TODAY).distinct(),
                basedOn = (s.basedOn + Evidence.TimeToday(chosen, usual)).distinct(),
            )
        }
        // Clearly shorter than chosen: why comes first, since the athlete just asked for that time — a lighter
        // week keeps its fewer sets, or there are no more exercises that fit.
        if (chosen != null && s.timeAdjustable && s.totalMinutes < chosen - max(5, chosen / 10)) {
            val phase = input.block?.phase
            val lighter = input.returnState != null || phase == BlockPhase.INTRO || phase == BlockPhase.DELOAD || phase == BlockPhase.TAPER
            val why = if (lighter) SuggestionReason.KEPT_SHORT_THIS_WEEK else SuggestionReason.LESS_THAN_CHOSEN
            s = s.copy(reasons = (listOf(why) + s.reasons).distinct())
        }
        // What hurts today explains a short or unusual list: it comes first, after the reason a
        // chosen kind of training had to be replaced (that one explains the title).
        if (s.focus == SuggestionFocus.REST || input.injuries.none { Soreness.isSore(it) }) return s
        val reasons = s.reasons.toMutableList()
        val chose = SuggestionReason.CHOSEN_TODAY in reasons || SuggestionReason.USUAL_KIND in reasons
        val replaced = chose && reasons.firstOrNull().let { it != SuggestionReason.CHOSEN_TODAY && it != SuggestionReason.USUAL_KIND }
        reasons.add(if (replaced) 1 else 0, SuggestionReason.SPARED_TODAY)
        return s.copy(reasons = reasons)
    }

    private fun suggestByRules(input: SuggestionInput): SessionSuggestion {
        val ctx = Ctx(input)
        val reasons = ctx.reasons
        val readiness = input.readiness
        val coach = input.coach
        // The date's own plan (a moved day, "no time today") before the standard week (owner 2026-10-19).
        val planned = DayPlans.entry(input.profile, input.today)
        val chosenMinutes = input.minutesToday?.coerceIn(10, 180)
        var budget = chosenMinutes
            ?: (input.profile.sessionMinutes * input.budgetFactor.coerceIn(0.3, 1.0)).roundToInt().coerceIn(10, 180)
        if (chosenMinutes == null && input.budgetFactor < 1.0) reasons += SuggestionReason.NO_TIME_TODAY
        collectContextEvidence(ctx, planned)

        // 1. Rest comes first: illness or a rest-level check-in.
        if (readiness.level == ReadinessLevel.REST) {
            reasons += if (ReadinessReason.SICK in readiness.reasons) SuggestionReason.SICK else SuggestionReason.REST_READINESS
            return build(ctx, SuggestionFocus.REST, listOf(Slot.MOBILITY, Slot.MOBILITY, Slot.MOBILITY), REST_MINUTES, planned, grow = false)
        }
        // The trip or competition itself: arrive fresh.
        if (input.block?.phase == BlockPhase.EVENT) {
            reasons += SuggestionReason.BLOCK_EVENT
            return build(ctx, SuggestionFocus.MOBILITY_RECOVERY, listOf(Slot.MOBILITY, Slot.MOBILITY, Slot.MOBILITY), REST_MINUTES, planned, grow = false)
        }
        val lowEnergy = ReadinessReason.LOW_ENERGY in readiness.reasons || ReadinessReason.LOW_SLEEP in readiness.reasons
        if (lowEnergy) {
            // Tired: shorter, unless the athlete chose the time for today (a usual time of the weekday still shrinks).
            if (chosenMinutes == null || input.minutesLearned) budget = max(15, (budget * 0.6).roundToInt())
            reasons += SuggestionReason.LOW_ENERGY
        }
        when (input.levelShift.coerceIn(-1, 1)) {
            1 -> reasons += SuggestionReason.LEVEL_HARDER
            -1 -> reasons += SuggestionReason.LEVEL_EASIER
        }
        val climbingPaused = input.injuries.any { it.isActive && it.climbingPaused }
        val guard = guardrails(input)
        guard.reason?.let { reasons += it; ctx.evidence += Evidence.Guardrail(guard.kind!!) }

        // Finger rules hold for planned and generated sessions alike: how hard and how
        // recent the climbing was, off-wall finger sets, tired fingers, a rising load trend.
        val recovery = recovery(input, coach.ageBand)
        recovery.evidence?.let { ctx.evidence.add(0, it) }
        if (recovery.reason != null && recovery(input, null).reason == null) {
            reasons += SuggestionReason.GUARDRAIL_MASTERS
            ctx.evidence += Evidence.Guardrail(GuardrailKind.MASTERS)
        }
        val fingersTired = readiness.avoidMaxFingerLoad
        val trend = trendBlock(input)
        trend.evidence.forEach { ctx.evidence += it }
        val fingerBlock = recovery.reason ?: (if (fingersTired) SuggestionReason.FINGERS_TIRED else null) ?: trend.fingerReason
        // A hard day planned yesterday and nothing logged: the coach cannot know the fingers are tired (owner 2026-10-19).
        val yesterday = input.activities[input.today.minus(DatePeriod(days = 1))]
        if ((input.plannedYesterday == ClimbIntensity.HARD || input.plannedYesterday == ClimbIntensity.LIMIT) &&
            (yesterday == null || (yesterday.climbingMinutes <= 0 && yesterday.climbingEfforts <= 0))) {
            ctx.notes += SuggestionReason.PLANNED_CLIMB_NOT_LOGGED
        }

        // The athlete's own choice for today comes before the plan and the rotation; a usual choice of the
        // weekday only fills a free day.
        input.wish?.takeIf { !input.wishLearned || (planned == null && !input.climbingToday) }?.let { wish ->
            val why = if (input.wishLearned) SuggestionReason.USUAL_KIND else SuggestionReason.CHOSEN_TODAY
            chosen(ctx, wish, planned, budget, fingerBlock, climbingPaused, readiness, trend.skinSpike, why)?.let { return it }
        }

        // Climbing already done today on a climbing day: a short block after it instead of a
        // second session — unless the athlete said no to add-ons.
        val today = input.activities[input.today]
        val climbedToday = today != null && (today.climbingMinutes > 0 || today.climbingEfforts > 0)
        val plannedClimb = DayPlans.climbOf(planned)
        val climbingDayPlan = planned == null || planned == PLAN_BOARD || plannedClimb != null
        if (climbedToday && climbingDayPlan && coach.addOnAfterClimbing != false && !climbingPaused &&
            (planned == PLAN_BOARD || plannedClimb != null || input.climbingToday || coach.addOnAfterClimbing == true)) {
            reasons.add(0, SuggestionReason.CLIMBING_DAY_ADDON)
            return addOn(ctx, planned)
        }

        // 2. The week plan wins unless readiness or an injury rules it out.
        val usualClimbingDay = planned == null && input.climbingToday && !climbedToday
        when {
            // A planned gym, rope or rock day: the climbing is the training; around it only the short block after it.
            plannedClimb != null && climbingPaused -> reasons += SuggestionReason.PLAN_REPLACED_FOR_INJURY
            plannedClimb != null -> {
                reasons.add(0, SuggestionReason.PLANNED_CLIMB)
                val hard = plannedClimb.intensity == ClimbIntensity.HARD || plannedClimb.intensity == ClimbIntensity.LIMIT
                // Advice, never a lock: the athlete climbs anyway, the card says what the fingers tell.
                if ((hard && (fingerBlock != null || trend.skinSpike)) || readiness.avoidClimbing) {
                    reasons += SuggestionReason.CLIMB_LIGHTER_ADVISED
                    (fingerBlock ?: if (trend.skinSpike) SuggestionReason.SKIN_LOAD_RISING else null)?.let { if (it !in reasons) reasons += it }
                }
                return climbDay(ctx, planned)
            }
            planned == PLAN_REST -> {
                reasons.add(0, SuggestionReason.WEEK_PLAN_REST)
                return build(ctx, SuggestionFocus.REST, listOf(Slot.MOBILITY, Slot.MOBILITY, Slot.MOBILITY), REST_MINUTES, planned, grow = false)
            }
            planned == PLAN_BOARD || usualClimbingDay -> {
                if (climbingPaused || readiness.avoidClimbing || fingersTired || trend.skinSpike) {
                    reasons += if (climbingPaused) SuggestionReason.PLAN_REPLACED_FOR_INJURY else SuggestionReason.BOARD_SKIPPED_TODAY
                    if (trend.skinSpike && !climbingPaused) reasons += SuggestionReason.SKIN_LOAD_RISING
                } else if (fingerBlock != null) {
                    // Audit W6: still recovering – the board day runs as a light session and says why (it ran
                    // silently as a pyramid before, while a chosen board day was refused).
                    reasons.add(0, if (planned == PLAN_BOARD) SuggestionReason.WEEK_PLAN_BOARD else SuggestionReason.CLIMBING_DAY)
                    reasons += SuggestionReason.BOARD_LIGHT
                    reasons += fingerBlock
                    return boardDay(ctx, planned, fresh = false, light = true)
                } else {
                    reasons.add(0, if (planned == PLAN_BOARD) SuggestionReason.WEEK_PLAN_BOARD else SuggestionReason.CLIMBING_DAY)
                    return boardDay(ctx, planned, fresh = readiness.level == ReadinessLevel.GO)
                }
            }
            planned != null -> plannedRoutine(ctx, planned, climbingPaused, fingerBlock, guard, lowEnergy, budget)?.let { return it }
        }

        // 3. Injury with climbing paused: train around it.
        if (climbingPaused) {
            reasons.add(0, SuggestionReason.INJURY_CLIMBING_PAUSED)
            // The healthy hand's finger work also needs its recovery.
            val base = if (fingerBlock == null) listOf(Slot.WARMUP, Slot.FINGER_MAIN, Slot.PULL, Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.LEGS)
                else listOf(Slot.WARMUP, Slot.PULL, Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.LEGS).also { reasons += fingerBlock }
            if (fingerBlock == null && coach.fingerPreference == FingerPreference.NONE) reasons += SuggestionReason.FINGER_PREFERENCE_NONE
            return build(ctx, SuggestionFocus.INJURY_SAFE, withFocusAreas(base, SuggestionFocus.INJURY_SAFE, coach.focus, ctx), budget, planned)
        }

        // 4. Recovery state decides between finger work and the rest; the weekly plan
        //    then decides what the rest is (and whether the fingers already had their week).
        val skinLow = ReadinessReason.SKIN_LOW in readiness.reasons
        val weekly = weeklyWeights(input)
        val focus0 = when {
            fingerBlock != null -> { reasons += fingerBlock; null }
            skinLow -> { reasons += SuggestionReason.SKIN_LOW; null }
            coach.fingerPreference == FingerPreference.NONE -> { reasons += SuggestionReason.FINGER_PREFERENCE_NONE; null }
            weekly != null && fingerTargetMet(input) -> {
                reasons += SuggestionReason.WEEKLY_TARGET
                weeklyEvidence(ctx, VolumeArea.FINGER)
                null
            }
            // The fingers go into tomorrow's board or climbing day fresh (audit P1-5); chosen finger work still runs.
            hardDayTomorrow(input) -> { reasons += SuggestionReason.HARD_DAY_TOMORROW; null }
            else -> { reasons += SuggestionReason.FINGERS_RESTED; SuggestionFocus.FINGER_STRENGTH }
        } ?: weeklyFocus(ctx, weekly) ?: run {
            val pullLast = lastDay(input, setOf(ExerciseCategoryV2.PULL, ExerciseCategoryV2.PUSH))
            val legsLast = lastDay(input, setOf(ExerciseCategoryV2.LEGS, ExerciseCategoryV2.CORE))
            val legsFirst = when {
                legsLast == null && pullLast == null -> input.variant % 2 == 1
                legsLast == null -> true
                pullLast == null -> false
                else -> legsLast < pullLast
            }
            if (legsFirst) { reasons += SuggestionReason.LEGS_LONGER_AGO; SuggestionFocus.LEGS_CORE }
            else { reasons += SuggestionReason.PULL_LONGER_AGO; SuggestionFocus.PULL_PUSH }
        }
        // A limit day tomorrow: the pulling muscles go into it fresh (owner 2026-10-19), legs and core today.
        val focus = if (focus0 == SuggestionFocus.PULL_PUSH && limitTomorrow(input)) {
            reasons += SuggestionReason.LIMIT_TOMORROW
            SuggestionFocus.LEGS_CORE
        } else focus0
        goalReasons(ctx)
        val result = build(ctx, focus, withFocusAreas(slotsFor(focus), focus, coach.focus, ctx), budget, planned)
        // Without a hangboard or block (or a guardrail keeping only light work) there is no finger session to suggest.
        val hasFingerMain = result.routine.items.any { !it.warmup && input.catalog[it.slug]?.category == ExerciseCategoryV2.FINGER }
        if (focus == SuggestionFocus.FINGER_STRENGTH && !hasFingerMain) {
            val replacement = when {
                guard.restrictFinger && guard.reason != null -> guard.reason
                else -> SuggestionReason.NO_FINGER_EQUIPMENT
            }
            val kept = reasons.filter { it != SuggestionReason.FINGERS_RESTED }
            reasons.clear()
            reasons += kept
            if (replacement !in reasons) reasons.add(0, replacement)
            return build(ctx, SuggestionFocus.PULL_PUSH, withFocusAreas(slotsFor(SuggestionFocus.PULL_PUSH), SuggestionFocus.PULL_PUSH, coach.focus, ctx),
                budget, planned)
        }
        return result
    }

    // ── Weekly volume plan ───────────────────────────────────────────

    private val WEEKLY_FOCI = setOf(SuggestionFocus.FINGER_STRENGTH, SuggestionFocus.PULL_PUSH, SuggestionFocus.LEGS_CORE,
        SuggestionFocus.INJURY_SAFE)

    private fun weeklyWeights(input: SuggestionInput): Map<VolumeArea, Double>? {
        if (input.weekly.isEmpty()) return null
        return WeeklyVolume.deficitWeights(input.weekly, input.today, input.weekStart ?: WeeklyVolume.weekStartOf(input.today))
    }

    /** Finger sessions (board days included) already reached this week's goal, or there is no finger goal. */
    private fun fingerTargetMet(input: SuggestionInput): Boolean {
        val p = input.weekly.firstOrNull { it.area == VolumeArea.FINGER } ?: return false
        return p.status == AreaStatus.DONE || p.status == AreaStatus.OVER
    }

    private fun weeklyEvidence(ctx: Ctx, area: VolumeArea) {
        val p = ctx.input.weekly.firstOrNull { it.area == area } ?: return
        ctx.evidence += Evidence.WeeklyTarget(area, p.effective.roundToInt(), p.goal)
    }

    /** Upper body or legs and core: whichever has more left this week, when the gap is clear. */
    private fun weeklyFocus(ctx: Ctx, weights: Map<VolumeArea, Double>?): SuggestionFocus? {
        weights ?: return null
        fun w(a: VolumeArea) = (weights[a] ?: 0.0).coerceAtLeast(0.0)
        val upper = w(VolumeArea.PULL) + w(VolumeArea.PUSH) + 0.5 * w(VolumeArea.ANTAGONIST)
        val lower = w(VolumeArea.LEGS) + w(VolumeArea.CORE)
        if (kotlin.math.abs(upper - lower) < 0.3) return null
        val legs = lower > upper
        val top = (if (legs) listOf(VolumeArea.LEGS, VolumeArea.CORE) else listOf(VolumeArea.PULL, VolumeArea.PUSH, VolumeArea.ANTAGONIST))
            .maxBy { w(it) }
        ctx.reasons += SuggestionReason.WEEKLY_TARGET
        weeklyEvidence(ctx, top)
        return if (legs) SuggestionFocus.LEGS_CORE else SuggestionFocus.PULL_PUSH
    }

    private fun slotFor(area: VolumeArea): Slot? = when (area) {
        VolumeArea.PULL -> Slot.PULL
        VolumeArea.PUSH -> Slot.PUSH
        VolumeArea.LEGS -> Slot.LEGS
        VolumeArea.CORE -> Slot.CORE
        VolumeArea.ANTAGONIST -> Slot.ANTAGONIST
        VolumeArea.MOBILITY -> Slot.MOBILITY
        VolumeArea.FINGER -> null
    }

    private fun areaFor(slot: Slot): VolumeArea? = when (slot) {
        Slot.PULL -> VolumeArea.PULL
        Slot.PUSH -> VolumeArea.PUSH
        Slot.LEGS -> VolumeArea.LEGS
        Slot.CORE -> VolumeArea.CORE
        Slot.ANTAGONIST -> VolumeArea.ANTAGONIST
        Slot.MOBILITY -> VolumeArea.MOBILITY
        Slot.WARMUP, Slot.FINGER_MAIN, Slot.POWER -> null
    }

    /**
     * Shapes the slots by the week: areas over their maximum hand their slots
     * to what is missing, and the area with the most left gets a slot early in
     * the session (so the time budget trims generic slots first). Finger work
     * is never added here — the recovery rules alone decide it — and every
     * slot still goes through the injury filter, guardrails and exclusions.
     */
    private fun withWeeklyDeficit(slots: List<Slot>, focus: SuggestionFocus, ctx: Ctx): List<Slot> {
        val input = ctx.input
        val weights = weeklyWeights(input) ?: return slots
        val list = slots.toMutableList()
        if (focus == SuggestionFocus.INJURY_SAFE && Slot.FINGER_MAIN in list && fingerTargetMet(input)) {
            // The healthy hand already had its finger sessions this week.
            list.remove(Slot.FINGER_MAIN)
            ctx.reasons += SuggestionReason.WEEKLY_TARGET
            weeklyEvidence(ctx, VolumeArea.FINGER)
        }
        val open = weights.filter { (area, w) -> slotFor(area) != null && w > 0.3 }
        for (i in list.indices.reversed()) {
            val area = areaFor(list[i]) ?: continue
            if ((weights[area] ?: 0.0) < 0.0) {
                val replacement = open.maxByOrNull { it.value }?.key?.let(::slotFor)
                if (replacement != null) list[i] = replacement else list.removeAt(i)
            }
        }
        val top = open.filterValues { it >= 0.8 }.maxByOrNull { it.value }?.key
        if (top != null) {
            val slot = slotFor(top)!!
            val present = list.count { it == slot }
            if (present == 0 || (present < 2 && (weights[top] ?: 0.0) >= 2.0)) list.add(min(2, list.size), slot)
            ctx.reasons += SuggestionReason.WEEKLY_TARGET
            weeklyEvidence(ctx, top)
        }
        return list
    }

    /**
     * Today's training as the athlete chose it. Safety still decides: fingers that need
     * their recovery or an injury pause turn a finger or board wish into pull and push
     * (or the injury-safe session), with the reason why.
     */
    private fun chosen(
        ctx: Ctx, wish: SuggestionFocus, planned: String?, budget: Int, fingerBlock: SuggestionReason?,
        climbingPaused: Boolean, readiness: Readiness, skinSpike: Boolean,
        why: SuggestionReason = SuggestionReason.CHOSEN_TODAY,
    ): SessionSuggestion? {
        val reasons = ctx.reasons
        val areas = ctx.input.coach.focus
        reasons.add(0, why)
        fun pullPush() = build(ctx, SuggestionFocus.PULL_PUSH, withFocusAreas(slotsFor(SuggestionFocus.PULL_PUSH), SuggestionFocus.PULL_PUSH, areas, ctx), budget, planned, weekly = false)
        fun safe() = build(ctx, SuggestionFocus.INJURY_SAFE,
            withFocusAreas(listOf(Slot.WARMUP, Slot.PULL, Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.LEGS), SuggestionFocus.INJURY_SAFE, areas, ctx), budget, planned, weekly = false)
        return when (wish) {
            SuggestionFocus.BOARD_DAY -> when {
                climbingPaused -> { reasons.add(0, SuggestionReason.PLAN_REPLACED_FOR_INJURY); safe() }
                // Equipment known and nothing to climb on here: no board day at this place.
                ctx.input.profile.equipmentConfigured && ctx.input.profile.equipment.none {
                    it == EquipmentV2.WALL || it == EquipmentV2.BOARD || it == EquipmentV2.CAMPUS_BOARD
                } -> { reasons.add(0, SuggestionReason.NO_WALL_HERE); pullPush() }
                readiness.avoidClimbing || skinSpike || fingerBlock == SuggestionReason.FINGERS_TIRED -> {
                    reasons.add(0, fingerBlock ?: if (skinSpike) SuggestionReason.SKIN_LOAD_RISING else SuggestionReason.BOARD_SKIPPED_TODAY)
                    pullPush()
                }
                // Chosen while the fingers recover from climbing: the board day as a light session, the same rule
                // as a planned one (audit W6) – the wish counts, the recovery decides how hard.
                fingerBlock != null -> {
                    reasons.add(0, SuggestionReason.BOARD_LIGHT)
                    reasons += fingerBlock
                    boardDay(ctx, planned, fresh = false, light = true)
                }
                else -> boardDay(ctx, planned, fresh = readiness.level == ReadinessLevel.GO)
            }
            SuggestionFocus.FINGER_STRENGTH -> when {
                climbingPaused -> { reasons.add(0, SuggestionReason.INJURY_CLIMBING_PAUSED); safe() }
                fingerBlock != null -> { reasons.add(0, fingerBlock); pullPush() }
                else -> {
                    // Chosen anyway: the wish counts, the card says what is coming tomorrow.
                    if (hardDayTomorrow(ctx.input)) reasons += SuggestionReason.HARD_DAY_TOMORROW
                    val result = build(ctx, SuggestionFocus.FINGER_STRENGTH,
                        withFocusAreas(slotsFor(SuggestionFocus.FINGER_STRENGTH), SuggestionFocus.FINGER_STRENGTH, areas, ctx), budget, planned, weekly = false)
                    val hasFingerMain = result.routine.items.any { !it.warmup && ctx.input.catalog[it.slug]?.category == ExerciseCategoryV2.FINGER }
                    // No hangboard or block at this place: say so and train the rest.
                    if (hasFingerMain) result else { reasons.add(0, SuggestionReason.NO_FINGER_EQUIPMENT); pullPush() }
                }
            }
            SuggestionFocus.PULL_PUSH, SuggestionFocus.LEGS_CORE ->
                build(ctx, wish, withFocusAreas(slotsFor(wish), wish, areas, ctx), budget, planned, weekly = false)
            SuggestionFocus.MOBILITY_RECOVERY, SuggestionFocus.REST ->
                build(ctx, SuggestionFocus.MOBILITY_RECOVERY, List(6) { Slot.MOBILITY }, budget, planned, weekly = false)
            SuggestionFocus.PLANNED, SuggestionFocus.INJURY_SAFE -> { reasons.remove(why); null }
        }
    }

    private fun slotsFor(focus: SuggestionFocus): List<Slot> = when (focus) {
        SuggestionFocus.FINGER_STRENGTH -> listOf(Slot.WARMUP, Slot.FINGER_MAIN, Slot.PULL, Slot.ANTAGONIST, Slot.CORE)
        SuggestionFocus.LEGS_CORE -> listOf(Slot.WARMUP, Slot.LEGS, Slot.LEGS, Slot.LEGS, Slot.CORE, Slot.CORE, Slot.MOBILITY)
        else -> listOf(Slot.WARMUP, Slot.PULL, Slot.PULL, Slot.PUSH, Slot.ANTAGONIST, Slot.ANTAGONIST, Slot.CORE)
    }

    /**
     * The athlete's focus areas add slots right after the main block, so the
     * time budget trims generic slots before the ones they asked for.
     */
    private fun withFocusAreas(slots: List<Slot>, focus: SuggestionFocus, areas: Set<FocusArea>, ctx: Ctx): List<Slot> {
        if (areas.isEmpty()) return slots
        val extra = buildList {
            if (FocusArea.PULL_STRENGTH in areas && focus != SuggestionFocus.LEGS_CORE) add(Slot.PULL)
            if (FocusArea.POWER in areas && focus == SuggestionFocus.PULL_PUSH) add(Slot.POWER)
            if (FocusArea.CORE in areas) add(Slot.CORE)
            if (FocusArea.MOBILITY in areas) add(Slot.MOBILITY)
            if (FocusArea.PREVENTION in areas) add(Slot.ANTAGONIST)
        }
        if (extra.isNotEmpty() || FocusArea.FINGER_STRENGTH in areas || FocusArea.POWER_ENDURANCE in areas) {
            ctx.reasons += SuggestionReason.FOCUS_AREAS
            ctx.evidence += Evidence.FocusNote(areas)
        }
        val at = min(2, slots.size)
        return slots.take(at) + extra + slots.drop(at)
    }

    // ── Context, recovery and guardrails ─────────────────────────────

    private fun collectContextEvidence(ctx: Ctx, planned: String?) {
        val input = ctx.input
        planned?.let { ctx.evidence += Evidence.WeekPlan(it) }
        input.readiness.reasons.firstOrNull {
            it != ReadinessReason.ALL_GOOD && it != ReadinessReason.INJURY_ACTIVE && it != ReadinessReason.INJURY_CLIMBING_PAUSED
        }?.let { ctx.evidence += Evidence.CheckIn(it) }
        input.injuries.filter { it.isActive }.forEach {
            ctx.evidence += if (Soreness.isSore(it)) Evidence.SoreToday(it.region) else Evidence.InjuryActive(it.region, it.side)
        }
        input.block?.let { ctx.evidence += Evidence.BlockNote(it) }
        input.coach.goal?.let { ctx.evidence += Evidence.GoalNote(it) }
        if (input.historyWeeks > 0 || input.logbookSends > 0) ctx.evidence += Evidence.History(input.historyWeeks, input.logbookSends)
    }

    private class Recovery(val reason: SuggestionReason?, val evidence: Evidence?)

    /**
     * Recovery from climbing scales with how hard it was relative to the
     * athlete's own level ([ClimbingLoad.recoveryHours]): after a limit
     * session no max finger work for 48–72 h, after volume the next day is
     * fine for pulling and core, light climbing does not block fingers.
     * Climbing without grades counts as before (48 h), and off-wall finger
     * sets block the same day and the next (one more day from 40 on).
     */
    private fun recovery(input: SuggestionInput, ageBand: AgeBand?): Recovery {
        var latest: Evidence? = null
        var blocking: SuggestionReason? = null
        for (daysAgo in 0..3) {
            val day = input.today.minus(DatePeriod(days = daysAgo))
            val a = input.activities[day] ?: continue
            if (a.climbingMinutes <= 0 && a.climbingEfforts <= 0) continue
            if (latest == null) latest = Evidence.Climbing(daysAgo, a.climbIntensity, a.climbingEfforts, a.climbingMinutes)
            val intensity = a.climbIntensity
            val blocked = if (intensity == null) daysAgo <= 1
                else daysAgo * 24 < ClimbingLoad.recoveryHours(intensity, ageBand)
            if (blocked && blocking == null) blocking = when (intensity) {
                null -> SuggestionReason.FINGERS_LOADED_RECENTLY
                ClimbIntensity.LIMIT -> SuggestionReason.RECOVERY_AFTER_LIMIT
                ClimbIntensity.HARD -> SuggestionReason.RECOVERY_AFTER_HARD
                ClimbIntensity.VOLUME, ClimbIntensity.LIGHT -> SuggestionReason.RECOVERY_AFTER_VOLUME
            }
        }
        if (blocking == null) {
            val masters = ageBand == AgeBand.Y40_54 || ageBand == AgeBand.Y55_PLUS
            val window = (0..(if (masters) 2 else 1)).map { input.today.minus(DatePeriod(days = it)) }.toSet()
            val fingerSets = input.recentSets.any { (day, set) ->
                day in window && set.isCompleted && set.setType != SetType.WARMUP &&
                    LoadDomain.FINGER in input.catalog.fallbackFor(set.exerciseSlug).domains
            }
            if (fingerSets) blocking = SuggestionReason.FINGERS_LOADED_RECENTLY
        }
        return Recovery(blocking, latest)
    }

    private class TrendBlock(val fingerReason: SuggestionReason?, val skinSpike: Boolean, val evidence: List<Evidence>)

    private fun trendBlock(input: SuggestionInput): TrendBlock {
        val structures = input.loadStatus?.structures ?: return TrendBlock(null, false, emptyList())
        val evidence = mutableListOf<Evidence>()
        val finger = structures[LoadStructure.FINGER]?.trend
        val skin = structures[LoadStructure.SKIN]?.trend
        val fingerRising = finger == LoadTrend.RISING || finger == LoadTrend.SPIKE
        val skinRising = skin == LoadTrend.RISING || skin == LoadTrend.SPIKE
        if (fingerRising && finger != null) evidence += Evidence.LoadTrendNote(LoadStructure.FINGER, finger)
        if (skinRising && skin != null) evidence += Evidence.LoadTrendNote(LoadStructure.SKIN, skin)
        val reason = when {
            fingerRising -> SuggestionReason.FINGER_LOAD_RISING
            skinRising -> SuggestionReason.SKIN_LOAD_RISING
            else -> null
        }
        return TrendBlock(reason, skin == LoadTrend.SPIKE, evidence)
    }

    private class Guard(val restrictFinger: Boolean, val reason: SuggestionReason?, val kind: GuardrailKind?)

    /**
     * Young climbers (still growing) and climbers in their first year get no
     * max hangs, one-arm finger work or campus — only light finger work. A
     * second-year climber without any performance value is treated the same
     * until a value exists.
     */
    private fun guardrails(input: SuggestionInput): Guard {
        val coach = input.coach
        val youth = coach.ageBand == AgeBand.UNDER_16 || coach.ageBand == AgeBand.Y16_17
        val novice = coach.experience == ExperienceBand.UNDER_1 ||
            (coach.experience == ExperienceBand.Y1_2 && input.benchmarkSlugs.isEmpty() && input.benchmarks.isEmpty())
        return when {
            youth -> Guard(true, SuggestionReason.GUARDRAIL_YOUTH, GuardrailKind.YOUTH)
            novice -> Guard(true, SuggestionReason.GUARDRAIL_NOVICE, GuardrailKind.NOVICE)
            else -> Guard(false, null, null)
        }
    }

    /** Finger work a guardrail keeps away: one-arm, hard hangs, campus. */
    private fun restrictedByGuard(def: ExerciseDefinition): Boolean =
        EquipmentV2.CAMPUS_BOARD in def.equipment ||
            (LoadDomain.FINGER in def.domains && def.category != ExerciseCategoryV2.WARMUP && (def.unilateral || def.difficulty >= 2))

    private fun goalReasons(ctx: Ctx) {
        val input = ctx.input
        when (input.coach.goal) {
            CoachGoal.BUILD_STRENGTH -> ctx.reasons += SuggestionReason.GOAL_STRENGTH
            CoachGoal.PROJECT, CoachGoal.CLIMB_HARDER -> ctx.reasons += SuggestionReason.GOAL_PROJECT
            CoachGoal.STAY_HEALTHY -> ctx.reasons += SuggestionReason.GOAL_HEALTHY
            CoachGoal.COMEBACK -> ctx.reasons += SuggestionReason.GOAL_COMEBACK
            CoachGoal.EVENT, null -> if (input.profile.goal == AthleteGoal.BUILD_STRENGTH) ctx.reasons += SuggestionReason.GOAL_STRENGTH
        }
    }

    // ── Focus helpers ────────────────────────────────────────────────

    /**
     * Tomorrow is planned as a board or climbing day (its own date entry first, then the standard week and the usual
     * climbing days); a light climbing day is none.
     */
    private fun hardDayTomorrow(input: SuggestionInput): Boolean {
        val tomorrow = input.today.plus(DatePeriod(days = 1))
        return DayPlans.climbingPlanned(input.profile, tomorrow) &&
            DayPlans.climbIntensity(input.profile, tomorrow) != ClimbIntensity.LIGHT
    }

    private fun limitTomorrow(input: SuggestionInput): Boolean =
        DayPlans.climbIntensity(input.profile, input.today.plus(DatePeriod(days = 1))) == ClimbIntensity.LIMIT

    private fun resolve(entry: String, routines: List<Routine>): Routine? =
        if (entry.startsWith("builtin:")) BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
        else routines.firstOrNull { it.id == entry }

    private fun lastDay(input: SuggestionInput, categories: Set<ExerciseCategoryV2>): LocalDate? =
        input.lastTrained.filterKeys { slug -> input.catalog[slug]?.category in categories }.values.maxOrNull()

    private fun plannedRoutine(
        ctx: Ctx,
        planned: String,
        climbingPaused: Boolean,
        fingerBlock: SuggestionReason?,
        guard: Guard,
        lowEnergy: Boolean,
        budget: Int,
    ): SessionSuggestion? {
        val input = ctx.input
        val reasons = ctx.reasons
        val routine = resolve(planned, input.routines) ?: return null
        // The same safety rules as a generated session: around a paused injury only what
        // spares it (or the healthy side), no wall while climbing is paused, no finger
        // work while the fingers recover; young climbers keep their guardrail.
        var injuryRemoved = 0
        var fingerRemoved = 0
        var guardRemoved = 0
        val youth = guard.kind == GuardrailKind.YOUTH
        val kept = routine.items.filter { item ->
            val def = input.catalog.fallbackFor(item.slug)
            val verdict = InjuryAdvisor.assess(def, input.injuries).verdict
            val injuryOk = if (climbingPaused) verdict == InjuryVerdict.OK || verdict == InjuryVerdict.ONE_SIDE_ONLY
                else verdict != InjuryVerdict.AVOID
            val fingerOk = item.warmup || fingerBlock == null || LoadDomain.FINGER !in def.domains
            when {
                !injuryOk || (climbingPaused && def.needsClimbingWall) -> { injuryRemoved++; false }
                !fingerOk -> { fingerRemoved++; false }
                youth && !item.warmup && restrictedByGuard(def) -> { guardRemoved++; false }
                else -> true
            }
        }
        val removed = injuryRemoved + fingerRemoved + guardRemoved
        if (kept.any { !it.warmup } && removed * 2 <= routine.items.size) {
            reasons.add(0, SuggestionReason.WEEK_PLAN)
            if (injuryRemoved > 0) reasons += SuggestionReason.PLAN_FILTERED_FOR_INJURY
            if (fingerRemoved > 0) reasons += fingerBlock!!
            // The athlete's own plan stays; finger work before a hard day is pointed out, not removed (warn, don't lock).
            if (fingerBlock == null && hardDayTomorrow(input) && kept.any { !it.warmup && LoadDomain.FINGER in input.catalog.fallbackFor(it.slug).domains }) {
                reasons += SuggestionReason.FINGERS_BEFORE_HARD_DAY
            }
            // The preferred duration is for free days; a planned workout is only cut when tired or short of time,
            // and only grows when the athlete chose more time today — by exercises after theirs, never by changing them.
            // A duration only learned from habit never reshapes the athlete's own plan (audit P2-13).
            val chosenToday = input.minutesToday?.takeIf { !input.minutesLearned }
            var items = if (chosenToday == null) kept
                else growToBudget(ctx, SuggestionFocus.PLANNED, kept, budget, listOf(Slot.ANTAGONIST, Slot.CORE, Slot.MOBILITY), sets = false)
            items = applyBlock(ctx, items)
            // "Easier" / "harder" from the card works on the athlete's own plan too (audit W8): a set less or more
            // of the main work, the exercises stay as they built them.
            when (shift(input, SuggestionFocus.PLANNED)) {
                -1 -> items = items.map { if (it.warmup || it.test) it else it.copy(sets = max(1, it.sets - 1)) }
                1 -> items = items.map { if (it.warmup || it.test) it else it.copy(sets = it.sets + 1) }
            }
            if (lowEnergy || input.budgetFactor < 1.0 || chosenToday != null) {
                val fitted = fitToBudget(items, budget, input.catalog)
                if (fitted != items) reasons += SuggestionReason.SHORTENED_TO_TIME
                items = fitted
            }
            val r = routine.copy(id = "suggestion", items = items, builtinKey = "suggestion")
            return finish(ctx, SuggestionFocus.PLANNED, r, planned)
        }
        reasons += when {
            injuryRemoved >= fingerRemoved && injuryRemoved >= guardRemoved -> SuggestionReason.PLAN_REPLACED_FOR_INJURY
            fingerRemoved >= guardRemoved -> fingerBlock ?: SuggestionReason.PLAN_REPLACED_FOR_INJURY
            else -> guard.reason ?: SuggestionReason.PLAN_REPLACED_FOR_INJURY
        }
        return null
    }

    private fun boardDay(ctx: Ctx, planned: String?, fresh: Boolean, light: Boolean = false): SessionSuggestion {
        val input = ctx.input
        val addOn = input.coach.addOnAfterClimbing
        val warmup = BuiltinRoutines.byKey(BuiltinRoutines.WARMUP_BOARD)?.items.orEmpty().filter { it.slug !in input.excluded }
        val after = when (addOn) {
            false -> emptyList()
            true -> pickSlots(ctx, listOf(Slot.ANTAGONIST, Slot.CORE), allowWall = true, focus = SuggestionFocus.BOARD_DAY,
                taken = warmup.map { it.slug }.toMutableSet())
            null -> pickSlots(ctx, listOf(Slot.ANTAGONIST, Slot.ANTAGONIST), allowWall = true, focus = SuggestionFocus.BOARD_DAY,
                taken = warmup.map { it.slug }.toMutableSet())
        }.map { it.copy(sets = min(it.sets, 2)) }
        if (addOn == true) ctx.reasons += SuggestionReason.CLIMBING_DAY_ADDON
        val items = warmup + after
        // A chosen duration goes to the board: what the warm-up and the block after it leave, in 5-minute steps.
        val plan = boardPlan(input, fresh, light).let { plan ->
            val chosen = input.minutesToday?.takeIf { !input.minutesLearned } ?: return@let plan
            plan.copy(minutes = ((chosen - estimateMinutes(items, input.catalog)) / 5.0).roundToInt().times(5).coerceIn(20, 180))
        }
        return finish(ctx, SuggestionFocus.BOARD_DAY, routineOf(SuggestionFocus.BOARD_DAY, items), planned, boardPlan = plan)
    }

    /**
     * A planned gym, rope or rock day before the climbing: the warm-up for it and, as on a board day, a short block
     * for afterwards (none when the athlete said no to add-ons).
     */
    private fun climbDay(ctx: Ctx, planned: String?): SessionSuggestion {
        val input = ctx.input
        val addOn = input.coach.addOnAfterClimbing
        val warmup = BuiltinRoutines.byKey(BuiltinRoutines.WARMUP_BOARD)?.items.orEmpty().filter { it.slug !in input.excluded }
        val after = when (addOn) {
            false -> emptyList()
            true -> pickSlots(ctx, listOf(Slot.ANTAGONIST, Slot.CORE), allowWall = true, focus = SuggestionFocus.BOARD_DAY,
                taken = warmup.map { it.slug }.toMutableSet())
            null -> pickSlots(ctx, listOf(Slot.ANTAGONIST, Slot.ANTAGONIST), allowWall = true, focus = SuggestionFocus.BOARD_DAY,
                taken = warmup.map { it.slug }.toMutableSet())
        }.map { it.copy(sets = min(it.sets, 2)) }
        if (addOn == true) ctx.reasons += SuggestionReason.CLIMBING_DAY_ADDON
        return finish(ctx, SuggestionFocus.BOARD_DAY, routineOf(SuggestionFocus.BOARD_DAY, warmup + after), planned)
    }

    /** 10–15 minutes of antagonists and core after the climbing session. */
    private fun addOn(ctx: Ctx, planned: String?): SessionSuggestion {
        val picked = pickSlots(ctx, listOf(Slot.ANTAGONIST, Slot.CORE, Slot.ANTAGONIST), allowWall = false,
            focus = SuggestionFocus.BOARD_DAY, taken = mutableSetOf())
            .map { it.copy(sets = min(it.sets, 2)) }
        val budget = ctx.input.minutesToday ?: ADDON_MINUTES
        var fitted = fitToBudget(picked, budget, ctx.input.catalog)
        if (ctx.input.minutesToday != null) {
            fitted = growToBudget(ctx, SuggestionFocus.BOARD_DAY, fitted, budget, listOf(Slot.ANTAGONIST, Slot.CORE, Slot.MOBILITY, Slot.CORE))
        }
        return finish(ctx, SuggestionFocus.BOARD_DAY, routineOf(SuggestionFocus.BOARD_DAY, fitted), planned, addOn = true)
    }

    /**
     * The board session for the generator: limit bouldering when fresh in a
     * build week (projects when that is the goal), volume in intro and
     * deload weeks, short and sharp in the taper, 4×4-style for a
     * power-endurance focus, a pyramid otherwise.
     */
    private fun boardPlan(input: SuggestionInput, fresh: Boolean, light: Boolean = false): BoardPlan {
        if (light) return BoardPlan(GeneratorType.VOLUME, 45)
        val phase = input.block?.phase
        // "Easier" makes the board day volume, "harder" limit moves when fresh (audit W8).
        val shift = shift(input, SuggestionFocus.BOARD_DAY)
        val coach = input.coach
        val type = when {
            shift < 0 -> GeneratorType.VOLUME
            shift > 0 && fresh && phase != BlockPhase.DELOAD -> GeneratorType.LIMIT
            phase == BlockPhase.DELOAD || phase == BlockPhase.INTRO -> GeneratorType.VOLUME
            phase == BlockPhase.TAPER -> GeneratorType.LIMIT
            FocusArea.POWER_ENDURANCE in coach.focus -> GeneratorType.POWER_ENDURANCE
            fresh && coach.goal == CoachGoal.PROJECT && coach.targetClimbUuid != null -> GeneratorType.PROJECTING
            fresh && (phase == null || phase == BlockPhase.BUILD) -> GeneratorType.LIMIT
            else -> GeneratorType.PYRAMID
        }
        val minutes = when (phase) {
            BlockPhase.DELOAD -> 45
            BlockPhase.TAPER -> 40
            BlockPhase.INTRO -> 50
            else -> 60
        }
        return BoardPlan(type, minutes)
    }

    // ── Building ─────────────────────────────────────────────────────

    private fun build(
        ctx: Ctx,
        focus: SuggestionFocus,
        slots: List<Slot>,
        budgetMinutes: Int,
        planned: String?,
        /** A kind the athlete chose stays that kind: the weekly plan does not mix other areas in. */
        weekly: Boolean = true,
        /** Rest days and the event stay short whatever the time. */
        grow: Boolean = true,
    ): SessionSuggestion {
        val input = ctx.input
        val shaped = if (weekly && focus in WEEKLY_FOCI) withWeeklyDeficit(slots, focus, ctx) else slots
        var items = pickSlots(ctx, shaped, allowWall = false, focus = focus, taken = mutableSetOf())
        val training = focus != SuggestionFocus.REST && focus != SuggestionFocus.MOBILITY_RECOVERY
        if (training) items = applyStyle(items, input.coach.intensityStyle, input.catalog)
        // More time picks its exercises at full volume; the block then scales the sets, so a deload or
        // taper week keeps the build week's exercises with fewer sets.
        if (grow) items = growToBudget(ctx, focus, items, budgetMinutes, growSlots(focus))
        if (training) items = applyBlock(ctx, items)
        val fitted = fitToBudget(items, budgetMinutes, input.catalog)
        if (fitted != items) ctx.reasons += SuggestionReason.SHORTENED_TO_TIME
        return finish(ctx, focus, routineOf(focus, fitted), planned)
    }

    /** What fills more time, in this order: the same kind of work, then a cool-down. Never more finger work. */
    private fun growSlots(focus: SuggestionFocus): List<Slot> = when (focus) {
        SuggestionFocus.FINGER_STRENGTH -> listOf(Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.PUSH, Slot.MOBILITY)
        SuggestionFocus.LEGS_CORE -> listOf(Slot.LEGS, Slot.CORE, Slot.MOBILITY, Slot.LEGS, Slot.CORE)
        SuggestionFocus.MOBILITY_RECOVERY, SuggestionFocus.REST -> List(6) { Slot.MOBILITY }
        SuggestionFocus.INJURY_SAFE -> listOf(Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.LEGS, Slot.MOBILITY)
        else -> listOf(Slot.PULL, Slot.PUSH, Slot.ANTAGONIST, Slot.CORE, Slot.MOBILITY)
    }

    /**
     * More time than the template fills: one more set on main work, then exercises from [extra] (up to
     * [MAX_GROWN_EXERCISES] in all), then a second extra set. Finger work keeps its dose (no extra sets, no second finger block). Runs before the
     * block and the re-entry scale the sets.
     */
    private fun growToBudget(
        ctx: Ctx,
        focus: SuggestionFocus,
        items: List<RoutineItem>,
        budgetMinutes: Int,
        extra: List<Slot>,
        sets: Boolean = true,
    ): List<RoutineItem> {
        val input = ctx.input
        val catalog = input.catalog
        fun short(list: List<RoutineItem>) = estimateMinutes(list, catalog) < budgetMinutes - GROW_TOLERANCE_MIN
        if (items.isEmpty() || !short(items) || input.block?.phase == BlockPhase.EVENT) return items
        val list = items.toMutableList()
        fun growable(item: RoutineItem): Boolean {
            val def = catalog.fallbackFor(item.slug)
            return !item.warmup && !item.test && LoadDomain.FINGER !in def.domains && def.kind != ExerciseKind.CLIMB
        }
        fun addSets(cap: Int) {
            for (i in list.indices) {
                if (!short(list)) return
                if (growable(list[i]) && list[i].sets < cap) list[i] = list[i].copy(sets = list[i].sets + 1)
            }
        }
        if (sets) addSets(4)
        // Up to three rounds through [extra]; a round that finds nothing new ends it.
        var full = false
        var round = 0
        fun room() = list.count { !it.warmup } < MAX_GROWN_EXERCISES
        while (!full && short(list) && room() && round++ < 3) {
            var added = 0
            for (slot in extra) {
                if (!short(list) || !room()) break
                val picked = pickSlots(ctx, listOf(slot), allowWall = false, focus = focus, taken = list.map { it.slug }.toMutableSet())
                    .firstOrNull() ?: continue
                val item = applyStyle(listOf(picked), input.coach.intensityStyle, catalog).first()
                // A cool-down stays last; other work goes before it.
                val coolDown = list.indexOfFirst { !it.warmup && catalog.fallbackFor(it.slug).category == ExerciseCategoryV2.MOBILITY }
                val at = if (slot == Slot.MOBILITY || coolDown < 0) list.size else coolDown
                list.add(at, item)
                // An exercise that overshoots clearly gets fewer sets, or stays out.
                while (estimateMinutes(list, catalog) > budgetMinutes + GROW_TOLERANCE_MIN && list[at].sets > 2) {
                    list[at] = list[at].copy(sets = list[at].sets - 1)
                }
                if (estimateMinutes(list, catalog) > budgetMinutes + GROW_TOLERANCE_MIN) { list.removeAt(at); full = true; break }
                added++
            }
            if (added == 0) break
        }
        if (sets) addSets(5)
        // Said only when the athlete asked for the time; filling the preferred duration needs no reason.
        if (input.minutesToday != null && list != items) ctx.reasons += SuggestionReason.EXTENDED_TO_TIME
        return list
    }

    /** Adds the item-dependent evidence and reasons and the confidence. */
    private fun finish(
        ctx: Ctx,
        focus: SuggestionFocus,
        routine: Routine,
        planned: String?,
        boardPlan: BoardPlan? = null,
        addOn: Boolean = false,
    ): SessionSuggestion {
        val input = ctx.input
        // Re-entry applies to every path — generated, planned, board warm-up, add-on — after all other rules.
        val back = input.returnState
        val routine = if (back == null) routine else {
            ctx.reasons += if (back.reason == ReturnReason.FINGER_INJURY_HEALED) SuggestionReason.RETURN_FINGER
                else SuggestionReason.RETURN_AFTER_BREAK
            ctx.evidence.add(0, Evidence.Return(back))
            routine.copy(items = routine.items.mapNotNull { item ->
                ReturnToTraining.applyToItem(item, input.catalog.fallbackFor(item.slug), back)
            })
        }
        val boardPlan = if (back == null || boardPlan == null || !back.noMaxFinger) boardPlan else {
            // The first board sessions back are volume, not limit: shorter, no maximal attempts.
            boardPlan.copy(
                type = if (boardPlan.type == GeneratorType.LIMIT || boardPlan.type == GeneratorType.PROJECTING) GeneratorType.VOLUME else boardPlan.type,
                minutes = (boardPlan.minutes * back.setsFactor).roundToInt().coerceAtLeast(20),
            )
        }
        // A step kept for this kind says so, unless today's own choice already explains the level.
        if (input.levelShift == 0) when (keptLevel(input, focus)) {
            -1 -> ctx.reasons += SuggestionReason.LEVEL_EASIER_KEPT
            1 -> ctx.reasons += SuggestionReason.LEVEL_HARDER_KEPT
        }
        val main = routine.items.filter { !it.warmup }
        val favs = main.count { it.slug in input.favorites }
        if (favs > 0) {
            ctx.reasons += SuggestionReason.FAVORITES_USED
            ctx.evidence += Evidence.Favorites(favs)
        }
        val liked = main.filter { (input.affinity[it.slug] ?: 0.0) >= 1.0 }.map { it.slug }
        if (liked.isNotEmpty()) {
            ctx.reasons += SuggestionReason.PREFERENCES_LEARNED
            ctx.evidence += Evidence.Affinity(liked)
        }
        main.mapNotNull { item -> input.benchmarks.filter { it.exerciseSlug == item.slug }.maxByOrNull { it.measuredAt } }
            .distinctBy { it.exerciseSlug }.take(2)
            .forEach { ctx.evidence += Evidence.PerformanceValue(it) }
        return SessionSuggestion(
            focus = focus,
            routine = routine,
            reasons = (ctx.reasons + ctx.notes).distinct(),
            estimatedMinutes = estimateMinutes(routine.items, input.catalog),
            plannedEntry = planned,
            basedOn = ctx.evidence.distinct(),
            confidence = confidence(input),
            boardPlan = boardPlan,
            addOn = addOn,
        )
    }

    private fun routineOf(focus: SuggestionFocus, items: List<RoutineItem>) =
        Routine(id = "suggestion", name = focus.name, items = items, builtinKey = "suggestion")

    /** Logbook, performance values, history and the coach setup: what the coach can lean on. */
    private fun confidence(input: SuggestionInput): Confidence {
        var points = 0
        if (input.logbookSends >= 20) points++
        if ((input.benchmarkSlugs + input.benchmarks.map { it.exerciseSlug }).size >= 2) points++
        if (input.historyWeeks >= 4) points++
        if (input.coach.setupState == SetupState.DONE) points++
        return when {
            points >= 3 -> Confidence.HIGH
            points == 2 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    /** The kept step for [focus] (−1/+1, 0 without one). */
    private fun keptLevel(input: SuggestionInput, focus: SuggestionFocus): Int = (input.profile.keptLevels[focus.name] ?: 0).coerceIn(-1, 1)

    /** Today's level step for [focus]: the kept one, moved by today's "easier"/"harder" – at most one step either way. */
    private fun shift(input: SuggestionInput, focus: SuggestionFocus): Int =
        (keptLevel(input, focus) + input.levelShift.coerceIn(-1, 1)).coerceIn(-1, 1)

    /** Athlete level from what is known about them, with the step for [focus]; keeps beginners away from one-arm and campus work. */
    private fun level(input: SuggestionInput, focus: SuggestionFocus): Int {
        var level = 2
        if (input.benchmarkSlugs.size >= 3) level++
        if ("pull.weighted_pull_up" in input.benchmarkSlugs && "finger.max_hang" in input.benchmarkSlugs) level++
        // What the athlete climbs, and for how long (quick start, coach setup): a 7a boulderer with years of
        // climbing got assisted hangs and negative pull-ups before the first performance value (device test
        // 2026-10-17). The grade sets the level, the years cap it; the novice and youth guardrails still apply.
        climbingLevel(input.coach, input.logbookDifficulty)?.let { level = max(level, it) }
        if (input.coach.goal == CoachGoal.COMEBACK) level--
        return (level + shift(input, focus)).coerceAtLeast(1)
    }

    /**
     * Exercise difficulty (1–5) the athlete's grade stands for, capped by their years of climbing; null without a grade.
     * The saved grade wins (the athlete's word); without one the board logbook's working grade stands in, so skipping
     * the quick start does not leave a logbook climber at the beginner level.
     */
    internal fun climbingLevel(coach: CoachProfile, logbookDifficulty: Double? = null): Int? {
        val grade = coach.currentGrade?.let { LogbookSummaries.difficultyOf(it) } ?: logbookDifficulty ?: return null
        val byGrade = when {
            grade < 16 -> 1      // up to 5c
            grade < 20 -> 2      // 6a – 6b+
            grade < 24 -> 3      // 6c – 7a+
            grade < 27 -> 4      // 7b – 7c
            else -> 5            // 7c+ and harder
        }
        val cap = when (coach.experience) {
            ExperienceBand.UNDER_1, ExperienceBand.Y1_2 -> 2
            ExperienceBand.Y3_5 -> 4
            ExperienceBand.OVER_5 -> 5
            null -> 3
        }
        return min(byGrade, cap)
    }

    private fun pickSlots(
        ctx: Ctx,
        slots: List<Slot>,
        allowWall: Boolean,
        focus: SuggestionFocus,
        taken: MutableSet<String>,
    ): List<RoutineItem> {
        val input = ctx.input
        val profile = input.profile
        // Equipment not set up yet: suggest only what needs none (floor and mat), not a hangboard
        // or dumbbells the athlete may not have (device/render test 2026-10-10).
        val filter = CatalogFilter(
            ownedEquipment = if (profile.equipmentConfigured) profile.equipment else setOf(EquipmentV2.NONE, EquipmentV2.MAT),
            withoutClimbing = !allowWall,
        )
        val level = level(input, focus)
        val guard = guardrails(input)
        val fingerFocus = focus == SuggestionFocus.FINGER_STRENGTH || focus == SuggestionFocus.INJURY_SAFE
        val result = mutableListOf<RoutineItem>()
        // The warm-up is chosen last, for what the session then holds (owner 2026-10-09), and goes first.
        for (slot in slots.filter { it != Slot.WARMUP } + slots.filter { it == Slot.WARMUP }) {
            val mains = if (slot == Slot.WARMUP) result.map { input.catalog.fallbackFor(it.slug) } else emptyList()
            // One progression chain per session: a 7a boulderer with weighted pull-ups got negative pull-ups two
            // steps down the same chain as the extra pull exercise at 90 min (device test 2026-10-17). Warm-ups
            // keep the neighbour rule only, so an easy hang can still prepare a hard pull.
            val takenChains = if (slot == Slot.WARMUP) emptySet()
                else taken.flatMapTo(mutableSetOf()) { t -> input.catalog.chainOf(t).map { it.slug } }
            // "Lieber gar nicht": finger strength comes from the board, not from here.
            if (slot == Slot.FINGER_MAIN && input.coach.fingerPreference == FingerPreference.NONE) continue
            val base = input.catalog.all.filter { def ->
                val verdict = InjuryAdvisor.assess(def, input.injuries).verdict
                // Around an injury only what does not load it at all, or the healthy side.
                val allowed = if (focus == SuggestionFocus.INJURY_SAFE) verdict == InjuryVerdict.OK || verdict == InjuryVerdict.ONE_SIDE_ONLY
                    else verdict != InjuryVerdict.AVOID
                def.slug !in taken && def.slug !in input.excluded && fits(slot, def, fingerFocus) && filter.matches(def) && allowed &&
                    (slot != Slot.WARMUP || warmupFits(def, mains)) &&
                    !(guard.restrictFinger && slot != Slot.WARMUP && restrictedByGuard(def)) &&
                    // No easier/harder neighbour of a pick, and for main work nothing else from its chain.
                    taken.none { t -> def.easier == t || def.harder == t } && def.slug !in takenChains
            }
            // Turned down lately: rests while something else fits the slot; never leaves a slot empty.
            val fresh = base.filter { it.slug !in input.resting }.ifEmpty { base }
            // The preferred finger-training family, when the equipment allows it.
            val preferred = if (slot == Slot.FINGER_MAIN) fresh.filter { fitsPreference(it, input.coach.fingerPreference) }.ifEmpty { fresh } else fresh
            val withinLevel = preferred.filter { it.difficulty <= level }.ifEmpty { preferred }
                // From level 3 on, main work is not two steps below the athlete (a 7a boulderer got assisted hangs);
                // favourites stay welcome at any difficulty, and prehab, mobility and warm-ups keep their easy picks.
                .let { list ->
                    if (level < 3 || slot !in LEVELLED_SLOTS) list
                    else list.filter { it.difficulty >= level - 1 || it.slug in input.favorites }.ifEmpty { list }
                }
            // Kept after a training (comparison review #3): the athlete's pick leads the slot, not the score.
            val kept = input.kept.firstNotNullOfOrNull { k -> fresh.firstOrNull { it.slug == k } }
                ?.takeIf { k -> input.levelShift == 0 || withinLevel.any { it.slug == k.slug } }
            if (kept != null) ctx.reasons += SuggestionReason.KEPT_CHOICE
            val choice = kept ?: withinLevel.maxWithOrNull(compareBy<ExerciseDefinition> { score(input, it, slot, focus, mains) }.thenBy { it.slug })
                ?: continue
            taken += choice.slug
            if (slot == Slot.WARMUP) result.add(0, WorkoutPlanner.itemFor(choice).copy(warmup = true, sets = min(WorkoutPlanner.itemFor(choice).sets, 4)))
            else result += WorkoutPlanner.itemFor(choice)
        }
        return result
    }

    private fun fitsPreference(def: ExerciseDefinition, pref: FingerPreference?): Boolean = when (pref) {
        null -> true
        FingerPreference.HANGBOARD -> EquipmentV2.HANGBOARD in def.equipment && !def.unilateral
        FingerPreference.PICKUP -> EquipmentV2.PICKUP_BLOCK in def.equipment || EquipmentV2.PICKUP_BLOCK_PAIR in def.equipment
        FingerPreference.ONE_ARM -> def.unilateral
        FingerPreference.NONE -> false
    }

    private fun fits(slot: Slot, def: ExerciseDefinition, fingerFocus: Boolean): Boolean {
        val loadsFingers = LoadDomain.FINGER in def.domains
        return when (slot) {
            Slot.WARMUP -> def.category == ExerciseCategoryV2.WARMUP && def.kind != ExerciseKind.CLIMB &&
                (fingerFocus || !loadsFingers)
            Slot.FINGER_MAIN -> def.category == ExerciseCategoryV2.FINGER && def.kind == ExerciseKind.HANG &&
                (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) && "strength" in def.tags
            Slot.PULL -> def.category == ExerciseCategoryV2.PULL && !loadsFingers
            Slot.PUSH -> def.category == ExerciseCategoryV2.PUSH && !loadsFingers
            Slot.POWER -> "power" in def.tags && def.kind != ExerciseKind.CLIMB && !loadsFingers
            Slot.ANTAGONIST -> def.category == ExerciseCategoryV2.ANTAGONIST && !loadsFingers
            Slot.CORE -> def.category == ExerciseCategoryV2.CORE && !loadsFingers
            Slot.LEGS -> def.category == ExerciseCategoryV2.LEGS
            Slot.MOBILITY -> def.category == ExerciseCategoryV2.MOBILITY && !loadsFingers
        }
    }

    /** Equipment a warm-up hangs or lifts on (not the floor or a mat). */
    private fun gear(def: ExerciseDefinition): Set<EquipmentV2> = def.equipment.toSet() - EquipmentV2.NONE - EquipmentV2.MAT

    /**
     * A loaded warm-up on equipment (the hangboard ramp, hangs on the bar) is a
     * lighter version of what follows only when the session uses that
     * equipment; general warm-ups always fit.
     */
    private fun warmupFits(def: ExerciseDefinition, mains: List<ExerciseDefinition>): Boolean {
        val gear = gear(def)
        if (def.load == LoadMode.NONE || gear.isEmpty()) return true
        return mains.any { m -> m.equipment.any { it in gear } }
    }

    private fun score(
        input: SuggestionInput,
        def: ExerciseDefinition,
        slot: Slot,
        focus: SuggestionFocus,
        mains: List<ExerciseDefinition> = emptyList(),
    ): Double {
        val coach = input.coach
        val variety = (coach.variety ?: 50).coerceIn(0, 100)
        var s = 0.0
        // Favourites (owner 2026-10-16): almost always with a routine, regularly with variety.
        if (def.slug in input.favorites) s += 3.0 + 5.0 * (100 - variety) / 100.0
        if (def.slug in input.benchmarkSlugs) s += 2.0
        val affinity = input.affinity[def.slug] ?: 0.0
        s += affinity
        val last = input.lastTrained[def.slug]
        // Variety 50 keeps the classic novelty bonus; lower favours the familiar routine, higher the new.
        // Not for what the athlete turned down: "not done for a while" was exactly why a rejected exercise came
        // back right after its rest (simulation 2026-10-17). It returns once the dislike has faded.
        val novelty = if (last == null) 2.0 else min(14L, (input.today.toEpochDays() - last.toEpochDays()).toLong().coerceAtLeast(0L)) / 3.5
        if (affinity >= DISLIKED) s += novelty * (0.5 + variety / 100.0)
        if (variety < 50 && last != null && (input.today.toEpochDays() - last.toEpochDays()).toLong() <= 14) s += (50 - variety) / 50.0 * 1.5
        if ((input.profile.goal == AthleteGoal.BUILD_STRENGTH || coach.goal == CoachGoal.BUILD_STRENGTH) && "strength" in def.tags) s += 1.5
        if (input.profile.goal == AthleteGoal.PERFORM && def.category in setOf(ExerciseCategoryV2.FINGER, ExerciseCategoryV2.PULL, ExerciseCategoryV2.ANTAGONIST)) s += 0.5
        when (coach.goal) {
            CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT ->
                if (def.category in setOf(ExerciseCategoryV2.FINGER, ExerciseCategoryV2.PULL, ExerciseCategoryV2.POWER)) s += 0.75
            CoachGoal.STAY_HEALTHY -> {
                if (def.tags.any { it == "prehab" || it == "antagonist" || it == "mobility" }) s += 1.0
                if ("beginner_friendly" in def.tags) s += 0.5
            }
            CoachGoal.COMEBACK -> if ("beginner_friendly" in def.tags) s += 1.0
            else -> Unit
        }
        if (slot == Slot.FINGER_MAIN) {
            if (FocusArea.FINGER_STRENGTH in coach.focus && "strength" in def.tags) s += 1.0
            if (FocusArea.POWER_ENDURANCE in coach.focus && "endurance" in def.tags) s += 2.0
        }
        if (slot == Slot.PULL && FocusArea.POWER in coach.focus && "power" in def.tags) s += 1.5
        if ("beginner_friendly" in def.tags && level(input, focus) <= 2) s += 0.5
        if (slot == Slot.WARMUP) {
            // Prepares the body parts the session loads; a lighter version on the same equipment first.
            val loaded = mains.flatMap { it.domains }.toSet()
            s += 2.0 * def.domains.count { it in loaded }
            if (def.load != LoadMode.NONE && gear(def).isNotEmpty() && mains.any { m -> m.equipment.any { it in gear(def) } }) s += 10.0
        }
        return s + jitter(def.slug, input.today, input.variant) * (0.4 + 1.2 * variety / 100.0)
    }

    /** Deterministic noise in [0, 2.5) so "another suggestion" reshuffles close calls only. */
    private fun jitter(slug: String, day: LocalDate, variant: Int): Double {
        var h = 1469598103934665603L
        for (c in "$slug|${day.toEpochDays()}|$variant") { h = (h xor c.code.toLong()) * 1099511628211L }
        return ((h ushr 11) % 1000L).toDouble() / 1000.0 * 2.5
    }

    /**
     * Short and intense: fewer exercises, heavier rep ranges, longer rests.
     * Longer and calmer: one more set on main work, shorter rests.
     */
    private fun applyStyle(items: List<RoutineItem>, style: Int?, catalog: ExerciseCatalog): List<RoutineItem> {
        style ?: return items
        return when {
            style <= 33 -> {
                val main = items.count { !it.warmup }
                val trimmed = if (main > 3) items.toMutableList().also { list ->
                    list.indices.lastOrNull { !list[it].warmup }?.let { list.removeAt(it) }
                } else items
                trimmed.map { item ->
                    if (item.warmup) item else {
                        val def = catalog.fallbackFor(item.slug)
                        val rest = (item.restS ?: def.defaults.restS ?: 90) + 30
                        if (def.kind == ExerciseKind.LOAD_REPS) item.copy(repsMin = item.repsMin?.let { min(it, 4) },
                            repsMax = min(item.repsMax ?: def.defaults.repsMax ?: 6, 6), restS = rest)
                        else item.copy(restS = rest)
                    }
                }
            }
            style >= 67 -> items.map { item ->
                if (item.warmup) item else {
                    val def = catalog.fallbackFor(item.slug)
                    val rest = ((item.restS ?: def.defaults.restS ?: 90) * 0.8).roundToInt().coerceAtLeast(30)
                    item.copy(sets = min(item.sets + 1, 5), restS = rest)
                }
            }
            else -> items
        }
    }

    /** Intro, deload and taper weeks scale the volume of main work; intensity stays. */
    private fun applyBlock(ctx: Ctx, items: List<RoutineItem>): List<RoutineItem> {
        val phase = ctx.input.block?.phase ?: return items
        val factor = TrainingBlocks.setsFactor(phase)
        when (phase) {
            BlockPhase.INTRO -> ctx.reasons += SuggestionReason.BLOCK_INTRO
            BlockPhase.DELOAD -> ctx.reasons += SuggestionReason.BLOCK_DELOAD
            BlockPhase.TAPER -> ctx.reasons += SuggestionReason.BLOCK_TAPER
            else -> Unit
        }
        if (factor == 1.0) return items
        return items.map { if (it.warmup || it.test) it else it.copy(sets = max(1, (it.sets * factor).roundToInt())) }
    }

    // ── The athlete's swaps (owner 2026-10-16: every recommendation overridable with little effort) ──

    /**
     * One exercise that could take another's place; [easier]/[harder] compare its difficulty with the
     * original's, [variant] marks the original's own easier or harder progression step.
     */
    data class Alternative(
        val def: ExerciseDefinition,
        val easier: Boolean = false,
        val harder: Boolean = false,
        val variant: Boolean = false,
    )

    /**
     * What could take [slug]'s place in [suggestion]: its easier and harder variant first, then the best
     * scored exercises of the same kind – through the same filters as a pick (equipment of today's place,
     * injuries and what hurts today, guardrails, exclusions). Work that spares the fingers is only ever
     * swapped for work that spares them too, so a swap never adds finger load the rules kept out.
     */
    fun alternatives(input: SuggestionInput, suggestion: SessionSuggestion, slug: String, count: Int = 4): List<Alternative> =
        alternatives(input, suggestion.routine.items, suggestion.focus, slug, count)

    /** The same for any list of exercises, e.g. an open training ([focus] decides how strict the injury filter is). */
    fun alternatives(input: SuggestionInput, items: List<RoutineItem>, focus: SuggestionFocus, slug: String, count: Int = 4): List<Alternative> {
        val catalog = input.catalog
        val item = items.firstOrNull { it.slug == slug } ?: return emptyList()
        val original = catalog.fallbackFor(slug)
        val others = items.map { it.slug }.filter { it != slug }.toSet()
        val filter = CatalogFilter(
            ownedEquipment = if (input.profile.equipmentConfigured) input.profile.equipment else setOf(EquipmentV2.NONE, EquipmentV2.MAT),
            withoutClimbing = !original.needsClimbingWall,
        )
        val guard = guardrails(input)
        val slot = slotOf(original, item.warmup)
        val mains = items.filter { !it.warmup }.map { catalog.fallbackFor(it.slug) }
        val sparesFingers = LoadDomain.FINGER !in original.domains
        val candidates = catalog.all.filter { def ->
            val verdict = InjuryAdvisor.assess(def, input.injuries).verdict
            val allowed = if (focus == SuggestionFocus.INJURY_SAFE) verdict == InjuryVerdict.OK || verdict == InjuryVerdict.ONE_SIDE_ONLY
                else verdict != InjuryVerdict.AVOID
            def.slug != slug && def.slug !in others && def.slug !in input.excluded && allowed && filter.matches(def) &&
                def.category == original.category && (def.kind == ExerciseKind.CLIMB) == (original.kind == ExerciseKind.CLIMB) &&
                (!sparesFingers || LoadDomain.FINGER !in def.domains) &&
                !(guard.restrictFinger && !item.warmup && restrictedByGuard(def)) &&
                (!item.warmup || warmupFits(def, mains)) &&
                // One progression chain per session: no neighbour of another pick.
                others.none { o -> def.easier == o || def.harder == o }
        }
        // Turned down lately ([SuggestionInput.resting]): offered last, so the athlete still finds them but the
        // coach does not lead with what they rejected (comparison review #1/#11).
        val chainAll = listOfNotNull(
            candidates.firstOrNull { it.slug == original.easier }?.let { Alternative(it, easier = true, variant = true) },
            candidates.firstOrNull { it.slug == original.harder }?.let { Alternative(it, harder = true, variant = true) },
        )
        val chain = chainAll.filter { it.def.slug !in input.resting }
        val rest = candidates.filter { c -> chain.none { it.def.slug == c.slug } }
            .sortedWith(compareBy<ExerciseDefinition> { it.slug in input.resting }
                .thenByDescending { score(input, it, slot, focus, mains) }.thenBy { it.slug })
        // Device review 2026-10-16: a diamond push-up got only harder push-ups. An easier one comes first when the
        // chain has none, then exercises about as hard, then the rest — each marked easier or harder.
        val fresh = rest.filter { it.slug !in input.resting }
        val easierPick = if (chain.any { it.easier }) null else fresh.firstOrNull { it.difficulty < original.difficulty }
        val ordered = listOfNotNull(easierPick) +
            fresh.filter { it != easierPick && it.difficulty == original.difficulty } +
            fresh.filter { it != easierPick && it.difficulty != original.difficulty } +
            rest.filter { it.slug in input.resting }
        return (chain + ordered.map { Alternative(it, easier = it.difficulty < original.difficulty, harder = it.difficulty > original.difficulty) })
            .take(count)
    }

    /**
     * Today's swaps on top of a suggestion: [swaps] maps a suggested slug to the athlete's pick, or to ""
     * for "leave it out today". The pick keeps the item's number of sets and its warm-up role; a pick that
     * is already in the session, or an unknown one, leaves the item out instead of doubling it.
     */
    fun withSwaps(suggestion: SessionSuggestion, swaps: Map<String, String>, catalog: ExerciseCatalog): SessionSuggestion {
        if (swaps.isEmpty() || suggestion.routine.items.none { it.slug in swaps }) return suggestion
        val items = mutableListOf<RoutineItem>()
        for (item in suggestion.routine.items) {
            val to = swaps[item.slug]
            if (to == null) { items += item; continue }
            val def = catalog[to] ?: continue
            if (suggestion.routine.items.any { it.slug == to } || items.any { it.slug == to }) continue
            items += WorkoutPlanner.itemFor(def).copy(sets = item.sets, warmup = item.warmup,
                circuit = item.circuit, circuitRestS = item.circuitRestS, roundRestS = item.roundRestS)
        }
        // A pick that cannot be in a circuit, or one left out, may leave a circuit of one.
        val normalized = Circuits.normalize(items, catalog)
        return suggestion.copy(routine = suggestion.routine.copy(items = normalized), estimatedMinutes = estimateMinutes(normalized, catalog))
    }

    private fun slotOf(def: ExerciseDefinition, warmup: Boolean): Slot = when {
        warmup -> Slot.WARMUP
        else -> when (def.category) {
            ExerciseCategoryV2.FINGER -> Slot.FINGER_MAIN
            ExerciseCategoryV2.PULL -> Slot.PULL
            ExerciseCategoryV2.PUSH -> Slot.PUSH
            ExerciseCategoryV2.ANTAGONIST -> Slot.ANTAGONIST
            ExerciseCategoryV2.CORE -> Slot.CORE
            ExerciseCategoryV2.LEGS -> Slot.LEGS
            ExerciseCategoryV2.MOBILITY -> Slot.MOBILITY
            ExerciseCategoryV2.WARMUP -> Slot.WARMUP
            else -> Slot.POWER
        }
    }

    // ── Time budget ──────────────────────────────────────────────────

    /**
     * Minutes [items] take. Straight items: sets × (work × sides + rest) plus
     * setup per exercise. A circuit G with members i (sᵢ sets, wᵢ work seconds,
     * kᵢ sides), R = max sᵢ rounds, nᵣ members in round r, short rest t and
     * round rest q: Σ sᵢ·wᵢ·kᵢ + t·Σᵣ(nᵣ − 1) + R·q + |G|·setup – the long
     * rests come once per round instead of once per set.
     */
    fun estimateMinutes(items: List<RoutineItem>, catalog: ExerciseCatalog): Int {
        // Items without circuits come back unchanged, so their estimate is exactly the straight one.
        val norm = Circuits.normalize(items, catalog)
        val groups = Circuits.groups(norm)
        val inCircuit = groups.flatMap { it.toList() }.toSet()
        val straight = norm.indices.filter { it !in inCircuit }.sumOf { itemSeconds(norm[it], catalog.fallbackFor(norm[it].slug)) }
        val circuits = groups.sumOf { range -> circuitSeconds(range.map { norm[it] }, catalog) }
        return ((straight + circuits) / 60.0).roundToInt().coerceAtLeast(1)
    }

    private fun circuitSeconds(members: List<RoutineItem>, catalog: ExerciseCatalog): Int {
        val rounds = members.maxOf { it.sets }
        val between = members.firstNotNullOfOrNull { it.circuitRestS } ?: Circuits.DEFAULT_CIRCUIT_REST_S
        val afterRound = members.firstNotNullOfOrNull { it.roundRestS } ?: Circuits.defaultRoundRest(members, catalog)
        val work = members.sumOf { m -> val def = catalog.fallbackFor(m.slug); m.sets * workSeconds(m, def) * sides(def) }
        val shortRests = (0 until rounds).sumOf { r -> (members.count { it.sets > r } - 1).coerceAtLeast(0) }
        return work + between * shortRests + rounds * afterRound + members.size * SETUP_SECONDS_PER_EXERCISE
    }

    private fun workSeconds(item: RoutineItem, def: ExerciseDefinition): Int = when (def.kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> (item.repsMax ?: item.repsMin ?: 8) * 3
        ExerciseKind.TIME, ExerciseKind.HANG -> item.durationS ?: 20
        ExerciseKind.INTERVAL -> (item.repsPerSet ?: 6) * ((item.workS ?: 7) + (item.restBetweenS ?: 3))
        ExerciseKind.CLIMB -> 60
    }

    private fun sides(def: ExerciseDefinition): Int = if (def.unilateral) 2 else 1

    private fun itemSeconds(item: RoutineItem, def: ExerciseDefinition): Int {
        val perSet = workSeconds(item, def) * sides(def) + (item.restS ?: def.defaults.restS ?: 60)
        return item.sets * perSet + SETUP_SECONDS_PER_EXERCISE
    }

    /** Fewer sets first (never below two for main work), then drop trailing slots, warm-up stays. */
    private fun fitToBudget(items: List<RoutineItem>, budgetMinutes: Int, catalog: ExerciseCatalog): List<RoutineItem> {
        val list = items.toMutableList()
        fun over() = estimateMinutes(list, catalog) > budgetMinutes
        var guard = 0
        while (over() && guard++ < 50) {
            val index = list.indices.reversed().firstOrNull { !list[it].warmup && list[it].sets > 2 }
            if (index != null) { list[index] = list[index].copy(sets = list[index].sets - 1); continue }
            val drop = list.indices.reversed().firstOrNull { !list[it].warmup && list.count { i -> !i.warmup } > 2 }
            if (drop != null) { list.removeAt(drop); continue }
            val warm = list.indexOfFirst { it.warmup && it.sets > 1 }
            if (warm >= 0) { list[warm] = list[warm].copy(sets = list[warm].sets - 1); continue }
            break
        }
        return list
    }
}
