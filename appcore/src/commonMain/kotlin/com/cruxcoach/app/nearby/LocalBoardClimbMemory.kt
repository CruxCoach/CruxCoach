// Port of androidApp/.../data/BoardStateManager.kt (0e1bad0c6) for this phone's own sends, plus the
// part of Android's ClimbBleAdvertiser the board state reads (`hasActiveClimb`): what this phone
// last put on the wall, and whether it is still its live climb on the link it was sent over.
//
// Android also bridges other phones' climbs into the same memory; on iOS those stay the
// scanner's (REMOTE_*), so this memory holds this phone's own sends only.
package com.cruxcoach.app.nearby

import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What this phone last lit (Android `LastBoardClimb`). */
internal data class LocalBoardClimb(
    val uuid: String,
    val angle: Int,
    /** The catalogue name at send time; empty when unknown. */
    val name: String,
    val sentAtMs: Long,
    /** False for a MoonBoard: its LEDs go out with the link (Android `BoardProjectionPolicy`). */
    val projectionSurvivesDisconnect: Boolean,
    /**
     * The board it was sent to while that link is still the one it was sent over (Android's
     * advertiser holding an active climb); empty once the link ended or after a restart.
     */
    val liveOnBoard: String,
)

internal class LocalBoardClimbMemory(
    private val store: KeyValueStore,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<LocalBoardClimb?>(null)
    val state: StateFlow<LocalBoardClimb?> = _state.asStateFlow()
    private var staleJob: Job? = null

    init {
        restore()
    }

    /** A send to [boardIdentifier] was confirmed. */
    fun record(uuid: String, angle: Int, name: String, projectionSurvivesDisconnect: Boolean, boardIdentifier: String) {
        if (uuid.isBlank()) return
        val at = nowMs()
        _state.value = LocalBoardClimb(uuid, angle, name, at, projectionSurvivesDisconnect, boardIdentifier)
        try {
            store.putString(KEY_UUID, uuid)
            store.putString(KEY_ANGLE, angle.toString())
            store.putString(KEY_NAME, name)
            store.putString(KEY_AT, at.toString())
            store.putString(KEY_SURVIVES, projectionSurvivesDisconnect.toString())
        } catch (e: Exception) {
            // Persistence is for a restart only; the live value stands.
        }
        scheduleStale(STALE_MS)
    }

    /** The link it was sent over is gone (Android's advertiser stops): the climb is no longer live. */
    fun linkEnded() {
        val current = _state.value ?: return
        if (current.liveOnBoard.isNotEmpty()) _state.value = current.copy(liveOnBoard = "")
    }

    /** Android `restore`: a persisted climb older than [STALE_MS] is not brought back, nor re-aged. */
    private fun restore() {
        val restored = try {
            val uuid = store.getString(KEY_UUID)?.takeIf { it.isNotBlank() } ?: return
            val at = store.getString(KEY_AT)?.toLongOrNull() ?: return
            LocalBoardClimb(
                uuid = uuid,
                angle = store.getString(KEY_ANGLE)?.toIntOrNull() ?: return,
                name = store.getString(KEY_NAME) ?: "",
                sentAtMs = at,
                projectionSurvivesDisconnect = store.getString(KEY_SURVIVES) != "false",
                liveOnBoard = "",
            )
        } catch (e: Exception) {
            return
        }
        val age = nowMs() - restored.sentAtMs
        if (age < 0 || age >= STALE_MS) return
        _state.value = restored
        scheduleStale(STALE_MS - age)
    }

    /** Android: an idle wall stops being reported after 30 minutes. */
    private fun scheduleStale(delayMs: Long) {
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(delayMs)
            _state.value = null
        }
    }

    companion object {
        /** Android `BoardStateManager.STALE_THRESHOLD_MS`. */
        const val STALE_MS = 30 * 60_000L
        private const val KEY_UUID = "ios_board_last_climb_uuid"
        private const val KEY_ANGLE = "ios_board_last_climb_angle"
        private const val KEY_NAME = "ios_board_last_climb_name"
        private const val KEY_AT = "ios_board_last_climb_at"
        private const val KEY_SURVIVES = "ios_board_last_climb_survives"
    }
}
