package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.AfterVersion
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
 * Board schema checks. The first test materialises the historical v1 DDL,
 * seeds data, and executes every checked-in SQLDelight migration through the
 * current version. The remaining tests exercise current-schema query shapes.
 */
class MigrationSmokeTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-migration-smoke-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `version 1 board data survives the complete migration chain`() {
        driver.applyHistoricalSchema("schema/board-v1.sql")
        driver.execute(
            null,
            """
                INSERT INTO aurora_climb(
                    uuid, layout_id, setter_username, name, frames_count,
                    created_at, frames
                ) VALUES (
                    'ABC-DEF', 1, 'setter', 'Historical climb', 1,
                    '2026-01-01 00:00:00', CAST('p1100r12' AS BLOB)
                )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO aurora_sync_state VALUES ('aurora_climb', '2026-01-01')",
            0,
        )

        // v1 climbs pre-date provenance. Once migration 4 has introduced the
        // columns, model a locally-authored row so the intentional Kilter
        // catalogue wipe in migrations 6/7 does not discard this fixture.
        BoardDatabase.Schema.migrate(
            driver,
            oldVersion = 1L,
            newVersion = BoardDatabase.Schema.version,
            AfterVersion(4L) { migrationDriver ->
                migrationDriver.execute(
                    null,
                    """
                        UPDATE climbs
                        SET source = 'local', origin = 'cruxcoach',
                            sync_status = 'draft', created_by_pubkey = 'pubkey-1'
                        WHERE uuid = 'ABC-DEF'
                    """.trimIndent(),
                    0,
                )
            },
        )

        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter))
        val climb = db.boardQueries.getMyClimbs("pubkey-1").executeAsOne()
        assertEquals("abc-def", climb.uuid)
        assertEquals("Historical climb", climb.name)
        assertEquals("cruxcoach", climb.origin)
        assertEquals("kilter", climb.board_brand)
        assertEquals("p1100r12", climb.frames)
        assertEquals(
            "2026-01-01",
            db.boardQueries.getSyncState("climbs").executeAsOne(),
            "the v2 table-name rewrite must preserve sync state",
        )
    }

    @Test
    fun `current schema creates without error and supports the new kilter_publish_attempts table`() {
        // The latest schema includes kilter_publish_attempts (added in 9.sqm).
        // If the migration body has a syntax error or references a column
        // that the prior version's CREATE TABLE didn't ship with, this
        // create() call throws. Pre-9.sqm schemas wouldn't have the
        // table at all — failing here would mean the new migration
        // doesn't compose cleanly with existing schema state.
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        // Sanity: verify the new B1 table exists and accepts an insert.
        db.boardQueries.recordKilterPublishAttempt(
            climb_uuid = "u-1",
            attempted_at = 1_715_000_000_000L,
            op = "create",
            via = "self",
            outcome = "success",
            http_code = 200L,
            error_excerpt = null,
        )
        val rows = db.boardQueries.getKilterPublishAttempts("u-1", 10).executeAsList()
        assertEquals(1, rows.size, "newly inserted attempt must round-trip")
        assertEquals(200L, rows[0].http_code)
    }

    @Test
    fun `recordKilterPublishAttempt round-trips with httpCode and outcome columns`() {
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        // Append a series of attempts for the same climb. The retry
        // worker writes one row per attempt; the per-climb history
        // query (used by the future detail view) reads them latest-first.
        val uuid = "u-history"
        listOf(
            Triple("transient", null as Long?, 1_000L),
            Triple("transient", 503L, 2_000L),
            Triple("permanent", 400L, 3_000L),
        ).forEach { (outcome, httpCode, ts) ->
            db.boardQueries.recordKilterPublishAttempt(
                climb_uuid = uuid,
                attempted_at = ts,
                op = "create",
                via = "self",
                outcome = outcome,
                http_code = httpCode,
                error_excerpt = "ex-$outcome",
            )
        }

        val rows = db.boardQueries.getKilterPublishAttempts(uuid, 10).executeAsList()
        assertEquals(3, rows.size)
        // Latest first.
        assertEquals(3_000L, rows[0].attempted_at)
        assertEquals("permanent", rows[0].outcome)
        assertEquals(400L, rows[0].http_code)
        assertEquals(2_000L, rows[1].attempted_at)
        assertEquals(503L, rows[1].http_code)
        assertEquals(1_000L, rows[2].attempted_at)
        assertEquals(null, rows[2].http_code, "first transient had no HTTP round-trip")
    }

    @Test
    fun `getKilterPublishQueueStats reports zero when no attempts and no climbs`() {
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        val stats = db.boardQueries.getKilterPublishQueueStats().executeAsOne()
        assertEquals(0L, stats.pending_count)
        assertEquals(0L, stats.failed_count)
        assertEquals(null, stats.last_attempt_at)
    }

    // ── FEAT-015 board-locations schema queryability ──────────────────
    //
    // `Schema.create()` emits the latest schema directly. We can't
    // exercise the 12.sqm + 13.sqm + 14.sqm migration *scripts* in
    // isolation from this harness (would require a checked-in v11 DB
    // fixture); instead, we cover the resulting shape by asserting
    // the new tables are queryable through the public query API. A
    // missing column or typo in any of the three .sqm files would
    // surface as a SQLDelight code-gen failure long before reaching
    // this test, but the runtime-shape sanity is still useful.

    @Test
    fun `kilter_board_location and kilter_board_wall are queryable in current schema`() {
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        assertEquals(0L, db.kilterBoardLocationQueries.countLocations().executeAsOne())
        assertEquals(0L, db.kilterBoardWallQueries.countWalls().executeAsOne())
        assertTrue(db.kilterBoardLocationQueries.getAllLocations().executeAsList().isEmpty())
        assertTrue(db.kilterBoardWallQueries.getAllWalls().executeAsList().isEmpty())
    }

    @Test
    fun `kilter_board_location upsert + read round-trips all FEAT-015 columns`() {
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        db.kilterBoardLocationQueries.upsertLocation(
            gym_uuid = "g-1", name = "Test Gym",
            lat = 48.137, lng = 11.575,
            address = "Foo St", city = "Munich", country_code = "DE",
            phone = null, email = null, url = null, instagram = null,
            layout_name = "Original", layout_id = 1L,
            size_label = "12x12", product_size_id = 10L,
            access_type = "PUBLIC", adjustability = "ADJUSTABLE",
            fixed_angle = null, frame_maker = "Kilter",
            board_brand = "kilter", wellpass = 1L,
        )
        val rows = db.kilterBoardLocationQueries.getAllLocations().executeAsList()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("g-1", row.gym_uuid)
        assertEquals("Test Gym", row.name)
        assertEquals("DE", row.country_code)
        assertEquals(1L, row.layout_id)
        assertEquals(10L, row.product_size_id)
        assertEquals("PUBLIC", row.access_type)
        assertEquals("ADJUSTABLE", row.adjustability)
        assertEquals("kilter", row.board_brand)
        assertEquals(1L, row.wellpass)
    }

    @Test
    fun `24 sqm migration removes non-public contact data but preserves public contact`() {
        BoardDatabase.Schema.create(driver)
        val db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))

        fun seed(id: String, access: String) {
            db.kilterBoardLocationQueries.upsertLocation(
                gym_uuid = id, name = "Gym $id", lat = 48.1, lng = 11.5,
                address = "Street 1", city = "Munich", country_code = "DE",
                phone = "+49 100", email = "$id@example.test",
                url = "https://$id.example", instagram = id,
                layout_name = "Original", layout_id = 1L,
                size_label = "12x12", product_size_id = 10L,
                access_type = access, adjustability = "ADJUSTABLE",
                fixed_angle = null, frame_maker = "Kilter",
                board_brand = "kilter", wellpass = null,
            )
        }
        seed("public", "PUBLIC")
        seed("private", "PRIVATE")
        seed("members", "MEMBERS")
        seed("unknown", "UNKNOWN")

        BoardDatabase.Schema.migrate(driver, oldVersion = 24L, newVersion = 25L)

        val public = db.kilterBoardLocationQueries.getLocationById("public").executeAsOne()
        assertEquals("Street 1", public.address)
        assertEquals("+49 100", public.phone)
        assertEquals("public@example.test", public.email)
        assertEquals("https://public.example", public.url)
        assertEquals("public", public.instagram)

        for (id in listOf("private", "members", "unknown")) {
            val row = db.kilterBoardLocationQueries.getLocationById(id).executeAsOne()
            assertNull(row.address, "$id address")
            assertNull(row.phone, "$id phone")
            assertNull(row.email, "$id email")
            assertNull(row.url, "$id url")
            assertNull(row.instagram, "$id instagram")
            assertEquals("Gym $id", row.name)
            assertEquals(48.1, row.lat)
            assertEquals(11.5, row.lng)
        }
    }
}
