package com.cruxcoach.app.community

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.creator.ClimbDraftStore
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.CommunityClimbTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The publisher signs with a real secp256k1 key and writes to a real in-memory
 * BoardDB; only the relay is a double, so what the tests inspect is the exact
 * event a relay would receive.
 */
class CommunityPublisherTest {
    private val database = BrowseTestDb()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val clock = FixedClock(1_790_000_000L)
    private val relay = FakeCommunityRelay()
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }

    private val drafts = ClimbDraftStore(
        boardRepository = database.boardRepo,
        hashing = JvmHashing,
        clock = clock,
        pubkeyProvider = { pubkey },
    )

    private fun publisher(eventSigner: com.cruxcoach.app.backup.EventSigner = signer) = CommunityPublisher(
        boardRepository = database.boardRepo,
        relay = relay,
        signer = eventSigner,
        hashing = JvmHashing,
        clock = clock,
        pubkeyProvider = { pubkey },
    )

    private fun deleter() = CommunityDeleter(
        boardRepository = database.boardRepo,
        relay = relay,
        signer = signer,
        hashing = JvmHashing,
        clock = clock,
        pubkeyProvider = { pubkey },
    )

    @AfterTest
    fun tearDown() = database.close()

    private val draft = ClimbEditorState(
        selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
        name = "Schulterzucken",
        description = "Kante rechts",
        setterGradeId = 21,
        angle = 40,
    )

    private fun savedDraft(state: ClimbEditorState = draft): String =
        drafts.saveDraft(state, layoutId = 1L)!!

    private fun tagValues(event: NostrEvent, name: String): List<List<String>> =
        event.tags.filter { it.isNotEmpty() && it[0] == name }

    @Test
    fun publishesAStableDTagWhoseCreatedAtStrictlyAdvancesOnEveryRepublish() = runBlocking {
        val uuid = savedDraft()
        val publisher = publisher()

        val first = publisher.publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.NONE, first.failure)
        val second = publisher.publish(uuid, 1L, BoardBrand.KILTER, draft.copy(name = "Schulterzucken v2"), "12 x 12")
        val third = publisher.publish(uuid, 1L, BoardBrand.KILTER, draft.copy(name = "Schulterzucken v3"), "12 x 12")

        // One replaceable address for the life of the climb.
        assertEquals("cruxcoach:climb:${pubkey.take(8)}:$uuid", first.dTag)
        assertEquals(first.dTag, second.dTag)
        assertEquals(first.dTag, third.dTag)

        // The clock never moves in this test, so any advance can only come from
        // the monotonic clamp — which is exactly what a same-second typo fix hits.
        val events = relay.ofKind(30078)
        assertEquals(3, events.size)
        assertTrue(events[1].createdAt > events[0].createdAt)
        assertTrue(events[2].createdAt > events[1].createdAt)
        assertEquals(clock.seconds, events[0].createdAt - 1)

        // The last emit is persisted, so a later edit or delete clamps against it.
        assertEquals(
            CommunityEventTime.isoFromEpochSeconds(events[2].createdAt),
            database.boardRepo.getClimbCreatedAt(uuid),
        )
    }

    @Test
    fun buildsTheKind30078EventAndrecordsThePublication() = runBlocking {
        val uuid = savedDraft()
        val result = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")

        val event = relay.ofKind(30078).single()
        assertEquals(result.eventId, event.id)
        assertEquals(pubkey, event.pubkey)
        assertEquals(result.dTag, event.firstTagValue("d"))
        // Kilter stays on the legacy namespace that pre-0.2.0 apps subscribe to.
        assertEquals(CommunityClimbTags.NS_CLIMB, event.firstTagValue("L"))
        assertEquals("p1r12p2r13p3r14", event.firstTagValue("frames"))
        assertEquals("1", event.firstTagValue("layout_id"))
        assertEquals("kilter", event.firstTagValue("board_brand"))
        assertTrue(event.firstTagValue("frames_hash")!!.startsWith("sha256:"))
        assertEquals(listOf("setter_grade", "21", "40"), tagValues(event, "setter_grade").single())
        assertTrue(event.content.contains("\"_v\":1"))
        assertTrue(event.content.contains("\"pubkey_prefix\":\"${pubkey.take(8)}\""))

        // The row leaves the drafts drawer and carries its publication.
        assertEquals(emptyList(), database.boardRepo.getDraftClimbs(pubkey, "kilter"))
        val row = database.boardRepo.getMyClimbs(pubkey).single()
        assertEquals("published_nostr", row.syncStatus)
        assertEquals(event.id, row.nostrEventId)
        assertEquals(result.dTag, row.nostrDTag)
    }

    @Test
    fun nonKilterBoardsRideTheBackCompatNamespace() = runBlocking {
        val tension = draft.copy(boardBrand = BoardBrand.TENSION.wireValue)
        val uuid = drafts.saveDraft(tension, layoutId = 1L)!!
        publisher().publish(uuid, 1L, BoardBrand.TENSION, tension, "8 x 12")

        val event = relay.ofKind(30078).single()
        // A pre-0.2.0 app's single-label filter must not match this, or the
        // climb lands there as a broken Kilter climb (the layout ids collide).
        assertEquals(CommunityClimbTags.NS_CLIMB_V2, event.firstTagValue("L"))
        assertEquals("tension", event.firstTagValue("board_brand"))
    }

    @Test
    fun neverPublishesAnEventTheSignerCouldNotProduceOrThatDoesNotVerify() = runBlocking {
        val uuid = savedDraft()

        // 1. No signature at all.
        val refused = publisher { _, _, _, _ -> null }
            .publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.SIGNING_FAILED, refused.failure)

        // 2. A validly-signed event whose body was altered afterwards: the id no
        //    longer binds the content, so verification must reject it.
        val tampering = com.cruxcoach.app.backup.EventSigner { createdAt, kind, tags, content ->
            NostrKeys.sign(JvmHashing, secret, createdAt, kind, tags, content)
                ?.copy(content = content + " tampered")
        }
        val tampered = publisher(tampering).publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.SIGNING_FAILED, tampered.failure)

        // 3. Signed by a different key than the d-tag names.
        val otherSecret = NostrKeys.generateSecretKey(JvmHashing)!!
        val foreign = publisher(LocalEventSigner(JvmHashing) { otherSecret.copyOf() })
            .publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.SIGNING_FAILED, foreign.failure)

        // Nothing left the process and the row was never marked published.
        assertEquals(emptyList(), relay.published)
        assertEquals(1, database.boardRepo.getDraftClimbs(pubkey, "kilter").size)
        assertNull(database.boardRepo.getMyClimbs(pubkey).single().nostrEventId)
    }

    @Test
    fun refusesAnIncompleteDraftBeforeAnythingIsSigned() = runBlocking {
        val uuid = savedDraft()
        val ungraded = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft.copy(setterGradeId = null), "12 x 12")
        // An ungraded event is accepted by every relay and then dropped by every
        // subscriber, so the climb would silently vanish for everyone else.
        assertEquals(PublishFailure.INCOMPLETE_DRAFT, ungraded.failure)
        val unangled = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft.copy(angle = null), "12 x 12")
        assertEquals(PublishFailure.INCOMPLETE_DRAFT, unangled.failure)
        assertEquals(emptyList(), relay.published)
    }

    @Test
    fun reportsAPartialPublishInsteadOfClaimingSuccessEverywhere() = runBlocking {
        val uuid = savedDraft()
        relay.relayCount = 5
        relay.acceptCount = 2

        val result = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.NONE, result.failure)
        assertEquals(5, result.attempted)
        assertEquals(2, result.accepted)
        assertTrue(result.isPartial)
        // Two relays stored it, so the climb IS live — the row must reflect that.
        assertEquals("published_nostr", database.boardRepo.getMyClimbs(pubkey).single().syncStatus)
    }

    @Test
    fun aRejectedPublishLeavesTheRowInTheRetrySet() = runBlocking {
        val uuid = savedDraft()
        relay.relayCount = 4
        relay.acceptCount = 0

        val result = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        assertEquals(PublishFailure.NO_RELAY_ACCEPTED, result.failure)
        assertEquals(4, result.attempted)
        assertEquals(0, result.accepted)
        assertFalse(result.isPublished)

        val row = database.boardRepo.getDraftClimbs(pubkey, "kilter").single()
        assertEquals("failed", row.syncStatus)
        assertNull(row.nostrEventId)
    }

    @Test
    fun deletingSendsBothATombstoneReplacementAndAKind5() = runBlocking {
        val uuid = savedDraft()
        val published = publisher().publish(uuid, 1L, BoardBrand.KILTER, draft, "12 x 12")
        val publishedAt = relay.ofKind(30078).single().createdAt
        relay.published.clear()

        val outcome = deleter().delete(uuid)
        assertEquals(DeleteOutcome.DONE, outcome.outcome)
        assertEquals(2, relay.published.size)

        val tombstone = relay.ofKind(30078).single()
        // Same replaceable address as the climb, so index-by-address relays
        // serve the tombstone from here on.
        assertEquals(published.dTag, tombstone.firstTagValue("d"))
        assertEquals("true", tombstone.firstTagValue("deleted"))
        assertEquals(CommunityClimbTags.NS_CLIMB, tombstone.firstTagValue("L"))
        assertTrue(tombstone.content.contains("\"deleted\":true"))
        // Strictly after the publish it supersedes, even on a frozen clock.
        assertTrue(tombstone.createdAt > publishedAt)

        val deletion = relay.ofKind(5).single()
        assertEquals("30078:$pubkey:${published.dTag}", deletion.firstTagValue("a"))
        assertEquals(published.eventId, deletion.firstTagValue("e"))
        assertEquals("30078", deletion.firstTagValue("k"))
        assertEquals(CommunityClimbTags.NS_CLIMB, deletion.firstTagValue("L"))
        assertEquals(pubkey, deletion.pubkey)

        assertTrue(database.boardRepo.isClimbTombstoned(uuid))
    }

    @Test
    fun refusesToDeleteWhatIsNotOursAndSendsNothing() = runBlocking {
        // A catalogue row: origin != 'cruxcoach'.
        database.climb("kilter-row", provenance = "kilter")
        assertEquals(DeleteOutcome.NOT_OUR_CLIMB, deleter().delete("kilter-row").outcome)

        // A community row authored by somebody else.
        database.climb("foreign-row", provenance = "cruxcoach", pubkey = "bb".repeat(32))
        assertEquals(DeleteOutcome.NOT_OWNER, deleter().delete("foreign-row").outcome)

        assertEquals(DeleteOutcome.NOT_FOUND, deleter().delete("never-existed").outcome)
        assertEquals(emptyList(), relay.published)
    }

    @Test
    fun aClimbWithNoRecordedDTagStillGetsARebuiltOne() = runBlocking {
        val uuid = savedDraft()
        // A restored row: origin and owner are known, the d-tag column is not
        // (the backup blob does not carry it).
        val outcome = deleter().delete(uuid)
        assertEquals(DeleteOutcome.DONE, outcome.outcome)
        val tombstone = relay.ofKind(30078).single()
        assertEquals("cruxcoach:climb:${pubkey.take(8)}:$uuid", tombstone.firstTagValue("d"))
        // No published event id existed, so no `e` pointer is invented.
        assertNull(relay.ofKind(5).single().firstTagValue("e"))
    }
}
