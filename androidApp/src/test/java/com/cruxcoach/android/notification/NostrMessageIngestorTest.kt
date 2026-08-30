package com.cruxcoach.android.notification

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.nostr.model.DecryptedMessage
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared ingest wiring (used by BOTH NostrPushCoordinator and
 * NotificationPollWorker) against the REAL SecureDatabase schema and
 * repository: reply normalization, notification deep-link route
 * construction, queued → delivered bookkeeping, and — the regression this
 * guards against — the wipe-and-refetch flow (recovery migrations v1-v4,
 * reinstalls, 365-day backfill), where every thread_anchor_id is lost and
 * must be re-learned from the re-ingested echoes in WHATEVER order the
 * relays deliver them.
 */
class NostrMessageIngestorTest {

    private lateinit var driver: SqlDriver
    private lateinit var repo: NostrMessageRepository
    private lateinit var ingestor: NostrMessageIngestor

    private var clock = 0L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repo = NostrMessageRepository(SecureDatabase(driver))
        ingestor = NostrMessageIngestor(repo)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun msg(
        id: String,
        sender: String = OWN_PUBKEY,
        type: MessageType = MessageType.FEATURE,
        replyToId: String? = null,
        selfReplyToId: String? = null
    ) = DecryptedMessage(
        id = id,
        content = "body-$id",
        type = type,
        senderPubkey = sender,
        timestamp = ++clock,
        wrapTimestamp = clock,
        subject = null,
        replyToId = replyToId,
        selfReplyToId = selfReplyToId
    )

    /** Seeds an own root the way sendMessage stores it (anchor known). */
    private fun seedSentRoot(id: String = ROOT_LOCAL, anchorId: String? = ROOT_ANCHOR) {
        repo.insert(
            id = id,
            type = MessageType.FEATURE.label,
            direction = "sent",
            content = "root",
            subject = "Subject",
            senderPubkey = OWN_PUBKEY,
            createdAt = ++clock,
            relayAccepted = true,
            read = true,
            replyToId = null,
            threadAnchorId = anchorId
        )
    }

    // ── PATH A: dev reply referencing the root by its recipient-wrap id ──

    @Test
    fun `dev reply carrying the recipient-wrap id is normalized to the local root and routed there`() {
        seedSentRoot()
        val route = ingestor.ingest(
            msg("dev-reply", sender = DEV_PUBKEY, replyToId = ROOT_ANCHOR),
            isSelfWrap = false
        )

        assertEquals("message_thread/$ROOT_LOCAL", route)
        val row = repo.getById("dev-reply")!!
        assertEquals(ROOT_LOCAL, row.replyToId)
        assertEquals(ROOT_ANCHOR, row.replyToWireId)
        assertEquals("received", row.direction)
        assertFalse(row.isRead)
    }

    @Test
    fun `non-reply routes to dev_chat`() {
        val route = ingestor.ingest(
            msg("dev-msg", sender = DEV_PUBKEY, type = MessageType.CHAT),
            isSelfWrap = false
        )
        assertEquals("dev_chat", route)
    }

    @Test
    fun `unresolvable reference keeps the raw id so the reference survives until the root arrives`() {
        val route = ingestor.ingest(
            msg("dev-reply", sender = DEV_PUBKEY, replyToId = "not-ingested-yet"),
            isSelfWrap = false
        )
        assertEquals("message_thread/not-ingested-yet", route)
        assertEquals("not-ingested-yet", repo.getById("dev-reply")?.replyToId)
    }

    // ── self-wrap bookkeeping ─────────────────────────────────────────

    @Test
    fun `self-wrap echo flips a queued row to delivered`() {
        seedSentRoot()
        repo.markQueued(ROOT_LOCAL, queuedAt = 123L, eventJson = "{}")

        ingestor.ingest(msg(ROOT_LOCAL), isSelfWrap = true)

        val row = repo.getById(ROOT_LOCAL)!!
        assertNull(row.queuedAt)
        assertTrue(row.isRelayAccepted)
        // INSERT OR IGNORE must keep the original row (anchor untouched).
        assertEquals(ROOT_ANCHOR, row.threadAnchorId)
    }

    // ── wipe-and-refetch regression (recovery migrations, reinstall) ──

