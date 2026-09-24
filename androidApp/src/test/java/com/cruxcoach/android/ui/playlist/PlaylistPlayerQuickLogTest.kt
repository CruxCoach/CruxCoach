package com.cruxcoach.android.ui.playlist

import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPlayerQuickLogTest {
    private val sessionStart = "2026-09-24T10:00:00"

    private fun entry(
        uuid: String,
        climbedAt: String,
        isSend: Boolean,
        angle: Long = 40,
        bidCount: Long = 1,
        isMirror: Boolean = false,
    ) = AscentWithClimb(
        uuid = uuid,
        climbUuid = "climb",
        angle = angle,
        isMirror = isMirror,
        bidCount = bidCount,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = climbedAt,
        climbName = "slowly",
        climbFrames = "",
        difficultyAverage = 20.0,
        isSend = isSend,
    )

    @Test
    fun `an attempt from this session is continued by the next log`() {
        val attempt = entry("bid", "2026-09-24T10:05:00", isSend = false, bidCount = 2)

        val open = openPlayerAttempt(listOf(attempt), angle = 40, sessionStartedAt = sessionStart)

        assertEquals("bid", open?.uuid)
        assertEquals(2L, open?.bidCount)
    }

    @Test
    fun `a send closes the sequence so the next attempt starts a new row`() {
        val history = listOf(
            entry("bid", "2026-09-24T10:05:00", isSend = false),
            entry("send", "2026-09-24T10:07:00", isSend = true, bidCount = 2),
        )

        assertNull(openPlayerAttempt(history, angle = 40, sessionStartedAt = sessionStart))
    }

    @Test
    fun `an attempt from an earlier session is not continued`() {
        val yesterday = entry("old", "2026-09-23T18:00:00", isSend = false)

        assertNull(openPlayerAttempt(listOf(yesterday), angle = 40, sessionStartedAt = sessionStart))
    }

    @Test
    fun `attempts at another angle or mirrored do not count`() {
        val history = listOf(
            entry("steeper", "2026-09-24T10:05:00", isSend = false, angle = 50),
            entry("mirrored", "2026-09-24T10:06:00", isSend = false, isMirror = true),
        )

        assertNull(openPlayerAttempt(history, angle = 40, sessionStartedAt = sessionStart))
    }

    @Test
    fun `nothing is continued without a running session`() {
        val attempt = entry("bid", "2026-09-24T10:05:00", isSend = false)

        assertNull(openPlayerAttempt(listOf(attempt), angle = 40, sessionStartedAt = null))
    }
}
