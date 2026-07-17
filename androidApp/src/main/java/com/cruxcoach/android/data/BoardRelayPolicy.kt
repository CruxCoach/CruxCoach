package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

internal enum class BoardRelayAvailability {
    AVAILABLE,
    NO_BOARD,
    UNSUPPORTED_PROTOCOL,
    SESSION_PARTICIPANT,
}

internal data class BoardRelayStopPlan(
    val stopHostSession: Boolean,
    val releaseBoardDirectly: Boolean,
)

/** Capability and ownership rules shared by the relay manager and its UI. */
internal object BoardRelayPolicy {
    fun availability(
        boardBrand: BoardBrand?,
        sessionRole: SessionRole,
    ): BoardRelayAvailability = when {
        boardBrand == null -> BoardRelayAvailability.NO_BOARD
        !boardBrand.usesAuroraProtocol -> BoardRelayAvailability.UNSUPPORTED_PROTOCOL
        sessionRole == SessionRole.PARTICIPANT ->
            BoardRelayAvailability.SESSION_PARTICIPANT
        else -> BoardRelayAvailability.AVAILABLE
    }

    /**
     * An unused helper session belongs to the same one-tap relay action and
     * ends with it. Once CruxCoach guests have joined, a relay-only stop keeps
     * their queue alive. An explicit session-level stop still ends both.
     */
    fun stopPlan(
        relayStartedSession: Boolean,
        sessionRole: SessionRole,
        releaseBoardRequested: Boolean,
        endHostSessionRequested: Boolean = false,
        hasCruxCoachGuests: Boolean = false,
    ): BoardRelayStopPlan {
        val stopRelayHelperSession = relayStartedSession &&
            (!hasCruxCoachGuests || !releaseBoardRequested)
        val stopHostSession = sessionRole == SessionRole.HOST &&
            (stopRelayHelperSession || endHostSessionRequested)
        val independentSessionContinues =
            sessionRole != SessionRole.NONE && !stopHostSession
        return BoardRelayStopPlan(
            stopHostSession = stopHostSession,
            releaseBoardDirectly = releaseBoardRequested &&
                !stopHostSession &&
                !independentSessionContinues,
        )
    }
}
