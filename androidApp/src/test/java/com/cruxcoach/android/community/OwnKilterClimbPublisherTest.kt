package com.cruxcoach.android.community

import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbPublishContext
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.ClimbEditorState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Orchestration tests for [OwnKilterClimbPublisher]. No live network and no
 * real relay pool — [CommunityClimbPublisher] is a mockk fake; the SQL-level
 * conversion guards have their own real-SQLite coverage in
 * OwnClimbAdoptionSqlTest.
 *
 * Compliance pin: a climb the user merely LOGGED but did not AUTHOR is not
 * publishable — the gate compares the recorded `kilter_author_uuid` against
 * the CONNECTED account's userUuid, never display names.
 */
class OwnKilterClimbPublisherTest {

    private val myKilterUuid = "my-own-kilter-user-uuid"
    private val otherKilterUuid = "someone-elses-kilter-uuid"
    private val myPubkey = "a".repeat(64)

    private val uuid = "11111111-aaaa-bbbb-cccc-000000000001"

    private lateinit var boardRepo: BoardRepository
    private lateinit var tokenStore: KilterTokenStore
    private lateinit var nostrSigner: NostrIdentity
    private lateinit var communityPublisher: CommunityClimbPublisher
    private lateinit var publisher: OwnKilterClimbPublisher

    /** Tracks the adopted state so the row returned after adoption is consistent. */
    private var adopted = false

    private fun row(
        published: Boolean = false,
    ) = CommunityClimbRow(
        uuid = uuid,
        name = "Mein Boulder",
        setterUsername = "me",
        description = "",
        framesText = "p1100r12p1200r13p1300r14",
        source = if (adopted) "local" else "kilter",
        syncStatus = if (published) "published_nostr" else if (adopted) "draft" else "synced",
        createdByPubkey = if (adopted) myPubkey else null,
        nostrEventId = if (published) "ev1" else null,
        nostrDTag = null,
        framesHash = null,
        createdAt = "2026-01-01 00:00:00",
        moveCount = 2,
        kilterSyncedAt = null,
        layoutId = 1L,
        boardBrand = "kilter",
    )

    @Before
    fun setUp() {
        adopted = false
        boardRepo = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        nostrSigner = mockk(relaxed = true)
        communityPublisher = mockk(relaxed = true)

        every { tokenStore.getUserUuid() } returns myKilterUuid
        every { nostrSigner.getPublicKeyHex() } returns myPubkey
        // Canonical resolution: identity (uuid already canonical).
        every { boardRepo.findClimbCanonicalUuid(any()) } answers { firstArg() }
        every { boardRepo.getClimbKilterAuthorUuid(uuid) } returns myKilterUuid
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } answers { row() }
        every {
            boardRepo.adoptKilterClimbAsCommunity(uuid, myKilterUuid, myPubkey, any())
        } answers { adopted = true; true }
        every { boardRepo.getClimbStatsForUuid(uuid) } returns (40 to 22)
        every { boardRepo.getClimbPublishContext(uuid) } returns
            ClimbPublishContext(boardBrand = "kilter", layoutId = 1L, sizeLabel = "12 x 12")

