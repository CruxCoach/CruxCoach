package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.BoardOverlap
import com.cruxcoach.athlete.logic.ClimbRow
import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Holistic audit W3 / P0-3: a watch session during a board session is not counted twice. */
class BoardOverlapTest {

    private val zone = TimeZone.UTC
    private val day = "2026-10-17"

    private fun ms(time: String): Long = BoardOverlap.epochMillis("${day}T$time", zone)!!
    private fun window(from: String, to: String) = BoardOverlap.Window(ms(from), ms(to))
    private fun minutes(w: BoardOverlap.Window) = ((w.endMs - w.startMs) / 60_000).toInt()

    private fun row(climbedAt: String, rowDay: String = day) =
        ClimbRow(rowDay, "c-$climbedAt", 20.0, tries = 2, sent = true, flash = false, angle = 40, brand = "kilter", climbedAt = climbedAt)

    private fun evidence(
        sessions: List<BoardOverlap.SessionTimes> = emptyList(),
        rows: List<ClimbRow> = emptyList(),
    ) = BoardOverlap.evidence(rows, sessions, zone, nowMs = ms("23:00:00"))

    private fun covered(watch: BoardOverlap.Window, kind: ClimbingDayKind, ev: BoardOverlap.Evidence) =
        BoardOverlap.coveredMinutes(watch, minutes(watch), day, kind, ev)

    @Test
    fun aBoardSessionInsideTheWatchSessionCountsOnlyTheRest() {
        // Watch 18:00–19:00 (60 min), board session 18:00–18:30: 30 min are the board's, 30 count.
        val ev = evidence(sessions = listOf(BoardOverlap.SessionTimes("${day}T18:00:00.123456789", "${day}T18:30:00.5", 1800)))
        val watch = window("18:00:00", "19:00:00")
        assertEquals(30, covered(watch, ClimbingDayKind.GYM_BOULDER, ev))
        assertEquals(30, BoardOverlap.Stored(30).counted(60))
    }

    @Test
    fun aRestUnderTenMinutesIsClockNoiseAndTheSessionIsFullyCovered() {
        // Board session 18:00–19:00 in the board database's own format, watch stopped at 19:05.
        val ev = evidence(sessions = listOf(BoardOverlap.SessionTimes("$day 18:00:00", "$day 19:00:00", 3600)))
        assertEquals(65, covered(window("18:00:00", "19:05:00"), ClimbingDayKind.GYM_BOULDER, ev))
        // Ten minutes or more are real extra climbing.
        assertEquals(60, covered(window("17:50:00", "19:00:00"), ClimbingDayKind.GYM_BOULDER, ev))
    }

    @Test
    fun loggedAttemptsWithoutABoardSessionCoverTheirStretchOfTime() {
        // Attempts 18:10 … 18:50 cover 18:05–18:55 (five minutes around the first and last): 50 of 90 min.
        val rows = listOf("18:10:00", "18:20:00", "18:40:00", "18:50:00").map { row("${day}T$it") }
        assertEquals(50, covered(window("18:00:00", "19:30:00"), ClimbingDayKind.GYM_BOULDER, evidence(rows = rows)))
        // More than 30 minutes apart: two short stretches of ten minutes each, not the gap between them.
        val apart = listOf(row("${day}T18:10:00"), row("${day}T19:20:00"))
        assertEquals(20, covered(window("18:00:00", "19:30:00"), ClimbingDayKind.GYM_BOULDER, evidence(rows = apart)))
    }

    @Test
    fun sessionAndAttemptsTogetherCountOnce() {
        val ev = evidence(
            sessions = listOf(BoardOverlap.SessionTimes("${day}T18:00:00", "${day}T18:30:00", 1800)),
            rows = listOf(row("${day}T18:20:00"), row("${day}T18:40:00")),
        )
        // Board 18:00–18:30 plus attempts 18:15–18:45: 18:00–18:45 = 45 of 120 min.
        assertEquals(45, covered(window("18:00:00", "20:00:00"), ClimbingDayKind.GYM_BOULDER, ev))
    }

