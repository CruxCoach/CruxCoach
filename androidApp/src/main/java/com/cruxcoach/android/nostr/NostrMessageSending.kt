package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.nostr.model.NostrRecipient

/**
 * Quartz-free sending facade over [NostrMessageSender] — same Java-21
 * rationale as [NostrIdentity]: the concrete sender cannot be loaded or
 * instrumented on the Java-17 unit-test JVM, so callers (ViewModels, the
 * offline queue) depend on this interface and JVM tests substitute fakes.
 *
 * Bound to [NostrMessageSender] in AppModule.
 */
interface NostrMessageSending {

    /**
     * Builds NIP-17 gift wraps locally (fast, no network) and returns
     * [SendResult.Queued] with the serialized wraps so the caller can
     * persist them and return to the UI immediately.
     *
     * @param replyToId WIRE id for the `["e", …, "reply"]` tag. For replies
     *  to an own root this must be the root's recipient-wrap id
     *  (thread_anchor_id) — the id the other side stored the root under —
     *  NOT the local self-wrap row id, which the recipient has never seen.
     * @param selfReplyToId LOCAL (self-wrap) id of the thread root, emitted
     *  as a [NostrConfig.RUMOR_TAG_SELF_ROOT] hint tag so wipe-and-refetch
     *  flows can re-thread this reply's echo and re-learn the root's anchor.
     */
    suspend fun buildMessage(
        content: String,
        type: MessageType,
        recipients: NostrRecipient = NostrRecipient.Single(NostrConfig.DEV_PUBKEY),
        subject: String? = null,
        replyToId: String? = null,
        selfReplyToId: String? = null
    ): SendResult

    /**
     * Delivers pre-built wrap JSONs to relays (includes a random 2-10s
     * timing-correlation delay). Returns true if at least one relay accepted.
     * Call from an app-scoped context (see [MessageDeliveryCoordinator]) so a
     * screen exit cannot cancel the delay mid-flight.
     */
    suspend fun deliverWraps(eventJsons: String): Boolean

    /** Retries sending queued events without the extra delay. */
    suspend fun retrySend(eventJsons: String): Boolean
}
