package com.cruxcoach.app.community

import com.cruxcoach.app.backup.PublishStats
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrRelays
import com.cruxcoach.app.nostr.RelayClient

/**
 * Relay side of the community-climb pipeline, isolated so the publisher, the
 * deleter and the subscriber can be tested against a fake without a socket.
 *
 * Implementations must only return events that already passed
 * [com.cruxcoach.app.nostr.NostrEvents.verify]; every consumer re-checks anyway,
 * because a source is not a trust boundary.
 */
interface CommunityRelay {
    suspend fun publish(event: NostrEvent): PublishStats
    suspend fun query(filters: List<String>, timeoutMs: Long = NostrRelays.RELAY_TIMEOUT_MS): List<NostrEvent>
}

/**
 * [CommunityRelay] over the real relay set.
 *
 * Android holds a long-lived REQ through `NostrRelayPool` and streams events as
 * they arrive. [RelayClient] deliberately has no such pool — it does one-shot
 * REQ/EOSE queries over short-lived sockets — so the iOS subscriber polls with
 * the persisted `since` cursor instead of streaming. The ingest rules are the
 * same either way; only the arrival schedule differs.
 */
class RelayCommunityRelay(
    private val client: RelayClient,
    private val relays: List<String> = NostrRelays.DEFAULT_RELAYS,
) : CommunityRelay {
    override suspend fun publish(event: NostrEvent): PublishStats {
        val (attempted, accepted) = client.publish(event, relays)
        return PublishStats(attempted, accepted)
    }

    override suspend fun query(filters: List<String>, timeoutMs: Long): List<NostrEvent> =
        client.query(filters, relays, timeoutMs)
}
