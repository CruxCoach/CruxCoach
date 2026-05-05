package com.cruxcoach.android.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** First-load: world-overview viewport so users see global coverage. */
private const val INITIAL_LAT = 20.0
private const val INITIAL_LNG = 0.0
private const val INITIAL_ZOOM = 1.0

data class MapState(
    /** Filtered list rendered as markers — already accounts for the filter chips. */
    val locations: List<BoardLocation> = emptyList(),
    val isLoading: Boolean = true,
    val initialLat: Double = INITIAL_LAT,
    val initialLng: Double = INITIAL_LNG,
    val initialZoom: Double = INITIAL_ZOOM,
    val publicOnly: Boolean = false,
    val matchesMyBoard: Boolean = false,
    /** False when user has no board configured — disables the chip + tooltip. */
    val canFilterByMyBoard: Boolean = false,
    val selectedLocationId: Long? = null,
    /** True when the underlying table is empty (older client without
     *  locations chunk in manifest, or sync failed). Surfaces as a snackbar. */
    val noLocationData: Boolean = false,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: BoardLocationRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    /** Cached unfiltered list — filter combinators reduce this in-memory. */
    private var allLocations: List<BoardLocation> = emptyList()

    init {
        viewModelScope.launch {
            allLocations = withContext(Dispatchers.IO) { repository.getAll() }
            if (allLocations.isEmpty()) {
                _state.update { it.copy(noLocationData = true, isLoading = false) }
            }
            // Wire reactive filter pipeline. Each pref change recomputes the
            // visible list. Layout/size flow surfaces "no board configured"
            // as canFilterByMyBoard=false.
            combine(
                userPreferences.mapFilterPublicOnly,
                userPreferences.mapFilterMatchesMyBoard,
                userPreferences.boardLayoutId,
                userPreferences.boardProductSizeId,
                userPreferences.isBoardProductSizeDefault,
            ) { publicOnly, matchMine, layoutId, sizeId, isDefault ->
                FilterInputs(publicOnly, matchMine, layoutId, sizeId, !isDefault)
            }.collect { inputs ->
                applyFilters(inputs)
            }
        }
    }

    private fun applyFilters(inputs: FilterInputs) {
        val effectiveMatchMine = inputs.matchesMyBoard && inputs.canFilterByMyBoard
        val filtered = allLocations.asSequence()
            .filter { !inputs.publicOnly || it.accessType == AccessType.PUBLIC }
            .filter { loc ->
                if (!effectiveMatchMine) return@filter true
                if (loc.layoutId != inputs.layoutId) return@filter false
                // Layout matches; size match required when both sides know it.
                loc.productSizeId == null || loc.productSizeId == inputs.sizeId
            }
            .toList()

        _state.update {
            it.copy(
                locations = filtered,
                isLoading = false,
                publicOnly = inputs.publicOnly,
                matchesMyBoard = inputs.matchesMyBoard,
                canFilterByMyBoard = inputs.canFilterByMyBoard,
            )
        }
    }

    fun selectLocation(id: Long?) {
        _state.update { it.copy(selectedLocationId = id) }
    }

    fun togglePublicOnly() {
        viewModelScope.launch {
            val current = userPreferences.mapFilterPublicOnly.first()
            userPreferences.setMapFilterPublicOnly(!current)
        }
    }

    fun toggleMatchesMyBoard() {
        if (!_state.value.canFilterByMyBoard) return
        viewModelScope.launch {
            val current = userPreferences.mapFilterMatchesMyBoard.first()
            userPreferences.setMapFilterMatchesMyBoard(!current)
        }
    }

    fun selectedLocation(): BoardLocation? {
        val id = _state.value.selectedLocationId ?: return null
        return _state.value.locations.firstOrNull { it.id == id }
            ?: allLocations.firstOrNull { it.id == id }
    }

    /**
     * Apply this board's layout/size as the user's active board filter so a
     * follow-up navigation to the Board Browser lands on a pre-filtered list.
     * Best-effort and async — if the navigation fires before the prefs flow
     * propagates, the browser refreshes within a frame as the new value
     * arrives via DataStore's reactive read.
     */
    fun applyBoardConfigForBrowse(layoutId: Int, productSizeId: Int?) {
        viewModelScope.launch {
            userPreferences.setBoardLayoutId(layoutId)
            if (productSizeId != null) {
                userPreferences.setBoardProductSizeId(productSizeId)
            }
        }
    }

    private data class FilterInputs(
        val publicOnly: Boolean,
        val matchesMyBoard: Boolean,
        val layoutId: Int,
        val sizeId: Int,
        val canFilterByMyBoard: Boolean,
    )
}
