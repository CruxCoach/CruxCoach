package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.Climb_lists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BoardListsState(
    val lists: List<Climb_lists> = emptyList(),
    /** kind='playlist' rows — shown in their own hub section. */
    val playlists: List<Climb_lists> = emptyList(),
    val showCreateDialog: Boolean = false,
    /** Whether the create dialog makes a playlist (true) or a plain list. */
    val createAsPlaylist: Boolean = false,
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
        viewModelScope.launch {
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
        viewModelScope.launch { refreshLists() }
    }

    private suspend fun refreshLists() {
        val all = withContext(Dispatchers.IO) {
            personalBoardRepo.getAllClimbLists()
        }
        // One hub for everything list-shaped: playlists section on top,
        // plain Merklisten below.
        _state.update {
            it.copy(
                lists = all.filter { l -> l.kind == "list" },
                playlists = all.filter { l -> l.kind == "playlist" },
            )
        }
    }

    fun showCreateDialog(asPlaylist: Boolean = false) {
        _state.update {
            it.copy(showCreateDialog = true, createAsPlaylist = asPlaylist, newListName = "")
        }
    }

    fun dismissCreateDialog() {
        _state.update { it.copy(showCreateDialog = false) }
    }

    fun updateNewListName(name: String) {
        _state.update { it.copy(newListName = name) }
    }

    /** Creates a list or playlist (per dialog mode); playlists report their
     *  id via [onPlaylistCreated] so the screen can jump into the detail. */
    fun createList(onPlaylistCreated: (Long) -> Unit = {}) {
        val name = _state.value.newListName.trim()
        if (name.isBlank()) return
        val asPlaylist = _state.value.createAsPlaylist
        val existing = if (asPlaylist) _state.value.playlists else _state.value.lists
        if (existing.any { it.name.equals(name, ignoreCase = true) }) return
        viewModelScope.launch {
            val playlistId = withContext(Dispatchers.IO) {
                if (asPlaylist) personalBoardRepo.createPlaylist(name)
                else { personalBoardRepo.createClimbList(name); null }
            }
            _state.update { it.copy(showCreateDialog = false, newListName = "") }
            refreshLists()
            playlistId?.let(onPlaylistCreated)
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                personalBoardRepo.deleteClimbList(listId)
            }
            _state.update { it.copy(deleteConfirmListId = null) }
            refreshLists()
        }
    }
}
