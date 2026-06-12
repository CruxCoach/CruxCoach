package com.cruxcoach.android.notification

import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.nostr.model.DecryptedMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared per-message DB ingest used by BOTH delivery paths — the
 * [NostrPushCoordinator] live subscription and the [NotificationPollWorker]
 * periodic poll — so thread-id normalization, anchor re-learning and the
 * notification deep-link route can never drift apart between them.
 *
 * Responsibilities per ingested [DecryptedMessage]:
 *  1. Anchor re-learning: own reply echoes carry the (wire id, local root
 *     id) pair — the only surviving source of a root's recipient-wrap id
 *     after a wipe-and-refetch (the recipient wrap itself is p-tagged to
 *     the dev and never reaches us again).
 *  2. Reply normalization: the raw e-tag may reference our root by its
 *     RECIPIENT-wrap id (the dashboard only knows that one) — normalize to
 *     the local root id so the stored row and the notification route both
 *     point at a real local thread.
 *  3. Idempotent insert (INSERT OR IGNORE on the wrap id).
 *  4. Self-wrap bookkeeping: an echo proves the relay has the event, so a
 *     pre-existing queued row flips to delivered; re-ingested own ROOTS
 *     additionally re-learn their anchor from any replies that were
 *     backfilled before them (out-of-order delivery).
 *
 * Callers remain responsible for sender authorization (self-wrap / dev
 * checks), duplicate-notification suppression and posting the notification.
 */
@Singleton
class NostrMessageIngestor @Inject constructor(
    private val repository: NostrMessageRepository
) {

    /**
     * Writes [msg] to the secure DB and returns the navigation route a
     * notification for it should deep-link to: `message_thread/<localRootId>`
     * for replies (raw-id fallback when the root isn't ingested yet),
     * `dev_chat` for non-replies.
     */
    fun ingest(msg: DecryptedMessage, isSelfWrap: Boolean): String {
        // Re-learn BEFORE normalizing: when this echo carries the id pair,
        // anchoring the root first lets the raw wire id resolve directly.
        if (msg.replyToId != null && msg.selfReplyToId != null) {
            repository.learnThreadAnchor(
                localRootId = msg.selfReplyToId,
                anchorId = msg.replyToId
            )
        }

        val localReplyToId = repository.normalizeReplyToId(
            rawReplyToId = msg.replyToId,
            selfRootHint = msg.selfReplyToId
        )

        repository.insert(
            id = msg.id,
            type = msg.type.label,
            direction = if (isSelfWrap) "sent" else "received",
            content = msg.content,
            subject = msg.subject,
            senderPubkey = msg.senderPubkey,
            createdAt = msg.timestamp,
            relayAccepted = true,
            read = isSelfWrap,
            replyToId = localReplyToId,
            // Keep the raw e-tag: replies are the only durable carrier of
            // the root's recipient-wrap id (anchor re-learning + send-time
            // wire-id recovery after a wipe).
            replyToWireId = msg.replyToId
        )

        if (isSelfWrap) {
            // Self-wrap echoes prove the relay has the event. Flip any
            // pre-existing queued row (INSERT OR IGNORE left it untouched)
            // to delivered so the UI transitions queued → delivered.
            repository.clearQueued(msg.id)
            if (msg.replyToId == null) {
                // Re-ingested own ROOT: its recipient-wrap id is not
                // recoverable from the echo itself — re-learn it from any
                // reply that was backfilled before this root row existed.
                repository.relearnAnchorFromReplies(msg.id)
            }
        }

        return if (localReplyToId != null) {
            "message_thread/$localReplyToId"
        } else {
            "dev_chat"
        }
    }
}
