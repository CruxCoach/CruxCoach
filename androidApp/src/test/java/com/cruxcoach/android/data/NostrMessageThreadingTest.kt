package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Thread-id resolution against the REAL SecureDatabase schema (JDBC SQLite
 * driver) and the REAL [NostrMessageRepository] — covering the actual
 * `resolveThreadMember` / `relearnThreadAnchor` / `findWireIdForRoot` SQL
 * and the repository's lambda plumbing into ThreadIdResolver, which the
 * map-backed fakes in ThreadIdResolverTest cannot exercise.
 *
 * Id vocabulary (NIP-17 wrap asymmetry):
 *  - ROOT_LOCAL  = self-wrap id of an own root (local row id)
 *  - ROOT_ANCHOR = recipient-wrap id of the same root (what the dashboard
 *    stores; persisted as thread_anchor_id / arriving as raw reply e-tags)
 */
class NostrMessageThreadingTest {

    private lateinit var driver: SqlDriver
    private lateinit var repo: NostrMessageRepository

    private var clock = 0L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repo = NostrMessageRepository(SecureDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun insertRow(
        id: String,
        type: String = MessageType.FEATURE.label,
        direction: String = "sent",
        replyToId: String? = null,
        threadAnchorId: String? = null,
        replyToWireId: String? = null
    ) = repo.insert(
        id = id,
        type = type,
        direction = direction,
        content = "content-$id",
        subject = null,
        senderPubkey = if (direction == "sent") "own-pubkey" else "dev-pubkey",
        createdAt = ++clock,
        relayAccepted = true,
        read = true,
        replyToId = replyToId,
        threadAnchorId = threadAnchorId,
        replyToWireId = replyToWireId
    )

    // ── resolveLocalRootId (resolveThreadMember SQL) ───────────────────

    @Test
    fun `resolves a root by local id, anchor id, and one reply hop`() {
        insertRow(ROOT_LOCAL, threadAnchorId = ROOT_ANCHOR)
        insertRow(
            "reply-local", replyToId = ROOT_LOCAL,
            threadAnchorId = "reply-anchor", replyToWireId = ROOT_ANCHOR
        )

        assertEquals(ROOT_LOCAL, repo.resolveLocalRootId(ROOT_LOCAL))
        assertEquals(ROOT_LOCAL, repo.resolveLocalRootId(ROOT_ANCHOR))
        assertEquals(ROOT_LOCAL, repo.resolveLocalRootId("reply-local"))
        assertEquals(ROOT_LOCAL, repo.resolveLocalRootId("reply-anchor"))
        assertNull(repo.resolveLocalRootId("unknown"))
    }

    @Test
    fun `resolves a foreign root reference through a reply's wire id when the root anchor was wiped`() {
        // Wipe-and-refetch: root re-hydrated WITHOUT its anchor; the reply
        // kept the raw e-tag it arrived with.
        insertRow(ROOT_LOCAL, threadAnchorId = null)
        insertRow("reply-local", replyToId = ROOT_LOCAL, replyToWireId = ROOT_ANCHOR)

        assertEquals(ROOT_LOCAL, repo.resolveLocalRootId(ROOT_ANCHOR))
    }

    @Test
    fun `a degenerate unresolved reply row never resolves the searched id to itself`() {
        // Dev reply ingested before anything else: reply_to_id fell back to
        // the raw wire id, which equals its own reply_to_wire_id. Matching
        // it would "resolve" ROOT_ANCHOR to ROOT_ANCHOR and mask the miss.
        insertRow(
            "dev-reply", direction = "received",
            replyToId = ROOT_ANCHOR, replyToWireId = ROOT_ANCHOR
        )

        assertNull(repo.resolveLocalRootId(ROOT_ANCHOR))
    }

    @Test
    fun `direct id match wins over a stray wire-id match`() {
        insertRow("root-x")
        insertRow("other-root")
        // Pathological reply of ANOTHER thread carrying root-x's id as its
        // raw wire reference must not shadow the direct row match.
        insertRow("other-reply", replyToId = "other-root", replyToWireId = "root-x")

        assertEquals("root-x", repo.resolveLocalRootId("root-x"))
    }

    // ── normalizeReplyToId ─────────────────────────────────────────────

    @Test
    fun `normalize resolves the raw anchor reference against the real query`() {
        insertRow(ROOT_LOCAL, threadAnchorId = ROOT_ANCHOR)
        assertEquals(ROOT_LOCAL, repo.normalizeReplyToId(ROOT_ANCHOR))
        // Unknown raw id without hint is preserved for getThread's anchor clause.
        assertEquals("never-seen", repo.normalizeReplyToId("never-seen"))
        assertNull(repo.normalizeReplyToId(null))
    }

    @Test
    fun `normalize falls back to the self-root hint when neither id resolves`() {
        assertEquals(
            ROOT_LOCAL,
            repo.normalizeReplyToId(rawReplyToId = ROOT_ANCHOR, selfRootHint = ROOT_LOCAL)
        )
    }

    @Test
    fun `normalize resolves via the hint when the re-hydrated root lost its anchor`() {
        insertRow(ROOT_LOCAL, threadAnchorId = null)
        assertEquals(
            ROOT_LOCAL,
            repo.normalizeReplyToId(rawReplyToId = ROOT_ANCHOR, selfRootHint = ROOT_LOCAL)
        )
    }

    // ── anchor re-learning ─────────────────────────────────────────────

    @Test
    fun `learnThreadAnchor sets a wiped anchor but never overwrites an existing one`() {
        insertRow(ROOT_LOCAL, threadAnchorId = null)
        repo.learnThreadAnchor(ROOT_LOCAL, ROOT_ANCHOR)
        assertEquals(ROOT_ANCHOR, repo.getById(ROOT_LOCAL)?.threadAnchorId)

        repo.learnThreadAnchor(ROOT_LOCAL, "some-other-id")
        assertEquals(ROOT_ANCHOR, repo.getById(ROOT_LOCAL)?.threadAnchorId)
    }

    @Test
    fun `learnThreadAnchor ignores received rows and identical ids`() {
        insertRow("dev-root", direction = "received", threadAnchorId = null)
        repo.learnThreadAnchor("dev-root", ROOT_ANCHOR)
        assertNull(repo.getById("dev-root")?.threadAnchorId)

        insertRow(ROOT_LOCAL, threadAnchorId = null)
        repo.learnThreadAnchor(ROOT_LOCAL, ROOT_LOCAL)
        assertNull(repo.getById(ROOT_LOCAL)?.threadAnchorId)
    }

    @Test
    fun `relearnAnchorFromReplies recovers the anchor from a backfilled reply`() {
        // Out-of-order backfill: reply ingested BEFORE the root row existed,
        // so learnThreadAnchor had nothing to update at reply time.
        insertRow("reply-local", replyToId = ROOT_LOCAL, replyToWireId = ROOT_ANCHOR)
        insertRow(ROOT_LOCAL, threadAnchorId = null)

        repo.relearnAnchorFromReplies(ROOT_LOCAL)
        assertEquals(ROOT_ANCHOR, repo.getById(ROOT_LOCAL)?.threadAnchorId)
    }

    @Test
    fun `relearnAnchorFromReplies ignores replies whose wire id is the local root id`() {
        // Pre-fix sends carried the LOCAL root id on the wire — that id is
        // not an anchor and must not be "learned" as one.
        insertRow("reply-local", replyToId = ROOT_LOCAL, replyToWireId = ROOT_LOCAL)
        insertRow(ROOT_LOCAL, threadAnchorId = null)

        repo.relearnAnchorFromReplies(ROOT_LOCAL)
        assertNull(repo.getById(ROOT_LOCAL)?.threadAnchorId)
    }

    // ── resolveReplyContext (repository lambda plumbing) ───────────────

    @Test
    fun `reply context puts the anchor on the wire and the local id on the row`() {
        insertRow(ROOT_LOCAL, threadAnchorId = ROOT_ANCHOR)
        // Stale foreign deep-link reference resolves to the same context.
        val ctx = repo.resolveReplyContext(ROOT_ANCHOR)
        assertEquals(ROOT_LOCAL, ctx.localRootId)
        assertEquals(ROOT_ANCHOR, ctx.wireReplyToId)
        assertEquals(MessageType.FEATURE.label, ctx.typeLabel)
    }

    @Test
    fun `reply context recovers the wire id from replies when the anchor was wiped`() {
        insertRow(ROOT_LOCAL, threadAnchorId = null)
        insertRow("reply-local", replyToId = ROOT_LOCAL, replyToWireId = ROOT_ANCHOR)

        val ctx = repo.resolveReplyContext(ROOT_LOCAL)
        assertEquals(ROOT_LOCAL, ctx.localRootId)
        assertEquals(ROOT_ANCHOR, ctx.wireReplyToId)
    }

    @Test
    fun `reply context inherits the type from the latest thread member when the root is missing`() {
        insertRow(
            "dev-reply", type = MessageType.BUG.label, direction = "received",
            replyToId = "missing-root"
        )
        val ctx = repo.resolveReplyContext("missing-root")
        assertEquals("missing-root", ctx.localRootId)
        assertEquals(MessageType.BUG.label, ctx.typeLabel)
    }

    @Test
    fun `reply context yields null type for a fully unknown root`() {
        val ctx = repo.resolveReplyContext("ghost")
        assertEquals("ghost", ctx.localRootId)
        assertEquals("ghost", ctx.wireReplyToId)
        assertNull(ctx.typeLabel)
    }

    // ── getThread anchor clause interaction ────────────────────────────

    @Test
    fun `dev reply stored under the raw anchor id joins the thread once the anchor is re-learned`() {
        // Dev reply backfilled first: unresolvable, stored under the raw id.
        insertRow(
            "dev-reply", direction = "received",
            replyToId = ROOT_ANCHOR, replyToWireId = ROOT_ANCHOR
        )
        insertRow(ROOT_LOCAL, threadAnchorId = null)
        assertEquals(listOf(ROOT_LOCAL), repo.getThread(ROOT_LOCAL).map { it.id })

        // Anchor re-learned (e.g. from an own-reply echo carrying the pair):
        // getThread's anchor clause now picks the dev reply up — no row rewrite.
        repo.learnThreadAnchor(ROOT_LOCAL, ROOT_ANCHOR)
        assertEquals(
            listOf("dev-reply", ROOT_LOCAL).sorted(),
            repo.getThread(ROOT_LOCAL).map { it.id }.sorted()
        )
    }

    private companion object {
        const val ROOT_LOCAL = "root-self-wrap"
        const val ROOT_ANCHOR = "root-recipient-wrap"
    }
}
