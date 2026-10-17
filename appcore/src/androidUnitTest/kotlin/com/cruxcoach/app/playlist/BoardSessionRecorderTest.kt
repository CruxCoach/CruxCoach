package com.cruxcoach.app.playlist

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.athlete.ClimbingDaysReader
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardSessionRecorderTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val db = SecureDatabase(driver)
    private val repo = PersonalBoardRepositoryImpl(db)
    private var now = 1_800_000_000_000L
    private fun recorder() = BoardSessionRecorder(repo, nowMs = { now }, nowIso = { "2026-10-17T10:00:00" })

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun `a planned rest stops the clock and only its end restarts it`() {
        val sessions = recorder()
        sessions.start()
        assertNotNull(repo.getActiveSession(), "the session is written the moment it starts")
        now += 10 * 60_000L
        sessions.pause(SessionPauseReason.PLANNED_REST)
        now += 3 * 60_000L
        sessions.resume(SessionPauseReason.MANUAL) // play button during a planned rest: ignored (Android M-097)
        now += 2 * 60_000L
        sessions.resume(SessionPauseReason.PLANNED_REST)
        now += 5 * 60_000L
        sessions.recordAscent(); sessions.recordBid(); sessions.recordBid()
        val ended = assertNotNull(sessions.end())
        assertEquals(20 * 60, ended.totalSeconds)
        assertEquals(5 * 60, ended.pauseSeconds)
        assertEquals(15 * 60, ended.activeSeconds)
        assertEquals(1, ended.ascents)
        assertEquals(2, ended.bids)
        assertNull(repo.getActiveSession())
        val row = repo.getAllBoardSessions().single()
        assertEquals(20 * 60L, row.totalDurationSeconds)
        assertEquals(5 * 60L, row.pauseDurationSeconds)
    }

    @Test
    fun `the training area counts the board time as a climbing day`() {
        val sessions = recorder()
        sessions.start()
        now += 45 * 60_000L
        sessions.end()
        val day = ClimbingDaysReader(db).since("2026-10-01")["2026-10-17"]
        assertNotNull(day)
        assertEquals(45, day.minutes)
    }

    @Test
    fun `a session left open by a killed process is ended where it was last recorded`() {
        val first = recorder()
        first.start()
        now += 30 * 60_000L
        first.recordBid() // persists 30 min so far
        now += 10 * 60 * 60_000L // the app was killed overnight
        recorder() // next start
        assertNull(repo.getActiveSession())
        val row = repo.getAllBoardSessions().single()
        assertEquals(30 * 60L, row.totalDurationSeconds)
        assertEquals("2026-10-17T10:30", row.endedAt?.take(16))
        assertTrue(row.bidCount == 1L)
    }
}
