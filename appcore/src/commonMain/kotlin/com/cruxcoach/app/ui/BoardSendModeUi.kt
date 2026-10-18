package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.BoardControllerProfiles
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.SessionRole
import com.cruxcoach.app.detail.ClimbDetailStatus
import com.cruxcoach.app.detail.ClimbDetailUiState
import com.cruxcoach.app.send.BoardAutoSend
import com.cruxcoach.app.settings.BoardSendMode
import com.cruxcoach.app.settings.SettingsSnapshot
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The climb detail's automatic send (Android `BoardClimbDetailViewModel.sendAutomaticallyIfEnabled`).
 *
 * Swift asks after the shown page settled (open, swipe, angle, mirror) and when a board
 * connected; a true answer goes through the same guarded send as the lamp, so the board
 * match check and the sender's fences apply unchanged. Only the page on screen is ever
 * asked about: preloaded neighbours never reach the presenter's state.
 */
class DetailAutoSendModel internal constructor(
    private val shown: () -> ClimbDetailUiState?,
    private val settings: () -> SettingsSnapshot,
    private val connection: () -> BoardConnectionUiState,
    /** The shared-session participant phase: idle | connecting | joined | hostLost. */
    private val sharedSessionPhase: () -> String,
    private val playlistRunning: () -> Boolean,
) {
    /** True when the climb [climbUuid], which the detail shows, goes on the wall now without a tap. */
    fun shouldSendNow(climbUuid: String): Boolean = try {
        decide(climbUuid)
    } catch (e: Exception) {
        // A failed read is not a request to light anything.
        false
    }

    internal fun decide(climbUuid: String): Boolean {
        val state = shown() ?: return false
        val data = state.data ?: return false
        if (state.status != ClimbDetailStatus.READY) return false
        if (climbUuid.isEmpty() || !state.uuid.equals(climbUuid, ignoreCase = true)) return false
        val link = connection()
        val snapshot = settings()
        val phase = sharedSessionPhase()
        val mode = BoardAutoSend.resolvedMode(
            single = snapshot.singleConnectionSendMode,
            multi = snapshot.multiConnectionSendMode,
            // Android reads the profile of the board on the link; none counts as one connection.
            capacity = BoardControllerProfiles.forBoard(link.connectedBoard).connectionCapacity,
            // iOS hosts no relay for other phones.
            hostingForOthers = false,
        )
        return BoardAutoSend.detailSendsAutomatically(
            mode = mode,
            climbBrand = BoardBrand.fromWireOrNull(data.climb.boardBrand),
            sessionRole = if (phase == "joined" || phase == "hostLost") SessionRole.PARTICIPANT else SessionRole.NONE,
            sessionConnecting = phase == "connecting",
            playlistRunning = playlistRunning(),
            boardConnected = link.connection == ConnectionState.CONNECTED,
            holdCount = state.holds.size,
            frames = data.climb.frames,
        )
    }
}

class BoardSendModeSettingsState(
    /** automatic | explicit — boards that take one app connection at a time. */
    val singleConnection: String,
    /** automatic | explicit — boards (and relays) that take several. */
    val multiConnection: String,
)

/**
 * Android's "Send to board" settings rows (`BoardSendModeSection`): one choice for
 * single-connection controllers (default automatic), one for multi-connection ones
 * (default tap to send), on Android's preference keys.
 */
class BoardSendModeSettingsModel internal constructor(
    private val store: SettingsStore,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val currentState: BoardSendModeSettingsState get() = map(store.snapshot)

    fun watch(onState: (BoardSendModeSettingsState) -> Unit): Subscription {
        val job = scope.launch { store.state.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    fun setSingleConnection(code: String) {
        parse(code)?.let { store.setSingleConnectionSendMode(it) }
    }

    fun setMultiConnection(code: String) {
        parse(code)?.let { store.setMultiConnectionSendMode(it) }
    }

    fun close() = scope.cancel()

    private fun parse(code: String): BoardSendMode? = when (code) {
        AUTOMATIC -> BoardSendMode.AUTOMATIC
        EXPLICIT -> BoardSendMode.EXPLICIT
        else -> null
    }

    private fun map(snapshot: SettingsSnapshot) = BoardSendModeSettingsState(
        singleConnection = code(snapshot.singleConnectionSendMode),
        multiConnection = code(snapshot.multiConnectionSendMode),
    )

    private fun code(mode: BoardSendMode): String = if (mode == BoardSendMode.AUTOMATIC) AUTOMATIC else EXPLICIT

    private companion object {
        const val AUTOMATIC = "automatic"
        const val EXPLICIT = "explicit"
    }
}
