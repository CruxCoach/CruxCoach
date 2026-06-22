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
import com.cruxcoach.domain.board.HoldSetMask
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the hold-set leg of the always-on "fits my board"
 * browse filter: `(hsm & :hsmExcludedMask) = 0` in the browse/search/count
 * queries, with the mask derived from board_images via the new
 * getHoldSetIdsForLayout / getHoldSetIdsForLayoutSize queries +
 * HoldSetMask.excludedMask.
 *
 * Homewall-like fixture: layout 8 with hold sets {26, 27} (26 = bit0,
 * 27 = bit1 — hsm bit index = rank of set_id ascending). Size A carries both
 * sets (mask 0 → everything passes); size B carries only 26 (excluded mask
 * 0b10): a set-26-only climb (hsm=1) passes, a climb also using set 27
 * (hsm=3 / hsm=2) is hidden, and hsm=0 (UNKNOWN — ~10% of Kilter rows, all
 * MoonBoard rows) always passes.
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * HeatmapBrandScopeTest.
 */
class HsmHoldSetFilterTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val layoutId = 8
    private val sizeA = 17  // carries sets {26, 27}
    private val sizeB = 18  // carries set {26} only

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-hsm-filter-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // board_images fixture (set universe of layout 8 = {26, 27}).
        repo.upsertBoardImage(1L, sizeA.toLong(), layoutId.toLong(), 26L, "a-26.png")
        repo.upsertBoardImage(2L, sizeA.toLong(), layoutId.toLong(), 27L, "a-27.png")
        repo.upsertBoardImage(3L, sizeB.toLong(), layoutId.toLong(), 26L, "b-26.png")

        // Catalogue rows: one climb per hsm shape.
        climb("c-set26", hsm = 1L)        // uses set 26 only        → fits size B
        climb("c-both", hsm = 3L)         // uses sets 26 + 27       → needs size A
        climb("c-set27", hsm = 2L)        // uses set 27 only        → needs size A
        climb("c-unknown", hsm = 0L)      // hsm unknown             → always passes
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A listed layout-8 Kilter climb with the given hsm + a 40° stats row. */
    private fun climb(uuid: String, hsm: Long) {
        db.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = layoutId.toLong(),
            setter_username = "s",
            name = uuid,
            frames = "p100r12p101r14",
            frames_count = 1L,
            is_listed = 1L,
            edge_left = null, edge_right = null, edge_bottom = null, edge_top = null,
            created_at = "2026-06-01T00:00:00Z",
            description = "",
            is_nomatch = 0L,
            frames_pace = 0L,
            hsm = hsm,
            move_count = 1L,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.5, ascensionist_count = 10L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun maskFor(sizeId: Int): Long = HoldSetMask.excludedMask(
        layoutSetIds = repo.getHoldSetIdsForLayout(layoutId, "kilter"),
        sizeSetIds = repo.getHoldSetIdsForLayoutSize(layoutId, sizeId, "kilter"),
    )

    private fun browseUuids(mask: Long): Set<String> = repo.searchClimbsSorted(
        angle = 40, layoutId = layoutId, boardBrand = "kilter",
        minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
        sortField = ClimbSortField.ASCENSIONISTS, sortDirection = SortDirection.DESC,
        climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = mask,
    ).mapTo(mutableSetOf()) { it.uuid }

    @Test
    fun setIdQueries_returnUniverseAndPerSizeSets() {
        assertEquals(listOf(26L, 27L), repo.getHoldSetIdsForLayout(layoutId, "kilter"))
        assertEquals(listOf(26L, 27L), repo.getHoldSetIdsForLayoutSize(layoutId, sizeA, "kilter"))
        assertEquals(listOf(26L), repo.getHoldSetIdsForLayoutSize(layoutId, sizeB, "kilter"))
        // Unknown size → no set data → mask 0 (lenient).
        assertEquals(0L, maskFor(999))
    }

    @Test
    fun fullSize_maskZero_everythingPasses() {
        assertEquals(0L, maskFor(sizeA))
        assertEquals(setOf("c-set26", "c-both", "c-set27", "c-unknown"), browseUuids(0L))
    }

    @Test
    fun subsetSize_hidesClimbsUsingTheMissingSet() {
        val mask = maskFor(sizeB)
        assertEquals(0b10L, mask, "set 27 is rank 1 of {26,27} → bit1")
        assertEquals(
            setOf("c-set26", "c-unknown"),
            browseUuids(mask),
            "hsm=1 passes, hsm=3 and hsm=2 hidden, hsm=0 (unknown) passes",
        )
    }

    @Test
    fun countAndUuidQueries_agreeWithTheBrowseList() {
        val mask = maskFor(sizeB)
        assertEquals(
            2L,
            repo.countFilteredClimbs(40, layoutId, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, mask),
        )
        assertEquals(
            setOf("c-set26", "c-unknown"),
            repo.getAllBrowseMatchingUuids(40, layoutId, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, mask).toSet(),
        )
        // Name search carries the same filter.
        assertTrue(
            repo.searchClimbsByName(
                "c-both", 40, layoutId, "kilter",
                climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = mask,
            ).isEmpty(),
            "a hidden climb must not resurface via name search",
        )
        assertEquals(
            1L,
            repo.countSearchClimbs("c-set26", 40, layoutId, "kilter", ClimbTypeFilter.ALL, 0, mask),
        )
    }
}
