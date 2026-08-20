package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for [BoardStatsComputer]. The computer was extracted from
 * [BoardLogbookViewModel] specifically for testability (§comment at
 * BoardStatsComputer.kt:16-18) — this file exercises the two public
 * entry points [computeStats] + [filterByInterval].
 *
 * No Android context is passed, so string-resource branches render via
 * the MONTH_NAMES_FALLBACK table / "CW" literals. That is intentional —
 * it decouples the test from R.string resource lookups.
 */
class BoardStatsComputerTest {

    // Pin "now" so fixture construction and the production code under
    // test see exactly the same instant. Without this a midnight-boundary
    // race made `filterByInterval DAYS_30 drops ascents older than cutoff`
    // and the periodComparison cases potentially flaky.
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.now(fixedClock)

    // -- Test fixtures --

    private fun ascent(
        uuid: String = "a-${nextId()}",
        climbUuid: String = "c-1",
        climbedAt: String = "2026-03-05T18:00:00",
        angle: Long = 40L,
        isSend: Boolean = true,
        bidCount: Long = 1L,
        difficulty: Double? = 18.5,
        framesCount: Long = 1L,
        boardBrand: String = "kilter",
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
        framesCount = framesCount,
        isSend = isSend,
        boardBrand = boardBrand,
    )

    private var idCounter = 0
    private fun nextId(): Int = ++idCounter

    // -- filterByInterval --

    @Test
    fun `filterByInterval ALL returns input unchanged`() {
        val ascents = listOf(ascent(climbedAt = "2020-01-01T10:00:00"), ascent(climbedAt = "2026-03-05T10:00:00"))
        val out = BoardStatsComputer.filterByInterval(ascents, StatsTimeInterval.ALL)
        assertEquals(2, out.size)
    }

    @Test
    fun `filterByInterval DAYS_30 drops ascents older than cutoff`() {
        val todayStr = today.toString()
        val old = today.minusDays(90).toString()
        val ascents = listOf(
            ascent(climbedAt = "${todayStr}T10:00:00"),
            ascent(climbedAt = "${old}T10:00:00"),
        )
        val out = BoardStatsComputer.filterByInterval(ascents, StatsTimeInterval.DAYS_30, clock = fixedClock)
        assertEquals(1, out.size)
        assertEquals("${todayStr}T10:00:00", out[0].climbedAt)
    }

