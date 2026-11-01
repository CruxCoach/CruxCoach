package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Tests for [CrossBoardStatsComputer] (#44). No context: month labels use the fallback names. */
class CrossBoardStatsComputerTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC)
    private val scale = GradeScale.FRENCH

    private var idCounter = 0

    private fun row(
        climbUuid: String,
        climbedAt: String,
        isSend: Boolean,
        bidCount: Long = 1L,
        difficulty: Double? = 18.0,
        boardBrand: String = "kilter",
        angle: Long = 40L,
    ) = AscentWithClimb(
        uuid = "r-${++idCounter}",
        climbUuid = climbUuid,
        angle = angle,
        isMirror = false,
        bidCount = bidCount,
        quality = null,
        difficulty = difficulty?.toLong(),
        comment = null,
        climbedAt = climbedAt,
        climbName = climbUuid,
        climbFrames = "",
        difficultyAverage = difficulty,
        isSend = isSend,
        boardBrand = boardBrand,
    )

    private fun compute(rows: List<AscentWithClimb>, interval: StatsTimeInterval = StatsTimeInterval.ALL) =
        CrossBoardStatsComputer.compute(rows, interval, scale, clock = fixedClock)

    @Test
    fun `tries add up attempts from earlier sessions until the first send`() {
        val rows = listOf(
            row("c1", "2026-03-01T18:00:00", isSend = false, bidCount = 3),
            row("c1", "2026-03-05T18:00:00", isSend = false, bidCount = 2),
            // The quick log folds this session's tries into the send.
            row("c1", "2026-03-08T18:00:00", isSend = true, bidCount = 2),
            // Repeats and later attempts do not count.
            row("c1", "2026-03-10T18:00:00", isSend = true, bidCount = 1),
            row("c1", "2026-03-11T18:00:00", isSend = false, bidCount = 4),
        )
        val first = CrossBoardStatsComputer.firstSends(rows).single()
        assertEquals("2026-03-08T18:00:00", first.send.climbedAt)
        assertEquals(7, first.tries)
    }

    @Test
    fun `a flash counts one try and a problem never sent is left out`() {
        val rows = listOf(
            row("flash", "2026-03-01T18:00:00", isSend = true, bidCount = 1),
            row("open", "2026-03-01T18:00:00", isSend = false, bidCount = 5),
        )
        val firstSends = CrossBoardStatsComputer.firstSends(rows)
        assertEquals(listOf("flash"), firstSends.map { it.send.climbUuid })
        assertEquals(1, firstSends.single().tries)
    }

    @Test
    fun `each angle is its own problem`() {
        val rows = listOf(
            row("c1", "2026-03-01T18:00:00", isSend = true, angle = 30),
            row("c1", "2026-03-02T18:00:00", isSend = false, bidCount = 2, angle = 50),
            row("c1", "2026-03-03T18:00:00", isSend = true, bidCount = 1, angle = 50),
        )
        assertEquals(listOf(1, 3), CrossBoardStatsComputer.firstSends(rows).map { it.tries }.sorted())
    }

    @Test
    fun `the same grade on two boards sits side by side`() {
        val rows = listOf(
            row("k1", "2026-03-01T18:00:00", isSend = true, bidCount = 1),
            row("k2", "2026-03-02T18:00:00", isSend = true, bidCount = 3),
            row("k3", "2026-03-03T18:00:00", isSend = true, bidCount = 2),
            row("m1", "2026-03-04T18:00:00", isSend = false, bidCount = 4, boardBrand = "moonboard"),
            row("m1", "2026-03-05T18:00:00", isSend = true, bidCount = 2, boardBrand = "moonboard"),
        )
        val stats = compute(rows)
        assertEquals("most first sends lead", listOf("kilter", "moonboard"), stats.boards)
        val grade = stats.grades.single()
        assertEquals(GradeDisplayHelper.formatDifficulty(18.0, scale), grade.grade)
        assertEquals(TriesToSend(problems = 3, tries = 6), grade.byBoard["kilter"])
        assertEquals(2.0, grade.byBoard.getValue("kilter").averageTries, 1e-9)
        assertEquals(TriesToSend(problems = 1, tries = 6), grade.byBoard["moonboard"])
    }

    @Test
    fun `grades are sorted from easy to hard and a board without sends at a grade is absent`() {
        val rows = listOf(
            row("hard", "2026-03-01T18:00:00", isSend = true, difficulty = 24.0),
            row("easy", "2026-03-01T18:00:00", isSend = true, difficulty = 14.0),
            row("easy-m", "2026-03-01T18:00:00", isSend = true, difficulty = 14.0, boardBrand = "moonboard"),
        )
        val grades = compute(rows).grades
        assertEquals(listOf(14, 24), grades.map { it.difficultyInt })
        assertNull(grades.last().byBoard["moonboard"])
    }

    @Test
    fun `months run newest first with the hardest new send per board`() {
        val rows = listOf(
            row("a", "2026-01-10T18:00:00", isSend = true, difficulty = 16.0),
            row("b", "2026-01-20T18:00:00", isSend = true, bidCount = 3, difficulty = 20.0),
            row("c", "2026-02-03T18:00:00", isSend = true, difficulty = 18.0, boardBrand = "moonboard"),
            // A repeat in February is not a new send.
            row("b", "2026-02-05T18:00:00", isSend = true, difficulty = 20.0),
        )
        val months = compute(rows).months
        assertEquals(listOf(YearMonth.of(2026, 2), YearMonth.of(2026, 1)), months.map { it.month })
        assertEquals("Feb '26", months.first().label)
        assertNull("the repeat adds nothing to February", months.first().byBoard["kilter"])
        val january = months.last().byBoard.getValue("kilter")
        assertEquals(GradeDisplayHelper.formatDifficulty(20.0, scale), january.hardestGrade)
        assertEquals(TriesToSend(problems = 2, tries = 4), january.tries)
    }

    @Test
    fun `the interval picks problems by their first send but keeps earlier tries`() {
        val rows = listOf(
            row("old", "2025-06-01T18:00:00", isSend = true),
            row("new", "2026-01-01T18:00:00", isSend = false, bidCount = 5),
            row("new", "2026-03-10T18:00:00", isSend = true, bidCount = 1),
        )
        val stats = compute(rows, StatsTimeInterval.DAYS_30)
        assertEquals(listOf("kilter"), stats.boards)
        assertEquals(TriesToSend(problems = 1, tries = 6), stats.grades.single().byBoard["kilter"])
        assertEquals(listOf(YearMonth.of(2026, 3)), stats.months.map { it.month })
    }

    @Test
    fun `no sends give empty stats`() {
        val stats = compute(listOf(row("c1", "2026-03-01T18:00:00", isSend = false, bidCount = 2)))
        assertTrue(stats.boards.isEmpty())
        assertTrue(stats.grades.isEmpty())
        assertTrue(stats.months.isEmpty())
    }
}
