package com.cruxcoach.android.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.SetterStat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class SettersListViewModel @Inject constructor(
    private val boardRepository: BoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettersListState())
    val state: StateFlow<SettersListState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getCommunitySetterStats() }
                    .getOrElse { emptyList() }
            }
            _state.update { it.copy(setters = rows, isLoading = false) }
        }
    }
}
