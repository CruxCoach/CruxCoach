package com.cruxcoach.app.background

import com.cruxcoach.app.messages.AnnouncementTags
import com.cruxcoach.app.messages.DevContactRepository
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.notify.NotificationRoutes
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.training.Tx
import com.cruxcoach.db.secure.SecureDatabase

/** The maintainer's public notes since a time; the relay client has verified id and signature. */
fun interface MaintainerNotes {
    suspend fun since(sinceSeconds: Long): List<NostrEvent>
}

class StoredAnnouncement(val id: String, val content: String, val category: String)

/** The local announcement table. */
interface AnnouncementInbox {
    fun contains(id: String): Boolean
    fun insert(id: String, content: String, category: String, priority: String, createdAtSeconds: Long)
    fun all(): List<StoredAnnouncement>
}

class ReceivedReply(val id: String, val typeLabel: String, val content: String)

/** The maintainer conversation's inbox: fetches gift wraps and lists what the maintainer sent. */
interface DevReplies {
    suspend fun fetch(sinceSeconds: Long)
    fun received(): List<ReceivedReply>
}

/** Android's announcement switches (`announcements_enabled`, `announcement_cat_*`). */
class AnnouncementSwitches(
    val enabled: Boolean,
    val release: Boolean,
    val issue: Boolean,
    val tip: Boolean,
    val general: Boolean,
) {
    fun allows(category: String): Boolean = when (category) {
        AnnouncementTags.CATEGORY_RELEASE -> release
        AnnouncementTags.CATEGORY_ISSUE -> issue
        AnnouncementTags.CATEGORY_TIP -> tip
        else -> general
    }
}

/**
 * Port of Android's `NotificationPollWorker` (every 15 minutes, 5 minutes back-off): the
 * maintainer's announcements, then the maintainer's replies, each new one as a local
 * notification.
 *
 * Android's first leg, the offline queue drain, has nothing to drain here: the iOS send path
 * keeps an undelivered message visible as "not delivered" and never queues a signed event.
 *
 * - Announcements: `announcements_enabled` off is the privacy boundary for unattended traffic
 *   (Android `AnnouncementPollingPolicy`), so that leg asks no relay at all. Every new
 *   announcement is stored; only the categories switched on notify.
 * - Replies: wraps since the cursor minus the NIP-59 two-day window, capped at a year. Only
 *   the maintainer's own messages are kept (the repository drops every other sender). The very
 *   first poll of an identity is a silent baseline: Android's foreground subscription has seen
 *   that history long before its worker runs, while here it would turn up to a year of old
 *   replies into notifications.
 */
