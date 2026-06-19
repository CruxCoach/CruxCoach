package com.cruxcoach.android.ui.community

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.SetterStat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists every CruxCoach community setter known to the local catalog,
 * sorted by climb count desc. Reads `setter_username` straight from the
 * DB — Plan C guarantees that's already the resolved display_name (or
 * `npub:<short>` fallback when no profile exists).
 */
data class SettersListState(
    val setters: List<SetterStat> = emptyList(),
    val isLoading: Boolean = true,
    /** Non-null after a DB read fails — distinguishes "no setters yet"
     *  from "the read threw and we're showing nothing because of a bug".
     *  UI surfaces this as an inline error card with a retry button. */
    val errorMessage: String? = null,
)

@HiltViewModel
class SettersListViewModel @Inject constructor(
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(SettersListState())
    val state: StateFlow<SettersListState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    /** Re-count for the (possibly changed) active board on resume. */
    fun refresh() = load()

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                // Board-scope the counts to the active board so the list count
                // matches the (board-filtered) setter detail. Same params the
                // SetterDetailScreen uses.
                val brand = userPreferences.boardBrand.first()
                val layoutId = userPreferences.boardLayoutId.first().toInt()
                val sizeId = userPreferences.boardProductSizeId.first().toInt()
                runCatching { boardRepository.getCommunitySetterStats(brand, layoutId, sizeId) }
            }
            result.fold(
                onSuccess = { rows ->
                    _state.update { it.copy(setters = rows, isLoading = false, errorMessage = null) }
                },
                onFailure = { e ->
                    Log.w(TAG, "getCommunitySetterStats failed", e)
                    _state.update {
                        it.copy(
                            setters = emptyList(),
                            isLoading = false,
                            errorMessage = e.message ?: e::class.simpleName ?: "load failed",
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val TAG = "SettersListVM"
    }
}
