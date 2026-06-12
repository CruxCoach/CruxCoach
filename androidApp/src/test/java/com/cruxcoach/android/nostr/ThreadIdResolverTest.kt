package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers thread-id resolution across the NIP-17 wrap asymmetry: every
 * message exists under a self-wrap id (local row id) and a recipient-wrap
 * id (thread_anchor_id), and references may arrive carrying either.
 *
 * The secure DB (SQLCipher) is not plain-JVM testable, so the store is a
 * hand-rolled in-memory fake mirroring the resolveThreadMember / getById /
 * getThread query semantics from NostrMessages.sq.
 */
class ThreadIdResolverTest {

    /** In-memory stand-in for the nostr_messages queries used by resolution. */
    private class FakeMessageStore(private val rows: List<ThreadMemberRef>) {
        /** Mirrors resolveThreadMember: id = ? OR thread_anchor_id = ?. */
        fun lookup(eventId: String): ThreadMemberRef? =
            rows.firstOrNull { it.id == eventId || it.threadAnchorId == eventId }

        fun getById(id: String): ThreadMemberRef? = rows.firstOrNull { it.id == id }

        /** Mirrors getThread ORDER BY created_at ASC → last = latest member. */
        fun latestThreadMember(rootId: String): ThreadMemberRef? {
            val anchor = getById(rootId)?.threadAnchorId
            return rows.lastOrNull {
                it.id == rootId || it.replyToId == rootId ||
                    (anchor != null && it.replyToId == anchor)
            }
        }

        fun resolveReplyContext(rootId: String): ReplyContext =
            ThreadIdResolver.resolveReplyContext(
                rootId = rootId,
                lookup = ::lookup,
                getById = ::getById,
                latestThreadMember = ::latestThreadMember
            )
    }

    // Local self-wrap ids vs the recipient-wrap ids the dashboard knows.
    private val rootLocalId = "root-self-wrap"
    private val rootAnchorId = "root-recipient-wrap"
    private val replyLocalId = "reply-self-wrap"

    private val store = FakeMessageStore(
        listOf(
            ThreadMemberRef(
                id = rootLocalId,
                replyToId = null,
                threadAnchorId = rootAnchorId,
                type = MessageType.FEATURE.label
            ),
            // Dev reply ingested under the wrap id WE received; its e-tag
            // already normalized to the local root id.
            ThreadMemberRef(
                id = "dev-reply-wrap",
                replyToId = rootLocalId,
                threadAnchorId = null,
                type = MessageType.FEATURE.label
            ),
            // Our own reply: local row under self-wrap id, anchored to its
            // own recipient-wrap id.
            ThreadMemberRef(
                id = replyLocalId,
                replyToId = rootLocalId,
                threadAnchorId = "reply-recipient-wrap",
                type = MessageType.FEATURE.label
            )
        )
    )

    // ── resolveLocalRootId / normalizeReplyToId (ingest, PATH A) ──

    @Test
    fun `resolveLocalRootId maps recipient-wrap anchor id to local root id`() {
        assertEquals(
            rootLocalId,
            ThreadIdResolver.resolveLocalRootId(rootAnchorId, store::lookup)
        )
    }

    @Test
    fun `resolveLocalRootId hops one reply level to the root`() {
        // Reference to a reply (by either of its ids) resolves to the root.
        assertEquals(
            rootLocalId,
            ThreadIdResolver.resolveLocalRootId(replyLocalId, store::lookup)
        )
        assertEquals(
            rootLocalId,
            ThreadIdResolver.resolveLocalRootId("reply-recipient-wrap", store::lookup)
        )
    }

    @Test
    fun `resolveLocalRootId returns null for unknown ids`() {
        assertNull(ThreadIdResolver.resolveLocalRootId("unknown", store::lookup))
    }

    @Test
    fun `normalizeReplyToId maps anchor reference to local root and falls back to raw for unknown ids`() {
        assertEquals(
            rootLocalId,
            ThreadIdResolver.normalizeReplyToId(rootAnchorId, lookup = store::lookup)
        )
        // Unknown reference (root not ingested yet) is preserved as-is.
        assertEquals(
            "never-seen",
            ThreadIdResolver.normalizeReplyToId("never-seen", lookup = store::lookup)
        )
        assertNull(ThreadIdResolver.normalizeReplyToId(null, lookup = store::lookup))
    }

