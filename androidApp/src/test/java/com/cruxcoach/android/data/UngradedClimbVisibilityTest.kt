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
 * Integration test for the `:showUngraded` leg of the browse grade predicate
 * under the ungraded-only browse mode (product decision 2026-06-11, replacing
 * the old "untouched default slider range" heuristic):
 *
 *  - NORMAL mode: the VM passes showUngraded=false regardless of the slider
 *    position — ungraded (difficulty_average NULL) climbs NEVER show.
 *  - UNGRADED-ONLY mode ("Nur unbewertete (Projekte)"): the VM passes an
 *    IMPOSSIBLE grade range (minDiff > maxDiff) together with
 *    showUngraded=true, so only the `IS NULL` leg of the shared predicate
 *      ((difficulty BETWEEN bounds) OR (:showUngraded = 1 AND difficulty IS NULL))
 *    can match — the list shows exactly the NULL-grade rows, with no SQL
 *    change. Every browse / count / uuid-enumeration variant shares that
 *    predicate shape.
 *  - BOARDSESH provenance pull: showUngraded=true unconditionally (the
 *    imports are inherently ungraded; the origin chip is the explicit
 *    opt-in), so its ungraded rows show in normal mode too.
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * HsmHoldSetFilterTest / HeatmapBrandScopeTest.
 */
class UngradedClimbVisibilityTest {

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

