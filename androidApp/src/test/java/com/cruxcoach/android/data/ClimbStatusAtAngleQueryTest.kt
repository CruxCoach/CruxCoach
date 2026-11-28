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

/** The browser's status sets come per climb AND angle, as in the board makers' apps. */
class ClimbStatusAtAngleQueryTest {
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest fun setUp() {
        dbFile = Files.createTempDirectory("cruxcoach-status-angle-").resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        repo = PersonalBoardRepositoryImpl(SecureDatabase(driver))
    }

    @AfterTest fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun send(uuid: String, climb: String, angle: Long) = repo.insertAscent(
        uuid = uuid, climbUuid = climb, angle = angle, isMirror = false, attemptId = 0,
        bidCount = 1, quality = 3, difficulty = 20, isBenchmark = false, comment = null,
        climbedAt = "2026-11-20T18:00:00", synced = false, climbName = "", difficultyAverage = null,
        climbFrames = "", framesCount = 1,
    )

    private fun attempt(uuid: String, climb: String, angle: Long) = repo.insertBid(
        uuid = uuid, climbUuid = climb, angle = angle, isMirror = false, bidCount = 2, comment = null,
        climbedAt = "2026-11-20T18:00:00", synced = false, climbName = "", difficultyAverage = null,
    )

    @Test fun `a send at one angle leaves the climb new at another`() {
        send("s1", "sent-at-40", 40)

        assertEquals(setOf("sent-at-40"), repo.getUserSentClimbUuidsAtAngle(40))
        assertEquals(emptySet(), repo.getUserSentClimbUuidsAtAngle(25))
        assertEquals(emptySet(), repo.getUserAttemptedClimbUuidsAtAngle(25))
    }

    @Test fun `an attempt counts at its own angle, unless the climb was sent there`() {
        attempt("b1", "tried-at-40", 40)
        attempt("b2", "sent-at-40-tried-at-25", 25)
        send("s1", "sent-at-40-tried-at-25", 40)
        attempt("b3", "tried-and-sent-at-40", 40)
        send("s2", "tried-and-sent-at-40", 40)

        assertEquals(setOf("sent-at-40-tried-at-25", "tried-and-sent-at-40"), repo.getUserSentClimbUuidsAtAngle(40))
        assertEquals(setOf("tried-at-40"), repo.getUserAttemptedClimbUuidsAtAngle(40))
        assertEquals(emptySet(), repo.getUserSentClimbUuidsAtAngle(25))
        assertEquals(setOf("sent-at-40-tried-at-25"), repo.getUserAttemptedClimbUuidsAtAngle(25))
    }
}
