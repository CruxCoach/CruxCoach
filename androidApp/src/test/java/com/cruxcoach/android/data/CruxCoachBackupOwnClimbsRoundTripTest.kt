package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end round-trip for the FEAT-008 §4 Phase B own-climb backup
 * payload. Real BoardDatabase via JdbcSqliteDriver — same fixture
 * pattern as [InsertLocalDraftPreservationTest] — mocked secure-DB
 * collaborators (those branches of [CruxCoachBackup.export] / .import
 * are exercised elsewhere). The contract under test:
 *
 *   1. A draft + a published own-climb (with full Nostr + Kilter
 *      provenance) round-trip through export → wipe → import without
 *      losing any persisted column.
 *   2. Identity binding: the export filter scopes to
 *      `created_by_pubkey == :pubkey`; rows authored by another pubkey
 *      never reach the envelope.
 *   3. Re-import is idempotent: running import a second time leaves
 *      the row count unchanged and bumps `skippedDuplicates`.
 *   4. v3 is forward-incompatible with v2 by-design: a v2 envelope
 *      restores fine on v3 (no own climbs); a v3 envelope on a v2
 *      validator fails (covered in CruxCoachBackupValidationTest, not
 *      here).
 */
class CruxCoachBackupOwnClimbsRoundTripTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var boardRepo: BoardRepositoryImpl

    private val ownPubkey = "a".repeat(64)
    private val otherPubkey = "b".repeat(64)

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    /** Trivial pass-through for a TransactionRunner — the secure-DB
     *  branch we exercise here doesn't need real transaction semantics. */
    private val passThroughTxn = object : TransactionRunner {
        override fun <T> runInTransaction(block: () -> T): T = block()
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-backup-roundtrip-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        boardRepo = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun seedDraft(
        uuid: String,
        name: String = "Test Draft",
        boardBrand: String = "kilter",
        layoutId: Long = 1L,
    ): String {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId, setter_username = "alice",
            name = name, frames = "p1164r12p1233r15p1392r14",
            edge_left = 1L, edge_right = 144L, edge_bottom = 1L, edge_top = 156L,
            created_at = "2026-05-01T10:00:00Z",
            description = "Sketchy crimps",
            move_count = 3L,
            created_by_pubkey = ownPubkey,
            // 64 lowercase hex characters — must match HEX64_REGEX in validate().
            frames_hash = "f".repeat(64),
            board_brand = boardBrand,
        )
        // Stats row at angle 40, setter grade id 18 (V5 / 6c+).
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 18.0, difficulty_average = null,
            quality_average = null, ascensionist_count = 0L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
        return uuid
    }

    private fun seedPublishedOwnClimb(uuid: String) {
        seedDraft(uuid, name = "Published Climb")
        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "e".repeat(64),
            nostr_d_tag = "cruxcoach:climb:${ownPubkey.take(8)}:$uuid",
            pubkey = ownPubkey,
            uuid = uuid,
        )
        // Simulate Kilter sync state set by the publisher.
        db.boardQueries.markKilterPublishSynced(
            kilter_synced_at = 1714000000L,
            kilter_publish_via = "self",
            uuid = uuid,
        )
    }

    private fun rowFor(uuid: String) = db.boardQueries.getClimbByUuid(40L, uuid).executeAsOne()

    private fun mockedExport(boardRepo: BoardRepositoryImpl, pubkey: String? = ownPubkey): String =
        CruxCoachBackup.export(
            categories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = mockk<UserRepository>(relaxed = true).also {
                every { it.getActiveProfile() } returns null
            },
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true).also {
                every { it.getAll() } returns emptyList()
            },
            workoutRepository = mockk<WorkoutRepository>(relaxed = true).also {
                every { it.getAll() } returns emptyList()
            },
            climbRepository = mockk<ClimbRepository>(relaxed = true).also {
                every { it.getAll() } returns emptyList()
            },
            planRepository = mockk<PlanRepository>(relaxed = true),
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true).also {
                every { it.getUserAscentsAll() } returns emptyList()
                every { it.getRawBidsForUser() } returns emptyList()
                every { it.getAllBoardSessions() } returns emptyList()
                every { it.getClimbListEntriesRaw() } returns emptyList()
                every { it.getAllClimbLists() } returns emptyList()
            },
            boardRepository = boardRepo,
            exportedAt = "2026-05-07T12:00:00Z",
            nostrPubkey = pubkey,
        )

    private fun mockedImport(boardRepo: BoardRepositoryImpl, json: String): CruxCoachBackup.ImportResult =
        CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = mockk<UserRepository>(relaxed = true).also {
                every { it.getActiveProfile() } returns null
            },
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true),
            workoutRepository = mockk<WorkoutRepository>(relaxed = true).also {
                every { it.getAll() } returns emptyList()
            },
            climbRepository = mockk<ClimbRepository>(relaxed = true).also {
                every { it.getAll() } returns emptyList()
            },
            planRepository = mockk<PlanRepository>(relaxed = true).also {
                every { it.getAllPlans(any()) } returns emptyList()
            },
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true).also {
                every { it.getUserAscentsAll() } returns emptyList()
                every { it.getRawBidsForUser() } returns emptyList()
                every { it.getAllBoardSessions() } returns emptyList()
            },
            boardRepository = boardRepo,
            transactionRunner = passThroughTxn,
            expectedNostrPubkey = ownPubkey,
        )

    // ── Round-trip: draft + published climb survive export → wipe → import ──

    @Test
    fun `published own-climb round-trips with all provenance intact`() {
        val uuid = "11111111-2222-3333-4444-555555555551"
        seedPublishedOwnClimb(uuid)
        val before = rowFor(uuid)

        val json = mockedExport(boardRepo)

        // Wipe the row from the board DB.
        driver.execute(null, "DELETE FROM climbs WHERE uuid = '$uuid'", 0)
        driver.execute(null, "DELETE FROM climb_stats WHERE climb_uuid = '$uuid'", 0)
        assertNull(db.boardQueries.getClimbByUuid(40L, uuid).executeAsOneOrNull(), "wipe sanity")

        val result = mockedImport(boardRepo, json)
        assertEquals(1, result.ownClimbs, "one own climb imported")
        assertEquals(1, result.ownClimbStats, "one stats row restored")

        val after = rowFor(uuid)
        // Editor-domain fields
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.name, after.name)
        assertEquals(before.frames, after.frames)
        assertEquals(before.description, after.description)
        assertEquals(before.move_count, after.move_count)
        assertEquals(before.created_at, after.created_at)
        assertEquals(before.layout_id, after.layout_id)
        assertEquals(before.setter_username, after.setter_username)
        // Edges
        assertEquals(before.edge_left, after.edge_left)
        assertEquals(before.edge_right, after.edge_right)
        assertEquals(before.edge_bottom, after.edge_bottom)
        assertEquals(before.edge_top, after.edge_top)
        // Nostr provenance
        assertEquals(before.source, after.source)
        assertEquals(before.sync_status, after.sync_status)
        assertEquals(before.nostr_event_id, after.nostr_event_id)
        assertEquals(before.nostr_d_tag, after.nostr_d_tag)
        assertEquals(before.created_by_pubkey, after.created_by_pubkey)
        assertEquals(before.frames_hash, after.frames_hash)
        assertEquals(before.origin, after.origin)
        // Kilter lifecycle
        assertEquals(before.kilter_status, after.kilter_status)
        assertEquals(before.kilter_synced_at, after.kilter_synced_at)
        assertEquals(before.kilter_publish_via, after.kilter_publish_via)
        // Board family (FEAT-031)
        assertEquals(before.board_brand, after.board_brand)
        // Stats
        val statsAfter = db.boardQueries.getClimbStatsForUuid(uuid).executeAsOneOrNull()
        assertNotNull(statsAfter, "stats row restored")
        assertEquals(40, statsAfter.angle.toInt())
        assertEquals(18.0, statsAfter.display_difficulty)
    }

    // ── FEAT-031: a non-Kilter own-climb keeps its board brand on restore ──
    // Regression guard for the backup chain that dropped board_brand: a
    // MoonBoard draft used to restore as 'kilter' (column DEFAULT) while
    // keeping its MoonBoard layout_id — a brand/layout mismatch. The default
    // masked it because every prior test seeded board_brand='kilter'.

    @Test
    fun `non-Kilter own-climb keeps its board_brand through export-import`() {
        val uuid = "33333333-4444-5555-6666-777777777771"
        // MoonBoard layout id (2) — distinct from Kilter's so a default-to-kilter
        // regression would leave board_brand='kilter' on a MoonBoard layout.
        seedDraft(uuid, name = "MoonBoard Draft", boardBrand = "moonboard", layoutId = 2L)
        val before = rowFor(uuid)
        assertEquals("moonboard", before.board_brand, "seed sanity")

        val json = mockedExport(boardRepo)

        driver.execute(null, "DELETE FROM climbs WHERE uuid = '$uuid'", 0)
        driver.execute(null, "DELETE FROM climb_stats WHERE climb_uuid = '$uuid'", 0)
        assertNull(db.boardQueries.getClimbByUuid(40L, uuid).executeAsOneOrNull(), "wipe sanity")

        val result = mockedImport(boardRepo, json)
        assertEquals(1, result.ownClimbs, "one own climb imported")

        val after = rowFor(uuid)
        assertEquals("moonboard", after.board_brand, "board_brand survived the round-trip")
        assertEquals(before.layout_id, after.layout_id, "layout_id unchanged")
    }

    // ── Identity isolation: export filter scopes to current pubkey ──

    @Test
    fun `export filters by pubkey - rows authored by other identity are excluded`() {
        val mineUuid = "11111111-2222-3333-4444-555555555551"
        val theirsUuid = "22222222-3333-4444-5555-666666666661"
        seedDraft(mineUuid)
        // Plant a row with someone else's pubkey via direct SQL — easier
        // than reseeding through insertLocalDraft (which always passes
        // ownPubkey from the caller).
        driver.execute(null, """
            INSERT INTO climbs(uuid, layout_id, name, frames, source, origin, sync_status,
                               frames_count, is_listed, created_by_pubkey, created_at)
            VALUES ('$theirsUuid', 1, 'Other Identity Draft', X'',
                    'local', 'cruxcoach', 'draft', 1, 1, '$otherPubkey', '2026-05-02T10:00:00Z')
        """.trimIndent(), 0)

        val json = mockedExport(boardRepo, pubkey = ownPubkey)
        val preview = CruxCoachBackup.preview(json)

        // Only OUR climb made it into the envelope. The other-identity
        // row is silently filtered at export-time SQL.
        assertEquals(1, preview.ownClimbs)
    }

    @Test
    fun `export with null pubkey emits no own climbs`() {
        seedDraft("11111111-2222-3333-4444-555555555551")
        val json = mockedExport(boardRepo, pubkey = null)
        val preview = CruxCoachBackup.preview(json)
        // Per the export-side guard: no pubkey → no scope → skip the
        // own-climb path entirely (defence against a multi-account
        // device leaking every identity's drafts).
        assertEquals(0, preview.ownClimbs)
    }

    // ── Idempotency: re-import is a no-op for already-restored rows ──

    @Test
    fun `re-import is idempotent - INSERT OR IGNORE preserves the existing row`() {
        val uuid = "11111111-2222-3333-4444-555555555551"
        seedPublishedOwnClimb(uuid)

        val json = mockedExport(boardRepo)

        // First import: row already exists locally (we never wiped),
        // so INSERT OR IGNORE no-ops and the count goes to skipped.
        val result = mockedImport(boardRepo, json)
        assertEquals(0, result.ownClimbs, "no fresh inserts — row already there")
        assertEquals(1, result.skippedDuplicates, "skip count picks up the no-op")

        // Stats restore is upsert (INSERT OR REPLACE) so it always
        // counts as imported.
        assertEquals(1, result.ownClimbStats)
    }

    // ── Backward compat: v2 envelope (no boardClimbs field) imports cleanly ──

    @Test
    fun `v2 envelope imports cleanly with zero own climbs`() {
        val v2Json = """
            {
              "version": 2,
              "exportedAt": "2026-05-07T12:00:00Z",
              "nostrPubkey": "$ownPubkey"
            }
        """.trimIndent()
        val result = mockedImport(boardRepo, v2Json)
        assertEquals(0, result.ownClimbs)
        assertEquals(0, result.ownClimbStats)
        assertTrue(result.ownClimbs == 0)
    }

    // ── Tombstone exclusion: deleted own-climbs never enter the envelope ──

    @Test
    fun `tombstoned own-climbs are excluded from export`() {
        val uuid = "11111111-2222-3333-4444-555555555551"
        seedPublishedOwnClimb(uuid)
        // Mark deleted via the SQL the deleter uses in production.
        db.boardQueries.markCommunityClimbDeleted(
            uuid = uuid, pubkey = ownPubkey, tombstone_iso = "2026-05-07T12:00:00Z",
        )

        val json = mockedExport(boardRepo)
        val preview = CruxCoachBackup.preview(json)
        // Tombstones don't ride the backup — restoring one on another
        // device would re-broadcast a deletion intent the user already
        // actioned.
        assertEquals(0, preview.ownClimbs)
    }
}
