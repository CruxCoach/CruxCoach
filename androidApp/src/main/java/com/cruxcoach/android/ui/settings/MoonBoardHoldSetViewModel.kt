package com.cruxcoach.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "MoonBoardHoldSetVM"

/**
 * How many of the active board's problems the current selection leaves
 * climbable, and how many there are in total. Null while not yet counted.
 */
data class MoonBoardHoldSetCounts(val climbable: Long, val total: Long)

/**
 * State of the two-level MoonBoard hold-set picker (FEAT-049 §3.5).
 *
 * Level 1 is the complete setup — one line, preselected, what a bundle buyer
 * has. Level 2 ("Some holds are missing") holds the per-set list and stays
 * collapsed until the user opens it. Level 1 is not a stored flag: it is
 * simply "every set selected", so [isCompleteSetup] follows from
 * [selectedSetIds] alone and cannot drift out of sync with it.
 */
data class MoonBoardHoldSetState(
    /** False until the board prefs have loaded — the section must not flash
     *  a placeholder variant's set list. */
    val loaded: Boolean = false,
    /** The active MoonBoard variant, or null when the active board is not a
     *  MoonBoard or is one with nothing to choose (MoonBoard 2010's single
     *  set — edge case 5). Null hides the picker entirely. */
    val variant: MoonBoardVariant? = null,
    val sets: List<MoonBoardHoldSets.HoldSet> = emptyList(),
    val selectedSetIds: Set<Long> = emptySet(),
    /** False until a catalogue with a populated `hsm` has arrived. The picker
     *  is then shown disabled with a reason rather than silently doing
     *  nothing (§3.7). */
    val catalogueHasHoldSetData: Boolean = false,
    /** Whether the per-set list is open. Starts closed on every entry. */
    val expanded: Boolean = false,
    val counts: MoonBoardHoldSetCounts? = null,
    /** Set when the user tried to untick the last remaining set. Cleared by
     *  the UI once shown. */
    val showMinimumOneWarning: Boolean = false,
) {
    val isCompleteSetup: Boolean
        get() = sets.isNotEmpty() && selectedSetIds.size == sets.size

    /** True when there is a variant with a real choice to offer. */
    val isVisible: Boolean get() = variant != null
}

/**
 * Backs the hold-set picker in board settings.
 *
 * Everything it shows is derived from the persisted selection, so the picker,
 * the browse filter and the climbable count can never disagree: they all read
 * the same preference and run it through the same [HoldSetMask].
 */
