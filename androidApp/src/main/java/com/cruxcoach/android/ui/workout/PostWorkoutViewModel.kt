package com.cruxcoach.android.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.domain.model.WorkoutLog
import com.cruxcoach.util.DateTimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PostWorkoutState(
    val sessionId: Long? = null,
    val rpe: Float = 7.0f,
    val energyLevel: Int = 3,
    val moodPre: Int = 3,
    val moodPost: Int = 3,
    val skinStatus: String = "GOOD",
    val painAreas: List<String> = emptyList(),
    val sleepHours: Float = 7.0f,
    val notes: String = "",
    val durationMin: Int = 0,
    val completedCount: Int = 0,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PostWorkoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        PostWorkoutState(
            sessionId = savedStateHandle.get<String>("sessionId")?.toLongOrNull(),
            durationMin = savedStateHandle.get<String>("durationMin")?.toIntOrNull() ?: 0,
            completedCount = savedStateHandle.get<String>("completedCount")?.toIntOrNull() ?: 0
        )
    )
    val state: StateFlow<PostWorkoutState> = _state.asStateFlow()

    fun clearError() { _state.update { it.copy(error = null) } }

    fun updateRpe(value: Float) {
        _state.update { it.copy(rpe = value) }
    }

    fun updateEnergyLevel(value: Int) {
        _state.update { it.copy(energyLevel = value.coerceIn(1, 5)) }
    }

    fun updateMoodPre(value: Int) {
        _state.update { it.copy(moodPre = value.coerceIn(1, 5)) }
    }

    fun updateMoodPost(value: Int) {
        _state.update { it.copy(moodPost = value.coerceIn(1, 5)) }
    }

    fun updateSkinStatus(status: String) {
        _state.update { it.copy(skinStatus = status) }
    }

    fun togglePainArea(area: String) {
        _state.update {
            val current = it.painAreas.toMutableList()
            if (area in current) current.remove(area) else current.add(area)
            it.copy(painAreas = current)
        }
    }

    fun updateSleepHours(hours: Float) {
        _state.update { it.copy(sleepHours = hours.coerceIn(0f, 14f)) }
    }

    fun updateNotes(text: String) {
        _state.update { it.copy(notes = text) }
    }

    fun save() {
        val state = _state.value
        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val log = WorkoutLog(
                        sessionId = state.sessionId,
                        date = DateTimeUtil.todayIso(),
                        actualDurationMin = state.durationMin,
                        perceivedRpe = state.rpe.toDouble(),
                        energyLevel = state.energyLevel,
                        moodPre = state.moodPre,
                        moodPost = state.moodPost,
                        fingerSkinStatus = state.skinStatus,
                        painAreas = state.painAreas,
                        sleepHoursPrevNight = state.sleepHours.toDouble(),
                        completedExercises = emptyList(),
                        freeNotes = state.notes.ifBlank { null }
                    )
                    workoutRepository.insertWorkout(log)
                }
                _state.update { it.copy(isSaving = false, isSaved = true) }
            } catch (e: Exception) {
                android.util.Log.w("PostWorkoutViewModel", "saveWorkout failed (${e.javaClass.simpleName})")
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
