package com.cruxcoach.android.ui.navigation

import androidx.lifecycle.ViewModel
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.PerfLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
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
    private val userPreferences: UserPreferences
) : ViewModel() {

    /** Resolved on first access (cached by Dagger's DoubleCheck). */
    val sessionManager: BoardSessionManager get() = _sessionManager.get()
    val syncManager: BoardSyncManager get() = _syncManager.get()
    val queueManager: SessionQueueManager get() = _queueManager.get()
    val gattBridge: SessionGattBridge get() = _gattBridge.get()
    val bleShareManager: BleShareManager get() = _bleShareManager.get()

    init {
        PerfLogger.milestone("StartViewModel created (all deps LAZY — no DI cascade)")
        PerfLogger.logMemory("StartVM-init")
    }

    val keepScreenOn: Flow<Boolean> = userPreferences.keepScreenOn

    /**
     * Reads the onboarding-completed flag from DataStore.
     * Prefer the cached SharedPreferences flag on the hot startup path; this
     * suspend call touches disk and should run on Dispatchers.IO.
     */
    suspend fun isOnboardingCompleted(): Boolean = userPreferences.isOnboardingCompleted()
}
