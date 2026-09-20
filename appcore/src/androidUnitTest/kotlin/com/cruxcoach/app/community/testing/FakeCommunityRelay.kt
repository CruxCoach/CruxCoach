package com.cruxcoach.app.community.testing

import com.cruxcoach.app.backup.PublishStats
import com.cruxcoach.app.community.CommunityRelay
import com.cruxcoach.app.nostr.NostrEvent

/**
 * Relay double with honest accounting: [relayCount] sockets are dialled and the
 * first [acceptCount] of them answer OK, so a test can reproduce a partial
 * publish without a network.
 */
class FakeCommunityRelay(
    var relayCount: Int = 3,
    var acceptCount: Int = 3,
) : CommunityRelay {
    val published = mutableListOf<NostrEvent>()
    val filters = mutableListOf<List<String>>()
    var queryResponse: List<NostrEvent> = emptyList()

    fun ofKind(kind: Int): List<NostrEvent> = published.filter { it.kind == kind }

    override suspend fun publish(event: NostrEvent): PublishStats {
        published += event
        return PublishStats(relayCount, minOf(acceptCount, relayCount).coerceAtLeast(0))
    }

    override suspend fun query(filters: List<String>, timeoutMs: Long): List<NostrEvent> {
        this.filters += filters
        return queryResponse
    }
}
