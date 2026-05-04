package com.cruxcoach.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.community.CommunityClimbSubscriber
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** Auto-Note global default — drives the editor's per-publish
     *  checkbox vorbelegung. Stored in [UserPreferences]. */
    val autoNoteEnabled: Boolean = false,
    /** Live snapshot of the community-climb subscriber's loop. Surfaced
     *  as a compact diagnostic line so users can tell whether incoming
     *  CruxCoach climbs from other authors are flowing. Null until the
     *  first emission. */
    val subscriberHealth: CommunityClimbSubscriber.SubscriberHealth? = null,
)

@HiltViewModel
class NostrProfileViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val nostrProfileManager: NostrProfileManager,
    private val nostrSigner: NostrSigner,
    private val kilterTokenStore: KilterTokenStore,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val communityClimbSubscriber: CommunityClimbSubscriber,
) : ViewModel() {

    private val _state = MutableStateFlow(NostrProfileEditState())
    val state: StateFlow<NostrProfileEditState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            val initial = userPreferences.autoNoteEnabled.first()
            _state.update { it.copy(autoNoteEnabled = initial) }
        }
        viewModelScope.launch {
            communityClimbSubscriber.health.collect { snapshot ->
                _state.update { it.copy(subscriberHealth = snapshot) }
            }
        }
    }

    fun setAutoNoteEnabled(enabled: Boolean) {
        _state.update { it.copy(autoNoteEnabled = enabled) }
        viewModelScope.launch { userPreferences.setAutoNoteEnabled(enabled) }
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
        // CAS guard against double-tap fanout: tapping "Save" twice in
        // quick succession used to start two `publishProfile` coroutines
        // that each fired a Kind-0 event under the user's pubkey,
        // doubling the relay write and potentially causing replaceable-
        // event ordering surprises. The check-and-set is on the
        // single-threaded main dispatcher, so it's safe without a Mutex.
        if (s.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null) }
            val result = nostrProfileManager.publishProfile(
                displayName = s.displayName.trim(),
                lightningAddress = s.lightningAddress.trim(),
                picture = s.pictureUrl.trim(),
                about = s.about.trim(),
            )
            if (result == null) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = appContext.getString(R.string.nostr_profile_save_failed),
                    )
                }
                return@launch
            }
            // Propagate the new display_name to the user's own community
            // climbs so the browse list updates instantly. Only the
            // setter_username column changes — provenance fields stay.
            // The Nostr publish has already succeeded by this point, so
            // a SQL failure here is non-fatal: the user's profile is
            // public on relays, the browse-list rename will happen on
            // the next sync. But the silent runCatching pre-fix hid
            // disk-full / lock-contention bugs forever — log them.
            val newDisplayName = result.displayName?.takeIf { it.isNotBlank() }
            if (newDisplayName != null) {
                runCatching {
                    boardRepository.updateSetterUsernameForPubkey(
                        pubkey = result.pubkey,
                        displayName = newDisplayName,
                    )
                }.onFailure {
                    android.util.Log.w(
                        "NostrProfileVM",
                        "post-publish bulk rename failed (browse will catch up on next sync)",
                        it,
                    )
                }
            }
            _state.update { it.copy(isSaving = false, justSaved = true) }
        }
    }
}
