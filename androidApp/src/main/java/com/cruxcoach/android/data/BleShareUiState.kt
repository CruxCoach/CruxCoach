package com.cruxcoach.android.data

import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.ble.QueueItem

/**
 * Unified UI state for all BLE sharing activity — nearby climbs, sessions, board status.
 * Produced by [BleShareManager], consumed by BleStatusArea composable.
 */
data class BleShareUiState(
    /** Current physical projection or the most recent climb available for resend. */
    val onBoardClimb: OnBoardClimbEntry? = null,

    /** Number of users connected to the board without an active climb. */
    val boardOccupiedCount: Int = 0,

    /** Nearby sessions available to join. */
    val nearbySessions: List<NearbySessionEntry> = emptyList(),

    /** Whether sharing is enabled in user preferences. */
    val sharingEnabled: Boolean = false,

    /** Own session state (null when no session active). */
    val ownSession: OwnSessionState? = null,

    /** Disconnect request state. */
    val canRequestDisconnect: Boolean = false,
    val isRequestingDisconnect: Boolean = false,
    val disconnectRequestNoResponse: Boolean = false
) {
    val hasAnything: Boolean
        get() = onBoardClimb != null || boardOccupiedCount > 0 ||
            nearbySessions.isNotEmpty() || ownSession != null

    val collapsedSummary: String
        get() = buildString {
            val climb = onBoardClimb
            if (climb != null) {
                val name = climb.name ?: "Unbekannter Climb"
                append("\"$name\"")
                if (climb.grade != null) append(" ${climb.grade}")
                append(" ${climb.angle}°")
                when (climb.source) {
                    OnBoardSource.REMOTE_ACTIVE -> append(" · klettert gerade")
                    OnBoardSource.REMOTE_LAST, OnBoardSource.LOCAL_MANAGER -> {
                        append(if (climb.isStillProjected) " · noch sichtbar" else " · letzter Boulder")
                    }
                    OnBoardSource.LOCAL_ACTIVE -> append(" · dein Climb")
                    OnBoardSource.SESSION_REMOTE -> append(" · Session-Climb")
                }
            }
            if (boardOccupiedCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("Board besetzt")
            }
            if (nearbySessions.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("${nearbySessions.size} Session${if (nearbySessions.size > 1) "s" else ""}")
            }
        }
}

data class OnBoardClimbEntry(
    val climbUuid: String,
    val angle: Int,
    val name: String?,
    val grade: String? = null,
    val source: OnBoardSource,
    val rssi: Int? = null,
    /** False when only resend metadata remains and the LEDs have gone out. */
    val isStillProjected: Boolean = true,
)

enum class OnBoardSource {
    /** User is connected and sent this climb. */
    LOCAL_ACTIVE,
    /** Another user is connected and climbing. */
    REMOTE_ACTIVE,
    /** Another user's last projection; [OnBoardClimbEntry.isStillProjected] is authoritative. */
    REMOTE_LAST,
    /** Own saved last projection (disconnected, no remote signal). */
    LOCAL_MANAGER,
    /** Climb from a nearby session's current queue item. */
    SESSION_REMOTE
}

data class NearbySessionEntry(
    val sessionId: Int,
    val hostName: String,
    val participantCount: Int,
    val rssi: Int,
    val currentClimbUuid: String?,
    val currentClimbName: String?,
    val currentClimbGrade: String? = null,
    val rawSession: NearbySession
)

/** State of the user's own session (host or participant). */
data class OwnSessionState(
    val isHost: Boolean,
    val participantCount: Int,
    val queue: List<QueueItem>,
    val currentIndex: Int,
    val currentClimbName: String?,
    val currentClimbGrade: String? = null,
    val externalBoardOverride: Boolean = false,
    val isPaused: Boolean,
    val elapsedSeconds: Int
)
