package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
