package com.cruxcoach.android.fakes

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.IntensityZoneEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal fake for BoardBleConnection.
 * Exposes StateFlows with default disconnected values.
 */
class FakeBleConnection {
    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectedBoardName = MutableStateFlow<String?>(null)

    fun isConnected(): Boolean = connectionState.value == ConnectionState.CONNECTED
    suspend fun sendRawChunks(chunks: List<ByteArray>): Boolean = false
    suspend fun clearBoard(): Boolean = false
}

/**
 * Minimal fake for BoardBleScanner.
 */
class FakeBleScanner {
    val discoveredBoards = MutableStateFlow<List<DiscoveredBoard>>(emptyList())
    val isScanning = MutableStateFlow(false)
    val bluetoothEnabled = MutableStateFlow(true)

    fun startScan() { isScanning.value = true }
    fun stopScan() { isScanning.value = false }
}

/**
 * Minimal fake for ClimbBleAdvertiser.
 */
class FakeClimbAdvertiser {
    val isAdvertising = MutableStateFlow(false)

    fun advertiseClimb(
        climbUuid: String,
        angle: Int,
        sharingEnabled: Boolean = true,
        projectionSurvivesDisconnect: Boolean = true,
    ): String = "ok"
    fun advertiseLastClimb(
        climbUuid: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ) {}
    fun clearClimb() {}
    fun onBoardDisconnected() {}
    fun advertiseDisconnectRequest() {}
    fun stopAdvertising() { isAdvertising.value = false }
}

/**
 * Minimal fake for NearbyClimbScanner.
 */
class FakeNearbyClimbScanner {
    val nearbyClimbs = MutableStateFlow<List<NearbyClimb>>(emptyList())
    val isScanning = MutableStateFlow(false)
    private val _disconnectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnectRequests: SharedFlow<Unit> = _disconnectRequests.asSharedFlow()

    fun startScan() { isScanning.value = true }
    fun stopScan() { isScanning.value = false }
}

/**
 * Minimal fake for BoardSessionManager.
 */
class FakeSessionManager {
    val state = MutableStateFlow(BoardSessionState())
    val restTimer = MutableStateFlow(RestTimerState())

    fun startSession() {
        state.value = state.value.copy(isActive = true)
    }

    fun endSession(): Board_sessions? {
        state.value = BoardSessionState()
        return null
    }

    fun pauseSession() {
        state.value = state.value.copy(isPaused = true)
    }

    fun resumeSession() {
        state.value = state.value.copy(isPaused = false)
    }

    fun cancelRestTimer() {
        restTimer.value = RestTimerState()
    }

    fun dismissRestTimerFinished() {
        restTimer.value = restTimer.value.copy(isFinished = false)
    }
}

/**
 * Minimal fake for IntensityZoneManager.
 */
class FakeZoneManager {
    val zones = MutableStateFlow(IntensityZoneEngine.computeFallbackZones(null))

    suspend fun recompute(userId: Long = 1L) {
        // No-op in tests
    }
}

/**
 * Minimal fake for BoardSyncManager.
 */
class FakeSyncManager {
    val state = MutableStateFlow(BoardSyncState())
}
