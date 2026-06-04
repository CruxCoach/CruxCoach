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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BoardListDetailState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val listId: Long = 0,
    val listName: String = "",
    val entries: List<Climb_list_entries> = emptyList(),
    val totalCount: Long = 0,
    val canLoadMore: Boolean = false,
    /** Secure-DB uuid offset reached so far (entry-driven pagination cursor).
     *  Pages advance over the board-agnostic uuid list, not over the
     *  board-scoped visible-entry count, so off-board entries that resolve to
     *  nothing don't desync the cursor. */
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
        viewModelScope.launch {
            userPreferences.boardAngle.collect { angle ->
                _state.update { it.copy(angle = angle) }
            }
        }
        viewModelScope.launch {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        viewModelScope.launch {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        // Re-query when the active board changes while this screen is open —
        // the list is now board-scoped, so a board switch must re-resolve the
        // visible entries (mirrors BoardBrowserViewModel's board-flow collect).
        viewModelScope.launch {
            combine(
                userPreferences.boardBrand,
                userPreferences.boardLayoutId,
                userPreferences.boardProductSizeId,
            ) { brand, layout, size -> Triple(brand, layout, size) }
                .drop(1) // initial load is handled by loadList()
                .distinctUntilChanged()
                .collect { loadList() }
        }
        loadList()
    }

    private fun loadList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val angle = _state.value.angle
                val board = boardSnapshot()
                val page = loadEntries(listId, angle, board, PAGE_SIZE, 0)
                // totalCount is the board-AGNOSTIC secure-DB entry count — it
                // counts entries on every board, so it's only a coarse header
                // hint, NOT the pagination boundary (see loadMore). After
                // board-scoping, scoped entries.size can be < this count even
                // when the page is full, so canLoadMore is entry-driven below.
                val count = personalBoardRepo.countClimbListEntries(listId)
                _state.update { it.copy(
                    isLoading = false,
                    listName = list?.name ?: "",
                    entries = page.entries,
                    totalCount = count,
                    uuidOffset = page.lastUuidOffset,
                    // Entry-driven: a full uuid page (PAGE_SIZE uuids consumed)
                    // means more uuid pages may exist. We page over the
                    // secure-DB uuid offset, not over the scoped result size.
                    canLoadMore = page.lastUuidOffset >= PAGE_SIZE,
                ) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.canLoadMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val board = boardSnapshot()
                // Page over the secure-DB uuid OFFSET, not over the (possibly
                // smaller, board-scoped) visible entry count — otherwise scoped
                // entries that filtered out a board's uuids would desync the
                // offset and re-fetch / skip uuid pages.
                val page = loadEntries(listId, s.angle, board, PAGE_SIZE, s.uuidOffset)
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

    /** Active board snapshot (brand + layout + product size) for scoping the
     *  list resolution. Snapshot via .first() so the page query is consistent. */
    private suspend fun boardSnapshot(): BoardSnapshot = BoardSnapshot(
        brand = userPreferences.boardBrand.first(),
        layoutId = userPreferences.boardLayoutId.first(),
        productSizeId = userPreferences.boardProductSizeId.first(),
    )

    private data class BoardSnapshot(val brand: String, val layoutId: Int, val productSizeId: Int)

    /** Result of resolving one uuid page: the scoped visible entries plus the
     *  secure-DB uuid offset reached (used as the next page's offset). */
    private data class EntryPage(val entries: List<Climb_list_entries>, val lastUuidOffset: Int)

    /** Two-phase: get UUIDs from SecureDB, then BOARD-SCOPED climb details
     *  from BoardDB. Off-board entries resolve to nothing and are dropped,
     *  so the page is entry-driven over the uuid offset. */
    private fun loadEntries(
        listId: Long, angle: Int, board: BoardSnapshot, limit: Int, offset: Int
    ): EntryPage {
        val uuidPairs = personalBoardRepo.getClimbListEntryUuids(listId, limit, offset)
        if (uuidPairs.isEmpty()) return EntryPage(emptyList(), offset)
        val uuids = uuidPairs.map { it.first }
        // Board-scoped resolution: only entries on the active board (or, for
        // Kilter, other Kilter layouts that fit the active size) surface.
        val climbs = boardRepository.getClimbsByUuidsForBoard(
            uuids, angle, board.brand, board.layoutId, board.productSizeId
        )
        // Recover entries with no row at the requested angle — notably
        // MoonBoard Masters problems set only at 25° — via an angle-agnostic
        // fallback that STAYS board-scoped so other-board climbs don't re-leak.
        val resolved = climbs.associateBy { it.uuid }
        val missing = uuids.filter { it !in resolved }
        val climbMap = if (missing.isEmpty()) resolved
            else resolved + boardRepository.getClimbsByUuidsForBoardAnyAngle(
                missing, board.brand, board.layoutId, board.productSizeId
            ).associateBy { it.uuid }
        val entries = uuidPairs.mapNotNull { (uuid, addedAt) ->
            climbMap[uuid]?.let { climb -> Climb_list_entries(addedAt = addedAt, climb = climb) }
        }
        return EntryPage(entries, offset + uuidPairs.size)
    }

    fun removeFromList(climbUuid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                personalBoardRepo.removeClimbFromList(listId, climbUuid)
            }
            _state.update { s ->
                val updated = s.entries.filterNot { it.climb.uuid == climbUuid }
                s.copy(entries = updated, totalCount = s.totalCount - 1)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
