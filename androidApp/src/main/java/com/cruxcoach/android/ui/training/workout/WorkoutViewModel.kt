package com.cruxcoach.android.ui.training.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.WarmupRamp
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
import javax.inject.Inject

/** One exercise of the running training with its set rows in display order. */
data class WorkoutBlock(
    val index: Int,
    val def: ExerciseDefinition,
    val sets: List<ExerciseSet>,
    /** A warm-up ramp can be inserted (loaded hang/lift without warm-up rows yet). */
    val warmupAvailable: Boolean,
    /** Neighbours in the progression chain, for the swap menu. */
    val easier: ExerciseDefinition? = null,
    val harder: ExerciseDefinition? = null,
)

data class WorkoutUiState(
    val loading: Boolean = true,
    val workout: Workout? = null,
    val blocks: List<WorkoutBlock> = emptyList(),
    val profile: AthleteProfile = AthleteProfile(),
    val bodyweight: Double? = null,
    /** Set once the athlete finished or discarded, so the screen does not flash "no training". */
    val closing: Boolean = false,
)

sealed interface WorkoutEvent {
    data class Record(val def: ExerciseDefinition, val record: PersonalRecord) : WorkoutEvent
    data class Finished(val workoutId: String) : WorkoutEvent
    data object Discarded : WorkoutEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkoutViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WorkoutEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WorkoutEvent> = _events.asSharedFlow()

    init {
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
                .collect { (workout, sets, profile) ->
                    _state.update {
                        it.copy(
                            loading = false,
                            workout = workout,
                            blocks = buildBlocks(sets, profile, bodyweight),
                            profile = profile,
                            bodyweight = bodyweight,
                        )
                    }
                }
        }
    }

    private fun buildBlocks(sets: List<ExerciseSet>, profile: AthleteProfile, bodyweight: Double?): List<WorkoutBlock> {
        val catalog = service.catalog
        return sets.groupBy { it.blockIndex }.toSortedMap().map { (index, rows) ->
            val def = catalog.fallbackFor(rows.first().exerciseSlug)
            val ordered = rows.sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
            val firstWork = ordered.firstOrNull { it.setType == SetType.WORK }
            val loaded = def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS
            val warmup = loaded && firstWork != null && ordered.none { it.setType == SetType.WARMUP } &&
                def.kind in setOf(ExerciseKind.HANG, ExerciseKind.LOAD_REPS, ExerciseKind.INTERVAL) &&
                WarmupRamp.build(def, firstWork.loadKg, bodyweight ?: firstWork.bodyweightKg, profile.smallestIncrementKg).isNotEmpty()
            WorkoutBlock(index, def, ordered, warmup, def.easier?.let { catalog[it] }, def.harder?.let { catalog[it] })
        }
    }

    private val workoutId: String? get() = _state.value.workout?.id

    /** Optimistic: the row changes on screen at once, the database follows. */
    fun update(set: ExerciseSet) {
        replaceLocal(set)
        io { service.updateSet(set) }
    }

    fun complete(set: ExerciseSet) {
        replaceLocal(set.copy(completedAt = System.currentTimeMillis()))
        val autoRest = _state.value.profile.autoRestTimer
        io {
            val record = service.completeSet(set, startRest = autoRest)
            if (record != null) _events.emit(WorkoutEvent.Record(service.catalog.fallbackFor(set.exerciseSlug), record))
        }
    }

    fun reopen(set: ExerciseSet) {
        replaceLocal(set.copy(completedAt = null))
        io { service.reopenSet(set) }
    }

    fun addSet(blockIndex: Int) {
        val id = workoutId ?: return
        io { service.addSet(id, blockIndex) }
    }

    fun removeSet(setId: String) = io { service.removeSet(setId) }

    fun removeBlock(blockIndex: Int) {
        val id = workoutId ?: return
        io { service.removeBlock(id, blockIndex) }
    }

    fun swap(blockIndex: Int, slug: String) {
        val id = workoutId ?: return
        io { service.swapBlock(id, blockIndex, slug) }
    }

    fun startRest(seconds: Int) = io { service.startRest(seconds) }

    /** Inserts the % ramp before the first work set (negative set indices sort first). */
    fun addWarmups(block: WorkoutBlock) {
        io {
            val first = block.sets.firstOrNull { it.setType == SetType.WORK } ?: return@io
            service.addWarmupRamp(first)
        }
    }

    fun finish(sessionRpe: Int?, notes: String?) {
        val id = workoutId ?: return
        _state.update { it.copy(closing = true) }
        io {
            service.finishWorkout(id, sessionRpe, notes)
            _events.emit(WorkoutEvent.Finished(id))
        }
    }

    fun discard() {
        val id = workoutId ?: return
        _state.update { it.copy(closing = true) }
        io {
            service.discardWorkout(id)
            _events.emit(WorkoutEvent.Discarded)
        }
    }

    private fun replaceLocal(set: ExerciseSet) {
        _state.update { st ->
            st.copy(blocks = st.blocks.map { b ->
                if (b.index != set.blockIndex) b else b.copy(sets = b.sets.map { if (it.id == set.id) set else it })
            })
        }
    }

    /**
     * Writes run strictly one after another in tap order (one consumer of an
     * unbounded queue): an edit followed by a quick ✓ must never land after
     * the completion and reopen — and later drop — the set.
     */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            for (write in writes) runCatching { write() }
                .onFailure { android.util.Log.e("WorkoutViewModel", "write failed", it) }
        }
    }

    private fun io(block: suspend () -> Unit) {
        writes.trySend(block)
    }
}
