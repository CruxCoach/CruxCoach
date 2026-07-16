package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.createBoardDatabase
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.db.board.BoardDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real SQLite coverage for the user-visible name/setter search contract. */
class ClimbSearchSqlTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-search-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = createBoardDatabase(driver)
        repo = BoardRepositoryImpl(db)

        climb("percent", "100% Fun", "Setter_One")
        climb("digits", "1000 Moves", "SetterXOne")
        climb("underscore", "Under_score", "Normal")
        climb("plain", "Underscore", "Normal")
        climb("backslash", "Back\\Slash", "Normal")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun climb(uuid: String, name: String, setter: String) {
        db.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = 1,
            setter_username = setter,
            name = name,
            frames = "p100r12",
            frames_count = 1,
            is_listed = 1,
            edge_left = null,
            edge_right = null,
            edge_bottom = null,
            edge_top = null,
            created_at = "2026-01-01T00:00:00Z",
            description = "",
            is_nomatch = 0,
            frames_pace = 0,
            hsm = 0,
            move_count = 1,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = 40,
            display_difficulty = 12.0,
            difficulty_average = 12.0,
            quality_average = 2.0,
            ascensionist_count = 1,
            benchmark_difficulty = 12.0,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun search(query: String): List<String> = repo.searchClimbsByName(
        query = query,
        angle = 40,
        layoutId = 1,
        boardBrand = "kilter",
        sortField = ClimbSortField.NAME,
        sortDirection = SortDirection.ASC,
        limit = 100,
        offset = 0,
        climbType = ClimbTypeFilter.ALL,
        selProductSizeId = 0,
        hsmExcludedMask = 0,
    ).map { it.uuid }

    private fun count(query: String): Long = repo.countSearchClimbs(
        query = query,
        angle = 40,
        layoutId = 1,
        boardBrand = "kilter",
        climbType = ClimbTypeFilter.ALL,
        selProductSizeId = 0,
        hsmExcludedMask = 0,
    )

    @Test
    fun percentUnderscoreAndEscapeCharacterAreLiteralSubstrings() {
        assertEquals(listOf("percent"), search("%"))
        assertEquals(listOf("percent", "underscore"), search("_").sorted())
        assertEquals(listOf("backslash"), search("\\"))
        assertEquals(1L, count("%"))
        assertEquals(2L, count("_"))
        assertEquals(1L, count("\\"))
    }

    @Test
    fun setterSearchUsesTheSameLiteralContract() {
        assertEquals(listOf("percent"), search("Setter_"))
        assertEquals(1L, count("Setter_"))
    }

    @Test
    fun equalPopularityUsesStableUuidTiebreaker() {
        val firstPage = repo.searchClimbsSorted(
            angle = 40,
            layoutId = 1,
            boardBrand = "kilter",
            minDifficulty = 0.0,
            maxDifficulty = 100.0,
            minAscensionists = 0,
            sortField = ClimbSortField.ASCENSIONISTS,
            sortDirection = SortDirection.DESC,
            limit = 100,
            offset = 0,
            climbType = ClimbTypeFilter.ALL,
            selProductSizeId = 0,
            hsmExcludedMask = 0,
            showUngraded = true,
        ).map { it.uuid }

        assertEquals(
            listOf("backslash", "digits", "percent", "plain", "underscore"),
            firstPage,
        )
    }
}
