package com.cruxcoach.android.ui.training.athlete.comeback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ComebackService
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.DayReading
import com.cruxcoach.athlete.logic.DueSession
import com.cruxcoach.athlete.logic.Prompt
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.StageChangeReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import javax.inject.Inject

data class ComebackUiState(
    val loading: Boolean = true,
    val injury: Injury? = null,
    val entries: List<ComebackEntry> = emptyList(),
    val today: LocalDate? = null,
    val now: Long = 0,
    val under18: Boolean = false,
    val routines: List<Routine> = emptyList(),
    /** Grip name → (kg, from the injured hand before the injury). */
    val suggestedRefs: Map<String, Pair<Double, Boolean>> = emptyMap(),
    val due: List<DueSession> = emptyList(),
    val prompts: List<Prompt> = emptyList(),
    val readings: List<DayReading> = emptyList(),
    val incrementKg: Double = 0.5,
    val catalog: com.cruxcoach.athlete.catalog.ExerciseCatalog? = null,
) {
    val todayEntry: ComebackEntry? get() = today?.let { d -> entries.firstOrNull { it.day == d.toString() } }
}

sealed interface ComebackEvent {
    data class WorkoutStarted(val workoutId: String) : ComebackEvent
    data class Share(val text: String) : ComebackEvent
}

/** One injury's comeback plan; every change here is a tap of the athlete (owner 2026-10-20). */
@HiltViewModel
class ComebackViewModel @Inject constructor(
    private val service: AthleteService,
    savedState: SavedStateHandle,
) : ViewModel() {

    val injuryId: String = savedState.get<String>(ARG_INJURY).orEmpty()
    private val comebacks = ComebackService(service)
    private val _state = MutableStateFlow(ComebackUiState())
    val state: StateFlow<ComebackUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ComebackEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ComebackEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            combine(service.repo.observeInjury(injuryId), service.repo.observeComebackEntries(injuryId), service.repo.observeProfile()) { injury, entries, profile ->
                val today = service.today()
                val now = System.currentTimeMillis()
                ComebackUiState(
                    loading = false, injury = injury, entries = entries, today = today, now = now,
                    under18 = injury != null && Comeback.under18(injury, profile.birthYear, today),
                    routines = service.repo.routines(),
                    suggestedRefs = injury?.let { runCatching { comebacks.suggestedReferences(it) }.getOrDefault(emptyMap()) }.orEmpty(),
                    due = injury?.let { Comeback.due(it, entries, today, now) }.orEmpty(),
                    prompts = injury?.let { Comeback.prompts(it, entries, today) }.orEmpty(),
                    readings = injury?.let { Comeback.readings(it, entries, today.minus(DatePeriod(days = 13)), today) }.orEmpty(),
                    incrementKg = profile.smallestIncrementKg.coerceAtLeast(0.5),
                    catalog = service.catalog,
                )
            }.collect { _state.value = it }
        }
    }

    fun saveCheck(check: InjuryCheck) = io { comebacks.saveCheck(injuryId, check) }
    fun acknowledgeSigns() = io { comebacks.acknowledgeSigns(injuryId) }

    fun start(source: PlanSource, stage: Int, agreed: Boolean, injuredDominant: Boolean?, referenceKg: Map<String, Double>, ownRoutineId: String?) =
        io { comebacks.start(injuryId, source, stage, agreed, injuredDominant, referenceKg, ownRoutineId) }

    fun moveTo(stage: Int, reason: StageChangeReason, promptKey: String? = null) = io {
        promptKey?.let { comebacks.markPrompt(injuryId, it) }
        comebacks.moveTo(injuryId, stage, reason)
    }

    fun dismissPrompt(key: String) = io { comebacks.markPrompt(injuryId, key) }
    fun markAgreed() = io { comebacks.updatePlan(injuryId) { it.copy(agreedWithProfessional = true) } }
    fun setReferences(referenceKg: Map<String, Double>, injuredDominant: Boolean?) =
        io { comebacks.updatePlan(injuryId) { it.copy(referenceKg = referenceKg, injuredDominant = injuredDominant) } }
    fun setOwnRoutine(routineId: String?) = io { comebacks.updatePlan(injuryId) { it.copy(ownRoutineId = routineId) } }

    /** Saves today's diary values as they are entered. */
    fun updateToday(change: (ComebackEntry) -> ComebackEntry) = io {
        val current = comebacks.entry(injuryId)
        val next = change(current)
        if (next != current) comebacks.saveEntry(next)
    }

    fun startSession(index: Int, name: String) = io {
        comebacks.startSession(injuryId, index, name)?.let { _events.emit(ComebackEvent.WorkoutStarted(it)) }
    }

    /** "My physio's plan": the chosen routine as a comeback training, so the finish asks for the pain. */
    fun startOwn(routine: Routine, name: String) = io {
        val id = service.startWorkout(routine.copy(id = Comeback.routineId(injuryId, OWN_STAGE, 0), name = name), name, trimSets = false)
        _events.emit(ComebackEvent.WorkoutStarted(id))
    }

    fun end(resolved: Boolean) = io { comebacks.end(injuryId, resolved) }
    fun deleteAll() = io { comebacks.deleteAll(injuryId) }

    fun share(language: String, labels: ComebackService.SummaryLabels) = io {
        _events.emit(ComebackEvent.Share(comebacks.summary(injuryId, language, labels)))
    }

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }

    companion object {
        const val ARG_INJURY = "injuryId"
        /** Stage number of a session from the athlete's own plan in its training id. */
        const val OWN_STAGE = -1
    }
}
