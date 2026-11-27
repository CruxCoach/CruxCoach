package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceProfileTest {

    private val today = LocalDate.parse("2026-10-05")
    private val catalog = ExerciseCatalog(1, listOf(
        ExerciseDefinition("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
            domains = listOf(LoadDomain.FINGER), i18n = mapOf("en" to ExerciseText("Max hang"))),
        ExerciseDefinition("pull.weighted_pull_up", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
            i18n = mapOf("en" to ExerciseText("Weighted pull-up"))),
        ExerciseDefinition("pull.pull_up", ExerciseCategoryV2.PULL, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
            i18n = mapOf("en" to ExerciseText("Pull-up"))),
        ExerciseDefinition("finger.one_arm_pickup", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.EXTERNAL,
            unilateral = true, domains = listOf(LoadDomain.FINGER), i18n = mapOf("en" to ExerciseText("Pick-up"))),
    ))
    private val dayOf: (Long) -> LocalDate = { ms -> LocalDate(1970, 1, 1).plus(DatePeriod(days = (ms / 86_400_000L).toInt())) }

    private fun send(day: LocalDate, climb: String, difficulty: Double) =
        ClimbRow(day.toString(), climb, difficulty, 2, sent = true, flash = false, angle = 40, brand = "kilter",
            climbedAt = day.toString() + "T18:00:00")

    @Test
    fun weeklyTimelineFollowsTheHardestSends() {
        // Twelve weeks: six different climbs a week, one grade harder after week six.
        val rows = (0 until 12).flatMap { w ->
            val day = today.minus(DatePeriod(days = (11 - w) * 7))
            val base = if (w < 6) 20.0 else 22.0
            (0 until 6).map { i -> send(day, "w$w-c$i", base - (i % 3)) }
        }
        val timeline = PerformanceProfiles.weeklyAnchors(rows, today, flashOnly = false)
        assertTrue(timeline.isNotEmpty())
        assertTrue(timeline.last().difficulty > timeline.first().difficulty, "$timeline")
        assertTrue(timeline.zipWithNext().all { (a, b) -> b.weekStart > a.weekStart })
    }

    @Test
    fun fingerStrengthIsInPercentOfBodyWeightAndNormalisedTo20mm() {
        val hang = ExerciseSet("s1", "w", "finger.max_hang", 0, 0, durationS = 10.0, loadKg = 21.0, edgeMm = 20.0,
            bodyweightKg = 70.0, completedAt = 1L)
        val small = hang.copy(id = "s2", edgeMm = 15.0, loadKg = 7.0)
        val profile = PerformanceProfiles.build(emptyList(), LogbookSummary(workingDifficulty = 22.0), listOf(
            today.minus(DatePeriod(days = 10)) to hang, today to small,
        ), emptyList(), catalog, emptyList(), today, dayOf)
        assertEquals(2, profile.finger.size)
        assertEquals(130.0, profile.finger.first().pctBw, 1e-6)
        // 77 kg on 15 mm ≈ 86.6 kg on 20 mm → 123.75 %.
        assertEquals(123.75, profile.finger.last().pctBw, 1e-6)
        // 123.75 % sits under the 7a band (≈ 127–151 %).
        val position = assertNotNull(profile.fingerPosition)
        assertEquals(GradeStrengthNorms.Position.BELOW, position.position)
    }

    @Test
    fun pullFallsBackToBodyweightRepsAndEstimatesAreMarked() {
        val reps = ExerciseSet("p1", "w", "pull.pull_up", 0, 0, reps = 10, bodyweightKg = 70.0, completedAt = 1L)
        val estimate = Benchmark("b1", "finger.one_arm_pickup", side = Side.RIGHT, loadKg = 30.0, durationS = 10.0,
            bodyweightKg = 70.0, source = BenchmarkSource.ESTIMATE, measuredAt = 0L)
        val profile = PerformanceProfiles.build(emptyList(), LogbookSummary(), listOf(today to reps), listOf(estimate),
            catalog, listOf(today to 70.0), today, dayOf)
        assertEquals(100.0 * (1 + 10 / 30.0), profile.pull.single().pctBw, 1e-6)
        assertTrue(profile.pickupLeft.isEmpty())
        assertEquals(StrengthSource.ESTIMATE, profile.pickupRight.single().source)
    }

    @Test
    fun correlationNeedsSixPairedWeeks() {
        val grades = (0 until 8).map { GradePoint(PerformanceProfiles.weekStart(today.minus(DatePeriod(days = (7 - it) * 7))), 18.0 + it) }
        // Four measured weeks pair with five grade weeks (a value also counts for the week after).
        val finger = grades.take(4).map { StrengthPoint(it.weekStart.plusDays(6), 100.0 + 5 * it.difficulty, StrengthSource.LOG) }
        val few = PerformanceProfiles.correlation(grades, finger)
        assertNull(few.r)
        assertEquals(1, few.missingWeeks)
        val all = grades.map { StrengthPoint(it.weekStart.plusDays(6), 100.0 + 5 * it.difficulty, StrengthSource.LOG) }
        val r = PerformanceProfiles.correlation(grades, all)
        assertEquals(1.0, r.r!!, 1e-9)
        assertEquals(0, r.missingWeeks)
        // Estimates never count as measurements.
        assertNull(PerformanceProfiles.correlation(grades, all.map { it.copy(source = StrengthSource.ESTIMATE) }).r)
    }

    @Test
    fun bottlenecksPutTheWeakestAreaFirst() {
        val hang = ExerciseSet("s1", "w", "finger.max_hang", 0, 0, durationS = 10.0, loadKg = 0.0, edgeMm = 20.0,
            bodyweightKg = 70.0, completedAt = 1L)
        val pull = ExerciseSet("p1", "w", "pull.weighted_pull_up", 1, 0, reps = 3, loadKg = 40.0, bodyweightKg = 70.0, completedAt = 1L)
        // 7b (24) climber hanging only body weight: fingers far below typical, pulling above.
        val profile = PerformanceProfiles.build(emptyList(), LogbookSummary(workingDifficulty = 24.0, flashDifficulty = 21.0),
            listOf(today to hang, today to pull), emptyList(), catalog, emptyList(), today, dayOf)
        assertEquals(BottleneckArea.FINGER, profile.bottlenecks.first().area)
        assertEquals(BottleneckReason.BELOW_TYPICAL, profile.bottlenecks.first().reason)
        assertTrue(profile.bottlenecks.last().score == null)
    }

    @Test
    fun normsPlaceValuesAndFlagImplausibleEstimates() {
        assertEquals(GradeStrengthNorms.Position.WITHIN, GradeStrengthNorms.position(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, 22.0, 138.0))
        assertEquals(GradeStrengthNorms.Position.BELOW, GradeStrengthNorms.position(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, 22.0, 110.0))
        assertEquals(22.0, GradeStrengthNorms.typicalDifficulty(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, 120.0 + 5.3 * 3.5), 1e-9)
        assertEquals(GradeStrengthNorms.Plausibility.UNUSUALLY_HIGH,
            GradeStrengthNorms.plausibility(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, 18.0, 200.0))
    }

    private fun LocalDate.plusDays(n: Int): LocalDate = plus(DatePeriod(days = n))
}
