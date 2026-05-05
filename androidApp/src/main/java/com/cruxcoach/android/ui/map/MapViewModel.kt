package com.cruxcoach.android.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** First-load: world-overview viewport so users see global coverage. */
private const val INITIAL_LAT = 20.0
private const val INITIAL_LNG = 0.0
private const val INITIAL_ZOOM = 1.0

data class MapState(
    val locations: List<BoardLocation> = emptyList(),
    val isLoading: Boolean = true,
    val initialLat: Double = INITIAL_LAT,
    val initialLng: Double = INITIAL_LNG,
    val initialZoom: Double = INITIAL_ZOOM,
    val publicOnly: Boolean = false,
    val matchesMyBoard: Boolean = false,
    val canFilterByMyBoard: Boolean = false,
    val selectedLocationId: Long? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: BoardLocationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    init {
        loadAllLocations()
    }

    private fun loadAllLocations() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { repository.getAll() }
            _state.update { it.copy(locations = all, isLoading = false) }
        }
    }

    fun selectLocation(id: Long?) {
        _state.update { it.copy(selectedLocationId = id) }
    }

    fun togglePublicOnly() {
        _state.update { it.copy(publicOnly = !it.publicOnly) }
    }

    fun toggleMatchesMyBoard() {
        if (!_state.value.canFilterByMyBoard) return
        _state.update { it.copy(matchesMyBoard = !it.matchesMyBoard) }
    }
}
