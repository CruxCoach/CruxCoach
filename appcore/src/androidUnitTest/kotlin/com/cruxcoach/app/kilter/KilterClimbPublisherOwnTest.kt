package com.cruxcoach.app.kilter

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of Android's `OwnKilterClimbPublisherTest` on the real board DB and the
 * real signed community publisher (relay double): the authorship gate, the
 * in-place adoption and the publish.
 */
class KilterClimbPublisherOwnTest {
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private var activePubkey: String? = pubkey
    private val relay = FakeCommunityRelay()
    private val community = CommunityPublisher(
        boardRepository = db.repo,
        relay = relay,
        signer = LocalEventSigner(JvmHashing) { secret.copyOf() },
        hashing = JvmHashing,
        clock = FixedClock(1_790_000_000L),
        pubkeyProvider = { activePubkey },
    )
    private val own = OwnKilterClimbPublisher(db.repo, session.account, community, { activePubkey }) { 1_790_000_000L }

    private val uuid = "a30d8042aeea42ce8015239016c87769"

    @AfterTest
    fun tearDown() = db.close()

    /** A climb as the backfill writes it: Kilter origin, author recorded. */
    private fun kilterClimb(uuid: String = this.uuid, author: String? = KilterTestSession.USER_UUID) {
        db.repo.upsertClimb(
            uuid = uuid, layoutId = 1L, setter = "alice", name = "Tallakrennesvingen",
            frames = "p501r12p502r13p503r14", framesCount = 1L, isListed = 1L,
            edgeLeft = 4L, edgeRight = 100L, edgeBottom = 8L, edgeTop = 120L, createdAt = "2026-05-01 10:00:00",
        )
        db.repo.upsertClimbStat(uuid, 40L, null, null, null, null, null)
        if (author != null) db.repo.setClimbKilterAuthorUuid(uuid, author)
    }

    @Test
    fun `an own authored climb is publishable as mine`() {
        session.signIn()
        kilterClimb()
        assertTrue(own.isPublishableAsMine(uuid))
        // Format-blind: the dashed upper spelling resolves to the same row.
        assertTrue(own.isPublishableAsMine("A30D8042-AEEA-42CE-8015-239016C87769"))
    }

    @Test
    fun `a climb someone else set is not mine`() {
        session.signIn()
        kilterClimb(author = "someone-else")
        assertFalse(own.isPublishableAsMine(uuid))
    }

    @Test
    fun `a curated climb with unknown author is not mine`() {
        session.signIn()
        kilterClimb(author = null)
        assertFalse(own.isPublishableAsMine(uuid))
    }

    @Test
    fun `without a connected Kilter account nothing is mine`() = runBlocking {
        kilterClimb()
        assertFalse(own.isPublishableAsMine(uuid))
        assertEquals("notAuthor", own.publish(uuid).code)
        assertTrue(relay.published.isEmpty())
    }

    @Test
    fun `publishing adopts the climb in place and sends it to the community`() = runBlocking {
        session.signIn()
        kilterClimb()
        val outcome = own.publish(uuid)
        assertEquals("published", outcome.code)
        val event = relay.ofKind(30078).single()
        assertEquals(pubkey, event.pubkey)
        assertTrue(event.tags.any { it.size > 1 && it[0] == "d" && it[1].contains(uuid) }, event.tags.toString())
        // Same uuid, now an own community climb; Kilter already has it.
        assertEquals("synced", db.kilterStatus(uuid))
        assertEquals("published_nostr", db.repo.getMyClimbs(pubkey).single { it.uuid == uuid }.syncStatus)
        assertFalse(own.isPublishableAsMine(uuid))
        assertEquals("alreadyPublished", own.publish(uuid).code)
        assertEquals(1, relay.ofKind(30078).size)
    }

    @Test
    fun `a foreign climb is refused before anything is sent`() = runBlocking {
        session.signIn()
        kilterClimb(author = "someone-else")
        assertEquals("notAuthor", own.publish(uuid).code)
        assertTrue(relay.published.isEmpty())
        assertEquals(null, db.kilterStatus(uuid))
    }

    @Test
    fun `a missing Nostr identity is reported before converting`() = runBlocking {
        session.signIn()
        kilterClimb()
        activePubkey = null
        assertEquals("noNostrIdentity", own.publish(uuid).code)
        assertTrue(db.repo.getMyClimbs(pubkey).none { it.uuid == uuid })
    }

    @Test
    fun `no relay accepting is a failure that stays retryable`() = runBlocking {
        session.signIn()
        kilterClimb()
        relay.acceptCount = 0
        assertEquals("failed", own.publish(uuid).code)
        assertTrue(own.isPublishableAsMine(uuid))
    }
}
