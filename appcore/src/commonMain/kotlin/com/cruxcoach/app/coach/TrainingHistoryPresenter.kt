// Port of androidApp/.../ui/training/workout/TrainingHistoryScreen.kt's TrainingHistoryViewModel
// (0e1bad0c6) for the iOS app. Platform seams: an injected scope and IO dispatcher; the months are
// sorted by key instead of `toSortedMap`, which Kotlin/Native lacks.
package com.cruxcoach.app.coach

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.model.Workout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TrainingHistoryRow(val workout: Workout, val sets: Int)

internal data class TrainingHistoryRunState(
    val loading: Boolean = true,
    /** "yyyy-MM" → workouts of that month, newest first. */
    val months: List<Pair<String, List<TrainingHistoryRow>>> = emptyList(),
)

internal class TrainingHistoryPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(TrainingHistoryRunState())
    val state: StateFlow<TrainingHistoryRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            repo.observeRecentWorkouts(LIMIT).collect { workouts ->
                val rows = workouts.map { w -> TrainingHistoryRow(w, repo.setsFor(w.id).count { it.isCompleted }) }
                val months = rows.groupBy { it.workout.day.take(7) }.toList().sortedByDescending { it.first }
                _state.value = TrainingHistoryRunState(loading = false, months = months)
            }
        }
    }

    fun close() = scope.cancel()

    companion object {
        const val LIMIT = 100
    }
}
