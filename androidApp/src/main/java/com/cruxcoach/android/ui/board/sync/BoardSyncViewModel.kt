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
    fun dismissUpToDateDialog() = syncManager.dismissUpToDateDialog()
    fun startApiSync() = syncManager.startApiSync()
    fun forceSync() = syncManager.forceSync()
    fun clearError() = syncManager.clearError()

    /**
     * Check if the user needs to select a board model after first sync.
     * Shows the dialog if boardProductSizeId has never been explicitly set
     * (i.e., still using the default).
     */
    fun checkFirstSyncModelSelection() {
        viewModelScope.launch {
            // Only show selection dialog if user has never explicitly chosen a board model
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
