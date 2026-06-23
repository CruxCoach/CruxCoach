package com.cruxcoach.android.ui.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
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

/** One board-filter option for a list (FEAT-023): a distinct (brand, layout)
 *  present among the list's entries, with how many entries it covers. The
 *  human label is resolved in the UI layer (needs string resources +
 *  MoonBoardVariant), so only the raw identity travels in state. */
data class BoardFilterOption(val brandWire: String, val layoutId: Long, val count: Int)

data class BoardListDetailState(
    val isLoading: Boolean = true,
    val listId: Long = 0,
    val listName: String = "",
    /** Entries currently shown — the full set narrowed by [selectedFilter]. */
    val entries: List<Climb_list_entries> = emptyList(),
    /** Total entries in the list (board-agnostic; unaffected by the filter). */
    val totalCount: Long = 0,
    /** Distinct boards present in the list. Empty when the list spans a single
     *  board (no point offering a filter). FEAT-023. */
    val boardFilters: List<BoardFilterOption> = emptyList(),
    /** Active board filter; null = "Alle" (every board). */
    val selectedFilter: BoardFilterOption? = null,
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

    /** All resolved entries, board-agnostic and unfiltered. The displayed
     *  [BoardListDetailState.entries] is this narrowed by the active filter;
     *  kept here so toggling the filter never re-queries the DB. */
    private var allEntries: List<Climb_list_entries> = emptyList()

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
        loadList()
    }

    /** Re-query — used on ON_RESUME so an edit/delete done on the detail screen
     *  reflects on return. */
    fun refresh() = loadList()

    private fun loadList() {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val angle = _state.value.angle
                val resolved = resolveAllEntries(angle)
                allEntries = resolved
                val filters = buildBoardFilters(resolved)
                _state.update { s ->
                    // Keep the active filter only if its board still exists.
                    val sel = s.selectedFilter?.let { prev ->
                        filters.firstOrNull { it.brandWire == prev.brandWire && it.layoutId == prev.layoutId }
                    }
                    s.copy(
                        isLoading = false,
                        listName = list?.name ?: "",
                        totalCount = resolved.size.toLong(),
                        boardFilters = filters,
                        selectedFilter = sel,
                        entries = applyFilter(resolved, sel),
                    )
                }
            }
        }
    }

    /** FEAT-023: a list is the user's explicit selection, so it's shown in FULL
     *  (board-agnostic) — never scoped to the active board. Resolves EVERY entry
     *  the device has board data for, at the active angle where available else a
     *  representative angle. Chunked to stay under SQLite's bound-parameter
     *  limit; lists are small, so this is a one-shot load (no lazy paging — the
     *  full set is needed for the board-filter options anyway). */
    private fun resolveAllEntries(angle: Int): List<Climb_list_entries> {
        val uuidPairs = personalBoardRepo.getClimbListEntryUuids(listId, Int.MAX_VALUE, 0)
        if (uuidPairs.isEmpty()) return emptyList()
        val byUuid = HashMap<String, ClimbWithStats>()
        uuidPairs.map { it.first }.chunked(IN_CHUNK).forEach { chunk ->
            boardRepository.getClimbsByUuids(chunk, angle).forEach { byUuid[it.uuid] = it }
            val missing = chunk.filter { it !in byUuid }
            if (missing.isNotEmpty()) {
                boardRepository.getClimbsByUuidsAnyAngle(missing).forEach { byUuid[it.uuid] = it }
            }
        }
        return uuidPairs.mapNotNull { (uuid, addedAt) ->
            byUuid[uuid]?.let { climb -> Climb_list_entries(addedAt = addedAt, climb = climb) }
        }
    }

    /** Distinct (brand, layout) present in the list, each with its entry count,
     *  in first-seen order. Empty when the list spans a single board — the UI
     *  then shows no filter row. */
    private fun buildBoardFilters(entries: List<Climb_list_entries>): List<BoardFilterOption> {
        val counts = LinkedHashMap<Pair<String, Long>, Int>()
        entries.forEach { e ->
            val key = e.climb.boardBrand to e.climb.layoutId
            counts[key] = (counts[key] ?: 0) + 1
        }
        if (counts.size <= 1) return emptyList()
        return counts.map { (k, c) -> BoardFilterOption(k.first, k.second, c) }
    }

    private fun applyFilter(
        entries: List<Climb_list_entries>, sel: BoardFilterOption?
    ): List<Climb_list_entries> =
        if (sel == null) entries
        else entries.filter { it.climb.boardBrand == sel.brandWire && it.climb.layoutId == sel.layoutId }

    /** Set the active board filter (null = "Alle"). Re-filters the already-loaded
     *  entries in place — no DB round-trip. */
    fun setBoardFilter(sel: BoardFilterOption?) {
        _state.update { it.copy(selectedFilter = sel, entries = applyFilter(allEntries, sel)) }
    }

    fun removeFromList(climbUuid: String) {
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                personalBoardRepo.removeClimbFromList(listId, climbUuid)
            }
            allEntries = allEntries.filterNot { it.climb.uuid == climbUuid }
            val filters = buildBoardFilters(allEntries)
            _state.update { s ->
                // Drop the active filter if its board no longer has entries.
                val sel = s.selectedFilter?.let { prev ->
                    filters.firstOrNull { it.brandWire == prev.brandWire && it.layoutId == prev.layoutId }
                }
                s.copy(
                    entries = applyFilter(allEntries, sel),
                    totalCount = allEntries.size.toLong(),
                    boardFilters = filters,
                    selectedFilter = sel,
                )
            }
        }
    }

    companion object {
        private const val TAG = "BoardListDetailVM"
        // SQLite's bound-parameter cap is 999 on older Android SQLite; chunk the
        // uuid IN-resolution well under it.
        private const val IN_CHUNK = 500
    }
}
