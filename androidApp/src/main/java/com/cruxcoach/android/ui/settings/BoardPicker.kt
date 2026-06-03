package com.cruxcoach.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.AuroraBoardSelector
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reactive state shared by every board picker (FEAT-031). Derived straight from
 * the persisted board prefs + DB sizes, so every picker call site (Settings,
 * Filter, Onboarding, sync card) shows identical selection + options and can
 * never drift apart.
 */
data class BoardPickerState(
    /** False until the real board prefs have loaded. The dialog must not seed
     *  its (unkeyed) remembered selection from the placeholder default, or it
     *  would always open on Kilter regardless of the actual active board. */
    val loaded: Boolean = false,
    val initialBrand: String = BoardBrand.KILTER.wireValue,
    val productSizes: List<BoardSize> = BoardConstants.KILTER_KNOWN_SIZES,
    val selectedKilterSizeId: Int = 0,
    val selectedMoonBoardVariant: MoonBoardVariant? = null,
    /** Active layout_id — seeds the Aurora variant selection (FEAT-031). */
    val selectedAuroraLayoutId: Int = 0,
    /** Wire values of Aurora brands whose catalogue is already loaded — hides
     *  the "we'll download …" hint for boards the user already has (FEAT-031). */
    val loadedAuroraBrands: Set<String> = emptySet(),
)

/**
 * Single source of truth for the board picker. All four pickers use this same
 * VM + the shared selection actions, so their state is identical by
 * construction and selecting in one is reflected everywhere via the prefs.
 */
@HiltViewModel
class BoardPickerViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val boardRepository: BoardRepository,
    private val auroraBoardSelector: AuroraBoardSelector,
) : ViewModel() {

    private val productSizes = MutableStateFlow(BoardConstants.KILTER_KNOWN_SIZES)
    private val loadedBrands = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<BoardPickerState> = combine(
        userPreferences.boardBrand,
        userPreferences.boardLayoutId,
        userPreferences.boardProductSizeId,
        productSizes,
        loadedBrands,
    ) { brand, layoutId, sizeId, sizes, loaded ->
        BoardPickerState(
            loaded = true,
            initialBrand = brand,
            productSizes = sizes,
            selectedKilterSizeId = sizeId,
            selectedMoonBoardVariant = MoonBoardVariant.fromLayoutId(layoutId.toLong()),
            selectedAuroraLayoutId = layoutId,
            loadedAuroraBrands = loaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BoardPickerState())

    init {
        // Prefer the synced DB sizes (both Kilter products); fall back to the
        // bundled known-sizes constant when the catalogue isn't imported yet.
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_PRODUCT_ID.toLong(), BoardBrand.KILTER.wireValue,
                ) + boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_HOMEWALL_PRODUCT_ID.toLong(), BoardBrand.KILTER.wireValue,
                )
            }
            if (sizes.isNotEmpty()) productSizes.value = sizes
            // Which boards are actually loaded — hides the download hint for them.
            loadedBrands.value = withContext(Dispatchers.IO) {
                boardRepository.getClimbCountsByBrand().filterValues { it > 0L }.keys
            }
        }
    }

    fun selectKilter(sizeId: Int) {
        viewModelScope.launch {
            val size = productSizes.value.firstOrNull { it.id.toInt() == sizeId }
            val layout = BoardConstants.layoutIdForProduct(
                size?.productId?.toInt() ?: BoardConstants.KILTER_PRODUCT_ID,
            )
            userPreferences.setBoardBrand(BoardBrand.KILTER.wireValue)
            userPreferences.setBoardLayoutId(layout)
            userPreferences.setBoardProductSizeId(sizeId)
        }
    }

    fun selectMoonBoard(variant: MoonBoardVariant) {
        viewModelScope.launch { userPreferences.setMoonBoardSelection(variant.layoutId.toInt()) }
    }

    fun selectAurora(board: BoardBrand, variant: BoardConstants.AuroraVariant?) {
        viewModelScope.launch { auroraBoardSelector.select(board, variant) }
    }
}

/**
 * The one board picker, used by every call site (FEAT-031). Reads its state
 * from the shared [BoardPickerViewModel] so all pickers are identical and
 * always offer the same boards (Kilter, MoonBoard + the Aurora family).
 *
 * @param onSelected invoked after a board is confirmed — the host uses it to
 *        close the dialog; board content updates reactively via the prefs.
 */
@Composable
internal fun BoardPickerDialog(
    onDismiss: () -> Unit,
    onSelected: () -> Unit = {},
    onFindViaGym: (() -> Unit)? = null,
) {
    val viewModel: BoardPickerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // Wait for the real prefs before composing the dialog — it seeds its
    // selection once (unkeyed remember), so it must not see the placeholder.
    if (!state.loaded) return
    BoardSelectionDialog(
        initialBrand = state.initialBrand,
        productSizes = state.productSizes,
        selectedKilterSizeId = state.selectedKilterSizeId,
        selectedMoonBoardVariant = state.selectedMoonBoardVariant,
        selectedAuroraLayoutId = state.selectedAuroraLayoutId,
        loadedAuroraBrands = state.loadedAuroraBrands,
        frequency = BoardConstants.DEFAULT_SIZE_FREQUENCY,
        showAuroraBoards = true,
        onConfirmKilter = { viewModel.selectKilter(it); onSelected() },
        onConfirmMoonBoard = { viewModel.selectMoonBoard(it); onSelected() },
        onConfirmAurora = { brand, variant -> viewModel.selectAurora(brand, variant); onSelected() },
        onFindViaGym = onFindViaGym,
        onDismiss = onDismiss,
    )
}
