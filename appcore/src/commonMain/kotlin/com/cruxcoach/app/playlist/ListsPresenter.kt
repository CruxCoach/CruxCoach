package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ListsError { NONE, LOAD_FAILED, CREATE_FAILED, DELETE_FAILED, DUPLICATE_NAME, MEMBERSHIP_FAILED }

data class ListsState(
    val isLoading: Boolean = true,
    val lists: List<Climb_lists> = emptyList(),
    val showCreateDialog: Boolean = false,
    val newListName: String = "",
    val deleteConfirmListId: Long? = null,
    /** Climb the add-to-list sheet is about; empty when it is closed. */
    val membershipClimbUuid: String = "",
    val membershipListIds: Set<Long> = emptySet(),
    val isFavorite: Boolean = false,
    val isIgnored: Boolean = false,
    val error: ListsError = ListsError.NONE,
)

/**
 * Port of Android `BoardListsViewModel` plus `AddToListViewModel`: the user's
 * climb lists, including the two built-ins (Favorites, Ignored), and the
 * membership toggles for one climb.
 */
class ListsPresenter(
    private val personalBoardRepo: PersonalBoardRepository,
    private val climbLookup: ListClimbLookup = EmptyClimbLookup,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ListsState())
    val state: StateFlow<ListsState> = _state.asStateFlow()

    /** Angle a newly added climb pins in an explicit plan. */
    private var membershipAngle: Int = 40

    init {
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    personalBoardRepo.ensureFavoritesListExists()
                    // The Ignored list is the only surface for un-ignoring a climb.
                    personalBoardRepo.ensureIgnoredListExists()
                }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = ListsError.LOAD_FAILED) }
            }
        }
    }

    fun watch(onState: (ListsState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun refresh() {
        scope.launch {
            try {
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = ListsError.LOAD_FAILED) }
            }
        }
    }

    private suspend fun refreshLists() {
        val all = withContext(ioDispatcher) { personalBoardRepo.getAllClimbLists() }
        _state.update { it.copy(isLoading = false, lists = all) }
    }

    fun showCreateDialog() = _state.update { it.copy(showCreateDialog = true, newListName = "") }

    fun dismissCreateDialog() = _state.update { it.copy(showCreateDialog = false) }

    fun updateNewListName(name: String) = _state.update { it.copy(newListName = name) }

    fun createList() {
        val name = _state.value.newListName.trim()
        if (name.isEmpty()) return
        if (_state.value.lists.any { it.name.equals(name, ignoreCase = true) }) {
            _state.update { it.copy(error = ListsError.DUPLICATE_NAME) }
            return
        }
        scope.launch {
            try {
                withContext(ioDispatcher) { personalBoardRepo.createClimbList(name) }
                _state.update { it.copy(showCreateDialog = false, newListName = "") }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = ListsError.CREATE_FAILED) }
            }
        }
    }

    fun requestDeleteList(listId: Long) = _state.update { it.copy(deleteConfirmListId = listId) }

    fun dismissDeleteConfirm() = _state.update { it.copy(deleteConfirmListId = null) }

    fun confirmDeleteList() {
        val listId = _state.value.deleteConfirmListId ?: return
        scope.launch {
            try {
                withContext(ioDispatcher) { personalBoardRepo.deleteClimbList(listId) }
                _state.update { it.copy(deleteConfirmListId = null) }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(deleteConfirmListId = null, error = ListsError.DELETE_FAILED) }
            }
        }
    }

    // --- Membership for one climb (Android AddToListViewModel) ---

    fun openClimb(climbUuid: String, angle: Int) {
        membershipAngle = angle
        _state.update { it.copy(membershipClimbUuid = climbUuid, membershipListIds = emptySet()) }
        scope.launch {
            try {
                val (ids, favorite, ignored) = withContext(ioDispatcher) {
                    personalBoardRepo.ensureFavoritesListExists()
                    val identities = climbLookup.equivalentUuids(climbUuid)
                    Triple(
                        identities.flatMap(personalBoardRepo::getListIdsForClimb).toSet(),
                        personalBoardRepo.isClimbFavorited(climbUuid),
                        personalBoardRepo.isClimbIgnored(climbUuid),
                    )
                }
                _state.update {
                    if (it.membershipClimbUuid != climbUuid) it
                    else it.copy(membershipListIds = ids, isFavorite = favorite, isIgnored = ignored)
                }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = ListsError.MEMBERSHIP_FAILED) }
            }
        }
    }

    fun closeClimb() = _state.update { it.copy(membershipClimbUuid = "", membershipListIds = emptySet()) }

    fun toggleList(listId: Long) {
        val climbUuid = _state.value.membershipClimbUuid
        if (climbUuid.isEmpty()) return
        val currentlyIn = listId in _state.value.membershipListIds
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    if (currentlyIn) {
                        // Alias spellings are separate rows; remove every one of them.
                        climbLookup.equivalentUuids(climbUuid).forEach {
                            personalBoardRepo.removeClimbFromList(listId, it)
                        }
                    } else {
                        personalBoardRepo.addClimbToListAndExtendPlayback(
                            listId = listId,
                            climbUuid = climbUuid,
                            angle = membershipAngle.toLong(),
                        )
                    }
                }
                _state.update {
                    it.copy(
                        membershipListIds =
                            if (currentlyIn) it.membershipListIds - listId else it.membershipListIds + listId,
                    )
                }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = ListsError.MEMBERSHIP_FAILED) }
            }
        }
    }

    /** Creates the list if the name is new, then adds the open climb to it. */
    fun createListAndAdd() {
        val name = _state.value.newListName.trim()
        val climbUuid = _state.value.membershipClimbUuid
        if (name.isEmpty() || climbUuid.isEmpty()) return
        _state.value.lists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { existing ->
            // Android toggles here, so re-entering the name of a list the climb is
            // already in REMOVES it rather than doing nothing. Kept for parity.
            toggleList(existing.id)
            _state.update { it.copy(newListName = "") }
            return
        }
        scope.launch {
            try {
                val newId = withContext(ioDispatcher) {
                    val id = personalBoardRepo.createClimbList(name)
                    personalBoardRepo.addClimbToListAndExtendPlayback(id, climbUuid, membershipAngle.toLong())
                    id
                }
                _state.update { it.copy(membershipListIds = it.membershipListIds + newId, newListName = "") }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = ListsError.CREATE_FAILED) }
            }
        }
    }

    fun toggleFavorite() = toggleBuiltin(favorite = true)

    fun toggleIgnored() = toggleBuiltin(favorite = false)

    private fun toggleBuiltin(favorite: Boolean) {
        val climbUuid = _state.value.membershipClimbUuid
        if (climbUuid.isEmpty()) return
        scope.launch {
            try {
                val now = withContext(ioDispatcher) {
                    if (favorite) personalBoardRepo.toggleFavorite(climbUuid)
                    else personalBoardRepo.toggleIgnored(climbUuid)
                }
                val listId = withContext(ioDispatcher) {
                    if (favorite) personalBoardRepo.ensureFavoritesListExists()
                    else personalBoardRepo.ensureIgnoredListExists()
                }
                _state.update {
                    it.copy(
                        isFavorite = if (favorite) now else it.isFavorite,
                        isIgnored = if (favorite) it.isIgnored else now,
                        membershipListIds =
                            if (now) it.membershipListIds + listId else it.membershipListIds - listId,
                    )
                }
                refreshLists()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = ListsError.MEMBERSHIP_FAILED) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = ListsError.NONE) }

    fun close() = scope.cancel()
}
