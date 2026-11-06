package com.cruxcoach.domain.yearreview

import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ComebackPlan
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.YearReviewComputer.Input
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YearReviewComputerTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private val utc = TimeZone.UTC
    private var next = 0

    private fun entry(
        at: String,
        climb: String,
        board: String = "kilter",
        send: Boolean = true,
        tries: Long = 1,
        difficulty: Double? = 20.0,
        angle: Long = 40,
    ) = AscentWithClimb(
        uuid = "e${next++}",
        climbUuid = climb,
        angle = angle,
        isMirror = false,
        bidCount = tries,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = at,
        climbName = "Problem $climb",
        climbFrames = "",
        difficultyAverage = difficulty,
        isSend = send,
        boardBrand = board,
    )

    private fun review(input: Input, year: Int, today: LocalDate, zone: TimeZone = utc, label: ((Double) -> String)? = null) =
        if (label == null) YearReviewComputer.compute(input, year, today, zone)
        else YearReviewComputer.compute(input, year, today, zone, label)

    private fun millis(day: LocalDate) = day.atStartOfDayIn(utc).toEpochMilliseconds()

    // ── Days and year boundaries ─────────────────────────────────────

    @Test
    fun localDayMovesInstantsIntoTheZoneAndKeepsLocalTimes() {
        val day = YearReviewComputer::localDay
        assertEquals(LocalDate(2025, 12, 31), day("2026-01-01T03:00:00Z", TimeZone.of("America/New_York")))
        assertEquals(LocalDate(2026, 2, 28), day("2026-03-01T00:30:00+01:00", utc))
        // A Kilter sync without a zone and an entry logged in the app are already local.
        assertEquals(LocalDate(2026, 5, 4), day("2026-05-04 23:22:11.123456", berlin))
        assertEquals(LocalDate(2026, 5, 4), day("2026-05-04T23:59:59.999", berlin))
        // A MoonBoard import is a day stored as noon UTC; it stays that day even twelve hours ahead.
        assertEquals(LocalDate(2026, 5, 4), day("2026-05-04T12:00:00Z", TimeZone.of("Pacific/Auckland")))
        assertEquals(LocalDate(2026, 5, 5), day("2026-05-04T13:00:00Z", TimeZone.of("Pacific/Auckland")))
        assertNull(day("garbage", utc))
        assertNull(day("2026-13-01T10:00:00", utc))
    }

    @Test
    fun theYearEndsAtLocalMidnight() {
        val input = Input(
            entries = listOf(
                entry("2025-12-31T23:30:00", "local-old"),
                entry("2026-01-01T00:10:00", "local-new"),
                // Imported instants: 00:30 and 23:30 in Berlin.
                entry("2025-12-31T23:30:00Z", "import-new"),
                entry("2026-12-31T22:30:00Z", "import-last"),
                entry("2026-12-31T23:30:00Z", "import-next-year"),
            ),
        )
        val year = review(input, 2026, LocalDate(2027, 1, 15), berlin)
        assertEquals(3, year.volume.sends)
        assertEquals(2, year.volume.boardDays)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), year.rhythm.daysPerMonth)
        assertEquals(1, review(input, 2025, LocalDate(2027, 1, 15), berlin).volume.sends)
        assertEquals(1, review(input, 2027, LocalDate(2027, 1, 15), berlin).volume.sends)
    }

    @Test
    fun decemberIsSoFarAndJanuaryShowsTheFinishedYear() {
        val input = Input(
            entries = listOf(
                entry("2025-06-01T18:00:00", "a"),
                entry("2025-12-20T18:00:00", "b"),
                entry("2026-06-01T18:00:00", "c"),
                // Logged ahead of today: not counted yet.
                entry("2026-12-20T18:00:00", "d"),
            ),
        )
        val december = review(input, 2026, LocalDate(2026, 12, 5))
        assertFalse(december.isFinal)
        assertEquals(LocalDate(2026, 12, 5), december.until)
        assertEquals(1, december.volume.sends)
        // Last year up to the same day: 20 December 2025 is not in it yet.
        assertEquals(LocalDate(2025, 12, 5), december.previous!!.until)
        assertEquals(1, december.previous!!.totals.sends)

        val january = review(input, 2026, LocalDate(2027, 1, 10))
        assertTrue(january.isFinal)
        assertEquals(LocalDate(2026, 12, 31), january.until)
        assertEquals(2, january.volume.sends)
        assertEquals(LocalDate(2025, 12, 31), january.previous!!.until)
        assertEquals(2, january.previous!!.totals.sends)
    }

    @Test
    fun aLeapDayCountsAndComparesWithTheTwentyEighth() {
        val input = Input(
            entries = listOf(
                entry("2028-02-28T18:00:00", "a"),
                entry("2028-02-29T18:00:00", "b"),
                entry("2027-02-28T10:00:00", "c"),
                entry("2027-03-01T10:00:00", "d"),
            ),
        )
        val leapDay = review(input, 2028, LocalDate(2028, 2, 29))
        assertEquals(2, leapDay.volume.climbingDays)
        assertEquals(2, leapDay.rhythm.daysPerMonth[1])
        assertEquals(LocalDate(2027, 2, 28), leapDay.previous!!.until)
        assertEquals(1, leapDay.previous!!.totals.climbingDays)

        val whole = review(input, 2028, LocalDate(2029, 1, 2))
        assertEquals(LocalDate(2028, 12, 31), whole.until)
        // 1 January 2028 is a Saturday and 31 December a Sunday: 53 weeks touch the year.
        assertEquals(53, whole.rhythm.weeks)
        assertEquals(LocalDate(2027, 12, 31), whole.previous!!.until)
        assertEquals(2, whole.previous!!.totals.climbingDays)
    }

    // ── Little data ──────────────────────────────────────────────────

    @Test
    fun noDataGivesAnEmptyReview() {
        val today = LocalDate(2026, 12, 3)
        val year = review(Input(), 2026, today)
        assertFalse(year.hasEnoughData)
        assertEquals(YearVolume(0, 0, 0, 0, 0, 0, 0, 0, 0), year.volume)
        assertTrue(year.boards.isEmpty())
        assertNull(year.previous)
        assertNull(year.rhythm.busiestMonth)
        assertNull(year.rhythm.favouriteWeekday)
        assertEquals(0, year.rhythm.longestWeekStreak)
        assertNull(year.highlights.biggestDay)
        assertNull(year.highlights.mostRepeated)
        assertNull(year.highlights.longestProject)
        assertTrue(year.highlights.hardestFlashes.isEmpty())
        assertTrue(year.training.isEmpty)
        assertEquals(listOf(2026), YearReviewComputer.years(Input(), today, utc))
    }

    @Test
    fun theFullReviewNeedsEightClimbingDaysOrThirtyTries() {
        val today = LocalDate(2026, 12, 3)
        val sevenDays = (1..7).map { entry("2026-03-0${it}T18:00:00", "p$it") }
        assertFalse(review(Input(entries = sevenDays), 2026, today).hasEnoughData)

        // A day in the gym logged by hand is a climbing day too.
        val gym = ClimbingDayEntry("g", "2026-04-01", ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.VOLUME)
        val eight = review(Input(entries = sevenDays, climbingDays = listOf(gym)), 2026, today)
        assertEquals(8, eight.volume.climbingDays)
        assertEquals(7, eight.volume.boardDays)
        assertTrue(eight.hasEnoughData)

        val oneLongDay = review(Input(entries = listOf(entry("2026-05-01T18:00:00", "x", send = false, tries = 30))), 2026, today)
        assertEquals(1, oneLongDay.volume.climbingDays)
        assertEquals(30, oneLongDay.volume.tries)
        assertTrue(oneLongDay.hasEnoughData)
    }

    // ── Several boards and imported logbooks ─────────────────────────

    @Test
    fun boardsKeepTheirOwnGradesAndImportsCountOnTheirLocalDay() {
        val input = Input(
            entries = listOf(
                // Kilter, logged in the app: a project begun last year, a flash and its repeat, a third problem.
                entry("2025-11-20T19:00:00", "k1", send = false, tries = 4, difficulty = 21.0),
                entry("2026-01-10T19:00:00", "k1", tries = 2, difficulty = 21.0),
                entry("2026-02-01T19:00:00", "k2", tries = 1, difficulty = 22.0),
                entry("2026-03-01T19:00:00", "k2", tries = 1, difficulty = 22.0),
                entry("2026-02-01T20:00:00", "k3", tries = 3, difficulty = 18.0),
                entry("2026-12-20T10:00:00", "k4", difficulty = 30.0),
                // MoonBoard import: days only.
                entry("2026-04-05T12:00:00Z", "m1", board = "moonboard", tries = 1, difficulty = 25.0),
                entry("2026-04-05T12:00:00Z", "m2", board = "moonboard", send = false, tries = 5, difficulty = 27.0),
                // Tension import: half past midnight on 1 July in Berlin.
                entry("2026-06-30T22:30:00Z", "t1", board = "tension", tries = 2, difficulty = 20.0),
            ),
        )
        val year = review(input, 2026, LocalDate(2026, 12, 10), berlin)

        assertEquals(6, year.volume.sends)
        assertEquals(15, year.volume.tries)
        assertEquals(6, year.volume.problems)
        assertEquals(5, year.volume.firstSends)
        assertEquals(2, year.volume.flashes)
        assertEquals(5, year.volume.boardDays)

        assertEquals(listOf("kilter", "moonboard", "tension"), year.boards.map { it.board })
        val kilter = year.boards[0]
        assertEquals(4, kilter.entries)
        assertEquals(4, kilter.gradedSends)
        assertEquals(listOf("k2", "k1", "k3").map { "Problem $it" }, kilter.hardest.map { it.name })
        assertEquals(YearSend("Problem k2", "kilter", 40, 22.0, LocalDate(2026, 2, 1), flash = true, tries = 1), kilter.hardest[0])
        assertEquals(6, kilter.hardest[1].tries)
        // The MoonBoard's harder grade stays on the MoonBoard.
        assertEquals(listOf(25.0), year.boards[1].hardest.map { it.difficulty })
        assertEquals(LocalDate(2026, 7, 1), year.boards[2].hardest.single().date)
        assertEquals(1, year.rhythm.daysPerMonth[6])

        assertEquals(listOf("moonboard", "kilter"), year.highlights.hardestFlashes.map { it.board })
        assertEquals(LocalDate(2026, 2, 1), year.highlights.biggestDay)
        assertEquals(2, year.highlights.biggestDaySends)
        assertEquals(RepeatedProblem("Problem k2", "kilter", 40, 22.0, 2), year.highlights.mostRepeated)
        val project = year.highlights.longestProject!!
        assertEquals("Problem k1", project.send.name)
        assertEquals(6, project.tries)
        assertEquals(LocalDate(2025, 11, 20), project.firstTry)
        assertEquals(2, project.sessions)

        val previous = year.previous!!
        assertEquals(2025, previous.year)
        assertEquals(1, previous.totals.climbingDays)
        assertEquals(4, previous.totals.tries)
        assertEquals(0, previous.totals.sends)
        assertTrue(previous.gradeStepsByBoard.isEmpty())
    }

    @Test
    fun aSendAfterTriesInAnEarlierYearIsNoFlash() {
        val input = Input(
            entries = listOf(
                entry("2025-12-30T18:00:00", "p", send = false, tries = 1),
                entry("2026-01-02T18:00:00", "p", tries = 1),
                // The same problem at another angle is another problem.
                entry("2026-01-02T18:30:00", "p", tries = 1, angle = 30),
            ),
        )
        val year = review(input, 2026, LocalDate(2026, 12, 31))
        assertEquals(2, year.volume.firstSends)
        assertEquals(1, year.volume.flashes)
        assertEquals(1, year.volume.problems)
        assertNull(year.highlights.longestProject)
    }

    @Test
    fun gradeStepsCountDisplayedGradesPerBoard() {
        val input = Input(
            entries = listOf(
                entry("2025-05-01T18:00:00", "a", difficulty = 20.0),
                entry("2026-05-01T18:00:00", "b", difficulty = 23.0),
                entry("2026-05-02T18:00:00", "m", board = "moonboard", difficulty = 26.0),
            ),
        )
        val today = LocalDate(2026, 12, 31)
        assertEquals(mapOf("kilter" to 3), review(input, 2026, today).previous!!.gradeStepsByBoard)
        // A scale with two difficulties per grade: 20 and 21 are one grade, 22 and 23 the next.
        val coarse: (Double) -> String = { (it.roundToInt() / 2).toString() }
        assertEquals(mapOf("kilter" to 1), review(input, 2026, today, label = coarse).previous!!.gradeStepsByBoard)
        assertEquals(-1, YearReviewComputer.gradeSteps(23.0, 20.0, coarse))
        assertEquals(0, YearReviewComputer.gradeSteps(20.0, 21.0, coarse))
    }

    // ── Rhythm ───────────────────────────────────────────────────────

    @Test
    fun rhythmFindsTheBusiestMonthTheLongestRunOfWeeksAndTheFavouriteDay() {
        // 5 January 2026 is a Monday.
        val days = listOf("2026-01-05", "2026-01-07", "2026-01-12", "2026-01-19", "2026-02-09", "2026-02-10", "2026-02-12")
        val input = Input(entries = days.mapIndexed { i, d -> entry("${d}T18:00:00", "p$i") })
        val rhythm = review(input, 2026, LocalDate(2026, 3, 1)).rhythm
        assertEquals(1, rhythm.busiestMonth)
        assertEquals(4, rhythm.activeWeeks)
        assertEquals(9, rhythm.weeks)
        assertEquals(3, rhythm.longestWeekStreak)
        assertEquals(LocalDate(2026, 1, 5), rhythm.longestWeekStreakStart)
        assertEquals(DayOfWeek.MONDAY, rhythm.favouriteWeekday)
        assertEquals(4, rhythm.favouriteWeekdayDays)
    }

    @Test
    fun rhythmTiesGoToTheEarlierMonthAndWeekday() {
        // Tuesday and Thursday twice each, January and March twice each.
        val days = listOf("2026-01-06", "2026-01-08", "2026-03-03", "2026-03-05")
        val input = Input(entries = days.mapIndexed { i, d -> entry("${d}T18:00:00", "p$i") })
        val rhythm = review(input, 2026, LocalDate(2026, 12, 31)).rhythm
        assertEquals(1, rhythm.busiestMonth)
        assertEquals(DayOfWeek.TUESDAY, rhythm.favouriteWeekday)
        assertEquals(2, rhythm.favouriteWeekdayDays)
        assertEquals(1, rhythm.longestWeekStreak)
        assertEquals(LocalDate(2026, 1, 5), rhythm.longestWeekStreakStart)
    }

    // ── Sessions and training ────────────────────────────────────────

    @Test
    fun timedSessionsCountWhenEndedAndInTheYear() {
        val input = Input(
            timedSessions = listOf(
                TimedSession("2026-02-01T18:00:00", activeSeconds = 3_600),
                TimedSession("2026-02-03T18:00:00", activeSeconds = 1_830),
                TimedSession("2026-02-05T18:00:00", activeSeconds = 900, ended = false),
                TimedSession("2025-12-31T18:00:00", activeSeconds = 3_600),
            ),
        )
        val volume = review(input, 2026, LocalDate(2026, 12, 31)).volume
        assertEquals(2, volume.timedSessions)
        assertEquals(90, volume.sessionMinutes)
    }

    @Test
    fun trainingCountsFinishedWorkoutsMeasuredBestsAndCompletedComebacks() {
        val hour = 3_600_000L
        val workouts = listOf(
            Workout("w1", startedAt = 0, endedAt = 45 * 60_000L, day = "2026-03-01", updatedAt = 0),
            Workout("w2", startedAt = hour, endedAt = hour + 30 * 60_000L, day = "2026-03-01", updatedAt = 0),
            Workout("w3", startedAt = 2 * hour, endedAt = null, day = "2026-04-01", updatedAt = 0),
            Workout("w4", startedAt = 0, endedAt = hour, day = "2025-12-31", updatedAt = 0),
        )
        val newest = Benchmark("b2", "hang", loadKg = 12.0, source = BenchmarkSource.TEST, measuredAt = millis(LocalDate(2026, 5, 1)))
        val benchmarks = listOf(
            Benchmark("b1", "hang", loadKg = 10.0, measuredAt = millis(LocalDate(2026, 2, 1))),
            newest,
            Benchmark("b3", "pullup", reps = 12, source = BenchmarkSource.ESTIMATE, measuredAt = millis(LocalDate(2026, 3, 1))),
            Benchmark("b4", "pullup", reps = 10, measuredAt = millis(LocalDate(2025, 6, 1))),
        )
        val finger = Injury("i", InjuryRegion.FINGER, severity = 3, startedOn = "2026-06-01")
        val last = Comeback.ladder(finger).stages.lastIndex
        fun plan(stage: Int, ended: String?) = ComebackPlan(acknowledgedOn = "2026-06-01", startedOn = "2026-06-01", stage = stage, endedOn = ended)
        val injuries = listOf(
            finger.copy(id = "done", plan = plan(last, "2026-08-01")),
            finger.copy(id = "stopped", plan = plan(0, "2026-09-01")),
            finger.copy(id = "last-year", plan = plan(last, "2025-12-01")),
            finger.copy(id = "running", plan = plan(last, null)),
        )
        val training = review(Input(workouts = workouts, benchmarks = benchmarks, injuries = injuries), 2026, LocalDate(2026, 12, 31)).training
        assertEquals(2, training.workouts)
        assertEquals(1, training.workoutDays)
        assertEquals(75, training.workoutMinutes)
        assertEquals(listOf(newest), training.bestValues)
        assertEquals(1, training.comebacksCompleted)
        assertFalse(training.isEmpty)
    }

    @Test
    fun yearsListEveryYearWithDataAndNeverTheFuture() {
        val input = Input(
            entries = listOf(entry("2024-03-01T18:00:00", "a"), entry("2027-01-02T18:00:00", "future")),
            climbingDays = listOf(ClimbingDayEntry("g", "2023-07-01", ClimbingDayKind.OUTDOOR, 240, ClimbIntensity.HARD)),
            workouts = listOf(Workout("w", startedAt = 0, endedAt = 1, day = "2025-02-02", updatedAt = 0)),
        )
        assertEquals(listOf(2026, 2025, 2024, 2023), YearReviewComputer.years(input, LocalDate(2026, 12, 1), utc))
    }
}