class NotificationPollStep(
    private val notes: MaintainerNotes,
    private val announcements: AnnouncementInbox,
    private val replies: DevReplies,
    private val notifications: NotificationScheduler,
    private val keyValues: KeyValueStore,
    private val switches: () -> AnnouncementSwitches,
    private val language: () -> String,
    private val devPubkey: String,
    /** Separates the reply cursor of one identity from the next (iOS settings are not key-scoped). */
    private val identityPrefix: String,
    private val clockMs: () -> Long,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = INTERVAL_MS

    override fun retryAfterMs(): Long = RETRY_MS

    override suspend fun enabled(trigger: String): Boolean = true

    override suspend fun run(): BackgroundStepResult {
        val gates = switches()
        val announcementsBefore = announcements.all().mapTo(HashSet()) { it.id }
        var notified = pollAnnouncements(gates)
        notified += pollReplies(gates, announcementsBefore)
        return BackgroundStepResult(true, notified)
    }

    private suspend fun pollAnnouncements(gates: AnnouncementSwitches): Int {
        if (!gates.enabled) return 0
        val sinceSeconds = keyValues.getString(KEY_LAST_ANNOUNCEMENT_CHECK)?.toLongOrNull()
            ?: (clockMs() / 1000 - DEFAULT_ANNOUNCEMENT_WINDOW_S)
        var latest = sinceSeconds
        var notified = 0
        val lang = language()
        for (event in notes.since(sinceSeconds)) {
            // Android `NostrEventPolicy`: the maintainer's key and kind 1, nothing else.
            if (event.pubkey != devPubkey || event.kind != 1) continue
            if (!AnnouncementTags.isAnnouncement(event.tags)) continue
            val category = AnnouncementTags.category(event.tags)
            if (announcements.contains(event.id)) {
                if (event.createdAt > latest) latest = event.createdAt
                continue
            }
            announcements.insert(
                id = event.id,
                content = event.content,
                category = category,
                priority = AnnouncementTags.priority(category),
                createdAtSeconds = event.createdAt,
            )
            if (gates.allows(category)) {
                notifyAnnouncement(event.id, category, AnnouncementTags.localized(event.content, lang))
                notified++
            }
            if (event.createdAt > latest) latest = event.createdAt
        }
        keyValues.putString(KEY_LAST_ANNOUNCEMENT_CHECK, latest.toString())
        return notified
    }

    private suspend fun pollReplies(gates: AnnouncementSwitches, announcementsBefore: Set<String>): Int {
        val cursorKey = "$KEY_REPLY_CURSOR_PREFIX$identityPrefix"
        val cursor = keyValues.getString(cursorKey)?.toLongOrNull()
        val startSeconds = clockMs() / 1000
        val since = maxOf((cursor ?: 0L) - NIP59_RANDOM_WINDOW_S, startSeconds - INITIAL_REPLY_WINDOW_S)
        val repliesBefore = replies.received().mapTo(HashSet()) { it.id }
        // Announcements found by the first leg are already handled; the inbox can bring more
        // (on iOS an announcement may also travel the private channel).
        val knownAnnouncements = HashSet(announcementsBefore).apply { announcements.all().forEach { add(it.id) } }
        replies.fetch(since)
        var notified = 0
        if (cursor != null) {
            for (reply in replies.received()) {
                if (reply.id in repliesBefore) continue
                notifyReply(reply)
                notified++
            }
            if (gates.enabled) {
                val lang = language()
                for (item in announcements.all()) {
                    if (item.id in knownAnnouncements || !gates.allows(item.category)) continue
                    notifyAnnouncement(item.id, item.category, AnnouncementTags.localized(item.content, lang))
                    notified++
                }
            }
        }
        // Android keeps the newest wrap time minus 60 s; the poll's start bounds every wrap
        // fetched, and the two-day window above still reaches the back-dated ones.
        keyValues.putString(cursorKey, (startSeconds - CURSOR_SAFETY_S).coerceAtLeast(0).toString())
        return notified
    }

    private fun notifyAnnouncement(id: String, category: String, content: String) {
        val title = when (category) {
            AnnouncementTags.CATEGORY_RELEASE -> Tx.t("notification_announcement_release")
            AnnouncementTags.CATEGORY_ISSUE -> Tx.t("notification_announcement_issue")
            AnnouncementTags.CATEGORY_TIP -> Tx.t("notification_announcement_tip")
            else -> Tx.plain(APP_NAME)
        }
        notifications.scheduleText(
            "$NOTIFICATION_ANNOUNCEMENT_PREFIX$id", 1, title,
            Tx.plain(content.take(ANNOUNCEMENT_PREVIEW_CHARS)), NotificationRoutes.ANNOUNCEMENTS,
        )
    }

    private fun notifyReply(reply: ReceivedReply) {
        val title = when (reply.typeLabel) {
            "bug-report" -> "notification_reply_bug"
            "feature-request" -> "notification_reply_feature"
            "crash-report" -> "notification_reply_crash"
            else -> "notification_reply_chat"
        }
        notifications.scheduleText(
            "$NOTIFICATION_REPLY_PREFIX${reply.id}", 1, Tx.t(title),
            Tx.plain(reply.content.take(REPLY_PREVIEW_CHARS)), NotificationRoutes.DEV_CHAT,
        )
    }

    companion object {
        const val CODE = "notification_poll"
        const val INTERVAL_MS = 15L * 60 * 1000
        const val RETRY_MS = 5L * 60 * 1000

        /** Android's `last_announcement_check`, epoch seconds. */
        const val KEY_LAST_ANNOUNCEMENT_CHECK = "last_announcement_check"
        const val KEY_REPLY_CURSOR_PREFIX = "bg_refresh_reply_cursor_"
        const val NOTIFICATION_ANNOUNCEMENT_PREFIX = "cc-announcement-"
        const val NOTIFICATION_REPLY_PREFIX = "cc-reply-"

        /** Without a check yet: the last day, as Android. */
        const val DEFAULT_ANNOUNCEMENT_WINDOW_S = 86_400L
        /** NIP-59 lets a gift wrap's created_at lie up to two days in the past. */
        const val NIP59_RANDOM_WINDOW_S = 2L * 24 * 60 * 60
        const val INITIAL_REPLY_WINDOW_S = 365L * 24 * 60 * 60
        const val CURSOR_SAFETY_S = 60L
        const val ANNOUNCEMENT_PREVIEW_CHARS = 200
        const val REPLY_PREVIEW_CHARS = 100

        /** Android's title for a general announcement; a name, not a translatable text. */
        private const val APP_NAME = "CruxCoach"

        fun filter(devPubkey: String, sinceSeconds: Long): String =
            """{"kinds":[1],"authors":["$devPubkey"],"since":$sinceSeconds}"""
    }
}

/** [MaintainerNotes] over the shared relay set. */
class RelayMaintainerNotes(private val relays: RelayClient, private val devPubkey: String) : MaintainerNotes {
    override suspend fun since(sinceSeconds: Long): List<NostrEvent> =
        relays.query(NotificationPollStep.filter(devPubkey, sinceSeconds))
}

/** [AnnouncementInbox] over the secure database's `announcements` table. */
class DatabaseAnnouncementInbox(private val database: SecureDatabase) : AnnouncementInbox {
    private val queries get() = database.announcementsQueries

    override fun contains(id: String): Boolean = queries.getById(id).executeAsOneOrNull() != null

    override fun insert(id: String, content: String, category: String, priority: String, createdAtSeconds: Long) {
        // Seconds, as the iOS inbox stores and shows them.
        queries.insert(id, content, category, priority, createdAtSeconds, 0L)
    }

    override fun all(): List<StoredAnnouncement> =
        queries.getAll().executeAsList().map { StoredAnnouncement(it.id, it.content, it.category) }
}

/** [DevReplies] over the maintainer conversation's repository. */
class RepositoryDevReplies(private val repository: DevContactRepository) : DevReplies {
    override suspend fun fetch(sinceSeconds: Long) {
        repository.fetchInbox(sinceSeconds)
    }

    override fun received(): List<ReceivedReply> =
        repository.messages().filter { !it.outgoing }.map { ReceivedReply(it.id, it.type.label, it.content) }
}
