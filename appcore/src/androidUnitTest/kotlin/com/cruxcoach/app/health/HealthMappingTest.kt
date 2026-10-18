package com.cruxcoach.app.health

import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Port of Android's HealthConnectMappingTest. The assertions are Android's; the exercise type becomes
 * "is a climbing workout" (HealthKit reads climbing workouts only) and times are epoch milliseconds.
 */
class HealthMappingTest {

    private val climbing = true
    private val other = false
    private val running = false

    @Test
    fun `rock climbing sessions map by their title`() {
        assertEquals(ClimbingDayKind.GYM_BOULDER, HealthMapping.kindFor(climbing, "Bouldern Halle", null))
        assertEquals(ClimbingDayKind.GYM_ROPE, HealthMapping.kindFor(climbing, "Vorstieg", null))
        assertEquals(ClimbingDayKind.OUTDOOR, HealthMapping.kindFor(climbing, "Fels Frankenjura", null))
        assertEquals(ClimbingDayKind.OUTDOOR, HealthMapping.kindFor(climbing, "Font bouldering", null))
        assertEquals(ClimbingDayKind.OTHER_BOARD, HealthMapping.kindFor(climbing, "Kilter 40°", null))
        assertEquals(ClimbingDayKind.OTHER, HealthMapping.kindFor(climbing, null, null))
    }

    @Test
    fun `other types count only when they say climbing`() {
        assertEquals(ClimbingDayKind.GYM_BOULDER, HealthMapping.kindFor(other, "Boulder session", null))
        assertEquals(ClimbingDayKind.OTHER, HealthMapping.kindFor(other, null, "Klettern mit Freunden"))
        assertNull(HealthMapping.kindFor(other, "Krafttraining", null))
        assertNull(HealthMapping.kindFor(running, "Morning run", null))
    }

    @Test
    fun `intensity defaults to volume unless the title says otherwise`() {
        assertEquals(ClimbIntensity.VOLUME, HealthMapping.intensityFor("Bouldern", null))
        assertEquals(ClimbIntensity.LIMIT, HealthMapping.intensityFor("Projekt 7B", null))
        assertEquals(ClimbIntensity.HARD, HealthMapping.intensityFor(null, "hart heute"))
        assertEquals(ClimbIntensity.LIGHT, HealthMapping.intensityFor("Technik locker", null))
    }

    @Test
    fun `sleep sessions are merged and awake time is subtracted`() {
        val t = Instant.parse("2026-10-04T22:00:00Z").toEpochMilliseconds()
        val h = 3_600_000L
        val sessions = listOf(
            SleepMath.Session(t, t + 4 * h, awake = listOf(SleepMath.Range(t + h, t + h + h / 2))),
            // Overlapping second source for the same night counts once.
            SleepMath.Session(t + 3 * h, t + 7 * h),
        )
        assertEquals(6.5, SleepMath.hours(sessions))
        assertNull(SleepMath.hours(emptyList()))
    }

    @Test
    fun `sleep score from hours`() {
        assertEquals(4, SleepHint.scoreFor(8.2))
        assertEquals(3, SleepHint.scoreFor(6.8))
        assertEquals(2, SleepHint.scoreFor(5.4))
        assertEquals(1, SleepHint.scoreFor(4.0))
    }

    @Test
    fun `loose HealthKit samples follow the same window and awake rule`() {
        val h = 3_600_000L
        val windowStart = Instant.parse("2026-10-04T13:00:00Z").toEpochMilliseconds()
        val windowEnd = windowStart + 24 * h
        val bed = windowStart + 9 * h // 22:00
        val samples = listOf(
            HealthSleepSample(bed, bed + 8 * h, "inBed"),
            HealthSleepSample(bed + h, bed + 4 * h, "asleep"),
            HealthSleepSample(bed + 4 * h, bed + 4 * h + h / 2, "awake"),
            HealthSleepSample(bed + 4 * h + h / 2, bed + 8 * h, "asleep"),
            // Yesterday's nap ends before the window: not last night.
            HealthSleepSample(windowStart - 2 * h, windowStart - h, "asleep"),
            // A stage this app does not know is ignored, not counted as sleep.
            HealthSleepSample(bed, bed + 9 * h, "unknown"),
        )
        assertEquals(7.5, HealthSync.sleepHours(samples, windowStart, windowEnd))
        assertNull(HealthSync.sleepHours(samples.take(0), windowStart, windowEnd))
        assertNull(HealthSync.sleepHours(listOf(HealthSleepSample(bed, bed + h, "awake")), windowStart, windowEnd))
    }
}
