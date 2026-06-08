package com.cruxcoach.android.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import com.cruxcoach.android.util.safeLaunch

private const val TAG = "MapViewModel"

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
    /** [filteredLocations] collapsed to one entry per physical venue — what
     *  the map renders (MapLibre then clusters these). */
    val filteredVenues: List<MapVenue> = emptyList(),
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
    val selectedVenueId: String? = null,
    val noLocationData: Boolean = false,
    /** True while the one-time locations backfill is fetching/importing. */
    val locationsLoading: Boolean = false,
    /** Non-null when the init pipeline threw — UI surfaces this and exits
     *  the loading state so the user is not stuck on an infinite spinner. */
    val errorMessage: String? = null,
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
        viewModelScope.safeLaunch(TAG) {
            try {
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
                    try {
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "backfill-flow collector failed", e)
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
                        userPreferences.mapFilterBrands,
                        userPreferences.mapFilterWellpassOnly,
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
                        brandKeys = values[10] as Set<String>,
                        wellpassOnly = values[11] as Boolean,
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
                                brands = inputs.brandKeys.mapTo(mutableSetOf()) { BoardBrand.fromWire(it) },
                                wellpassOnly = inputs.wellpassOnly,
                            ),
                            canFilterByMyBoard = inputs.canFilterByMyBoard,
                            userBoardLayoutId = inputs.layoutId,
                            userBoardSizeId = inputs.sizeId,
                        )
                    }
                    recomputeFiltered()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MapViewModel.init failed — surfacing error state", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        // Internal flag only (exception class name, never the
                        // raw e.message — which can carry cache paths). The
                        // screen renders a generic localized message; the full
                        // exception is in the log above.
                        errorMessage = e::class.simpleName ?: "error",
                    )
                }
            }
        }
    }

    /** Clear the error banner after the UI has surfaced it (e.g. snackbar dismissed). */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
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
                filteredVenues = groupIntoVenues(filtered),
                stats = MapStats.from(filtered),
            )
        }
    }

    fun selectVenue(id: String?) {
        _state.update { it.copy(selectedVenueId = id) }
    }

    fun toggleShowOriginal() {
        viewModelScope.safeLaunch(TAG) {
            val current = userPreferences.mapFilterShowOriginal.first()
            userPreferences.setMapFilterShowOriginal(!current)
        }
    }

    fun toggleShowHomewalls() {
        viewModelScope.safeLaunch(TAG) {
            val current = userPreferences.mapFilterShowHomewalls.first()
            userPreferences.setMapFilterShowHomewalls(!current)
        }
    }

    fun toggleMatchesMyBoard() {
        if (!_state.value.canFilterByMyBoard) return
        viewModelScope.safeLaunch(TAG) {
            val current = userPreferences.mapFilterMatchesMyBoard.first()
            userPreferences.setMapFilterMatchesMyBoard(!current)
        }
    }

    /** "All" layout chip: show both Original and Homewall families. */
    fun selectAllLayouts() {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.setMapFilterShowOriginal(true)
            userPreferences.setMapFilterShowHomewalls(true)
        }
    }

    // Toggles delegate to atomic UserPreferences helpers (read+modify+write
    // in one dataStore.edit{}), so two rapid taps on a chip can't lose an
    // update via a read-then-set race.
    fun toggleCountry(code: String) {
        viewModelScope.safeLaunch(TAG) { userPreferences.toggleMapFilterCountry(code) }
    }

    fun toggleAccessType(type: AccessType) {
        viewModelScope.safeLaunch(TAG) { userPreferences.toggleMapFilterAccessType(type.name) }
    }

    fun toggleAdjustability(adj: Adjustability) {
        viewModelScope.safeLaunch(TAG) { userPreferences.toggleMapFilterAdjustability(adj.name) }
    }

    fun toggleSizeId(sizeId: Int) {
        viewModelScope.safeLaunch(TAG) { userPreferences.toggleMapFilterSizeId(sizeId) }
    }

    fun toggleBrand(brand: BoardBrand) {
        viewModelScope.safeLaunch(TAG) { userPreferences.toggleMapFilterBrand(brand.wireValue) }
    }

    /** "Other boards" chip: toggle the whole map-only info-layer family set
     *  (Tension, Aurora, …) in one tap, since they share a single chip. */
    fun toggleOtherBrands() {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.toggleMapFilterBrandGroup(
                BoardBrand.INFO_LAYER.map { it.wireValue }.toSet()
            )
        }
    }

    /** "All" brand chip: clear the brand filter (empty = every brand). */
    fun selectAllBrands() {
        viewModelScope.safeLaunch(TAG) { userPreferences.setMapFilterBrands(emptySet()) }
    }

    fun toggleWellpassOnly() {
        viewModelScope.safeLaunch(TAG) {
            val current = userPreferences.mapFilterWellpassOnly.first()
            userPreferences.setMapFilterWellpassOnly(!current)
        }
    }

    fun resetFilters() {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.resetMapFilters()
        }
    }

    fun applyBoardConfigForBrowse(layoutId: Int, productSizeId: Int?) {
        viewModelScope.safeLaunch(TAG) {
            // A MoonBoard gym (layout 2/4/5/6) must switch the active brand to
            // MoonBoard so the browser shows MoonBoard climbs, not an empty
            // Kilter slice at that layout id.
            val variant = MoonBoardVariant.fromLayoutId(layoutId.toLong())
            if (variant != null) {
                userPreferences.setMoonBoardSelection(variant.layoutId.toInt())
            } else {
                userPreferences.setBoardLayoutId(layoutId)
                if (productSizeId != null) {
                    userPreferences.setBoardProductSizeId(productSizeId)
                }
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
        val brandKeys: Set<String>,
        val wellpassOnly: Boolean,
    )
}
