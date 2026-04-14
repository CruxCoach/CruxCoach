package com.cruxcoach.usecase

import com.cruxcoach.domain.model.ClimbLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClimbLogRoundtripTest {

    private val repo = FakeClimbRepository()

    @Test
    fun insertAndRetrieve_fullClimbLog() {
        val log = ClimbLog(
            id = 1,
            workoutLogId = 10,
            date = "2026-03-01",
            grade = "V5",
            style = "OVERHANG",
            holdTypes = listOf("CRIMP", "SLOPER"),
            attempts = 3,
            sent = true,
            flash = false,
            boardType = "KILTER",
            boardAngle = 40,
            notes = "Crux at move 7"
        )

        repo.insertClimb(log)
        val retrieved = repo.getClimbById(1)

        assertNotNull(retrieved)
        assertEquals("V5", retrieved.grade)
        assertEquals("OVERHANG", retrieved.style)
        assertEquals(2, retrieved.holdTypes.size)
        assertEquals(3, retrieved.attempts)
        assertTrue(retrieved.sent)
        assertEquals(false, retrieved.flash)
        assertEquals("KILTER", retrieved.boardType)
        assertEquals(40, retrieved.boardAngle)
    }

    @Test
    fun getClimbsForWorkout_groupsCorrectly() {
        repo.insertClimb(ClimbLog(id = 1, workoutLogId = 10, date = "2026-03-01", grade = "V3", sent = true))
        repo.insertClimb(ClimbLog(id = 2, workoutLogId = 10, date = "2026-03-01", grade = "V4", sent = false))
        repo.insertClimb(ClimbLog(id = 3, workoutLogId = 20, date = "2026-03-01", grade = "V5", sent = true))

        val workout10 = repo.getClimbsForWorkout(10)
        assertEquals(2, workout10.size)

        val workout20 = repo.getClimbsForWorkout(20)
        assertEquals(1, workout20.size)
    }

    @Test
    fun getSendsForDateRange_onlyReturnsSends() {
        repo.insertClimb(ClimbLog(id = 1, date = "2026-03-01", grade = "V3", sent = true))
        repo.insertClimb(ClimbLog(id = 2, date = "2026-03-01", grade = "V5", sent = false))
        repo.insertClimb(ClimbLog(id = 3, date = "2026-03-02", grade = "V4", sent = true))
        repo.insertClimb(ClimbLog(id = 4, date = "2026-02-28", grade = "V6", sent = true))

        val sends = repo.getSendsForDateRange("2026-03-01", "2026-03-07")
        assertEquals(2, sends.size)
        assertTrue(sends.all { it.sent })
    }

    @Test
    fun deleteClimb_removesEntry() {
        repo.insertClimb(ClimbLog(id = 1, date = "2026-03-01", grade = "V3", sent = true))
        assertNotNull(repo.getClimbById(1))

        repo.deleteClimb(1)
        assertNull(repo.getClimbById(1))
    }

    @Test
    fun insertClimb_flash_impliesSent() {
        val log = ClimbLog(id = 1, date = "2026-03-01", grade = "V4", sent = true, flash = true, attempts = 1)
        repo.insertClimb(log)

        val retrieved = repo.getClimbById(1)
        assertNotNull(retrieved)
        assertTrue(retrieved.sent)
        assertTrue(retrieved.flash)
        assertEquals(1, retrieved.attempts)
    }

    @Test
    fun insertClimb_withMultipleHoldTypes() {
        val log = ClimbLog(
            id = 1, date = "2026-03-01", grade = "V6",
            holdTypes = listOf("CRIMP", "PINCH", "SLOPER", "UNDERCLING"),
            sent = true
        )
        repo.insertClimb(log)

        val retrieved = repo.getClimbById(1)
        assertNotNull(retrieved)
        assertEquals(4, retrieved.holdTypes.size)
        assertTrue(retrieved.holdTypes.contains("PINCH"))
    }

    @Test
    fun insertClimb_boardTypeOptional() {
        val gym = ClimbLog(id = 1, date = "2026-03-01", grade = "V3", boardType = null, sent = true)
        val kilter = ClimbLog(id = 2, date = "2026-03-01", grade = "V4", boardType = "KILTER", sent = true)

        repo.insertClimb(gym)
        repo.insertClimb(kilter)

        assertNull(repo.getClimbById(1)?.boardType)
        assertEquals("KILTER", repo.getClimbById(2)?.boardType)
    }

    @Test
    fun multipleClimbs_sameSession() {
        repeat(5) { i ->
            repo.insertClimb(
                ClimbLog(
                    id = (i + 1).toLong(),
                    workoutLogId = 1,
                    date = "2026-03-01",
                    grade = "V${i + 2}",
                    sent = i % 2 == 0
                )
            )
        }

        val session = repo.getClimbsForWorkout(1)
        assertEquals(5, session.size)
        assertEquals(3, session.count { it.sent })
    }
}
