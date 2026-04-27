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

    suspend fun publishProfile(
        displayName: String?,
        lightningAddress: String?,
        picture: String?
    ) {
        try {
            val content = JSONObject().apply {
                displayName?.let { put("name", it) }
                lightningAddress?.let { put("lud16", it) }
                picture?.let { put("picture", it) }
                put("about", "CruxCoach User")
            }.toString()

            val event = eventBuilder.buildSignedEvent(
                kind = KIND_METADATA,
                content = content,
                tags = emptyList()
            )
            relayPool.sendEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile", e)
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
