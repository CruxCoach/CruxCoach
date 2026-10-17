// Port of the view model in androidApp/.../ui/training/workout/WorkoutSummaryScreen.kt (0e1bad0c6).
package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

internal data class SummaryRunState(
    val loading: Boolean = true,
    val workout: Workout? = null,
    val blocks: List<Pair<ExerciseDefinition, List<ExerciseSet>>> = emptyList(),
    val summary: WorkoutSummary? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val routineSaved: Boolean = false,
    /** Patterns across sessions per exercise (FEAT-071): stall, decline, twice too easy/hard. */
    val trends: List<Pair<String, ProgressionTrend>> = emptyList(),
    /** Own workouts containing each exercise, for the swap prompt. */
    val routineCounts: Map<String, Int> = emptyMap(),
    /** One-shot message: swapped in n workouts (≥ 0), deload started (-1). */
    val message: Int? = null,
    /** Total load moved in weighted rep work (kg × reps) and time under load on holds, for the reward header. */
    val volumeKg: Double = 0.0,
    val hangSeconds: Int = 0,
    val streak: StreakState? = null,
    /** The training was deleted: the screen leaves. */
    val deleted: Boolean = false,
    /** Loads the athlete lowered that the next training plans ([AthleteService.learnOwnLoads]). */
    val ownLoads: List<Benchmark> = emptyList(),
    /** Today's training only (audit P2-15): when the fingers take max work again, after finger sets. */
    val fingersFreeOn: LocalDate? = null,
    /** Today's nutrition after the training: the day type and what is left of carbs and protein. */
    val fuelLeft: SummaryFuelLeft? = null,
    /** What today's form trimmed in this training, while it was not taken back. */
    val dayForm: ReadinessModifier? = null,
    /** What the athlete changed in today's suggestion: asked once "suggest it like this from now on?" (comparison review #3). */
    val keep: List<PreferenceLearning.Change> = emptyList(),
    /** The answer just given, for the snackbar and "Rückgängig". */
    val keepAnswer: SummaryKeepAnswer? = null,
)

/** "Ja" ([until] set) or "Nur heute" for [changes]; [eventIds] go again with "Rückgängig". */
internal data class SummaryKeepAnswer(val changes: List<PreferenceLearning.Change>, val eventIds: List<String>, val until: LocalDate?)

internal data class SummaryFuelLeft(val dayLoad: DayLoad, val carbsG: Int, val proteinG: Int)

