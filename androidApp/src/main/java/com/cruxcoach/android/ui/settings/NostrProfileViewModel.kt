package com.cruxcoach.android.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.community.CommunityClimbSubscriber
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.profile.ImageProcessor
import com.cruxcoach.android.nostr.profile.LnurlVerifier
import com.cruxcoach.android.nostr.profile.Nip05Verifier
import com.cruxcoach.android.nostr.profile.ProfileImageUploader
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
    /** True while a banner image is being compressed + uploaded to Blossom.
     *  UI shows a progress overlay on the banner edit area. */
    val bannerUploadInFlight: Boolean = false,
    /** True while a profile picture is being compressed + uploaded. */
    val pictureUploadInFlight: Boolean = false,
    /** True while a relay refresh is running in the background. The
     *  fields are already populated from the local cache when this is
     *  true — UI uses it for a subtle "syncing…" indicator, not a
     *  full-screen blocker. */
    val isRefreshing: Boolean = false,
    /** Set on any field setter call. Guards Phase-2 cache→relay refresh
     *  from clobbering the user's in-flight edits. Cleared by `load()`
     *  on screen entry and by `save()` after a successful publish. */
    val hasUserEdited: Boolean = false,
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
    private val imageProcessor: ImageProcessor,
    private val profileImageUploader: ProfileImageUploader,
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

            // Phase 1: render from local cache instantly. The previous
            // code path went through `getProfile`, which always blocks
            // on the relay round-trip when the cache is empty AND
            // bypasses the cache hit when there's any in-process event-
            // dedup hit — the screen used to show a full-screen spinner
            // for up to 10s on every open. Now we paint cached values
            // immediately and only touch relays in the background.
            val cached = runCatching { nostrProfileManager.getProfileFromCache(pubkey) }
                .getOrNull()
            if (cached != null) {
                _state.update {
                    it.copy(
                        displayName = cached.displayName.orEmpty(),
                        lightningAddress = cached.lightningAddress.orEmpty(),
                        pictureUrl = cached.pictureUrl.orEmpty(),
                        about = "",
                        bannerUrl = cached.bannerUrl.orEmpty(),
                        nip05 = cached.nip05.orEmpty(),
                        website = cached.website.orEmpty(),
                        isLoading = false,
                        isRefreshing = true,
                        hasUserEdited = false,
                        canImportFromKilter = kilterUsername != null,
                        kilterUsername = kilterUsername,
                    )
                }
                if (!cached.nip05.isNullOrBlank()) verifyNip05Now()
                if (!cached.lightningAddress.isNullOrBlank()) verifyLightningNow()
            } else {
                // No cache yet — drop the full-screen spinner anyway
                // so the user can start typing while the relay fetch
                // runs in the background. The TopAppBar's small
                // refresh indicator (driven by isRefreshing) signals
                // the in-flight fetch.
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = true,
                        hasUserEdited = false,
                        canImportFromKilter = kilterUsername != null,
                        kilterUsername = kilterUsername,
                    )
                }
            }

            // Phase 2: background relay fetch. This either populates an
            // empty cache (first-ever open) or refreshes a stale one.
            // We skip the field-overwrite if the user already started
            // typing — `setDisplayName`/etc. flip `hasUserEdited`.
            val fresh = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
            val freshChanged = fresh != null && (
                fresh.displayName.orEmpty() != cached?.displayName.orEmpty() ||
                fresh.lightningAddress.orEmpty() != cached?.lightningAddress.orEmpty() ||
                fresh.pictureUrl.orEmpty() != cached?.pictureUrl.orEmpty() ||
                fresh.bannerUrl.orEmpty() != cached?.bannerUrl.orEmpty() ||
                fresh.nip05.orEmpty() != cached?.nip05.orEmpty() ||
                fresh.website.orEmpty() != cached?.website.orEmpty()
            )
            _state.update { current ->
                if (fresh == null) {
                    // Relay fetch failed — keep whatever cache we already
                    // showed; just drop the syncing indicator.
                    current.copy(isLoading = false, isRefreshing = false)
                } else if (current.hasUserEdited) {
                    // Don't clobber typed text. The fresh values still
                    // landed in the cache via parseAndCacheProfile, so
                    // a future load() picks them up.
                    current.copy(isLoading = false, isRefreshing = false)
                } else if (cached == null || freshChanged) {
                    current.copy(
                        displayName = fresh.displayName.orEmpty(),
                        lightningAddress = fresh.lightningAddress.orEmpty(),
                        pictureUrl = fresh.pictureUrl.orEmpty(),
                        bannerUrl = fresh.bannerUrl.orEmpty(),
                        nip05 = fresh.nip05.orEmpty(),
                        website = fresh.website.orEmpty(),
                        isLoading = false,
                        isRefreshing = false,
                    )
                } else {
                    current.copy(isLoading = false, isRefreshing = false)
                }
            }
            // Re-verify if the relay-fetched values differ — the cached
            // verifyNip05Now / verifyLightningNow above were against
            // the (potentially stale) cache.
            if (fresh != null && (cached == null || freshChanged) && !_state.value.hasUserEdited) {
                if (!fresh.nip05.isNullOrBlank()) verifyNip05Now()
                if (!fresh.lightningAddress.isNullOrBlank()) verifyLightningNow()
            }
        }
    }

    fun setDisplayName(value: String) = _state.update {
        it.copy(displayName = value, justSaved = false, hasUserEdited = true)
    }
    fun setLightningAddress(value: String) = _state.update {
        // Reset verification on every keystroke — a half-typed address
        // shouldn't carry over the previous result's ✓/⚠. Re-verify on
        // blur via [verifyLightningNow].
        it.copy(
            lightningAddress = value,
            justSaved = false,
            hasUserEdited = true,
            lnurlVerification = LnurlVerifier.State.Idle,
        )
    }
    fun setPictureUrl(value: String) = _state.update {
        it.copy(pictureUrl = value, justSaved = false, hasUserEdited = true)
    }
    fun setAbout(value: String) = _state.update {
        it.copy(about = value, justSaved = false, hasUserEdited = true)
    }
    fun setBannerUrl(value: String) = _state.update {
        it.copy(bannerUrl = value, justSaved = false, hasUserEdited = true)
    }
    fun setNip05(value: String) = _state.update {
        it.copy(
            nip05 = value,
            justSaved = false,
            hasUserEdited = true,
            nip05Verification = Nip05Verifier.State.Idle,
        )
    }
    fun setWebsite(value: String) = _state.update {
        it.copy(website = value, justSaved = false, hasUserEdited = true)
    }
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

    /** Compress the picked image at [uri] (1024 px long-edge JPEG) and
     *  upload to Blossom. On success the URL replaces [pictureUrl]. On
     *  failure an [errorMessage] surfaces in the snackbar. */
    fun uploadPicture(uri: Uri) = uploadImage(
        uri = uri,
        maxDimension = ImageProcessor.MAX_DIMENSION_PICTURE,
        markInFlight = { _state.update { it.copy(pictureUploadInFlight = true, errorMessage = null) } },
        clearInFlight = { _state.update { it.copy(pictureUploadInFlight = false) } },
        onSuccess = { url ->
            _state.update { it.copy(pictureUrl = url, justSaved = false) }
        },
    )

    /** Same as [uploadPicture] but for the 3:1-ish banner image
     *  (1920 px long-edge). */
    fun uploadBanner(uri: Uri) = uploadImage(
        uri = uri,
        maxDimension = ImageProcessor.MAX_DIMENSION_BANNER,
        markInFlight = { _state.update { it.copy(bannerUploadInFlight = true, errorMessage = null) } },
        clearInFlight = { _state.update { it.copy(bannerUploadInFlight = false) } },
        onSuccess = { url ->
            _state.update { it.copy(bannerUrl = url, justSaved = false) }
        },
    )

    private fun uploadImage(
        uri: Uri,
        maxDimension: Int,
        markInFlight: () -> Unit,
        clearInFlight: () -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        markInFlight()
        viewModelScope.launch {
            try {
                val bytes = imageProcessor.loadAndCompress(uri, maxDimension)
                when (val result = profileImageUploader.upload(bytes)) {
                    is ProfileImageUploader.Result.Success -> onSuccess(result.url)
                    is ProfileImageUploader.Result.Failure -> _state.update {
                        it.copy(
                            errorMessage = appContext.getString(
                                R.string.nostr_profile_image_upload_failed,
                                result.message,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NostrProfileVM", "image upload pipeline failed", e)
                _state.update {
                    it.copy(
                        errorMessage = appContext.getString(
                            R.string.nostr_profile_image_upload_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                clearInFlight()
            }
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
