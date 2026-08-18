package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.domain.relay.CompleteClimb
import com.cruxcoach.android.boardcell.BoardProjection

/**
 * Reconciles writes whose climb identity is unavailable to CruxCoach.
 *
 * Relay does not own the queue. It only reports that the physical projection
 * changed, so an independently running host queue does not claim its previous
 * item is still on the wall.
 *
 * The identity itself is recovered where it can be: an official-app write
 * carries LED data only, but that is enough to find the climb in the catalogue
 * ([RelayClimbIdentifier]) — the "on the board" banner then names it like any
 * CruxCoach send instead of going blank. Only a genuinely unidentifiable write
 * clears the state.
 */
class BoardProjectionCoordinator(
    private val sessionQueueManager: SessionQueueManager,
    private val boardStateManager: BoardStateManager,
    private val climbIdentifier: RelayClimbIdentifier? = null,
) {
    /** Called when sharing starts, so the first relayed climb does not pay for
     *  the identification lookup's one-time setup. */
    suspend fun prepareForExternalWrites() {
        climbIdentifier?.warmUp()
    }

    suspend fun identifyExternal(climb: CompleteClimb): BoardProjection? =
        climbIdentifier?.identify(climb)?.let { BoardProjection(it.uuid, it.angle) }

    suspend fun onCanonicalExternalBoardWrite(projection: BoardProjection?) {
        if (sessionQueueManager.state.value.role == SessionRole.HOST) {
            sessionQueueManager.markExternalBoardWrite()
        }
        if (projection != null) {
            Log.d(TAG, "external write identified as ${projection.climbUuid.take(8)}")
            runCatching {
                boardStateManager.setLastClimb(projection.climbUuid, projection.angle)
            }.onFailure { Log.w(TAG, "failed to persist the identified external climb", it) }
            return
        }
        runCatching { boardStateManager.clearLastClimb() }
            .onFailure { Log.w(TAG, "failed to clear persisted climb after external write", it) }
    }

    private companion object {
        const val TAG = "BoardProjection"
    }
}
