package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.model.DecryptedMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrRelaySubscription @Inject constructor(
    private val relayPool: NostrRelayPool,
    private val eventDecryptor: NostrEventDecryptor,
    private val nostrSigner: NostrSigner,
    private val userPreferences: UserPreferences
) {
    suspend fun subscribe(): Flow<DecryptedMessage> {
        val ownPubkey = nostrSigner.getPublicKeyHex()
        val filter = buildFilter(ownPubkey)

        return relayPool.subscribe(filter).mapNotNull { eventJson ->
            try {
                val msg = eventDecryptor.decrypt(eventJson) ?: return@mapNotNull null
                // Advance the persistent sync cursor so the next subscription
                // resumes where we left off. The cursor MUST track the
                // OUTER gift wrap's created_at (wrapTimestamp), because
                // that's the field the relay `since` filter operates on.
                // Using the inner rumor time would drift the cursor into a
                // different time domain and start excluding events. 60s
                // back-off guards against out-of-order delivery from
                // multiple relays.
                val wrapCreatedAtSec = msg.wrapTimestamp / 1000
                val current = userPreferences.getNostrSyncCursor() ?: 0L
                val next = (wrapCreatedAtSec - 60).coerceAtLeast(0L)
                if (next > current) {
                    userPreferences.setNostrSyncCursor(next)
                }
                msg
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process incoming event", e)
                null
            }
        }
    }

    private suspend fun buildFilter(pubkey: String): String {
        // Resume from the persistent cursor, capped at 365 days back so a
        // fresh install doesn't ask the relays for ancient history (and so
        // a missing/zero cursor still produces a sensible bounded query).
        //
        // NIP-59 GIFT WRAP RANDOMIZATION: gift wraps deliberately backdate
        // their created_at by a random amount up to 2 days for timing
        // correlation resistance (NDK does this by default for NIP-17 DMs).
        // Without compensating, a freshly published reply with
        // created_at = now - 1.5d will fall behind a cursor at now - 30min
        // and never be delivered. Subtract NIP59_RANDOM_WINDOW so the relay
        // includes those backdated wraps. Duplicate IDs dedupe via
        // INSERT OR IGNORE in the local DB.
        val now = System.currentTimeMillis() / 1000
        val initialWindow = now - INITIAL_WINDOW_SECONDS
        val cursor = userPreferences.getNostrSyncCursor() ?: 0L
        val since = maxOf(cursor - NIP59_RANDOM_WINDOW_SECONDS, initialWindow)
        return """{"kinds":[1059],"#p":["$pubkey"],"since":$since}"""
    }

    companion object {
        private const val TAG = "NostrRelaySubscription"
        private const val INITIAL_WINDOW_SECONDS = 365L * 24 * 60 * 60
        // NIP-59 §"Wrapping": gift wrap created_at MAY be tweaked up to 2
        // days in either direction. NDK randomizes by up to 2 days back.
        private const val NIP59_RANDOM_WINDOW_SECONDS = 2L * 24 * 60 * 60
    }
}
