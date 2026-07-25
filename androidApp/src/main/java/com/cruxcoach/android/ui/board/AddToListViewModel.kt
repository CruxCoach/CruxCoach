package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionQueueManager
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

data class AddToListState(
    val lists: List<Climb_lists> = emptyList(),
    val climbInListIds: Set<Long> = emptySet(),
    val newListName: String = "",
    /** True while a playlist is running — offers the add-to-queue row. */
    val playbackActive: Boolean = false,
    /** One-shot feedback after adding to the running playlist. */
    val addedToRunning: Boolean = false,
)

/**
 * Self-contained backing for the add-to-list dialog so ANY screen can
 * offer "add this climb to a list" without re-implementing the
 * toggle logic (previously only the climb-detail VM carried it). Also
 * powers the "add to the running session" shortcut.
 */
@HiltViewModel
class AddToListViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val queueManager: SessionQueueManager,
    private val playback: PlaylistPlaybackCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(AddToListState())
    val state = _state.asStateFlow()

    private var climbUuid: String = ""
    private var angle: Int = 40

    /** Load lists + membership for the climb the dialog is about. */
    fun open(climbUuid: String, angle: Int) {
        this.climbUuid = climbUuid
        this.angle = angle
        viewModelScope.safeLaunch(TAG) {
            val (lists, inIds) = withContext(Dispatchers.IO) {
                personalBoardRepo.ensureFavoritesListExists()
                val lists = personalBoardRepo.getAllClimbLists().filterNot { it.isIgnored }
                lists to personalBoardRepo.getListIdsForClimb(climbUuid)
            }
            _state.update {
                AddToListState(
                    lists = lists,
                    climbInListIds = inIds,
                    playbackActive = playback.state.value.isActive,
                )
            }
        }
    }

    fun toggleList(listId: Long) {
        viewModelScope.safeLaunch(TAG) {
            val currentlyIn = _state.value.climbInListIds.contains(listId)
            withContext(Dispatchers.IO) {
                if (currentlyIn) {
                    personalBoardRepo.removeClimbFromList(listId, climbUuid)
                } else {
                    personalBoardRepo.addClimbToListAndExtendPlayback(
                        listId = listId,
                        climbUuid = climbUuid,
                        angle = angle.toLong(),
                    )
                }
            }
            _state.update {
                it.copy(
                    climbInListIds = if (currentlyIn) it.climbInListIds - listId
                                     else it.climbInListIds + listId,
                )
            }
        }
    }

    fun updateNewListName(name: String) = _state.update { it.copy(newListName = name) }

    fun createNewListAndAdd() {
        val name = _state.value.newListName.trim()
        if (name.isBlank()) return
        _state.value.lists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            toggleList(it.id)
            _state.update { s -> s.copy(newListName = "") }
            return
        }
        viewModelScope.safeLaunch(TAG) {
            val newId = withContext(Dispatchers.IO) {
                val id = personalBoardRepo.createClimbList(name)
                personalBoardRepo.addClimbToListAndExtendPlayback(
                    listId = id,
                    climbUuid = climbUuid,
                    angle = angle.toLong(),
                )
                id
            }
            val lists = withContext(Dispatchers.IO) {
                personalBoardRepo.getAllClimbLists().filterNot { it.isIgnored }
            }
            _state.update {
                it.copy(
                    lists = lists,
                    climbInListIds = it.climbInListIds + newId,
                    newListName = "",
                )
            }
        }
    }

    /** Append to the RUNNING playlist's queue (role-aware inside). */
    fun addToRunningPlaylist() {
        if (_state.value.addedToRunning) return
        queueManager.addClimb(climbUuid, angle)
        _state.update { it.copy(addedToRunning = true) }
    }

    private companion object {
        const val TAG = "AddToListVM"
    }
}
