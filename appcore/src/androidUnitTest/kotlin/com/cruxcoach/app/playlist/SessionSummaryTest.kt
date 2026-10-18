package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.app.ui.PlayerScreenModel
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.IntensityZones
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Port of Android `SessionSummaryBuilderTest` (assertions unchanged), plus the
 * player's end-of-session path: the session bar clock and the summary sheet.
 */
class SessionSummaryTest {

    private val zones = IntensityZones(warmUpCeiling = 15.0, optimalCeiling = 22.0, isPersonalized = false)

    private fun ascent(
        uuid: String,
        climbUuid: String = "c-1",
        climbedAt: String = "2026-03-05T18:00:00",
        angle: Long = 40L,
        isSend: Boolean = true,
        bidCount: Long = 1L,
        difficulty: Double? = 18.5,
    ) = AscentWithClimb(
        uuid = uuid,
        userId = 0L,
        climbUuid = climbUuid,
        angle = angle,
        isMirror = false,
        bidCount = bidCount,
        quality = 3L,
        difficulty = difficulty?.toLong(),
        comment = null,
        climbedAt = climbedAt,
        climbName = "Test",
        climbFrames = "p1r12",
        difficultyAverage = difficulty,
        framesCount = 1L,
        isSend = isSend,
        boardBrand = "kilter",
    )

    @Test
    fun `repeat send with prior attempts in an earlier session is not a flash`() {
        val oldAttempt = ascent(uuid = "old-bid", isSend = false, climbedAt = "2026-02-01T18:00:00")
        val todaysSend = ascent(uuid = "send-1", bidCount = 1L, climbedAt = "2026-03-05T18:00:00")
        val history = listOf(oldAttempt, todaysSend)
        val flashUuids = BoardStatsComputer.trueFlashUuids(history)

        val summary = SessionSummaryBuilder.build(
            ascents = listOf(todaysSend),
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = flashUuids,
        )

        assertEquals(0, summary.flashCount)
        assertEquals(1, summary.totalSends)
    }

    @Test
    fun `first-ever first-try send counts as a flash`() {
        val send = ascent(uuid = "send-1", bidCount = 1L)
        val summary = SessionSummaryBuilder.build(
            ascents = listOf(send),
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = BoardStatsComputer.trueFlashUuids(listOf(send)),
        )
        assertEquals(1, summary.flashCount)
    }

    @Test
    fun `session attempt total uses tries stored on consolidated outcomes`() {
        val summary = SessionSummaryBuilder.build(
            ascents = listOf(
                ascent(uuid = "open", isSend = false, bidCount = 2L),
                ascent(uuid = "send", isSend = true, bidCount = 3L),
            ),
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = emptySet(),
        )
        assertEquals(1, summary.totalSends)
        assertEquals(5, summary.totalAttempts)
    }

    @Test
    fun `pyramid groups by displayed grade keeping Font grades of one V bucket apart`() {
        // Kilter difficulty 24 = 7b, 25 = 7b+ — both V8.
        val sends = listOf(
            ascent(uuid = "s1", climbUuid = "c-7b", difficulty = 24.0),
            ascent(uuid = "s2", climbUuid = "c-7bp", difficulty = 25.0),
        )
        val summary = SessionSummaryBuilder.build(
            ascents = sends,
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = emptySet(),
        )
        assertEquals(listOf("7b", "7b+"), summary.gradeDistribution.map { it.grade })
    }

    @Test
    fun `session rows keep sends and attempts of the window only`() {
        val rows = SessionSummaryBuilder.sessionRows(
            listOf(
                ascent(uuid = "before", climbedAt = "2026-03-05T17:59:59"),
                ascent(uuid = "attempt", isSend = false, climbedAt = "2026-03-05T18:10:00"),
                ascent(uuid = "send", climbedAt = "2026-03-05T18:05:00"),
                ascent(uuid = "after", climbedAt = "2026-03-05T19:00:01"),
            ),
            from = "2026-03-05T18:00:00",
            to = "2026-03-05T19:00:00",
        )
        assertEquals(listOf("send", "attempt"), rows.map { it.uuid })
    }

