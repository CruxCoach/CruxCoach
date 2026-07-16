package com.cruxcoach.android.data

import android.util.Log
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
class BoardStateManager internal constructor(
    private val userPreferences: UserPreferences,
    private val climbNameResolver: ClimbNameResolver,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        userPreferences: UserPreferences,
        climbNameResolver: ClimbNameResolver,
    ) : this(
        userPreferences,
        climbNameResolver,
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    data class LastBoardClimb(
        val uuid: String,
        val angle: Int,
        val name: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val staleJob = AtomicReference<Job?>(null)
    private val requestSequence = AtomicLong(0L)
    private val commitMutex = Mutex()

    private val _lastClimb = MutableStateFlow<LastBoardClimb?>(null)
    /** The last climb, or null if it's older than [STALE_THRESHOLD_MS]. */
    val lastClimb: StateFlow<LastBoardClimb?> = _lastClimb.asStateFlow()

    /**
     * Sets the last board climb with name resolution from DB.
     * Deduplicates: if uuid + angle match current value, no update.
     * Persists to DataStore for app-restart survival.
     */
    suspend fun setLastClimb(uuid: String, angle: Int) {
        // Allocate the sequence before resolving the name. A later invocation
        // supersedes this one immediately, so a slow older DB lookup cannot
        // overwrite a newer climb when it eventually completes.
        val requestId = requestSequence.incrementAndGet()
        val current = _lastClimb.value
        if (current != null && current.uuid == uuid && current.angle == angle && current.name != null) {
            Log.d(TAG, "SKIP dedup uuid=${uuid.take(8)} angle=$angle (unchanged)")
            return
        }

        val name = withContext(Dispatchers.IO) {
            climbNameResolver.resolveName(uuid, angle)
        }

        commitMutex.withLock {
            if (requestSequence.get() != requestId) {
                Log.d(TAG, "SKIP superseded uuid=${uuid.take(8)} angle=$angle")
                return@withLock
            }
            _lastClimb.update { LastBoardClimb(uuid, angle, name) }
            userPreferences.setLastClimb(uuid, angle)
            scheduleStaleCleanup(requestId)
            Log.d(TAG, "SET uuid=${uuid.take(8)} angle=$angle hasName=${name != null}")
        }
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
            val age = System.currentTimeMillis() - persistedAt
            if (persistedAt > 0 && age > STALE_THRESHOLD_MS) {
                Log.d(TAG, "RESTORE skipped — stale (${age / 60_000}min old)")
                return
            }
            Log.d(TAG, "RESTORE uuid=${uuid.take(8)} angle=$angle")
            setLastClimb(uuid, angle)
        }
    }

    /**
     * Synchronously updates the StateFlow (no DB name resolution, no DataStore persist).
     * Call before endQueue() to prevent stale data flashing in the combine flow.
     * Always follow up with the full [setLastClimb] for persistence + name resolution.
     */
    fun setLastClimbQuick(uuid: String, angle: Int) {
        val requestId = requestSequence.incrementAndGet()
        val current = _lastClimb.value
        if (current != null && current.uuid == uuid && current.angle == angle) return
        // Keep existing name if same UUID (just angle changed), otherwise null
        val existingName = current?.name?.takeIf { current.uuid == uuid }
        _lastClimb.value = LastBoardClimb(uuid, angle, existingName)
        scheduleStaleCleanup(requestId)
        Log.d(TAG, "QUICK uuid=${uuid.take(8)} angle=$angle hasName=${existingName != null}")
    }

    /**
     * Schedules a coroutine that clears [_lastClimb] after [STALE_THRESHOLD_MS].
     * Resets on every new climb so only truly idle boards get cleared.
     */
    private fun scheduleStaleCleanup(requestId: Long) {
        val replacement = scope.launch(start = CoroutineStart.LAZY) {
            delay(STALE_THRESHOLD_MS)
            if (requestSequence.get() == requestId) {
                Log.d(TAG, "STALE — clearing last climb after ${STALE_THRESHOLD_MS / 60_000}min")
                _lastClimb.value = null
            }
        }
        staleJob.getAndSet(replacement)?.cancel()
        replacement.start()
    }

    companion object {
        private const val TAG = "CruxBLE/BoardState"
        /** 30 minutes in milliseconds. */
        private const val STALE_THRESHOLD_MS = 30 * 60_000L
    }
}
