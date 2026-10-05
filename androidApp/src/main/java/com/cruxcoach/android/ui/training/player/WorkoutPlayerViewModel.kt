package com.cruxcoach.android.ui.training.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.Capacity
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SetType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One set as the player shows it. */
data class PlayerItem(val set: ExerciseSet, val def: ExerciseDefinition, val position: PlayerQueue.Position)

enum class PlayerPhase { LOADING, NO_WORKOUT, SET, TIMER, REST, COMPLETE }

/** What the set just done changed — shown once, quietly, on the rest screen. */
data class PlayerFeedback(
    val def: ExerciseDefinition,
    val record: PersonalRecord?,
    val raisedCapacity: Capacity?,
)

data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.LOADING,
    val workout: Workout? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val bodyweight: Double? = null,
    val sets: List<ExerciseSet> = emptyList(),
    /** The set the SET view shows. */
    val current: PlayerItem? = null,
    /** The set after [current], for the small "after this" line. */
    val upcoming: PlayerItem? = null,
    /** During a rest: the set just done (reserve question) and the next one (preview). */
    val lastDone: PlayerItem? = null,
    val next: PlayerItem? = null,
    val sideSwitch: Boolean = false,
    /** The rest timer has been started for this rest (guards against advancing on a stale idle state). */
    val restArmed: Boolean = false,
    val restFinished: Boolean = false,
    val feedback: PlayerFeedback? = null,
    val completedCount: Int = 0,
    val closing: Boolean = false,
)

sealed interface PlayerEvent {
    data class Finished(val workoutId: String) : PlayerEvent
    data object Discarded : PlayerEvent
}

