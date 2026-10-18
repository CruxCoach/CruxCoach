package com.cruxcoach.app.session

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.playlist.PlayerClimbInfo
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
    /** One "uuid@angle" per queued entry (uuid lowercased): the browser counts a climb's entries from it. */
    val queuedKeys: List<String>,
)

/** One row of Android's `SessionQueueSheet`. */
class SessionQueueRowUi(
    val index: Int,
    val climbUuid: String,
    val angle: Int,
    val name: String,
    /** "" without a grade. */
    val grade: String,
    val isCurrent: Boolean,
    val restAfterSeconds: Int,
)

class SessionQueueSheetState(
    val isActive: Boolean,
    val rows: List<SessionQueueRowUi>,
    val currentIndex: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
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
    /** Name and difficulty of a queued climb for the queue sheet (Android `SessionQueueViewModel`). */
    private val rowInfo: (climbUuid: String, angle: Int) -> PlayerClimbInfo? = { _, _ -> null },
    private val gradeLabel: (Double?) -> String = { "" },
) {
    /** uuid → resolved row info, as Android caches it; touched on the main thread only. */
    private val rowInfoCache = mutableMapOf<String, PlayerClimbInfo?>()

    /** The list the running playback was started from (Android `playingSource`). */
    private var playingSource: String? = null

    val bannerState: SessionBannerState get() = map(presenter.state.value, isBoardConnected())

    /** True while a playlist runs. */
    val isActive: Boolean get() = presenter.state.value.isActive

    /**
     * The running playlist's current climb (uuid, angle); null while none runs. The nearby area treats
     * it as Android treats a hosted queue's current climb.
     */
    internal val currentClimbFlow: Flow<Pair<String, Int>?>
        get() = presenter.state
            .map { s -> s.currentClimb?.takeIf { s.isActive }?.let { it.climbUuid to it.angle } }
            .distinctUntilChanged()

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

    val queueState: SessionQueueSheetState get() = mapQueue(presenter.state.value)

    fun watchQueue(onState: (SessionQueueSheetState) -> Unit): Subscription {
        val job = scope.launch { presenter.state.collect { onState(mapQueue(it)) } }
        return Subscription { job.cancel() }
    }

    /** The player's queue sheet: a jump is a user override and never arms a rest (Android). */
    fun selectQueueClimb(index: Int) = player.setCurrent(index)
    fun removeQueueClimb(index: Int) = presenter.removeAt(index)
    fun moveQueueClimb(from: Int, to: Int) = presenter.move(from, to)

    /** Android's "+" on a browser row while a playlist runs: queue the climb at the browsed angle. */
    fun addToQueue(climbUuid: String, angle: Int) = presenter.addClimb(climbUuid, angle)

    /** How often [climbUuid] is queued at [angle] (Android `runningPlaylistCounts`, case-blind). */
    fun queuedCount(state: SessionBannerState, climbUuid: String, angle: Int): Int {
        val key = queueKey(climbUuid, angle)
        return state.queuedKeys.count { it == key }
    }

    fun previous() = player.previous()
    fun next() = player.next()

    /** The banner's lamp: put the current climb back on the wall. */
    fun light() = player.resendCurrentClimb()

    private fun mapQueue(state: PlaylistPlayerState): SessionQueueSheetState {
        val rows = state.queue.mapIndexed { index, item ->
            val info = rowInfoCache.getOrPut(item.climbUuid) { runCatching { rowInfo(item.climbUuid, item.angle) }.getOrNull() }
            SessionQueueRowUi(
                index = index,
                climbUuid = item.climbUuid,
                angle = item.angle,
                // Android shows the uuid's first 8 characters for a climb it cannot resolve.
                name = info?.name?.takeIf { it.isNotBlank() } ?: item.climbUuid.take(8),
                grade = info?.difficultyAverage?.let { gradeLabel(it) } ?: "",
                isCurrent = index == state.currentIndex,
                restAfterSeconds = item.restAfterSeconds,
            )
        }
        return SessionQueueSheetState(
            isActive = state.isActive,
            rows = rows,
            currentIndex = state.currentIndex,
            hasPrevious = state.isActive && state.hasPrevious,
            hasNext = state.isActive && state.hasNext,
        )
    }

    private fun queueKey(climbUuid: String, angle: Int) = "${climbUuid.lowercase()}@$angle"

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
            queuedKeys = if (state.isActive) state.queue.map { queueKey(it.climbUuid, it.angle) } else emptyList(),
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
