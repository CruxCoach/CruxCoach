package com.cruxcoach.app.profile

import com.cruxcoach.app.nostr.RelayClient
import kotlinx.coroutines.CancellationException

/**
 * Reads any climber's kind-0 profile: cache first, relay when the cached copy
 * is older than [ttlSeconds].
 *
 * The TTL exists because there is no live kind-0 subscription on iOS. Without
 * it a profile cached on first contact would never update, so a setter could
 * rename themselves and every other device would keep the old name forever —
 * the same bug Android's `NostrProfileManager` documents.
 */
class ProfileLookup(
    private val store: NostrProfileStore,
    private val relays: RelayClient,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
) {
    /** Never touches the network; for paths that cannot wait for a relay. */
    fun cached(pubkey: String): NostrProfileData? = store.cached(pubkey)

    /**
     * The freshest profile this device can get. A relay failure keeps the
     * cached copy rather than blanking the screen.
     */
    suspend fun resolve(pubkey: String, force: Boolean = false): NostrProfileData? {
        if (pubkey.isEmpty()) return null
        val cached = store.cached(pubkey)
        // A profile this device owns is authoritative.
        if (cached != null && store.isLocalPrimary(pubkey)) return cached
        if (!force && cached != null) {
            val age = store.cacheAgeSeconds(pubkey)
            if (age != null && age < ttlSeconds) return cached
        }
        val fetched = try {
            val filter = """{"kinds":[$KIND_METADATA],"authors":["$pubkey"],"limit":1}"""
            relays.query(filter)
                .filter { it.kind == KIND_METADATA && it.pubkey == pubkey }
                // Newest wins: a relay answering first with an older kind-0
                // must not pin a stale name.
                .maxByOrNull { it.createdAt }
                ?.let { event ->
                    ProfilePresenter.parseMetadata(pubkey, event.content)
                        ?.also { store.cacheIfNewer(it, event.createdAt, localPrimary = false) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return fetched ?: cached
    }

    companion object {
        /** Android's `PROFILE_CACHE_TTL_SECONDS`: thirty minutes. */
        const val DEFAULT_TTL_SECONDS = 30L * 60
    }
}
