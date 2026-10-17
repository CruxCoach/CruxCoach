package com.cruxcoach.android.athlete.health

import android.app.Application
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.today.ClimbingDaySheet
import com.cruxcoach.android.ui.training.today.ClimbingDayViewModel
import com.cruxcoach.android.ui.training.today.ClimbingDaysScreen
import com.cruxcoach.android.ui.training.today.ClimbingDaysViewModel
import com.cruxcoach.athlete.logic.BoardOverlap
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Holistic audit W3 / P0-3: a Health Connect climbing session recorded during a board session is not counted
 * twice – fake Health Connect records go through the real import, board data through the real board database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthBoardOverlapTest : AthleteScreenTest() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: String get() = service.today().toString()
    private val board by lazy { PersonalBoardRepositoryImpl(SecureDatabase(secureDriver)) }

    private fun at(time: String): Instant = LocalDateTime.parse("${today}T$time").atZone(zone).toInstant()

    private fun boardSession(from: String, to: String) {
        board.insertBoardSession("${today}T$from", "${today}T$to", Duration.between(at(from), at(to)).seconds, 0L, 0L, 0L)
    }

    private fun attempt(uuid: String, time: String) = board.insertAscent(
        uuid = uuid, climbUuid = "c-$uuid", angle = 40L, isMirror = false, attemptId = 0L, bidCount = 1L,
        quality = 3L, difficulty = 15L, isBenchmark = false, comment = null, climbedAt = "${today}T$time", synced = false,
        gymUuid = null, wallUuid = null, productLayoutUuid = null, climbName = uuid, difficultyAverage = 15.0,
        climbFrames = "p100r12", framesCount = 1L, boardBrand = "kilter", layoutId = 1L, externalId = null,
    )

    private fun watch(id: String, title: String, from: String, to: String) =
        HealthSession(id, ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING, title, null, at(from), at(to))

    private fun import(vararg sessions: HealthSession): HealthSyncResult =
        HealthConnectSource(context, service).importSessions(sessions.toList(), service.today().minus(DatePeriod(days = 13)), zone)

    private fun todayActivity() = service.activities(days = 1)[today]!!

    @Test
    fun `a watch session during a board session counts only the minutes outside it`() {
        boardSession("18:00:00", "18:30:00")
        val result = import(watch("hc1", "Bouldern Halle", "18:00:00", "19:00:00"))
        assertEquals(1, result.added)
        assertEquals(1, result.boardOverlaps)
        val entry = repo.climbingDayByExternalId("hc1")!!
        // The entry keeps what the watch recorded; 30 of its minutes are the board session's.
        assertEquals(60, entry.minutes)
        assertEquals(BoardOverlap.Stored(30), service.boardOverlap(entry.id))
        // Board 30 + the 30 minutes after it – not 90.
        assertEquals(60, todayActivity().climbingMinutes)
    }

    @Test
    fun `another board on a board day is the board session and can be counted anyway`() {
        boardSession("18:00:00", "18:30:00")
        import(watch("hc2", "Board 40°", "17:55:00", "19:00:00"))
        val entry = repo.climbingDayByExternalId("hc2")!!
        assertEquals(ClimbingDayKind.OTHER_BOARD, entry.kind)
        assertEquals(BoardOverlap.Stored(65), service.boardOverlap(entry.id))
        val notTwice = todayActivity()
        assertEquals(30, notTwice.climbingMinutes)

        // The sheet says so with the numbers, and "count anyway" is the athlete's way out.
        render { ClimbingDaySheet(onDismiss = {}, editId = entry.id, viewModel = ClimbingDayViewModel(service).tracked()) }
        waitForTag("climbing_day_overlap")
        waitForText("Overlaps with the board session (65 of 65 min) – not counted twice.")
        click("climbing_day_count_anyway")
        waitForText("Overlaps with the board session (65 of 65 min) – counted anyway.")
        click("climbing_day_save")
        compose.waitUntil(WAIT_MS) { service.boardOverlap(entry.id)?.countAnyway == true }
        val counted = todayActivity()
        assertEquals(30 + 65, counted.climbingMinutes)
        assertTrue(counted.fingerLoad > notTwice.fingerLoad)

        // A later sync looks again but keeps the athlete's choice; deleting the day takes the overlap along.
        import(watch("hc2", "Board 40°", "17:55:00", "19:00:00"))
        assertEquals(BoardOverlap.Stored(65, countAnyway = true), service.boardOverlap(entry.id))
        service.deleteClimbingDay(entry.id)
        assertNull(service.boardOverlap(entry.id))
    }

    @Test
    fun `logged attempts cover their stretch and the list says it is not counted twice`() {
        // No board session, only attempts 18:10–18:50: they cover 18:05–18:55, 50 of the watch's 60 minutes.
        listOf("18:10:00", "18:30:00", "18:50:00").forEachIndexed { i, t -> attempt("a$i", t) }
        import(watch("hc3", "Bouldern", "18:00:00", "19:00:00"))
        val entry = repo.climbingDayByExternalId("hc3")!!
        assertEquals(BoardOverlap.Stored(50), service.boardOverlap(entry.id))
        val day = todayActivity()
        // The logbook has no session time: the watch's 50 minutes stand in for it, so the energy keeps the hour …
        assertEquals(60, day.climbingMinutes)
        // … while the finger load counts the attempts and only the ten extra minutes.
        service.saveBoardOverlap(entry.id, countAnyway = true)
        assertTrue(todayActivity().fingerLoad > day.fingerLoad)
        service.saveBoardOverlap(entry.id, countAnyway = false)

        render { ClimbingDaysScreen({}, viewModel = ClimbingDaysViewModel(service).tracked()) }
        waitForTag("climbing_day_row_overlap_${entry.id}")
        waitForText("(50 of 60 min) – not counted twice")
    }

    @Test
    fun `a watch session at another time of the day counts in full`() {
        boardSession("09:00:00", "10:00:00")
        val result = import(watch("hc4", "Bouldern Halle", "18:00:00", "19:30:00"))
        assertEquals(0, result.boardOverlaps)
        val entry = repo.climbingDayByExternalId("hc4")!!
        assertNull(service.boardOverlap(entry.id))
        assertEquals(60 + 90, todayActivity().climbingMinutes)
    }
}
