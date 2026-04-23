package com.cruxcoach.android.nostr.relaydiscovery

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver.PubkeyProvider
import com.cruxcoach.android.nostr.model.RelaySource
import com.cruxcoach.android.nostr.model.ResolvedRelayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns the resolved NIP-65 relay list for the whole app.
 *
 * Call [current] on app start or whenever a snapshot is needed — it returns
 * from cache within the TTL, or triggers a bootstrap fetch on cache miss.
 * On identity change ([NostrKeyStore.KeyChangeListener.onKeyChanged]) the
 * cache is cleared and a refresh is kicked off.
 *
 * Writes the resolved list back to [NostrRelayPool] via
 * [NostrRelayPool.onRelaysChanged] whenever it changes. The pool never pulls
 * from this class — it consumes an always-up-to-date `@Volatile` snapshot.
 */
@Singleton
class RelayListResolver @Inject constructor(
    private val fetcher: Nip65RelayListFetcher,
    private val cache: RelayListCache,
    private val pool: NostrRelayPool,
    private val pubkeyProvider: PubkeyProvider,
    private val userPreferences: UserPreferences,
    @Named("relayDiscovery") private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : NostrKeyStore.KeyChangeListener {

    /**
     * Abstracts pubkey retrieval so the resolver doesn't need to link
     * against Quartz's `KeyPair` class in tests. Returns the current pubkey
     * as hex, or `null` when no key has been provisioned yet (fresh
     * install, pre-NostrKeyStore initialization).
     */
    fun interface PubkeyProvider {
        fun currentPubkeyHex(): String?
    }

    private val fetchMutex = Mutex()

    @Volatile
    private var inFlightFetch: Deferred<ResolvedRelayList>? = null

    /** Guard for the last-notified set — prevents duplicate pool notifications. */
    @Volatile
    private var lastNotifiedSignature: Set<RelaySignature>? = null

    private var killSwitchLogged = false

    /**
     * Returns the current resolved relay list. Cache-first; on stale cache,
     * returns the cached copy immediately and launches a background refresh.
     * Never throws.
     */
    suspend fun current(): ResolvedRelayList {
        if (!userPreferences.isNip65DiscoveryEnabled()) {
            if (!killSwitchLogged) {
                Log.i(TAG, "event=killswitch_off")
                killSwitchLogged = true
            }
            val list = defaultsOnly()
            logResolverCurrent("killswitch", list)
            notifyPoolIfChanged(list)
            return list
        }
        killSwitchLogged = false

        val cached = cache.read()
        if (cached != null && !cache.isStale()) {
            logResolverCurrent("cache", cached)
            notifyPoolIfChanged(cached)
            return cached
        }

        if (cached != null) {
            // Stale → serve it, refresh in background
            Log.d(TAG, "event=cache_stale_served ageMs=${clock() - cached.resolvedAtEpochMs}")
            logResolverCurrent("cache", cached)
            notifyPoolIfChanged(cached)
            launchBackgroundRefresh()
            return cached
        }

        // Cache miss — perform a synchronous fetch (deduped across callers).
        if (pubkeyProvider.currentPubkeyHex() == null) {
            val list = defaultsOnly()
            Log.d(TAG, "event=resolver_current source=defaults reason=no-key")
            notifyPoolIfChanged(list)
            return list
        }

        val resolved = fetchOnceOrJoin()
        notifyPoolIfChanged(resolved)
        return resolved
    }

    /** Clear cached state. Triggered by identity change (logout / key switch). */
    suspend fun invalidate() {
        Log.d(TAG, "event=invalidated")
        cache.clear()
        lastNotifiedSignature = null
    }

    /**
     * Fire-and-forget refresh. Used by the app-bootstrap hook and by stale-cache
     * fallback. Joins any in-flight fetch; never starts a second one in parallel.
     */
    fun refreshAsync(): Job = appScope.launch {
        if (!userPreferences.isNip65DiscoveryEnabled()) return@launch
        if (pubkeyProvider.currentPubkeyHex() == null) return@launch
        val resolved = fetchOnceOrJoin()
        notifyPoolIfChanged(resolved)
    }

    override fun onKeyChanged() {
        // Fire and forget. appScope is a SupervisorJob so one failure doesn't
        // poison the next identity change.
        appScope.launch {
            invalidate()
            if (userPreferences.isNip65DiscoveryEnabled() && pubkeyProvider.currentPubkeyHex() != null) {
                notifyPoolIfChanged(fetchOnceOrJoin())
            } else {
                // Logout or discovery disabled — revert to defaults explicitly.
                notifyPoolIfChanged(defaultsOnly())
            }
        }
    }

    // ----------------------------------------------------------------- fetch

    private suspend fun fetchOnceOrJoin(): ResolvedRelayList {
        inFlightFetch?.let { return awaitOrDefaults(it) }
        // Mutex is held only across the in-flight state mutation, never
        // across `deferred.await()` — otherwise the cleanup `withLock` in
        // the `finally` would deadlock (Kotlin's Mutex is not re-entrant).
        val deferred = fetchMutex.withLock {
            inFlightFetch?.let { return@withLock it }
            val d = appScope.async { performFetchAndCache() }
            inFlightFetch = d
            d
        }
        return try {
            deferred.await()
        } catch (_: Exception) {
            defaultsOnly()
        } finally {
            fetchMutex.withLock {
                if (inFlightFetch === deferred) inFlightFetch = null
            }
        }
    }

    private suspend fun awaitOrDefaults(deferred: Deferred<ResolvedRelayList>): ResolvedRelayList {
        return try {
            deferred.await()
        } catch (_: Exception) {
            defaultsOnly()
        }
    }

    private suspend fun performFetchAndCache(): ResolvedRelayList {
        val pubkeyHex = try {
            pubkeyProvider.currentPubkeyHex()
        } catch (e: Exception) {
            Log.w(TAG, "event=resolver_no_key", e)
            null
        }
        if (pubkeyHex.isNullOrBlank()) return defaultsOnly()
        val event = fetcher.fetch(pubkeyHex)

        val userMarkers = event?.relays.orEmpty()
        val merged = mergeAdditive(userMarkers)

        val resolved = ResolvedRelayList(
            relays = merged,
            resolvedAtEpochMs = clock(),
            hasUserList = userMarkers.isNotEmpty(),
        )
        cache.write(resolved)
        return resolved
    }

    /** Additive union: user list first (order preserved), then defaults not already present. */
    internal fun mergeAdditive(
        userMarkers: List<Kind10002Event.RelayMarker>,
    ): List<RelayConfig> {
        val out = LinkedHashMap<String, RelayConfig>()
        for (marker in userMarkers) {
            out[marker.url] = RelayConfig(
                url = marker.url,
                read = marker.read,
                write = marker.write,
                source = RelaySource.USER_NIP65,
            )
        }
        for (default in NostrConfig.DEFAULT_RELAYS) {
            if (!out.containsKey(default.url)) {
                out[default.url] = default.copy(source = RelaySource.DEFAULT)
            }
            // If user already listed it, user markers win — do not overwrite.
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------- pool wiring

    private fun notifyPoolIfChanged(list: ResolvedRelayList) {
        val signature = list.relays.map { RelaySignature(it.url, it.read, it.write) }.toSet()
        if (signature == lastNotifiedSignature) return
        val previous = lastNotifiedSignature ?: emptySet()
        val added = signature - previous
        val dropped = previous - signature
        Log.d(TAG, "event=pool_relays_changed added=${added.size} dropped=${dropped.size} stable=${(previous intersect signature).size}")
        pool.onRelaysChanged(list.relays)
        lastNotifiedSignature = signature
    }

    private fun launchBackgroundRefresh() {
        appScope.launch {
            if (inFlightFetch != null) return@launch   // someone else is already on it
            val resolved = fetchOnceOrJoin()
            notifyPoolIfChanged(resolved)
        }
    }

    private fun defaultsOnly(): ResolvedRelayList = ResolvedRelayList(
        relays = NostrConfig.DEFAULT_RELAYS.map { it.copy(source = RelaySource.DEFAULT) },
        resolvedAtEpochMs = clock(),
        hasUserList = false,
    )

    private fun logResolverCurrent(source: String, list: ResolvedRelayList) {
        Log.d(
            TAG,
            "event=resolver_current source=$source hasUserList=${list.hasUserList} relayCount=${list.relays.size}",
        )
    }

    /** Compared set of (url, read, write) so notifications skip pure source-field flips. */
    private data class RelaySignature(val url: String, val read: Boolean, val write: Boolean)

    companion object {
        private const val TAG = "Nip65Discovery"
    }
}