/**
 * Guided training: one set at a time, then a rest of its own, then the next
 * set. The pointer to the shown set is local (skipping leaves a set open);
 * all writes go through one ordered queue like the list logger, and the rest
 * runs on the shared board rest timer, so the countdown, alarm and
 * notification also work with the screen off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    /** The shared rest timer, for the countdown ring. */
    val restTimer: StateFlow<RestTimerState> get() = service.restTimer

    /** Set the athlete moved to by skipping; null = the first open set. */
    private var focusId: String? = null
    private var advanceJob: Job? = null

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            for (write in writes) runCatching { write() }
                .onFailure { android.util.Log.e("WorkoutPlayerVM", "write failed", it) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val bodyweight = service.currentBodyweight()
            repo.observeOpenWorkout()
                .flatMapLatest { w ->
                    if (w == null) flowOf<Pair<Workout?, List<ExerciseSet>>>(null to emptyList())
                    else repo.observeSets(w.id).map<List<ExerciseSet>, Pair<Workout?, List<ExerciseSet>>> { w to it }
                }
                .combine(repo.observeProfile()) { pair, profile -> Triple(pair.first, pair.second, profile) }
                .collect { (workout, sets, profile) -> onData(workout, sets, profile, bodyweight) }
        }
        // Rest end: the shared timer finished (or was ended elsewhere) → next set.
        viewModelScope.launch {
            service.restTimer.collect { timer -> onRestTimer(timer) }
        }
    }

    private fun item(sets: List<ExerciseSet>, set: ExerciseSet): PlayerItem =
        PlayerItem(set, service.catalog.fallbackFor(set.exerciseSlug), PlayerQueue.position(sets, set))

    private fun onData(workout: Workout?, sets: List<ExerciseSet>, profile: AthleteProfile, bodyweight: Double?) {
        _state.update { st ->
            if (st.closing) return@update st.copy(profile = profile)
            if (workout == null) return@update st.copy(phase = PlayerPhase.NO_WORKOUT, workout = null, sets = emptyList(),
                current = null, upcoming = null, profile = profile)
            val ordered = PlayerQueue.ordered(sets)
            val focus = focusId?.let { id -> ordered.firstOrNull { it.id == id && !it.isCompleted } }
            if (focus == null) focusId = null
            val currentSet = focus ?: PlayerQueue.current(ordered)
            val upcomingSet = currentSet?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { it.id != currentSet.id }
            val phase = when {
                st.phase == PlayerPhase.REST || st.phase == PlayerPhase.TIMER -> st.phase
                currentSet == null && ordered.isNotEmpty() -> PlayerPhase.COMPLETE
                currentSet == null -> PlayerPhase.COMPLETE
                else -> PlayerPhase.SET
            }
            st.copy(
                phase = phase,
                workout = workout,
                profile = profile,
                bodyweight = bodyweight,
                sets = ordered,
                current = currentSet?.let { item(ordered, it) },
                upcoming = upcomingSet?.let { item(ordered, it) },
                // Keep the rest screen's items fresh (e.g. a reserve answer just saved).
                lastDone = st.lastDone?.let { ld -> ordered.firstOrNull { it.id == ld.set.id }?.let { item(ordered, it) } ?: ld },
                next = st.next?.let { nx -> ordered.firstOrNull { it.id == nx.set.id }?.let { item(ordered, it) } ?: nx },
                completedCount = ordered.count { it.isCompleted },
            )
        }
    }

    private fun onRestTimer(timer: RestTimerState) {
        val st = _state.value
        if (st.phase != PlayerPhase.REST || !st.restArmed) return
        when {
            timer.isFinished && !st.restFinished -> {
                _state.update { it.copy(restFinished = true) }
                advanceJob?.cancel()
                advanceJob = viewModelScope.launch { delay(GO_DISPLAY_MS); advance() }
            }
            !timer.isRunning && !timer.isFinished -> advance() // ended early or from the banner
        }
    }

    // ── Set view actions ─────────────────────────────────────────────

    /** Optimistic: the value changes on screen at once, the database follows in order. */
    fun update(set: ExerciseSet) {
        replaceLocal(set)
        enqueue { service.updateSet(set) }
    }

    fun startTimer() {
        if (_state.value.current != null) _state.update { it.copy(phase = PlayerPhase.TIMER) }
    }

    fun cancelTimer() {
        _state.update { if (it.phase == PlayerPhase.TIMER) it.copy(phase = PlayerPhase.SET) else it }
    }

    /** Leaves the shown set open and moves to the next one. */
    fun skip() {
        val st = _state.value
        val current = st.current ?: return
        val next = PlayerQueue.nextAfter(st.sets, current.set)?.takeIf { it.id != current.set.id } ?: return
        focusId = next.id
        // A skipped set tells the coach something about the exercise (FEAT-071 ledger).
        val skipped = current.set
        if (skipped.setType == SetType.WORK) enqueue {
            service.logSuggestion(SuggestionEventKind.SKIPPED_SET, null, listOf(skipped.exerciseSlug), workoutId = skipped.workoutId)
        }
        _state.update { s ->
            val upcoming = PlayerQueue.nextAfter(s.sets, next)?.takeIf { it.id != next.id }
            s.copy(current = item(s.sets, next), upcoming = upcoming?.let { item(s.sets, it) })
        }
    }

    /** Ticks the shown set off and moves into the rest (or to the end). */
    fun completeCurrent() {
        val st = _state.value
        val current = st.current ?: return
        if (st.phase == PlayerPhase.REST) return
        val doneLocal = current.set.copy(completedAt = System.currentTimeMillis())
        val setsAfter = st.sets.map { if (it.id == doneLocal.id) doneLocal else it }
        val next = PlayerQueue.nextAfter(setsAfter, doneLocal)?.takeIf { !it.isCompleted && it.id != doneLocal.id }
        val rest = PlayerQueue.restAfter(doneLocal, next, current.def)
        focusId = next?.id
        advanceJob?.cancel()
        _state.update {
            it.copy(
                phase = when {
                    next == null -> PlayerPhase.COMPLETE
                    rest > 0 -> PlayerPhase.REST
                    else -> PlayerPhase.SET
                },
                sets = setsAfter,
                lastDone = item(setsAfter, doneLocal),
                next = next?.let { n -> item(setsAfter, n) },
                current = if (rest <= 0) next?.let { n -> item(setsAfter, n) } else it.current,
                sideSwitch = next != null && next.blockIndex == doneLocal.blockIndex &&
                    next.setIndex == doneLocal.setIndex && next.side != null && next.side != doneLocal.side,
                restArmed = false,
                restFinished = false,
                feedback = null,
                completedCount = setsAfter.count { s -> s.isCompleted },
            )
        }
        enqueue {
            val startRest = next != null && rest > 0
            val outcome = service.completeSetDetailed(current.set, startRest = startRest, restSeconds = rest)
            val now = _state.value
            if (startRest && (now.phase != PlayerPhase.REST || now.lastDone?.set?.id != current.set.id)) {
                // The athlete already ended this rest while the write was queued.
                service.cancelRest()
            }
            val feedback = if (outcome.record != null || outcome.raisedBenchmark != null)
                PlayerFeedback(current.def, outcome.record, outcome.capacity?.takeIf { outcome.raisedBenchmark != null }) else null
            withContext(Dispatchers.Main) {
                _state.update { s ->
                    if (s.lastDone?.set?.id != current.set.id) s
                    else s.copy(feedback = feedback, restArmed = s.phase == PlayerPhase.REST)
                }
                // The timer may already have run out while the write was queued.
                onRestTimer(service.restTimer.value)
            }
        }
    }

    // ── Rest actions ─────────────────────────────────────────────────

    fun setReserve(rir: Int) {
        val done = _state.value.lastDone?.set ?: return
        val updated = done.copy(rir = rir)
        _state.update { it.copy(lastDone = it.lastDone?.copy(set = updated)) }
        enqueue { service.updateSet(updated) }
    }

    fun adjustRest(deltaSeconds: Int) = enqueue { service.adjustRest(deltaSeconds) }

    /** "End rest": stop the shared timer; the timer observer moves on. */
    fun endRest() {
        if (_state.value.restArmed) enqueue { service.cancelRest() } else advance()
    }

    private fun advance() {
        advanceJob?.cancel()
        val st = _state.value
        if (st.phase != PlayerPhase.REST) return
        if (service.restTimer.value.isFinished) enqueue { service.dismissRestFinished() }
        val next = st.next
        focusId = next?.set?.id
        _state.update { s ->
            val ordered = s.sets
            val currentSet = next?.set?.let { n -> ordered.firstOrNull { it.id == n.id } } ?: PlayerQueue.current(ordered)
            val upcomingSet = currentSet?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { it.id != currentSet.id }
            s.copy(
                phase = if (currentSet == null) PlayerPhase.COMPLETE else PlayerPhase.SET,
                current = currentSet?.let { item(ordered, it) },
                upcoming = upcomingSet?.let { item(ordered, it) },
                lastDone = null, next = null, restArmed = false, restFinished = false, feedback = null, sideSwitch = false,
            )
        }
    }

    // ── Finish ───────────────────────────────────────────────────────

    fun finish(sessionRpe: Int?, notes: String?) {
        val workout = _state.value.workout ?: return
        val id = workout.id
        val sets = _state.value.sets
        _state.update { it.copy(closing = true) }
        enqueue {
            if (service.restTimer.value.isRunning || service.restTimer.value.isFinished) service.cancelRest()
            // A training started from today's suggestion: how much of it was done, and how hard it felt.
            if (workout.routineId?.startsWith("suggestion") == true) {
                val work = sets.filter { it.setType == SetType.WORK }
                val done = work.filter { it.isCompleted }
                if (work.isNotEmpty()) service.logSuggestion(SuggestionEventKind.COMPLETED, null,
                    done.map { it.exerciseSlug }.distinct(), workoutId = id, value = done.size.toDouble() / work.size)
                sessionRpe?.let { service.logSuggestion(SuggestionEventKind.FEEDBACK, "session_rpe", emptyList(), workoutId = id, value = it.toDouble()) }
            }
            service.finishWorkout(id, sessionRpe, notes)
            _events.emit(PlayerEvent.Finished(id))
        }
    }

    fun discard() {
        val id = _state.value.workout?.id ?: return
        _state.update { it.copy(closing = true) }
        enqueue {
            if (service.restTimer.value.isRunning || service.restTimer.value.isFinished) service.cancelRest()
            service.discardWorkout(id)
            _events.emit(PlayerEvent.Discarded)
        }
    }

    private fun replaceLocal(set: ExerciseSet) {
        _state.update { st ->
            st.copy(
                sets = st.sets.map { if (it.id == set.id) set else it },
                current = st.current?.let { if (it.set.id == set.id) it.copy(set = set) else it },
            )
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    companion object {
        /** How long "Los geht's" stays before the next set appears. */
        const val GO_DISPLAY_MS = 1_500L
    }
}
