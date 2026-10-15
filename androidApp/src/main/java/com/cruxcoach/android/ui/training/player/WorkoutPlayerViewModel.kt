package com.cruxcoach.android.ui.training.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.Capacity
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.SetAdjustment
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.android.athlete.PainAction
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
    /** How the rest of the block changed after [lastDone] (set-to-set autoregulation). */
    val adjustment: SetAdjustment? = null,
    /** One-shot result of "it hurts", for a short confirmation. */
    val painResult: com.cruxcoach.android.athlete.AthleteService.PainOutcome? = null,
    /** The exercise a pain swap brought in, for the confirmation. */
    val painNewDef: ExerciseDefinition? = null,
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
    /** A completed set the athlete went back to, to look at or correct it. */
    private var reviewId: String? = null
    /** Sets after which the athlete changed the next load by hand: their reserve answer no longer moves it. */
    private val manualAfter = mutableSetOf<String>()
    /** What autoregulation already applied per completed set, so a changed reserve answer never stacks steps. */
    private val applied = mutableMapOf<String, SetAdjustment?>()
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
            val review = reviewId?.let { id -> ordered.firstOrNull { it.id == id && it.isCompleted } }
            if (review == null) reviewId = null
            val focus = focusId?.let { id -> ordered.firstOrNull { it.id == id && !it.isCompleted } }
            if (focus == null) focusId = null
            val currentSet = review ?: focus ?: PlayerQueue.current(ordered)
            val upcomingSet = currentSet?.takeIf { review == null }?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { it.id != currentSet.id }
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

    /**
     * Optimistic: the value changes on screen at once, the database follows in order.
     * A load the athlete changes on an open set carries over to the sets after it
     * (owner 2026-10-15: the recommendation must be overridable – "too heavy" for
     * one set is too heavy for the next one too). A completed set changed
     * afterwards moves the rest of its block like a fresh answer would.
     */
    fun update(set: ExerciseSet) {
        val before = _state.value.sets.firstOrNull { it.id == set.id }
        val carried = carryLoad(before, set)
        replaceLocal(set)
        carried.forEach(::replaceLocal)
        enqueue {
            service.updateSet(set)
            carried.forEach { service.updateSet(it) }
            // Only for sets done in this training session: an older one's step is unknown and would stack.
            if (set.isCompleted && set.setType == SetType.WORK && set.id !in manualAfter && applied.containsKey(set.id)) {
                readjust(set)
                withContext(Dispatchers.Main) {
                    _state.update { s -> if (s.lastDone?.set?.id == set.id) s.copy(adjustment = applied[set.id]) else s }
                }
            }
        }
    }

    /**
     * During a rest: the athlete sets the next load by hand. It carries over like
     * [update], and the reserve answer for the set just done no longer moves it.
     */
    fun updateNext(set: ExerciseSet) {
        val done = _state.value.lastDone?.set
        if (done != null) manualAfter += done.id
        _state.update { st -> st.copy(next = st.next?.let { if (it.set.id == set.id) it.copy(set = set) else it }, adjustment = null) }
        update(set)
    }

    /** The open sets after [after] in its block and side that followed its old load, moved by the same step. */
    private fun carryLoad(before: ExerciseSet?, after: ExerciseSet): List<ExerciseSet> {
        if (before == null || before.isCompleted || after.isCompleted) return emptyList()
        val def = service.catalog.fallbackFor(after.exerciseSlug)
        if (def.load != com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS && def.load != com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL) return emptyList()
        // No load yet means body weight (or nothing on the block): the first "+" starts from zero.
        val old = before.loadKg ?: 0.0
        val new = after.loadKg ?: return emptyList()
        val delta = new - old
        if (kotlin.math.abs(delta) < 1e-9) return emptyList()
        val min = if (def.load == com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL) 0.0 else Double.NEGATIVE_INFINITY
        return _state.value.sets.filter {
            val load = it.loadKg ?: 0.0
            it.id != after.id && !it.isCompleted && it.blockIndex == after.blockIndex && it.side == after.side &&
                it.setType == after.setType && it.setIndex > after.setIndex &&
                // Untouched by the athlete, or already following the old value.
                (load == (it.targetLoadKg ?: load) || load == old)
        }.map { s ->
            val moved = ((s.loadKg ?: 0.0) + delta).coerceAtLeast(min)
            // Target and value move together: autoregulation keeps working from the athlete's load.
            s.copy(loadKg = moved, targetLoadKg = moved)
        }
    }

    private suspend fun readjust(done: ExerciseSet) {
        val adjustment = runCatching { service.adjustUpcomingSets(done.workoutId, done, applied[done.id]) }.getOrNull()
        applied[done.id] = adjustment
    }

    // ── Moving between sets ──────────────────────────────────────────

    /** The set before the shown one, done or not (owner 2026-10-15: "you cannot go back between sets"). */
    fun back() {
        val st = _state.value
        val shown = st.current?.set ?: return
        val index = st.sets.indexOfFirst { it.id == shown.id }
        st.sets.getOrNull(index - 1)?.let(::show)
    }

    /** From a completed set: the next one in order; from an open one this is [skip]. */
    fun forward() {
        val st = _state.value
        val shown = st.current?.set ?: return
        if (!shown.isCompleted) return skip()
        val index = st.sets.indexOfFirst { it.id == shown.id }
        val next = st.sets.getOrNull(index + 1)
        if (next != null) show(next) else resume()
    }

    /** Leaves a completed set and goes on with the next open one. */
    fun resume() {
        val st = _state.value
        val shown = st.current?.set
        reviewId = null
        val next = shown?.let { s -> PlayerQueue.nextAfter(st.sets, s)?.takeIf { !it.isCompleted } } ?: PlayerQueue.current(st.sets)
        if (next == null) { _state.update { it.copy(phase = PlayerPhase.COMPLETE) }; return }
        show(next)
    }

    /**
     * "Not done after all": the completed set opens again with its planned reps and time and
     * no reserve answer, and what it changed in its block is taken back. The load stays: it is
     * the athlete's choice.
     */
    fun reopen() {
        val set = _state.value.current?.set?.takeIf { it.isCompleted } ?: return
        val open = set.copy(completedAt = null, rir = null, reps = set.targetReps ?: set.reps, durationS = set.targetDurationS ?: set.durationS)
        replaceLocal(open)
        show(open)
        enqueue {
            service.updateSet(open)
            if (applied.containsKey(set.id) && set.id !in manualAfter) readjust(open)
        }
    }

    private fun show(target: ExerciseSet) {
        if (target.isCompleted) reviewId = target.id else { reviewId = null; focusId = target.id }
        advanceJob?.cancel()
        _state.update { s ->
            val upcoming = if (target.isCompleted) null else PlayerQueue.nextAfter(s.sets, target)?.takeIf { it.id != target.id }
            s.copy(phase = PlayerPhase.SET, current = item(s.sets, target), upcoming = upcoming?.let { item(s.sets, it) },
                lastDone = null, next = null, restArmed = false, restFinished = false, feedback = null, adjustment = null, sideSwitch = false)
        }
    }

    fun startTimer() {
        if (_state.value.current?.set?.isCompleted == false) _state.update { it.copy(phase = PlayerPhase.TIMER) }
    }

    fun cancelTimer() {
        _state.update { if (it.phase == PlayerPhase.TIMER) it.copy(phase = PlayerPhase.SET) else it }
    }

    /** Leaves the shown set open and moves to the next one. */
    fun skip() {
        val st = _state.value
        val current = st.current?.takeIf { !it.set.isCompleted } ?: return
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

    /**
     * Ticks the shown set off and moves into the rest (or to the end). [heldSeconds] is the
     * time actually held when the athlete let go before the hang timer ended.
     */
    fun completeCurrent(heldSeconds: Double? = null) {
        val st = _state.value
        val current0 = st.current ?: return
        if (st.phase == PlayerPhase.REST || current0.set.isCompleted) return
        val current = if (heldSeconds == null) current0 else current0.copy(set = current0.set.copy(durationS = heldSeconds))
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
                adjustment = null,
                completedCount = setsAfter.count { s -> s.isCompleted },
            )
        }
        enqueue {
            val startRest = next != null && rest > 0
            val outcome = service.completeSetDetailed(current.set, startRest = startRest, restSeconds = rest)
            // Missed reps or a hold that ended early move the next sets at once; the reserve answer may refine it.
            // Not when the athlete already set the next load by hand after this set (a set done again after "not done").
            val adjustment = if (doneLocal.id in manualAfter) null
                else runCatching { service.adjustUpcomingSets(doneLocal.workoutId, doneLocal, applied[doneLocal.id]) }.getOrNull()
            if (doneLocal.id !in manualAfter) applied[doneLocal.id] = adjustment
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
                    else s.copy(feedback = feedback, adjustment = adjustment, restArmed = s.phase == PlayerPhase.REST)
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
        enqueue {
            service.updateSet(updated)
            // The athlete set the next load by hand: that wins over the answer.
            if (updated.id in manualAfter) return@enqueue
            // The answer arrives during the rest, before the next set starts: adjust what is still planned.
            val adjustment = runCatching { service.adjustUpcomingSets(updated.workoutId, updated, applied[updated.id]) }.getOrNull()
            applied[updated.id] = adjustment
            withContext(Dispatchers.Main) {
                _state.update { s -> if (s.lastDone?.set?.id == updated.id) s.copy(adjustment = adjustment) else s }
            }
        }
    }

    // ── It hurts ─────────────────────────────────────────────────────

    /**
     * "Tut weh" on the shown set: swap, lighten or end the exercise; with
     * [remember] the pain becomes an injury the coach keeps respecting.
     */
    fun reportPain(region: InjuryRegion, side: InjurySide?, severity: Int, action: PainAction, remember: Boolean) {
        val current = _state.value.current ?: return
        focusId = null
        enqueue {
            val outcome = runCatching {
                service.painStop(current.set.workoutId, current.set.blockIndex, region, side, severity, action, remember)
            }.getOrNull()
            val newDef = outcome?.newSlug?.let { service.catalog.fallbackFor(it) }
            withContext(Dispatchers.Main) { _state.update { it.copy(painResult = outcome, painNewDef = newDef) } }
        }
    }

    fun consumePainResult() = _state.update { it.copy(painResult = null, painNewDef = null) }

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
                lastDone = null, next = null, restArmed = false, restFinished = false, feedback = null, adjustment = null, sideSwitch = false,
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
            fun PlayerItem.fresh() = if (this.set.id == set.id) copy(set = set) else this
            st.copy(
                sets = st.sets.map { if (it.id == set.id) set else it },
                current = st.current?.fresh(),
                upcoming = st.upcoming?.fresh(),
                lastDone = st.lastDone?.fresh(),
                next = st.next?.fresh(),
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
