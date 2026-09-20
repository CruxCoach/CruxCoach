package com.cruxcoach.app.messages

import com.cruxcoach.app.nostr.KIND_GIFT_WRAP
import com.cruxcoach.app.nostr.Nip17
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.nostr.Rumor
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.db.secure.SecureDatabase

/** What a message to the maintainer is about. Android's `MessageType`. */
enum class DevMessageType(val label: String, val prefix: String?) {
    CRASH("crash-report", "[CRASH]"),
    BUG("bug-report", "[BUG]"),
    FEATURE("feature-request", "[FEATURE]"),
    CHAT("chat", null),
}

/** One row of a conversation with the maintainer. */
class DevMessage(
    val id: String,
    val type: DevMessageType,
    /** True for something this device sent. */
    val outgoing: Boolean,
    val content: String,
    val subject: String?,
    val createdAt: Long,
    val relayAccepted: Boolean,
    val read: Boolean,
    val replyToId: String?,
)

/**
 * Bug reports, feature requests and replies, as NIP-17 private messages to the
 * project's maintainer key.
 *
 * Only the transport is here; [Nip17] does the sealing. What this class owns
 * is the local record: a sent message is stored whether or not a relay took
 * it, so an unsent report is visible and can be retried instead of vanishing.
 */
class DevContactRepository(
    private val database: SecureDatabase,
    private val relays: RelayClient,
    private val hashing: Hashing,
    private val nip44: com.cruxcoach.app.platform.Nip44Cipher,
    private val clock: WallClock,
    private val devPubkey: String,
    private val secretKeyProvider: () -> ByteArray?,
) {
    private val queries get() = database.nostrMessagesQueries
    private val announcementQueries get() = database.announcementsQueries

    /** Result of trying to send; [accepted] is 0 when nothing was stored. */
    class SendOutcome(val messageId: String, val attempted: Int, val accepted: Int)

    /**
     * Seals [content] to the maintainer and publishes both wraps.
     *
     * The local row is written before the network call so a failed send is
     * still a visible message; [SendOutcome.accepted] tells the screen whether
     * anyone actually has it.
     */
    suspend fun send(
        type: DevMessageType,
        content: String,
        subject: String? = null,
        replyToId: String? = null,
    ): SendOutcome? {
        val secret = secretKeyProvider() ?: return null
        val body = type.prefix?.let { "$it $content" } ?: content
        val tags = buildList {
            add(listOf("L", TYPE_NAMESPACE))
            add(listOf("l", type.label, TYPE_NAMESPACE))
            if (subject != null) add(listOf("subject", subject))
            if (replyToId != null) add(listOf("e", replyToId, "", "reply"))
        }
        val wrapped = try {
            Nip17.wrap(hashing, nip44, secret, devPubkey, body, tags, clock.epochSeconds())
        } finally {
            secret.fill(0)
        } ?: return null
        val (rumor, wraps) = wrapped
        // The wrap addressed to the maintainer is the id their client will
        // quote in a reply, so it is what a thread has to be anchored on.
        val anchor = wraps.firstOrNull { it.firstTagValue("p") == devPubkey }?.id

        store(rumor, type, outgoing = true, relayAccepted = false, replyToId = replyToId, anchor = anchor)

        var attempted = 0
        var accepted = 0
        for (wrap in wraps) {
            val (a, ok) = relays.publish(wrap)
            attempted += a
            accepted += ok
        }
        if (accepted > 0) queries.updateRelayAccepted(rumor.id)
        return SendOutcome(rumor.id, attempted, accepted)
    }

    /**
     * Fetches gift wraps addressed to this identity and stores the ones the
     * maintainer sealed. Returns how many new messages were stored.
     *
     * A wrap from anyone else is dropped on purpose: this inbox is the
     * maintainer conversation, not an open DM surface.
     */
    suspend fun fetchInbox(sinceSeconds: Long = 0): Int {
        val secret = secretKeyProvider() ?: return 0
        val pubkey = com.cruxcoach.app.nostr.NostrKeys.publicKeyHex(secret) ?: run {
            secret.fill(0)
            return 0
        }
        val since = if (sinceSeconds > 0) ""","since":$sinceSeconds""" else ""
        val filter = """{"kinds":[$KIND_GIFT_WRAP],"#p":["$pubkey"],"limit":$INBOX_LIMIT$since}"""
        val events = try {
            relays.query(filter)
        } catch (e: kotlinx.coroutines.CancellationException) {
            secret.fill(0)
            throw e
        } catch (_: Exception) {
            emptyList<NostrEvent>()
        }
        var stored = 0
        try {
            for (wrap in events) {
                val rumor = Nip17.unwrap(hashing, nip44, secret, wrap) ?: continue
                // Only the maintainer's own messages belong in this inbox.
                if (rumor.pubkey != devPubkey) continue
                // An announcement travels the same private channel as a
                // reply; the NIP-32 namespace label decides where it lands.
                if (AnnouncementTags.isAnnouncement(rumor.tags)) {
                    if (announcementQueries.getById(rumor.id).executeAsOneOrNull() != null) continue
                    val category = AnnouncementTags.category(rumor.tags)
                    announcementQueries.insert(
                        id = rumor.id,
                        content = rumor.content,
                        category = category,
                        priority = AnnouncementTags.priority(category),
                        created_at = rumor.createdAt,
                        read = 0L,
                    )
                    stored++
                    continue
                }
                if (queries.getById(rumor.id).executeAsOneOrNull() != null) continue
                val type = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "l" }
                    ?.get(1)?.let { label -> DevMessageType.entries.firstOrNull { it.label == label } }
                    ?: DevMessageType.CHAT
                val wireReply = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                // A reply quotes the wrap id the maintainer received, not the
                // local message id: resolve it back to the thread we know.
                val localRoot = wireReply?.let {
                    queries.resolveThreadMember(it).executeAsOneOrNull()
                        ?.let { row -> row.reply_to_id ?: row.id }
                }
                store(
                    rumor, type, outgoing = false, relayAccepted = true,
                    replyToId = localRoot, anchor = null, wireReplyId = wireReply,
                )
                stored++
            }
        } finally {
            secret.fill(0)
        }
        return stored
    }

    fun messages(): List<DevMessage> = queries.getAll().executeAsList().map { row ->
        DevMessage(
            id = row.id,
            type = DevMessageType.entries.firstOrNull { it.label == row.type } ?: DevMessageType.CHAT,
            outgoing = row.direction == "sent",
            content = row.content,
            subject = row.subject,
            createdAt = row.created_at,
            relayAccepted = row.relay_accepted == 1L,
            read = row.read == 1L,
            replyToId = row.reply_to_id,
        )
    }

    fun unreadCount(): Long = queries.getTotalUnreadCount().executeAsOne()

    /** Project announcements, newest first. */
    fun announcements(language: String): List<Announcement> =
        announcementQueries.getAll().executeAsList().map { row ->
            Announcement(
                id = row.id,
                content = AnnouncementTags.localized(row.content, language),
                category = row.category,
                priority = row.priority,
                createdAt = row.created_at,
                read = row.read == 1L,
            )
        }

    fun unreadAnnouncements(): Long = announcementQueries.getUnreadCount().executeAsOne()

    fun markAnnouncementRead(id: String) = announcementQueries.markRead(id)

    fun markRead(id: String) = queries.markRead(id)

    private fun store(
        rumor: Rumor,
        type: DevMessageType,
        outgoing: Boolean,
        relayAccepted: Boolean,
        replyToId: String?,
        anchor: String?,
        wireReplyId: String? = null,
    ) {
        queries.insert(
            id = rumor.id,
            type = type.label,
            direction = if (outgoing) "sent" else "received",
            content = rumor.content,
            subject = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "subject" }?.get(1),
            sender_pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            relay_accepted = if (relayAccepted) 1L else 0L,
            // Something this device sent has already been seen by the person
            // who wrote it.
            read = if (outgoing) 1L else 0L,
            reply_to_id = replyToId,
            thread_anchor_id = anchor,
            reply_to_wire_id = wireReplyId,
        )
    }

    companion object {
        /** Label namespace Android tags its reports with. */
        const val TYPE_NAMESPACE = "com.cruxcoach.type"
        private const val INBOX_LIMIT = 200
    }
}