    /**
     * Echo set re-delivered by the relays after a wipe. The recipient wrap
     * of the root is p-tagged to the dev and never reaches us — the anchor
     * is only recoverable from the reply echoes carrying the id pair.
     */
    private fun refetchEchoes(): Map<String, () -> String> = mapOf(
        "root" to {
            ingestor.ingest(msg(ROOT_LOCAL), isSelfWrap = true)
        },
        "ownReply" to {
            ingestor.ingest(
                msg(OWN_REPLY, replyToId = ROOT_ANCHOR, selfReplyToId = ROOT_LOCAL),
                isSelfWrap = true
            )
        },
        "devReply" to {
            ingestor.ingest(
                msg(DEV_REPLY, sender = DEV_PUBKEY, replyToId = ROOT_ANCHOR),
                isSelfWrap = false
            )
        }
    )

    private fun assertThreadFullyRecovered(order: List<String>) {
        assertEquals(
            listOf(DEV_REPLY, OWN_REPLY, ROOT_LOCAL),
            repo.getThread(ROOT_LOCAL).map { it.id }.sorted(),
            "thread membership after refetch order $order"
        )
        assertEquals(
            ROOT_ANCHOR,
            repo.getById(ROOT_LOCAL)?.threadAnchorId,
            "re-learned anchor after refetch order $order"
        )
        // New replies must go out with the dashboard-known id on the wire
        // again — NOT the local root id (the PATH B orphan bug).
        val ctx = repo.resolveReplyContext(ROOT_LOCAL)
        assertEquals(ROOT_LOCAL, ctx.localRootId, "local root after order $order")
        assertEquals(ROOT_ANCHOR, ctx.wireReplyToId, "wire id after order $order")
        assertEquals(MessageType.FEATURE.label, ctx.typeLabel, "type after order $order")
    }

    @Test
    fun `wipe-and-refetch recovers the full thread in every relay delivery order`() {
        val orders = listOf(
            listOf("root", "ownReply", "devReply"), // causal order
            listOf("root", "devReply", "ownReply"),
            listOf("ownReply", "devReply", "root"), // replies first
            listOf("ownReply", "root", "devReply"),
            listOf("devReply", "ownReply", "root"), // dev reply first
            listOf("devReply", "root", "ownReply")
        )
        for (order in orders) {
            setUp() // fresh DB per permutation
            // Pre-wipe state exactly as sendMessage created it.
            seedSentRoot()
            repo.insert(
                id = OWN_REPLY, type = MessageType.FEATURE.label, direction = "sent",
                content = "own reply", subject = null, senderPubkey = OWN_PUBKEY,
                createdAt = ++clock, relayAccepted = true, read = true,
                replyToId = ROOT_LOCAL, threadAnchorId = "own-reply-anchor",
                replyToWireId = ROOT_ANCHOR
            )

            // Recovery migration wipes everything not queued.
            repo.deleteAllNonQueued()
            assertEquals(0, repo.getAll().size)

            val echoes = refetchEchoes()
            order.forEach { echoes.getValue(it).invoke() }

            assertThreadFullyRecovered(order)
            tearDown()
        }
    }

    @Test
    fun `re-ingested own reply is visible in its thread even before the root echo arrives`() {
        // The exact regression: pre-fix the reply's e-tag was the local root
        // id and re-threaded trivially; post-fix it is the recipient-wrap id,
        // so without the self-root hint the row would match neither the
        // thread view nor the root lists.
        ingestor.ingest(
            msg(OWN_REPLY, replyToId = ROOT_ANCHOR, selfReplyToId = ROOT_LOCAL),
            isSelfWrap = true
        )

        val row = repo.getById(OWN_REPLY)!!
        assertEquals(ROOT_LOCAL, row.replyToId)
        assertEquals(ROOT_ANCHOR, row.replyToWireId)
        // Root echo arrives later — the reply is already threaded under it.
        ingestor.ingest(msg(ROOT_LOCAL), isSelfWrap = true)
        assertEquals(
            listOf(OWN_REPLY, ROOT_LOCAL),
            repo.getThread(ROOT_LOCAL).map { it.id }.sorted()
        )
    }

    private companion object {
        const val OWN_PUBKEY = "own-pubkey"
        const val DEV_PUBKEY = "dev-pubkey"
        const val ROOT_LOCAL = "root-self-wrap"
        const val ROOT_ANCHOR = "root-recipient-wrap"
        const val OWN_REPLY = "own-reply-self-wrap"
        const val DEV_REPLY = "dev-reply-wrap"
    }
}
