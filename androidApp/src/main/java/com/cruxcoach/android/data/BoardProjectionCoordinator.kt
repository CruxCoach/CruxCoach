package com.cruxcoach.android.data

import android.util.Log

/**
 * Reconciles writes whose climb identity is unavailable to CruxCoach.
 *
 * Relay does not own the queue. It only reports that the physical projection
 * changed, so an independently running host queue does not claim its previous
 * item is still on the wall.
 */
class BoardProjectionCoordinator(
    private val sessionQueueManager: SessionQueueManager,
    private val boardStateManager: BoardStateManager,
) {
    suspend fun onExternalBoardWrite() {
        if (sessionQueueManager.state.value.role == SessionRole.HOST) {
            sessionQueueManager.markExternalBoardWrite()
        }
        runCatching { boardStateManager.clearLastClimb() }
            .onFailure { Log.w(TAG, "failed to clear persisted climb after external write", it) }
    }

    private companion object {
        const val TAG = "BoardProjection"
    }
}
