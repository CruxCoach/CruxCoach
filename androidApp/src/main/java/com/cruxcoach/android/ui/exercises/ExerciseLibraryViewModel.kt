package com.cruxcoach.android.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.data.repository.ExerciseRepository
import com.cruxcoach.domain.model.ExerciseCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExerciseLibraryState(
    val exercises: List<ExerciseEntry> = emptyList(),
    val selectedCategory: ExerciseCategory? = null,
    val searchQuery: String = "",
    val expandedExerciseId: Long? = null
)

@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseLibraryState())
    val state: StateFlow<ExerciseLibraryState> = _state.asStateFlow()

    init {
        loadExercises()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            val s = _state.value
            val exercises = withContext(Dispatchers.IO) {
                when {
                    s.searchQuery.isNotBlank() -> exerciseRepository.search(s.searchQuery)
                    s.selectedCategory != null -> exerciseRepository.getByCategory(s.selectedCategory.name)
                    else -> exerciseRepository.getAll()
                }
            }
            _state.update { it.copy(exercises = exercises) }
        }
    }

    fun selectCategory(category: ExerciseCategory?) {
        _state.update { it.copy(selectedCategory = category, searchQuery = "") }
        loadExercises()
    }

    fun updateSearch(query: String) {
        _state.update { it.copy(searchQuery = query, selectedCategory = null) }
        loadExercises()
    }

    fun toggleExpanded(exerciseId: Long) {
        _state.update { it.copy(expandedExerciseId = if (it.expandedExerciseId == exerciseId) null else exerciseId) }
    }
}