        publisher = OwnKilterClimbPublisher(boardRepo, tokenStore, nostrSigner, communityPublisher)
    }

    // ── Authorship predicate ─────────────────────────────────────────

    @Test
    fun own_authored_climb_is_recognized() {
        assertTrue(publisher.isOwnAuthoredClimb(uuid))
        assertTrue(publisher.isPublishableAsMine(uuid))
    }

    @Test
    fun foreign_authored_climb_is_not_own() {
        every { boardRepo.getClimbKilterAuthorUuid(uuid) } returns otherKilterUuid
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns null
        assertFalse(publisher.isOwnAuthoredClimb(uuid))
        assertFalse(publisher.isPublishableAsMine(uuid))
    }

    @Test
    fun curated_unknown_author_climb_is_not_own() {
        every { boardRepo.getClimbKilterAuthorUuid(uuid) } returns null
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns null
        assertFalse(publisher.isOwnAuthoredClimb(uuid))
        assertFalse(publisher.isPublishableAsMine(uuid))
    }

    @Test
    fun no_connected_kilter_account_closes_the_gate() {
        every { tokenStore.getUserUuid() } returns null
        assertFalse(publisher.isOwnAuthoredClimb(uuid))
        assertFalse(publisher.isPublishableAsMine(uuid))
        assertTrue(publisher.getOwnAuthoredClimbs().isEmpty())
    }

    @Test
    fun already_published_climb_is_own_but_not_publishable() {
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns row(published = true)
        assertTrue(publisher.isOwnAuthoredClimb(uuid))
        assertFalse(publisher.isPublishableAsMine(uuid))
    }

    // ── Publish orchestration ────────────────────────────────────────

    @Test
    fun publish_adopts_in_place_and_routes_through_community_publisher() = runTest {
        val stateSlot = slot<ClimbEditorState>()
        coEvery {
            communityPublisher.publish(
                uuid = uuid, layoutId = 1L, boardBrand = BoardBrand.KILTER,
                state = capture(stateSlot), sizeLabel = "12 x 12",
            )
        } returns CommunityClimbPublisher.Result(
            nostrEventId = "ev1", nudgeToConnectKilter = false,
        )

        val outcome = publisher.publish(uuid)

        assertEquals(OwnKilterClimbPublisher.Outcome.Published("ev1"), outcome)
        // Conversion happened BEFORE the publish, with the connected
        // account's identity and the user's own pubkey — same Kilter uuid.
        coVerify(exactly = 1) {
            boardRepo.adoptKilterClimbAsCommunity(uuid, myKilterUuid, myPubkey, any())
        }
        // State reconstructed from the stored row + stats.
        val state = stateSlot.captured
        assertEquals("Mein Boulder", state.name)
        assertEquals(22, state.setterGradeId)
        assertEquals(40, state.angle)
        assertEquals(setOf(1100, 1200, 1300), state.selectedHolds.keys)
    }

    /** COMPLIANCE: a logged-but-not-authored climb must be unpublishable —
     *  publish refuses before any conversion or relay traffic happens. */
    @Test
    fun publish_refuses_logged_but_foreign_authored_climb() = runTest {
        every { boardRepo.getClimbKilterAuthorUuid(uuid) } returns otherKilterUuid
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns null

        val outcome = publisher.publish(uuid)

        assertEquals(OwnKilterClimbPublisher.Outcome.NotAuthor, outcome)
        coVerify(exactly = 0) { boardRepo.adoptKilterClimbAsCommunity(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            communityPublisher.publish(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun publish_refuses_curated_unknown_author_climb() = runTest {
        every { boardRepo.getClimbKilterAuthorUuid(uuid) } returns null
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns null

        assertEquals(OwnKilterClimbPublisher.Outcome.NotAuthor, publisher.publish(uuid))
        coVerify(exactly = 0) { boardRepo.adoptKilterClimbAsCommunity(any(), any(), any(), any()) }
    }

    @Test
    fun publish_refuses_without_connected_kilter_account() = runTest {
        every { tokenStore.getUserUuid() } returns null
        assertEquals(OwnKilterClimbPublisher.Outcome.NotAuthor, publisher.publish(uuid))
        coVerify(exactly = 0) { boardRepo.adoptKilterClimbAsCommunity(any(), any(), any(), any()) }
    }

    @Test
    fun publish_requires_nostr_identity_before_converting() = runTest {
        every { nostrSigner.getPublicKeyHex() } returns ""
        assertEquals(OwnKilterClimbPublisher.Outcome.NoNostrIdentity, publisher.publish(uuid))
        coVerify(exactly = 0) { boardRepo.adoptKilterClimbAsCommunity(any(), any(), any(), any()) }
    }

    @Test
    fun publish_short_circuits_already_published_row() = runTest {
        every { boardRepo.getOwnAuthoredClimbRow(uuid, myKilterUuid) } returns row(published = true)
        assertEquals(OwnKilterClimbPublisher.Outcome.AlreadyPublished, publisher.publish(uuid))
        coVerify(exactly = 0) { boardRepo.adoptKilterClimbAsCommunity(any(), any(), any(), any()) }
    }

    @Test
    fun publish_surfaces_failure_when_no_relay_accepts() = runTest {
        coEvery {
            communityPublisher.publish(any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("No relay accepted the community-climb event (attempted=3)")

        val outcome = publisher.publish(uuid)

        assertTrue(outcome is OwnKilterClimbPublisher.Outcome.Failed)
        // The conversion already happened — the row is in the retry queue
        // (sync_status 'failed'), so a later attempt can succeed.
        coVerify(exactly = 1) {
            boardRepo.adoptKilterClimbAsCommunity(uuid, myKilterUuid, myPubkey, any())
        }
    }

    @Test
    fun publish_fails_cleanly_when_adoption_is_refused() = runTest {
        every {
            boardRepo.adoptKilterClimbAsCommunity(uuid, myKilterUuid, myPubkey, any())
        } returns false

        val outcome = publisher.publish(uuid)

        assertTrue(outcome is OwnKilterClimbPublisher.Outcome.Failed)
        coVerify(exactly = 0) {
            communityPublisher.publish(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun own_authored_list_delegates_with_connected_account() {
        every { boardRepo.getOwnAuthoredKilterClimbs(myKilterUuid) } returns listOf(row())
        val list = publisher.getOwnAuthoredClimbs()
        assertEquals(listOf(uuid), list.map { it.uuid })
    }
}
