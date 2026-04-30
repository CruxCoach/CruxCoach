package com.cruxcoach.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor for the local user's Nostr Kind 0 profile. Three operations:
 *  - Load: read cache or fetch from relays on first open
 *  - Save: publish a new Kind 0 event, refresh cache, propagate the
 *    new display_name to the user's own community climbs (so the
 *    browser shows the real name immediately, no async lookup needed)
 *  - Import from Kilter: pre-fill `displayName` with the cached
 *    Kilter username from the JWT (`preferred_username` claim)
 */
data class NostrProfileEditState(
    val displayName: String = "",
    val lightningAddress: String = "",
    val pictureUrl: String = "",
    val about: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val justSaved: Boolean = false,
    val canImportFromKilter: Boolean = false,
    /** Cached Kilter username when the user is connected. UI shows a
     *  divergence hint when [displayName] is set and differs — clarifies
     *  that the two identities live on different platforms and don't
     *  override each other. */
    val kilterUsername: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class NostrProfileViewModel @Inject constructor(
    private val nostrProfileManager: NostrProfileManager,
    private val nostrSigner: NostrSigner,
    private val kilterTokenStore: KilterTokenStore,
    private val boardRepository: BoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NostrProfileEditState())
    val state: StateFlow<NostrProfileEditState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
            val kilterUsername = kilterTokenStore.getUsername()?.takeIf { it.isNotBlank() }
            if (pubkey == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        canImportFromKilter = kilterUsername != null,
                        kilterUsername = kilterUsername,
                    )
                }
                return@launch
            }
            val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
            _state.update {
                it.copy(
                    displayName = profile?.displayName.orEmpty(),
                    lightningAddress = profile?.lightningAddress.orEmpty(),
                    pictureUrl = profile?.pictureUrl.orEmpty(),
                    about = "",                     // about is not cached locally; keep blank
                    isLoading = false,
                    canImportFromKilter = kilterUsername != null,
                    kilterUsername = kilterUsername,
                )
            }
        }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, justSaved = false) }
    fun setLightningAddress(value: String) = _state.update { it.copy(lightningAddress = value, justSaved = false) }
    fun setPictureUrl(value: String) = _state.update { it.copy(pictureUrl = value, justSaved = false) }
    fun setAbout(value: String) = _state.update { it.copy(about = value, justSaved = false) }
    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /**
     * Pre-fill `displayName` with the cached Kilter username (no API call —
     * the JWT's `preferred_username` claim was extracted at login time and
     * persisted in [KilterTokenStore]). No-op when no Kilter login. Doesn't
     * overwrite a non-blank `displayName` field — the user has to clear it
     * first if they want to replace.
     */
    fun importFromKilter() {
        val username = kilterTokenStore.getUsername()?.takeIf { it.isNotBlank() } ?: return
        _state.update { current ->
            if (current.displayName.isNotBlank()) current
            else current.copy(displayName = username, justSaved = false)
        }
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null) }
            val result = nostrProfileManager.publishProfile(
                displayName = s.displayName.trim(),
                lightningAddress = s.lightningAddress.trim(),
                picture = s.pictureUrl.trim(),
                about = s.about.trim(),
            )
            if (result == null) {
                _state.update { it.copy(isSaving = false, errorMessage = "Konnte Profil nicht senden. Versuche es nochmal.") }
                return@launch
            }
            // Propagate the new display_name to the user's own community
            // climbs so the browse list updates instantly. Only the
            // setter_username column changes — provenance fields stay.
            val newDisplayName = result.displayName?.takeIf { it.isNotBlank() }
            if (newDisplayName != null) {
                runCatching {
                    boardRepository.updateSetterUsernameForPubkey(
                        pubkey = result.pubkey,
                        displayName = newDisplayName,
                    )
                }
            }
            _state.update { it.copy(isSaving = false, justSaved = true) }
        }
    }
}
