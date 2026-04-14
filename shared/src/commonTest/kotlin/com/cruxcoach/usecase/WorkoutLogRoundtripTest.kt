package com.cruxcoach.usecase

import com.cruxcoach.domain.model.ExerciseBlock
import com.cruxcoach.domain.model.WorkoutLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutLogRoundtripTest {

    private val repo = FakeWorkoutRepository()

    @Test
    fun insertAndRetrieve_fullWorkoutLog() {
        val log = WorkoutLog(
            id = 1,
            sessionId = 10,
            date = "2026-03-01",
            actualDurationMin = 55,
            perceivedRpe = 7.5,
            energyLevel = 3,
            moodPre = 3,
            moodPost = 4,
            fingerSkinStatus = "GOOD",
            painAreas = emptyList(),
            sleepHoursPrevNight = 7.5,
            completedExercises = listOf(
                ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, duration = "7 sec", restSeconds = 180)
            ),
            freeNotes = "Gute Session"
        )

        repo.insertWorkout(log)
        val retrieved = repo.getWorkoutById(1)

        assertNotNull(retrieved)
        assertEquals("2026-03-01", retrieved.date)
        assertEquals(55, retrieved.actualDurationMin)
        assertEquals(7.5, retrieved.perceivedRpe)
        assertEquals(3, retrieved.energyLevel)
        assertEquals(4, retrieved.moodPost)
        assertEquals("GOOD", retrieved.fingerSkinStatus)
        assertEquals("Gute Session", retrieved.freeNotes)
        assertEquals(1, retrieved.completedExercises.size)
    }

    @Test
    fun getRecentWorkouts_returnsInOrder() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-02-28", perceivedRpe = 6.0))
        repo.insertWorkout(WorkoutLog(id = 2, date = "2026-03-01", perceivedRpe = 7.0))
        repo.insertWorkout(WorkoutLog(id = 3, date = "2026-03-02", perceivedRpe = 8.0))

        val recent = repo.getRecentWorkouts(2)
        assertEquals(2, recent.size)
    }

    @Test
    fun getWorkoutsForDateRange_filtersCorrectly() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-02-28", perceivedRpe = 6.0))
        repo.insertWorkout(WorkoutLog(id = 2, date = "2026-03-01", perceivedRpe = 7.0))
        repo.insertWorkout(WorkoutLog(id = 3, date = "2026-03-05", perceivedRpe = 8.0))

        val inRange = repo.getWorkoutsForDateRange("2026-03-01", "2026-03-07")
        assertEquals(2, inRange.size)
    }

    @Test
    fun getAvgRpeLastN_calculatesCorrectly() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 6.0))
        repo.insertWorkout(WorkoutLog(id = 2, date = "2026-03-02", perceivedRpe = 8.0))

        val avg = repo.getAvgRpeLastN(2)
        assertNotNull(avg)
        assertEquals(7.0, avg, 0.01)
    }

    @Test
    fun getAvgRpeLastN_returnsNull_whenEmpty() {
        val avg = repo.getAvgRpeLastN(5)
        assertNull(avg)
    }

    @Test
    fun deleteWorkout_removesLog() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 7.0))
        assertNotNull(repo.getWorkoutById(1))

        repo.deleteWorkout(1)
        assertNull(repo.getWorkoutById(1))
    }

    @Test
    fun insertWorkout_withPainAreas() {
        val log = WorkoutLog(
            id = 1,
            date = "2026-03-01",
            perceivedRpe = 7.0,
            painAreas = listOf("finger A2 pulley", "shoulder")
        )
        repo.insertWorkout(log)

        val retrieved = repo.getWorkoutById(1)
        assertNotNull(retrieved)
        assertEquals(2, retrieved.painAreas.size)
        assertTrue(retrieved.painAreas.contains("finger A2 pulley"))
        assertTrue(retrieved.painAreas.contains("shoulder"))
    }

    @Test
    fun insertWorkout_withSkinStatus() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-03-01", fingerSkinStatus = "SPLIT"))
        val retrieved = repo.getWorkoutById(1)
        assertNotNull(retrieved)
        assertEquals("SPLIT", retrieved.fingerSkinStatus)
    }

    @Test
    fun countThisWeek_returnsCount() {
        repo.insertWorkout(WorkoutLog(id = 1, date = "2026-03-01"))
        repo.insertWorkout(WorkoutLog(id = 2, date = "2026-03-03"))
        assertEquals(2, repo.countThisWeek())
    }
}
