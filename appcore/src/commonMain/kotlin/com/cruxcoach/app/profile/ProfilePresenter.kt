package com.cruxcoach.app.profile

import com.cruxcoach.app.backup.EventSigner
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.nostr.UrlValidation
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Nostr kind of a metadata (profile) event. */
internal const val KIND_METADATA = 0

enum class ProfileError { NONE, LOAD_FAILED, NO_IDENTITY, SIGNING_FAILED, NO_RELAY_ACCEPTED, INVALID_URL }

/** Result of checking a NIP-05 identifier against its own domain. */
enum class Nip05Status { UNKNOWN, CHECKING, MATCHES, MISMATCH, UNREACHABLE }

data class ProfileState(
    val isLoading: Boolean = true,
    val isPublishing: Boolean = false,
    val pubkey: String = "",
    val npub: String = "",
    val displayName: String = "",
    val about: String = "",
    val pictureUrl: String = "",
    val bannerUrl: String = "",
    val nip05: String = "",
    val website: String = "",
    val lightningAddress: String = "",
    /** True once an edit has not yet been saved locally. */
    val isDirty: Boolean = false,
    val nip05Status: Nip05Status = Nip05Status.UNKNOWN,
    /** Relays that stored the last publish, out of those dialed. */
    val publishedTo: Int = 0,
    val publishAttempted: Int = 0,
    val error: ProfileError = ProfileError.NONE,
)

/**
 * The user's own Nostr profile (kind 0): what other climbers see next to a
 * community problem they published.
 *
 * Port of Android's `NostrProfileManager` plus its profile screen. Editing is
 * local and explicit; publishing signs a kind-0 event and fails closed when no
 * relay stored it, because a cache that diverges from what everyone else sees
 * is worse than a visible failure.
 */
