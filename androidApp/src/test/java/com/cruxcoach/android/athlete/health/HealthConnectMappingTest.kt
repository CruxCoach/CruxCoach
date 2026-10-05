package com.cruxcoach.android.athlete.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthConnectMappingTest {

    private val climbing = ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
    private val other = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    private val running = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING

    @Test
    fun `rock climbing sessions map by their title`() {
        assertEquals(ClimbingDayKind.GYM_BOULDER, HealthConnectMapping.kindFor(climbing, "Bouldern Halle", null))
        assertEquals(ClimbingDayKind.GYM_ROPE, HealthConnectMapping.kindFor(climbing, "Vorstieg", null))
        assertEquals(ClimbingDayKind.OUTDOOR, HealthConnectMapping.kindFor(climbing, "Fels Frankenjura", null))
        assertEquals(ClimbingDayKind.OUTDOOR, HealthConnectMapping.kindFor(climbing, "Font bouldering", null))
        assertEquals(ClimbingDayKind.OTHER_BOARD, HealthConnectMapping.kindFor(climbing, "Kilter 40°", null))
        assertEquals(ClimbingDayKind.OTHER, HealthConnectMapping.kindFor(climbing, null, null))
    }

    @Test
    fun `other types count only when they say climbing`() {
        assertEquals(ClimbingDayKind.GYM_BOULDER, HealthConnectMapping.kindFor(other, "Boulder session", null))
        assertEquals(ClimbingDayKind.OTHER, HealthConnectMapping.kindFor(other, null, "Klettern mit Freunden"))
        assertNull(HealthConnectMapping.kindFor(other, "Krafttraining", null))
        assertNull(HealthConnectMapping.kindFor(running, "Morning run", null))
    }

    @Test
    fun `intensity defaults to volume unless the title says otherwise`() {
        assertEquals(ClimbIntensity.VOLUME, HealthConnectMapping.intensityFor("Bouldern", null))
        assertEquals(ClimbIntensity.LIMIT, HealthConnectMapping.intensityFor("Projekt 7B", null))
        assertEquals(ClimbIntensity.HARD, HealthConnectMapping.intensityFor(null, "hart heute"))
        assertEquals(ClimbIntensity.LIGHT, HealthConnectMapping.intensityFor("Technik locker", null))
    }

    @Test
    fun `sleep sessions are merged and awake time is subtracted`() {
        val t = Instant.parse("2026-10-04T22:00:00Z")
        val sessions = listOf(
            SleepMath.Session(t, t.plusSeconds(4 * 3600), awake = listOf(t.plusSeconds(3600) to t.plusSeconds(3600 + 1800))),
            // Overlapping second source for the same night counts once.
            SleepMath.Session(t.plusSeconds(3 * 3600), t.plusSeconds(7 * 3600)),
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
}