    // --- Player: session bar and summary sheet ---

    private val serial = com.cruxcoach.app.logbook.SerialDispatcher().dispatcher

    private suspend fun <T> db(block: suspend () -> T): T = withContext(serial) { block() }

    @Test
    fun `ending the run shows the session clock and the logbook summary`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        var now = 1_800_000_000_000L
        var iso = "2026-10-17T10:00:00"
        val recorder = db { BoardSessionRecorder(repo, nowMs = { now }, nowIso = { iso }) }
        val summarizer = BoardSessionSummarizer(repo, { GradeScale.FRENCH }, cutoffDate = { "2026-07-19" })
        val presenter = PlaylistPlayerPresenter(
            scope = CoroutineScope(serial),
            sessions = recorder,
            summarize = { summarizer.summarize(it) },
            summaryDispatcher = serial,
        )
        val model = PlayerScreenModel(presenter)
        db { presenter.play("Limit", listOf(PlaybackItem("c-1", 40), PlaybackItem("c-2", 40))) }
        assertTrue(model.currentState.sessionRunning)

        now += 25 * 60_000L
        assertEquals(25 * 60, model.sessionElapsedSeconds())
        assertEquals(25 * 60, model.sessionActiveSeconds())

        db {
            presenter.onClimbLogged(isSend = true)
            // The logbook row the climb's log sheet wrote during the session.
            repo.insertAscent(
                uuid = "a-1", climbUuid = "c-1", angle = 40L, isMirror = false, attemptId = 0L, bidCount = 2L,
                quality = 3L, difficulty = 20L, isBenchmark = false, comment = null,
                climbedAt = "2026-10-17T10:20:00", synced = false, climbName = "Crimp", difficultyAverage = 20.0,
                climbFrames = "p1r12", framesCount = 1L,
            )
        }
        iso = "2026-10-17T10:25:00"
        db { model.endSession() }

        val finished = withTimeout(CI_WAIT_MS) { presenter.state.first { it.finished?.summary != null } }
        assertFalse(finished.isActive)
        val ui = model.currentState
        assertTrue(ui.showSummary)
        assertFalse(ui.sessionRunning)
        assertEquals(25 * 60, ui.summary.totalSeconds)
        assertEquals(25 * 60, ui.summary.activeSeconds)
        assertEquals(1, ui.summary.sends)
        assertEquals(2, ui.summary.attempts)
        assertTrue(ui.summary.hasDetails)
        assertEquals(1, ui.summary.uniqueClimbs)
        assertEquals("Crimp", ui.summary.hardestName)
        assertEquals(1, ui.summary.gradeDistribution.size)
        assertNull(db { repo.getActiveSession() })

        model.dismissSummary()
        assertFalse(model.currentState.showSummary)
        model.close()
    }

    @Test
    fun `a run without logbook rows falls back to the session counters`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        var now = 1_800_000_000_000L
        val recorder = db { BoardSessionRecorder(repo, nowMs = { now }, nowIso = { "2026-10-17T10:00:00" }) }
        val summarizer = BoardSessionSummarizer(repo, { GradeScale.FRENCH })
        val presenter = PlaylistPlayerPresenter(
            scope = CoroutineScope(serial),
            sessions = recorder,
            summarize = { summarizer.summarize(it) },
            summaryDispatcher = serial,
        )
        val model = PlayerScreenModel(presenter)
        db {
            presenter.play("Volume", listOf(PlaybackItem("c-1", 40)))
            presenter.onClimbLogged(isSend = false)
            presenter.onClimbLogged(isSend = false)
        }
        now += 60_000L
        db { model.endSession() }

        val finished = withTimeout(CI_WAIT_MS) { presenter.state.first { it.finished != null } }.finished
        assertNotNull(finished)
        assertNull(finished.summary)
        val ui = model.currentState.summary
        assertFalse(ui.hasDetails)
        assertEquals(2, ui.attempts)
        assertEquals(0, ui.sends)
        assertEquals(60, ui.totalSeconds)
        model.close()
    }
}
