package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveSessionStateTest {
    @Test
    fun `active duration never becomes negative`() {
        val state = ActiveSessionState(
            sessionId = "session-1",
            startedAt = "2026-08-30T10:15:30Z",
            phase = ActiveSessionPhase.RESTING,
            elapsedSeconds = 20,
            pausedSeconds = 25,
            restSecondsRemaining = 120,
            sendCount = 1,
            attemptCount = 3,
        )

        assertEquals(0, state.activeSeconds)
    }
}
