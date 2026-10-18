package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.Workout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One past training of the exercise: its completed sets in order. */
data class LibraryHistorySession(val workoutId: String, val completedAt: Long, val sets: List<ExerciseSet>)

/** Best result for one comparable key (edge, grip, side), with the set that produced it. */
data class LibraryBestEntry(val key: PersonalRecords.Key, val kind: RecordKind, val value: Double, val set: ExerciseSet)

data class ExerciseDetailState(
    val loading: Boolean = true,
    val definition: ExerciseDefinition? = null,
    val chain: List<ExerciseDefinition> = emptyList(),
    val advice: InjuryAdvice = InjuryAdvice(InjuryVerdict.OK),
    val favorite: Boolean = false,
    val openWorkout: Workout? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val sessions: List<LibraryHistorySession> = emptyList(),
    val bests: List<LibraryBestEntry> = emptyList(),
    /** One-shot: the training the exercise was added to; null when none is waiting. */
    val navigateToWorkout: String? = null,
    val addRejected: Boolean = false,
    /** "Never suggest": never part of a suggestion (FEAT-071). */
    val excluded: Boolean = false,
    /** An own exercise was deleted: the screen closes. */
    val deleted: Boolean = false,
    val failed: Boolean = false,
)

/** Port of Android `ExerciseDetailViewModel`. */
class ExerciseDetailPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(ExerciseDetailState())
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()
    private var loadedSlug: String? = null
    private var job: Job? = null
    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(loading = false, failed = true) } }

    fun load(slug: String) {
        if (loadedSlug == slug) return
        loadedSlug = slug
        job?.cancel()
        job = scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                combine(
                    service.catalogStore.catalog,
                    repo.observeProfile(),
                    repo.observeActiveInjuries(),
                    repo.observeFavorites(),
                    repo.observeOpenWorkout(),
                ) { catalog, profile, injuries, favorites, open ->
                    val def = catalog[slug]
                    val history = repo.history(slug, 200)
                    ExerciseDetailState(
                        loading = false,
                        definition = def,
                        chain = if (def != null) catalog.chainOf(def.slug) else emptyList(),
                        advice = if (def != null) InjuryAdvisor.assess(def, injuries) else InjuryAdvice(InjuryVerdict.OK),
                        favorite = slug in favorites,
                        excluded = slug in profile.excludedExercises,
                        openWorkout = open,
                        profile = profile,
                        sessions = sessionsOf(history),
                        bests = if (def != null) bestsOf(def, history) else emptyList(),
                    )
                }.collect { fresh ->
                    _state.update { old -> fresh.copy(navigateToWorkout = old.navigateToWorkout, addRejected = old.addRejected, deleted = old.deleted) }
                }
            }
        }
    }

    private fun sessionsOf(history: List<ExerciseSet>): List<LibraryHistorySession> =
        history.filter { it.isCompleted }
            .groupBy { it.workoutId }
            .map { (id, sets) ->
                LibraryHistorySession(id, sets.maxOf { it.completedAt ?: 0L },
                    sets.sortedWith(compareBy({ it.blockIndex }, { it.setIndex }, { it.side?.ordinal ?: -1 })))
            }
            .sortedByDescending { it.completedAt }
            .take(5)

    private fun bestsOf(def: ExerciseDefinition, history: List<ExerciseSet>): List<LibraryBestEntry> =
        history.mapNotNull { set -> PersonalRecords.score(def, set)?.let { (kind, value) -> LibraryBestEntry(PersonalRecords.keyOf(set), kind, value, set) } }
            .groupBy { it.key }
            .map { (_, entries) -> entries.maxBy { it.value } }
            .sortedWith(compareBy<LibraryBestEntry>({ it.key.edgeMm ?: Int.MAX_VALUE }, { it.key.grip?.ordinal ?: -1 }, { it.key.side?.ordinal ?: -1 }))

    fun toggleFavorite() {
        val slug = loadedSlug ?: return
        val favorite = !_state.value.favorite
        io { service.repo.setFavorite(slug, favorite) }
    }

    fun toggleExcluded() {
        val slug = loadedSlug ?: return
        val excluded = !_state.value.excluded
        io { service.setExcluded(slug, excluded) }
    }

    /** Adds the exercise to the running training, or starts one with it ([title]: the exercise's name). */
    fun addToTraining(title: String) {
        val slug = loadedSlug ?: return
        if (_state.value.advice.verdict == InjuryVerdict.AVOID) {
            _state.update { it.copy(addRejected = true) }
            return
        }
        io {
            val workoutId = service.repo.openWorkout()?.id ?: service.startWorkout(null, title)
            val ok = service.addExercise(workoutId, slug)
            _state.update { if (ok) it.copy(navigateToWorkout = workoutId) else it.copy(addRejected = true) }
        }
    }

    fun consumeEvents() = _state.update { it.copy(navigateToWorkout = null, addRejected = false) }

    /** Deletes an own exercise (a typo from typed workouts, an idea that did not stick). */
    fun deleteCustom() {
        val slug = loadedSlug ?: return
        io {
            job?.cancel()
            service.deleteCustomExercise(slug)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); block() } }
    }
}

