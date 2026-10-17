// Port of androidApp/.../ui/training/player/WorkoutPlayerViewModel.kt (0e1bad0c6) for the iOS app,
// plus LoadCarry.kt's `carriedLoads`. Kept close to the Android file so later Android changes carry
// over by diff; the platform seams differ: an injected scope (main thread in the app) and IO
// dispatcher, a reentrant lock instead of `synchronized`, the common clock, TrainingRestTimer.
package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.PainAction
import com.cruxcoach.app.athlete.RestTimerState
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.app.util.ReentrantLock
import com.cruxcoach.app.util.withReentrantLock
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.Capacity
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.SetAdjustment
import com.cruxcoach.athlete.logic.WarmupRamp
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.Workout
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

/** One set as the player shows it. */
internal data class WorkoutPlayerItem(val set: ExerciseSet, val def: ExerciseDefinition, val position: PlayerQueue.Position)

/**
 * SET and REST both show the exercise page (the rest is a bar on it, not a screen of its own);
 * TIMER is the full-screen hang timer; EMPTY a free training without exercises yet.
 */
internal enum class WorkoutPlayerPhase { LOADING, NO_WORKOUT, EMPTY, SET, TIMER, REST, COMPLETE }

/** What the set just done changed — shown once, quietly, under its row. */
internal data class WorkoutPlayerFeedback(
    val def: ExerciseDefinition,
    val record: PersonalRecord?,
    val raisedCapacity: Capacity?,
    /** The capacity is the exercise's first value, learned from this set (not a raise). */
    val firstValue: Boolean = false,
)

/**
 * One page of the training: one exercise with all its sets, or a whole circuit (its members' sets
 * round after round). [sets] are in the order they are done.
 */
internal data class WorkoutPlayerPage(
    val blockIndexes: List<Int>,
    val circuit: Int?,
    val sets: List<ExerciseSet>,
) {
    val key: List<Int> get() = blockIndexes
    /** Work sets; a warm-up exercise (only warm-up rows) counts those instead ("0 of 0 sets" on the strip). */
    private val counted: List<ExerciseSet> get() = sets.filter { it.setType != SetType.WARMUP }.ifEmpty { sets }
    val done: Int get() = counted.count { it.isCompleted }
    val total: Int get() = counted.size
    val open: Boolean get() = sets.any { !it.isCompleted }
}

/** How an exercise left the page: ended after some sets, skipped before any, or removed from the training. */
internal enum class WorkoutEndKind { ENDED, SKIPPED, REMOVED }

/** An exercise ended early, for the "Undo" in the snackbar. */
internal data class WorkoutEndedExercise(val slugs: List<String>, val sets: List<ExerciseSet>, val circuit: Boolean = false,
                                         val kind: WorkoutEndKind = WorkoutEndKind.ENDED)

internal data class WorkoutPlayerRunState(
    val phase: WorkoutPlayerPhase = WorkoutPlayerPhase.LOADING,
    val workout: Workout? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val bodyweight: Double? = null,
    val sets: List<ExerciseSet> = emptyList(),
    /** The active set: the one the main button logs. During a rest it is already the next one. */
    val current: WorkoutPlayerItem? = null,
    /** The set after [current], for "after this". */
    val upcoming: WorkoutPlayerItem? = null,
    /** The set done last in this session: reserve question, feedback and adjustment sit under its row. */
    val lastDone: WorkoutPlayerItem? = null,
    /** During a rest: the set it leads to (the same as [current]). */
    val next: WorkoutPlayerItem? = null,
    val sideSwitch: Boolean = false,
    /** The rest timer has been started for this rest (guards against advancing on a stale idle state). */
    val restArmed: Boolean = false,
    val restFinished: Boolean = false,
    val feedback: WorkoutPlayerFeedback? = null,
    /** How the rest of the block changed after [lastDone] (set-to-set autoregulation). */
    val adjustment: SetAdjustment? = null,
    /** Every adjustment of this session by the set that caused it: the rows it moved get ↓/↑. */
    val adjustments: Map<String, SetAdjustment> = emptyMap(),
    /** One-shot result of "it hurts", for a short confirmation. */
    val painResult: AthleteService.PainOutcome? = null,
    /** The exercise a pain swap brought in, for the confirmation. */
    val painNewDef: ExerciseDefinition? = null,
    val completedCount: Int = 0,
    val closing: Boolean = false,
    // ── Pages (owner 2026-10-17: one screen per exercise with all its sets) ──
    val pages: List<WorkoutPlayerPage> = emptyList(),
    /** The page on screen; follows the active set unless the athlete moved to another one. */
    val pageIndex: Int = 0,
    /** The athlete moved to a page by hand (or a finished exercise stays during its rest). */
    val browsing: Boolean = false,
    /** A done set opened for correction; only one row is open at a time. */
    val editingId: String? = null,
    val endedUndo: WorkoutEndedExercise? = null,
    val defs: Map<String, ExerciseDefinition> = emptyMap(),
    /** What today's form trimmed in this training (sets, load), until the athlete chooses "as planned". */
    val dayForm: com.cruxcoach.athlete.logic.ReadinessModifier? = null,
    /** "Wie geplant" was just chosen: the snackbar offers to take it back. */
    val asPlannedUndo: AthleteService.AsPlannedUndo? = null,
    /** This week's volume per area, for the hint when an exercise is left out or removed (comparison review #13). */
    val weekly: List<com.cruxcoach.athlete.logic.AreaProgress> = emptyList(),
    /** The "still learning this value" line per exercise (Android `LearningBadge`), read once per exercise. */
    val learning: Map<String, WorkoutLearning> = emptyMap(),
) {
    val page: WorkoutPlayerPage? get() = pages.getOrNull(pageIndex)
    fun def(slug: String): ExerciseDefinition? = defs[slug]
}

