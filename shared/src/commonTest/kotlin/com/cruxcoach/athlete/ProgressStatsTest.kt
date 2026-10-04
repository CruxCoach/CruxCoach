package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressStatsTest {

    private val zone = TimeZone.UTC

    private fun def(slug: String, kind: ExerciseKind, load: LoadMode, unilateral: Boolean = false,
                    domains: List<LoadDomain> = emptyList()) =
        ExerciseDefinition(slug, ExerciseCategoryV2.PULL, kind, load, unilateral, domains = domains,
            i18n = mapOf("en" to ExerciseText(slug)))

    private val weighted = def("pull.weighted_pull_up", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
        domains = listOf(LoadDomain.ELBOW, LoadDomain.SHOULDER))
    private val pickup = def("finger.one_arm_pickup", ExerciseKind.HANG, LoadMode.EXTERNAL, unilateral = true,
        domains = listOf(LoadDomain.FINGER))
    private val pullUp = def("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT)

    private fun ms(day: String, minute: Int = 0) =
        LocalDate.parse(day).atStartOfDayIn(zone).toEpochMilliseconds() + minute * 60_000L

    private fun set(slug: String, workout: String, day: String, index: Int = 0, reps: Int? = null, load: Double? = null,
                    duration: Double? = null, side: Side? = null, bw: Double? = 70.0, type: SetType = SetType.WORK) =
        ExerciseSet("$workout-$index-$side", workout, slug, 0, index, type, side, reps = reps, loadKg = load,
            durationS = duration, bodyweightKg = bw, completedAt = ms(day, index))

    @Test
    fun weightedPullUpsShowAddedKilosAndChangeOverFourWeeks() {
        val sets = listOf(
            set(weighted.slug, "a", "2026-08-01", reps = 5, load = 10.0),
            set(weighted.slug, "a", "2026-08-01", index = 1, reps = 5, load = 12.0),
            set(weighted.slug, "b", "2026-09-10", reps = 5, load = 20.0),
            set(weighted.slug, "b", "2026-09-10", index = 1, reps = 3, load = 20.0, type = SetType.WARMUP),
        )
        val p = ProgressStats.exerciseProgress(weighted, sets, zone, null)
        assertEquals(2, p.sessionCount)
        assertEquals(CapacityKind.E1RM_TOTAL, p.kind)
        // (70 + 20) × (1 + 5/30) − 70 = 35
        assertEquals(35.0, p.last!!, 1e-9)
        assertEquals(35.0, p.best!!, 1e-9)
        // first session best: (70 + 12) × (1 + 5/30) − 70 ≈ 25.67
        assertEquals(35.0 - (82.0 * (1 + 5 / 30.0) - 70.0), p.change28!!, 1e-9)
        assertEquals(105.0 / 70.0 * 100.0, p.percentBodyweight!!, 1e-9)
        // warm-ups never count, also not in the set count
        assertEquals(1, p.sessions.last().sets)
    }

    @Test
    fun noChangeWithoutASessionFourWeeksEarlier() {
        val sets = listOf(
            set(weighted.slug, "a", "2026-09-01", reps = 5, load = 10.0),
            set(weighted.slug, "b", "2026-09-10", reps = 5, load = 12.0),
        )
        assertNull(ProgressStats.exerciseProgress(weighted, sets, zone, null).change28)
    }

    @Test
    fun oneSidedHangsKeepBothSidesApart() {
        val sets = listOf(
            set(pickup.slug, "a", "2026-09-01", load = 30.0, duration = 10.0, side = Side.RIGHT),
            set(pickup.slug, "a", "2026-09-01", index = 1, load = 26.0, duration = 10.0, side = Side.LEFT),
        )
        val s = ProgressStats.exerciseProgress(pickup, sets, zone, 70.0).sessions.single()
        assertEquals(30.0, s.capacity[Side.RIGHT]!!, 1e-9)
        assertEquals(26.0, s.capacity[Side.LEFT]!!, 1e-9)
        assertEquals(10.0 * 30 + 10.0 * 26, s.volume, 1e-9)
        assertEquals(Side.RIGHT, s.bestSet?.side)
    }

    @Test
    fun bodyweightRepsUseTheMaximumAndAFallbackBodyweight() {
        val sets = listOf(set(pullUp.slug, "a", "2026-09-01", reps = 9, bw = null), set(pullUp.slug, "a", "2026-09-01", index = 1, reps = 7, bw = null))
        val p = ProgressStats.exerciseProgress(pullUp, sets, zone, 70.0)
        assertEquals(9.0, p.last!!, 1e-9)
        assertEquals(16.0, p.sessions.single().volume, 1e-9)
        assertNull(p.percentBodyweight)
    }

    @Test
    fun weeklyAggregatesSplitClimbingAndOffBoard() {
        val today = LocalDate.parse("2026-10-04") // Sunday
        val activities = mapOf(
            "2026-09-29" to DayActivity("2026-09-29", climbingMinutes = 60),
            "2026-10-01" to DayActivity("2026-10-01", workoutMinutes = 40, workoutLoad = 240),
            "2026-10-02" to DayActivity("2026-10-02", climbingEfforts = 10, workoutMinutes = 20),
            "2026-09-22" to DayActivity("2026-09-22", climbingMinutes = 30),
        )
        val weeks = ProgressStats.weekly(activities, listOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01")), today, 2)
        assertEquals(2, weeks.size)
        val current = weeks.last()
        assertEquals(LocalDate.parse("2026-09-28"), current.weekStart)
        assertEquals(3, current.trainingDays)
        assertEquals(2, current.climbingDays)
        assertEquals(2, current.offBoardDays)
        assertEquals(60 + 40, current.climbingMinutes)
        assertEquals(60, current.workoutMinutes)
        assertEquals(2, current.sets)
        assertEquals(1, weeks.first().trainingDays)
    }

    @Test
    fun calendarGradesTheDayLoad() {
        val today = LocalDate.parse("2026-10-04")
        val activities = mapOf(
            "2026-10-04" to DayActivity("2026-10-04", climbingMinutes = 120),
            "2026-10-03" to DayActivity("2026-10-03", workoutMinutes = 20, workoutLoad = 60),
        )
        val cal = ProgressStats.calendar(activities, today, 3)
        assertEquals(3, cal.size)
        assertEquals(3, cal[today])
        assertEquals(1, cal[LocalDate.parse("2026-10-03")])
        assertEquals(0, cal[LocalDate.parse("2026-10-02")])
    }

    @Test
    fun domainWeeksCountBoardAndExerciseDays() {
        val today = LocalDate.parse("2026-10-04")
        val catalog = ExerciseCatalog(1, listOf(pickup, weighted))
        val activities = mapOf("2026-09-30" to DayActivity("2026-09-30", climbingMinutes = 60))
        val sets = listOf(
            LocalDate.parse("2026-10-01") to set(pickup.slug, "w", "2026-10-01", load = 20.0, duration = 10.0, side = Side.RIGHT),
            LocalDate.parse("2026-10-02") to set(weighted.slug, "x", "2026-10-02", reps = 5, load = 5.0),
        )
        val week = ProgressStats.domainWeeks(activities, sets, catalog, today, 1).single()
        assertEquals(2, week.days[LoadDomain.FINGER])   // board day + pick-up day
        assertEquals(2, week.days[LoadDomain.SHOULDER]) // board day + weighted pull-up day
        assertEquals(1, week.days[LoadDomain.SKIN])
        assertEquals(0, week.days[LoadDomain.LOWER_BODY])
    }

    @Test
    fun displayValueOnlyRebasesBodyweightPlusCapacities() {
        assertEquals(15.0, ProgressStats.displayValue(weighted, CapacityKind.E1RM_TOTAL, 85.0, 70.0), 1e-9)
        assertEquals(85.0, ProgressStats.displayValue(weighted, CapacityKind.E1RM_TOTAL, 85.0, null), 1e-9)
        assertEquals(30.0, ProgressStats.displayValue(pickup, CapacityKind.TEN_SECOND_MAX, 30.0, 70.0), 1e-9)
        assertTrue(ProgressStats.volume(pickup, set(pickup.slug, "w", "2026-10-01", load = 20.0, duration = 10.0, type = SetType.WARMUP), 70.0) == 0.0)
        assertNotNull(ProgressStats.dayOf(ms("2026-10-01", 30), zone))
    }
}
