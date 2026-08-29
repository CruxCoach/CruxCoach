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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        hsm: Long = 0L,
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
            // FEAT-049: MoonBoard rows derive hsm on insert; a Kilter fixture has none.
            hsm = hsm,
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
            created_at = "2026-01-01T00:00:00Z",
            uuid = uuid,
        )
        // Simulate Kilter sync state set by the publisher.
        db.boardQueries.markKilterPublishSynced(
            kilter_synced_at = 1714000000L,
            kilter_publish_via = "self",
            uuid = uuid,
        )
    }

    private fun statLayoutId(uuid: String): Long = driver.executeQuery(
        null,
        "SELECT layout_id FROM climb_stats WHERE climb_uuid = '$uuid' LIMIT 1",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) (cursor.getLong(0) ?: -1L) else -1L,
            )
        },
        0,
    ).value

    private data class StatSnapshot(
        val displayDifficulty: Double?,
        val difficultyAverage: Double?,
        val qualityAverage: Double?,
        val ascensionistCount: Long?,
        val benchmarkDifficulty: Double?,
    )

    private fun statFor(uuid: String): StatSnapshot = driver.executeQuery(
        null,
        """SELECT display_difficulty, difficulty_average, quality_average,
                  ascensionist_count, benchmark_difficulty
             FROM climb_stats WHERE climb_uuid = '$uuid' LIMIT 1""",
        { cursor ->
            check(cursor.next().value) { "Missing stats for $uuid" }
            app.cash.sqldelight.db.QueryResult.Value(
                StatSnapshot(
                    displayDifficulty = cursor.getDouble(0),
                    difficultyAverage = cursor.getDouble(1),
                    qualityAverage = cursor.getDouble(2),
                    ascensionistCount = cursor.getLong(3),
                    benchmarkDifficulty = cursor.getDouble(4),
                ),
            )
        },
        0,
    ).value

    private fun rowFor(uuid: String) = db.boardQueries.getClimbByUuid(40L, uuid).executeAsOne()

    private fun climbCount(uuid: String): Long = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM climbs WHERE uuid = '$uuid'",
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value

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

    @Test
    fun `hidden published Quantum own climb lifecycle stats and hsm round-trip in backup version 3`() {
        val uuid = "4f06c97d-a92f-5ec0-a02f-b19f5db0ce45"
        seedDraft(
            uuid = uuid,
            name = "Quantum Draft",
            boardBrand = "quantum",
            layoutId = 9101L,
            hsm = 31L,
        )
        db.boardQueries.markClimbPublishedNostr(
            nostr_event_id = "1".repeat(64),
            nostr_d_tag = "cruxcoach:climb:${ownPubkey.take(8)}:$uuid",
            pubkey = ownPubkey,
            created_at = "2026-08-24T12:00:00Z",
            uuid = uuid,
        )
        driver.execute(
            null,
            "UPDATE climbs SET is_listed = 0, kilter_status = 'failed', " +
                "kilter_error = 'not eligible on Quantum' WHERE uuid = '$uuid'",
            0,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = 40L,
            display_difficulty = 18.0,
            difficulty_average = 18.75,
            quality_average = 2.4,
            ascensionist_count = 37L,
            benchmark_difficulty = 19.0,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )

        val json = mockedExport(boardRepo)
        assertEquals(
            3,
            Json.parseToJsonElement(json).jsonObject.getValue("version").jsonPrimitive.int,
            "Quantum support remains additive inside the established v3 format",
        )
        driver.execute(null, "DELETE FROM climb_stats WHERE climb_uuid = '$uuid'", 0)
        driver.execute(null, "DELETE FROM climbs WHERE uuid = '$uuid'", 0)

        val result = mockedImport(boardRepo, json)

        assertEquals(1, result.ownClimbs)
        assertEquals(1, result.ownClimbStats)
        val climb = rowFor(uuid)
        assertEquals("quantum", climb.board_brand)
        assertEquals(9101L, climb.layout_id)
        assertEquals(31L, climb.hsm)
        assertEquals(0L, climb.is_listed, "hidden own climb must not be resurrected as listed")
        assertEquals("local", climb.source)
        assertEquals("published_nostr", climb.sync_status)
        assertEquals("1".repeat(64), climb.nostr_event_id)
        assertEquals("failed", climb.kilter_status)
        assertEquals("not eligible on Quantum", climb.kilter_error)
        val stats = statFor(uuid)
        assertEquals(9101L, statLayoutId(uuid))
        assertEquals(18.0, stats.displayDifficulty)
        assertEquals(18.75, stats.difficultyAverage)
        assertEquals(2.4, stats.qualityAverage)
        assertEquals(37L, stats.ascensionistCount)
        assertEquals(19.0, stats.benchmarkDifficulty)
    }

    @Test
    fun `v0_2_1 version 3 own-climb golden restores with legacy defaults`() {
        // Captures the v0.2.1 wire shape: boardClimbs and boardClimbStats are
        // present, while later additive fields such as boardBrand are absent.
        // It must remain readable without a backup-version bump.
        val uuid = "521f8b6d-1d36-4d9f-8f83-62f012a92c11"
        val v021Json = """
            {
              "version": 3,
              "app": "CruxCoach",
              "exportedAt": "2026-07-18T09:42:11Z",
              "nostrPubkey": "$ownPubkey",
              "boardClimbs": [{
                "uuid": "$uuid",
                "layoutId": 1,
                "setterUsername": "alice",
                "name": "v0.2.1 backup climb",
                "frames": "p1164r12p1233r15p1392r14",
                "edgeLeft": 1,
                "edgeRight": 144,
                "edgeBottom": 1,
                "edgeTop": 156,
                "createdAt": "2026-07-17T18:20:00Z",
                "description": "Golden backup fixture",
                "moveCount": 3,
                "source": "nostr",
                "origin": "cruxcoach",
                "syncStatus": "published_nostr",
                "createdByPubkey": "$ownPubkey",
                "framesHash": "${"f".repeat(64)}",
                "nostrEventId": "${"e".repeat(64)}",
                "nostrDTag": "cruxcoach:climb:aaaaaaaa:$uuid",
                "nostrPublishVia": "direct",
                "kilterStatus": "synced",
                "kilterSyncedAt": 1721300000,
                "kilterPublishVia": "self",
                "kilterAuthorUuid": "3fc3c2bc-0000-1111-2222-333333333333"
              }],
              "boardClimbStats": [{
                "climbUuid": "$uuid",
                "angle": 40,
                "displayDifficulty": 18.0,
                "difficultyAverage": 18.5,
                "qualityAverage": 2.7,
                "ascensionistCount": 12,
                "benchmarkDifficulty": 19.0
              }]
            }
        """.trimIndent()

        val result = mockedImport(boardRepo, v021Json)

        assertEquals(1, result.ownClimbs)
        assertEquals(1, result.ownClimbStats)
        val climb = rowFor(uuid)
        assertEquals("kilter", climb.board_brand, "missing additive brand keeps the legacy default")
        assertEquals("published_nostr", climb.sync_status)
        assertEquals("synced", climb.kilter_status)
        assertEquals("self", climb.kilter_publish_via)
        assertEquals("3fc3c2bc-0000-1111-2222-333333333333", climb.kilter_author_uuid)
        assertEquals(1L, climb.is_listed, "missing v0.2.1 field keeps the legacy default")
        val stat = statFor(uuid)
        assertEquals(18.0, stat.displayDifficulty)
        assertEquals(18.5, stat.difficultyAverage)
        assertEquals(2.7, stat.qualityAverage)
        assertEquals(12L, stat.ascensionistCount)
        assertEquals(19.0, stat.benchmarkDifficulty)

        val reExported = mockedExport(boardRepo)
        assertEquals(
            3,
            Json.parseToJsonElement(reExported).jsonObject.getValue("version").jsonPrimitive.int,
            "additive 0.2.2 fields do not bump the established backup format",
        )
        assertEquals(1, CruxCoachBackup.preview(reExported).ownClimbs)
        assertEquals(
            1,
            Json.parseToJsonElement(reExported).jsonObject
                .getValue("boardClimbStats").jsonArray.size,
        )
    }

    @Test
    fun `catalogue-first own climb is excluded from export then promoted by its signed backup`() {
        val uuid = "674ff8bd-f702-4ba4-8aa9-cc529aa33333"
        driver.execute(null, """
            INSERT INTO climbs(
                uuid, layout_id, setter_username, name, frames, frames_count,
                is_listed, created_at, description, hsm, move_count, is_deleted,
                source, origin, sync_status, created_by_pubkey, board_brand
            ) VALUES (
                '$uuid', 1, 'alice', 'Public catalogue copy', X'', 1,
                1, '2026-08-23T00:00:00Z', 'public fields only', 0, 3, 0,
                'kilter', 'cruxcoach', 'synced', '$ownPubkey', 'kilter'
            )
        """.trimIndent(), 0)
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = 40L,
            display_difficulty = 18.0,
            difficulty_average = null,
            quality_average = null,
            ascensionist_count = 0L,
            benchmark_difficulty = null,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )

        val catalogueOnlyExport = Json.parseToJsonElement(mockedExport(boardRepo)).jsonObject
        assertEquals(0, catalogueOnlyExport.getValue("boardClimbs").jsonArray.size)
        assertEquals(0, catalogueOnlyExport.getValue("boardClimbStats").jsonArray.size)

        val privateBackup = """
            {
              "version": 3,
              "exportedAt": "2026-08-24T00:00:00Z",
              "nostrPubkey": "$ownPubkey",
              "boardClimbs": [{
                "uuid": "$uuid",
                "layoutId": 1,
                "name": "Private lifecycle snapshot",
                "frames": "p100r15",
                "description": "content must not replace the catalogue copy",
                "isListed": false,
                "source": "nostr",
                "syncStatus": "published_nostr",
                "createdByPubkey": "$ownPubkey",
                "framesHash": "${"f".repeat(64)}",
                "nostrEventId": "${"e".repeat(64)}",
                "nostrDTag": "cruxcoach:climb:${ownPubkey.take(8)}:$uuid",
                "nostrPublishVia": "direct",
                "kilterStatus": "failed",
                "kilterPublishVia": "self",
                "kilterError": "retry me",
                "boardBrand": "kilter"
              }],
              "boardClimbStats": [{
                "climbUuid": "$uuid",
                "angle": 40,
                "displayDifficulty": 19.0,
                "difficultyAverage": 19.5,
                "qualityAverage": 2.1,
                "ascensionistCount": 4
              }]
            }
        """.trimIndent()

        val result = mockedImport(boardRepo, privateBackup)

        assertEquals(0, result.ownClimbs, "catalogue UUID remains an existing row")
        assertEquals(1, result.ownClimbStats, "same-owner catalogue UUID accepts its private stats")
        val promoted = rowFor(uuid)
        assertEquals("Public catalogue copy", promoted.name, "public content is not overwritten")
        assertEquals("nostr", promoted.source)
        assertEquals("published_nostr", promoted.sync_status)
        assertEquals(0L, promoted.is_listed)
        assertEquals("failed", promoted.kilter_status)
        assertEquals("retry me", promoted.kilter_error)
        assertEquals(listOf(uuid), boardRepo.getClimbsAwaitingKilterRetry(ownPubkey).map { it.uuid })

        val promotedExport = mockedExport(boardRepo)
        assertEquals(1, CruxCoachBackup.preview(promotedExport).ownClimbs)
        assertEquals(1, Json.parseToJsonElement(promotedExport).jsonObject
            .getValue("boardClimbStats").jsonArray.size)
    }

    @Test
    fun `own-climb UUID collision preserves unrelated catalogue lifecycle and stats`() {
        val uuid = "674ff8bd-f702-4ba4-8aa9-cc529aa22222"
        driver.execute(null, """
            INSERT INTO climbs(
                uuid, layout_id, setter_username, name, frames, frames_count,
                is_listed, created_at, description, hsm, move_count, is_deleted,
                source, origin, sync_status, created_by_pubkey, frames_hash,
                nostr_event_id, kilter_status, kilter_synced_at,
                kilter_publish_via, kilter_error, board_brand
            ) VALUES (
                '$uuid', 1, 'catalogue-setter', 'Catalogue Original', X'', 1,
                1, '2025-01-02T03:04:05Z', 'remote catalogue row', 0, 7, 0,
                'kilter', 'kilter', 'synced', '$otherPubkey', '${"c".repeat(64)}',
                '${"d".repeat(64)}', 'synced', 1700000000,
                'official', 'keep this error', 'kilter'
            )
        """.trimIndent(), 0)
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = 40L,
            display_difficulty = 12.0,
            difficulty_average = 12.5,
            quality_average = 2.9,
            ascensionist_count = 222L,
            benchmark_difficulty = 13.0,
            fa_username = "first-ascent",
            fa_at = "2025-01-03T00:00:00Z",
            official_kilter_difficulty = 12L,
        )
        val beforeClimb = rowFor(uuid)
        val beforeStat = statFor(uuid)
        val collisionJson = """
            {
              "version": 3,
              "exportedAt": "2026-08-24T00:00:00Z",
              "nostrPubkey": "$ownPubkey",
              "boardClimbs": [{
                "uuid": "$uuid",
                "layoutId": 9101,
                "name": "Injected replacement",
                "frames": "p100r15",
                "description": "must not overwrite",
                "source": "local",
                "syncStatus": "draft",
                "createdByPubkey": "$ownPubkey",
                "framesHash": "${"f".repeat(64)}",
                "boardBrand": "quantum"
              }],
              "boardClimbStats": [{
                "climbUuid": "$uuid",
                "angle": 40,
                "displayDifficulty": 30.0,
                "difficultyAverage": 30.5,
                "qualityAverage": 0.1,
                "ascensionistCount": 1,
                "benchmarkDifficulty": 31.0
              }]
            }
        """.trimIndent()

        val result = mockedImport(boardRepo, collisionJson)

        assertEquals(0, result.ownClimbs)
        assertEquals(0, result.ownClimbStats)
        assertEquals(2, result.skippedDuplicates, "both the colliding climb and its stat are skipped")
        val afterClimb = rowFor(uuid)
        val afterStat = statFor(uuid)
        assertEquals(beforeClimb, afterClimb, "catalogue data and lifecycle remain byte-for-byte equivalent")
        assertEquals(beforeStat, afterStat, "colliding backup stats cannot replace catalogue stats")
    }

    @Test
    fun `identity mismatch is rejected before any own-climb database write`() {
        val uuid = "de3c3114-11e5-4a56-9420-67d6adadbeef"
        val wrongIdentityJson = """
            {
              "version": 3,
              "exportedAt": "2026-08-24T00:00:00Z",
              "nostrPubkey": "$otherPubkey",
              "boardClimbs": [{
                "uuid": "$uuid",
                "layoutId": 9101,
                "name": "Wrong signer climb",
                "frames": "p100r15",
                "source": "nostr",
                "syncStatus": "published_nostr",
                "createdByPubkey": "$otherPubkey",
                "nostrEventId": "${"e".repeat(64)}",
                "boardBrand": "quantum"
              }],
              "boardClimbStats": [{
                "climbUuid": "$uuid",
                "angle": 40,
                "displayDifficulty": 20.0
              }]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            mockedImport(boardRepo, wrongIdentityJson)
        }
        assertEquals(0L, climbCount(uuid), "identity rejection happens before board DB restore")
        assertNull(db.boardQueries.getClimbStatsForUuid(uuid).executeAsOneOrNull())
    }

    @Test
    fun `missing identity is rejected before any selected own-climb database write`() {
        val uuid = "de3c3114-11e5-4a56-9420-67d6ada0beef"
        val missingIdentityJson = """
            {
              "version": 3,
              "exportedAt": "2026-08-24T00:00:00Z",
              "boardClimbs": [{
                "uuid": "$uuid",
                "layoutId": 9101,
                "name": "Unbound draft",
                "frames": "p100r15",
                "source": "local",
                "syncStatus": "draft",
                "framesHash": "${"f".repeat(64)}",
                "boardBrand": "quantum"
              }],
              "boardClimbStats": [{
                "climbUuid": "$uuid",
                "angle": 40,
                "displayDifficulty": 20.0
              }]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            mockedImport(boardRepo, missingIdentityJson)
        }
        assertEquals(0L, climbCount(uuid), "identity rejection happens before board DB restore")
        assertNull(db.boardQueries.getClimbStatsForUuid(uuid).executeAsOneOrNull())
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

    // ── Kilter authorship survives the round-trip ──
    // kilter_author_uuid is the publish gate: NULL = "unknown author →
    // not publishable". Pre-fix it was absent from the export query, the
    // wire model AND the restore INSERT, so an own Kilter-published climb
    // came back un-republishable after every restore.

    @Test
    fun `kilter_author_uuid round-trips for a published own climb`() {
        val uuid = "11111111-2222-3333-4444-555555555551"
        val authorUuid = "3fc3c2bc-0000-1111-2222-333333333333"
        seedPublishedOwnClimb(uuid)
        db.boardQueries.setClimbKilterAuthorUuid(authorUuid = authorUuid, uuid = uuid)
        assertEquals(authorUuid, rowFor(uuid).kilter_author_uuid, "seed sanity")

        val json = mockedExport(boardRepo)

        driver.execute(null, "DELETE FROM climbs WHERE uuid = '$uuid'", 0)
        driver.execute(null, "DELETE FROM climb_stats WHERE climb_uuid = '$uuid'", 0)

        mockedImport(boardRepo, json)

        assertEquals(
            authorUuid,
            rowFor(uuid).kilter_author_uuid,
            "kilter_author_uuid restored — climb stays republishable",
        )
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
