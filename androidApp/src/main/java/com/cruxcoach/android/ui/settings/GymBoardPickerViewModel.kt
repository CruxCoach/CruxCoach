package com.cruxcoach.android.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardWall
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
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

/** A selectable board derived from a gym (FEAT-007/031): a Kilter physical
 *  wall, a MoonBoard variant, or an Aurora-family board/variant. [boardBrand]
 *  is the gym's own brand and tells the apply path which selection action to
 *  route through. MoonBoard has no product size, so [productSizeId] is the
 *  [MOONBOARD_NO_SIZE] sentinel there; a single-layout Aurora board likewise
 *  carries layout 0 / size 0 and lets the synced chunk derive the default. */
data class GymWallOption(
    val layoutId: Int,
    val productSizeId: Int,
    val label: String,
    val boardBrand: BoardBrand,
)

/** Placeholder product size for MoonBoard options — MoonBoard variants are
 *  distinct boards, not sizes of one board, so there is nothing to carry. */
const val MOONBOARD_NO_SIZE = 0

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
                // Any synced location enables the picker. Walls give Kilter
                // gyms their rich per-board resolution, but a MoonBoard gym
                // (no walls) resolves via its variant instead — so gate on
                // locations, not walls, or MoonBoard-only data would hide
                // the whole "find my gym" path.
                val enabled = withContext(Dispatchers.IO) { repository.count() > 0L }
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
        val gymBrand = gym.boardBrand
        // MoonBoard gyms carry no per-wall rows — they resolve to one of the
        // distinct MoonBoard variants instead. The gym's own brand decides
        // the resolution, so no brand has to be threaded in from the caller.
        if (gymBrand == BoardBrand.MOONBOARD) {
            _state.update { it.copy(selectedGym = gym, wallOptions = moonBoardOptions(gym)) }
            return
        }
        // Foreign Aurora-family gyms (Tension, Grasshopper, …) are info-only
        // points with no walls — resolve them from the static variant catalog
        // instead (FEAT-031). A multi-layout board (Tension) offers one option
        // per variant; a single-layout board offers exactly one, with layout 0
        // / size 0 so the synced chunk derives the real default at select time.
        if (gymBrand.usesAuroraProtocol && gymBrand != BoardBrand.KILTER) {
            _state.update { it.copy(selectedGym = gym, wallOptions = auroraOptions(gymBrand)) }
            return
        }
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
                            boardBrand = BoardBrand.KILTER,
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

    /** Board options for a foreign Aurora gym (FEAT-031). A multi-layout board
     *  (currently only Tension) yields one option per catalog variant; a
     *  single-layout board (Grasshopper / Decoy / So iLL / Touchstone) has no
     *  catalog entry, so produce exactly one option labelled by the brand —
     *  layout 0 / size 0, letting the apply path's [BoardConstants.auroraVariant]
     *  lookup return null and the synced chunk supply the default. */
    private fun auroraOptions(brand: BoardBrand): List<GymWallOption> {
        val variants = BoardConstants.auroraVariants(brand)
        if (variants.isEmpty()) {
            return listOf(
                GymWallOption(
                    layoutId = 0,
                    productSizeId = 0,
                    label = brand.displayName,
                    boardBrand = brand,
                ),
            )
        }
        return variants.map {
            GymWallOption(
                layoutId = it.layoutId,
                productSizeId = it.defaultSizeId,
                label = it.displayName,
                boardBrand = brand,
            )
        }
    }

    /** Variant options for a MoonBoard gym. A gym whose variant the cron
     *  could resolve from its description offers exactly that board; an
     *  unresolved one offers every supported variant so the climber picks
     *  the board actually in front of them. */
    private fun moonBoardOptions(gym: BoardLocation): List<GymWallOption> {
        val resolved = gym.layoutId?.toLong()?.let { MoonBoardVariant.fromLayoutId(it) }
        val variants = if (resolved != null) listOf(resolved) else MoonBoardVariant.entries
        return variants.map {
            GymWallOption(
                layoutId = it.layoutId.toInt(),
                productSizeId = MOONBOARD_NO_SIZE,
                label = it.displayName,
                boardBrand = BoardBrand.MOONBOARD,
            )
        }
    }

    fun clearGymSelection() {
        _state.update { it.copy(selectedGym = null, wallOptions = emptyList()) }
    }
}
