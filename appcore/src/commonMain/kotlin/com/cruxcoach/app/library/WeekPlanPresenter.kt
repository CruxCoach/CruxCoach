package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.WeekPlanSuggester
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Routine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class WeekPlanState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val routines: List<Routine> = emptyList(),
    /** Standard week proposed from the coach answers and the logbook's rhythm (FEAT-071). */
    val proposal: Map<Int, String> = emptyMap(),
    /** "Rückgängig" is offered after "Übernehmen" (audit P2-14). */
    val canUndo: Boolean = false,
    val failed: Boolean = false,
)

/** Port of Android `WeekPlanViewModel`: the standard week and the session length. */
class WeekPlanPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(WeekPlanState())
    val state: StateFlow<WeekPlanState> = _state.asStateFlow()
    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(loading = false, failed = true) } }

    init {
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val logbook = runCatching { service.logbookSummary() }.getOrDefault(LogbookSummary())
                combine(service.repo.observeProfile(), service.repo.observeRoutines(), service.repo.observeActiveInjuries()) { profile, routines, injuries ->
                    val proposal = WeekPlanSuggester.suggest(profile.coach, logbook, routines, profile.equipment, injuries)
                    Triple(profile, routines, proposal)
                }.collect { (profile, routines, proposal) ->
                    _state.update { it.copy(loading = false, profile = profile, routines = routines, proposal = proposal) }
                }
            }
        }
    }

    /** [entry] null = free day. */
    fun set(day: Int, entry: String?) = io {
        service.repo.updateProfile { p ->
            val plan = p.weekPlan.toMutableMap()
            if (entry == null) plan.remove(day) else plan[day] = entry
            p.copy(weekPlan = plan)
        }
    }

    fun setMinutes(minutes: Int) = io { service.repo.updateProfile { it.copy(sessionMinutes = minutes) } }

    /** The week before "Übernehmen", for "Rückgängig" (audit P2-14). */
    private var previousPlan: Map<Int, String>? = null

    fun applyProposal() {
        val plan = _state.value.proposal
        if (plan.isEmpty()) return
        previousPlan = _state.value.profile.weekPlan
        _state.update { it.copy(canUndo = true) }
        io { service.repo.updateProfile { it.copy(weekPlan = plan) } }
    }

    fun undoProposal() {
        val previous = previousPlan ?: return
        previousPlan = null
        _state.update { it.copy(canUndo = false) }
        io { service.repo.updateProfile { it.copy(weekPlan = previous) } }
    }

    /** The undo offer expired (the snackbar went away). */
    fun dropUndo() {
        previousPlan = null
        _state.update { it.copy(canUndo = false) }
    }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); block() } }
    }

    companion object {
        /** The same choices as the time on Today's card (Android `TodayViewModel.DURATION_OPTIONS`). */
        val SESSION_MINUTES = listOf(15, 20, 30, 45, 60, 75, 90, 120)
    }
}
