package com.cruxcoach.app.community

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.ClimbValidation
import com.cruxcoach.domain.community.CommunityClimbTags
import com.cruxcoach.domain.community.buildCommunityClimbEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Every event here is really signed with secp256k1 and really written into an
 * in-memory BoardDB, so the guards are exercised against the same bytes a relay
 * would deliver.
 */
class CommunitySubscriberTest {
    private val database = BrowseTestDb()
    private val store = MemoryKeyValueStore()
    private val relay = FakeCommunityRelay()
    private val clock = FixedClock(1_790_000_000L)

    private val ownSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val ownPubkey = NostrKeys.publicKeyHex(ownSecret)!!
    private val setterSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val setterPubkey = NostrKeys.publicKeyHex(setterSecret)!!

    private val subscriber = CommunitySubscriber(
        boardRepository = database.boardRepo,
        relay = relay,
        store = store,
        hashing = JvmHashing,
        clock = clock,
        pubkeyProvider = { ownPubkey },
    )

    @AfterTest
    fun tearDown() = database.close()

    private val draft = ClimbEditorState(
        selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
        name = "Fremde Aufgabe",
        description = "Von jemand anderem",
        setterGradeId = 19,
        angle = 35,
    )

    /** A climb event exactly as the publisher builds it, signed by [secret]. */
    private fun climbEvent(
        secret: ByteArray = setterSecret,
        pubkey: String = setterPubkey,
        uuid: String = CLIMB_UUID,
        state: ClimbEditorState = draft,
        layoutId: Long = 1L,
        brand: BoardBrand = BoardBrand.KILTER,
        createdAt: Long = clock.seconds - 60,
        bounds: ClimbBounds? = ClimbBounds(4, 40, 8, 96),
        mutate: (List<List<String>>) -> List<List<String>> = { it },
    ): NostrEvent {
        val payload = buildCommunityClimbEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            uuid = uuid,
            layoutId = layoutId,
            sizeLabel = "12 x 12",
            state = state,
            brand = brand,
            bounds = bounds,
        )
        return NostrKeys.sign(JvmHashing, secret, createdAt, 30078, mutate(payload.tags), payload.content)!!
    }

    private fun deletionEvent(
        secret: ByteArray = setterSecret,
        pubkey: String = setterPubkey,
        uuid: String = CLIMB_UUID,
        dTagOwner: String = pubkey,
        createdAt: Long = clock.seconds - 10,
    ): NostrEvent {
        val dTag = "cruxcoach:climb:${dTagOwner.take(8)}:$uuid"
        val tags = listOf(
            listOf("a", "30078:$pubkey:$dTag"),
            listOf("k", "30078"),
            listOf("L", CommunityClimbTags.NS_CLIMB),
            listOf("l", CommunityClimbTags.LABEL_CLIMB, CommunityClimbTags.NS_CLIMB),
        )
        return NostrKeys.sign(JvmHashing, secret, createdAt, 5, tags, "")!!
    }

    private suspend fun fetch(vararg events: NostrEvent): CommunityFetchResult {
        relay.queryResponse = events.toList()
        return subscriber.fetch()
    }

    @Test
    fun importsAForeignClimbAsACatalogueRowAndAdvancesTheCursor() = runBlocking {
        val event = climbEvent()
        val result = fetch(event)

        assertEquals(1, result.received)
        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)

        val row = database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35)
        assertNotNull(row)
        // Provenance is what the Android upsert writes, not a guess.
        assertEquals("cruxcoach", row.origin)
        assertEquals("nostr", row.source)
        assertEquals("Fremde Aufgabe", row.name)
        assertEquals("p1r12p2r13p3r14", row.frames)
        assertEquals(setterPubkey, row.createdByPubkey)
        // No Kind-0 cache on this platform, so the persisted stub stands.
        assertEquals("npub:${setterPubkey.take(16)}", row.setterUsername)
        assertEquals(19.0, row.difficultyAverage)
        // Quality is never invented: there is no community vote aggregation.
        assertNull(row.qualityAverage)
        assertEquals(
            CommunityEventTime.isoFromEpochSeconds(event.createdAt),
            database.boardRepo.getClimbCreatedAt(CLIMB_UUID),
        )

        // The cursor is persisted under Android's own preference key.
        assertEquals(event.createdAt.toString(), store.getString("community_climb_since"))
        assertEquals(event.createdAt, result.cursor)
        // ... and the next REQ asks only for what is newer.
        fetch()
        assertTrue(relay.filters.last().single().contains("\"since\":${event.createdAt}"))
    }

    @Test
    fun theFirstFilterAsksForBothNamespacesAndBothKinds() = runBlocking {
        fetch()
        val filter = relay.filters.single().single()
        assertTrue(filter.contains("\"kinds\":[30078,5]"))
        assertTrue(filter.contains(CommunityClimbTags.NS_CLIMB))
        assertTrue(filter.contains(CommunityClimbTags.NS_CLIMB_V2))
        // A fresh install has no cursor, so relays send what they hold.
        assertTrue(!filter.contains("since"))
    }

    @Test
    fun ignoresTheEchoOfOurOwnPublish() = runBlocking {
        val result = fetch(climbEvent(secret = ownSecret, pubkey = ownPubkey))
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.OWN_EVENT, subscriber.lastSkip)
        assertNull(database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35))
    }

    @Test
    fun refusesAnEventBeyondTheSizeCap() = runBlocking {
        // Padding tags rather than content: name and description have their own
        // caps, so an oversize payload has to come from somewhere else.
        val padding = List(400) { listOf("padding", "x".repeat(64)) }
        val result = fetch(climbEvent(mutate = { it + padding }))
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.OVERSIZE, subscriber.lastSkip)
        assertNull(database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35))
    }

    @Test
    fun refusesAnEventWhoseBodyNoLongerMatchesItsId() = runBlocking {
        val tampered = climbEvent().let { it.copy(content = it.content.replace("Fremde", "Gefaelschte")) }
        val result = fetch(tampered)
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.UNVERIFIED, subscriber.lastSkip)
    }

    @Test
    fun refusesAFutureDatedEvent() = runBlocking {
        val result = fetch(climbEvent(createdAt = clock.seconds + 2 * 60 * 60))
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.FUTURE_DATED, subscriber.lastSkip)
        // A forged far-future stamp must not push the cursor past every real event.
        assertNull(store.getString("community_climb_since"))
    }

    @Test
    fun refusesAnEventClaimingSomeoneElsesDTagNamespace() = runBlocking {
        val victim = NostrKeys.publicKeyHex(NostrKeys.generateSecretKey(JvmHashing)!!)!!
        val forged = climbEvent(mutate = { tags ->
            tags.map { if (it[0] == "d") listOf("d", "cruxcoach:climb:${victim.take(8)}:$CLIMB_UUID") else it }
        })
        val result = fetch(forged)
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.DTAG_AUTHOR_MISMATCH, subscriber.lastSkip)
    }

    @Test
    fun refusesASecondAuthorForAUuidAlreadyOwned() = runBlocking {
        fetch(climbEvent())
        assertNotNull(database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35))

        // A different keypair, its own valid d-tag, the same uuid in the content:
        // INSERT OR REPLACE keys on uuid alone, so this would silently take over.
        val intruderSecret = NostrKeys.generateSecretKey(JvmHashing)!!
        val intruder = NostrKeys.publicKeyHex(intruderSecret)!!
        val result = fetch(
            climbEvent(
                secret = intruderSecret,
                pubkey = intruder,
                state = draft.copy(name = "Uebernommen"),
                createdAt = clock.seconds - 5,
            )
        )
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.CROSS_AUTHOR, subscriber.lastSkip)
        assertEquals("Fremde Aufgabe", database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35)!!.name)
    }

    @Test
    fun refusesToRekeyCatalogueContent() = runBlocking {
        // A real catalogue row has NO author, which is also what
        // getClimbAuthorPubkey returns for "no row at all" — so the ownership
        // check alone would treat this uuid as claimable and let a community
        // event overwrite official content.
        database.board.boardQueries.insertClimbRow(
            uuid = CLIMB_UUID, layout_id = 1L, setter_username = "Aurora", name = "Offizielle Aufgabe",
            frames_count = 1L, is_listed = 1L, edge_left = null, edge_right = null,
            edge_bottom = null, edge_top = null, created_at = "2026-01-01 00:00:00",
            description = "", is_nomatch = 0L, frames_pace = 0L, hsm = 0L,
            frames = "p7r12p8r14", move_count = 1L,
        )
        assertNull(database.boardRepo.getClimbAuthorPubkey(CLIMB_UUID))

        val result = fetch(climbEvent())
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.NON_COMMUNITY_CLIMB, subscriber.lastSkip)
        assertEquals("p7r12p8r14", database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35)?.frames ?: "p7r12p8r14")
    }

    @Test
    fun refusesAnEventForAUuidAnotherAuthorAlreadyOwns() = runBlocking {
        database.climb(CLIMB_UUID, name = "Fremdes Original", provenance = "cruxcoach", pubkey = "cc".repeat(32))
        val result = fetch(climbEvent())
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.CROSS_AUTHOR, subscriber.lastSkip)
        assertEquals("Fremdes Original", database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 40)!!.name)
    }

    @Test
    fun refusesAMismatchedFramesHash() = runBlocking {
        val result = fetch(
            climbEvent(mutate = { tags ->
                tags.map { if (it[0] == "frames_hash") listOf("frames_hash", "sha256:${"0".repeat(64)}") else it }
            })
        )
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.FRAMES_HASH_MISMATCH, subscriber.lastSkip)
    }

    @Test
    fun refusesEventsWithoutAGradeOrBeyondTheHoldCap() = runBlocking {
        val ungraded = climbEvent(mutate = { tags -> tags.filterNot { it[0] == "setter_grade" } })
        assertEquals(0, fetch(ungraded).imported)
        assertEquals(IngestSkip.NO_SETTER_GRADE, subscriber.lastSkip)

        val tooMany = ClimbEditorState(
            selectedHolds = (1..ClimbValidation.MAX_HOLDS_TOTAL + 1).associateWith { HoldRole.HAND } +
                mapOf(9001 to HoldRole.START, 9002 to HoldRole.FINISH),
            name = "Zu viele Griffe",
            setterGradeId = 19,
            angle = 35,
        )
        assertEquals(0, fetch(climbEvent(state = tooMany, uuid = OTHER_UUID)).imported)
        assertEquals(IngestSkip.TOO_MANY_HOLDS, subscriber.lastSkip)
    }

    @Test
    fun appliesATombstoneReplacementAndAbsorbsALaterRebroadcast() = runBlocking {
        fetch(climbEvent())
        val tombstone = NostrKeys.sign(
            JvmHashing, setterSecret, clock.seconds - 30, 30078,
            listOf(
                listOf("d", "cruxcoach:climb:${setterPubkey.take(8)}:$CLIMB_UUID"),
                listOf("L", CommunityClimbTags.NS_CLIMB),
                listOf("l", CommunityClimbTags.LABEL_CLIMB, CommunityClimbTags.NS_CLIMB),
                listOf("deleted", "true"),
            ),
            """{"deleted":true,"uuid":"$CLIMB_UUID"}""",
        )!!
        assertEquals(1, fetch(tombstone).tombstoned)
        assertTrue(database.boardRepo.isClimbTombstoned(CLIMB_UUID))

        // A non-deleting relay re-sending the original must not resurrect it.
        val result = fetch(climbEvent(createdAt = clock.seconds - 20))
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.TOMBSTONED, subscriber.lastSkip)
        assertTrue(database.boardRepo.isClimbTombstoned(CLIMB_UUID))
    }

    @Test
    fun honoursAKind5OnlyFromTheClimbsOwnAuthor() = runBlocking {
        fetch(climbEvent())

        // A stranger's deletion for someone else's climb is a denial of service.
        val strangerSecret = NostrKeys.generateSecretKey(JvmHashing)!!
        val stranger = NostrKeys.publicKeyHex(strangerSecret)!!
        val result = fetch(deletionEvent(secret = strangerSecret, pubkey = stranger, dTagOwner = setterPubkey))
        assertEquals(0, result.tombstoned)
        assertTrue(!database.boardRepo.isClimbTombstoned(CLIMB_UUID))

        assertEquals(1, fetch(deletionEvent()).tombstoned)
        assertTrue(database.boardRepo.isClimbTombstoned(CLIMB_UUID))
    }

    @Test
    fun aStaleReplaceableEventNeverOverwritesANewerOne() = runBlocking {
        fetch(climbEvent(createdAt = clock.seconds - 60, state = draft.copy(name = "Neu")))
        val result = fetch(climbEvent(createdAt = clock.seconds - 600, state = draft.copy(name = "Alt")))
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.STALE, subscriber.lastSkip)
        assertEquals("Neu", database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35)!!.name)
    }

    @Test
    fun aNonKilterClimbIsIngestedUnderItsOwnBrand() = runBlocking {
        val tension = draft.copy(boardBrand = BoardBrand.TENSION.wireValue)
        assertEquals(1, fetch(climbEvent(state = tension, brand = BoardBrand.TENSION)).imported)
        assertEquals("tension", database.boardRepo.getClimbByUuid(CLIMB_UUID, angle = 35)!!.boardBrand)
    }

    @Test
    fun refusesABrandThatContradictsItsNamespace() = runBlocking {
        // Kilter's legacy namespace with a non-Kilter brand tag: the publisher
        // never pairs these, so the event is forged or garbled.
        val result = fetch(
            climbEvent(mutate = { tags ->
                tags.map { if (it[0] == "board_brand") listOf("board_brand", "tension") else it }
            })
        )
        assertEquals(0, result.imported)
        assertEquals(IngestSkip.MALFORMED, subscriber.lastSkip)

        // An unknown brand must not fall back to Kilter.
        val unknown = fetch(
            climbEvent(mutate = { tags ->
                tags.map { if (it[0] == "board_brand") listOf("board_brand", "definitely-not-a-board") else it }
            })
        )
        assertEquals(0, unknown.imported)
        assertEquals(IngestSkip.MALFORMED, subscriber.lastSkip)
    }

    private companion object {
        const val CLIMB_UUID = "0123456789abcdef0123456789abcdef"
        const val OTHER_UUID = "fedcba9876543210fedcba9876543210"
    }
}
