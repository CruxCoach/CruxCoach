package com.cruxcoach.android.ui.navigation

import androidx.lifecycle.ViewModel
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.PerfLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Holds app-wide singletons exposed via CompositionLocals (session, sync, BLE).
 * All heavyweight dependencies are [dagger.Lazy] so that creating this ViewModel
 * on the main thread does NOT trigger a DI cascade (BoardDatabase, BLE, etc.).
 * The singletons are resolved lazily on first access — typically when
 * [CompositionLocalProvider] runs, by which time the IO thread has already
 * created them via CruxCoachApp.appScope.
 */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val _sessionManager: dagger.Lazy<BoardSessionManager>,
    private val _syncManager: dagger.Lazy<BoardSyncManager>,
    private val _queueManager: dagger.Lazy<SessionQueueManager>,
    private val _gattBridge: dagger.Lazy<SessionGattBridge>,
    private val _bleShareManager: dagger.Lazy<BleShareManager>,
    private val _playbackCoordinator: dagger.Lazy<PlaylistPlaybackCoordinator>,
    private val _cruxRelayManager: dagger.Lazy<CruxRelayManager>,
    private val _boardCellManager: dagger.Lazy<com.cruxcoach.android.boardcell.BoardCellManager>,
    private val userPreferences: UserPreferences
) : ViewModel() {

    /** Resolved on first access (cached by Dagger's DoubleCheck). */
    val sessionManager: BoardSessionManager get() = _sessionManager.get()
    val syncManager: BoardSyncManager get() = _syncManager.get()
    val queueManager: SessionQueueManager get() = _queueManager.get()
    val gattBridge: SessionGattBridge get() = _gattBridge.get()
    val bleShareManager: BleShareManager get() = _bleShareManager.get()
    val playbackCoordinator: PlaylistPlaybackCoordinator get() = _playbackCoordinator.get()
    val cruxRelayManager: CruxRelayManager get() = _cruxRelayManager.get()

    init {
        PerfLogger.milestone("StartViewModel created (all deps LAZY — no DI cascade)")
        PerfLogger.logMemory("StartVM-init")
    }

    val keepScreenOn: Flow<Boolean> = userPreferences.keepScreenOn

    /**
     * The BoardCell this device is currently a member of, or null.
     *
     * Built lazily and collected off the composition path on purpose — the
     * whole point of this class is that touching it during composition must
     * not drag the BLE and database graph onto the main thread.
     *
     * Membership is the signal, not "a board was seen": creating a board and
     * joining one both arrive here as a transition from null to a cell id,
     * which is exactly the moment the group's list becomes relevant and
     * therefore the moment to show it. Leaving returns to null, so joining
     * again later is a fresh transition rather than a silent no-op.
     */
    val activeBoardCellId: Flow<String?> by lazy {
        val manager = _boardCellManager.get()
        manager.snapshots.map { snapshot ->
            snapshot?.takeIf {
                // FROZEN is a controller-recovery state, not a leave. Emitting
                // null there made the same BoardCell look like a fresh join
                // when it became ACTIVE again and reopened the playlist over
                // whatever screen the user had chosen in the meantime.
                manager.localNodeId() in it.members
            }?.cellId?.value
        }.distinctUntilChanged()
    }

    /**
     * Reads the onboarding-completed flag from DataStore.
     * Prefer the cached SharedPreferences flag on the hot startup path; this
     * suspend call touches disk and should run on Dispatchers.IO.
     */
    suspend fun isOnboardingCompleted(): Boolean = userPreferences.isOnboardingCompleted()
}