class ProfilePresenter(
    private val store: NostrProfileStore,
    private val relays: RelayClient,
    private val signer: EventSigner,
    private val http: HttpTransport,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val pubkeyHex: () -> String,
    private val npubOf: (String) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    fun watch(onState: (ProfileState) -> Unit) =
        com.cruxcoach.app.logbook.StateWatch(scope.launch { state.collect { onState(it) } })

    /** Cache first, then a relay refresh unless this device owns the profile. */
    fun load() {
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) {
            _state.update { it.copy(isLoading = false, error = ProfileError.NO_IDENTITY) }
            return
        }
        scope.launch {
            val cached = try {
                withContext(ioDispatcher) { store.cached(pubkey) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = ProfileError.LOAD_FAILED) }
                return@launch
            }
            _state.update { apply(it, pubkey, cached).copy(isLoading = false) }
            // A profile this device owns is authoritative: a relay still
            // serving an older kind-0 must not overwrite unsaved local edits.
            if (cached != null && withContext(ioDispatcher) { store.isLocalPrimary(pubkey) }) return@launch
            refresh()
        }
    }

    /** Fetches the newest kind-0 from the relays and caches it. */
    fun refresh() {
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) return
        scope.launch {
            val newest = try {
                fetchNewest(pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            val parsed = parseMetadata(pubkey, newest.content) ?: return@launch
            withContext(ioDispatcher) {
                store.cacheIfNewer(parsed, newest.createdAt, localPrimary = false)
            }
            // Never clobber an edit the user is in the middle of.
            if (_state.value.isDirty) return@launch
            _state.update { apply(it, pubkey, parsed) }
        }
    }

    fun setDisplayName(value: String) = edit { it.copy(displayName = value) }
    fun setAbout(value: String) = edit { it.copy(about = value) }
    fun setPictureUrl(value: String) = edit { it.copy(pictureUrl = value) }
    fun setBannerUrl(value: String) = edit { it.copy(bannerUrl = value) }
    fun setWebsite(value: String) = edit { it.copy(website = value) }
    fun setLightningAddress(value: String) = edit { it.copy(lightningAddress = value) }
    fun setNip05(value: String) = edit {
        it.copy(nip05 = value, nip05Status = Nip05Status.UNKNOWN)
    }

    private fun edit(block: (ProfileState) -> ProfileState) {
        _state.update { block(it).copy(isDirty = true, error = ProfileError.NONE) }
    }

    /** Stores the edits locally; nothing is signed or sent. */
    fun saveLocal() {
        val current = _state.value
        if (current.pubkey.isEmpty()) {
            _state.update { it.copy(error = ProfileError.NO_IDENTITY) }
            return
        }
        // isValidBlossom is the shared https gate (scheme, length, no control
        // characters); the name is historical, the rule is what we want here.
        val invalid = listOf(current.pictureUrl, current.bannerUrl, current.website)
            .any { it.isNotBlank() && !UrlValidation.isValidBlossom(it) }
        if (invalid) {
            _state.update { it.copy(error = ProfileError.INVALID_URL) }
            return
        }
        scope.launch {
            withContext(ioDispatcher) { store.saveLocal(current.toProfile()) }
            _state.update { it.copy(isDirty = false) }
        }
    }

    /**
     * Signs a kind-0 event and sends it. The local cache is only written when
     * a relay confirmed storing it — otherwise this device would show a
     * profile no one else can see.
     */
    fun publish() {
        val current = _state.value
        if (current.isPublishing) return
        if (current.pubkey.isEmpty()) {
            _state.update { it.copy(error = ProfileError.NO_IDENTITY) }
            return
        }
        val invalid = listOf(current.pictureUrl, current.bannerUrl, current.website)
            .any { it.isNotBlank() && !UrlValidation.isValidBlossom(it) }
        if (invalid) {
            _state.update { it.copy(error = ProfileError.INVALID_URL) }
            return
        }
        _state.update { it.copy(isPublishing = true, error = ProfileError.NONE) }
        scope.launch {
            val profile = current.toProfile()
            val event = signer.sign(clock.epochSeconds(), KIND_METADATA, emptyList(), content(profile))
            if (event == null || !NostrEvents.verify(hashing, event)) {
                _state.update { it.copy(isPublishing = false, error = ProfileError.SIGNING_FAILED) }
                return@launch
            }
            val (attempted, accepted) = try {
                relays.publish(event)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0 to 0
            }
            if (accepted == 0) {
                _state.update {
                    it.copy(
                        isPublishing = false,
                        publishAttempted = attempted,
                        publishedTo = 0,
                        error = ProfileError.NO_RELAY_ACCEPTED,
                    )
                }
                return@launch
            }
            withContext(ioDispatcher) {
                store.cacheIfNewer(profile, event.createdAt, localPrimary = true)
            }
            _state.update {
                it.copy(
                    isPublishing = false,
                    isDirty = false,
                    publishAttempted = attempted,
                    publishedTo = accepted,
                )
            }
        }
    }

    /**
     * Checks the NIP-05 identifier against its own domain: `name@example.com`
     * must be listed in `https://example.com/.well-known/nostr.json?name=name`
     * with exactly this pubkey. An unreachable domain is reported as such and
     * never as a match.
     */
    fun verifyNip05() {
        val identifier = _state.value.nip05.trim()
        val pubkey = _state.value.pubkey
        if (identifier.isEmpty() || pubkey.isEmpty()) return
        val parts = identifier.split("@")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            _state.update { it.copy(nip05Status = Nip05Status.MISMATCH) }
            return
        }
        val (name, domain) = parts
        if (!NIP05_NAME.matches(name) || !NIP05_DOMAIN.matches(domain)) {
            _state.update { it.copy(nip05Status = Nip05Status.MISMATCH) }
            return
        }
        _state.update { it.copy(nip05Status = Nip05Status.CHECKING) }
        scope.launch {
            val status = try {
                val url = "https://$domain/.well-known/nostr.json?name=$name"
                when (val result = http.request(
                    method = "GET",
                    url = url,
                    headers = emptyMap(),
                    body = null,
                    maxResponseBytes = MAX_NIP05_BYTES,
                    timeoutSeconds = NIP05_TIMEOUT_SECONDS,
                )) {
                    is HttpResult.Ok ->
                        if (result.response.status != 200) Nip05Status.UNREACHABLE
                        else if (namesMatch(result.response.body.decodeToString(), name, pubkey)) {
                            Nip05Status.MATCHES
                        } else Nip05Status.MISMATCH
                    is HttpResult.Failed -> Nip05Status.UNREACHABLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Nip05Status.UNREACHABLE
            }
            _state.update { it.copy(nip05Status = status) }
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = ProfileError.NONE) }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun fetchNewest(pubkey: String): NostrEvent? {
        val filter = """{"kinds":[$KIND_METADATA],"authors":["$pubkey"],"limit":1}"""
        // Every relay is asked and the newest event wins: a relay answering
        // first with an older kind-0 must not pin a stale profile.
        return relays.query(filter)
            .filter { it.kind == KIND_METADATA && it.pubkey == pubkey }
            .maxByOrNull { it.createdAt }
    }

    private fun apply(state: ProfileState, pubkey: String, profile: NostrProfileData?) = state.copy(
        pubkey = pubkey,
        npub = npubOf(pubkey),
        displayName = profile?.displayName ?: "",
        about = profile?.about ?: "",
        pictureUrl = profile?.pictureUrl ?: "",
        bannerUrl = profile?.bannerUrl ?: "",
        nip05 = profile?.nip05 ?: "",
        website = profile?.website ?: "",
        lightningAddress = profile?.lightningAddress ?: "",
        isDirty = false,
    )

    private fun ProfileState.toProfile() = NostrProfileData(
        pubkey = pubkey,
        displayName = displayName.trim().ifEmpty { null },
        about = about.trim().ifEmpty { null },
        pictureUrl = pictureUrl.trim().ifEmpty { null },
        bannerUrl = bannerUrl.trim().ifEmpty { null },
        nip05 = nip05.trim().ifEmpty { null },
        website = website.trim().ifEmpty { null },
        lightningAddress = lightningAddress.trim().ifEmpty { null },
    )

    companion object {
        private const val MAX_NIP05_BYTES = 256L * 1024
        private const val NIP05_TIMEOUT_SECONDS = 10

        /** NIP-05 local part; `_` is the domain-root identifier. */
        private val NIP05_NAME = Regex("^[a-z0-9\\-_.]+$", RegexOption.IGNORE_CASE)
        private val NIP05_DOMAIN = Regex("^[a-z0-9.\\-]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE)

        private val JSON = Json { ignoreUnknownKeys = true }

        /** kind-0 content is a JSON object of the fields that are set. */
        internal fun content(profile: NostrProfileData): String = JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                profile.displayName?.let { put("name", it) }
                profile.about?.let { put("about", it) }
                profile.pictureUrl?.let { put("picture", it) }
                profile.bannerUrl?.let { put("banner", it) }
                profile.nip05?.let { put("nip05", it) }
                profile.website?.let { put("website", it) }
                profile.lightningAddress?.let { put("lud16", it) }
            },
        )

        internal fun parseMetadata(pubkey: String, content: String): NostrProfileData? = try {
            val json = JSON.parseToJsonElement(content).jsonObject
            fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
            NostrProfileData(
                pubkey = pubkey,
                // `display_name` is the older field; `name` wins when both exist.
                displayName = str("name") ?: str("display_name"),
                about = str("about"),
                pictureUrl = str("picture"),
                bannerUrl = str("banner"),
                nip05 = str("nip05"),
                website = str("website"),
                lightningAddress = str("lud16"),
            )
        } catch (_: Exception) {
            null
        }

        /** True when the well-known document maps [name] to [pubkey]. */
        internal fun namesMatch(body: String, name: String, pubkey: String): Boolean = try {
            val names = JSON.parseToJsonElement(body).jsonObject["names"]?.jsonObject
            val listed = names?.get(name)?.jsonPrimitive?.contentOrNullSafe()
                ?: names?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value?.jsonPrimitive?.contentOrNullSafe()
            listed != null && listed.equals(pubkey, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}

/** `jsonPrimitive.content` throws on a JSON null; a missing field is not an error. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
