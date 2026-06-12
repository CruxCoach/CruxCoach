package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration test for getClimbByUuidNormalized (Point 1 of the
 * "Climb nicht gefunden" fix).
 *
 * The board DB mixes uuid formats: legacy rows are nodash-UPPERCASE, new-world
 * rows are dashed-lowercase. A Kilter logbook-imported uuid can therefore fail
 * the exact/case getClimbByUuid lookups even though the same climb is stored
 * under a differently-formatted uuid. The normalized lookup strips hyphens +
 * lowercases on both sides so it resolves regardless of format.
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * BoardSizeFitFilterTest.
 */
class ClimbUuidNormalizedLookupTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val brand = "kilter"

    // Same climb, two storage formats.
    private val dashedLower = "a30d8042-aeea-42ce-8015-239016c87769"
    private val nodashUpper = "A30D8042AEEA42CE8015239016C87769"

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-uuid-normalize-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun insertClimb(uuid: String) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "s", name = "Tallakrennesvingen",
            frames = "p100r12p101r14",
            edge_left = null, edge_right = null, edge_bottom = null, edge_top = null,
            created_at = "2026-06-01T00:00:00Z", description = "", move_count = 1L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 25L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.5, ascensionist_count = 10L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    @Test
    fun dashedLowercaseQuery_resolvesNodashUppercaseStoredRow() {
        insertClimb(nodashUpper)

        // Exact / lower / upper all miss because the stored uuid carries no
        // hyphens but the query string does.
        assertNull(repo.getClimbByUuid(dashedLower, 25))
        assertNull(repo.getClimbByUuid(dashedLower.lowercase(), 25))
        assertNull(repo.getClimbByUuid(dashedLower.uppercase(), 25))

        val resolved = repo.getClimbByUuidNormalized(dashedLower, 25)
        assertEquals(nodashUpper, resolved?.uuid)
        // The angle-scoped stats LEFT JOIN still resolves (25° row present).
        assertEquals(15.0, resolved?.difficultyAverage)
    }

    @Test
    fun nodashUppercaseQuery_resolvesDashedLowercaseStoredRow() {
        insertClimb(dashedLower)

        assertNull(repo.getClimbByUuid(nodashUpper, 25))

        val resolved = repo.getClimbByUuidNormalized(nodashUpper, 25)
        assertEquals(dashedLower, resolved?.uuid)
    }

    @Test
    fun unknownUuid_returnsNull() {
        insertClimb(dashedLower)
        assertNull(repo.getClimbByUuidNormalized("ffffffff-0000-0000-0000-000000000000", 25))
    }
}
