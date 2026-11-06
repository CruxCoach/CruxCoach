package com.cruxcoach.android.communityclient

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.communityclient.CommunityApi
import com.cruxcoach.communityclient.CommunityClient
import com.cruxcoach.communityclient.CommunityEndpoint
import com.cruxcoach.communityclient.CommunityJoin
import com.cruxcoach.communityclient.UnixClock
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** What the community screens need to know before a client exists. */
data class CommunitySetup(
    /** The user turned the feature on. Nothing reaches the server before that. */
    val enabled: Boolean,
    /** The community this key joined, if any. */
    val joined: CommunityEndpoint?,
)

/**
 * Owns the one [CommunityClient] of the app and the feature's own small settings
 * file. The client exists only while the feature is on and a community is joined,
 * and it is rebuilt when the user switches keys.
 */
@Singleton
class CommunityClientHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val nostrSigner: NostrSigner,
    @param:Named("nostr") private val okHttpClient: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = UnixClock { System.currentTimeMillis() / 1000 }
    private val http by lazy { OkHttpCommunityHttp(okHttpClient) }
    private val sockets by lazy { OkHttpCommunitySocketFactory(okHttpClient) }
    private val signer = QuartzCommunitySigner(signer = { nostrSigner.signer }, publicKey = { nostrSigner.getPublicKeyHex() })

    private val _setup = MutableStateFlow(readSetup())
    val setup: StateFlow<CommunitySetup> = _setup.asStateFlow()

    private var client: CommunityClient? = null
    private var clientKey: Pair<CommunityEndpoint, String>? = null

    /** The identity that signs, for display. */
    val pubkey: String get() = signer.pubkey

    /** The same identity as an npub, which people can give to a community admin. */
    val npub: String get() = runCatching { pubkey.hexToByteArray().toNpub() }.getOrDefault(pubkey)
    val usesAmber: Boolean get() = nostrSigner.getStoredSignerMode().name == "AMBER"

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) closeClient()
        _setup.value = readSetup()
    }

    /** Remembers that this key is a member of [endpoint]. */
    fun markJoined(endpoint: CommunityEndpoint) {
        prefs.edit()
            .putString(KEY_HOST, endpoint.host)
            .putBoolean(KEY_SECURE, endpoint.secure)
            .putString(KEY_JOINED_PUBKEY, signer.pubkey)
            .apply()
        _setup.value = readSetup()
    }

    /** Forgets the joined community on this device (membership on the server stays). */
    fun forgetCommunity() {
        prefs.edit().remove(KEY_HOST).remove(KEY_SECURE).remove(KEY_JOINED_PUBKEY).apply()
        closeClient()
        _setup.value = readSetup()
    }

    /** The running client for the joined community, created and started on first use. */
    @Synchronized
    fun client(): CommunityClient? {
        val setup = readSetup()
        val endpoint = setup.joined?.takeIf { setup.enabled } ?: return null.also { closeClient() }
        val key = endpoint to signer.pubkey
        if (clientKey != key) {
            closeClient()
            client = CommunityClient(
                endpoint = endpoint,
                socketFactory = sockets,
                http = http,
                signer = signer,
                verifier = QuartzEventVerifier,
                clock = clock,
                scope = scope,
                elapsedMillis = { SystemClock.elapsedRealtime() },
                onTiming = { step, millis -> Log.i(TAG, "event=timing step=$step ms=$millis") },
            ).also { started ->
                started.start()
                // Time to the first room list, for the spike report (logcat only, no content).
                scope.launch {
                    val seconds = started.rooms.first { it.firstListAfterSeconds != null }.firstListAfterSeconds
                    Log.i(TAG, "event=first_room_list seconds=$seconds")
                }
            }
            clientKey = key
        }
        return client
    }

    /** The pre-membership API for the join steps. */
    fun joinSteps(endpoint: CommunityEndpoint): CommunityJoin =
        CommunityJoin(CommunityApi(endpoint, http, signer, QuartzEventVerifier, clock))

    @Synchronized
    private fun closeClient() {
        client?.stop()
        client = null
        clientKey = null
    }

    private fun readSetup(): CommunitySetup {
        val host = prefs.getString(KEY_HOST, null)
        val joinedBy = prefs.getString(KEY_JOINED_PUBKEY, null)
        // A membership belongs to one key; after a key switch the user joins again.
        val joined = if (host != null && joinedBy == runCatching { signer.pubkey }.getOrNull()) {
            CommunityEndpoint(host, prefs.getBoolean(KEY_SECURE, true))
        } else {
            null
        }
        return CommunitySetup(enabled = prefs.getBoolean(KEY_ENABLED, false), joined = joined)
    }

    companion object {
        /** The CruxCoach community. Invites name their own host; this is the default for members. */
        val DEFAULT_COMMUNITY = CommunityEndpoint("community.cruxcoach.org")

        /** The preview is in feature and development builds only. */
        val isAvailable: Boolean get() = BuildConfig.DEBUG

        private const val TAG = "CommunityClient"
        private const val PREFS = "community_client"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOST = "host"
        private const val KEY_SECURE = "secure"
        private const val KEY_JOINED_PUBKEY = "joined_pubkey"
    }
}
