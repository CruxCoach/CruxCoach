package com.cruxcoach.android.data

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.GetQueued
import com.cruxcoach.db.secure.Nostr_messages
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrMessageRepository @Inject constructor(
    private val database: SecureDatabase
) {
    private val queries get() = database.nostrMessagesQueries

    fun insert(
        id: String,
        type: String,
        direction: String,
        content: String,
        subject: String?,
        senderPubkey: String,
        createdAt: Long,
        relayAccepted: Boolean = false,
        read: Boolean = false,
        replyToId: String? = null,
        threadAnchorId: String? = null
    ) {
        queries.insert(
            id = id,
            type = type,
            direction = direction,
            content = content,
            subject = subject,
            sender_pubkey = senderPubkey,
            created_at = createdAt,
            relay_accepted = if (relayAccepted) 1L else 0L,
            read = if (read) 1L else 0L,
            reply_to_id = replyToId,
            thread_anchor_id = threadAnchorId
        )
    }

    fun getAll(): List<Nostr_messages> = queries.getAll().executeAsList()

    fun getByType(type: String): List<Nostr_messages> = queries.getByType(type).executeAsList()

    fun getById(id: String): Nostr_messages? = queries.getById(id).executeAsOneOrNull()

    fun getRootMessagesByType(type: String): List<Nostr_messages> =
        queries.getRootMessagesByType(type).executeAsList()

    fun getThread(rootId: String): List<Nostr_messages> =
        queries.getThread(rootId).executeAsList()

    fun getUnreadCountByType(type: String): Long =
        queries.getUnreadCountByType(type).executeAsOne()

    fun getTotalUnreadCount(): Long =
        queries.getTotalUnreadCount().executeAsOne()

    fun markRead(id: String) = queries.markRead(id)

    fun markAllReadByType(type: String) = queries.markAllReadByType(type)

    fun markThreadRead(rootId: String) = queries.markThreadRead(rootId)

    fun updateRelayAccepted(id: String) = queries.updateRelayAccepted(id)

    fun deleteById(id: String) = queries.deleteById(id)

    fun deleteSelfEchoes(ownPubkey: String) = queries.deleteSelfEchoes(ownPubkey)

    fun deleteAllNonQueued() = queries.deleteAllNonQueued()

    fun countStaleSentRows(currentPubkey: String): Long =
        queries.countStaleSentRows(currentPubkey).executeAsOne()

    /**
     * Hard identity filter: deletes every non-queued row that doesn't belong
     * to the current identity. Keeps only:
     *   - sent rows signed by [currentPubkey]
     *   - received rows actually from [devPubkey]
     * Everything else is a phantom from a rotated identity or an old
     * self-echo and is purged.
     */
    fun deleteForeignIdentityRows(currentPubkey: String, devPubkey: String) =
        queries.deleteForeignIdentityRows(
            currentPubkey = currentPubkey,
            devPubkey = devPubkey
        )

    // Queue operations

    fun getQueued(): List<GetQueued> = queries.getQueued().executeAsList()

    fun getQueuedCount(): Long = queries.getQueuedCount().executeAsOne()

    fun markQueued(id: String, queuedAt: Long, eventJson: String) =
        queries.markQueued(queued_at = queuedAt, event_json = eventJson, id = id)

    fun clearQueued(id: String) = queries.clearQueued(id)

    fun deleteExpiredQueued(cutoffTimestamp: Long) =
        queries.deleteExpiredQueued(cutoffTimestamp)
}
