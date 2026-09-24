package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenQuickAttemptTest {
    private val sessionStart = "2026-09-24T10:00:00"

    private fun entry(
        uuid: String,
        climbedAt: String,
        isSend: Boolean,
        angle: Long = 40,
        bidCount: Long = 1,
        isMirror: Boolean = false,
        comment: String? = null,
    ) = AscentWithClimb(
        uuid = uuid,
        climbUuid = "climb",
        angle = angle,
        isMirror = isMirror,
        bidCount = bidCount,
        quality = null,
        difficulty = null,
        comment = comment,
        climbedAt = climbedAt,
        climbName = "slowly",
        climbFrames = "",
        difficultyAverage = 20.0,
        isSend = isSend,
    )

    @Test
    fun `an attempt from this session is continued by the next log`() {
        val attempt = entry("bid", "2026-09-24T10:05:00", isSend = false, bidCount = 2)

        val open = openQuickAttempt(listOf(attempt), angle = 40, isMirror = false, since = sessionStart)

        assertEquals("bid", open?.uuid)
        assertEquals(2L, open?.bidCount)
    }

    @Test
    fun `a send closes the sequence so the next attempt starts a new row`() {
        val history = listOf(
            entry("bid", "2026-09-24T10:05:00", isSend = false),
            entry("send", "2026-09-24T10:07:00", isSend = true, bidCount = 2),
        )

        assertNull(openQuickAttempt(history, angle = 40, isMirror = false, since = sessionStart))
    }

    @Test
    fun `an attempt from an earlier session is not continued`() {
        val yesterday = entry("old", "2026-09-23T18:00:00", isSend = false)

        assertNull(openQuickAttempt(listOf(yesterday), angle = 40, isMirror = false, since = sessionStart))
    }

    @Test
    fun `attempts at another angle or mirrored do not count`() {
        val history = listOf(
            entry("steeper", "2026-09-24T10:05:00", isSend = false, angle = 50),
            entry("mirrored", "2026-09-24T10:06:00", isSend = false, isMirror = true),
        )

        assertNull(openQuickAttempt(history, angle = 40, isMirror = false, since = sessionStart))
    }

    @Test
    fun `an earlier attempt today is continued when the window is the day`() {
        val morning = entry("bid", "2026-09-24T07:11:02.120", isSend = false)

        val open = openQuickAttempt(listOf(morning), angle = 40, isMirror = false, since = "2026-09-24")

        assertEquals("bid", open?.uuid)
    }

    @Test
    fun `a mirrored attempt is continued only by a mirrored log`() {
        val mirrored = entry("mirror", "2026-09-24T10:06:00", isSend = false, isMirror = true)

        val open = openQuickAttempt(listOf(mirrored), angle = 40, isMirror = true, since = sessionStart)

        assertEquals("mirror", open?.uuid)
    }

    @Test
    fun `an attempt with a comment stays its own entry`() {
        val noted = entry("noted", "2026-09-24T10:05:00", isSend = false, comment = "left heel hook")

        assertNull(openQuickAttempt(listOf(noted), angle = 40, isMirror = false, since = sessionStart))
    }

    @Test
    fun `nothing is continued without a running session`() {
        val attempt = entry("bid", "2026-09-24T10:05:00", isSend = false)

        assertNull(openQuickAttempt(listOf(attempt), angle = 40, isMirror = false, since = null))
    }
}
