package com.cruxcoach.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.community.CommunityClimbSubscriber
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.profile.LnurlVerifier
import com.cruxcoach.android.nostr.profile.Nip05Verifier
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
    /** Banner image URL (3:1 aspect, Kind-0 `banner` field). FEAT-010
     *  Tier 1 ships URL-paste only; in-app upload + crop arrive in a
     *  later tranche per spec §7.1 M1. */
    val bannerUrl: String = "",
    /** NIP-05 DNS identifier (`<local>@<domain>`). Verified server-side
     *  via `https://<domain>/.well-known/nostr.json?name=<local>` —
     *  see [Nip05Verifier] in Tier 2. */
    val nip05: String = "",
    /** Free-form website URL (Kind-0 `website` field). */
    val website: String = "",
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
    /** NIP-05 verification status — surfaced as a trailing icon next to
     *  the NIP-05 field. Resets to [Nip05Verifier.State.Idle] on every
     *  edit; updated when the user blurs the field or the editor opens
     *  with a non-blank cached value. */
    val nip05Verification: Nip05Verifier.State = Nip05Verifier.State.Idle,
    /** LNURL probe status for the Lightning-address field. Same lifecycle
     *  as [nip05Verification]. */
    val lnurlVerification: LnurlVerifier.State = LnurlVerifier.State.Idle,
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
    private val nip05Verifier: Nip05Verifier,
    private val lnurlVerifier: LnurlVerifier,
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
                    bannerUrl = profile?.bannerUrl.orEmpty(),
                    nip05 = profile?.nip05.orEmpty(),
                    website = profile?.website.orEmpty(),
                    isLoading = false,
                    canImportFromKilter = kilterUsername != null,
                    kilterUsername = kilterUsername,
                )
            }
            // Eager re-verification: if the cached profile already has
            // an NIP-05 / Lightning address, the user should see the
            // ✓ / ⚠ / ✗ indicator without first having to focus + blur
            // the field. Async — UI just reads the StateFlow.
            if (!profile?.nip05.isNullOrBlank()) verifyNip05Now()
            if (!profile?.lightningAddress.isNullOrBlank()) verifyLightningNow()
        }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, justSaved = false) }
    fun setLightningAddress(value: String) = _state.update {
        // Reset verification on every keystroke — a half-typed address
        // shouldn't carry over the previous result's ✓/⚠. Re-verify on
        // blur via [verifyLightningNow].
        it.copy(
            lightningAddress = value,
            justSaved = false,
            lnurlVerification = LnurlVerifier.State.Idle,
        )
    }
    fun setPictureUrl(value: String) = _state.update { it.copy(pictureUrl = value, justSaved = false) }
    fun setAbout(value: String) = _state.update { it.copy(about = value, justSaved = false) }
    fun setBannerUrl(value: String) = _state.update { it.copy(bannerUrl = value, justSaved = false) }
    fun setNip05(value: String) = _state.update {
        it.copy(
            nip05 = value,
            justSaved = false,
            nip05Verification = Nip05Verifier.State.Idle,
        )
    }
    fun setWebsite(value: String) = _state.update { it.copy(website = value, justSaved = false) }
    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /** Trigger a NIP-05 well-known fetch + match against the user's own
     *  pubkey. Wired to the field's onFocusChanged on blur. No-op when
     *  the field is blank. */
    fun verifyNip05Now() {
        val nip05 = _state.value.nip05.trim()
        if (nip05.isBlank()) {
            _state.update { it.copy(nip05Verification = Nip05Verifier.State.Idle) }
            return
        }
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull() ?: return
        _state.update { it.copy(nip05Verification = Nip05Verifier.State.Verifying) }
        viewModelScope.launch {
            val result = nip05Verifier.verify(nip05, pubkey)
            _state.update { it.copy(nip05Verification = result) }
        }
    }

    /** Best-effort LNURL-pay probe on the Lightning-address field.
     *  Wired to onFocusChanged on blur. */
    fun verifyLightningNow() {
        val lud16 = _state.value.lightningAddress.trim()
        if (lud16.isBlank()) {
            _state.update { it.copy(lnurlVerification = LnurlVerifier.State.Idle) }
            return
        }
        _state.update { it.copy(lnurlVerification = LnurlVerifier.State.Verifying) }
        viewModelScope.launch {
            val result = lnurlVerifier.verify(lud16)
            _state.update { it.copy(lnurlVerification = result) }
        }
    }

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
                banner = s.bannerUrl.trim(),
                nip05 = s.nip05.trim(),
                website = s.website.trim(),
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
