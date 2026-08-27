package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.domain.relay.CompleteClimb
import kotlinx.coroutines.flow.first

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
    private val climbIdentifier: RelayClimbIdentifier,
    private val advertiser: ClimbBleAdvertiser,
    private val userPreferences: UserPreferences,
) {
    /** Called when sharing starts, so the first relayed climb does not pay for
     *  the identification lookup's one-time setup. */
    suspend fun prepareForExternalWrites() {
        climbIdentifier.warmUp()
    }

    suspend fun onExternalBoardWrite(climb: CompleteClimb? = null) {
        if (sessionQueueManager.state.value.role == SessionRole.HOST) {
            sessionQueueManager.markExternalBoardWrite()
        }
        val identified = climb?.let { climbIdentifier.identify(it) }
        if (identified != null) {
            Log.d(TAG, "external write identified as ${identified.uuid.take(8)}")
            val sharingEnabled = runCatching {
                userPreferences.nearbyClimbSharing.first()
            }.getOrDefault(true)
            if (sharingEnabled && sessionQueueManager.state.value.role == SessionRole.HOST) {
                // A first, identity-free marker may already have gone out so
                // nobody claims the old queue item is still projected. Push a
                // second delta now that the relay resolved the real climb.
                sessionQueueManager.markExternalBoardWrite(identified.uuid, identified.angle)
            }
            runCatching {
                boardStateManager.setLastClimb(identified.uuid, identified.angle)
            }.onFailure { Log.w(TAG, "failed to persist the identified external climb", it) }
            val result = advertiser.advertiseClimb(
                climbUuid = identified.uuid,
                angle = identified.angle,
                sharingEnabled = sharingEnabled,
            )
            Log.d(
                TAG,
                "external projection broadcast=$result sharing=$sharingEnabled",
            )
            return
        }
        runCatching { boardStateManager.clearLastClimb() }
            .onFailure { Log.w(TAG, "failed to clear persisted climb after external write", it) }
    }

    private companion object {
        const val TAG = "BoardProjection"
    }
}
