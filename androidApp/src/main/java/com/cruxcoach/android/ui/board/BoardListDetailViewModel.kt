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
        loadList()
    }

    private fun loadList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val list = personalBoardRepo.getClimbListById(listId)
                val angle = _state.value.angle
                val entries = loadEntries(listId, angle, PAGE_SIZE, 0)
                val count = personalBoardRepo.countClimbListEntries(listId)
                _state.update { it.copy(
                    isLoading = false,
                    listName = list?.name ?: "",
                    entries = entries,
                    totalCount = count,
                    canLoadMore = entries.size.toLong() < count
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
                val newEntries = loadEntries(listId, s.angle, PAGE_SIZE, s.entries.size)
                val combined = s.entries + newEntries
                _state.update { it.copy(
                    isLoadingMore = false,
                    entries = combined,
                    canLoadMore = combined.size.toLong() < s.totalCount
                ) }
            }
        }
    }

    /** Two-phase: get UUIDs from SecureDB, then climb details from BoardDB. */
    private fun loadEntries(listId: Long, angle: Int, limit: Int, offset: Int): List<Climb_list_entries> {
        val uuidPairs = personalBoardRepo.getClimbListEntryUuids(listId, limit, offset)
        if (uuidPairs.isEmpty()) return emptyList()
        val uuids = uuidPairs.map { it.first }
        val addedAtMap = uuidPairs.associate { it.first to it.second }
        val climbs = boardRepository.getClimbsByUuids(uuids, angle)
        // Recover entries with no row at the requested angle — notably
        // MoonBoard Masters problems set only at 25° — via an angle-agnostic
        // fallback, so cross-board / cross-angle lists don't silently drop
        // climbs. Kilter entries keep their angle-correct row from above.
        val resolved = climbs.associateBy { it.uuid }
        val missing = uuids.filter { it !in resolved }
        val climbMap = if (missing.isEmpty()) resolved
            else resolved + boardRepository.getClimbsByUuidsAnyAngle(missing).associateBy { it.uuid }
        return uuidPairs.mapNotNull { (uuid, addedAt) ->
            climbMap[uuid]?.let { climb -> Climb_list_entries(addedAt = addedAt, climb = climb) }
        }
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
