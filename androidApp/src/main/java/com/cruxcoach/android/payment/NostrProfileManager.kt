package com.cruxcoach.android.payment

import android.util.Log
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.payment.model.NostrProfileData
import com.cruxcoach.db.secure.SecureDatabase
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrProfileManager @Inject constructor(
    private val eventBuilder: NostrPublicEventBuilder,
    private val relayPool: NostrRelayPool,
    private val database: SecureDatabase
) {
    private val profileQueries get() = database.nostrProfilesQueries

    /**
     * Publish a Kind 0 metadata event with the given fields and refresh
     * the local cache so subsequent [getProfile] calls see the new
     * values without a relay round-trip. `about` is now a parameter
     * (was hardcoded to "CruxCoach User"); pass null/empty to omit.
     *
     * Returns the [NostrProfileData] that was published (= what the
     * cache now holds). Returns null if signing or relay submission
     * threw — caller should treat that as "publish failed, retry".
     */
    suspend fun publishProfile(
        displayName: String?,
        lightningAddress: String?,
        picture: String?,
        about: String? = null,
        banner: String? = null,
        nip05: String? = null,
        website: String? = null,
    ): NostrProfileData? {
        return try {
            val content = JSONObject().apply {
                displayName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                lightningAddress?.takeIf { it.isNotBlank() }?.let { put("lud16", it) }
                picture?.takeIf { it.isNotBlank() }?.let { put("picture", it) }
                about?.takeIf { it.isNotBlank() }?.let { put("about", it) }
                banner?.takeIf { it.isNotBlank() }?.let { put("banner", it) }
                nip05?.takeIf { it.isNotBlank() }?.let { put("nip05", it) }
                website?.takeIf { it.isNotBlank() }?.let { put("website", it) }
            }.toString()

            val event = eventBuilder.buildSignedEvent(
                kind = KIND_METADATA,
                content = content,
                tags = emptyList()
            )
            // Use sendEventWithStats so we can fail-closed when zero
            // relays accepted: previously the local profileQueries cache
            // got upserted regardless of relay outcome, so a "0/N
            // accepted" result diverged silently from what other clients
            // actually saw. Now the caller sees null and surfaces the
            // localized publish-failed Snackbar.
            val (attempted, accepted) = relayPool.sendEventWithStats(event)
            if (accepted == 0 && attempted > 0) {
                Log.w(TAG, "publishProfile: zero relays accepted attempted=$attempted — abort cache write")
                return null
            }

            val ownPubkey = event.pubKey
            // Cache the freshly-published profile under the stale-event
            // guard. The signed event's own `createdAt` (NIP-01 Unix
            // seconds) is what gets compared on the next write — a
            // stale Kind-0 racing in from a mis-configured relay later
            // can't pin an older lud16 over this publish.
            cacheProfileIfNewer(
                pubkey = ownPubkey,
                eventCreatedAt = event.createdAt,
                displayName = displayName?.takeIf { it.isNotBlank() },
                lightningAddress = lightningAddress?.takeIf { it.isNotBlank() },
                pictureUrl = picture?.takeIf { it.isNotBlank() },
                bannerUrl = banner?.takeIf { it.isNotBlank() },
                nip05 = nip05?.takeIf { it.isNotBlank() },
                website = website?.takeIf { it.isNotBlank() },
            )
            NostrProfileData(
                pubkey = ownPubkey,
                displayName = displayName?.takeIf { it.isNotBlank() },
                lightningAddress = lightningAddress?.takeIf { it.isNotBlank() },
                pictureUrl = picture?.takeIf { it.isNotBlank() },
                bannerUrl = banner?.takeIf { it.isNotBlank() },
                nip05 = nip05?.takeIf { it.isNotBlank() },
                website = website?.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile", e)
            null
        }
    }

    suspend fun getProfile(pubkey: String): NostrProfileData? {
        val cached = getCachedProfile(pubkey)
        if (cached != null) return cached

        return fetchProfileFromRelays(pubkey)
    }

    suspend fun getLightningAddress(pubkey: String): String? {
        val cached = profileQueries.getLightningAddress(pubkey).executeAsOneOrNull()
            ?.lightning_address
        if (cached != null) return cached

        val profile = fetchProfileFromRelays(pubkey)
        return profile?.lightningAddress
    }

    /**
     * Cache-only lookup — never touches relays, never times out.
     * For paths that can't afford the 10 s relay-fetch budget on a
     * cache miss (e.g. saveDraft, where the user expects an instant
     * "draft saved" snackbar). Returns null on miss; callers fall
     * back to whatever stub they use for unknown profiles.
     */
    fun getProfileFromCache(pubkey: String): NostrProfileData? = getCachedProfile(pubkey)

    private fun getCachedProfile(pubkey: String): NostrProfileData? {
        val row = profileQueries.getByPubkey(pubkey).executeAsOneOrNull() ?: return null
        return NostrProfileData(
            pubkey = row.pubkey,
            displayName = row.display_name,
            lightningAddress = row.lightning_address,
            pictureUrl = row.picture_url,
            bannerUrl = row.banner_url,
            nip05 = row.nip05,
            website = row.website,
        )
    }

    private suspend fun fetchProfileFromRelays(pubkey: String): NostrProfileData? {
        return try {
            val filter = """{"kinds":[0],"authors":["$pubkey"],"limit":1}"""
            // Collect from every relay until EOSE (or the pool times
            // out), then pick the event with the highest `createdAt`.
            // Pre-fix this used `firstOrNull()`, which let whichever
            // relay answered first win — a hostile or NIP-16-broken
            // relay could deliver an older Kind-0 ahead of the freshest
            // one and pin a stale lud16 in the cache.
            //
            // skipDedup=true: this is a one-shot historical query, not
            // a live stream. NostrRelayPool's `seenEventIds` cache is
            // shared across every subscriber in the process; on the
            // second fetchProfile call for the same pubkey the kind:0
            // event ID would already be present from the first call
            // and toList() would silently return empty. Same pattern
            // as BackupRepository.queryAllValid and NotificationPollWorker.
            //
            // closeOnEose=true: terminate the flow after every relay
            // signals end-of-stored-events instead of waiting for the
            // full RELAY_TIMEOUT_MS — keeps profile blur-fetch latency
            // close to the slowest responsive relay.
            val collected: List<String> = withTimeoutOrNull(NostrConfig.RELAY_TIMEOUT_MS) {
                relayPool.subscribe(filter, skipDedup = true, closeOnEose = true).toList()
            } ?: emptyList()
            if (collected.isEmpty()) return null

            // Parse once, pick newest by `event.createdAt`. Drop events
            // that fail Quartz' parsing — they'd have failed verification
            // in `parseAndCacheProfile` anyway.
            val newest = collected.mapNotNull { json ->
                runCatching { Event.fromJson(json) to json }.getOrNull()
            }.maxByOrNull { it.first.createdAt }?.second
                ?: return null

            parseAndCacheProfile(pubkey, newest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile for $pubkey", e)
            null
        }
    }

    private fun parseAndCacheProfile(pubkey: String, eventJson: String): NostrProfileData? {
        return try {
            // Relay is untrusted: verify pubkey, kind, and Schnorr signature
            // before trusting the profile content. Without this, any relay
            // can return a forged kind:0 that overwrites the cached lud16
            // and redirects zaps to an attacker wallet.
            val event = Event.fromJson(eventJson)
            if (event.kind != KIND_METADATA) {
                Log.w(TAG, "Ignoring non-metadata event kind ${event.kind} for $pubkey")
                return null
            }
            if (event.pubKey != pubkey) {
                Log.w(TAG, "Profile event pubkey mismatch: asked $pubkey, got ${event.pubKey}")
                return null
            }
            if (!event.verifySignature()) {
                Log.w(TAG, "Profile event signature invalid for $pubkey")
                return null
            }

            val content = JSONObject(event.content)
            val displayName = content.optString("name", null)
            val lud16 = content.optString("lud16", null)
            val picture = content.optString("picture", null)
            val banner = content.optString("banner", null)
            val nip05 = content.optString("nip05", null)
            val website = content.optString("website", null)

            // Stale-event guard: an older Kind-0 arriving after a newer
            // one (mis-configured relay, slow delivery, hostile race)
            // is rejected before the upsert. The signed event's own
            // `createdAt` is the comparison key — wall-clock would let
            // out-of-order relay deliveries silently overwrite a fresh
            // lud16 with a stale one and re-route zaps.
            val written = cacheProfileIfNewer(
                pubkey = pubkey,
                eventCreatedAt = event.createdAt,
                displayName = displayName,
                lightningAddress = lud16,
                pictureUrl = picture,
                bannerUrl = banner,
                nip05 = nip05,
                website = website,
            )
            if (!written) {
                Log.i(TAG, "skip stale Kind-0 for $pubkey created_at=${event.createdAt}")
                // Return the cache view (callers expect non-null on
                // success; the freshest data is already there). Falling
                // through to a re-read keeps the contract intact for
                // the unusual "we got a stale event but the row was
                // already populated by something newer" path.
                return getCachedProfile(pubkey)
            }

            NostrProfileData(
                pubkey = pubkey,
                displayName = displayName,
                lightningAddress = lud16,
                pictureUrl = picture,
                bannerUrl = banner,
                nip05 = nip05,
                website = website,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profile event", e)
            null
        }
    }

    /**
     * Atomic stale-event-guarded upsert: writes the incoming Kind-0
     * fields only if [eventCreatedAt] is strictly newer than the
     * cached `last_event_created_at` (or the row is absent / the
     * cached value is NULL — pre-migration writers).
     *
     * Wrapped in a SQLDelight transaction so the read + write pair is
     * atomic against parallel writes for the same pubkey. Returns
     * `true` when the upsert ran, `false` when the incoming event was
     * stale and skipped — callers can then surface the latter as a
     * silent skip log.
     *
     * SQLite 3.18 (Android API 26 baseline) doesn't speak
     * `ON CONFLICT … DO UPDATE` (introduced in 3.24), so the guard
     * lives in Kotlin instead of as a single SQL statement. The
     * transaction keeps it safe under parallel writers.
     */
    private fun cacheProfileIfNewer(
        pubkey: String,
        eventCreatedAt: Long,
        displayName: String?,
        lightningAddress: String?,
        pictureUrl: String?,
        bannerUrl: String?,
        nip05: String?,
        website: String?,
    ): Boolean {
        var wrote = false
        database.nostrProfilesQueries.transaction {
            val existing = profileQueries.getLastEventCreatedAt(pubkey)
                .executeAsOneOrNull()
                ?.last_event_created_at
            if (existing != null && eventCreatedAt <= existing) {
                return@transaction
            }
            profileQueries.upsert(
                pubkey = pubkey,
                display_name = displayName,
                lightning_address = lightningAddress,
                picture_url = pictureUrl,
                updated_at = System.currentTimeMillis() / 1000,
                banner_url = bannerUrl,
                nip05 = nip05,
                website = website,
                last_event_created_at = eventCreatedAt,
            )
            wrote = true
        }
        return wrote
    }

    companion object {
        private const val TAG = "NostrProfileManager"
        private const val KIND_METADATA = 0
    }
}
