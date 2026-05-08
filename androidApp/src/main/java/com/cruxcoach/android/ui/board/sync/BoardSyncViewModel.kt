package com.cruxcoach.android.ui.board.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BoardModelSelectionState(
    val showDialog: Boolean = false,
    val productSizes: List<BoardSize> = emptyList(),
    val selectedId: Int = BoardConstants.KILTER_DEFAULT_SIZE
)

/**
 * Thin ViewModel that delegates to the application-scoped [BoardSyncManager].
 * The actual sync work continues even when this ViewModel is cleared
 * (e.g. user navigates away from BoardSyncScreen).
 */
@HiltViewModel
class BoardSyncViewModel @Inject constructor(
    private val syncManager: BoardSyncManager,
    private val boardRepository: BoardRepository,
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
            val isDefault = userPreferences.isBoardProductSizeDefault.first()
            if (!isDefault) return@launch

            val sizes = withContext(Dispatchers.IO) {
                boardRepository.getAllProductSizes()
            }
            if (sizes.isEmpty()) return@launch
            val currentId = userPreferences.boardProductSizeId.first()
            _modelState.update {
                it.copy(showDialog = true, productSizes = sizes, selectedId = currentId)
            }
        }
    }

    fun confirmBoardModel(id: Int) {
        _modelState.update { it.copy(showDialog = false) }
        viewModelScope.launch {
            userPreferences.setBoardProductSizeId(id)
        }
    }

    fun dismissModelDialog() {
        _modelState.update { it.copy(showDialog = false) }
    }
}
