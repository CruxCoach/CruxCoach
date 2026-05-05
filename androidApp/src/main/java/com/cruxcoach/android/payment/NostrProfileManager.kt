package com.cruxcoach.android.payment

import android.util.Log
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.payment.model.NostrProfileData
import com.cruxcoach.db.secure.SecureDatabase
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
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
    ): NostrProfileData? {
        return try {
            val content = JSONObject().apply {
                displayName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                lightningAddress?.takeIf { it.isNotBlank() }?.let { put("lud16", it) }
                picture?.takeIf { it.isNotBlank() }?.let { put("picture", it) }
                about?.takeIf { it.isNotBlank() }?.let { put("about", it) }
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
            profileQueries.upsert(
                pubkey = ownPubkey,
                display_name = displayName?.takeIf { it.isNotBlank() },
                lightning_address = lightningAddress?.takeIf { it.isNotBlank() },
                picture_url = picture?.takeIf { it.isNotBlank() },
                updated_at = System.currentTimeMillis() / 1000
            )
            NostrProfileData(
                pubkey = ownPubkey,
                displayName = displayName?.takeIf { it.isNotBlank() },
                lightningAddress = lightningAddress?.takeIf { it.isNotBlank() },
                pictureUrl = picture?.takeIf { it.isNotBlank() },
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
            pictureUrl = row.picture_url
        )
    }

    private suspend fun fetchProfileFromRelays(pubkey: String): NostrProfileData? {
        return try {
            val filter = """{"kinds":[0],"authors":["$pubkey"],"limit":1}"""
            // skipDedup=true: this is a one-shot historical query, not a
            // live stream. NostrRelayPool's `seenEventIds` cache is shared
            // across every subscriber in the process; on the second
            // fetchProfile call for the same pubkey the kind:0 event ID
            // would already be present from the first call, causing
            // firstOrNull() to silently return null and the cached
            // profile to never refresh after the first hit. Same pattern
            // as BackupRepository.queryAllValid and NotificationPollWorker.
            val eventJson = withTimeout(NostrConfig.RELAY_TIMEOUT_MS) {
                relayPool.subscribe(filter, skipDedup = true).firstOrNull()
            } ?: return null

            parseAndCacheProfile(pubkey, eventJson)
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

            profileQueries.upsert(
                pubkey = pubkey,
                display_name = displayName,
                lightning_address = lud16,
                picture_url = picture,
                updated_at = System.currentTimeMillis() / 1000
            )

            NostrProfileData(
                pubkey = pubkey,
                displayName = displayName,
                lightningAddress = lud16,
                pictureUrl = picture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profile event", e)
            null
        }
    }

    companion object {
        private const val TAG = "NostrProfileManager"
        private const val KIND_METADATA = 0
    }
}