    @Test
    fun climbingAtAnotherTimeOfTheDayIsNotCovered() {
        val ev = evidence(
            sessions = listOf(BoardOverlap.SessionTimes("${day}T09:00:00", "${day}T10:00:00", 3600)),
            rows = listOf(row("${day}T09:30:00")),
        )
        assertEquals(0, covered(window("18:00:00", "19:30:00"), ClimbingDayKind.GYM_BOULDER, ev))
        assertEquals(0, covered(window("18:00:00", "19:30:00"), ClimbingDayKind.OUTDOOR, ev))
    }

    @Test
    fun anotherBoardOnADayWithBoardRowsIsTheBoardSessionEvenWithoutClockTimes() {
        // MoonBoard CSV rows carry only the date (noon UTC marker): no clock time, but a board day.
        val csv = evidence(rows = listOf(row("${day}T12:00:00Z")))
        assertTrue(csv.attemptsMs.isEmpty())
        assertEquals(60, covered(window("18:00:00", "19:00:00"), ClimbingDayKind.OTHER_BOARD, csv))
        // A gym session that day is not the board session.
        assertEquals(0, covered(window("18:00:00", "19:00:00"), ClimbingDayKind.GYM_BOULDER, csv))
        // Another board on a day without board rows is its own climbing.
        val otherDay = evidence(rows = listOf(row("2026-10-16T18:30:00", rowDay = "2026-10-16")))
        assertEquals(0, covered(window("18:00:00", "19:00:00"), ClimbingDayKind.OTHER_BOARD, otherDay))
    }

    @Test
    fun anOpenBoardSessionRunsUntilNow() {
        val ev = evidence(sessions = listOf(BoardOverlap.SessionTimes("${day}T18:30:00", null, 0)))
        // now = 23:00: the open session covers 18:30–19:00 of the watch's 18:00–19:00.
        assertEquals(30, covered(window("18:00:00", "19:00:00"), ClimbingDayKind.GYM_BOULDER, ev))
    }

    @Test
    fun timestampsWithoutAClockTimeNeverMakeAnOverlap() {
        assertNull(BoardOverlap.epochMillis("2026-10-17", zone))
        assertNull(BoardOverlap.epochMillis("2026-10-17T12:00:00Z", zone))
        assertNull(BoardOverlap.epochMillis("2026-10-17T00:00:00", zone))
        assertNull(BoardOverlap.epochMillis("2026-10-17 00:00:00.000000", zone))
        assertNull(BoardOverlap.epochMillis("garbage", zone))
        // Device-local, with a space or a T, and zoned timestamps all parse.
        assertEquals(BoardOverlap.epochMillis("2026-10-17T18:05:11", zone), BoardOverlap.epochMillis("2026-10-17 18:05:11", zone))
        assertEquals(
            BoardOverlap.epochMillis("2026-10-17T16:05:11Z", zone),
            BoardOverlap.epochMillis("2026-10-17T18:05:11+02:00", zone),
        )
        assertEquals(
            BoardOverlap.epochMillis("2026-10-17T16:05:11Z", zone),
            BoardOverlap.epochMillis("2026-10-17T18:05:11", TimeZone.of("Europe/Berlin")),
        )
    }

    @Test
    fun theStoredOverlapKeepsTheAthletesChoice() {
        val stored = BoardOverlap.Stored(45)
        assertEquals("45", stored.encode())
        assertEquals(stored, BoardOverlap.Stored.parse("45"))
        assertEquals(15, stored.counted(60))
        // Fully covered counts zero, never below.
        assertEquals(0, BoardOverlap.Stored(60).counted(45))
        val anyway = stored.copy(countAnyway = true)
        assertEquals("45,count", anyway.encode())
        assertEquals(anyway, BoardOverlap.Stored.parse("45,count"))
        assertEquals(60, anyway.counted(60))
        assertNull(BoardOverlap.Stored.parse(""))
        assertNull(BoardOverlap.Stored.parse(null))
        assertNull(BoardOverlap.Stored.parse("x"))
        assertFalse(BoardOverlap.Stored.parse("0")!!.countAnyway)
    }
}
