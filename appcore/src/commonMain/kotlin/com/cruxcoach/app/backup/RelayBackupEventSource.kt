package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrRelays
import com.cruxcoach.app.nostr.RelayClient

/**
 * [BackupEventSource] over the real relays. The client already verifies every
 * event it returns; [BackupRepository] re-checks author, kind and signature
 * anyway, because a source is not a trust boundary.
 */
class RelayBackupEventSource(
    private val client: RelayClient,
    private val relays: List<String> = NostrRelays.DEFAULT_RELAYS,
) : BackupEventSource {
    override suspend fun query(filter: String, timeoutMs: Long): List<NostrEvent> =
        client.query(filter, relays, timeoutMs)

    override suspend fun publish(event: NostrEvent): PublishStats {
        val (attempted, accepted) = client.publish(event, relays)
        return PublishStats(attempted, accepted)
    }
}
