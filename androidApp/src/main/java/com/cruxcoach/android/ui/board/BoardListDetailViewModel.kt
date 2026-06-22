package com.cruxcoach.android.ui.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.Climb_list_entries
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.cruxcoach.android.util.safeLaunch

data class BoardListDetailState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val listId: Long = 0,
    val listName: String = "",
    val entries: List<Climb_list_entries> = emptyList(),
    val totalCount: Long = 0,
    val canLoadMore: Boolean = false,
    /** Secure-DB uuid offset reached so far (entry-driven pagination cursor).
     *  Pages advance over the entry uuid list; an entry whose climb isn't in
     *  the local board DB (its board's catalogue isn't downloaded) resolves to
     *  nothing and is skipped, so we page over the uuid offset, not the
     *  (possibly smaller) resolved-entry count. */
    val uuidOffset: Int = 0,
    val angle: Int = 40,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val zones: IntensityZones? = null
)

@HiltViewModel
class BoardListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val zoneManager: IntensityZoneManager,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState
) : ViewModel() {

    private val listId: Long = savedStateHandle.get<String>("listId")?.toLongOrNull() ?: 0

    private val _state = MutableStateFlow(BoardListDetailState(listId = listId))
    val state: StateFlow<BoardListDetailState> = _state.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.boardAngle.collect { angle ->
                _state.update { it.copy(angle = angle) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        // FEAT-023: lists are board-AGNOSTIC. A saved list is the user's
        // explicit selection, NOT the catalogue — so it shows ALL its entries
        // regardless of the active board (no silent hiding). A board switch
        // therefore no longer re-scopes the visible entries: each card's board
        // badge labels its own board, and the send path (BoardSendController)
        // already refuses / warns on wrong-board sends. The ON_RESUME refresh
        // (BoardListDetailScreen) re-loads grades after a Settings board/angle
        // change.
        loadList()
    }

    /** Re-query from the first page — used on ON_RESUME so an edit/delete done
     *  on the detail screen reflects instantly on return. */
    fun refresh() = loadList()

    private fun loadList() {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val angle = _state.value.angle
                val page = loadEntries(listId, angle, PAGE_SIZE, 0)
                val count = personalBoardRepo.countClimbListEntries(listId)
                _state.update { it.copy(
                    isLoading = false,
                    listName = list?.name ?: "",
                    entries = page.entries,
                    totalCount = count,
                    uuidOffset = page.lastUuidOffset,
                    // Entry-driven: a full uuid page (PAGE_SIZE uuids consumed)
                    // means more uuid pages may exist.
                    canLoadMore = page.lastUuidOffset >= PAGE_SIZE,
                ) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.canLoadMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val page = loadEntries(listId, s.angle, PAGE_SIZE, s.uuidOffset)
                val consumed = page.lastUuidOffset - s.uuidOffset
                val combined = s.entries + page.entries
                _state.update { it.copy(
                    isLoadingMore = false,
                    entries = combined,
                    uuidOffset = page.lastUuidOffset,
                    // Keep loading while the last uuid page came back full;
                    // stop when a short/empty uuid page signals the list end.
                    canLoadMore = consumed >= PAGE_SIZE,
                ) }
            }
        }
    }

    /** Result of resolving one uuid page: the resolved entries plus the
     *  secure-DB uuid offset reached (used as the next page's offset). */
    private data class EntryPage(val entries: List<Climb_list_entries>, val lastUuidOffset: Int)

    /** Two-phase: get UUIDs from SecureDB, then BOARD-AGNOSTIC climb details
     *  from BoardDB (FEAT-023). Every entry resolves regardless of the active
     *  board — at the active angle if it has stats there, else at a
     *  representative angle (so e.g. MoonBoard Masters problems set only at 25°
     *  still surface). An entry whose climb isn't in the local board DB at all
     *  (its board's catalogue isn't downloaded) resolves to nothing and is
     *  dropped, so the page stays entry-driven over the uuid offset. */
    private fun loadEntries(
        listId: Long, angle: Int, limit: Int, offset: Int
    ): EntryPage {
        val uuidPairs = personalBoardRepo.getClimbListEntryUuids(listId, limit, offset)
        if (uuidPairs.isEmpty()) return EntryPage(emptyList(), offset)
        val uuids = uuidPairs.map { it.first }
        val climbs = boardRepository.getClimbsByUuids(uuids, angle)
        val resolved = climbs.associateBy { it.uuid }
        // Recover entries with no row at the requested angle — notably
        // MoonBoard Masters problems set only at 25° — via the board-agnostic
        // any-angle fallback (one representative row per climb).
        val missing = uuids.filter { it !in resolved }
        val climbMap = if (missing.isEmpty()) resolved
            else resolved + boardRepository.getClimbsByUuidsAnyAngle(missing).associateBy { it.uuid }
        val entries = uuidPairs.mapNotNull { (uuid, addedAt) ->
            climbMap[uuid]?.let { climb -> Climb_list_entries(addedAt = addedAt, climb = climb) }
        }
        return EntryPage(entries, offset + uuidPairs.size)
    }

    fun removeFromList(climbUuid: String) {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.removeClimbFromList(listId, climbUuid)
            }
            _state.update { s ->
                val updated = s.entries.filterNot { it.climb.uuid == climbUuid }
                s.copy(
                    entries = updated,
                    totalCount = (s.totalCount - 1).coerceAtLeast(0),
                )
            }
        }
    }

    companion object {
        private const val TAG = "BoardListDetailVM"
        private const val PAGE_SIZE = 50
    }
}
