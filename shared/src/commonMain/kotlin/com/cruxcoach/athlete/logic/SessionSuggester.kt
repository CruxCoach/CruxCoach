package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
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
    INJURY_CLIMBING_PAUSED,
    FINGERS_LOADED_RECENTLY,
    FINGERS_TIRED,
    SKIN_LOW,
    FINGERS_RESTED,
    PULL_LONGER_AGO,
    LEGS_LONGER_AGO,
    LOW_ENERGY,
    GOAL_STRENGTH,
    FAVORITES_USED,
    SHORTENED_TO_TIME,
}

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
)

data class SessionSuggestion(
    val focus: SuggestionFocus,
    val routine: Routine,
    val reasons: List<SuggestionReason>,
    val estimatedMinutes: Int,
    /** The week-plan value that shaped the suggestion, if any. */
    val plannedEntry: String? = null,
)

/**
 * Today's training, MCI-style but for climbers: deterministic rules decide the
 * focus (week plan, readiness, injuries, finger load of the last 48 h), slot
 * templates decide the structure, and a transparent score picks exercises
 * (favourites, known performance values, variety, a small seeded jitter for
 * "another suggestion"). Every decision leaves a [SuggestionReason], so the
 * athlete can see why — and nothing here can override the injury filter.
 */
object SessionSuggester {

    private enum class Slot { WARMUP, FINGER_MAIN, PULL, PUSH, ANTAGONIST, CORE, LEGS, MOBILITY }

    private const val REST_MINUTES = 10
    private const val SETUP_SECONDS_PER_EXERCISE = 60

