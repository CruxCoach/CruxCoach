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
import kotlin.test.assertTrue

/**
 * Regression test for the edit-republish-wipes-metadata bug:
 * `INSERT OR REPLACE` reset every column not in the INSERT list,
 * losing nostr/kilter publish state on every editor save.
 *
 * **Pre-fix behaviour** (`INSERT OR REPLACE INTO climbs(...)`): every
 * editor save reset every column not in the INSERT list — `is_deleted`,
 * `nostr_event_id`, `nostr_d_tag`, `kilter_status`, `kilter_synced_at`,
 * `kilter_publish_via`, `kilter_error`, `nostr_publish_via` — to their
 * column defaults (NULL / 0). The post-publish `markClimbPublishedNostr`
 * + Kilter `markKilterPublishSynced` repopulated some of them on success,
 * but a process kill in the editor → publish window stranded the row
 * with kilter_status=NULL even though Kilter still held the original.
 * Subsequent retry-worker tick saw it as awaiting-retry, attempted to
 * CREATE on Kilter, got a 4xx (already exists), marked 'rejected'.
 *
 * **Post-fix behaviour** (INSERT OR IGNORE + column-whitelist UPDATE):
 * the editor save touches only editor-domain columns. Provenance
 * columns owned by the publish path stay intact. A new uuid still
 * INSERTs cleanly with column defaults; an existing uuid only refreshes
 * the editor fields.
 *
 * Test runs against a real in-memory SQLite via JdbcSqliteDriver — same
 * pattern as KilterSyncRaceTest in this directory.
 */
class InsertLocalDraftPreservationTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        // File-backed temp DB to mirror real driver semantics; in-memory
        // jdbc:sqlite::memory: gives each connection its own empty DB
        // which would break the multi-statement insertLocalDraft block.
        val tmp = Files.createTempDirectory("cruxcoach-insert-local-draft-")
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

    private fun insertEditorDraft(
        uuid: String,
        name: String = "Test Climb",
        frames: String = "p1164r12p1233r15p1392r14",
        framesHash: String = "hash-1",
        createdAt: String = "2026-05-01T10:00:00Z",
    ) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid,
            layout_id = 1L,
            setter_username = "setter",
            name = name,
            frames = frames,
            edge_left = 1L, edge_right = 144L, edge_bottom = 1L, edge_top = 156L,
            created_at = createdAt,
            description = "",
            move_count = 3L,
            created_by_pubkey = "pubkey-hex",
            frames_hash = framesHash,
            // FEAT-049: MoonBoard rows derive hsm on insert; a Kilter fixture has none.
            hsm = 0L,
            board_brand = "kilter",
        )
    }

    private fun rowFor(uuid: String) = db.boardQueries.getClimbByUuid(40, uuid).executeAsOne()

    // ── Behaviour: new rows still get column defaults ──────────────

    @Test
    fun `fresh insert applies defaults — kilter and nostr columns NULL`() {
        insertEditorDraft("c1")

        val r = rowFor("c1")
        assertEquals("c1", r.uuid)
        assertEquals("Test Climb", r.name)
        assertEquals("draft", r.sync_status)
        assertEquals("local", r.source)
        assertEquals("cruxcoach", r.origin)
        assertNull(r.nostr_event_id)
        assertNull(r.nostr_d_tag)
        assertNull(r.kilter_status)
        assertNull(r.kilter_synced_at)
        assertEquals(0L, r.is_deleted)
    }

    // ── Behaviour: editor re-save preserves provenance ──────────────

    @Test
    fun `re-save keeps nostr_event_id and nostr_d_tag set by publish path`() {
        insertEditorDraft("c1", name = "Old Name")

        // Publish path runs after editor save.
        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "ev-abc-123",
            nostr_d_tag = "cruxcoach:climb:pubkey-h:c1",
            pubkey = "pubkey-hex",
            created_at = "2026-01-01T00:00:00Z",
            uuid = "c1",
        )

        // User edits + saves again. PRE-FIX: nostr_* would now be NULL.
        insertEditorDraft("c1", name = "New Name", frames = "p1164r12p1500r15")

        val r = rowFor("c1")
        assertEquals("New Name", r.name, "editor field rewritten")
        assertEquals("p1164r12p1500r15", r.frames, "frames rewritten")
        assertEquals("draft", r.sync_status, "editor save resets to draft")
        assertEquals("ev-abc-123", r.nostr_event_id, "nostr_event_id preserved")
        assertEquals("cruxcoach:climb:pubkey-h:c1", r.nostr_d_tag, "nostr_d_tag preserved")
    }

    @Test
    fun `re-save keeps kilter_status and kilter_synced_at set by retry worker`() {
        insertEditorDraft("c1")
        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "ev-1", nostr_d_tag = "d-1",
            pubkey = "pk", created_at = "2026-01-01T00:00:00Z", uuid = "c1",
        )
        // Kilter side: pending → synced.
        db.boardQueries.markKilterPublishPending(uuid = "c1")
        db.boardQueries.markKilterPublishSynced(
            kilter_synced_at = 1714000000L,
            kilter_publish_via = "self",
            uuid = "c1",
        )

        // User edits + saves. PRE-FIX: kilter_status would be NULL,
        // kilter_synced_at gone, retry-worker would attempt CREATE.
        insertEditorDraft("c1", name = "Edited")

        val r = rowFor("c1")
        assertEquals("synced", r.kilter_status, "kilter_status preserved across editor save")
        assertEquals(1714000000L, r.kilter_synced_at, "kilter_synced_at preserved")
        assertEquals("self", r.kilter_publish_via, "kilter_publish_via preserved")
    }

    @Test
    fun `re-save keeps is_deleted flag (subscriber tombstones survive editor save)`() {
        insertEditorDraft("c1")
        // Simulate a tombstone marker some other path set; the editor
        // re-save shouldn't undo it. (Pre-fix the INSERT OR REPLACE
        // would reset is_deleted to 0.)
        driver.execute(null, "UPDATE climbs SET is_deleted = 1 WHERE uuid = 'c1';", 0)

        insertEditorDraft("c1", name = "Edit")

        val r = rowFor("c1")
        assertEquals(1L, r.is_deleted, "is_deleted preserved across editor save")
    }

    @Test
    fun `re-save still updates editor-domain columns and zeros bookkeeping`() {
        insertEditorDraft("c1", name = "Old", frames = "p1r12", framesHash = "h-old")

        insertEditorDraft(
            uuid = "c1",
            name = "New",
            frames = "p2r14",
            framesHash = "h-new",
            createdAt = "2026-05-02T11:00:00Z",
        )

        val r = rowFor("c1")
        assertEquals("New", r.name)
        assertEquals("p2r14", r.frames)
        assertEquals("h-new", r.frames_hash)
        assertEquals("2026-05-02T11:00:00Z", r.created_at)
        // Bookkeeping that the pre-fix INSERT OR REPLACE force-set is
        // also re-set by the new UPDATE so behaviour matches: a new
        // editor save always writes is_nomatch=0, frames_pace=0, hsm=0,
        // is_listed=1, frames_count=1.
        assertEquals(0L, r.is_nomatch)
        assertEquals(0L, r.frames_pace)
        assertEquals(0L, r.hsm)
        assertEquals(1L, r.is_listed)
        assertEquals(1L, r.frames_count)
    }

    // ── E1: pre-send crash-safety marker ───────────────────────────

    @Test
    fun `markClimbPublishInFlight flips a draft to failed (= retry queue)`() {
        insertEditorDraft("c1")
        assertEquals("draft", rowFor("c1").sync_status)

        db.boardQueries.markClimbPublishInFlight("c1")

        // CommunityPublishRetryWorker drains rows on sync_status='failed';
        // this transition promotes the row into the queue BEFORE the
        // relay round-trip starts so a process death between
        // pool.sendEventWithStats and markClimbPublishedNostr leaves the
        // row recoverable instead of stuck at 'draft' forever.
        assertEquals("failed", rowFor("c1").sync_status)
    }

    @Test
    fun `markClimbPublishedNostr resolves an in-flight marker to published_nostr`() {
        insertEditorDraft("c1")
        db.boardQueries.markClimbPublishInFlight("c1")
        assertEquals("failed", rowFor("c1").sync_status)

        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "ev-1",
            nostr_d_tag = "cruxcoach:climb:pubkey-h:c1",
            pubkey = "pubkey-hex",
            created_at = "2026-01-01T00:00:00Z",
            uuid = "c1",
        )

        val r = rowFor("c1")
        assertEquals("published_nostr", r.sync_status, "post-send flip overrides the in-flight marker")
        assertEquals("ev-1", r.nostr_event_id)
    }

    @Test
    fun `markClimbPublishInFlight refuses to touch a kilter-origin row`() {
        // Manually plant a kilter-origin row to mimic a Blossom-imported
        // catalog entry. The marker must not promote it into the Nostr
        // retry queue — only cruxcoach-authored locals belong there.
        driver.execute(null, """
            INSERT INTO climbs(uuid, layout_id, name, frames, source, origin, sync_status, frames_count, is_listed)
            VALUES ('kilter-uuid', 1, 'Catalog', X'', 'kilter', 'kilter', 'synced', 1, 1)
        """.trimIndent(), 0)

        db.boardQueries.markClimbPublishInFlight("kilter-uuid")

        val r = rowFor("kilter-uuid")
        assertEquals("synced", r.sync_status, "kilter-origin row left untouched")
        assertEquals("kilter", r.origin)
    }

    // ── E2: deleteLocalClimb hardened against published rows ───────

    @Test
    fun `deleteLocalClimb removes an unpublished draft (nostr_event_id NULL)`() {
        insertEditorDraft("c1")
        // Stats row to mirror real editor flow (climb_stats is touched in lockstep).
        db.boardQueries.upsertClimbStat(
            climb_uuid = "c1", angle = 40L,
            display_difficulty = null, difficulty_average = null,
            quality_average = null, ascensionist_count = 0L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
        assertNotNull(db.boardQueries.getClimbStatsForUuid("c1").executeAsOneOrNull())

        db.boardQueries.deleteLocalClimb("c1")

        assertNull(db.boardQueries.getClimbByUuid(40L, "c1").executeAsOneOrNull(),
            "draft row gone")
        assertNull(db.boardQueries.getClimbStatsForUuid("c1").executeAsOneOrNull(),
            "stats row gone in lockstep")
    }

    @Test
    fun `deleteLocalClimb refuses to delete a row that has been published to Nostr`() {
        insertEditorDraft("c1")
        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "ev-on-relay",
            nostr_d_tag = "cruxcoach:climb:pubkey-h:c1",
            pubkey = "pubkey-hex",
            created_at = "2026-01-01T00:00:00Z",
            uuid = "c1",
        )

        db.boardQueries.deleteLocalClimb("c1")

        // Post-publish rows must go through CommunityClimbDeleter (Kind-5
        // + tombstone-replacement). A hard DELETE here would leave the
        // relay copy with no local marker, so the live-sub would just
        // re-import the climb on next sync.
        val r = rowFor("c1")
        assertEquals("published_nostr", r.sync_status, "published row preserved")
        assertEquals("ev-on-relay", r.nostr_event_id)
    }

    // ── E5: source='local' backstop for the live-sub self-filter ───

    @Test
    fun `isLocallyAuthored true for editor-inserted draft`() {
        insertEditorDraft("c1")
        assertTrue(db.boardQueries.isLocallyAuthored("c1").executeAsOneOrNull() != null)
    }

    @Test
    fun `isLocallyAuthored false for kilter-origin row`() {
        driver.execute(null, """
            INSERT INTO climbs(uuid, layout_id, name, frames, source, origin, sync_status, frames_count, is_listed)
            VALUES ('kilter-uuid', 1, 'Catalog', X'', 'kilter', 'kilter', 'synced', 1, 1)
        """.trimIndent(), 0)
        assertNull(db.boardQueries.isLocallyAuthored("kilter-uuid").executeAsOneOrNull())
    }

    @Test
    fun `isLocallyAuthored false for unknown uuid`() {
        assertNull(db.boardQueries.isLocallyAuthored("does-not-exist").executeAsOneOrNull())
    }
}