    // The exact impossible-range values the VM's ungraded-only mode passes
    // (BoardBrowserViewModel.UNGRADED_ONLY_MIN/MAX_DIFF).
    private val impossibleMin = 9999.0
    private val impossibleMax = -9999.0

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-ungraded-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // Two graded catalogue climbs (easy + hard) and one Aurora-style
        // ungraded stub: difficulty NULL, quality NULL, 0 ascents — exactly
        // the shape the /sync climb_stats payload carries for a climb nobody
        // has ascended yet, at the setter's angle.
        climb("c-easy", difficulty = 12.0, quality = 2.0, ascents = 30L)
        climb("c-hard", difficulty = 25.0, quality = 3.0, ascents = 5L)
        climb("c-ungraded", difficulty = null, quality = null, ascents = 0L)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A listed layout-9 Kilter climb with an [angle]° stats row. */
    private fun climb(uuid: String, difficulty: Double?, quality: Double?, ascents: Long) {
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
            hsm = 0L,
            move_count = 1L,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = angle.toLong(),
            display_difficulty = difficulty, difficulty_average = difficulty,
            quality_average = quality, ascensionist_count = ascents,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun browseUuids(
        minDiff: Double = 0.0,
        maxDiff: Double = 100.0,
        showUngraded: Boolean,
        sortField: ClimbSortField = ClimbSortField.ASCENSIONISTS,
        sortDirection: SortDirection = SortDirection.DESC,
    ): List<String> = repo.searchClimbsSorted(
        angle = angle, layoutId = layoutId, boardBrand = "kilter",
        minDifficulty = minDiff, maxDifficulty = maxDiff, minAscensionists = 0,
        sortField = sortField, sortDirection = sortDirection,
        climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0,
        showUngraded = showUngraded,
    ).map { it.uuid }

    // ── (a) Normal mode: ungraded never shows ─────────────────────────────

    @Test
    fun defaultMode_hidesUngraded_evenAtDefaultSliderRange() {
        // The VM now passes showUngraded=false whatever the slider reads —
        // the full default range (here: the whole 0..100 difficulty span)
        // must NOT surface the NULL-grade stub anymore.
        assertEquals(
            setOf("c-easy", "c-hard"),
            browseUuids(showUngraded = false).toSet(),
        )
        // And of course not under a narrowed range either.
        assertEquals(
            listOf("c-easy"),
            browseUuids(minDiff = 10.0, maxDiff = 15.0, showUngraded = false),
        )
    }

    // ── (b) Ungraded-only mode: exactly the NULL-grade rows ───────────────

    @Test
    fun ungradedOnlyMode_returnsExactlyTheNullGradeRows() {
        // Impossible range + showUngraded=true → only the IS NULL leg of the
        // grade predicate can match.
        assertEquals(
            listOf("c-ungraded"),
            browseUuids(minDiff = impossibleMin, maxDiff = impossibleMax, showUngraded = true),
        )
        // The trick holds for every sort variant of the same predicate.
        for (field in ClimbSortField.entries) {
            for (dir in listOf(SortDirection.ASC, SortDirection.DESC)) {
                assertEquals(
                    listOf("c-ungraded"),
                    browseUuids(minDiff = impossibleMin, maxDiff = impossibleMax, showUngraded = true, sortField = field, sortDirection = dir),
                    "sort variant $field $dir must also match only the NULL-grade row",
                )
            }
        }
    }

    @Test
    fun countQueries_agreeWithTheBrowseList() {
        // Normal mode counts: ungraded excluded.
        assertEquals(
            2L,
            repo.countFilteredClimbs(angle, layoutId, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, 0, showUngraded = false),
        )
        assertEquals(
            1L,
            repo.countFilteredClimbs(angle, layoutId, "kilter", 10.0, 15.0, 0, ClimbTypeFilter.ALL, 0, 0, showUngraded = false),
        )
        // Ungraded-only mode counts: exactly the NULL-grade rows — the count
        // display under the list uses these same params.
        assertEquals(
            1L,
            repo.countFilteredClimbs(angle, layoutId, "kilter", impossibleMin, impossibleMax, 0, ClimbTypeFilter.ALL, 0, 0, showUngraded = true),
        )
        assertEquals(
            1L,
            repo.countFilteredClimbsFast(angle, layoutId, "kilter", impossibleMin, impossibleMax, 0, 0, 0, showUngraded = true),
        )
        // The RANDOM-sort uuid enumeration carries the same predicate shape.
        assertEquals(
            setOf("c-ungraded"),
            repo.getAllBrowseMatchingUuids(angle, layoutId, "kilter", impossibleMin, impossibleMax, 0, ClimbTypeFilter.ALL, 0, 0, showUngraded = true).toSet(),
        )
        assertEquals(
            setOf("c-easy", "c-hard"),
            repo.getAllBrowseMatchingUuids(angle, layoutId, "kilter", 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, 0, showUngraded = false).toSet(),
        )
    }

    // ── (c) BoardSesh provenance: ungraded rows in normal mode ────────────

    @Test
    fun boardSeshProvenance_returnsUngradedRowsInNormalMode() {
        // The VM passes showUngraded=true UNCONDITIONALLY on the BoardSesh
        // pull: the imports are inherently ungraded, the origin chip is the
        // explicit opt-in. Graded imports still honour the range leg.
        driver.execute(null, "UPDATE climbs SET origin = 'boardsesh' WHERE uuid IN ('c-easy', 'c-ungraded')", 0)

        fun boardSeshUuids(minDiff: Double = 0.0, maxDiff: Double = 100.0): Set<String> =
            repo.getBoardSeshClimbs(
                layoutId, "kilter", angle, minDiff, maxDiff, 0,
                ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0,
                showUngraded = true,
            ).mapTo(mutableSetOf()) { it.uuid }

        // Normal mode (real range): graded-in-range + all ungraded imports.
        assertEquals(setOf("c-easy", "c-ungraded"), boardSeshUuids())
        // Narrowed range: the graded import drops out, the ungraded one stays.
        assertEquals(setOf("c-ungraded"), boardSeshUuids(minDiff = 20.0, maxDiff = 30.0))
        // Ungraded-only mode composes with the provenance pull too.
        assertEquals(setOf("c-ungraded"), boardSeshUuids(minDiff = impossibleMin, maxDiff = impossibleMax))
        // Non-boardsesh rows never leak in.
        assertTrue("c-hard" !in boardSeshUuids(), "non-boardsesh rows never leak in")
    }

    // ── (d) Ascending-sort tiebreaks unchanged ─────────────────────────────

    @Test
    fun ascendingSorts_placeUngradedLast() {
        // SQLite sorts NULL as smallest: DESC already trails NULL rows, but a
        // bare ASC would surface them FIRST — the ASC variants of the two
        // NULL-able sort keys carry an explicit `IS NULL` tiebreak instead.
        // (Exercised at the SQL level with showUngraded=true + a real range —
        // the predicate and ORDER BY clauses are untouched by the mode change.)
        assertEquals(
            "c-ungraded",
            browseUuids(showUngraded = true, sortField = ClimbSortField.DIFFICULTY, sortDirection = SortDirection.ASC).last(),
            "difficulty ASC must trail the NULL-grade stub",
        )
        assertEquals(
            "c-ungraded",
            browseUuids(showUngraded = true, sortField = ClimbSortField.QUALITY, sortDirection = SortDirection.ASC).last(),
            "quality ASC must trail the NULL-quality stub",
        )
        // DESC orders trail NULLs natively — guard that too.
        assertEquals(
            "c-ungraded",
            browseUuids(showUngraded = true, sortField = ClimbSortField.DIFFICULTY, sortDirection = SortDirection.DESC).last(),
        )
        assertEquals(
            "c-ungraded",
            browseUuids(showUngraded = true, sortField = ClimbSortField.QUALITY, sortDirection = SortDirection.DESC).last(),
        )
    }
}
