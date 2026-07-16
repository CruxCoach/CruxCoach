package com.cruxcoach.android.ui.workout

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.domain.model.ExerciseBlock
import com.cruxcoach.domain.model.PlannedSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExerciseProgress(
    val exercise: ExerciseBlock,
    val completedSets: Int = 0,
    val isCompleted: Boolean = false
)

data class ActiveWorkoutUiState(
    val isLoading: Boolean = true,
    val session: PlannedSession? = null,
    val exerciseProgress: List<ExerciseProgress> = emptyList(),
    val currentExerciseIndex: Int = 0,
    val currentSet: Int = 1,
    val isResting: Boolean = false,
    val restSecondsRemaining: Int = 0,
    val elapsedSeconds: Long = 0,
    val isFinished: Boolean = false,
    val error: String? = null
) {
    val currentExercise: ExerciseProgress?
        get() = exerciseProgress.getOrNull(currentExerciseIndex)

    val totalExercises: Int
        get() = exerciseProgress.size

    val completedExercises: Int
        get() = exerciseProgress.count { it.isCompleted }

    val overallProgress: Float
        get() {
            if (exerciseProgress.isEmpty()) return 0f
            val totalSets = exerciseProgress.sumOf { it.exercise.sets }
            val completedSets = exerciseProgress.sumOf { it.completedSets }
            return if (totalSets > 0) completedSets.toFloat() / totalSets else 0f
        }
}

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planRepository: PlanRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val sessionId: Long = savedStateHandle.get<String>("sessionId")?.toLongOrNull() ?: 0

    private val _state = MutableStateFlow(ActiveWorkoutUiState())
    val state: StateFlow<ActiveWorkoutUiState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var restTimerJob: Job? = null
    private var startTimeMs: Long = 0

    init {
        loadSession()
    }

    private fun loadSession() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val session = findSessionById(sessionId)
                    if (session != null) {
                        val progress = session.exercises.map { ExerciseProgress(exercise = it) }
                        _state.update { it.copy(
                            isLoading = false,
                            session = session,
                            exerciseProgress = progress
                        ) }
                        startElapsedTimer()
                    } else {
                        _state.update { it.copy(
                            isLoading = false,
                            error = context.getString(R.string.error_session_not_found)
                        ) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Session load failed", e)
                _state.update { it.copy(isLoading = false, error = context.getString(R.string.workout_error)) }
            }
        }
    }

    private fun findSessionById(id: Long): PlannedSession? {
        val allPlans = planRepository.getAllPlans(1)
        for (plan in allPlans) {
            val sessions = planRepository.getSessionsForPlan(plan.id)
            val found = sessions.find { it.id == id }
            if (found != null) return found
        }
        return null
    }

    private fun startElapsedTimer() {
        startTimeMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
                _state.update { it.copy(elapsedSeconds = elapsed) }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    private companion object {
        const val TAG = "ActiveWorkoutVM"
    }

    fun completeSet() {
        val state = _state.value
        val current = state.currentExercise ?: return
        val exercise = current.exercise

        val newCompletedSets = current.completedSets + 1
        val isExerciseDone = newCompletedSets >= exercise.sets

        val updatedProgress = state.exerciseProgress.toMutableList()
        updatedProgress[state.currentExerciseIndex] = current.copy(
            completedSets = newCompletedSets,
            isCompleted = isExerciseDone
        )

        if (isExerciseDone) {
            // Move to next exercise or finish
            val nextIndex = state.currentExerciseIndex + 1
            if (nextIndex >= state.totalExercises) {
                // All exercises done
                timerJob?.cancel()
                _state.update { it.copy(
                    exerciseProgress = updatedProgress,
                    isFinished = true
                ) }
            } else {
                _state.update { it.copy(
                    exerciseProgress = updatedProgress,
                    currentExerciseIndex = nextIndex,
                    currentSet = 1,
                    isResting = false
                ) }
            }
        } else {
            // Start rest timer between sets
            _state.update { it.copy(
                exerciseProgress = updatedProgress,
                currentSet = newCompletedSets + 1,
                isResting = true,
                restSecondsRemaining = exercise.restSeconds
            ) }
            startRestTimer(exercise.restSeconds)
        }
    }

    private fun startRestTimer(seconds: Int) {
        restTimerJob?.cancel()
        restTimerJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _state.update { it.copy(restSecondsRemaining = remaining) }
                if (remaining > 0) delay(1000)
            }
            _state.update { it.copy(isResting = false) }
        }
    }

    fun skipRest() {
        restTimerJob?.cancel()
        _state.update { it.copy(isResting = false, restSecondsRemaining = 0) }
    }

    fun skipExercise() {
        val state = _state.value
        val nextIndex = state.currentExerciseIndex + 1

        restTimerJob?.cancel()

        if (nextIndex >= state.totalExercises) {
            timerJob?.cancel()
            _state.update { it.copy(isFinished = true, isResting = false) }
        } else {
            _state.update { it.copy(
                currentExerciseIndex = nextIndex,
                currentSet = 1,
                isResting = false,
                restSecondsRemaining = 0
            ) }
        }
    }

    fun previousExercise() {
        val state = _state.value
        if (state.currentExerciseIndex > 0) {
            restTimerJob?.cancel()
            _state.update { it.copy(
                currentExerciseIndex = state.currentExerciseIndex - 1,
                currentSet = 1,
                isResting = false,
                restSecondsRemaining = 0
            ) }
        }
    }

    fun getCompletedExercises(): List<ExerciseBlock> {
        return _state.value.exerciseProgress
            .filter { it.completedSets > 0 }
            .map { it.exercise }
    }

    fun getElapsedMinutes(): Int {
        return (_state.value.elapsedSeconds / 60).toInt()
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        restTimerJob?.cancel()
    }
}
