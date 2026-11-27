package com.cruxcoach.android.ui.yearreview

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.data.repository.ClimbRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.ClimbLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What makes the year in review's card and statistics read the logbook again (#64). */
class YearReviewSourceTest {

    private val secure = SecureDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) })
    private val athleteDb = AthleteDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) })
    private val athlete = AthleteRepository(athleteDb, Dispatchers.Unconfined) { 0L }
    private val board = PersonalBoardRepositoryImpl(secure)
    private val climbs = ClimbRepositoryImpl(secure)
    private val source = YearReviewSource(board, mockk<AthleteService> { every { repo } returns athlete }, climbs, secure) { athleteDb }

    @Test
    fun everyWriteToWhatDecidesTheMinimumCountsAndNothingElse() = runBlocking {
        source.load()
        assertEquals(0L, source.writes())

        counts {
            board.insertAscent(
                uuid = "a1", climbUuid = "c1", angle = 40, isMirror = false, attemptId = 0, bidCount = 2, quality = null,
                difficulty = null, isBenchmark = false, comment = null, climbedAt = "2026-12-02T18:00:00", synced = false,
                climbName = "Problem", difficultyAverage = 20.0, climbFrames = "", framesCount = 1,
            )
        }
        counts {
            board.insertBid(
                uuid = "b1", climbUuid = "c2", angle = 40, isMirror = false, bidCount = 3, comment = null,
                climbedAt = "2026-12-03T18:00:00", synced = false, climbName = "Project", difficultyAverage = 22.0,
            )
        }
        counts { board.updateBid("b1", bidCount = 4, comment = null) }
        counts { board.deleteAscent("a1") }
        counts { athlete.saveClimbingDay(ClimbingDayEntry("d1", "2026-12-04", ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD)) }
        counts { climbs.insertClimb(ClimbLog(date = "2026-12-05", grade = "6a", sent = true)) }

        // Reading, and writing what the minimum does not count, leave it alone.
        val before = source.writes()
        source.load()
        athlete.saveBenchmark(Benchmark(id = "bm1", exerciseSlug = "finger.max_hang", loadKg = 10.0, durationS = 10.0, measuredAt = 0))
        assertEquals(before, source.writes())
    }

    private fun counts(write: () -> Unit) {
        val before = source.writes()
        write()
        assertTrue(source.writes() > before)
    }
}
