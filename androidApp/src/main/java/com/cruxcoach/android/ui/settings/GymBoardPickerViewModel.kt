package com.cruxcoach.android.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardWall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "GymBoardPickerVM"

/** A selectable board derived from a gym's physical wall. */
data class GymWallOption(
    val layoutId: Int,
    val productSizeId: Int,
    val label: String,
)

data class GymBoardPickerState(
    /** False when no wall data is synced yet → host hides Path B. */
    val enabled: Boolean = false,
    val query: String = "",
    val results: List<BoardLocation> = emptyList(),
    val selectedGym: BoardLocation? = null,
    val wallOptions: List<GymWallOption> = emptyList(),
    val searching: Boolean = false,
)

/**
 * FEAT-007 Path B — "find your gym". Local, offline search over the
 * synced `kilter_board_location`; tapping a gym resolves its physical
 * walls (`kilter_board_wall`) into selectable board configs, ordered by
 * how common each is across all gyms. Shared by Settings + Onboarding;
 * the host applies the final pick via its own ViewModel.
 */
@HiltViewModel
class GymBoardPickerViewModel @Inject constructor(
    private val repository: BoardLocationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GymBoardPickerState())
    val state: StateFlow<GymBoardPickerState> = _state.asStateFlow()

    private var frequency: Map<Int, Long> = emptyMap()

    init {
        viewModelScope.launch {
            try {
                val enabled = withContext(Dispatchers.IO) { repository.countWalls() > 0L }
                frequency = withContext(Dispatchers.IO) { repository.productSizeFrequency() }
                _state.update { it.copy(enabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "init read failed — gym picker disabled this session", e)
                _state.update { it.copy(enabled = false) }
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, selectedGym = null, wallOptions = emptyList()) }
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val res = withContext(Dispatchers.IO) { repository.searchLocations(trimmed, 60) }
                // Drop the query result if the user kept typing.
                if (_state.value.query.trim() == trimmed) {
                    _state.update { it.copy(results = res, searching = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "searchLocations failed", e)
                if (_state.value.query.trim() == trimmed) {
                    _state.update { it.copy(results = emptyList(), searching = false) }
                }
            }
        }
    }

    fun selectGym(gym: BoardLocation) {
        viewModelScope.launch {
            try {
                val walls = withContext(Dispatchers.IO) { repository.getWallsForGym(gym.id) }
                val opts = walls
                    .filter { it.layoutId != null && it.productSizeId != null }
                    .map { w ->
                        GymWallOption(
                            layoutId = w.layoutId!!,
                            productSizeId = w.productSizeId!!,
                            label = BoardConstants.sizeLabel(
                                w.productSizeId!!.toLong(),
                                w.sizeLabel ?: w.productName ?: "",
                            ),
                        )
                    }
                    // Most common board config first.
                    .sortedByDescending { frequency[it.productSizeId] ?: 0L }
                _state.update { it.copy(selectedGym = gym, wallOptions = opts) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "selectGym(${gym.id}) failed", e)
                _state.update { it.copy(selectedGym = gym, wallOptions = emptyList()) }
            }
        }
    }

    fun clearGymSelection() {
        _state.update { it.copy(selectedGym = null, wallOptions = emptyList()) }
    }
}