/** The end of a training: bests, next steps, own loads, the keep question, recovery and food. */
internal class WorkoutSummaryPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(SummaryRunState())
    val state: StateFlow<SummaryRunState> = _state.asStateFlow()

    fun load(workoutId: String) {
        if (_state.value.workout?.id == workoutId) return
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val workout = repo.workout(workoutId)
            val catalog = service.catalog
            val sets = repo.setsFor(workoutId).filter { it.isCompleted }
            val blocks = sets.groupBy { it.blockIndex }.entries.sortedBy { it.key }.map { (_, rows) ->
                catalog.fallbackFor(rows.first().exerciseSlug) to rows.sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
            }
            val profile = repo.profile()
            val routines = repo.routines()
            val slugs = blocks.map { it.first.slug }.distinct()
            val trends = slugs.mapNotNull { slug ->
                val def = catalog[slug] ?: return@mapNotNull null
                // History is newest first; one list per training.
                val sessions = repo.history(slug, 200).filter { it.isCompleted }
                    .groupBy { it.workoutId }.values.take(6).map { it.toList() }
                val trend = ProgressionAdvisor.trend(def, sessions, profile.smallestIncrementKg)
                trend.takeIf { it.kind != TrendKind.NONE && it.kind != TrendKind.PROGRESSING }?.let { slug to it }
            }
            val work = sets.filter { it.setType == SetType.WORK }
            val volume = work.sumOf { s ->
                val def = catalog.fallbackFor(s.exerciseSlug)
                val loaded = def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS
                if ((def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.LOAD_REPS) && loaded)
                    (StrengthMath.effectiveLoad(def.load, s.loadKg, s.bodyweightKg) ?: 0.0) * (s.reps ?: 0)
                else 0.0
            }
            val hang = work.sumOf { s ->
                val def = catalog.fallbackFor(s.exerciseSlug)
                when (def.kind) {
                    ExerciseKind.HANG -> s.durationS ?: 0.0
                    ExerciseKind.INTERVAL -> (s.workS ?: def.defaults.workS?.toDouble() ?: 7.0) * (s.repsPerSet ?: def.defaults.repsPerSet ?: 6)
                    else -> 0.0
                }
            }
            val streak = runCatching { service.streak(profile, service.activities(7 * 26)) }.getOrNull()
            val today = service.today()
            val isToday = workout?.day == today.toString()
            // Off-wall finger sets block today and tomorrow (one more day from 40 on), as the suggestion counts it.
            val masters = profile.coach.ageBand == AgeBand.Y40_54 || profile.coach.ageBand == AgeBand.Y55_PLUS
            // Test sets count too: a first max hang without a value is a test, and it loads the fingers fully.
            val fingerWork = sets.any { it.setType != SetType.WARMUP &&
                com.cruxcoach.athlete.catalog.LoadDomain.FINGER in catalog.fallbackFor(it.exerciseSlug).domains }
            val fingersFreeOn = if (isToday && fingerWork) today.plus(DatePeriod(days = if (masters) 3 else 2)) else null
            val fuelLeft = if (!isToday) null else runCatching {
                val targets = service.fuelTargets(service.activities(1)[today.toString()], profile) ?: return@runCatching null
                val eaten = repo.foodLog(today.toString())
                SummaryFuelLeft(targets.dayLoad, (targets.carbsG - eaten.sumOf { it.carbsG ?: 0.0 }).roundToInt().coerceAtLeast(0),
                    (targets.proteinG - eaten.sumOf { it.proteinG ?: 0.0 }).roundToInt().coerceAtLeast(0))
            }.getOrNull()
            _state.update {
                it.copy(
                    loading = false, workout = workout, blocks = blocks, catalog = catalog,
                    volumeKg = volume, hangSeconds = hang.roundToInt(), streak = streak,
                    summary = if (workout != null) service.summarize(workoutId) else null,
                    units = profile.units,
                    trends = trends,
                    routineCounts = slugs.associateWith { s -> routines.count { r -> r.items.any { i -> i.slug == s } } },
                    ownLoads = workout?.let { w -> runCatching { service.ownLoadsOf(w) }.getOrNull() }.orEmpty(),
                    fingersFreeOn = fingersFreeOn, fuelLeft = fuelLeft, dayForm = service.dayForm(workoutId),
                    keep = workout?.let { service.keepQuestion(it) }.orEmpty(),
                )
            }
            if (workout != null) logCompletion(workout, sets)
        }
    }

    /**
     * The answer to "suggest it like this from now on?": [keep] keeps the picks until the block ends (the chosen
     * exercises lead their slots, the replaced ones rest); "only today" changes nothing beyond the light learning.
     */
    fun answerKeep(keep: Boolean) {
        val workout = _state.value.workout ?: return
        val changes = _state.value.keep.takeIf { it.isNotEmpty() } ?: return
        _state.update { it.copy(keep = emptyList()) }
        scope.launch(io) {
            service.ensureReady()
            val answer = if (keep) service.keepChoices(workout, changes).let { (ids, until) -> SummaryKeepAnswer(changes, ids, until) }
                else SummaryKeepAnswer(changes, listOfNotNull(service.keepTodayOnly(workout)), null)
            _state.update { it.copy(keepAnswer = answer) }
        }
    }

    /** "Rückgängig": the answer goes from the ledger and the question is back. */
    fun undoKeep(answer: SummaryKeepAnswer) {
        _state.update { it.copy(keepAnswer = null, keep = answer.changes) }
        scope.launch(io) { service.ensureReady(); answer.eventIds.forEach(service::forgetSuggestion) }
    }

    fun consumeKeepAnswer() = _state.update { it.copy(keepAnswer = null) }

    /**
     * A training started from today's suggestion tells the coach how much of
     * it was done (the player logs this on finish; this covers the list view).
     */
    private fun logCompletion(workout: Workout, completed: List<ExerciseSet>) {
        if (workout.routineId?.startsWith("suggestion") != true) return
        val events = service.repo.suggestionEventsForDay(workout.day)
        if (events.any { it.kind == SuggestionEventKind.COMPLETED && it.workoutId == workout.id }) return
        val started = events.firstOrNull { it.kind == SuggestionEventKind.STARTED && it.workoutId == workout.id }
        val done = completed.count { it.setType == SetType.WORK }
        val planned = started?.value?.takeIf { it > 0 } ?: done.toDouble()
        val share = if (planned > 0) (done / planned).coerceIn(0.0, 1.0) else 0.0
        service.logSuggestion(SuggestionEventKind.COMPLETED, started?.focus, completed.map { it.exerciseSlug }.distinct(),
            workoutId = workout.id, value = share)
        workout.sessionRpe?.let { rpe ->
            service.logSuggestion(SuggestionEventKind.FEEDBACK, "session_rpe", emptyList(), workoutId = workout.id, value = rpe.toDouble())
        }
    }

    /** What the last summary action changed, for "Rückgängig" (audit P2-14): routines before a swap, or the block start. */
    private var undoRoutines: List<Routine> = emptyList()
    private var undoSwapEvent: String? = null
    private var undoBlockStart: String? = null
    private var undoDeload = false

    /** Replaces [from] with [to] in every own workout that contains it, keeping sets, rest and sides. */
    fun swapInRoutines(from: String, to: String) {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val target = service.catalog[to] ?: return@launch
            var count = 0
            val before = repo.routines().filter { r -> r.items.any { it.slug == from } }
            undoRoutines = before; undoDeload = false
            before.forEach { r ->
                // The replacement keeps its place in a circuit (rounds, rests); normalize drops it if it cannot be in one.
                val items = Circuits.normalize(r.items.map { item ->
                    if (item.slug != from) item
                    else WorkoutPlanner.itemFor(target).copy(sets = item.sets, restS = item.restS, sides = item.sides, warmup = item.warmup,
                        circuit = item.circuit, circuitRestS = item.circuitRestS, roundRestS = item.roundRestS)
                }, service.catalog)
                repo.saveRoutine(r.copy(items = items, updatedAt = nowMs()))
                count++
            }
            undoSwapEvent = service.logSuggestion(SuggestionEventKind.SWAPPED, null, listOf(from, to))
            _state.update { s -> s.copy(message = count, routineCounts = s.routineCounts - from) }
        }
    }

    /** Starts a lighter week now: the training block jumps to its deload week. */
    fun startDeload() {
        scope.launch(io) {
            service.ensureReady()
            val start = service.today().minus(DatePeriod(days = 7 * (TrainingBlocks.INTRO_WEEKS + TrainingBlocks.BUILD_WEEKS)))
            undoBlockStart = service.repo.profile().coach.blockStartDay; undoDeload = true
            service.repo.updateProfile { p -> p.copy(coach = p.coach.copy(blockStartDay = start.toString())) }
            _state.update { it.copy(message = -1) }
        }
    }

    /** "Rückgängig" for the last swap in the workouts or the lighter week. */
    fun undoLastAction() {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            if (undoDeload) {
                val previous = undoBlockStart
                repo.updateProfile { p -> p.copy(coach = p.coach.copy(blockStartDay = previous)) }
                undoDeload = false
            } else if (undoRoutines.isNotEmpty()) {
                val restored = undoRoutines
                restored.forEach { repo.saveRoutine(it.copy(updatedAt = nowMs())) }
                undoSwapEvent?.let(service::forgetSuggestion)
                undoRoutines = emptyList(); undoSwapEvent = null
                val routines = repo.routines()
                _state.update { s -> s.copy(routineCounts = s.routineCounts.keys.associateWith { slug ->
                    routines.count { r -> r.items.any { it.slug == slug } } }) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun saveRoutine(name: String) {
        val id = _state.value.workout?.id ?: return
        scope.launch(io) {
            service.ensureReady()
            service.saveRoutineFromWorkout(id, name.trim())
            _state.update { it.copy(routineSaved = true) }
        }
    }

    /** A training logged by mistake can go again, with its sets (device test 2026-10-10: there was no way). */
    fun delete() {
        val id = _state.value.workout?.id ?: return
        scope.launch(io) {
            service.ensureReady()
            service.discardWorkout(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun close() = scope.cancel()
}
