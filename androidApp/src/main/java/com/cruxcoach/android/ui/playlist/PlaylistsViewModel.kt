package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class PlaylistsState(
    val playlists: List<Climb_lists> = emptyList(),
    val showCreateDialog: Boolean = false,
    val newPlaylistName: String = "",
    val deleteConfirmId: Long? = null,
)

/** Overview of kind='playlist' lists — the plain Merklisten stay on
 *  [com.cruxcoach.android.ui.board.BoardListsViewModel]. */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.safeLaunch(TAG) {
            val playlists = withContext(Dispatchers.IO) {
                personalBoardRepo.getAllClimbLists().filter { it.kind == "playlist" }
            }
            _state.update { it.copy(playlists = playlists) }
        }
    }

    fun showCreateDialog() = _state.update { it.copy(showCreateDialog = true, newPlaylistName = "") }
    fun dismissCreateDialog() = _state.update { it.copy(showCreateDialog = false) }
    fun updateNewPlaylistName(name: String) = _state.update { it.copy(newPlaylistName = name) }

    /** Creates an empty manual playlist; returns via [onCreated] with the id. */
    fun createManualPlaylist(onCreated: (Long) -> Unit) {
        val name = _state.value.newPlaylistName.trim()
        if (name.isBlank()) return
        viewModelScope.safeLaunch(TAG) {
            val id = withContext(Dispatchers.IO) { personalBoardRepo.createPlaylist(name) }
            _state.update { it.copy(showCreateDialog = false, newPlaylistName = "") }
            refresh()
            onCreated(id)
        }
    }

    fun requestDelete(listId: Long) = _state.update { it.copy(deleteConfirmId = listId) }
    fun dismissDelete() = _state.update { it.copy(deleteConfirmId = null) }

    fun confirmDelete() {
        val id = _state.value.deleteConfirmId ?: return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) { personalBoardRepo.deleteClimbList(id) }
            _state.update { it.copy(deleteConfirmId = null) }
            refresh()
        }
    }

    private companion object {
        const val TAG = "PlaylistsVM"
    }
}
