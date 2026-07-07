package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.ui.board.boardBrowserSortInKotlin
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

/**
 * SQL-level ordering test for the NEWEST sort (ORDER BY climbs.created_at,
 * exposed through the climb_browse VIEW since 23.sqm).
 *
 * created_at is a TEXT passthrough in two real-world shapes:
 *  - catalogue rows (Aurora /sync, Blossom chunks): 'YYYY-MM-DD HH:MM:SS(.f)'
 *  - community/local rows (Nostr live-sub, creator):  ISO-8601 'YYYY-MM-DDTHH:MM:SSZ'
 * Lexicographic ordering is date-correct across both shapes down to day
 * precision — the fixtures deliberately mix them. NULL (unknown date) must
 * trail in BOTH directions (DESC natively, ASC via the IS NULL tiebreak).
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * UngradedClimbVisibilityTest / HsmHoldSetFilterTest.
 */
class NewestSortBrowseTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val layoutId = 9
    private val angle = 40

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-newest-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // Ascent counts deliberately anti-correlate with age (the oldest climb
        // is the most popular) so a regression to the ASCENSIONISTS fallback
        // would invert the expected order instead of passing by accident.
        climb("c-2019-catalogue", createdAt = "2019-03-13 21:41:07.135795", ascents = 500L)
        climb("c-2024-iso", createdAt = "2024-05-05T10:00:00Z", ascents = 50L)
        climb("c-2026-iso", createdAt = "2026-07-01T12:00:00Z", ascents = 5L)
        climb("c-unknown", createdAt = null, ascents = 1000L)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A listed, graded layout-9 Kilter climb with an [angle]° stats row. */
    private fun climb(uuid: String, createdAt: String?, ascents: Long) {
        db.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = layoutId.toLong(),
            setter_username = "s",
            name = uuid,
            frames = "p100r12p101r14",
            frames_count = 1L,
            is_listed = 1L,
            edge_left = null, edge_right = null, edge_bottom = null, edge_top = null,
            created_at = createdAt,
            description = "",
            is_nomatch = 0L,
            frames_pace = 0L,
            hsm = 0L,
            move_count = 1L,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = angle.toLong(),
            display_difficulty = 12.0, difficulty_average = 12.0,
            quality_average = 2.0, ascensionist_count = ascents,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun browseUuids(direction: SortDirection): List<String> = repo.searchClimbsSorted(
        angle = angle, layoutId = layoutId, boardBrand = "kilter",
        minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
        sortField = ClimbSortField.NEWEST, sortDirection = direction,
        climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0,
        showUngraded = false,
    ).map { it.uuid }

    @Test
    fun newestDesc_ordersByCreationDate_mixedFormats_nullLast() {
        assertEquals(
            listOf("c-2026-iso", "c-2024-iso", "c-2019-catalogue", "c-unknown"),
            browseUuids(SortDirection.DESC),
        )
    }

    @Test
    fun newestAsc_oldestFirst_nullStillLast() {
        // ASC carries the explicit `created_at IS NULL` tiebreak (house style
        // of the NULL-able sort keys) so unknown-date rows trail here too.
        assertEquals(
            listOf("c-2019-catalogue", "c-2024-iso", "c-2026-iso", "c-unknown"),
            browseUuids(SortDirection.ASC),
        )
    }

    @Test
    fun searchFamily_supportsNewest() {
        val uuids = repo.searchClimbsByName(
            query = "c-", angle = angle, layoutId = layoutId, boardBrand = "kilter",
            sortField = ClimbSortField.NEWEST, sortDirection = SortDirection.DESC,
            limit = 50, offset = 0,
            climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0,
        ).map { it.uuid }
        assertEquals(listOf("c-2026-iso", "c-2024-iso", "c-2019-catalogue", "c-unknown"), uuids)
    }

    @Test
    fun inMemoryComparator_matchesSqlOrder() {
        // The VM's post-filter pagination re-sorts pages in Kotlin — the
        // comparator must agree with the SQL order, which also proves
        // mapBrowse plumbs created_at into ClimbWithStats.
        val unordered = repo.searchClimbsSorted(
            angle = angle, layoutId = layoutId, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
            sortField = ClimbSortField.ASCENSIONISTS, sortDirection = SortDirection.DESC,
            climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0,
            showUngraded = false,
        )
        val resorted = boardBrowserSortInKotlin(unordered, ClimbSortField.NEWEST, SortDirection.DESC)
        assertEquals(
            listOf("c-2026-iso", "c-2024-iso", "c-2019-catalogue", "c-unknown"),
            resorted.map { it.uuid },
        )
    }
}
