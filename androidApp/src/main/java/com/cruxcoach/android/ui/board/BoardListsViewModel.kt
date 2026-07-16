package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.Climb_lists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BoardListsState(
    val lists: List<Climb_lists> = emptyList(),
    val showCreateDialog: Boolean = false,
    val newListName: String = "",
    val deleteConfirmListId: Long? = null
)

@HiltViewModel
class BoardListsViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BoardListsState())
    val state: StateFlow<BoardListsState> = _state.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.ensureFavoritesListExists()
                // Seed the built-in "Ignored" list so it's visible here as the
                // management surface for un-ignoring climbs (tap → list detail
                // → remove). Created lazily once, then persists.
                personalBoardRepo.ensureIgnoredListExists()
            }
            refreshLists()
        }
    }

    fun refresh() {
        viewModelScope.safeLaunch(TAG) { refreshLists() }
    }

    private suspend fun refreshLists() {
        val lists = withContext(Dispatchers.IO) {
            personalBoardRepo.getAllClimbLists()
        }
        _state.update { it.copy(lists = lists) }
    }

    fun showCreateDialog() {
        _state.update { it.copy(showCreateDialog = true, newListName = "") }
    }

    fun dismissCreateDialog() {
        _state.update { it.copy(showCreateDialog = false) }
    }

    fun updateNewListName(name: String) {
        _state.update { it.copy(newListName = name) }
    }

    fun createList() {
        val name = _state.value.newListName.trim()
        if (name.isBlank()) return
        val exists = _state.value.lists.any { it.name.equals(name, ignoreCase = true) }
        if (exists) return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.createClimbList(name)
            }
            _state.update { it.copy(showCreateDialog = false, newListName = "") }
            refreshLists()
        }
    }

    fun requestDeleteList(listId: Long) {
        _state.update { it.copy(deleteConfirmListId = listId) }
    }

    fun dismissDeleteConfirm() {
        _state.update { it.copy(deleteConfirmListId = null) }
    }

    fun confirmDeleteList() {
        val listId = _state.value.deleteConfirmListId ?: return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.deleteClimbList(listId)
            }
            _state.update { it.copy(deleteConfirmListId = null) }
            refreshLists()
        }
    }

    private companion object {
        const val TAG = "BoardListsVM"
    }
}
