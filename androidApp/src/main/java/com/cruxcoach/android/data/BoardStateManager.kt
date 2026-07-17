package com.cruxcoach.android.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "what climb is currently on the physical board".
 *
 * The board state changes via [setLastClimb] (new climb overwrites old)
 * or [restore] (app start). Stale climbs (older than [STALE_THRESHOLD_MS])
 * are automatically suppressed — the chip disappears after 30 minutes of
 * inactivity so users don't see a days-old climb in the BLE status area.
 */
@Singleton
class BoardStateManager @Inject constructor(
    private val userPreferences: UserPreferences,
    private val climbNameResolver: ClimbNameResolver
) {
    data class LastBoardClimb(
        val uuid: String,
        val angle: Int,
        val name: String?,
        val timestamp: Long = System.currentTimeMillis(),
        /** Whether the physical controller retains this projection without GATT. */
        val projectionSurvivesDisconnect: Boolean = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var staleJob: Job? = null

    private val _lastClimb = MutableStateFlow<LastBoardClimb?>(null)
    /** The last climb, or null if it's older than [STALE_THRESHOLD_MS]. */
    val lastClimb: StateFlow<LastBoardClimb?> = _lastClimb.asStateFlow()

    /**
     * Sets the last board climb with name resolution from DB.
     * Deduplicates: if uuid + angle match current value, no update.
     * Persists to DataStore for app-restart survival.
     */
    suspend fun setLastClimb(
        uuid: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ) {
        val current = _lastClimb.value
        if (current != null && current.uuid == uuid && current.angle == angle &&
            current.name != null &&
            current.projectionSurvivesDisconnect == projectionSurvivesDisconnect
        ) {
            Log.d(TAG, "SKIP dedup uuid=${uuid.take(8)} angle=$angle (unchanged)")
            return
        }

        val name = withContext(Dispatchers.IO) {
            climbNameResolver.resolveName(uuid, angle)
        }

        _lastClimb.update {
            LastBoardClimb(
                uuid = uuid,
                angle = angle,
                name = name,
                projectionSurvivesDisconnect = projectionSurvivesDisconnect,
            )
        }
        userPreferences.setLastClimb(uuid, angle, projectionSurvivesDisconnect)
        scheduleStaleCleanup()
        Log.d(TAG, "SET uuid=${uuid.take(8)} angle=$angle name=${name ?: "unknown"}")
    }

    /**
     * Restores last climb from DataStore on app start.
     * Called once from BleShareManager.init.
     * Skips restore if the persisted climb is older than [STALE_THRESHOLD_MS].
     */
    suspend fun restore() {
        val uuid = userPreferences.lastClimbUuid.first()
        if (uuid != null) {
            val angle = userPreferences.lastClimbAngle.first()
            val persistedAt = userPreferences.lastClimbTimestamp.first()
            val projectionSurvivesDisconnect =
                userPreferences.lastClimbProjectionSurvivesDisconnect.first()
            val age = System.currentTimeMillis() - persistedAt
            if (persistedAt > 0 && age > STALE_THRESHOLD_MS) {
                Log.d(TAG, "RESTORE skipped — stale (${age / 60_000}min old)")
                return
            }
            Log.d(TAG, "RESTORE uuid=${uuid.take(8)} angle=$angle")
            setLastClimb(uuid, angle, projectionSurvivesDisconnect)
        }
    }

    /**
     * Synchronously updates the StateFlow (no DB name resolution, no DataStore persist).
     * Call before endQueue() to prevent stale data flashing in the combine flow.
     * Always follow up with the full [setLastClimb] for persistence + name resolution.
     */
    fun setLastClimbQuick(
        uuid: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ) {
        val current = _lastClimb.value
        if (current != null && current.uuid == uuid && current.angle == angle &&
            current.projectionSurvivesDisconnect == projectionSurvivesDisconnect
        ) return
        // Keep existing name if same UUID (just angle changed), otherwise null
        val existingName = current?.name?.takeIf { current.uuid == uuid }
        _lastClimb.value = LastBoardClimb(
            uuid = uuid,
            angle = angle,
            name = existingName,
            projectionSurvivesDisconnect = projectionSurvivesDisconnect,
        )
        scheduleStaleCleanup()
        Log.d(TAG, "QUICK uuid=${uuid.take(8)} angle=$angle name=${existingName ?: "pending"}")
    }

    /**
     * Schedules a coroutine that clears [_lastClimb] after [STALE_THRESHOLD_MS].
     * Resets on every new climb so only truly idle boards get cleared.
     */
    private fun scheduleStaleCleanup() {
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(STALE_THRESHOLD_MS)
            Log.d(TAG, "STALE — clearing last climb after ${STALE_THRESHOLD_MS / 60_000}min")
            _lastClimb.value = null
        }
    }

    companion object {
        private const val TAG = "CruxBLE/BoardState"
        /** 30 minutes in milliseconds. */
        private const val STALE_THRESHOLD_MS = 30 * 60_000L
    }
}