data class AddToWorkoutState(
    val def: ExerciseDefinition? = null,
    val openWorkout: Workout? = null,
    val routines: List<Routine> = emptyList(),
    val favorite: Boolean = false,
    /** One-shot: the training the exercise went into; null when none is waiting. */
    val trainingStarted: String? = null,
    /** The open injury rules the exercise out. */
    val rejected: Boolean = false,
    /** Name of the workout the exercise was just added to; null when none. */
    val addedToRoutine: String? = null,
    val failed: Boolean = false,
)

/**
 * Port of Android `AddToWorkoutViewModel`: "Plan this exercise" – into the
 * running training, a new training, one of the athlete's own workouts or a
 * new workout, plus the favourite star.
 */
class AddToWorkoutPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(AddToWorkoutState())
    val state: StateFlow<AddToWorkoutState> = _state.asStateFlow()
    private var slug: String? = null
    private var job: Job? = null
    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(failed = true) } }

    fun load(slug: String) {
        if (this.slug == slug) return
        this.slug = slug
        job?.cancel()
        job = scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                combine(repo.observeOpenWorkout(), repo.observeRoutines(), repo.observeFavorites()) { open, routines, favs ->
                    Triple(open, routines, slug in favs)
                }.collect { (open, routines, fav) ->
                    _state.update { it.copy(def = service.catalog[slug], openWorkout = open, routines = routines, favorite = fav) }
                }
            }
        }
    }

    fun addToOpen() = io { slug ->
        val open = service.repo.openWorkout() ?: return@io
        if (service.addExercise(open.id, slug)) _state.update { it.copy(trainingStarted = open.id) }
        else _state.update { it.copy(rejected = true, addedToRoutine = null) }
    }

    fun startNew(title: String) = io { slug ->
        // Check first: a rejected exercise must not leave an empty training behind.
        val def = service.catalog[slug] ?: return@io
        if (InjuryAdvisor.assess(def, service.repo.activeInjuries()).verdict == InjuryVerdict.AVOID) {
            _state.update { it.copy(rejected = true, addedToRoutine = null) }
            return@io
        }
        val hadOpen = service.repo.openWorkout() != null
        val id = service.startWorkout(null, title)
        if (service.addExercise(id, slug)) _state.update { it.copy(trainingStarted = id) }
        else {
            if (!hadOpen) service.discardWorkout(id)
            _state.update { it.copy(rejected = true, addedToRoutine = null) }
        }
    }

    fun addToRoutine(routineId: String) = io { slug ->
        val def = service.catalog[slug] ?: return@io
        val routine = service.repo.routines().firstOrNull { it.id == routineId } ?: return@io
        service.repo.saveRoutine(routine.copy(items = routine.items + WorkoutPlanner.itemFor(def), updatedAt = nowMs()))
        _state.update { it.copy(addedToRoutine = routine.name, rejected = false) }
    }

    fun toggleFavorite() = io { slug -> service.repo.setFavorite(slug, !_state.value.favorite) }

    fun consumeStarted() = _state.update { it.copy(trainingStarted = null) }

    fun close() = scope.cancel()

    private fun io(block: suspend (String) -> Unit) {
        val s = slug ?: return
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); block(s) } }
    }
}
