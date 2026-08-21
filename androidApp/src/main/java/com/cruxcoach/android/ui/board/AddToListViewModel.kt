package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionVisibility
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
    /** True while any playlist is running — offers the add-to-queue row. */
    val playbackActive: Boolean = false,
    /** The user is connected to a board group; its playlist is always available. */
    val boardGroupActive: Boolean = false,
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
    private val boardCellManager: BoardCellManager,
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
                    // Membership, not availability. Controller recovery
                    // freezes writes without ending membership, and during
                    // that handover this dialog was silently falling back to
                    // the legacy session queue while the group's list was
                    // still the thing that owned the board.
                    boardGroupActive = boardCellManager.localParticipatesInSharedPlaylist(),
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

    /**
     * One-tap Board action. The first climb creates the Board playlist;
     * later taps append to the same canonical queue. Local lists remain
     * independent and are still offered below this primary action.
     */
    fun addToBoardPlaylist(
        selectedClimbUuid: String = climbUuid,
        selectedAngle: Int = angle,
    ) {
        if (_state.value.addedToRunning && climbUuid == selectedClimbUuid && angle == selectedAngle) return
        climbUuid = selectedClimbUuid
        angle = selectedAngle
        if (boardCellManager.localParticipatesInSharedPlaylist()) {
            // The BoardCell always has exactly one playlist, so this is always
            // "add to the group's list" — never "start a second one".
            playback.play(
                hostName = "",
                items = listOf(QueueItem(selectedClimbUuid, selectedAngle)),
                visibility = SessionVisibility.JOINABLE,
            )
        } else {
            queueManager.addClimb(selectedClimbUuid, selectedAngle)
        }
        _state.update { it.copy(addedToRunning = true) }
    }

    private companion object {
        const val TAG = "AddToListVM"
    }
}
