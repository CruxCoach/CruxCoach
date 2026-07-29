package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
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
 * Integration test for the edge-containment leg of the always-on "fits my
 * board" browse filter, asserting STRICT `<`/`>` edge comparison (official
 * Kilter/Aurora parity) rather than the old inclusive `<=`/`>=`.
 *
 * A product_size's mounting frame edges sit ~1 grid unit (4) BEYOND its
 * outermost actually-mounted holes, so a climb whose bbox edge EQUALS the
 * frame edge uses a boundary hole that is NOT mounted on this (smaller) size
 * — under inclusive comparison it was a false-positive that rendered floating
 * off the row. Strict `<`/`>` excludes exactly those boundary cases.
 *
 * Fixture (Tension TB2 size-9 shape): layout 10, size 9 with frame edges
 * (-44/44/0/120). Climbs:
 *   - c-inside       bbox -40/40/4/116  → strictly inside  → INCLUDED
 *   - c-top-boundary bbox -40/40/4/120  → edge_top == frame → EXCLUDED (strict)
 *   - c-community    NULL edges          → lenient guard     → INCLUDED
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * HsmHoldSetFilterTest / HeatmapBrandScopeTest.
 */
class BoardSizeFitFilterTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val brand = "tension"
    private val layoutId = 10
    private val sizeId = 9

    // size-9 mounting frame edges (board-local grid units).
    private val frameLeft = -44L
    private val frameRight = 44L
    private val frameBottom = 0L
    private val frameTop = 120L

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-fit-filter-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // The selected product_size whose frame the fit-EXISTS references.
        repo.upsertProductSize(
            id = sizeId.toLong(), productId = 1L, name = "TB2 size 9",
            edgeLeft = frameLeft, edgeRight = frameRight, edgeBottom = frameBottom, edgeTop = frameTop,
            imageFilename = null, boardBrand = brand,
        )
        // board_images so the size is a real render target for the layout.
        repo.upsertBoardImage(1L, sizeId.toLong(), layoutId.toLong(), 8L, "s9-8.png")

        // Strictly inside the frame in every direction.
        climb("c-inside", edgeLeft = -40L, edgeRight = 40L, edgeBottom = 4L, edgeTop = 116L)
        // Top hold sits ON the frame's top edge (a hole mounted only on taller
        // sizes): inclusive 120 <= 120 passed it; strict 120 > 120 is false.
        climb("c-top-boundary", edgeLeft = -40L, edgeRight = 40L, edgeBottom = 4L, edgeTop = 120L)
        // Community / on-board authored climb with no recorded bbox.
        climb("c-community", edgeLeft = null, edgeRight = null, edgeBottom = null, edgeTop = null)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A listed layout-10 tension climb with the given bbox + a 40° stats row.
     *  insertLocalDraft is the only insert that takes board_brand + nullable
     *  edges directly (same path HeatmapBrandScopeTest uses for non-Kilter
     *  brands). is_listed/frames_count/hsm are fixed to 1/1/0 by the query. */
    private fun climb(uuid: String, edgeLeft: Long?, edgeRight: Long?, edgeBottom: Long?, edgeTop: Long?) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId.toLong(), setter_username = "s", name = uuid,
            frames = "p100r12p101r14",
            edge_left = edgeLeft, edge_right = edgeRight, edge_bottom = edgeBottom, edge_top = edgeTop,
            created_at = "2026-06-01T00:00:00Z", description = "", move_count = 1L,
            // FEAT-049: MoonBoard rows derive hsm on insert; a Kilter fixture has none.
            hsm = 0L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.5, ascensionist_count = 10L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun browseUuids(sel: Int): Set<String> = repo.searchClimbsSorted(
        angle = 40, layoutId = layoutId, boardBrand = brand,
        minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
        sortField = ClimbSortField.ASCENSIONISTS, sortDirection = SortDirection.DESC,
        climbType = ClimbTypeFilter.ALL, selProductSizeId = sel, hsmExcludedMask = 0L,
    ).mapTo(mutableSetOf()) { it.uuid }

    @Test
    fun filterOff_showsEverything() {
        // selProductSizeId <= 0 = filter off sentinel → all rows regardless of fit.
        assertEquals(setOf("c-inside", "c-top-boundary", "c-community"), browseUuids(0))
    }

    @Test
    fun strictFit_excludesBoundaryHold_keepsInsideAndCommunity() {
        assertEquals(
            setOf("c-inside", "c-community"),
            browseUuids(sizeId),
            "edge_top == frame top is a boundary false-positive (strict <): excluded; " +
                "strictly-inside and NULL-edge community climbs stay visible",
        )
    }

    @Test
    fun countAndUuidQueries_agreeWithTheStrictBrowseList() {
        assertEquals(
            2L,
            repo.countFilteredClimbs(40, layoutId, brand, 0.0, 100.0, 0, ClimbTypeFilter.ALL, sizeId, 0L),
        )
        assertEquals(
            setOf("c-inside", "c-community"),
            repo.getAllBrowseMatchingUuids(40, layoutId, brand, 0.0, 100.0, 0, ClimbTypeFilter.ALL, sizeId, 0L).toSet(),
        )
        // Name search carries the same strict fit filter.
        assertTrue(
            repo.searchClimbsByName(
                "c-top-boundary", 40, layoutId, brand,
                climbType = ClimbTypeFilter.ALL, selProductSizeId = sizeId, hsmExcludedMask = 0L,
            ).isEmpty(),
            "a boundary climb must not resurface via name search under strict fit",
        )
    }
}