    fun suggest(input: SuggestionInput): SessionSuggestion {
        val reasons = mutableListOf<SuggestionReason>()
        val readiness = input.readiness
        val planned = input.profile.weekPlan[input.today.dayOfWeek.isoDayNumber]
        var budget = input.profile.sessionMinutes.coerceIn(10, 180)

        // 1. Rest comes first: illness or a rest-level check-in.
        if (readiness.level == ReadinessLevel.REST) {
            reasons += if (ReadinessReason.SICK in readiness.reasons) SuggestionReason.SICK else SuggestionReason.REST_READINESS
            return build(input, SuggestionFocus.REST, listOf(Slot.MOBILITY, Slot.MOBILITY, Slot.MOBILITY), REST_MINUTES, reasons, planned)
        }
        if (ReadinessReason.LOW_ENERGY in readiness.reasons || ReadinessReason.LOW_SLEEP in readiness.reasons) {
            budget = max(15, (budget * 0.6).roundToInt())
            reasons += SuggestionReason.LOW_ENERGY
        }
        val climbingPaused = input.injuries.any { it.isActive && it.climbingPaused }

        // 2. The week plan wins unless readiness or an injury rules it out.
        when {
            planned == PLAN_REST -> {
                reasons.add(0, SuggestionReason.WEEK_PLAN_REST)
                return build(input, SuggestionFocus.REST, listOf(Slot.MOBILITY, Slot.MOBILITY, Slot.MOBILITY), REST_MINUTES, reasons, planned)
            }
            planned == PLAN_BOARD -> {
                if (climbingPaused || readiness.avoidClimbing) {
                    reasons += if (climbingPaused) SuggestionReason.PLAN_REPLACED_FOR_INJURY else SuggestionReason.BOARD_SKIPPED_TODAY
                } else {
                    reasons.add(0, SuggestionReason.WEEK_PLAN_BOARD)
                    return boardDay(input, budget, reasons, planned)
                }
            }
            planned != null -> {
                val routine = resolve(planned, input.routines)
                if (routine != null) {
                    val kept = routine.items.filter { item ->
                        val def = input.catalog.fallbackFor(item.slug)
                        InjuryAdvisor.assess(def, input.injuries).verdict != InjuryVerdict.AVOID &&
                            !(climbingPaused && def.needsClimbingWall)
                    }
                    val removed = routine.items.size - kept.size
                    if (kept.isNotEmpty() && removed * 2 <= routine.items.size) {
                        reasons.add(0, SuggestionReason.WEEK_PLAN)
                        if (removed > 0) reasons += SuggestionReason.PLAN_FILTERED_FOR_INJURY
                        val r = routine.copy(id = "suggestion", items = kept, builtinKey = "suggestion")
                        return SessionSuggestion(SuggestionFocus.PLANNED, r, reasons.distinct(), estimateMinutes(kept, input.catalog), planned)
                    }
                    reasons += SuggestionReason.PLAN_REPLACED_FOR_INJURY
                }
            }
        }

        // 3. Injury with climbing paused: train around it.
        if (climbingPaused) {
            reasons.add(0, SuggestionReason.INJURY_CLIMBING_PAUSED)
            val slots = listOf(Slot.WARMUP, Slot.FINGER_MAIN, Slot.PULL, Slot.PULL, Slot.ANTAGONIST, Slot.CORE, Slot.LEGS)
            return build(input, SuggestionFocus.INJURY_SAFE, slots, budget, reasons, planned)
        }

        // 4. Finger load in the last 48 hours decides between finger work and the rest.
        val fingersLoaded = fingerLoadedRecently(input)
        val skinLow = ReadinessReason.SKIN_LOW in readiness.reasons
        val focus = when {
            fingersLoaded -> { reasons += SuggestionReason.FINGERS_LOADED_RECENTLY; null }
            readiness.avoidMaxFingerLoad -> { reasons += SuggestionReason.FINGERS_TIRED; null }
            skinLow -> { reasons += SuggestionReason.SKIN_LOW; null }
            else -> { reasons += SuggestionReason.FINGERS_RESTED; SuggestionFocus.FINGER_STRENGTH }
        } ?: run {
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
        if (input.profile.goal == AthleteGoal.BUILD_STRENGTH) reasons += SuggestionReason.GOAL_STRENGTH
        val result = build(input, focus, slotsFor(focus), budget, reasons.toMutableList(), planned)
        // Without a hangboard or block there is no finger session to suggest.
        val hasFingerMain = result.routine.items.any { !it.warmup && input.catalog[it.slug]?.category == ExerciseCategoryV2.FINGER }
        if (focus == SuggestionFocus.FINGER_STRENGTH && !hasFingerMain) {
            return build(input, SuggestionFocus.PULL_PUSH, slotsFor(SuggestionFocus.PULL_PUSH), budget, reasons, planned)
        }
        return result
    }

    private fun slotsFor(focus: SuggestionFocus): List<Slot> = when (focus) {
        SuggestionFocus.FINGER_STRENGTH -> listOf(Slot.WARMUP, Slot.FINGER_MAIN, Slot.PULL, Slot.ANTAGONIST, Slot.CORE)
        SuggestionFocus.LEGS_CORE -> listOf(Slot.WARMUP, Slot.LEGS, Slot.LEGS, Slot.LEGS, Slot.CORE, Slot.CORE, Slot.MOBILITY)
        else -> listOf(Slot.WARMUP, Slot.PULL, Slot.PULL, Slot.PUSH, Slot.ANTAGONIST, Slot.ANTAGONIST, Slot.CORE)
    }

    // ── Focus helpers ────────────────────────────────────────────────

    private fun resolve(entry: String, routines: List<Routine>): Routine? =
        if (entry.startsWith("builtin:")) BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
        else routines.firstOrNull { it.id == entry }

    private fun fingerLoadedRecently(input: SuggestionInput): Boolean {
        val yesterday = input.today.minus(DatePeriod(days = 1))
        val window = setOf(input.today, yesterday)
        val climbed = window.any { d -> input.activities[d]?.let { it.climbingMinutes > 0 || it.climbingEfforts > 0 } == true }
        val fingerSets = input.recentSets.any { (day, set) ->
            day in window && set.isCompleted && set.setType != SetType.WARMUP &&
                LoadDomain.FINGER in input.catalog.fallbackFor(set.exerciseSlug).domains
        }
        return climbed || fingerSets
    }

    private fun lastDay(input: SuggestionInput, categories: Set<ExerciseCategoryV2>): LocalDate? =
        input.lastTrained.filterKeys { slug -> input.catalog[slug]?.category in categories }.values.maxOrNull()

    private fun boardDay(input: SuggestionInput, budget: Int, reasons: MutableList<SuggestionReason>, planned: String?): SessionSuggestion {
        val warmup = BuiltinRoutines.byKey(BuiltinRoutines.WARMUP_BOARD)?.items.orEmpty()
        val picked = pickSlots(input, listOf(Slot.ANTAGONIST, Slot.ANTAGONIST), allowWall = true, focus = SuggestionFocus.BOARD_DAY,
            taken = warmup.map { it.slug }.toMutableSet())
            .map { it.copy(sets = min(it.sets, 2)) }
        val items = warmup + picked
        return SessionSuggestion(SuggestionFocus.BOARD_DAY, routineOf(SuggestionFocus.BOARD_DAY, items), reasons.distinct(),
            estimateMinutes(items, input.catalog), planned)
    }

    // ── Building ─────────────────────────────────────────────────────

    private fun build(
        input: SuggestionInput,
        focus: SuggestionFocus,
        slots: List<Slot>,
        budgetMinutes: Int,
        reasons: MutableList<SuggestionReason>,
        planned: String?,
    ): SessionSuggestion {
        var items = pickSlots(input, slots, allowWall = false, focus = focus, taken = mutableSetOf())
        if (items.any { it.slug in input.favorites && it.warmup.not() }) reasons += SuggestionReason.FAVORITES_USED
        val fitted = fitToBudget(items, budgetMinutes, input.catalog)
        if (fitted != items) reasons += SuggestionReason.SHORTENED_TO_TIME
        items = fitted
        return SessionSuggestion(focus, routineOf(focus, items), reasons.distinct(), estimateMinutes(items, input.catalog), planned)
    }

    private fun routineOf(focus: SuggestionFocus, items: List<RoutineItem>) =
        Routine(id = "suggestion", name = focus.name, items = items, builtinKey = "suggestion")

    /** Athlete level from what is known about them; keeps beginners away from one-arm and campus work. */
    private fun level(input: SuggestionInput): Int {
        var level = 2
        if (input.benchmarkSlugs.size >= 3) level++
        if ("pull.weighted_pull_up" in input.benchmarkSlugs && "finger.max_hang" in input.benchmarkSlugs) level++
        return level
    }

    private fun pickSlots(
        input: SuggestionInput,
        slots: List<Slot>,
        allowWall: Boolean,
        focus: SuggestionFocus,
        taken: MutableSet<String>,
    ): List<RoutineItem> {
        val profile = input.profile
        val filter = CatalogFilter(
            ownedEquipment = if (profile.equipmentConfigured) profile.equipment else null,
            withoutClimbing = !allowWall,
        )
        val level = level(input)
        val fingerFocus = focus == SuggestionFocus.FINGER_STRENGTH || focus == SuggestionFocus.INJURY_SAFE
        val result = mutableListOf<RoutineItem>()
        for (slot in slots) {
            val base = input.catalog.all.filter { def ->
                val verdict = InjuryAdvisor.assess(def, input.injuries).verdict
                // Around an injury only what does not load it at all, or the healthy side.
                val allowed = if (focus == SuggestionFocus.INJURY_SAFE) verdict == InjuryVerdict.OK || verdict == InjuryVerdict.ONE_SIDE_ONLY
                    else verdict != InjuryVerdict.AVOID
                def.slug !in taken && fits(slot, def, fingerFocus) && filter.matches(def) && allowed &&
                    // One progression chain per session: no easier/harder neighbour of a pick.
                    taken.none { t -> def.easier == t || def.harder == t }
            }
            val withinLevel = base.filter { it.difficulty <= level }.ifEmpty { base }
            val choice = withinLevel.maxWithOrNull(compareBy<ExerciseDefinition> { score(input, it, slot, focus) }.thenBy { it.slug })
                ?: continue
            taken += choice.slug
            result += WorkoutPlanner.itemFor(choice).let { if (slot == Slot.WARMUP) it.copy(warmup = true, sets = min(it.sets, 4)) else it }
        }
        return result
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
            Slot.ANTAGONIST -> def.category == ExerciseCategoryV2.ANTAGONIST && !loadsFingers
            Slot.CORE -> def.category == ExerciseCategoryV2.CORE && !loadsFingers
            Slot.LEGS -> def.category == ExerciseCategoryV2.LEGS
            Slot.MOBILITY -> def.category == ExerciseCategoryV2.MOBILITY && !loadsFingers
        }
    }

