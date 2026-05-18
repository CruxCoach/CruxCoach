package com.cruxcoach.android.ui.board

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles BLE send/clear operations and nearby climb advertising.
 *
 * Plain Kotlin class (not a ViewModel). Receives a [CoroutineScope] from the
 * parent ViewModel for launching async work.
 */
internal class BoardSendController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClimbDetailState>,
    private val boardRepository: BoardRepository,
    private val bleConnection: BoardBleConnection,
    private val userPreferences: UserPreferences,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val isSharingEnabled: () -> Boolean
) {

    private var sendJob: Job? = null

    /** Cancel any in-flight send (used when switching climbs). */
    fun cancelSend() {
        sendJob?.cancel()
    }

    fun sendToBoard() {
        // When a session queue is active, the queue controls what's on the board.
        // Individual climb sends from detail views are suppressed.
        if (sessionQueueManager.state.value.isActive) {
            Log.d(TAG, "sendToBoard: suppressed (session queue active)")
            return
        }
        val s = state.value
        if (s.holds.isEmpty() || s.ble.connectionState != ConnectionState.CONNECTED) {
            state.update { it.copy(nearby = it.nearby.copy(
                debugInfo = "skip: holds=${s.holds.size} conn=${s.ble.connectionState}"
            )) }
            return
        }
        if (s.ble.isSending) {
            state.update { it.copy(nearby = it.nearby.copy(debugInfo = "skip: already sending")) }
            return
        }

        state.update { it.copy(
            ble = BoardSendState(connectionState = it.ble.connectionState, isSending = true),
            nearby = it.nearby.copy(debugInfo = "sending...")
        ) }
        sendJob = scope.launch {
            try {
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = "loading LED map...")) }
                val productSizeId = userPreferences.boardProductSizeId.first()
                val placementToLed = withContext(Dispatchers.IO) {
                    boardRepository.getPlacementLedMap(productSizeId)
                }
                if (placementToLed.isEmpty()) {
                    state.update { it.copy(
                        ble = it.ble.copy(isSending = false, error = "Keine LED-Daten. Bitte Board-Daten neu synchronisieren."),
                        nearby = it.nearby.copy(debugInfo = "no LED data")
                    ) }
                    return@launch
                }
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = "BLE sending...")) }
                val colors = userPreferences.ledHoldColors.first()
                val success = bleConnection.sendClimb(s.holds, placementToLed, colors.toRoleColorMap())
                state.update { it.copy(
                    ble = it.ble.copy(isSending = false, success = success, error = if (!success) "Senden fehlgeschlagen" else null),
                    nearby = it.nearby.copy(debugInfo = "sent ok=$success")
                ) }
                // Advertise climb to nearby devices if sharing is enabled
                val sharingEnabled = isSharingEnabled()
                val climb = state.value.climb
                val debugMsg = when {
                    !success -> "send failed"
                    climb == null -> "climb null"
                    else -> {
                        val result = climbAdvertiser.advertiseClimb(climb.uuid, state.value.angle, sharingEnabled)
                        "adv: $result"
                    }
                }
                state.update { it.copy(nearby = it.nearby.copy(debugInfo = debugMsg)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sendToBoard failed", e)
                state.update { it.copy(
                    ble = it.ble.copy(isSending = false, error = e.message ?: "Fehler beim Senden"),
                    nearby = it.nearby.copy(debugInfo = "exception: ${e.message?.take(50)}")
                ) }
            }
        }
    }

    /** Re-advertise the current climb to nearby devices (called on every climb load/switch). */
    fun updateNearbyAdvertising(uuid: String, angle: Int) {
        if (!isSharingEnabled()) return
        // Only advertise when the board is connected -- browsing without connection should not share
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return
        scope.launch {
            climbAdvertiser.advertiseClimb(uuid, angle)
        }
    }

    /** Whether the BLE board is currently connected. */
    fun isConnected(): Boolean =
        bleConnection.connectionState.value == ConnectionState.CONNECTED

    private companion object {
        const val TAG = "BoardSendController"
    }
}
