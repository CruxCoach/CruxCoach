package com.cruxcoach.android.ui.community

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.SetterClimbEntry
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
 * ViewModel for the [SetterDetailScreen]. Loads:
 *  - Profile header (display_name, about, picture, lud16) via Kind 0
 *  - All climbs the setter has authored that we have locally
 *
 * Both fetches in parallel; the screen renders progressively as state
 * fills in.
 *
 * Pubkey is passed as a hex string in the navigation argument
 * `setterPubkey`. Invalid / missing pubkey lands on an empty state.
 */
data class SetterDetailState(
    val pubkey: String = "",
    val displayName: String? = null,    // resolved display_name or `npub:<short>` fallback
    val about: String? = null,
    val pictureUrl: String? = null,
    val lightningAddress: String? = null,
    val climbs: List<SetterClimbEntry> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class SetterDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boardRepository: BoardRepository,
    private val nostrProfileManager: NostrProfileManager,
) : ViewModel() {

    private val pubkey: String = savedStateHandle["setterPubkey"] ?: ""

    private val _state = MutableStateFlow(
        SetterDetailState(
            pubkey = pubkey,
            // Synchronous fallback: until profile resolves, show a stub.
            displayName = if (pubkey.isNotBlank()) "npub:${pubkey.take(16)}" else null,
        )
    )
    val state: StateFlow<SetterDetailState> = _state.asStateFlow()

    init {
        if (pubkey.isNotBlank()) {
            loadClimbs()
            loadProfile()
        } else {
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun loadClimbs() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getClimbsByPubkey(pubkey) }
                    .getOrElse { emptyList() }
            }
            _state.update {
                it.copy(
                    climbs = rows,
                    // SetterClimbEntry doesn't carry the resolved
                    // setter_username — that's what we'd display anyway,
                    // but the title bar already uses the npub stub from
                    // init. Kind-0 fetch below replaces it with the
                    // proper display_name.
                    isLoading = false,
                )
            }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
                ?: return@launch
            _state.update { current ->
                current.copy(
                    displayName = profile.displayName?.takeIf { it.isNotBlank() }
                        ?: current.displayName,
                    pictureUrl = profile.pictureUrl?.takeIf { it.isNotBlank() },
                    lightningAddress = profile.lightningAddress?.takeIf { it.isNotBlank() },
                    // about is on the event but not in the cache row;
                    // re-parse if the manager exposes it later.
                )
            }
        }
    }
}
