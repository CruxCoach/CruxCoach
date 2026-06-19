package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.community.isCommunityPublished
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SQL-boundary tests for the own-Kilter-climb publish path (real SQLite,
 * same JDBC harness as BoardSizeFitFilterTest):
 *
 *  - the authorship predicate (`kilter_author_uuid` identity match):
 *    own vs foreign-authored vs curated-unknown rows
 *  - `adoptKilterClimbAsCommunity` flips provenance IN PLACE (origin →
 *    'cruxcoach', source → 'local', sync_status → 'draft', owner pubkey
 *    attached, Kilter leg pre-marked 'synced') while KEEPING the Kilter
 *    uuid, and is REJECTED for foreign authors / rows owned by a
 *    different pubkey / already-published rows
 *  - the reused publish bookkeeping (`markClimbPublishInFlight` →
 *    `markClimbPublishedNostr`) works on an adopted row unchanged
 */
class OwnClimbAdoptionSqlTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val myKilterUuid = "my-own-kilter-user-uuid"
    private val otherKilterUuid = "someone-elses-kilter-uuid"
    private val myPubkey = "a".repeat(64)
    private val otherPubkey = "b".repeat(64)

    private val ownUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val foreignUuid = "11111111-aaaa-bbbb-cccc-000000000002"
    private val curatedUuid = "11111111-aaaa-bbbb-cccc-000000000003"

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-own-adopt-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // Own-authored Kilter climb (the connected account set it).
        climb(ownUuid, "Mein Boulder")
        repo.setClimbKilterAuthorUuid(ownUuid, myKilterUuid)
        // Logged-but-foreign climb (someone else's work — never publishable).
        climb(foreignUuid, "Fremder Boulder")
        repo.setClimbKilterAuthorUuid(foreignUuid, otherKilterUuid)
        // Curated row, author unknown (kilter_author_uuid stays NULL).
        climb(curatedUuid, "Kuratierter Boulder")
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        runCatching { dbFile.delete() }
    }

    private fun climb(uuid: String, name: String) {
        repo.upsertClimb(
            uuid = uuid, layoutId = 1L, setter = "setter", name = name,
            frames = "p1100r12p1200r13p1300r14", framesCount = 1L, isListed = 1L,
            edgeLeft = 0L, edgeRight = 100L, edgeBottom = 0L, edgeTop = 100L,
            createdAt = "2026-01-01 00:00:00",
        )
    }

    // ── Authorship predicate ─────────────────────────────────────────

    @Test
    fun author_uuid_readable_and_distinguishes_own_foreign_curated() {
        assertEquals(myKilterUuid, repo.getClimbKilterAuthorUuid(ownUuid))
        assertEquals(otherKilterUuid, repo.getClimbKilterAuthorUuid(foreignUuid))
        assertNull(repo.getClimbKilterAuthorUuid(curatedUuid), "curated row has no author")
    }

    @Test
    fun own_authored_list_contains_only_my_climbs() {
        val mine = repo.getOwnAuthoredKilterClimbs(myKilterUuid)
        assertEquals(listOf(ownUuid), mine.map { it.uuid })
    }

    @Test
    fun gated_row_lookup_refuses_foreign_and_curated_rows() {
        assertNotNull(repo.getOwnAuthoredClimbRow(ownUuid, myKilterUuid))
        assertNull(repo.getOwnAuthoredClimbRow(foreignUuid, myKilterUuid),
            "a logged-but-not-authored climb must never resolve as publishable")
        assertNull(repo.getOwnAuthoredClimbRow(curatedUuid, myKilterUuid),
            "an unknown-author curated climb must never resolve as publishable")
    }

    // ── Adoption (in-place provenance conversion) ────────────────────

    @Test
    fun adopt_flips_provenance_in_place_keeping_kilter_uuid() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 1_700_000_000L))

        val row = repo.getOwnAuthoredClimbRow(ownUuid, myKilterUuid)
        assertNotNull(row)
        assertEquals(ownUuid, row.uuid, "uuid must stay the Kilter uuid")
        assertEquals("local", row.source)
        assertEquals("draft", row.syncStatus)
        assertEquals(myPubkey, row.createdByPubkey)
        // origin via the delete-context projection (CommunityClimbRow has no origin).
        val ctx = repo.getCommunityClimbDeleteContext(ownUuid)
        assertEquals("cruxcoach", ctx?.origin)
        // Kilter leg pre-marked: the climb is already on Kilter natively, so
        // the publisher's best-effort Kilter create must see an occupied slot.
        assertEquals("synced", repo.getKilterPublishState(ownUuid)?.status)
    }

    @Test
    fun adopt_rejected_for_foreign_author_and_unknown_author() {
        assertFalse(repo.adoptKilterClimbAsCommunity(foreignUuid, myKilterUuid, myPubkey, 0L),
            "foreign-authored climb must not be adoptable")
        assertFalse(repo.adoptKilterClimbAsCommunity(curatedUuid, myKilterUuid, myPubkey, 0L),
            "unknown-author curated climb must not be adoptable")
        // Both rows keep their catalogue provenance.
        assertEquals("kilter", repo.getCommunityClimbDeleteContext(foreignUuid)?.origin)
        assertEquals("kilter", repo.getCommunityClimbDeleteContext(curatedUuid)?.origin)
    }

    @Test
    fun adopt_rejected_when_row_owned_by_different_pubkey() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))
        assertFalse(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, otherPubkey, 0L),
            "a row owned by a different created_by_pubkey must never be re-keyed")
        assertEquals(myPubkey, repo.getCommunityClimbDeleteContext(ownUuid)?.createdByPubkey)
    }

    @Test
    fun adopt_is_idempotent_for_same_owner_retry() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L),
            "same-owner retry (e.g. after a relay outage) must stay possible")
    }

    // ── Reused publish bookkeeping ───────────────────────────────────

    @Test
    fun adopted_row_flows_through_existing_publish_bookkeeping() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))

        // Crash-safety pre-mark only touches local+cruxcoach rows — the
        // adopted row qualifies now.
        repo.markClimbPublishInFlight(ownUuid)
        assertEquals("failed", repo.getOwnAuthoredClimbRow(ownUuid, myKilterUuid)?.syncStatus)

        repo.markClimbPublishedNostr(
            uuid = ownUuid,
            nostrEventId = "ev1",
            nostrDTag = "cruxcoach:climb:aaaaaaaa:$ownUuid",
            pubkey = myPubkey,
            createdAtIso = "2026-01-01T00:00:00Z",
        )
        val row = repo.getOwnAuthoredClimbRow(ownUuid, myKilterUuid)
        assertNotNull(row)
        assertEquals("published_nostr", row.syncStatus)
        assertEquals("ev1", row.nostrEventId)
        assertTrue(row.isCommunityPublished)

        // Once published, a stray re-adopt must be refused (would reset the
        // row to 'draft' and strand the publication).
        assertFalse(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))
    }

    @Test
    fun premark_does_not_promote_unadopted_kilter_rows() {
        repo.markClimbPublishInFlight(ownUuid)
        assertEquals("synced", repo.getOwnAuthoredClimbRow(ownUuid, myKilterUuid)?.syncStatus,
            "markClimbPublishInFlight must not touch a not-yet-adopted Kilter row")
    }

    // ── Un-claim (revert of adoption) ────────────────────────────────

    @Test
    fun revert_unclaims_a_published_climb_back_to_a_reclaimable_kilter_import() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))
        repo.markClimbPublishedNostr(
            uuid = ownUuid, nostrEventId = "ev1",
            nostrDTag = "cruxcoach:climb:aaaaaaaa:$ownUuid",
            pubkey = myPubkey, createdAtIso = "2026-01-01T00:00:00Z",
        )
        assertEquals("cruxcoach", repo.getCommunityClimbDeleteContext(ownUuid)?.origin)

        assertTrue(repo.revertClaimedKilterClimb(ownUuid, myPubkey))

        val ctx = repo.getCommunityClimbDeleteContext(ownUuid)
        assertEquals("kilter", ctx?.origin, "origin reverts to kilter")
        assertNull(ctx?.createdByPubkey, "owner pubkey cleared")
        assertNull(ctx?.nostrEventId, "nostr event id cleared")
        // Still the user's authored climb → back in the own-authored list…
        assertEquals(myKilterUuid, repo.getClimbKilterAuthorUuid(ownUuid))
        assertTrue(repo.getOwnAuthoredKilterClimbs(myKilterUuid).any { it.uuid == ownUuid })
        // …and re-claimable (publish artifacts cleared so adoption is allowed again).
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L),
            "an un-claimed climb can be claimed again")
    }

    @Test
    fun revert_is_owner_locked() {
        assertTrue(repo.adoptKilterClimbAsCommunity(ownUuid, myKilterUuid, myPubkey, 0L))
        assertFalse(repo.revertClaimedKilterClimb(ownUuid, otherPubkey),
            "a foreign caller cannot un-claim someone's climb")
        assertEquals("cruxcoach", repo.getCommunityClimbDeleteContext(ownUuid)?.origin)
    }
}
