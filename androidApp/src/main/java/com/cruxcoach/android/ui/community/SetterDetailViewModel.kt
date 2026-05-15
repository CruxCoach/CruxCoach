package com.cruxcoach.android.ui.community

import android.util.Log
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
    /** Non-null after the climbs DB read fails — distinguishes "this
     *  setter has no climbs yet" from "the read threw". */
    val errorMessage: String? = null,
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

    fun retry() {
        if (pubkey.isBlank()) return
        loadClimbs()
        loadProfile()
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private fun loadClimbs() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getClimbsByPubkey(pubkey) }
            }
            result.fold(
                onSuccess = { rows ->
                    _state.update {
                        it.copy(
                            climbs = rows,
                            // SetterClimbEntry doesn't carry the resolved
                            // setter_username — the title bar uses the npub
                            // stub from init; loadProfile replaces it.
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "getClimbsByPubkey($pubkey) failed", e)
                    _state.update {
                        it.copy(
                            climbs = emptyList(),
                            isLoading = false,
                            errorMessage = e.message ?: e::class.simpleName ?: "load failed",
                        )
                    }
                },
            )
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // Two-phase: paint cached values *immediately* (sync DB
            // read) so the user sees the display name + picture
            // without an npub-stub flicker, then re-fetch from relays
            // in the background and update the screen if the live
            // version differs. Pre-fix this only ran refreshProfile,
            // which forced a 1-3 s relay round-trip before any name
            // appeared — perceived as "nothing loaded yet" even
            // though we had cached metadata locally.
            val cached = runCatching { nostrProfileManager.getProfileFromCache(pubkey) }
                .onFailure { Log.w(TAG, "getProfileFromCache($pubkey) failed", it) }
                .getOrNull()
            if (cached != null) {
                _state.update { current -> current.applyProfile(cached) }
            }

            // Force a relay re-fetch on every screen-open (not just on
            // TTL miss). Opening someone's setter page is an explicit
            // "show me their latest" signal — the TTL only optimises
            // the *background* lookup paths (climb-detail setter chip,
            // live-sub resolve). Stale-tolerance is baked into
            // refreshProfile: a relay outage just falls back to the
            // existing cache instead of blanking the screen.
            val refreshed = runCatching { nostrProfileManager.refreshProfile(pubkey) }
                .onFailure { Log.w(TAG, "refreshProfile($pubkey) failed", it) }
                .getOrNull()
                ?: return@launch
            _state.update { current -> current.applyProfile(refreshed) }
        }
    }

    private fun SetterDetailState.applyProfile(
        profile: com.cruxcoach.android.payment.model.NostrProfileData,
    ) = copy(
        displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: displayName,
        pictureUrl = profile.pictureUrl?.takeIf { it.isNotBlank() },
        lightningAddress = profile.lightningAddress?.takeIf { it.isNotBlank() },
    )

    private companion object {
        const val TAG = "SetterDetailVM"
    }
}