    @Test
    fun `normalizeReplyToId resolves through the self-root hint when the raw wire id is unknown`() {
        // Wipe-and-refetch: the re-hydrated root row lost its anchor, so the
        // raw recipient-wrap reference no longer resolves — but the hint
        // carries the local root id directly.
        val wipedStore = FakeMessageStore(
            listOf(
                ThreadMemberRef(
                    id = rootLocalId,
                    replyToId = null,
                    threadAnchorId = null, // anchor wiped
                    type = MessageType.FEATURE.label
                )
            )
        )
        assertEquals(
            rootLocalId,
            ThreadIdResolver.normalizeReplyToId(
                rawReplyToId = rootAnchorId,
                selfRootHint = rootLocalId,
                lookup = wipedStore::lookup
            )
        )
    }

    @Test
    fun `normalizeReplyToId prefers the unresolved hint over the unresolved raw id`() {
        // Neither id resolves (root echo not backfilled yet). The hint IS
        // the local root id, so storing it threads the row correctly as
        // soon as the root row reappears under that id.
        assertEquals(
            "local-root-hint",
            ThreadIdResolver.normalizeReplyToId(
                rawReplyToId = "foreign-wire-id",
                selfRootHint = "local-root-hint",
                lookup = FakeMessageStore(emptyList())::lookup
            )
        )
    }

    // ── resolveReplyContext (sendReply, PATH B) ────────────────────

    @Test
    fun `reply context resolves type from the store independent of any UI state`() {
        val ctx = store.resolveReplyContext(rootLocalId)
        assertEquals(MessageType.FEATURE.label, ctx.typeLabel)
    }

    @Test
    fun `reply context puts the anchor id on the wire but keeps the local root id for the local row`() {
        val ctx = store.resolveReplyContext(rootLocalId)
        // Wire e-tag must carry the id the dashboard stored the root under…
        assertEquals(rootAnchorId, ctx.wireReplyToId)
        // …while the local insert keeps threading via the local root id.
        assertEquals(rootLocalId, ctx.localRootId)
    }

    @Test
    fun `reply context resolves a stale foreign root reference to the same context`() {
        // Old notification deep-link carrying the recipient-wrap id.
        val ctx = store.resolveReplyContext(rootAnchorId)
        assertEquals(rootLocalId, ctx.localRootId)
        assertEquals(rootAnchorId, ctx.wireReplyToId)
        assertEquals(MessageType.FEATURE.label, ctx.typeLabel)
    }

    @Test
    fun `reply context inherits type from the latest thread member when the root row is missing`() {
        val orphanStore = FakeMessageStore(
            listOf(
                // Replies exist (still pointing at the never-ingested root id),
                // but the root row itself is absent.
                ThreadMemberRef(
                    id = "dev-reply",
                    replyToId = "missing-root",
                    threadAnchorId = null,
                    type = MessageType.BUG.label
                )
            )
        )
        val ctx = orphanStore.resolveReplyContext("missing-root")
        // No row matches the reference itself → falls back to the raw id.
        assertEquals("missing-root", ctx.localRootId)
        // No root row → no anchor id; wire falls back to the local root id.
        assertEquals("missing-root", ctx.wireReplyToId)
        assertEquals(MessageType.BUG.label, ctx.typeLabel)
    }

    @Test
    fun `reply context yields null type for a fully unknown root so callers can warn`() {
        val ctx = FakeMessageStore(emptyList()).resolveReplyContext("ghost")
        assertEquals("ghost", ctx.localRootId)
        assertEquals("ghost", ctx.wireReplyToId)
        assertNull(ctx.typeLabel)
    }

    @Test
    fun `reply context recovers the wire id from replies when the root anchor was wiped`() {
        // Wipe-and-refetch: root re-hydrated without an anchor; the raw
        // e-tags its replies arrived with still know the recipient-wrap id.
        val wipedRoot = ThreadMemberRef(
            id = rootLocalId,
            replyToId = null,
            threadAnchorId = null,
            type = MessageType.FEATURE.label
        )
        val ctx = ThreadIdResolver.resolveReplyContext(
            rootId = rootLocalId,
            lookup = { if (it == rootLocalId) wipedRoot else null },
            getById = { if (it == rootLocalId) wipedRoot else null },
            latestThreadMember = { wipedRoot },
            wireIdFromReplies = { if (it == rootLocalId) rootAnchorId else null }
        )
        assertEquals(rootLocalId, ctx.localRootId)
        // The recovered recipient-wrap id goes on the wire — NOT the local
        // root id, which would recreate the dashboard orphan thread.
        assertEquals(rootAnchorId, ctx.wireReplyToId)
    }
}
