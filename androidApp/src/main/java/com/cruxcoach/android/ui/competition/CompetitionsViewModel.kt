package com.cruxcoach.android.ui.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.competition.CompetitionDiscovery
import com.cruxcoach.android.competition.CompetitionShareLink
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
 * The competition list: what is on, and a way in from a link.
 *
 * `loaded` is deliberately separate from "the list is empty". A relay that
 * answered with nothing and a relay that never answered look identical in a
 * bare list, and only one of them means "there are no competitions".
 */
@HiltViewModel
class CompetitionsViewModel @Inject constructor(
    private val discovery: CompetitionDiscovery,
) : ViewModel() {

    data class State(
        val listings: List<CompetitionDiscovery.Listing> = emptyList(),
        val query: String = "",
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val linkInput: String = "",
        val linkError: Boolean = false,
    ) {
        val visible: List<CompetitionDiscovery.Listing>
            get() = listings
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val found = withContext(Dispatchers.IO) { discovery.search(now) }
            _state.update {
                it.copy(
                    listings = discovery.filter(found, it.query),
                    loading = false,
                    loaded = true,
                )
            }
            allListings = found
        }
    }

    private var allListings: List<CompetitionDiscovery.Listing> = emptyList()

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, listings = discovery.filter(allListings, query)) }
    }

    fun onLinkChange(value: String) {
        _state.update { it.copy(linkInput = value, linkError = false) }
    }

    /**
     * @return the parsed reference, or null. The caller navigates; this only
     *   decides whether the text points at a competition, so a bad paste never
     *   opens a half-loaded screen.
     */
    fun openLink(): CompetitionShareLink.Ref? {
        val ref = CompetitionShareLink.parse(_state.value.linkInput)
        if (ref == null) {
            _state.update { it.copy(linkError = true) }
            return null
        }
        _state.update { it.copy(linkInput = "", linkError = false) }
        return ref
    }
}
