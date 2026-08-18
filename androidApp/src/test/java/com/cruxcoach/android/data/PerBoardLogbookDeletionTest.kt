package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-board "Delete logbook data" (Settings multiselect): ascents + bids
 * scope on their denormalized board_brand; climb-list ENTRIES scope on
 * the caller-resolved climb uuids while the list rows themselves — and
 * the brand-less board sessions — survive. Only the all-boards path
 * ([PersonalBoardRepositoryImpl.deleteAllUserBoardData]) keeps the
 * historical full wipe.
 *
 * Real file-backed SQLite (JdbcSqliteDriver), same harness as
 * IgnoredClimbsTest.
 */
class PerBoardLogbookDeletionTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-per-board-logbook-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        db = SecureDatabase(driver)
        repo = PersonalBoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ── Seeding helpers ─────────────────────────────────────────────

    private fun ascent(uuid: String, climbUuid: String, brand: String) {
        repo.insertAscent(
            uuid = uuid, climbUuid = climbUuid, angle = 40L,
            isMirror = false, attemptId = 0L, bidCount = 1L,
            quality = 3L, difficulty = 15L, isBenchmark = false,
            comment = null, climbedAt = "2026-07-01 10:00:00", synced = false,
            gymUuid = null, wallUuid = null, productLayoutUuid = null,
            climbName = climbUuid, difficultyAverage = 15.0,
            climbFrames = "p100r12", framesCount = 1L,
            boardBrand = brand, layoutId = 1L, externalId = null,
        )
    }

    private fun bid(uuid: String, climbUuid: String, brand: String) {
        repo.insertBid(
            uuid = uuid, climbUuid = climbUuid, angle = 40L,
            isMirror = false, bidCount = 2L,
            comment = null, climbedAt = "2026-07-02 11:00:00", synced = false,
            gymUuid = null, wallUuid = null, productLayoutUuid = null,
            climbName = climbUuid, difficultyAverage = 16.0,
            boardBrand = brand, layoutId = 1L, externalId = null,
        )
    }

    /** Favorites + one custom list; ck (kilter) in both, ct (tension) in
     *  favorites only; one finished board session. Returns (favId, customId). */
    private fun seedLogbook(): Pair<Long, Long> {
        ascent("a-k", "ck", "kilter")
        ascent("a-t", "ct", "tension")
        bid("b-k", "ck", "kilter")
        bid("b-t", "ct", "tension")
        val favId = repo.ensureFavoritesListExists()
        val customId = repo.createClimbList("Projects")
        repo.addClimbToList(favId, "ck")
        repo.addClimbToList(favId, "ct")
        repo.addClimbToList(customId, "ck")
        repo.insertBoardSession("2026-07-01 09:00:00", "2026-07-01 11:00:00", 7200L, 0L, 2L, 1L)
        return favId to customId
    }

    // ── Scoped path ─────────────────────────────────────────────────

    @Test
    fun `brand-scoped deletion removes one brand's ascents, bids and list entries only`() {
        val (favId, customId) = seedLogbook()
        assertEquals(setOf("ck", "ct"), repo.getAllListEntryClimbUuids())

        // The caller (SettingsViewModel) resolves entry uuids → brand via
        // the board DB; here "ck" resolved to the selected kilter.
        repo.deleteUserBoardDataForBrands(setOf("kilter"), listOf("ck"))

        assertEquals(listOf("a-t"), repo.getUserAscentsAll().map { it.uuid }, "only the tension ascent survives")
        assertEquals(listOf("b-t"), repo.getRawBidsForUser().map { it.uuid }, "only the tension bid survives")

        // Kilter entries gone from BOTH lists; the tension entry and the
        // list rows themselves stay.
        assertEquals(listOf("ct"), repo.getClimbListEntryUuids(favId).map { it.first })
        assertTrue(repo.getClimbListEntryUuids(customId).isEmpty(), "custom list is emptied")
        val lists = repo.getAllClimbLists()
        assertEquals(2, lists.size, "both list rows survive a per-brand delete")
        assertTrue(lists.any { it.name == "Projects" }, "the emptied custom list still exists")

        // Sessions are brand-less aggregates — a partial selection keeps them.
        assertEquals(1, repo.getRecentBoardSessions().size)
    }

    @Test
    fun `scoped deletion with empty entry list leaves every list entry alone`() {
        val (favId, _) = seedLogbook()

        // Tension selected, but its climb was never put into a list → the
        // resolver hands over nothing and only ascents/bids go.
        repo.deleteUserBoardDataForBrands(setOf("tension"), emptyList())

        assertEquals(listOf("a-k"), repo.getUserAscentsAll().map { it.uuid })
        assertEquals(listOf("b-k"), repo.getRawBidsForUser().map { it.uuid })
        assertEquals(setOf("ck", "ct"), repo.getClimbListEntryUuids(favId).map { it.first }.toSet())
    }

    // ── All-boards path (historical behaviour) ──────────────────────

    @Test
    fun `deleteAllUserBoardData still wipes ascents, bids, sessions and whole lists`() {
        seedLogbook()

        repo.deleteAllUserBoardData()

        assertTrue(repo.getUserAscentsAll().isEmpty())
        assertTrue(repo.getRawBidsForUser().isEmpty())
        assertTrue(repo.getAllClimbLists().isEmpty(), "full wipe removes the list rows too")
        assertTrue(repo.getRecentBoardSessions().isEmpty())
        assertTrue(repo.getAllListEntryClimbUuids().isEmpty())
    }
}
