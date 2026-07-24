package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.data.repository.BoardRepositoryImpl
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
 * Regression tests for the tombstone path on community climbs:
 *
 *  * `markCommunityClimbDeleted` — owner-locked + origin-locked. A
 *    Kilter-origin row or a row owned by a different pubkey must
 *    never flip is_deleted=1 even if the caller passes the right uuid.
 *  * `isClimbTombstoned` — true only for `is_deleted=1` rows.
 *  * `insertTombstoneShell` — INSERT OR IGNORE: never overwrites a
 *    real row, only plants memorial when no row exists.
 *
 * Together these are the L3 + cross-author/cross-origin guards for
 * the user-driven delete flow + the cross-device defence against a
 * Live-Sub Original-Event landing on a fresh device after the
 * tombstone arrived first.
 */
class CommunityClimbTombstoneTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repository: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-tombstone-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repository = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** Inserts a published cruxcoach-origin row owned by [pubkey]. */
    private fun insertCommunityRow(uuid: String, pubkey: String) {
        db.boardQueries.upsertCommunityClimb(
            uuid = uuid,
            layout_id = 1L,
            setter_username = "tester",
            name = "Test",
            frames = "p1164r12p1233r15",
            edge_left = 1L, edge_right = 144L, edge_bottom = 1L, edge_top = 156L,
            description = "",
            move_count = 1L,
            nostr_event_id = "ev-$uuid",
            nostr_d_tag = "cruxcoach:climb:${pubkey.take(8)}:$uuid",
            created_by_pubkey = pubkey,
            frames_hash = "hash-$uuid",
            created_at = "2026-05-01T10:00:00Z",
            board_brand = "kilter",
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L, display_difficulty = 14.5,
            difficulty_average = 14.5, quality_average = 4.0,
            ascensionist_count = 1L, benchmark_difficulty = null,
            fa_username = null, fa_at = null, official_kilter_difficulty = null,
        )
    }

    /** Inserts a Kilter-origin row (origin='kilter') as if from blob sync. */
    private fun insertKilterRow(uuid: String) {
        db.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = 1L,
            setter_username = "kilter-setter",
            name = "Kilter Climb",
            frames = "p100r12p101r15",
            frames_count = 1L,
            is_listed = 1L,
            edge_left = 1L, edge_right = 144L, edge_bottom = 1L, edge_top = 156L,
            created_at = "2024-01-01T00:00:00Z",
            description = "",
            is_nomatch = 0L,
            frames_pace = 0L,
            hsm = 0L,
            move_count = 1L,
        )
    }

    private fun rowFor(uuid: String) =
        // angle=40 just selects a join target for the LEFT JOIN; the
        // climb-side columns are returned regardless of stat existence.
        db.boardQueries.getClimbByUuid(angle = 40L, uuid = uuid).executeAsOneOrNull()

    /** Reads climb_stats.layout_id directly — getClimbByUuid exposes the CLIMB's
     *  layout_id, not the denormalized stat column the 20.sqm backfill targets. */
    private fun statLayoutId(uuid: String): Long =
        driver.executeQuery(
            null,
            "SELECT layout_id FROM climb_stats WHERE climb_uuid = '$uuid' LIMIT 1",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) (cursor.getLong(0) ?: -1L) else -1L,
                )
            },
            0,
        ).value

    private fun isTombstoned(uuid: String) =
        db.boardQueries.isClimbTombstoned(uuid).executeAsOneOrNull() != null

    private val authorA = "aa".repeat(32)
    private val authorB = "bb".repeat(32)

    // ── markCommunityClimbDeleted: happy path ───────────────────────

    @Test
    fun `tombstone flips own cruxcoach row + preserves stats`() {
        insertCommunityRow("c1", authorA)
        assertEquals(1, db.boardQueries.countStats().executeAsOne().toInt())

        db.boardQueries.markCommunityClimbDeleted(
            uuid = "c1",
            pubkey = authorA,
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        val r = rowFor("c1")
        assertNotNull(r)
        assertEquals(1L, r.is_deleted, "is_deleted flipped")
        assertEquals(0L, r.is_listed, "is_listed cleared (browse VIEW excludes)")
        assertEquals("deleted", r.sync_status, "sync_status reflects tombstone")
        assertEquals("2026-05-04T13:00:00Z", r.created_at, "created_at bumped to tombstone time")
        assertEquals(
            1, db.boardQueries.countStats().executeAsOne().toInt(),
            "climb_stats preserved — tombstone is a visibility flip, not a purge; " +
                "the user's logbook keeps grade/send-count for a climb they already " +
                "attempted after the setter deletes it",
        )
        assert(isTombstoned("c1")) { "isClimbTombstoned returns true post-mark" }
    }

    // ── Owner-lock: cross-author tombstone is a no-op ───────────────

    @Test
    fun `tombstone refuses cross-author — Mallory cannot tombstone Alice's climb`() {
        insertCommunityRow("c1", authorA)

        db.boardQueries.markCommunityClimbDeleted(
            uuid = "c1",
            pubkey = authorB, // != row's created_by_pubkey
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        val r = rowFor("c1")
        assertNotNull(r)
        assertEquals(0L, r.is_deleted, "alien tombstone must NOT flip is_deleted")
        assertEquals(1L, r.is_listed, "row stays listed")
        assertEquals(1, db.boardQueries.countStats().executeAsOne().toInt(), "stats stay attached")
    }

    // ── Catalogue-hijack guard: isNonCommunityClimb ─────────────────
    // The live subscriber's cross-author guard treats a NULL existing author as
    // claimable, and getClimbAuthorPubkey returns NULL for BOTH "no row" and a
    // catalogue row with no author. isNonCommunityClimb disambiguates so a
    // community Kind-30078 can never re-key/overwrite official catalogue content.

    @Test
    fun `isNonCommunityClimb flags a kilter (catalogue, NULL-author) row`() {
        insertKilterRow("k1")
        assertNotNull(
            db.boardQueries.isNonCommunityClimb("k1").executeAsOneOrNull(),
            "a kilter/NULL-author row must be flagged non-community so the live " +
                "subscriber refuses to overwrite it via INSERT OR REPLACE on uuid",
        )
    }

    @Test
    fun `isNonCommunityClimb does NOT flag a genuine community row`() {
        insertCommunityRow("c1", authorA)
        assertNull(
            db.boardQueries.isNonCommunityClimb("c1").executeAsOneOrNull(),
            "origin='cruxcoach' AND non-NULL author → a real community row; " +
                "legitimate same-author replaceable updates must still pass",
        )
    }

    @Test
    fun `isNonCommunityClimb does NOT flag an unknown uuid`() {
        assertNull(
            db.boardQueries.isNonCommunityClimb("missing-uuid").executeAsOneOrNull(),
            "no row → not flagged → a brand-new community climb is allowed",
        )
    }

    // ── Migration 20.sqm / 21.sqm: data transforms on existing rows ──
    // Run the exact .sqm UPDATE bodies against seeded "broken" rows to prove
    // they heal the intended rows and leave the catalogue untouched.

    @Test
    fun `21_sqm reclassifies kilter rows carrying a pubkey to cruxcoach, catalogue untouched`() {
        insertKilterRow("k-native")  // origin='kilter', pubkey NULL → native catalogue
        insertKilterRow("k-cc")      // origin='kilter', gets a real pubkey below → heals
        insertKilterRow("k-empty")   // origin='kilter', empty-string pubkey → must stay
        driver.execute(null, "UPDATE climbs SET created_by_pubkey='pkCC' WHERE uuid='k-cc'", 0)
        driver.execute(null, "UPDATE climbs SET created_by_pubkey='' WHERE uuid='k-empty'", 0)

        // exact 21.sqm body
        driver.execute(
            null,
            "UPDATE climbs SET origin = 'cruxcoach' " +
                "WHERE origin = 'kilter' AND created_by_pubkey IS NOT NULL AND created_by_pubkey != ''",
            0,
        )

        assertEquals("cruxcoach", rowFor("k-cc")!!.origin, "kilter row WITH a pubkey heals to cruxcoach")
        assertEquals("kilter", rowFor("k-native")!!.origin, "native catalogue (no pubkey) untouched")
        assertEquals("kilter", rowFor("k-empty")!!.origin, "empty-string pubkey is not a real author — untouched")
    }

    @Test
    fun `20_sqm backfills a broken climb_stats layout_id from its climb`() {
        insertKilterRow("k1")
        driver.execute(null, "UPDATE climbs SET layout_id=7 WHERE uuid='k1'", 0)
        db.boardQueries.upsertClimbStat(
            climb_uuid = "k1", angle = 40L, display_difficulty = 14.5,
            difficulty_average = 14.5, quality_average = 4.0,
            ascensionist_count = 1L, benchmark_difficulty = null,
            fa_username = null, fa_at = null, official_kilter_difficulty = null,
        )
        // Simulate the pre-15.sqm broken row (community/local INSERT omitted layout_id).
        driver.execute(null, "UPDATE climb_stats SET layout_id=0 WHERE climb_uuid='k1'", 0)
        assertEquals(0L, statLayoutId("k1"), "precondition: stat is broken (layout_id=0)")

        // exact 20.sqm body
        driver.execute(
            null,
            "UPDATE climb_stats SET layout_id = COALESCE(" +
                "(SELECT c.layout_id FROM climbs c WHERE c.uuid = climb_stats.climb_uuid), 0) " +
                "WHERE layout_id = 0",
            0,
        )

        assertEquals(7L, statLayoutId("k1"), "stat layout_id healed from the climb (7)")
    }

    // ── Origin-lock: Kilter rows are read-only on our end ───────────

    @Test
    fun `tombstone refuses Kilter-origin rows even if pubkey is null`() {
        insertKilterRow("k1") // origin defaults to 'kilter', no created_by_pubkey

        // Even if a Kind-5 deletion arrives with an apparent match, the
        // SQL guard refuses to tombstone a kilter row. (Defence: a
        // forged d-tag pointing at a Kilter uuid must never let
        // anything through this path.)
        db.boardQueries.markCommunityClimbDeleted(
            uuid = "k1",
            pubkey = authorA,
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        val r = rowFor("k1")
        assertNotNull(r)
        assertEquals(0L, r.is_deleted, "Kilter row never tombstoned via cruxcoach delete path")
        assertEquals(1L, r.is_listed, "Kilter row stays listed")
        assertEquals("kilter", r.origin, "origin preserved")
    }

    // ── insertTombstoneShell: cross-device defence ──────────────────

    @Test
    fun `shell inserts memorial when no real row exists`() {
        insertTombstoneShell("missing-uuid", authorA, "cruxcoach:climb:${authorA.take(8)}:missing-uuid")

        val r = rowFor("missing-uuid")
        assertNotNull(r, "shell row was inserted")
        assertEquals(1L, r.is_deleted, "shell carries is_deleted=1")
        assertEquals(0L, r.is_listed, "shell never appears in browse VIEW")
        assertEquals("cruxcoach", r.origin, "shell is cruxcoach-origin")
        assertEquals("deleted", r.sync_status, "shell flagged as deleted")
        assertEquals(authorA, r.created_by_pubkey, "shell records the deleter's pubkey")
        assert(isTombstoned("missing-uuid")) { "isClimbTombstoned returns true for shell" }
    }

    @Test
    fun `shell INSERT OR IGNORE never overwrites a real existing row`() {
        // A real row exists from a prior live-sub upsert.
        insertCommunityRow("c1", authorA)

        // A Kind-5 from a *different* author for the same uuid arrives —
        // shouldn't insert (PK conflict on uuid). The follow-up
        // markCommunityClimbDeleted is owner-locked so no flip either.
        insertTombstoneShell("c1", authorB, "spoofed-d-tag")

        val r = rowFor("c1")
        assertNotNull(r)
        assertEquals("Test", r.name, "real row's name untouched by shell INSERT OR IGNORE")
        assertEquals(authorA, r.created_by_pubkey, "real row's author untouched")
        assertEquals(0L, r.is_deleted, "shell did not flip is_deleted (real row's value preserved)")
    }

    @Test
    fun `foreign shell is removable so genuine author can reclaim uuid`() {
        insertTombstoneShell("target", authorB, "cruxcoach:climb:${authorB.take(8)}:target")

        assertTrue(repository.removeForeignTombstoneShell("target", authorA))

        assertNull(rowFor("target"), "attacker-owned synthetic shell no longer squats the uuid")
        insertCommunityRow("target", authorA)
        assertEquals(authorA, rowFor("target")!!.created_by_pubkey)
    }

    @Test
    fun `same-author shell remains and continues to absorb original replays`() {
        insertTombstoneShell("target", authorA, "cruxcoach:climb:${authorA.take(8)}:target")

        assertFalse(repository.removeForeignTombstoneShell("target", authorA))

        assertNotNull(rowFor("target"), "the author's legitimate deletion memorial must remain")
        assertTrue(isTombstoned("target"))
    }

    @Test
    fun `real deleted climb is never mistaken for a removable shell`() {
        insertCommunityRow("target", authorB)
        db.boardQueries.markCommunityClimbDeleted(
            uuid = "target",
            pubkey = authorB,
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        assertFalse(repository.removeForeignTombstoneShell("target", authorA))

        assertEquals(authorB, rowFor("target")!!.created_by_pubkey)
        assertTrue(isTombstoned("target"))
    }

    // ── Combined absorbTombstone semantics ──────────────────────────

    @Test
    fun `absorbTombstone on existing own row tombstones cleanly`() {
        // Mirrors CommunityClimbSubscriber.absorbTombstone: shell first
        // (no-op if real row exists), then owner-locked mark.
        insertCommunityRow("c1", authorA)

        insertTombstoneShell("c1", authorA, "cruxcoach:climb:${authorA.take(8)}:c1")
        db.boardQueries.markCommunityClimbDeleted(
            uuid = "c1",
            pubkey = authorA,
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        val r = rowFor("c1")
        assertNotNull(r)
        assertEquals(1L, r.is_deleted)
        assertEquals(0L, r.is_listed)
        assert(isTombstoned("c1"))
    }

    @Test
    fun `absorbTombstone for unknown uuid leaves shell so future imports are absorbed`() {
        insertTombstoneShell("future-uuid", authorA, "cruxcoach:climb:${authorA.take(8)}:future-uuid")
        db.boardQueries.markCommunityClimbDeleted(
            uuid = "future-uuid",
            pubkey = authorA,
            tombstone_iso = "2026-05-04T13:00:00Z",
        )

        // The shell exists, is is_deleted=1; a future Live-Sub
        // upsertCommunityClimb on the same uuid would be absorbed by
        // the L3 isClimbTombstoned check before it even ran.
        assert(isTombstoned("future-uuid")) {
            "shell remains tombstoned so L3 absorption fires on later Original-Events"
        }
    }

    private fun insertTombstoneShell(uuid: String, pubkey: String, dTag: String) {
        db.boardQueries.insertTombstoneShell(
            uuid = uuid,
            tombstone_iso = "2026-05-04T13:00:00Z",
            d_tag = dTag,
            pubkey = pubkey,
        )
    }
}
