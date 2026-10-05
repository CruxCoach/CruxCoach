package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClimbingLoadTest {

    private val today = LocalDate.parse("2026-10-05")
    /** Working grade 7a (22), flash 6c (20). */
    private val summary = LogbookSummary(primaryBrand = "kilter", sampleSize = 40, workingDifficulty = 22.0, flashDifficulty = 20.0)

    private fun row(difficulty: Double?, tries: Int, brand: String = "kilter", angle: Int = 40) =
        ClimbRow(today.toString(), "c$difficulty-$tries", difficulty, tries, sent = true, flash = false, angle = angle,
            brand = brand, climbedAt = today.toString() + "T18:00:00")

    private val catalog = ExerciseCatalog(1, listOf(
        ExerciseDefinition("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
            domains = listOf(LoadDomain.FINGER), i18n = mapOf("en" to ExerciseText("Max hang"))),
        ExerciseDefinition("pull.pull_up", ExerciseCategoryV2.PULL, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
            domains = listOf(LoadDomain.SHOULDER, LoadDomain.ELBOW), i18n = mapOf("en" to ExerciseText("Pull-up"))),
    ))

    @Test
    fun attemptsAreClassifiedAgainstTheAnchors() {
        assertEquals(ClimbIntensity.LIMIT, ClimbingLoad.classifyAttempt(22.0, summary))
        assertEquals(ClimbIntensity.HARD, ClimbingLoad.classifyAttempt(21.0, summary))
        assertEquals(ClimbIntensity.VOLUME, ClimbingLoad.classifyAttempt(19.0, summary))
        assertEquals(ClimbIntensity.LIGHT, ClimbingLoad.classifyAttempt(18.0, summary))
        assertNull(ClimbingLoad.classifyAttempt(22.0, LogbookSummary()))
    }

    @Test
    fun limitDayNeedsAMeaningfulShareOfAttempts() {
        val limitDay = listOf(row(22.0, 6), row(19.0, 4))
        assertEquals(ClimbIntensity.LIMIT, ClimbingLoad.classifyDay(limitDay, summary))
        // One stray try at the limit inside a long volume session stays a volume day.
        val volumeDay = listOf(row(22.0, 1)) + (1..12).map { row(19.0 + (it % 2) * 0.0, 2).copy(climbUuid = "v$it") }
        assertEquals(ClimbIntensity.VOLUME, ClimbingLoad.classifyDay(volumeDay, summary))
        // Rows from another board family are not graded against this anchor.
        assertNull(ClimbingLoad.classifyDay(listOf(row(22.0, 5, brand = "moon")), summary))
    }

    @Test
    fun hardAttemptsCountMoreThanVolume() {
        val limit = ClimbingLoad.dayUnits(listOf(row(22.0, 10)), summary, 0, emptyList(), emptyList(), catalog)
        val volume = ClimbingLoad.dayUnits(listOf(row(19.0, 10)), summary, 0, emptyList(), emptyList(), catalog)
        assertTrue(limit.getValue(LoadStructure.FINGER) > volume.getValue(LoadStructure.FINGER))
        assertEquals(10.0, volume.getValue(LoadStructure.FINGER), 1e-9)
    }

    @Test
    fun manualClimbingDaysAndOffWallSetsAddLoad() {
        val gym = ClimbingDayEntry("m1", today.toString(), ClimbingDayKind.GYM_BOULDER, minutes = 90, intensity = ClimbIntensity.HARD)
        val rope = gym.copy(id = "m2", kind = ClimbingDayKind.GYM_ROPE)
        val gymUnits = ClimbingLoad.dayUnits(emptyList(), summary, 0, listOf(gym), emptyList(), catalog)
        val ropeUnits = ClimbingLoad.dayUnits(emptyList(), summary, 0, listOf(rope), emptyList(), catalog)
        assertEquals(30 * 1.4, gymUnits.getValue(LoadStructure.FINGER), 1e-9)
        assertTrue(ropeUnits.getValue(LoadStructure.FINGER) < gymUnits.getValue(LoadStructure.FINGER))

        val hang = ExerciseSet("s1", "w", "finger.max_hang", 0, 0, durationS = 10.0, loadKg = 14.0, bodyweightKg = 70.0, completedAt = 1L)
        val warmup = hang.copy(id = "s2", setType = SetType.WARMUP)
        val pulls = ExerciseSet("s3", "w", "pull.pull_up", 1, 0, reps = 5, bodyweightKg = 70.0, completedAt = 1L)
        val sets = ClimbingLoad.dayUnits(emptyList(), summary, 0, emptyList(), listOf(hang, warmup, pulls), catalog)
        assertEquals(1.2, sets.getValue(LoadStructure.FINGER), 1e-9)
        assertEquals(1.0, sets.getValue(LoadStructure.SHOULDER), 1e-9)
    }

    @Test
    fun boardMinutesWithoutRowsAreEstimated() {
        val units = ClimbingLoad.dayUnits(emptyList(), summary, 60, emptyList(), emptyList(), catalog)
        assertEquals(20.0, units.getValue(LoadStructure.FINGER), 1e-9)
    }

    @Test
    fun spikeIsDetectedAgainstTheAthletesOwnNormal() {
        val daily = mutableMapOf<LocalDate, Map<LoadStructure, Double>>()
        // Four weeks of a steady 10 units every other day …
        (8 until 56 step 2).forEach { daily[today.minus(DatePeriod(days = it))] = mapOf(LoadStructure.FINGER to 10.0) }
        // … then a heavy last week.
        (0 until 7).forEach { daily[today.minus(DatePeriod(days = it))] = mapOf(LoadStructure.FINGER to 20.0) }
        val status = ClimbingLoad.status(daily, today)
        val finger = status.structures.getValue(LoadStructure.FINGER)
        assertEquals(LoadTrend.SPIKE, finger.trend)
        assertTrue(finger.ratio!! > ClimbingLoad.SPIKE_RATIO)
        assertEquals(ClimbingLoad.SERIES_DAYS, status.series.getValue(LoadStructure.FINGER).size)
        assertEquals(today, status.series.getValue(LoadStructure.FINGER).last().first)
        // Nothing logged: no baseline, low trend.
        assertEquals(LoadTrend.LOW, status.structures.getValue(LoadStructure.SKIN).trend)
        assertNull(status.structures.getValue(LoadStructure.SKIN).ratio)
    }

    @Test
    fun recoveryGrowsWithIntensityAndAge() {
        assertEquals(60, ClimbingLoad.recoveryHours(ClimbIntensity.LIMIT, null))
        assertEquals(48, ClimbingLoad.recoveryHours(ClimbIntensity.HARD, AgeBand.Y18_39))
        assertEquals(36, ClimbingLoad.recoveryHours(ClimbIntensity.VOLUME, AgeBand.Y40_54))
        assertEquals(84, ClimbingLoad.recoveryHours(ClimbIntensity.LIMIT, AgeBand.Y55_PLUS))
    }
}
