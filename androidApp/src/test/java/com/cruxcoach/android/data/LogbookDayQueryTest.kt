package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hold cleaning reads "today" exactly as the logbook groups its days. */
class LogbookDayQueryTest {
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest fun setUp() {
        dbFile = Files.createTempDirectory("cruxcoach-logbook-day-").resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        repo = PersonalBoardRepositoryImpl(SecureDatabase(driver))
    }

    @AfterTest fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun send(uuid: String, at: String) = repo.insertAscent(
        uuid = uuid, climbUuid = "climb-$uuid", angle = 40, isMirror = uuid == "mirrored", attemptId = 0,
        bidCount = 1, quality = 3, difficulty = 20, isBenchmark = false, comment = null, climbedAt = at,
        synced = false, climbName = "", difficultyAverage = null, climbFrames = "p1r12", framesCount = 1,
        boardBrand = "tension", layoutId = 9,
    )

    private fun attempt(uuid: String, at: String) = repo.insertBid(
        uuid = uuid, climbUuid = "climb-$uuid", angle = 40, isMirror = false, bidCount = 2, comment = null,
        climbedAt = at, synced = false, climbName = "", difficultyAverage = null,
    )

    @Test fun `the day query returns the logbook's day group, sends and attempts`() {
        send("first", "2026-10-10T00:00:00")
        send("mirrored", "2026-10-10 23:59:59")
        send("import", "2026-10-10")
        send("before", "2026-10-09T23:59:59.999")
        attempt("try", "2026-10-10T12:00:00.5")
        attempt("after", "2026-10-11T00:00")

        val logbookDay = repo.getUserLogbookAllLight().groupBy { it.climbedAt.take(10) }.getValue("2026-10-10")
        val day = repo.getUserLogbookForDay("2026-10-10")
        assertEquals(logbookDay.map { it.climbUuid }.sorted(), day.map { it.climbUuid }.sorted())
        assertEquals(4, day.size)

        val mirrored = day.single { it.climbUuid == "climb-mirrored" }
        assertEquals(true, mirrored.isMirror)
        assertEquals("p1r12", mirrored.climbFrames)
        assertEquals("tension", mirrored.boardBrand)
        assertEquals(9L, mirrored.layoutId)
        val tried = day.single { it.climbUuid == "climb-try" }
        assertEquals("", tried.climbFrames)
        assertEquals("kilter", tried.boardBrand)

        // The interface default (used by test fakes) agrees with the SQL query.
        val fallback = object : PersonalBoardRepository by repo {
            override fun getUserLogbookForDay(day: String) = super.getUserLogbookForDay(day)
        }
        assertEquals(
            day.map { it.copy(climbFrames = "") }.sortedBy { it.climbUuid },
            fallback.getUserLogbookForDay("2026-10-10").sortedBy { it.climbUuid },
        )
    }
}
