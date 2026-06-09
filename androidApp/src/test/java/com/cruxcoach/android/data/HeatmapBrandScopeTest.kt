package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brand-scoping regression for the heatmap + hold-search query
 * (getAllFramesForFilter). Aurora-family boards reuse Kilter's low layout-ids —
 * every board's Original layout is id 1 — so a layout-only query leaked Kilter
 * climbs into an Aurora board's heatmap and hold-search counts. Proves the
 * board_brand predicate isolates each board on the shared layout_id=1.
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as BoardLedMapTest.
 */
class HeatmapBrandScopeTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-heatmap-scope-")
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

    /** A listed climb on (brand, layout 1) + an angle-40 stats row (layout_id is
     *  derived from the climb by upsertClimbStat). */
    private fun climb(uuid: String, brand: String, frames: String) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "s", name = uuid,
            frames = frames, edge_left = 0L, edge_right = 144L, edge_bottom = 0L, edge_top = 156L,
            created_at = "2026-06-08T10:00:00Z", description = "", move_count = 1L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = null, ascensionist_count = 50L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    @Test
    fun heatmapFrames_areScopedToBoardBrand_onSharedLayout1() {
        climb("k1", "kilter", "p100r12p101r13")        // Kilter Original (layout 1)
        climb("g1", "grasshopper", "p300r12p301r13")   // Grasshopper (also layout 1)

        val gh = repo.getAllFramesForHeatmap(40, 1, "grasshopper", 0.0, 100.0, 0, ClimbTypeFilter.ALL)
        assertEquals(listOf("p300r12p301r13"), gh.map { it.frames }, "grasshopper heatmap must exclude Kilter climbs")

        val kil = repo.getAllFramesForHeatmap(40, 1, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL)
        assertEquals(listOf("p100r12p101r13"), kil.map { it.frames }, "kilter heatmap must exclude Grasshopper climbs")
    }

    @Test
    fun holdSearch_isScopedToBoardBrand_onSharedLayout1() {
        climb("k1", "kilter", "p100r12p101r13")
        climb("g1", "grasshopper", "p300r12p301r13")

        // Kilter's hold 100 must NOT leak into a Grasshopper hold-search, and v.v.
        assertEquals(
            setOf("g1"),
            repo.searchClimbUuidsByAllHolds(listOf("p300r"), 40, 1, "grasshopper", 0.0, 100.0, 0, ClimbTypeFilter.ALL),
        )
        assertTrue(
            repo.searchClimbUuidsByAllHolds(listOf("p100r"), 40, 1, "grasshopper", 0.0, 100.0, 0, ClimbTypeFilter.ALL).isEmpty(),
            "Kilter's hold must not match under the grasshopper brand",
        )
        assertEquals(
            setOf("k1"),
            repo.searchClimbUuidsByAllHolds(listOf("p100r"), 40, 1, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL),
        )
    }
}
