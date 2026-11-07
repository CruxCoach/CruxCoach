package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Tests for [StatsHighlightsComputer]: at a glance, milestones and the session recap. */
class StatsHighlightsComputerTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC)
    private val scale = GradeScale.FRENCH
    private var id = 0

    private fun row(
        climb: String,
        day: String,
        isSend: Boolean = true,
        tries: Long = 1L,
        difficulty: Double? = 18.0,
        board: String = "kilter",
        time: String = "18:00:00",
    ) = AscentWithClimb(
        uuid = "r-${++id}", climbUuid = climb, angle = 40, isMirror = false, bidCount = tries,
        quality = null, difficulty = difficulty?.toLong(), comment = null, climbedAt = "${day}T$time",
        climbName = "name-$climb", climbFrames = "", difficultyAverage = difficulty, isSend = isSend,
        boardBrand = board,
    )

    private fun grade(difficulty: Double) = GradeDisplayHelper.formatDifficulty(difficulty, scale)

    // ---------------------------------------------------------------- at a glance

    @Test
    fun `hardest send and flash cover the whole logbook and name their board`() {
        val rows = listOf(
            row("old-hard", "2025-01-10", tries = 4, difficulty = 22.0, board = "moonboard"),
            row("flash", "2026-03-20", tries = 1, difficulty = 20.0),
            // A first-try send after attempts on an earlier day is no flash.
            row("redpoint", "2026-03-01", isSend = false, tries = 3, difficulty = 21.0),
            row("redpoint", "2026-03-25", tries = 1, difficulty = 21.0),
        )
        val glance = StatsHighlightsComputer.atAGlance(rows, scale, clock = clock)
        assertEquals(grade(22.0), glance.hardestSend?.grade)
        assertEquals("moonboard", glance.hardestSend?.board)
        assertEquals(LocalDate.parse("2025-01-10"), glance.hardestSend?.date)
        assertEquals(grade(20.0), glance.hardestFlash?.grade)
        assertEquals("kilter", glance.hardestFlash?.board)
    }

    @Test
    fun `the last 30 days are compared with the 30 days before`() {
        val rows = listOf(
            // The 30 days before: 2026-01-31 .. 2026-03-01.
            row("p1", "2026-02-10", tries = 4, difficulty = 18.0),
            row("p2", "2026-02-12", tries = 2, difficulty = 16.0),
            // The last 30 days: 2026-03-02 .. 2026-03-31.
            row("c1", "2026-03-05", isSend = false, tries = 2, difficulty = 20.0),
            row("c1", "2026-03-06", tries = 1, difficulty = 20.0),
            row("c2", "2026-03-06", tries = 1, difficulty = 18.0),
            row("c3", "2026-03-31", tries = 1, difficulty = 18.0),
            // Too old for either span.
            row("x", "2026-01-30", tries = 9, difficulty = 25.0),
        )
        val recent = StatsHighlightsComputer.atAGlance(rows, scale, clock = clock).recent!!
        assertEquals("days with any entry", 3, recent.sessions)
        assertEquals(2, recent.previousSessions)
        assertEquals(3, recent.sends)
        assertEquals(2, recent.previousSends)
        assertEquals(grade(20.0), recent.hardest?.grade)
        assertEquals(2, recent.hardestStep)
        assertTrue(recent.hasPrevious)
    }

    @Test
    fun `nothing in either span leaves out the comparison`() {
        val rows = listOf(row("old", "2025-06-01"))
        val glance = StatsHighlightsComputer.atAGlance(rows, scale, clock = clock)
        assertNull(glance.recent)
        assertEquals(grade(18.0), glance.hardestSend?.grade)
    }

    @Test
    fun `without a previous span there is nothing to compare`() {
        val recent = StatsHighlightsComputer.atAGlance(listOf(row("c", "2026-03-20")), scale, clock = clock).recent!!
        assertFalse(recent.hasPrevious)
        assertNull(recent.hardestStep)
        assertEquals(0, recent.previousSessions)
    }

    @Test
    fun `grade steps count displayed grades`() {
        assertEquals(0, StatsHighlightsComputer.gradeSteps(18.2, 17.9, scale))
        assertEquals(2, StatsHighlightsComputer.gradeSteps(18.0, 20.0, scale))
        assertEquals(-2, StatsHighlightsComputer.gradeSteps(20.0, 18.0, scale))
        // In V grades 18 and 19 share a grade.
        assertEquals(
            GradeDisplayHelper.formatDifficulty(18.0, GradeScale.V_SCALE) == GradeDisplayHelper.formatDifficulty(19.0, GradeScale.V_SCALE),
            StatsHighlightsComputer.gradeSteps(18.0, 19.0, GradeScale.V_SCALE) == 0,
        )
    }

    // ---------------------------------------------------------------- milestones

    @Test
    fun `a new grade is a send harder than every send before, but not on the first day`() {
        val rows = listOf(
            row("a", "2026-01-01", difficulty = 14.0),
            row("b", "2026-01-01", difficulty = 16.0),
            row("c", "2026-01-08", difficulty = 16.0),
            row("d", "2026-01-15", difficulty = 18.0, board = "moonboard"),
            row("e", "2026-01-15", difficulty = 20.0, time = "19:00:00"),
            // Attempts do not count.
            row("f", "2026-01-20", isSend = false, difficulty = 25.0),
        )
        val badges = StatsHighlightsComputer.milestones(rows, scale)
            .filter { it.kind == MilestoneKind.NEW_GRADE }
        assertEquals("one per day, the hardest of it", 1, badges.size)
        assertEquals(LocalDate.parse("2026-01-15"), badges.single().date)
        assertEquals(grade(20.0), badges.single().grade?.grade)
        assertEquals("kilter", badges.single().grade?.board)
    }

    @Test
    fun `send counts mark the day they were reached`() {
        val rows = (1..120).map { i ->
            row("p$i", LocalDate.parse("2026-01-01").plusDays((i / 10).toLong()).toString(), difficulty = 14.0)
        }
        val counts = StatsHighlightsComputer.milestones(rows, scale).filter { it.kind == MilestoneKind.SENDS }
        assertEquals(listOf(100, 50), counts.map { it.count })
        assertEquals(LocalDate.parse("2026-01-11"), counts.first().date)
        assertEquals(LocalDate.parse("2026-01-06"), counts.last().date)
    }

    @Test
    fun `the biggest day needs five sends and the first day to reach it keeps the record`() {
        val small = (1..4).map { row("s$it", "2026-02-01") }
        assertTrue(StatsHighlightsComputer.milestones(small, scale).none { it.kind == MilestoneKind.BIGGEST_DAY })

        val rows = (1..6).map { row("a$it", "2026-02-01") } + (1..6).map { row("b$it", "2026-03-01") } +
            listOf(row("x", "2026-02-02", isSend = false, tries = 10))
        val day = StatsHighlightsComputer.milestones(rows, scale).single { it.kind == MilestoneKind.BIGGEST_DAY }
        assertEquals(6, day.count)
        assertEquals(LocalDate.parse("2026-02-01"), day.date)
    }

    @Test
    fun `a running week streak leads and the rest run newest first`() {
        val rows = listOf(
            row("a", "2026-01-01", difficulty = 14.0),
            row("b", "2026-01-10", difficulty = 16.0),
            row("c", "2026-02-10", difficulty = 18.0),
        )
        val badges = StatsHighlightsComputer.milestones(rows, scale, weekStreak = 3)
        assertEquals(MilestoneKind.WEEK_STREAK, badges.first().kind)
        assertEquals(3, badges.first().count)
        val dated = badges.drop(1).map { it.date!! }
        assertEquals(dated.sortedDescending(), dated)
        assertTrue(StatsHighlightsComputer.milestones(rows, scale, weekStreak = 1).none { it.kind == MilestoneKind.WEEK_STREAK })
    }

    // ---------------------------------------------------------------- session recap

    @Test
    fun `the recap sums one day over all boards`() {
        val rows = listOf(
            row("earlier", "2026-03-01", isSend = false, tries = 2, difficulty = 20.0),
            row("flash", "2026-03-10", tries = 1, difficulty = 16.0),
            row("earlier", "2026-03-10", tries = 2, difficulty = 20.0),
            row("open", "2026-03-10", isSend = false, tries = 4, difficulty = 22.0, board = "moonboard"),
            row("m", "2026-03-10", tries = 1, difficulty = 16.0, board = "moonboard"),
            row("later", "2026-03-11", tries = 1, difficulty = 25.0),
        )
        val recap = StatsHighlightsComputer.sessionRecap(rows, LocalDate.parse("2026-03-10"), scale)!!
        assertEquals(3, recap.sends)
        assertEquals("the redpoint of an earlier project is no flash", 2, recap.flashes)
        assertEquals(1 + 2 + 4 + 1, recap.tries)
        assertEquals(4, recap.problems)
        assertEquals(grade(20.0), recap.hardest?.grade)
        assertEquals(listOf("kilter", "moonboard"), recap.boards)
        assertEquals(listOf(grade(16.0), grade(20.0)), recap.grades.map { it.grade })
        assertEquals(RecapGrade(grade(16.0), 16, sends = 2, flashes = 2), recap.grades.first())
        assertEquals(listOf("name-earlier", "name-flash", "name-m"), recap.topClimbs.map { it.name })
        assertEquals(listOf(false, true, true), recap.topClimbs.map { it.flash })
        assertEquals(grade(20.0), recap.topClimbs.first().grade)
        assertNull(StatsHighlightsComputer.sessionRecap(rows, LocalDate.parse("2026-03-12"), scale))
    }

    @Test
    fun `a timed session's recap keeps only the milestones its own sends reached`() {
        val morning = (1..6).map { row("m$it", "2026-03-10", difficulty = 14.0, time = "09:0$it:00") } +
            listOf(row("hard", "2026-03-10", difficulty = 22.0, time = "09:30:00"))
        val evening = listOf(
            row("e1", "2026-03-10", difficulty = 14.0, time = "19:00:00"),
            row("e2", "2026-03-10", isSend = false, tries = 3, difficulty = 16.0, time = "19:10:00"),
        )
        val history = listOf(row("first", "2026-03-01", difficulty = 14.0)) + morning + evening
        // The morning reached the new grade; the evening added to the biggest day.
        val eveningRecap = StatsHighlightsComputer.recapOf(evening, history, scale)!!
        assertEquals(listOf(MilestoneKind.BIGGEST_DAY), eveningRecap.milestones.map { it.kind })
        assertEquals(8, eveningRecap.milestones.single().count)
        val morningRecap = StatsHighlightsComputer.recapOf(morning, history.filterNot { it in evening }, scale)!!
        assertEquals(setOf(MilestoneKind.NEW_GRADE, MilestoneKind.BIGGEST_DAY), morningRecap.milestones.map { it.kind }.toSet())
        // An evening of attempts only reaches nothing.
        val attempts = listOf(evening[1])
        assertTrue(StatsHighlightsComputer.recapOf(attempts, history.filterNot { it == evening[0] }, scale)!!.milestones.isEmpty())
        // The day's recap in the logbook still shows the whole day.
        val day = StatsHighlightsComputer.sessionRecap(history, LocalDate.parse("2026-03-10"), scale)!!
        assertEquals(setOf(MilestoneKind.NEW_GRADE, MilestoneKind.BIGGEST_DAY), day.milestones.map { it.kind }.toSet())
    }

    @Test
    fun `the recap carries the milestones of its day`() {
        val rows = listOf(
            row("a", "2026-03-01", difficulty = 16.0),
            row("b", "2026-03-10", difficulty = 20.0),
        )
        val recap = StatsHighlightsComputer.sessionRecap(rows, LocalDate.parse("2026-03-10"), scale)!!
        assertEquals(listOf(MilestoneKind.NEW_GRADE), recap.milestones.map { it.kind })
        assertTrue(StatsHighlightsComputer.sessionRecap(rows, LocalDate.parse("2026-03-01"), scale)!!.milestones.isEmpty())
    }
}
