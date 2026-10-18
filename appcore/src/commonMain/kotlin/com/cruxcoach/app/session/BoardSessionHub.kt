package com.cruxcoach.app.session

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import com.cruxcoach.app.playlist.PlaylistPlayerState
import com.cruxcoach.app.playlist.PlaybackPhase
import com.cruxcoach.app.ui.PlayerScreenModel
import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android `LocalPlaylistBrowserCard`: the banner every board screen shows while
 * a playlist runs — current climb, position, previous / light / next, and a tap
 * that reopens the player.
 */
class SessionBannerState(
    val isActive: Boolean,
    val listName: String,
    /** "" while the climb's name is unknown: Swift shows `playlist_player_title`, as Android does. */
    val currentClimbName: String,
    val currentClimbUuid: String,
    val currentAngle: Int,
    /** 1-based position in the queue; 0 = no position to show (Android hides "n/m" then). */
    val position: Int,
    val total: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    /** A climb is current and a board is connected (Android `canLight`). */
    val canLight: Boolean,
    val isResting: Boolean,
    val restSecondsRemaining: Int,
)

/**
 * The one running board session of the app, as Android's single
 * `PlaylistPlaybackCoordinator`: the playlist keeps playing when the player
 * screen is left ("Back = leave the SCREEN; the playlist keeps running"), every
 * board screen shows its banner, and the player view reopens this same model
 * instead of building a new one. The session clock lives in the app-wide
 * `BoardSessionRecorder` the presenter was built with.
 *
 * [player] is never closed: the hub lives as long as the core.
 */
class BoardSessionHub(
    private val presenter: PlaylistPlayerPresenter,
    val player: PlayerScreenModel,
    private val boardConnected: Flow<Boolean>,
    private val isBoardConnected: () -> Boolean,
    private val scope: CoroutineScope,
) {
    /** The list the running playback was started from (Android `playingSource`). */
    private var playingSource: String? = null

    val bannerState: SessionBannerState get() = map(presenter.state.value, isBoardConnected())

    /** True while a playlist runs. */
    val isActive: Boolean get() = presenter.state.value.isActive

    /** A running playlist owns the wall: the detail's route playback stays on screen then. */
    internal val activeFlow: Flow<Boolean> get() = presenter.state.map { it.isActive }.distinctUntilChanged()

    fun watchBanner(onState: (SessionBannerState) -> Unit): Subscription {
        val job = scope.launch {
            combine(presenter.state, boardConnected) { state, connected -> map(state, connected) }
                .collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    /**
     * Android `PlaylistPlaybackCoordinator.play`: starting what is already
     * playing resumes it — reloading put the player back on its first problem
     * while the session clock ran on. Another list replaces the queue; the
     * session clock carries on, as on Android. Returns true when playback was
     * (re)started. An empty list starts nothing: that is how the banner opens
     * the running player.
     */
    fun play(
        listName: String,
        climbUuids: List<String>,
        angles: List<Int>,
        restsAfter: List<Int>,
        orderCode: String,
        advanceCode: String,
        defaultRestSeconds: Int,
    ): Boolean {
        if (climbUuids.isEmpty()) return false
        if (climbUuids.size != angles.size || climbUuids.size != restsAfter.size) return false
        val source = sourceKey(listName, climbUuids, angles)
        if (playingSource == source && presenter.state.value.isActive) return false
        player.play(listName, climbUuids, angles, restsAfter, orderCode, advanceCode, defaultRestSeconds)
        playingSource = if (presenter.state.value.isActive) source else null
        return presenter.state.value.isActive
    }

    /** Whether the list [listName] with these climbs is the one playing right now. */
    fun isPlaying(listName: String, climbUuids: List<String>, angles: List<Int>): Boolean =
        presenter.state.value.isActive && playingSource == sourceKey(listName, climbUuids, angles)

    fun previous() = player.previous()
    fun next() = player.next()

    /** The banner's lamp: put the current climb back on the wall. */
    fun light() = player.resendCurrentClimb()

    private fun sourceKey(listName: String, climbUuids: List<String>, angles: List<Int>): String =
        listName + "\n" + climbUuids.indices.joinToString(",") { "${climbUuids[it]}@${angles[it]}" }

    private fun map(state: PlaylistPlayerState, connected: Boolean): SessionBannerState {
        val resting = state.phase as? PlaybackPhase.Resting
        val current = state.currentClimb
        val showPosition = state.currentIndex >= 0 && state.queue.isNotEmpty()
        return SessionBannerState(
            isActive = state.isActive,
            listName = state.listName,
            currentClimbName = state.currentClimbName,
            currentClimbUuid = current?.climbUuid ?: "",
            currentAngle = current?.angle ?: -1,
            position = if (showPosition) state.currentIndex + 1 else 0,
            total = if (showPosition) state.queue.size else 0,
            hasPrevious = state.isActive && state.hasPrevious,
            hasNext = state.isActive && state.hasNext,
            canLight = state.isActive && current != null && connected,
            isResting = resting != null,
            restSecondsRemaining = resting?.secondsRemaining ?: 0,
        )
    }
}

/** Whether the one board link can take a climb right now. */
internal object BoardLink {
    fun isLinked(state: ConnectionState): Boolean =
        state == ConnectionState.CONNECTED || state == ConnectionState.SENDING

    fun linked(state: StateFlow<BoardConnectionUiState>): Flow<Boolean> =
        state.map { isLinked(it.connection) }.distinctUntilChanged()
}
