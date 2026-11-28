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
        assertEquals(
            ClimbLogCounts(triesAtAngle = 0, topsAtAngle = 0, tries = 0, tops = 0),
            climbLogCounts(emptyList(), angle = 40),
        )
    }

    @Test
    fun `an attempt row counts each of its tries`() {
        assertEquals(
            ClimbLogCounts(triesAtAngle = 4, topsAtAngle = 0, tries = 4, tops = 0),
            climbLogCounts(listOf(row("a", isSend = false, bidCount = 4)), angle = 40),
        )
    }

    @Test
    fun `the try that topped is the top and not also a try`() {
        // Three taps on "Try", then "Top": one send with four tries to the top.
        assertEquals(
            ClimbLogCounts(triesAtAngle = 3, topsAtAngle = 1, tries = 3, tops = 1),
            climbLogCounts(listOf(row("a", isSend = true, bidCount = 4)), angle = 40),
        )
        assertEquals(
            ClimbLogCounts(triesAtAngle = 0, topsAtAngle = 1, tries = 0, tops = 1),
            climbLogCounts(listOf(row("flash", isSend = true, bidCount = 1)), angle = 40),
        )
    }

    @Test
    fun `the angle on screen counts its own rows, the total every day and angle`() {
        val history = listOf(
            row("a", isSend = false, bidCount = 2, angle = 40),
            row("b", isSend = true, bidCount = 3, angle = 40),
            row("c", isSend = true, bidCount = 1, angle = 25),
            row("d", isSend = false, bidCount = 1, angle = 25),
        )
        assertEquals(
            ClimbLogCounts(triesAtAngle = 4, topsAtAngle = 1, tries = 5, tops = 2),
            climbLogCounts(history, angle = 40),
        )
        assertEquals(
            ClimbLogCounts(triesAtAngle = 1, topsAtAngle = 1, tries = 5, tops = 2),
            climbLogCounts(history, angle = 25),
        )
    }

    @Test
    fun `an angle without a log counts nothing there and keeps the total`() {
        // A top at 40° is no top at 25°.
        assertEquals(
            ClimbLogCounts(triesAtAngle = 0, topsAtAngle = 0, tries = 2, tops = 1),
            climbLogCounts(listOf(row("a", isSend = true, bidCount = 3, angle = 40)), angle = 25),
        )
    }

    @Test
    fun `a row without a count is one try, a send without one is a top`() {
        assertEquals(
            ClimbLogCounts(triesAtAngle = 1, topsAtAngle = 1, tries = 1, tops = 1),
            climbLogCounts(listOf(row("a", isSend = false, bidCount = 0), row("b", isSend = true, bidCount = 0)), angle = 40),
        )
    }
}
