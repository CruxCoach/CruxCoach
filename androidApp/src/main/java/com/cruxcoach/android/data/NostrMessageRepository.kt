package com.cruxcoach.android.data

import com.cruxcoach.android.nostr.ReplyContext
import com.cruxcoach.android.nostr.ThreadIdResolver
import com.cruxcoach.android.nostr.ThreadMemberRef
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
        threadAnchorId: String? = null,
        replyToWireId: String? = null
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
            thread_anchor_id = threadAnchorId,
            reply_to_wire_id = replyToWireId
        )
    }

    fun getAll(): List<Nostr_messages> = queries.getAll().executeAsList()

    fun getByType(type: String): List<Nostr_messages> = queries.getByType(type).executeAsList()

    fun getById(id: String): Nostr_messages? = queries.getById(id).executeAsOneOrNull()

    fun getRootMessagesByType(type: String): List<Nostr_messages> =
        queries.getRootMessagesByType(type).executeAsList()

    fun getThread(rootId: String): List<Nostr_messages> =
        queries.getThread(rootId).executeAsList()

    /**
     * Resolves an arbitrary event id — local (self-wrap) row id OR the
     * recipient-wrap id stored as thread_anchor_id — to the LOCAL id of
     * its thread root. Returns null when nothing matches.
     */
    fun resolveLocalRootId(eventId: String): String? =
        ThreadIdResolver.resolveLocalRootId(eventId, ::threadMemberRef)

    /**
     * Normalizes an incoming reply reference (raw rumor e-tag, which may
     * carry the recipient-wrap id of our own root) to the local root id
     * before insertion. [selfRootHint] is the local root id own replies
     * carry as an extra rumor tag — it both resolves the reference after a
     * wipe-and-refetch and is the preferred unresolved fallback (see
     * [ThreadIdResolver.normalizeReplyToId]).
     */
    fun normalizeReplyToId(rawReplyToId: String?, selfRootHint: String? = null): String? =
        ThreadIdResolver.normalizeReplyToId(rawReplyToId, selfRootHint, ::threadMemberRef)

    /**
     * Re-learns a wiped thread anchor from an ingested own reply that
     * carried the (wire id, local root id) pair: sets [anchorId] as the
     * recipient-wrap id of root [localRootId] when the root row exists and
     * has no anchor yet. No-op otherwise (guarded in SQL).
     */
    fun learnThreadAnchor(localRootId: String, anchorId: String) {
        if (localRootId == anchorId) return
        queries.relearnThreadAnchor(anchorId = anchorId, rootId = localRootId)
    }

    /**
     * Re-learns the anchor of root [rootId] from the raw wire e-tags its
     * already-ingested replies arrived with — covers out-of-order backfill
     * where replies were ingested before the root row existed, so
     * [learnThreadAnchor] had nothing to update at reply time.
     */
    fun relearnAnchorFromReplies(rootId: String) {
        val wireId = queries.findWireIdForRoot(rootId)
            .executeAsOneOrNull()?.reply_to_wire_id ?: return
        queries.relearnThreadAnchor(anchorId = wireId, rootId = rootId)
    }

    /**
     * Resolves the local root id, the outgoing wire e-tag id and the
     * thread type for a reply to [rootId] (which may be a stale foreign
     * id from an old notification deep-link).
     */
    fun resolveReplyContext(rootId: String): ReplyContext =
        ThreadIdResolver.resolveReplyContext(
            rootId = rootId,
            lookup = ::threadMemberRef,
            getById = { getById(it)?.toThreadMemberRef() },
            latestThreadMember = { getThread(it).lastOrNull()?.toThreadMemberRef() },
            wireIdFromReplies = {
                queries.findWireIdForRoot(it).executeAsOneOrNull()?.reply_to_wire_id
            }
        )

    private fun threadMemberRef(eventId: String): ThreadMemberRef? =
        queries.resolveThreadMember(eventId).executeAsOneOrNull()
            ?.let { ThreadMemberRef(id = it.id, replyToId = it.reply_to_id) }

    private fun Nostr_messages.toThreadMemberRef(): ThreadMemberRef = ThreadMemberRef(
        id = id,
        replyToId = reply_to_id,
        threadAnchorId = thread_anchor_id,
        type = type
    )

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

    fun deferQueued(id: String, queuedAt: Long) =
        queries.deferQueued(queuedAt = queuedAt, id = id)

    fun clearQueued(id: String) = queries.clearQueued(id)

    fun deleteExpiredQueued(cutoffTimestamp: Long) =
        queries.deleteExpiredQueued(cutoffTimestamp)
}
