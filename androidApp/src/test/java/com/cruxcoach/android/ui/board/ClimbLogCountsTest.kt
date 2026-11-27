package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Test

class ClimbLogCountsTest {

    private fun row(uuid: String, isSend: Boolean, bidCount: Long, angle: Long = 40) = AscentWithClimb(
        uuid = uuid, climbUuid = "climb", angle = angle, isMirror = false, bidCount = bidCount, quality = null,
        difficulty = null, comment = null, climbedAt = "2026-11-23 10:00:00", climbName = "Climb", climbFrames = "",
        difficultyAverage = null, isSend = isSend,
    )

    @Test
    fun `nothing logged counts nothing`() {
        assertEquals(ClimbLogCounts(tries = 0, tops = 0), climbLogCounts(emptyList()))
    }

    @Test
    fun `an attempt row counts each of its tries`() {
        assertEquals(ClimbLogCounts(tries = 4, tops = 0), climbLogCounts(listOf(row("a", isSend = false, bidCount = 4))))
    }

    @Test
    fun `the try that topped is the top and not also a try`() {
        // Three taps on "Try", then "Top": one send with four tries to the top.
        assertEquals(ClimbLogCounts(tries = 3, tops = 1), climbLogCounts(listOf(row("a", isSend = true, bidCount = 4))))
        assertEquals(ClimbLogCounts(tries = 0, tops = 1), climbLogCounts(listOf(row("flash", isSend = true, bidCount = 1))))
    }

    @Test
    fun `every day and angle of the climb adds up`() {
        val history = listOf(
            row("a", isSend = false, bidCount = 2, angle = 40),
            row("b", isSend = true, bidCount = 3, angle = 40),
            row("c", isSend = true, bidCount = 1, angle = 25),
            row("d", isSend = false, bidCount = 1, angle = 25),
        )
        assertEquals(ClimbLogCounts(tries = 5, tops = 2), climbLogCounts(history))
    }

    @Test
    fun `a row without a count is one try, a send without one is a top`() {
        assertEquals(ClimbLogCounts(tries = 1, tops = 1),
            climbLogCounts(listOf(row("a", isSend = false, bidCount = 0), row("b", isSend = true, bidCount = 0))))
    }
}
