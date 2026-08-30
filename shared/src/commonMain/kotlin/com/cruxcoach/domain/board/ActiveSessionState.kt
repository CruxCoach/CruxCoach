package com.cruxcoach.domain.board

/** Portable phases that can later be rendered by an iOS Live Activity. */
enum class ActiveSessionPhase {
    ACTIVE,
    PAUSED,
    RESTING,
}

enum class BoardConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/** Compact climb identity suitable for an active-session surface. */
data class ActiveSessionClimb(
    val uuid: String,
    val name: String,
    val angle: Long,
    val isMirrored: Boolean,
)

/**
 * Cross-platform snapshot of a currently running board session.
 *
 * This is deliberately presentation-neutral: elapsed values are canonical
 * seconds, timestamps are ISO strings, and no Android notification or SwiftUI
 * types cross the boundary.
 */
data class ActiveSessionState(
    val sessionId: String,
    val startedAt: String,
    val phase: ActiveSessionPhase,
    val elapsedSeconds: Long,
    val pausedSeconds: Long,
    val restSecondsRemaining: Long? = null,
    val sendCount: Long,
    val attemptCount: Long,
    val currentClimb: ActiveSessionClimb? = null,
    val connection: BoardConnectionState = BoardConnectionState.DISCONNECTED,
) {
    val activeSeconds: Long get() = (elapsedSeconds - pausedSeconds).coerceAtLeast(0L)
}
