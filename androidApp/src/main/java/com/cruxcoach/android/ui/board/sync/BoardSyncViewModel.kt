package com.cruxcoach.android.ui.board.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BoardModelSelectionState(
    val showDialog: Boolean = false,
    val productSizes: List<BoardSize> = emptyList(),
    val selectedId: Int = BoardConstants.KILTER_DEFAULT_SIZE,
    /** Active brand — drives the unified picker's initial category. */
    val boardBrand: String = BoardBrand.KILTER.wireValue,
    /** Active MoonBoard variant when [boardBrand] == "moonboard". */
    val selectedMoonBoardVariant: com.cruxcoach.domain.board.MoonBoardVariant? = null,
)

/**
 * Thin ViewModel that delegates to the application-scoped [BoardSyncManager].
 * The actual sync work continues even when this ViewModel is cleared
 * (e.g. user navigates away from BoardSyncScreen).
 */
@HiltViewModel
class BoardSyncViewModel @Inject constructor(
    private val syncManager: BoardSyncManager,
    private val userPreferences: UserPreferences
) : ViewModel() {

    val state: StateFlow<BoardSyncState> = syncManager.state

    private val _modelState = MutableStateFlow(BoardModelSelectionState())
    val modelState: StateFlow<BoardModelSelectionState> = _modelState.asStateFlow()

    fun checkNetwork() = syncManager.checkNetwork()
    fun dismissWifiDialog() = syncManager.dismissWifiDialog()
    fun dismissNetworkDialog() = syncManager.dismissNetworkDialog()
    fun startApiSync() = syncManager.startApiSync()

    /** Onboarding-only auto-trigger. Fires startApiSync only when no
     *  board data exists yet and no sync is already in flight; safe to
     *  call repeatedly. Without this, a fresh-install user would have
     *  to scroll past the title + intro text inside the BOARD_SETUP
     *  onboarding step to find the "Jetzt laden" button before the
     *  sync would even start — an extra friction step that defeats the
     *  point of having the inline card embedded in the step at all. */
    fun startApiSyncIfNeeded() {
        val s = state.value
        if (s.alreadyImported || s.isSyncing) return
        syncManager.startApiSync()
    }
    fun clearError() = syncManager.clearError()
    fun confirmLocalImport() = syncManager.confirmLocalImport()
    fun dismissLocalImport() = syncManager.dismissLocalImport()

    /**
     * Check if the user needs to select a board model after first sync.
     * Shows the dialog if boardProductSizeId has never been explicitly set
     * (i.e., still using the default).
     *
     * Two skip-gates, in order:
     *   1. Onboarding still in progress — OnboardingScreen embeds
     *      BoardSyncInlineCard during the first-run sync, so this hook
     *      fires WHILE the user is still inside the BOARD_SETUP step.
     *      The onboarding's completeOnboarding() writes the pref at the
     *      end of the flow; popping the dialog here would race that
     *      write and surface a redundant prompt for a question the
     *      onboarding's board-step is already asking. Defer to the
     *      onboarding flow.
     *   2. User has already explicitly chosen a board (post-onboarding
     *      or via Settings) — pref-key exists in DataStore.
     *
     * Either gate true → no dialog. Otherwise show the picker.
     */
    fun checkFirstSyncModelSelection() {
        viewModelScope.launch {
            if (!userPreferences.isOnboardingCompleted()) return@launch
            // FEAT-027: MoonBoard users have already picked their variant
            // in onboarding/Settings — don't re-prompt them with this
            // Kilter-flavoured post-Kilter-sync nudge.
            val brand = userPreferences.boardBrand.first()
            if (BoardBrand.fromWire(brand) != BoardBrand.KILTER) return@launch
            val isDefault = userPreferences.isBoardProductSizeDefault.first()
            if (!isDefault) return@launch

            // Unified picker shows all 16 hardware-known Kilter sizes
            // (both products); the picker's category tier filters them
            // into Original vs Homewall, so we no longer need to pick
            // one product up-front.
            val sizes = BoardConstants.KILTER_KNOWN_SIZES
            val currentId = userPreferences.boardProductSizeId.first()
            _modelState.update {
                it.copy(
                    showDialog = true,
                    productSizes = sizes,
                    selectedId = currentId,
                    boardBrand = brand,
                    selectedMoonBoardVariant = null,
                )
            }
        }
    }

    fun confirmBoardModel(id: Int) {
        // Derive layout from the picked size's product — a Homewall pick
        // must flip the layout too, not just the size, so browse + BLE
        // land on the right Kilter board.
        val sizes = _modelState.value.productSizes
        _modelState.update { it.copy(showDialog = false) }
        viewModelScope.launch {
            val ps = sizes.firstOrNull { it.id.toInt() == id }
            val layout = BoardConstants.layoutIdForProduct(
                ps?.productId?.toInt() ?: BoardConstants.KILTER_PRODUCT_ID
            )
            userPreferences.setBoardLayoutId(layout)
            userPreferences.setBoardProductSizeId(id)
            userPreferences.setBoardBrand(BoardBrand.KILTER.wireValue)
        }
    }

    /** FEAT-027: user picked a MoonBoard variant in the unified post-sync
     *  picker. Persists brand + layout (+ pins the angle via
     *  setMoonBoardSelection); the next browse fetch flips brand
     *  atomically. */
    fun confirmMoonBoardVariant(variant: com.cruxcoach.domain.board.MoonBoardVariant) {
        _modelState.update { it.copy(showDialog = false) }
        viewModelScope.launch {
            userPreferences.setMoonBoardSelection(variant.layoutId.toInt())
        }
    }

    fun dismissModelDialog() {
        _modelState.update { it.copy(showDialog = false) }
    }
}
