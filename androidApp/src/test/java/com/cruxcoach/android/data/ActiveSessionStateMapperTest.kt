package com.cruxcoach.android.data

import com.cruxcoach.domain.board.ActiveSessionClimb
import com.cruxcoach.domain.board.ActiveSessionPhase
import com.cruxcoach.domain.board.BoardConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSessionStateMapperTest {
    @Test
    fun `inactive Android state has no active portable snapshot`() {
        assertNull(BoardSessionState().toPortableState(RestTimerState()))
    }

    @Test
    fun `planned rest carries countdown and stable session identity`() {
        val state = BoardSessionState(
            isActive = true,
            isPaused = true,
            pauseReason = PauseReason.PLANNED_REST,
            elapsedSeconds = 90,
            pauseSeconds = 15,
            ascentCount = 2,
            bidCount = 5,
            startedAt = "2026-08-30T10:15:30Z",
        ).toPortableState(
            restTimer = RestTimerState(isRunning = true, secondsRemaining = 120),
            currentClimb = ActiveSessionClimb("climb-1", "Limit Line", 40, false),
            connection = BoardConnectionState.CONNECTED,
        )!!

        assertEquals("2026-08-30T10:15:30Z", state.sessionId)
        assertEquals(ActiveSessionPhase.RESTING, state.phase)
        assertEquals(120, state.restSecondsRemaining)
        assertEquals(75, state.activeSeconds)
        assertEquals(2, state.sendCount)
        assertEquals(5, state.attemptCount)
        assertEquals("climb-1", state.currentClimb?.uuid)
        assertEquals(BoardConnectionState.CONNECTED, state.connection)
    }

    @Test
    fun `manual pause never exposes an unrelated rest countdown`() {
        val state = BoardSessionState(
            isActive = true,
            isPaused = true,
            pauseReason = PauseReason.MANUAL,
            startedAt = "2026-08-30T10:15:30Z",
        ).toPortableState(
            restTimer = RestTimerState(isRunning = true, secondsRemaining = 99),
        )!!

        assertEquals(ActiveSessionPhase.PAUSED, state.phase)
        assertNull(state.restSecondsRemaining)
    }
}
