package com.cruxcoach.android.ui.climb

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.domain.model.ClimbLog
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.util.GradeConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ClimbLogUiState(
    val gradeIndex: Int = 6,  // unified index: 6 = 6a (V3)
    val sent: Boolean = false,
    val flash: Boolean = false,
    val attempts: Int = 1,
    val selectedStyles: List<String> = emptyList(),
    val selectedHoldTypes: List<String> = emptyList(),
    val boardType: String? = null,
    val notes: String = "",
    val todayClimbs: List<ClimbLog> = emptyList(),
    val todaySends: Int = 0,
    val todayFlashes: Int = 0,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val error: String? = null
)

@HiltViewModel
class ClimbLogViewModel @Inject constructor(
    private val climbRepository: ClimbRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(ClimbLogUiState())
    val state: StateFlow<ClimbLogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Load initial grade scale before first render
            val scale = userPreferences.gradeScale.first()
            _state.update { it.copy(gradeScale = scale) }
            // Then collect live updates
            launch {
                userPreferences.gradeScale.collect { s ->
                    _state.update { it.copy(gradeScale = s) }
                }
            }
        }
        loadTodayClimbs()
    }

    private fun loadTodayClimbs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val today = DateTimeUtil.todayIso()
                val climbs = climbRepository.getSendsForDateRange(today, today)
                val sends = climbs.count { it.sent }
                val flashes = climbs.count { it.flash }

                val lastGrade = climbRepository.getRecentHighestGrade()
                val lastIndex = lastGrade?.let {
                    val idx = GradeConverter.gradeToIndex(it)
                    if (idx >= 0) idx else null
                }

                _state.update { it.copy(
                    todayClimbs = climbs,
                    todaySends = sends,
                    todayFlashes = flashes,
                    gradeIndex = lastIndex ?: it.gradeIndex
                ) }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    fun gradeUp() {
        _state.update { s ->
            val frenchMode = s.gradeScale == GradeScale.FRENCH
            s.copy(gradeIndex = GradeConverter.nextIndex(s.gradeIndex, frenchMode))
        }
    }

    fun gradeDown() {
        _state.update { s ->
            val frenchMode = s.gradeScale == GradeScale.FRENCH
            s.copy(gradeIndex = GradeConverter.prevIndex(s.gradeIndex, frenchMode))
        }
    }

    fun toggleSent() {
        _state.update { it.copy(
            sent = !it.sent,
            flash = if (it.sent) false else it.flash
        ) }
    }

    fun toggleFlash() {
        _state.update {
            if (it.sent) it.copy(flash = !it.flash) else it
        }
    }

    fun incrementAttempts() {
        _state.update { it.copy(attempts = it.attempts + 1) }
    }

    fun decrementAttempts() {
        _state.update {
            if (it.attempts > 1) it.copy(attempts = it.attempts - 1) else it
        }
    }

    fun toggleStyle(style: String) {
        _state.update {
            val current = it.selectedStyles.toMutableList()
            if (style in current) current.remove(style) else current.add(style)
            it.copy(selectedStyles = current)
        }
    }

    fun toggleHoldType(holdType: String) {
        _state.update {
            val current = it.selectedHoldTypes.toMutableList()
            if (holdType in current) current.remove(holdType) else current.add(holdType)
            it.copy(selectedHoldTypes = current)
        }
    }

    fun setBoardType(type: String?) {
        _state.update {
            it.copy(boardType = if (it.boardType == type) null else type)
        }
    }

    fun updateNotes(text: String) {
        _state.update { it.copy(notes = text) }
    }

    fun saveAndNext() {
        val state = _state.value
        _state.update { it.copy(isSaving = true) }

        val gradeString = GradeConverter.indexToFrench(state.gradeIndex)

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val log = ClimbLog(
                        date = DateTimeUtil.todayIso(),
                        grade = gradeString,
                        style = state.selectedStyles.firstOrNull(),
                        holdTypes = state.selectedHoldTypes,
                        attempts = state.attempts,
                        sent = state.sent,
                        flash = state.flash,
                        boardType = state.boardType,
                        notes = state.notes.ifBlank { null }
                    )
                    climbRepository.insertClimb(log)
                }

                val savedIndex = state.gradeIndex
                val displayGrade = gradeString
                _state.update { it.copy(
                    gradeIndex = savedIndex,
                    sent = false,
                    flash = false,
                    attempts = 1,
                    selectedStyles = emptyList(),
                    selectedHoldTypes = emptyList(),
                    boardType = null,
                    notes = "",
                    isSaving = false,
                    savedMessage = "$displayGrade ${if (state.sent) "geschafft" else "versucht"}!",
                    error = null
                ) }
                loadTodayClimbs()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(savedMessage = null) }
    }

    companion object {
        private const val TAG = "ClimbLogViewModel"
    }
}
