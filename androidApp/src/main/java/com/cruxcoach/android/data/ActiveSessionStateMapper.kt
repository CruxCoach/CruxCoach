package com.cruxcoach.android.data

import com.cruxcoach.domain.board.ActiveSessionClimb
import com.cruxcoach.domain.board.ActiveSessionPhase
import com.cruxcoach.domain.board.ActiveSessionState
import com.cruxcoach.domain.board.BoardConnectionState

/** Maps Android runtime state into the portable active-session snapshot. */
fun BoardSessionState.toPortableState(
    restTimer: RestTimerState,
    currentClimb: ActiveSessionClimb? = null,
    connection: BoardConnectionState = BoardConnectionState.DISCONNECTED,
): ActiveSessionState? {
    val stableStartedAt = startedAt ?: return null
    if (!isActive) return null

    val phase = when {
        isPaused && pauseReason == PauseReason.PLANNED_REST -> ActiveSessionPhase.RESTING
        isPaused -> ActiveSessionPhase.PAUSED
        else -> ActiveSessionPhase.ACTIVE
    }
    return ActiveSessionState(
        // The current schema has no public UUID. Its immutable ISO start value
        // remains stable through recovery and is suitable as an external key.
        sessionId = stableStartedAt,
        startedAt = stableStartedAt,
        phase = phase,
        elapsedSeconds = elapsedSeconds.toLong(),
        pausedSeconds = pauseSeconds.toLong(),
        restSecondsRemaining = if (phase == ActiveSessionPhase.RESTING) {
            restTimer.secondsRemaining.toLong()
        } else {
            null
        },
        sendCount = ascentCount.toLong(),
        attemptCount = bidCount.toLong(),
        currentClimb = currentClimb,
        connection = connection,
    )
}