@HiltViewModel
class MoonBoardHoldSetViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val boardRepository: BoardRepository,
) : ViewModel() {

    private val expanded = MutableStateFlow(false)
    private val counts = MutableStateFlow<MoonBoardHoldSetCounts?>(null)
    private val gate = MutableStateFlow(false)
    private val minimumOneWarning = MutableStateFlow(false)

    /** The active variant, narrowed to one worth offering a choice for. */
    private val activeVariant: Flow<MoonBoardVariant?> = combine(
        userPreferences.boardBrand,
        userPreferences.boardLayoutId,
    ) { brand, layoutId ->
        MoonBoardVariant.fromBoardSelection(layoutId.toLong(), BoardBrand.fromWire(brand))
            ?.takeIf { MoonBoardHoldSets.isSelectable(it) }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selection: Flow<List<Long>> = activeVariant.flatMapLatest { variant ->
        if (variant == null) flowOf(emptyList()) else userPreferences.moonBoardHoldSets(variant)
    }

    val state: StateFlow<MoonBoardHoldSetState> = combine(
        activeVariant,
        selection,
        expanded,
        combine(gate, counts, minimumOneWarning) { g, c, w -> Triple(g, c, w) },
    ) { variant, selected, isExpanded, derived ->
        val (hasData, currentCounts, warn) = derived
        MoonBoardHoldSetState(
            loaded = true,
            variant = variant,
            sets = variant?.let { MoonBoardHoldSets.setsFor(it) }.orEmpty(),
            selectedSetIds = selected.toSet(),
            catalogueHasHoldSetData = hasData,
            expanded = isExpanded,
            counts = currentCounts,
            showMinimumOneWarning = warn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MoonBoardHoldSetState())

    init {
        viewModelScope.safeLaunch(TAG) {
            combine(activeVariant, selection) { variant, selected -> variant to selected }
                .distinctUntilChanged()
                // collectLatest: toggling several sets quickly must not queue a
                // count per tap — only the last selection's numbers matter.
                .collectLatest { (variant, selected) -> refresh(variant, selected) }
        }
        // A variant switch closes the per-set list again: the sets on screen
        // belong to a different board now, and Level 1 is where every board
        // starts.
        viewModelScope.safeLaunch(TAG) {
            activeVariant.collectLatest {
                expanded.value = false
                minimumOneWarning.value = false
            }
        }
    }

    /** Level 1: the bundle as sold. Stores every set and closes the list. */
    fun selectCompleteSetup() {
        val variant = state.value.variant ?: return
        viewModelScope.safeLaunch(TAG) {
            userPreferences.setMoonBoardHoldSets(variant, MoonBoardHoldSets.setIdsFor(variant))
            expanded.value = false
        }
    }

    /** Level 2: open (or close) the per-set list. */
    fun setExpanded(value: Boolean) {
        expanded.value = value
        if (!value) minimumOneWarning.value = false
    }

    /**
     * Tick or untick one set. Unticking the last remaining one is refused —
     * a board with no hold sets has no climbs, and the read path would expand
     * an empty selection back to "all" anyway, so the UI would appear to
     * accept a change that silently did the opposite.
     */
    fun toggleSet(setId: Long) {
        val current = state.value
        val variant = current.variant ?: return
        val next = if (setId in current.selectedSetIds) {
            current.selectedSetIds - setId
        } else {
            current.selectedSetIds + setId
        }
        if (next.isEmpty()) {
            minimumOneWarning.value = true
            return
        }
        minimumOneWarning.value = false
        viewModelScope.safeLaunch(TAG) {
            userPreferences.setMoonBoardHoldSets(variant, next)
        }
    }

    /** Acknowledge the "keep one selected" warning once it has been shown. */
    fun dismissMinimumOneWarning() {
        minimumOneWarning.value = false
    }

    private suspend fun refresh(variant: MoonBoardVariant?, selected: List<Long>) {
        if (variant == null) {
            gate.value = false
            counts.value = null
            return
        }
        val angle = userPreferences.boardAngle.first()
        withContext(Dispatchers.IO) {
            val hasData = boardRepository.hasMoonBoardHoldSetMask()
            gate.value = hasData
            val mask = if (!hasData) 0L else HoldSetMask.excludedMask(
                layoutSetIds = MoonBoardHoldSets.setIdsFor(variant),
                sizeSetIds = selected,
            )
            // AC 15: the same countFilteredClimbs the browser uses, with the
            // same mask — no second counting path that could drift from the
            // list the user then sees. Unbounded grade range and ALL climb
            // types on purpose: this line answers "how much of this BOARD can
            // I climb", not "how much of my current filter".
            counts.value = MoonBoardHoldSetCounts(
                climbable = countWithMask(variant, angle, mask),
                total = countWithMask(variant, angle, 0L),
            )
        }
    }

    private fun countWithMask(variant: MoonBoardVariant, angle: Int, mask: Long): Long =
        boardRepository.countFilteredClimbs(
            angle = angle,
            layoutId = variant.layoutId.toInt(),
            boardBrand = BoardBrand.MOONBOARD.wireValue,
            minDifficulty = 0.0,
            maxDifficulty = MAX_DIFFICULTY,
            minAscensionists = 0,
            climbType = ClimbTypeFilter.ALL,
            selProductSizeId = 0,
            hsmExcludedMask = mask,
            showUngraded = true,
        )
}

/** Above every stored `difficulty_average`, so the count is grade-unbounded. */
private const val MAX_DIFFICULTY = 100.0

/** Total hold count across the selected sets — the "%1$s holds" line. */
internal fun MoonBoardHoldSetState.selectedHoldCount(): Int {
    val v = variant ?: return 0
    return selectedSetIds.sumOf { MoonBoardHoldSets.holdIdsFor(v, it).size }
}
