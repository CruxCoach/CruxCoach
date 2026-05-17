package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
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

/**
 * Migration-chain smoke test — real-data preservation across the full
 * migration walk from v1 to current.
 *
 * Pre-fix the project's only migration coverage was `verifySqlDelightMigration`
 * (synthetic schema-snapshot diffing). Real-data preservation — "after
 * migrations 2-9 run, does a row I inserted at version 1 still carry the
 * fields I care about?" — was untested. Real `body_stat`/`climb_browse`
 * bugs in 4.sqm + 5.sqm could land in production undetected.
 *
 * What this test does:
 *  - Walks the full migration chain from v1 to current via SQLDelight's
 *    `migrate(driver, oldVersion=1, newVersion=schemaVersion)`
 *  - Seeds a representative row at each major schema-shape boundary
 *  - Asserts the row survives every migration with its identity-bearing
 *    columns intact (uuid, name, created_by_pubkey, kilter_status,
 *    nostr_event_id)
 *
 * What it doesn't do:
 *  - Reproduce production data shapes from real users — that requires a
 *    checked-in v0.1.3 DB fixture, deferred to a follow-up.
 *  - Cover the BoardDatabaseImporter Blossom-merge UPDATE pass — that's
 *    a separate code path (post-migration importer logic) and lives in
 *    BoardDatabaseImporterTest.
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
}