/** What the coach still learns about an exercise's value (Android `LearningBadgeViewModel`). */
internal data class WorkoutLearning(
    val state: com.cruxcoach.athlete.logic.LearningState.State,
    /** Nothing known, and the load is a careful start guess. */
    val guessed: Boolean,
    /** The plan comes from the load the athlete lowered last time. */
    val ownLoad: Boolean,
    /** A hold without load: learnt from the time held. */
    val hold: Boolean,
)

internal sealed interface WorkoutPlayerEvent {
    data class Finished(val workoutId: String) : WorkoutPlayerEvent
    data object Discarded : WorkoutPlayerEvent
    /** Typed exercises an open injury ruled out. */
    data class SkippedForInjury(val slugs: List<String>) : WorkoutPlayerEvent
}

/**
 * The training: one page per exercise (or per circuit) with all its sets, the active one
 * open; a set is logged as planned with one tap, with fewer or more with one tap on a
 * result chip; the rest is a bar on the same page, so the sets stay in view and the next
 * one can be changed while resting. All writes go through one ordered queue, and the rest
 * runs on the shared rest timer, so the countdown and the notification also work with the
 * screen off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorkoutPlayerPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow(WorkoutPlayerRunState())
    val state: StateFlow<WorkoutPlayerRunState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WorkoutPlayerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<WorkoutPlayerEvent> = _events.asSharedFlow()

    /** The shared rest timer, for the countdown. */
    val restTimer: StateFlow<RestTimerState> get() = service.restTimer

    /** Android's `withContext(Dispatchers.Main)`: the scope's own thread. */
    private val main = scope.coroutineContext[ContinuationInterceptor] ?: io

    /** The set the athlete made active by hand (a planned row, another page); null = the first open set. */
    private var focusId: String? = null
    /** The page the athlete moved to by hand, by its block indexes; null = the active set's page. */
    private var shownPage: List<Int>? = null
    /** "+ set" on this block: its new set becomes the active one once it is in. */
    private var focusNewIn: Int? = null
    /** The first rows after opening the training: go on after the set done last, not at a skipped one. */
    private var resumed = false
    /** Sets after which the athlete changed the next load by hand: their reserve answer no longer moves it. */
    private val manualAfter = mutableSetOf<String>()
    /** What autoregulation already applied per completed set, so a changed reserve answer never stacks steps. */
    private val applied = mutableMapOf<String, SetAdjustment?>()
    private var advanceJob: Job? = null

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * Sets changed on screen whose write the database has not reported back yet. A row read
     * before the write must not undo them (CI 2026-10-16: right after "not done", a stale
     * "done" row moved the player on to the next set; a reserve answer was lost the same way).
     */
    private val pending = mutableMapOf<String, ExerciseSet>()
    private val pendingLock = ReentrantLock()
    /** Ledger entries of a skip, by the first set it removed: "Rückgängig" takes them back too. */
    private val undoEvents = mutableMapOf<String, String>()

    private fun pend(set: ExerciseSet) = withReentrantLock(pendingLock) { pending[set.id] = set }

    /** After the write: rows read from now on carry it; a short grace covers a query already under way. */
    private fun written(set: ExerciseSet) {
        scope.launch {
            delay(PENDING_GRACE_MS)
            withReentrantLock(pendingLock) { if (pending[set.id] === set) pending.remove(set.id) }
        }
    }

    private fun withPending(rows: List<ExerciseSet>): List<ExerciseSet> = withReentrantLock(pendingLock) {
        if (pending.isEmpty()) rows else rows.map { pending[it.id] ?: it }
    }

    init {
        scope.launch(io) {
            service.ensureReady()
            for (write in writes) runCatching { write() }
                .onFailure { println("WorkoutPlayerPresenter: write failed: ${it.message}") }
        }
        scope.launch(io) {
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
        // Rest end: the shared timer finished (or was ended elsewhere) → on with the active set.
        scope.launch {
            service.restTimer.collect { timer -> onRestTimer(timer) }
        }
    }

    /** The catalogue with the athlete's own exercises, for typed lines. */
    fun catalog(): ExerciseCatalog = service.catalog

    private fun def(slug: String): ExerciseDefinition = service.catalog.fallbackFor(slug)

    private fun item(sets: List<ExerciseSet>, set: ExerciseSet): WorkoutPlayerItem =
        WorkoutPlayerItem(set, def(set.exerciseSlug), PlayerQueue.position(sets, set))

    /** Pages in the order the sets are done: one per exercise, one per circuit. */
    private fun pagesOf(ordered: List<ExerciseSet>): List<WorkoutPlayerPage> =
        PlayerQueue.pages(ordered).map { p ->
            WorkoutPlayerPage(p.blockIndexes, p.circuit, ordered.filter { it.blockIndex in p.blockIndexes })
        }

    private fun pageIndexOf(pages: List<WorkoutPlayerPage>, set: ExerciseSet): Int =
        pages.indexOfFirst { set.blockIndex in it.blockIndexes }.coerceAtLeast(0)

    /** The page on screen: the one moved to by hand, else the active set's, else the last one with something done. */
    private fun pageIndexFor(pages: List<WorkoutPlayerPage>, current: ExerciseSet?): Int {
        shownPage?.let { key -> pages.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { return it } }
        shownPage = null
        if (current != null) return pageIndexOf(pages, current)
        return pages.indexOfLast { p -> p.sets.any { it.isCompleted } }.coerceAtLeast(0)
    }

    /** Workout and done sets the weekly volume in the state was read for. */
    private var weeklyKey: String? = null

    private fun onData(workout: Workout?, sets: List<ExerciseSet>, profile: AthleteProfile, bodyweight: Double?) {
        // Read here on the IO thread, again only when a set was done or taken back; the screen only looks it up.
        val weeklyNow = workout?.let { w -> w.id + ":" + sets.count { it.isCompleted } }
        val weekly = when {
            workout == null -> emptyList()
            weeklyNow == weeklyKey -> _state.value.weekly
            else -> runCatching { service.weeklyProgress(profile) }.getOrDefault(emptyList()).also { weeklyKey = weeklyNow }
        }
        val dayForm = workout?.let { service.dayForm(it.id) }
        val learning = _state.value.learning.let { known ->
            known + sets.map { it.exerciseSlug }.distinct().filter { it !in known }
                .associateWith { slug -> runCatching { learningOf(slug, workout?.id) }.getOrElse { WorkoutLearning(com.cruxcoach.athlete.logic.LearningState.State.Known, false, false, false) } }
        }
        _state.update { st ->
            if (st.closing) return@update st.copy(profile = profile)
            if (workout == null) return@update st.copy(phase = WorkoutPlayerPhase.NO_WORKOUT, workout = null, sets = emptyList(),
                current = null, upcoming = null, pages = emptyList(), profile = profile)
            val ordered = PlayerQueue.ordered(withPending(sets))
            if (!resumed && ordered.isNotEmpty()) {
                resumed = true
                // Back in a training (app or screen reopened): where the athlete left off, after the set done last –
                // not at an exercise skipped earlier (device test 2026-10-17). Skipped sets still come back at the end.
                val last = ordered.filter { it.isCompleted }.maxByOrNull { it.completedAt ?: 0L }
                last?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { !it.isCompleted }?.let { focusId = it.id }
            }
            focusNewIn?.let { block ->
                ordered.filter { it.blockIndex == block && !it.isCompleted }.maxByOrNull { it.setIndex }?.let { focusId = it.id; focusNewIn = null }
            }
            val focus = focusId?.let { id -> ordered.firstOrNull { it.id == id && !it.isCompleted } }
            if (focus == null) focusId = null
            val currentSet = focus ?: PlayerQueue.current(ordered)
            val upcomingSet = currentSet?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { it.id != currentSet.id && !it.isCompleted }
            val pages = pagesOf(ordered)
            val phase = when {
                st.phase == WorkoutPlayerPhase.REST && currentSet != null -> WorkoutPlayerPhase.REST
                st.phase == WorkoutPlayerPhase.TIMER && currentSet != null -> WorkoutPlayerPhase.TIMER
                ordered.isEmpty() -> WorkoutPlayerPhase.EMPTY
                currentSet == null -> WorkoutPlayerPhase.COMPLETE
                else -> WorkoutPlayerPhase.SET
            }
            val editing = st.editingId?.takeIf { id -> ordered.any { it.id == id && it.isCompleted } }
            st.copy(
                phase = phase,
                workout = workout,
                profile = profile,
                bodyweight = bodyweight,
                sets = ordered,
                current = currentSet?.let { item(ordered, it) },
                upcoming = upcomingSet?.let { item(ordered, it) },
                // Keep the rest's items fresh (e.g. a reserve answer just saved).
                lastDone = st.lastDone?.let { ld -> ordered.firstOrNull { it.id == ld.set.id }?.let { item(ordered, it) } },
                next = if (phase == WorkoutPlayerPhase.REST) currentSet?.let { item(ordered, it) } else null,
                completedCount = ordered.count { it.isCompleted },
                pages = pages,
                pageIndex = pageIndexFor(pages, currentSet),
                browsing = shownPage != null,
                editingId = editing,
                defs = ordered.map { it.exerciseSlug }.distinct().associateWith { def(it) },
                dayForm = dayForm,
                weekly = weekly,
                learning = learning,
            )
        }
    }

    /** Android `LearningBadgeViewModel.load`; [openId] (the open training) does not count as a session yet. */
    private fun learningOf(slug: String, openId: String?): WorkoutLearning {
        val def = service.catalog[slug]
        // Only exercises whose loads come from a performance value have something to learn; stretches and warm-ups not.
        val noValue = def == null || com.cruxcoach.athlete.logic.BenchmarkMath.capacityKind(def) == null ||
            def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.MOBILITY ||
            def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.WARMUP
        if (noValue) return WorkoutLearning(com.cruxcoach.athlete.logic.LearningState.State.Known, false, false, false)
        val hold = def?.kind == ExerciseKind.TIME
        val benchmarks = service.repo.benchmarks(slug)
        val ownLoad = benchmarks.maxByOrNull { it.measuredAt }
            ?.let { it.source == com.cruxcoach.athlete.model.BenchmarkSource.AUTO && it.sets != null } == true
        val sessions = service.repo.history(slug, 200)
            .filter { it.isCompleted && it.setType == SetType.WORK && it.workoutId != openId }
            .map { it.workoutId }.distinct().size
        val state = com.cruxcoach.athlete.logic.LearningState.of(slug, benchmarks, sessions)
        val guessed = state == com.cruxcoach.athlete.logic.LearningState.State.None && service.startEstimate(def!!) != null
        return WorkoutLearning(state, guessed, ownLoad, hold)
    }

    private fun onRestTimer(timer: RestTimerState) {
        val st = _state.value
        if (st.phase != WorkoutPlayerPhase.REST || !st.restArmed) return
        when {
            timer.isFinished && !st.restFinished -> {
                _state.update { it.copy(restFinished = true) }
                advanceJob?.cancel()
                advanceJob = scope.launch { delay(GO_DISPLAY_MS); advance() }
            }
            !timer.isRunning && !timer.isFinished -> advance() // ended early or from the banner
        }
    }

    // ── Values ───────────────────────────────────────────────────────

    /**
     * Optimistic: the value changes on screen at once, the database follows in order.
     * A load the athlete changes on an open set carries over to the sets after it
     * (owner 2026-10-15: "too heavy" for one set is too heavy for the next one too).
     * During a rest, a changed next load wins over the reserve answer for the set just done.
     * A completed set changed afterwards moves the rest of its block like a fresh answer would.
     */
    fun update(set: ExerciseSet) {
        val st = _state.value
        val before = st.sets.firstOrNull { it.id == set.id }
        val done = st.lastDone?.set
        if (st.phase == WorkoutPlayerPhase.REST && done != null && !set.isCompleted && before != null && before.loadKg != set.loadKg &&
            set.blockIndex == done.blockIndex) {
            manualAfter += done.id
            _state.update { it.copy(adjustment = null) }
        }
        val carried = carryLoad(before, set)
        replaceLocal(set)
        carried.forEach(::replaceLocal)
        pend(set)
        carried.forEach(::pend)
        enqueue {
            service.updateSet(set)
            carried.forEach { service.updateSet(it) }
            written(set)
            carried.forEach(::written)
            // Only for sets done in this training session: an older one's step is unknown and would stack.
            if (set.isCompleted && set.setType == SetType.WORK && set.id !in manualAfter && applied.containsKey(set.id)) {
                readjust(set)
            }
        }
    }

    /** During a rest: the athlete sets the next load by hand (kept for callers of the old rest screen). */
    fun updateNext(set: ExerciseSet) = update(set)

    /** The open sets after [after] in its block and side that followed its old load, moved by the same step. */
    private fun carryLoad(before: ExerciseSet?, after: ExerciseSet): List<ExerciseSet> =
        carriedLoads(before, after, _state.value.sets, def(after.exerciseSlug))

    private suspend fun readjust(done: ExerciseSet) {
        val adjustment = runCatching { service.adjustUpcomingSets(done.workoutId, done, applied[done.id]) }.getOrNull()
        applied[done.id] = adjustment
        withContext(main) {
            _state.update { s ->
                s.copy(
                    adjustment = if (s.lastDone?.set?.id == done.id) adjustment else s.adjustment,
                    adjustments = if (adjustment != null) s.adjustments + (done.id to adjustment) else s.adjustments - done.id,
                )
            }
        }
    }

    // ── Moving around (logs nothing: looking is not skipping) ────────

    /** Shows another page; its first open set becomes the active one. */
    fun showPage(index: Int) {
        val st = _state.value
        val page = st.pages.getOrNull(index) ?: return
        shownPage = page.key
        val firstOpen = page.sets.firstOrNull { !it.isCompleted }
        if (firstOpen != null && st.phase != WorkoutPlayerPhase.TIMER) focusId = firstOpen.id
        _state.update { s ->
            s.copy(
                pageIndex = index, browsing = true, editingId = null,
                current = firstOpen?.let { item(s.sets, it) } ?: s.current,
                next = if (s.phase == WorkoutPlayerPhase.REST) (firstOpen?.let { item(s.sets, it) } ?: s.next) else s.next,
                upcoming = firstOpen?.let { f -> PlayerQueue.nextAfter(s.sets, f)?.takeIf { it.id != f.id && !it.isCompleted }?.let { item(s.sets, it) } }
                    ?: s.upcoming,
            )
        }
    }

    /** Back to the active set's page – or to the end page when everything is done. */
    fun backToActive() {
        shownPage = null
        _state.update { s ->
            s.copy(browsing = false, editingId = null, pageIndex = s.current?.set?.let { pageIndexOf(s.pages, it) } ?: s.pageIndex)
        }
    }

    /** A planned row tapped: it is done next (sets can be done in any order). */
    fun focus(setId: String) {
        val st = _state.value
        val target = st.sets.firstOrNull { it.id == setId && !it.isCompleted } ?: return
        focusId = target.id
        shownPage = st.pages.getOrNull(pageIndexOf(st.pages, target))?.key
        _state.update { s ->
            s.copy(
                current = item(s.sets, target), editingId = null, browsing = true,
                next = if (s.phase == WorkoutPlayerPhase.REST) item(s.sets, target) else s.next,
                upcoming = PlayerQueue.nextAfter(s.sets, target)?.takeIf { it.id != target.id && !it.isCompleted }?.let { item(s.sets, it) },
            )
        }
    }

    /** A done row opened to correct it; one at a time. */
    fun edit(setId: String?) = _state.update { s -> s.copy(editingId = setId?.takeIf { id -> s.sets.any { it.id == id && it.isCompleted } }) }

    fun closeEdit() = edit(null)

    /**
     * "Not done after all": the set opens again with its planned reps and time and no reserve
     * answer, becomes the active one, and what it changed in its block is taken back. The load
     * stays: it is the athlete's choice.
     */
    fun reopen(setId: String? = _state.value.editingId) {
        val set = _state.value.sets.firstOrNull { it.id == setId && it.isCompleted } ?: return
        val open = set.copy(completedAt = null, rir = null, reps = set.targetReps ?: set.reps, durationS = set.targetDurationS ?: set.durationS)
        replaceLocal(open)
        pend(open)
        focusId = open.id
        _state.update { s -> s.copy(editingId = null, current = item(s.sets, open),
            lastDone = s.lastDone?.takeIf { it.set.id != open.id }, feedback = if (s.lastDone?.set?.id == open.id) null else s.feedback,
            adjustments = s.adjustments - open.id) }
        enqueue {
            service.updateSet(open)
            written(open)
            if (applied.containsKey(set.id) && set.id !in manualAfter) readjust(open)
        }
    }

    fun startTimer() {
        val st = _state.value
        if (st.current?.set?.isCompleted != false) return
        if (st.phase == WorkoutPlayerPhase.REST) {
            // Starting the hang ends the rest.
            advanceJob?.cancel()
            enqueue { if (service.restTimer.value.isRunning || service.restTimer.value.isFinished) service.cancelRest() }
        }
        _state.update { it.copy(phase = WorkoutPlayerPhase.TIMER, restArmed = false, restFinished = false, editingId = null) }
    }

    fun cancelTimer() {
        _state.update { if (it.phase == WorkoutPlayerPhase.TIMER) it.copy(phase = WorkoutPlayerPhase.SET) else it }
    }

    // ── Logging a set ────────────────────────────────────────────────

    /** "Did 6 instead of 8": the active set logged with that number in one tap (reps, or seconds for timed work). */
    fun completeAs(value: Double) {
        val current = _state.value.current?.takeIf { !it.set.isCompleted } ?: return
        val set = when (current.def.kind) {
            ExerciseKind.TIME, ExerciseKind.HANG -> current.set.copy(durationS = value.coerceIn(0.0, 3600.0))
            else -> current.set.copy(reps = value.toInt().coerceIn(0, 999))
        }
        replaceLocal(set)
        completeCurrent()
    }

    /**
     * Ticks the active set off and starts the rest (or ends the page). [heldSeconds] is the time
     * actually held when the athlete let go before the hang timer ended. During a rest this ends
     * the rest and logs the active (next) set.
     */
    fun completeCurrent(heldSeconds: Double? = null) {
        val st = _state.value
        val current0 = st.current ?: return
        if (current0.set.isCompleted) return
        val wasResting = st.phase == WorkoutPlayerPhase.REST
        val current = if (heldSeconds == null) current0 else current0.copy(set = current0.set.copy(durationS = heldSeconds))
        val doneLocal = current.set.copy(completedAt = nowMs())
        pend(doneLocal)
        val setsAfter = st.sets.map { if (it.id == doneLocal.id) doneLocal else it }
        val next = PlayerQueue.nextAfter(setsAfter, doneLocal)?.takeIf { !it.isCompleted && it.id != doneLocal.id }
        val rest = PlayerQueue.restAfter(doneLocal, next, current.def)
        focusId = next?.id
        advanceJob?.cancel()
        val pages = st.pages
        val donePage = pageIndexOf(pages, doneLocal)
        // A finished exercise stays on screen during its rest ("3 of 4 on target · next: …"); after the very last
        // set too, so "+ set" or a correction is one tap away before the end page.
        shownPage = if (next == null || rest > 0 && pageIndexOf(pages, next) != donePage) pages.getOrNull(donePage)?.key else null
        _state.update {
            it.copy(
                phase = when {
                    next == null -> WorkoutPlayerPhase.COMPLETE
                    rest > 0 -> WorkoutPlayerPhase.REST
                    else -> WorkoutPlayerPhase.SET
                },
                sets = setsAfter,
                lastDone = item(setsAfter, doneLocal),
                current = next?.let { n -> item(setsAfter, n) },
                next = next?.takeIf { rest > 0 }?.let { n -> item(setsAfter, n) },
                upcoming = next?.let { n -> PlayerQueue.nextAfter(setsAfter, n)?.takeIf { u -> u.id != n.id && !u.isCompleted }?.let { u -> item(setsAfter, u) } },
                sideSwitch = next != null && next.blockIndex == doneLocal.blockIndex &&
                    next.setIndex == doneLocal.setIndex && next.side != null && next.side != doneLocal.side,
                restArmed = false,
                restFinished = false,
                feedback = null,
                adjustment = null,
                editingId = null,
                completedCount = setsAfter.count { s -> s.isCompleted },
                // The page carries the done set at once too (iOS addition): until the database reports back, "end
                // exercise" would otherwise read the page as untouched and skip it, the done set included.
                pages = it.pages.map { p -> if (doneLocal.blockIndex in p.blockIndexes) p.copy(sets = p.sets.map { s -> if (s.id == doneLocal.id) doneLocal else s }) else p },
                pageIndex = if (shownPage != null) donePage else next?.let { n -> pageIndexOf(pages, n) } ?: donePage,
                browsing = shownPage != null,
            )
        }
        enqueue {
            val startRest = next != null && rest > 0
            // A new rest replaces the running one; without a new rest the old one ends here.
            if (wasResting && !startRest && (service.restTimer.value.isRunning || service.restTimer.value.isFinished)) service.cancelRest()
            val outcome = service.completeSetDetailed(current.set, startRest = startRest, restSeconds = rest)
            written(doneLocal)
            // Missed reps or a hold that ended early move the next sets at once; the reserve answer may refine it.
            // Not when the athlete already set the next load by hand after this set (a set done again after "not done").
            val adjustment = if (doneLocal.id in manualAfter) null
                else runCatching { service.adjustUpcomingSets(doneLocal.workoutId, doneLocal, applied[doneLocal.id]) }.getOrNull()
            if (doneLocal.id !in manualAfter) applied[doneLocal.id] = adjustment
            val now = _state.value
            if (startRest && (now.phase != WorkoutPlayerPhase.REST || now.lastDone?.set?.id != current.set.id)) {
                // The athlete already ended this rest (or logged the next set) while the write was queued.
                if (now.phase != WorkoutPlayerPhase.REST) service.cancelRest()
            }
            val feedback = if (outcome.record != null || outcome.raisedBenchmark != null)
                WorkoutPlayerFeedback(current.def, outcome.record, outcome.capacity?.takeIf { outcome.raisedBenchmark != null },
                    firstValue = outcome.firstValue) else null
            withContext(main) {
                _state.update { s ->
                    if (s.lastDone?.set?.id != current.set.id) s.copy(
                        adjustments = if (adjustment != null) s.adjustments + (doneLocal.id to adjustment) else s.adjustments)
                    else s.copy(feedback = feedback, adjustment = adjustment, restArmed = s.phase == WorkoutPlayerPhase.REST,
                        adjustments = if (adjustment != null) s.adjustments + (doneLocal.id to adjustment) else s.adjustments)
                }
                // The timer may already have run out while the write was queued.
                onRestTimer(service.restTimer.value)
            }
        }
    }

    /** The reserve answer for the set done last. */
    fun setReserve(rir: Int) {
        val done = _state.value.lastDone?.set ?: return
        setReserve(done.id, rir)
    }

    /** The reserve answer for any set done in this session, also later from its row. */
    fun setReserve(setId: String, rir: Int?) {
        val done = _state.value.sets.firstOrNull { it.id == setId && it.isCompleted } ?: return
        val updated = done.copy(rir = rir)
        replaceLocal(updated)
        pend(updated)
        enqueue {
            service.updateSet(updated)
            written(updated)
            // The athlete set the next load by hand: that wins over the answer. Older sessions' sets never move anything.
            if (updated.id in manualAfter || !applied.containsKey(updated.id)) return@enqueue
            readjust(updated)
        }
    }

    // ── The exercise as a whole ──────────────────────────────────────

    /** The page's exercise can be swapped while none of its sets is done; afterwards only ended. */
    fun canSwap(): Boolean {
        val st = _state.value
        val current = activeOnPage() ?: return false
        return st.sets.none { it.blockIndex == current.blockIndex && it.isCompleted }
    }

    /** The active set if it is on the page on screen, else the page's first open set. */
    private fun activeOnPage(): ExerciseSet? {
        val st = _state.value
        val page = st.page ?: return null
        return st.current?.set?.takeIf { c -> !c.isCompleted && c.blockIndex in page.blockIndexes }
            ?: page.sets.firstOrNull { !it.isCompleted }
    }

    /**
     * Where the week stands for the area [slug] trains, for a hint when it is left out or removed (comparison review
     * #13): from the state, so the screen never waits on the database for it.
     */
    fun weeklyFor(slug: String): com.cruxcoach.athlete.logic.AreaProgress? {
        val area = com.cruxcoach.athlete.logic.WeeklyVolume.areaOf(service.catalog.fallbackFor(slug)) ?: return null
        return _state.value.weekly.firstOrNull { it.area == area && !it.target.isOff && it.goal > 0 }
    }

    /** What could replace the page's exercise: the suggestion's filters with today's place and injuries. Blocks: call off the main thread. */
    fun alternatives(): List<com.cruxcoach.athlete.logic.SessionSuggester.Alternative> {
        val st = _state.value
        val current = activeOnPage() ?: return emptyList()
        if (!canSwap()) return emptyList()
        return runCatching { service.alternativesInTraining(current.exerciseSlug, st.sets.map { it.exerciseSlug }) }.getOrDefault(emptyList())
    }

    /** Another exercise instead of the page's one, with the same number of sets; the coach learns from it (−1 / +1). */
    fun swapExercise(to: String) {
        val current = activeOnPage() ?: return
        if (!canSwap()) return
        focusId = null
        enqueue {
            service.swapBlock(current.workoutId, current.blockIndex, to)
            service.logSuggestion(SuggestionEventKind.SWAPPED, null, listOf(current.exerciseSlug, to), workoutId = current.workoutId)
        }
    }

    /** Nothing done yet: the exercise's sets go; one skip in the ledger (time or mood, not necessarily dislike). */
    fun skipExercise() {
        val st = _state.value
        val page = st.page ?: return
        val open = page.sets.filter { !it.isCompleted }
        if (open.isEmpty()) return
        focusId = null
        shownPage = null
        val first = open.first()
        val slugs = page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b }?.exerciseSlug }
        // With Undo, like ending (audit P2-14); the skip is only learned when it stays.
        _state.update { s -> s.copy(sets = s.sets.filterNot { o -> open.any { it.id == o.id } },
            endedUndo = WorkoutEndedExercise(slugs, open, circuit = page.circuit != null, kind = WorkoutEndKind.SKIPPED)) }
        enqueue {
            open.forEach { service.removeSet(it.id) }
            if (first.setType == SetType.WORK || open.any { it.setType == SetType.WORK }) {
                service.logSuggestion(SuggestionEventKind.SKIPPED_SET, null, open.map { it.exerciseSlug }.distinct(), workoutId = first.workoutId)
                    ?.let { id -> withReentrantLock(pendingLock) { undoEvents[first.id] = id } }
            }
        }
    }

    /**
     * "Enough for today" after some sets: the page's open sets go in one tap, with "Undo".
     * The coach notes it as ended early, not as a dislike. Without a done set this is [skipExercise].
     */
    fun endExercise() {
        val st = _state.value
        val page = st.page ?: return
        if (page.sets.none { it.isCompleted && it.setType != SetType.WARMUP }) return skipExercise()
        val open = page.sets.filter { !it.isCompleted }
        if (open.isEmpty()) return
        val workoutId = open.first().workoutId
        val slugs = page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b }?.exerciseSlug }
        focusId = null
        shownPage = null
        // Off the screen at once; the database follows.
        _state.update { s -> s.copy(sets = s.sets.filterNot { o -> open.any { it.id == o.id } },
            endedUndo = WorkoutEndedExercise(slugs, open, circuit = page.circuit != null)) }
        enqueue {
            if (service.restTimer.value.isRunning && st.next?.set?.blockIndex in page.blockIndexes) service.cancelRest()
            service.endBlocks(workoutId, page.blockIndexes)
        }
    }

    /** "Undo" after [endExercise]: the sets come back as they were. */
    fun undoEnd() {
        val ended = _state.value.endedUndo ?: return
        _state.update { it.copy(endedUndo = null) }
        focusId = ended.sets.firstOrNull { !it.isCompleted }?.id
        enqueue {
            service.restoreSets(ended.sets)
            ended.sets.firstOrNull()?.id?.let { id -> withReentrantLock(pendingLock) { undoEvents.remove(id) } }?.let(service::forgetSuggestion)
        }
    }

    fun consumeEnded() = _state.update { it.copy(endedUndo = null) }

    /** "Wie geplant": what today's form trimmed comes back (open sets only) and stays off for today. */
    fun asPlanned() {
        val workoutId = _state.value.workout?.id ?: return
        _state.update { it.copy(dayForm = null) }
        enqueue {
            val undo = service.asPlanned(workoutId)
            withContext(main) { _state.update { it.copy(asPlannedUndo = undo) } }
        }
    }

    /** "Rückgängig" after "Wie geplant": today's trim is back, the line too. */
    fun undoAsPlanned() {
        val workoutId = _state.value.workout?.id ?: return
        val undo = _state.value.asPlannedUndo ?: return
        _state.update { it.copy(asPlannedUndo = null) }
        enqueue { service.undoAsPlanned(workoutId, undo) }
    }

    fun consumeAsPlannedUndo() = _state.update { it.copy(asPlannedUndo = null) }

    /** "+ set" (or "+ round" for a circuit): one more like the last, with its plan, not with a missed result. */
    fun addSet() {
        val st = _state.value
        val page = st.page ?: return
        val workoutId = st.workout?.id ?: return
        val circuit = page.circuit
        // The new set becomes the one due only when the exercise was done; with sets still open the order stays
        // (device test 2026-10-17: "+ set" during set 2 of 3 jumped to the new set 4).
        val wasDone = page.sets.none { !it.isCompleted }
        if (circuit != null) {
            if (wasDone) focusNewIn = page.blockIndexes.first()
            enqueue { service.addRound(workoutId, circuit) }
        } else {
            val block = page.blockIndexes.first()
            if (wasDone) focusNewIn = block
            enqueue { service.addSet(workoutId, block) }
        }
        shownPage = page.key
    }

    /** The warm-up ramp before the first work set of the page's exercise (loaded hangs and lifts). */
    fun addWarmups() {
        val page = _state.value.page ?: return
        val first = page.sets.firstOrNull { it.setType == SetType.WORK } ?: return
        enqueue { service.addWarmupRamp(first) }
    }

    /** Whether the page's exercise can get a warm-up ramp (loaded, no warm-up rows yet). */
    fun warmupAvailable(): Boolean {
        val st = _state.value
        val page = st.page ?: return false
        if (page.circuit != null) return false
        val first = page.sets.firstOrNull { it.setType == SetType.WORK } ?: return false
        val def = st.def(first.exerciseSlug) ?: return false
        val loaded = def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS
        return loaded && page.sets.none { it.setType == SetType.WARMUP } &&
            def.kind in setOf(ExerciseKind.HANG, ExerciseKind.LOAD_REPS, ExerciseKind.INTERVAL) &&
            WarmupRamp.build(def, first.loadKg, st.bodyweight ?: first.bodyweightKg, st.profile.smallestIncrementKg).isNotEmpty()
    }

    /**
     * The page's exercise leaves the training with all its sets (asked first on screen). The ledger notes it as
     * removed – no dislike, but the summary asks whether the suggestion should go without it from now on.
     */
    fun removeExercise() {
        val st = _state.value
        val page = st.page ?: return
        val workoutId = st.workout?.id ?: return
        focusId = null
        shownPage = null
        val slugs = page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b }?.exerciseSlug }
        _state.update { s -> s.copy(sets = s.sets.filterNot { o -> page.sets.any { it.id == o.id } },
            endedUndo = WorkoutEndedExercise(slugs, page.sets, circuit = page.circuit != null, kind = WorkoutEndKind.REMOVED)) }
        enqueue {
            page.blockIndexes.forEach { service.removeBlock(workoutId, it) }
            service.logSuggestion(SuggestionEventKind.FEEDBACK, com.cruxcoach.athlete.logic.PreferenceLearning.REMOVED, slugs, workoutId = workoutId)
                ?.let { id -> page.sets.firstOrNull()?.id?.let { first -> withReentrantLock(pendingLock) { undoEvents[first] = id } } }
        }
    }

    /** Adds a catalogue exercise to the open training; false when an open injury rules it out. */
    fun addExercise(slug: String) {
        val id = _state.value.workout?.id ?: return
        enqueue { service.addExercise(id, slug) }
    }

    /** "Klimmzüge 5x5 +20 kg" typed into the open training (owner 2026-10-15). */
    fun addTyped(result: com.cruxcoach.athlete.logic.WorkoutTextParser.Result) {
        val id = _state.value.workout?.id ?: return
        if (result.lines.isEmpty()) return
        enqueue {
            val skipped = service.addTyped(id, result.lines)
            if (skipped.isNotEmpty()) _events.emit(WorkoutPlayerEvent.SkippedForInjury(skipped))
        }
    }

    // ── It hurts ─────────────────────────────────────────────────────

    /**
     * "Tut weh" on the page's exercise: swap, lighten or end it; with [remember] the pain
     * becomes an injury the coach keeps respecting.
     */
    fun reportPain(region: InjuryRegion, side: InjurySide?, severity: Int, action: PainAction, remember: Boolean) {
        val current = activeOnPage() ?: _state.value.current?.set ?: return
        focusId = null
        enqueue {
            val outcome = runCatching {
                service.painStop(current.workoutId, current.blockIndex, region, side, severity, action, remember)
            }.getOrNull()
            val newDef = outcome?.newSlug?.let { def(it) }
            withContext(main) { _state.update { it.copy(painResult = outcome, painNewDef = newDef) } }
        }
    }

    fun consumePainResult() = _state.update { it.copy(painResult = null, painNewDef = null) }

    /** "Rückgängig" after a pain stop saved an injury: the exercise stays swapped, the injury goes. */
    fun forgetInjury(id: String) = enqueue { service.repo.deleteInjury(id) }

    // ── Rest ─────────────────────────────────────────────────────────

    fun adjustRest(deltaSeconds: Int) = enqueue { service.adjustRest(deltaSeconds) }

    /** "On": stop the shared timer; the timer observer moves on. */
    fun endRest() {
        if (_state.value.restArmed) enqueue { service.cancelRest() } else advance()
    }

    /** The rest is over: the active set's page comes up (a finished exercise gives way to the next one). */
    private fun advance() {
        advanceJob?.cancel()
        val st = _state.value
        if (st.phase != WorkoutPlayerPhase.REST) return
        if (service.restTimer.value.isFinished) enqueue { service.dismissRestFinished() }
        shownPage = null
        _state.update { s ->
            val ordered = s.sets
            val currentSet = s.current?.set?.let { c -> ordered.firstOrNull { it.id == c.id && !it.isCompleted } } ?: PlayerQueue.current(ordered)
            focusId = currentSet?.id
            val upcomingSet = currentSet?.let { PlayerQueue.nextAfter(ordered, it) }?.takeIf { it.id != currentSet.id && !it.isCompleted }
            s.copy(
                phase = if (currentSet == null) WorkoutPlayerPhase.COMPLETE else WorkoutPlayerPhase.SET,
                current = currentSet?.let { item(ordered, it) },
                upcoming = upcomingSet?.let { item(ordered, it) },
                next = null, restArmed = false, restFinished = false, sideSwitch = false,
                pageIndex = currentSet?.let { pageIndexOf(s.pages, it) } ?: s.pageIndex,
                browsing = false,
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
            _events.emit(WorkoutPlayerEvent.Finished(id))
        }
    }

    fun discard() {
        val id = _state.value.workout?.id ?: return
        _state.update { it.copy(closing = true) }
        enqueue {
            if (service.restTimer.value.isRunning || service.restTimer.value.isFinished) service.cancelRest()
            service.discardWorkout(id)
            _events.emit(WorkoutPlayerEvent.Discarded)
        }
    }

    private fun replaceLocal(set: ExerciseSet) {
        _state.update { st ->
            fun WorkoutPlayerItem.fresh() = if (this.set.id == set.id) copy(set = set) else this
            val sets = st.sets.map { if (it.id == set.id) set else it }
            st.copy(
                sets = sets,
                current = st.current?.fresh(),
                upcoming = st.upcoming?.fresh(),
                lastDone = st.lastDone?.fresh(),
                next = st.next?.fresh(),
                pages = st.pages.map { p -> if (set.blockIndex in p.blockIndexes) p.copy(sets = p.sets.map { if (it.id == set.id) set else it }) else p },
            )
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    /** Ends the presenter; queued writes that have not run yet are dropped with it, as on Android's onCleared. */
    fun close() = scope.cancel()

    companion object {
        /** How long "Los geht's" stays before the next set comes up. */
        const val GO_DISPLAY_MS = 1_500L
        /** How long a written set still wins over rows read from the database. */
        const val PENDING_GRACE_MS = 500L
    }
}

/**
 * A load the athlete changes on an open set carries over to the later open sets of the same exercise
 * and side (owner 2026-10-15: "too heavy" for one set is too heavy for the next one too). Sets the
 * athlete set by hand to something else keep their value; target and value move together, so
 * autoregulation keeps working from the athlete's load. (Android `LoadCarry.kt`.)
 */
internal fun carriedLoads(before: ExerciseSet?, after: ExerciseSet, sets: List<ExerciseSet>, def: ExerciseDefinition): List<ExerciseSet> {
    if (before == null || before.isCompleted || after.isCompleted) return emptyList()
    if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return emptyList()
    // No load yet means body weight (or nothing on the block): the first "+" starts from zero.
    val old = before.loadKg ?: 0.0
    val new = after.loadKg ?: return emptyList()
    val delta = new - old
    if (kotlin.math.abs(delta) < 1e-9) return emptyList()
    val min = if (def.load == LoadMode.EXTERNAL) 0.0 else Double.NEGATIVE_INFINITY
    return sets.filter {
        val load = it.loadKg ?: 0.0
        it.id != after.id && !it.isCompleted && it.blockIndex == after.blockIndex && it.side == after.side &&
            it.setType == after.setType && it.setIndex > after.setIndex &&
            // Untouched by the athlete, or already following the old value.
            (load == (it.targetLoadKg ?: load) || load == old)
    }.map { s ->
        val moved = ((s.loadKg ?: 0.0) + delta).coerceAtLeast(min)
        s.copy(loadKg = moved, targetLoadKg = moved)
    }
}
