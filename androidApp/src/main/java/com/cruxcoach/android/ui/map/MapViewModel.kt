package com.cruxcoach.android.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val INITIAL_LAT = 20.0
private const val INITIAL_LNG = 0.0
private const val INITIAL_ZOOM = 1.0

/**
 * Two parallel datasets live in [MapState]:
 *  - [unfilteredLocations]: the full set, kept as the source of truth for
 *    chip-list aggregation (e.g. enumerating which countries exist).
 *  - [filteredLocations]: what's actually rendered on the map and fed into
 *    [stats]. Recomputed whenever filters or the underlying dataset change.
 *
 * Stats are an embedded snapshot rather than a separate flow so the
 * Stats tab and the live filter-count footer don't recompute independently.
 */
data class MapState(
    val unfilteredLocations: List<BoardLocation> = emptyList(),
    val filteredLocations: List<BoardLocation> = emptyList(),
    val stats: MapStats = MapStats.Empty,
    val unfilteredStats: MapStats = MapStats.Empty,
    val isLoading: Boolean = true,
    val initialLat: Double = INITIAL_LAT,
    val initialLng: Double = INITIAL_LNG,
    val initialZoom: Double = INITIAL_ZOOM,
    val filters: MapFilters = MapFilters(),
    /** False when user has no board configured — disables matchesMyBoard. */
    val canFilterByMyBoard: Boolean = false,
    val userBoardLayoutId: Int? = null,
    val userBoardSizeId: Int? = null,
    val selectedLocationId: String? = null,
    val noLocationData: Boolean = false,
    /** True while the one-time locations backfill is fetching/importing. */
    val locationsLoading: Boolean = false,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: BoardLocationRepository,
    private val userPreferences: UserPreferences,
    private val boardSyncManager: BoardSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { repository.getAll() }
            _state.update {
                it.copy(
                    unfilteredLocations = all,
                    unfilteredStats = MapStats.from(all),
                    isLoading = false,
                    noLocationData = all.isEmpty(),
                )
            }
            recomputeFiltered()

            launch {
                boardSyncManager.locationsBackfilling.collect { running ->
                    _state.update { it.copy(locationsLoading = running) }
                    if (!running) {
                        val refreshed = withContext(Dispatchers.IO) { repository.getAll() }
                        _state.update {
                            it.copy(
                                unfilteredLocations = refreshed,
                                unfilteredStats = MapStats.from(refreshed),
                                noLocationData = refreshed.isEmpty(),
                            )
                        }
                        recomputeFiltered()
                    }
                }
            }

            combine(
                listOf<Flow<Any?>>(
                    userPreferences.mapFilterShowOriginal,
                    userPreferences.mapFilterShowHomewalls,
                    userPreferences.mapFilterMatchesMyBoard,
                    userPreferences.mapFilterCountries,
                    userPreferences.mapFilterAccessTypes,
                    userPreferences.mapFilterAdjustabilities,
                    userPreferences.mapFilterSizeIds,
                    userPreferences.boardLayoutId,
                    userPreferences.boardProductSizeId,
                    userPreferences.isBoardProductSizeDefault,
                )
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                FilterInputs(
                    showOriginal = values[0] as Boolean,
                    showHomewalls = values[1] as Boolean,
                    matchesMyBoard = values[2] as Boolean,
                    countries = values[3] as Set<String>,
                    accessTypeKeys = values[4] as Set<String>,
                    adjustabilityKeys = values[5] as Set<String>,
                    sizeIds = values[6] as Set<Int>,
                    layoutId = values[7] as Int,
                    sizeId = values[8] as Int,
                    canFilterByMyBoard = !(values[9] as Boolean),
                )
            }.collect { inputs ->
                _state.update {
                    it.copy(
                        filters = MapFilters(
                            showOriginal = inputs.showOriginal,
                            showHomewalls = inputs.showHomewalls,
                            matchesMyBoard = inputs.matchesMyBoard,
                            countries = inputs.countries,
                            accessTypes = inputs.accessTypeKeys.mapNotNullTo(mutableSetOf()) { runCatching { AccessType.valueOf(it) }.getOrNull() },
                            adjustabilities = inputs.adjustabilityKeys.mapNotNullTo(mutableSetOf()) { runCatching { Adjustability.valueOf(it) }.getOrNull() },
                            sizeIds = inputs.sizeIds,
                        ),
                        canFilterByMyBoard = inputs.canFilterByMyBoard,
                        userBoardLayoutId = inputs.layoutId,
                        userBoardSizeId = inputs.sizeId,
                    )
                }
                recomputeFiltered()
            }
        }
    }

    private fun recomputeFiltered() {
        val s = _state.value
        val effectiveFilters = if (!s.canFilterByMyBoard) {
            s.filters.copy(matchesMyBoard = false)
        } else s.filters
        val filtered = effectiveFilters.apply(
            locations = s.unfilteredLocations,
            userBoardLayoutId = s.userBoardLayoutId,
            userBoardSizeId = s.userBoardSizeId,
        )
        _state.update {
            it.copy(
                filteredLocations = filtered,
                stats = MapStats.from(filtered),
            )
        }
    }

    fun selectLocation(id: String?) {
        _state.update { it.copy(selectedLocationId = id) }
    }

    fun toggleShowOriginal() {
        viewModelScope.launch {
            val current = userPreferences.mapFilterShowOriginal.first()
            userPreferences.setMapFilterShowOriginal(!current)
        }
    }

    fun toggleShowHomewalls() {
        viewModelScope.launch {
            val current = userPreferences.mapFilterShowHomewalls.first()
            userPreferences.setMapFilterShowHomewalls(!current)
        }
    }

    fun toggleMatchesMyBoard() {
        if (!_state.value.canFilterByMyBoard) return
        viewModelScope.launch {
            val current = userPreferences.mapFilterMatchesMyBoard.first()
            userPreferences.setMapFilterMatchesMyBoard(!current)
        }
    }

    /** "All" layout chip: show both Original and Homewall families. */
    fun selectAllLayouts() {
        viewModelScope.launch {
            userPreferences.setMapFilterShowOriginal(true)
            userPreferences.setMapFilterShowHomewalls(true)
        }
    }

    fun toggleCountry(code: String) {
        viewModelScope.launch {
            val current = userPreferences.mapFilterCountries.first()
            val next = if (code in current) current - code else current + code
            userPreferences.setMapFilterCountries(next)
        }
    }

    fun toggleAccessType(type: AccessType) {
        viewModelScope.launch {
            val current = userPreferences.mapFilterAccessTypes.first()
            val key = type.name
            val next = if (key in current) current - key else current + key
            userPreferences.setMapFilterAccessTypes(next)
        }
    }

    fun toggleAdjustability(adj: Adjustability) {
        viewModelScope.launch {
            val current = userPreferences.mapFilterAdjustabilities.first()
            val key = adj.name
            val next = if (key in current) current - key else current + key
            userPreferences.setMapFilterAdjustabilities(next)
        }
    }

    fun toggleSizeId(sizeId: Int) {
        viewModelScope.launch {
            val current = userPreferences.mapFilterSizeIds.first()
            val next = if (sizeId in current) current - sizeId else current + sizeId
            userPreferences.setMapFilterSizeIds(next)
        }
    }

    fun resetFilters() {
        viewModelScope.launch {
            userPreferences.resetMapFilters()
        }
    }

    fun applyBoardConfigForBrowse(layoutId: Int, productSizeId: Int?) {
        viewModelScope.launch {
            userPreferences.setBoardLayoutId(layoutId)
            if (productSizeId != null) {
                userPreferences.setBoardProductSizeId(productSizeId)
            }
        }
    }

    private data class FilterInputs(
        val showOriginal: Boolean,
        val showHomewalls: Boolean,
        val matchesMyBoard: Boolean,
        val countries: Set<String>,
        val accessTypeKeys: Set<String>,
        val adjustabilityKeys: Set<String>,
        val sizeIds: Set<Int>,
        val layoutId: Int,
        val sizeId: Int,
        val canFilterByMyBoard: Boolean,
    )
}