    @Test
    fun `filterByInterval custom range overrides interval`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-01T10:00:00"),
            ascent(climbedAt = "2026-03-05T10:00:00"),
            ascent(climbedAt = "2026-03-10T10:00:00"),
        )
        val out = BoardStatsComputer.filterByInterval(
            ascents,
            StatsTimeInterval.DAYS_30,
            customFrom = LocalDate.of(2026, 3, 3),
            customTo = LocalDate.of(2026, 3, 8),
        )
        assertEquals(1, out.size)
        assertEquals("2026-03-05T10:00:00", out[0].climbedAt)
    }

    @Test
    fun `filterByInterval custom range is inclusive of end-date sends`() {
        // customTo.plusDays(1) handling ensures sends on the end date aren't dropped.
        val ascents = listOf(ascent(climbedAt = "2026-03-08T23:59:00"))
        val out = BoardStatsComputer.filterByInterval(
            ascents,
            StatsTimeInterval.DAYS_30,
            customFrom = LocalDate.of(2026, 3, 1),
            customTo = LocalDate.of(2026, 3, 8),
        )
        assertEquals(1, out.size)
    }

    // -- computeStats: empty path --

    @Test
    fun `computeStats returns empty BoardLogbookStats when nothing in interval`() {
        val stats = BoardStatsComputer.computeStats(
            ascents = emptyList(),
            interval = StatsTimeInterval.ALL,
            gradeScale = GradeScale.V_SCALE,
        )
        assertEquals(0, stats.totalSends)
        assertEquals(0, stats.totalAttempts)
        assertEquals(0, stats.sessionCount)
        assertEquals(0, stats.uniqueClimbs)
        assertTrue(stats.gradePyramid.isEmpty())
        assertNull(stats.hardestGrade)
        assertNull(stats.periodComparison)
    }

    // -- computeStats: send / bid split --

    @Test
    fun `sends and total tries are counted separately`() {
        val ascents = listOf(
            ascent(uuid = "s1", isSend = true),
            ascent(uuid = "s2", isSend = true),
            ascent(uuid = "b1", isSend = false),
            ascent(uuid = "b2", isSend = false),
            ascent(uuid = "b3", isSend = false),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.totalSends)
        assertEquals(5, stats.totalAttempts)
    }

    @Test
    fun `attempt total sums consolidated open and sent tries`() {
        val ascents = listOf(
            ascent(uuid = "open", isSend = false, bidCount = 2L),
            ascent(uuid = "send", isSend = true, bidCount = 3L),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(1, stats.totalSends)
        assertEquals(5, stats.totalAttempts)
    }

    @Test
    fun `flash rate counts bidCount equal 1 as flash`() {
        val ascents = listOf(
            ascent(uuid = "flash", isSend = true, bidCount = 1L),
            ascent(uuid = "rp", isSend = true, bidCount = 5L),
            ascent(uuid = "rp2", isSend = true, bidCount = 2L),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(3, stats.totalSends)
        // 1 flash out of 3 sends = 33.33%
        assertEquals(33.333f, stats.flashRate, 0.1f)
    }

    @Test
    fun `boulder vs route split uses framesCount`() {
        val ascents = listOf(
            ascent(uuid = "boulder1", framesCount = 1L),
            ascent(uuid = "boulder2", framesCount = 1L),
            ascent(uuid = "route1", framesCount = 5L),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.boulderSends)
        assertEquals(1, stats.routeSends)
    }

    // -- computeStats: distinct counts --

    @Test
    fun `uniqueClimbs counts distinct climbUuid`() {
        val ascents = listOf(
            ascent(uuid = "a1", climbUuid = "X"),
            ascent(uuid = "a2", climbUuid = "X"),
            ascent(uuid = "a3", climbUuid = "Y"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.uniqueClimbs)
    }

    @Test
    fun `sessionCount counts distinct dates`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-05T10:00:00"),
            ascent(climbedAt = "2026-03-05T14:00:00"),
            ascent(climbedAt = "2026-03-06T18:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.sessionCount)
    }

    // -- computeStats: hardest grade --

    @Test
    fun `hardestGrade uses max difficulty across sends only`() {
        val ascents = listOf(
            ascent(isSend = true, difficulty = 15.0),
            ascent(isSend = true, difficulty = 22.7),
            ascent(isSend = false, difficulty = 30.0), // bid — must be ignored
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(22, stats.hardestDifficultyInt)
        assertNotNull(stats.hardestGrade)
    }

    @Test
    fun `hardestGrade is null when no sends have difficulty`() {
        val ascents = listOf(ascent(isSend = true, difficulty = null))
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertNull(stats.hardestGrade)
        assertEquals(0, stats.hardestDifficultyInt)
    }

    // -- Grade pyramid & distributions --

    @Test
    fun `gradePyramid is sorted by difficulty ascending`() {
        val ascents = listOf(
            ascent(climbUuid = "a", difficulty = 25.0),
            ascent(climbUuid = "b", difficulty = 15.0),
            ascent(climbUuid = "c", difficulty = 20.0),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(3, stats.gradePyramid.size)
        val diffs = stats.gradePyramid.map { it.difficultyInt }
        assertEquals(diffs.sorted(), diffs)
    }

    @Test
    fun `angleDistribution buckets by angle and sorts`() {
        val ascents = listOf(
            ascent(angle = 40L), ascent(angle = 40L),
            ascent(angle = 55L),
            ascent(angle = 20L),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        val expected = listOf(
            AngleDistEntry(20, 1),
            AngleDistEntry(40, 2),
            AngleDistEntry(55, 1),
        )
        assertEquals(expected, stats.angleDistribution)
    }

    // -- Activity map & weekly volume --

    @Test
    fun `activityMap maps dates to send counts`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-05T10:00:00"),
            ascent(climbedAt = "2026-03-05T14:00:00"),
            ascent(climbedAt = "2026-03-06T18:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.activityMap[LocalDate.of(2026, 3, 5)])
        assertEquals(1, stats.activityMap[LocalDate.of(2026, 3, 6)])
    }

    @Test
    fun `weeklyVolume bands difficulty into easy medium hard elite`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-02T10:00:00", difficulty = 12.0), // easy
            ascent(climbedAt = "2026-03-03T10:00:00", difficulty = 18.0), // medium
            ascent(climbedAt = "2026-03-04T10:00:00", difficulty = 23.0), // hard
            ascent(climbedAt = "2026-03-05T10:00:00", difficulty = 28.0), // elite
            ascent(climbedAt = "2026-03-06T10:00:00", difficulty = null), // dropped
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        // All five ascents are in ISO week 10 of 2026 (Mon 2026-03-02 .. Sun 2026-03-08).
        assertEquals(1, stats.weeklyVolume.size)
        val wk = stats.weeklyVolume[0]
        assertEquals(1, wk.easyCount)
        assertEquals(1, wk.mediumCount)
        assertEquals(1, wk.hardCount)
        assertEquals(1, wk.eliteCount)
    }

    // -- Period comparison --

    @Test
    fun `periodComparison is null for interval ALL`() {
        val stats = BoardStatsComputer.computeStats(
            ascents = listOf(ascent()),
            interval = StatsTimeInterval.ALL,
            gradeScale = GradeScale.V_SCALE,
        )
        assertNull(stats.periodComparison)
    }

    @Test
    fun `periodComparison is null when neither period has data`() {
        val veryOld = today.minusDays(400).toString()
        val stats = BoardStatsComputer.computeStats(
            ascents = listOf(ascent(climbedAt = "${veryOld}T10:00:00")),
            clock = fixedClock,
            interval = StatsTimeInterval.DAYS_30,
            gradeScale = GradeScale.V_SCALE,
        )
        assertNull(stats.periodComparison)
    }

    // -- Personal records --

    @Test
    fun `personalRecords returns defaults for no sends`() {
        val stats = BoardStatsComputer.computeStats(
            ascents = listOf(ascent(isSend = false)),
            interval = StatsTimeInterval.ALL,
            gradeScale = GradeScale.V_SCALE,
        )
        assertEquals(0, stats.personalRecords.mostSendsInDay)
        assertNull(stats.personalRecords.mostSendsDate)
        assertNull(stats.personalRecords.hardestFlashGrade)
    }

    @Test
    fun `personalRecords hardestFlash ignores non-flash sends`() {
        val ascents = listOf(
            ascent(uuid = "rp", climbUuid = "c-rp", isSend = true, bidCount = 10L, difficulty = 30.0),
            ascent(uuid = "fl", climbUuid = "c-fl", isSend = true, bidCount = 1L, difficulty = 22.0),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(22, stats.personalRecords.hardestFlashDifficulty)
    }

    @Test
    fun `personalRecords mostSendsInDay picks the highest-density date`() {
        val ascents = listOf(
            ascent(uuid = "a", climbedAt = "2026-03-05T10:00:00", climbUuid = "c1"),
            ascent(uuid = "b", climbedAt = "2026-03-05T11:00:00", climbUuid = "c2"),
            ascent(uuid = "c", climbedAt = "2026-03-05T12:00:00", climbUuid = "c3"),
            ascent(uuid = "d", climbedAt = "2026-03-06T10:00:00", climbUuid = "c4"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(3, stats.personalRecords.mostSendsInDay)
        assertEquals("2026-03-05", stats.personalRecords.mostSendsDate)
    }

    // -- Session consistency (avg sessions/week + week streak) --
    // fixedClock "today" 2026-03-15 is a SUNDAY; its ISO week starts Mon 2026-03-09.

    @Test
    fun `avgSessionsPerWeek averages distinct active days over the 8-week window`() {
        // Logbook older than the window (fixes the divisor at 8 weeks),
        // 4 distinct session days inside the window → 0.5/week.
        val ascents = listOf(
            ascent(climbedAt = "${today.minusDays(100)}T10:00:00"),
            ascent(climbedAt = "${today.minusDays(3)}T10:00:00"),
            ascent(climbedAt = "${today.minusDays(3)}T11:00:00"), // same day, still 1 session
            ascent(climbedAt = "${today.minusDays(10)}T10:00:00"),
            ascent(climbedAt = "${today.minusDays(20)}T10:00:00"),
            ascent(climbedAt = "${today.minusDays(40)}T10:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertEquals(0.5, stats.personalRecords.avgSessionsPerWeek, 0.001)
    }

    @Test
    fun `avgSessionsPerWeek divides by the full window for a young logbook`() {
        // Logbook younger than the window: 2 distinct session days spanning
        // ~3 weeks. The divisor stays the 8-week window (not the 3-week active
        // span), so 2 / 8 = 0.25 — matching the "(8 W.)" label instead of the
        // inflated 0.6 the active-span divisor produced.
        val ascents = listOf(
            ascent(climbedAt = "${today.minusDays(21)}T10:00:00"),
            ascent(climbedAt = "${today}T10:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertEquals(0.25, stats.personalRecords.avgSessionsPerWeek, 0.001)
    }

    @Test
    fun `avgSessionsPerWeek counts attempt-only days as sessions`() {
        val ascents = listOf(
            ascent(isSend = false, climbedAt = "${today.minusDays(2)}T10:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertTrue(stats.personalRecords.avgSessionsPerWeek > 0.0)
    }

    @Test
    fun `weekStreak counts consecutive ISO weeks and survives rest days`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-10T10:00:00"), // this week (Mon 03-09)
            ascent(climbedAt = "2026-03-04T10:00:00"), // last week
            ascent(climbedAt = "2026-02-25T10:00:00"), // week before
            ascent(climbedAt = "2026-01-05T10:00:00"), // long ago — gap, not part of streak
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertEquals(3, stats.personalRecords.weekStreak)
    }

    @Test
    fun `weekStreak is zero when the last session is more than a week back`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-02-10T10:00:00"),
            ascent(climbedAt = "2026-02-17T10:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertEquals(0, stats.personalRecords.weekStreak)
    }

    @Test
    fun `weekStreak still active when only last week has a session`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-06T10:00:00"), // last week (Mon 03-02)
        )
        val stats = BoardStatsComputer.computeStats(
            ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE, clock = fixedClock,
        )
        assertEquals(1, stats.personalRecords.weekStreak)
    }

    // -- Outcome distribution --

    @Test
    fun `outcomeDistribution counts flash vs redpoint`() {
        val ascents = listOf(
            ascent(uuid = "f1", climbUuid = "c-1", isSend = true, bidCount = 1L),
            ascent(uuid = "f2", climbUuid = "c-2", isSend = true, bidCount = 1L),
            ascent(uuid = "r1", climbUuid = "c-3", isSend = true, bidCount = 4L),
            ascent(uuid = "bid", climbUuid = "c-4", isSend = false, bidCount = 3L),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, stats.outcomeDistribution.flashes)
        assertEquals(1, stats.outcomeDistribution.redpoints)
        assertEquals(6, stats.outcomeDistribution.attempts)
    }

    @Test
    fun `first-try send after an earlier-session attempt is NOT a flash`() {
        val ascents = listOf(
            // Session 1: two failed burns on the climb
            ascent(uuid = "bid", climbUuid = "c-proj", isSend = false, bidCount = 2L,
                climbedAt = "2026-03-01T18:00:00"),
            // Session 2: goes first try — a repeat/redpoint, not a flash
            ascent(uuid = "send", climbUuid = "c-proj", isSend = true, bidCount = 1L,
                climbedAt = "2026-03-10T18:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(0, stats.outcomeDistribution.flashes)
        assertEquals(1, stats.outcomeDistribution.redpoints)
        assertEquals(0f, stats.flashRate)
        assertNull(stats.personalRecords.hardestFlashGrade)
    }

    @Test
    fun `earlier attempt at a DIFFERENT angle does not kill the flash`() {
        val ascents = listOf(
            ascent(uuid = "bid30", climbUuid = "c-x", angle = 30L, isSend = false,
                climbedAt = "2026-03-01T18:00:00"),
            ascent(uuid = "send40", climbUuid = "c-x", angle = 40L, isSend = true, bidCount = 1L,
                climbedAt = "2026-03-10T18:00:00"),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(1, stats.outcomeDistribution.flashes)
    }

    // -- Grade pyramid label fidelity --

    @Test
    fun `gradePyramid keeps Font grades that share a V bucket apart`() {
        // Kilter difficulty 24 = 7b, 25 = 7b+ — BOTH are V8. The old
        // difficulty→V→Font detour collapsed them into a single "7b+" row,
        // showing a 7b top send as 7b+.
        val ascents = listOf(
            ascent(uuid = "s1", climbUuid = "c-7b", difficulty = 24.0),
            ascent(uuid = "s2", climbUuid = "c-7bp", difficulty = 25.0),
        )
        val stats = BoardStatsComputer.computeStats(ascents, StatsTimeInterval.ALL, GradeScale.FRENCH)
        val labels = stats.gradePyramid.map { it.grade }
        assertEquals(listOf("7b", "7b+"), labels)
        assertTrue(stats.gradePyramid.all { it.count == 1 })
    }

    // -- Sends-over-time buckets --

    @Test
    fun `computeSendsOverTime DAYS_30 buckets by day`() {
        val ascents = listOf(
            ascent(climbedAt = "2026-03-05T10:00:00"),
            ascent(climbedAt = "2026-03-05T11:00:00"),
            ascent(climbedAt = "2026-03-06T10:00:00"),
        )
        val out = BoardStatsComputer.computeSendsOverTime(ascents, StatsTimeInterval.DAYS_30)
        // 2 distinct dates → 2 buckets
        assertEquals(2, out.size)
    }

    @Test
    fun `computeSendsOverTime unparseable dates are dropped`() {
        val ascents = listOf(
            ascent(climbedAt = "totally-not-a-date"),
            ascent(climbedAt = "2026-03-05T10:00:00"),
        )
        val out = BoardStatsComputer.computeSendsOverTime(ascents, StatsTimeInterval.YEAR_1)
        assertEquals(1, out.size)
        assertFalse(out[0].label.isEmpty())
    }

    // -- Board comparison --

    @Test
    fun `computeBoardComparison groups per board with sends attempts and top grade`() {
        val ascents = listOf(
            ascent(uuid = "k1", boardBrand = "kilter", isSend = true, difficulty = 18.0),
            ascent(uuid = "k2", boardBrand = "kilter", isSend = true, difficulty = 22.0),
            ascent(uuid = "kb", boardBrand = "kilter", isSend = false, bidCount = 3L, difficulty = 25.0),
            ascent(uuid = "t1", boardBrand = "tension", isSend = true, difficulty = 20.0),
        )
        val out = BoardStatsComputer.computeBoardComparison(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(2, out.size)
        val kilter = out.first { it.boardBrand == "kilter" }
        assertEquals(2, kilter.sendCount)
        assertEquals(5, kilter.attemptCount)
        // Top grade is from sends only — the bid at 25.0 must not count.
        assertEquals(22, kilter.hardestDifficultyInt)
        assertNotNull(kilter.hardestGrade)
        val tension = out.first { it.boardBrand == "tension" }
        assertEquals(1, tension.sendCount)
        assertEquals(20, tension.hardestDifficultyInt)
    }

    @Test
    fun `computeBoardComparison is sorted by send count descending`() {
        val ascents = listOf(
            ascent(uuid = "t1", boardBrand = "tension", isSend = true),
            ascent(uuid = "k1", boardBrand = "kilter", isSend = true),
            ascent(uuid = "k2", boardBrand = "kilter", isSend = true),
            ascent(uuid = "k3", boardBrand = "kilter", isSend = true),
        )
        val out = BoardStatsComputer.computeBoardComparison(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals("kilter", out[0].boardBrand)
        assertEquals("tension", out[1].boardBrand)
    }

    @Test
    fun `computeBoardComparison hardestGrade is null for a board with only attempts`() {
        val ascents = listOf(
            ascent(uuid = "b1", boardBrand = "moonboard", isSend = false, difficulty = 21.0),
        )
        val out = BoardStatsComputer.computeBoardComparison(ascents, StatsTimeInterval.ALL, GradeScale.V_SCALE)
        assertEquals(1, out.size)
        assertEquals(0, out[0].sendCount)
        assertEquals(1, out[0].attemptCount)
        assertNull(out[0].hardestGrade)
        assertEquals(0, out[0].hardestDifficultyInt)
    }

    @Test
    fun `computeBoardComparison respects the time interval`() {
        val recent = today.toString()
        val old = today.minusDays(200).toString()
        val ascents = listOf(
            ascent(uuid = "new", boardBrand = "kilter", climbedAt = "${recent}T10:00:00", isSend = true),
            ascent(uuid = "old", boardBrand = "tension", climbedAt = "${old}T10:00:00", isSend = true),
        )
        val out = BoardStatsComputer.computeBoardComparison(
            ascents, StatsTimeInterval.DAYS_30, GradeScale.V_SCALE, clock = fixedClock,
        )
        // Only the kilter ascent falls inside the 30-day window.
        assertEquals(1, out.size)
        assertEquals("kilter", out[0].boardBrand)
    }
}