    private fun score(input: SuggestionInput, def: ExerciseDefinition, slot: Slot, focus: SuggestionFocus): Double {
        var s = 0.0
        if (def.slug in input.favorites) s += 3.0
        if (def.slug in input.benchmarkSlugs) s += 2.0
        val last = input.lastTrained[def.slug]
        s += if (last == null) 2.0 else min(14L, (input.today.toEpochDays() - last.toEpochDays()).toLong().coerceAtLeast(0L)) / 3.5
        if (input.profile.goal == AthleteGoal.BUILD_STRENGTH && "strength" in def.tags) s += 1.5
        if (input.profile.goal == AthleteGoal.PERFORM && def.category in setOf(ExerciseCategoryV2.FINGER, ExerciseCategoryV2.PULL, ExerciseCategoryV2.ANTAGONIST)) s += 0.5
        if ("beginner_friendly" in def.tags && level(input) <= 2) s += 0.5
        if (slot == Slot.WARMUP && focus == SuggestionFocus.FINGER_STRENGTH && def.slug == "warmup.finger_ramp") s += 10.0
        return s + jitter(def.slug, input.today, input.variant)
    }

    /** Deterministic noise in [0, 2.5) so "another suggestion" reshuffles close calls only. */
    private fun jitter(slug: String, day: LocalDate, variant: Int): Double {
        var h = 1469598103934665603L
        for (c in "$slug|${day.toEpochDays()}|$variant") { h = (h xor c.code.toLong()) * 1099511628211L }
        return ((h ushr 11) % 1000L).toDouble() / 1000.0 * 2.5
    }

    // ── Time budget ──────────────────────────────────────────────────

    fun estimateMinutes(items: List<RoutineItem>, catalog: ExerciseCatalog): Int =
        (items.sumOf { itemSeconds(it, catalog.fallbackFor(it.slug)) } / 60.0).roundToInt().coerceAtLeast(1)

    private fun itemSeconds(item: RoutineItem, def: ExerciseDefinition): Int {
        val work = when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> (item.repsMax ?: item.repsMin ?: 8) * 3
            ExerciseKind.TIME, ExerciseKind.HANG -> item.durationS ?: 20
            ExerciseKind.INTERVAL -> (item.repsPerSet ?: 6) * ((item.workS ?: 7) + (item.restBetweenS ?: 3))
            ExerciseKind.CLIMB -> 60
        }
        val sides = if (def.unilateral) 2 else 1
        val perSet = work * sides + (item.restS ?: def.defaults.restS ?: 60)
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
