package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.board.ClimbRenderData
import com.cruxcoach.android.ui.board.ClimbRenderLoader
import com.cruxcoach.android.ui.board.EnhancedSessionSummary
import com.cruxcoach.android.ui.board.SessionSummaryBuilder
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.QuickLogSendInput
import com.cruxcoach.domain.board.IntensityZones
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistPlayerState(
    val render: ClimbRenderData? = null,
    val renderLoading: Boolean = false,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    /** Set after stop — drives the summary sheet before leaving. */
    val finishedSession: Board_sessions? = null,
    val summary: EnhancedSessionSummary? = null,
    val zones: IntensityZones? = null,
    /** One-shot quick-log feedback: true=send, false=attempt, null=idle. */
    val lastLogged: Boolean? = null,
    /** Confirm dialog for the central stop button. */
    val showStopConfirm: Boolean = false,
)

/**
 * Resolve only the newest playlist occurrence. Clearing the previous render at
 * the loading boundary prevents its board family and Quantum rack from leaking
 * onto the next occurrence while catalogue/geometry reads are still running.
 */
internal suspend fun collectPlaylistRenders(
    playbackStates: Flow<PlaylistPlaybackState>,
    load: suspend (com.cruxcoach.android.ble.QueueItem) -> ClimbRenderData?,
    update: (render: ClimbRenderData?, loading: Boolean) -> Unit,
) {
    playbackStates
        .map { it.currentClimb }
        .distinctUntilChangedBy { item -> item?.let { "${it.climbUuid}:${it.angle}" } }
        .collectLatest { item ->
            if (item == null) {
                update(null, false)
                return@collectLatest
            }
            update(null, true)
            val render = load(item)
            update(render, false)
        }
}

/**
 * The attempt row a quick log in the player continues: the newest entry on
 * this climb and angle is an unsent attempt from the running session. A send
 * after it closes that sequence, so a later attempt starts a fresh row.
 * Without this, "Attempt" then "Sent" left an open attempt entry beside a
 * one-try send that counted as a flash.
 */
internal fun openPlayerAttempt(
    history: List<AscentWithClimb>,
    angle: Long,
    sessionStartedAt: String?,
): AscentWithClimb? {
    val start = sessionStartedAt ?: return null
    val newest = history
        .filter { it.angle == angle && !it.isMirror }
        .maxByOrNull { it.climbedAt }
        ?: return null
    return newest.takeIf { !it.isSend && it.climbedAt >= start }
}

/**
 * Player over [PlaylistPlaybackCoordinator]: exposes its state verbatim,
 * loads the render payload whenever the current climb changes, and builds
 * the end-of-playlist summary on stop.
 */
@HiltViewModel
class PlaylistPlayerViewModel @Inject constructor(
    val playback: PlaylistPlaybackCoordinator,
    private val renderLoader: ClimbRenderLoader,
    private val userPreferences: UserPreferences,
    private val personalBoardRepo: PersonalBoardRepository,
    private val zoneManager: IntensityZoneManager,
    private val boardSessionManager: com.cruxcoach.android.data.BoardSessionManager,
    boardLayerManager: BoardLayerManager,
    val climbNavState: ClimbNavigationState,
) : ViewModel() {

    val playbackState: StateFlow<PlaylistPlaybackState> = playback.state
    /** Local physical-controller rack; playlists never publish this state. */
    val boardLayerState = boardLayerManager.state

    private val _state = MutableStateFlow(PlaylistPlayerState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(gradeScale = userPreferences.getBoardFilterSnapshot().gradeScale) }
        }
        viewModelScope.safeLaunch(TAG) {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        // Re-load the board render whenever the current climb changes.
        viewModelScope.safeLaunch(TAG) {
            collectPlaylistRenders(
                playbackStates = playback.state,
                load = { item -> withContext(Dispatchers.IO) {
                    renderLoader.load(item.climbUuid, item.angle)
                } },
                update = { render, loading ->
                    _state.update { it.copy(render = render, renderLoading = loading) }
                },
            )
        }
    }

    /**
     * Quick-log for the current climb: send (isSend) or attempt. Same
     * write path as the detail screen's AscentLogger — ascent/bid row,
     * Verlauf entry for sends, session counters, zone recompute — but
     * one tap instead of dialog + form. Like the detail screen's quick log,
     * repeated attempts add to one open attempt row and a send promotes that
     * row, so the send carries its real number of tries.
     */
    fun quickLog(isSend: Boolean) {
        val climb = _state.value.render?.climb ?: return
        val angle = playback.state.value.currentClimb?.angle ?: return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val uuid = java.util.UUID.randomUUID().toString()
                val now = com.cruxcoach.util.DateTimeUtil.nowIso()
                val open = openPlayerAttempt(
                    history = personalBoardRepo.getUserHistoryForClimb(climb.uuid),
                    angle = angle.toLong(),
                    sessionStartedAt = boardSessionManager.state.value.startedAt,
                )
                if (isSend) {
                    if (open != null) personalBoardRepo.promoteQuickBidToSend(
                        QuickLogSendInput(
                            uuid = open.uuid,
                            climbUuid = climb.uuid,
                            angle = angle.toLong(),
                            isMirror = false,
                            bidCount = open.bidCount + 1,
                            difficulty = climb.difficultyAverage?.toLong(),
                            climbedAt = now,
                            climbName = climb.name,
                            difficultyAverage = climb.difficultyAverage,
                            climbFrames = climb.frames,
                            framesCount = climb.framesCount,
                            boardBrand = climb.boardBrand,
                            layoutId = climb.layoutId,
                        )
                    ) else personalBoardRepo.insertAscent(
                        uuid = uuid,
                        climbUuid = climb.uuid,
                        angle = angle.toLong(),
                        isMirror = false,
                        attemptId = 0,
                        bidCount = 1,
                        quality = null,
                        difficulty = climb.difficultyAverage?.toLong(),
                        isBenchmark = false,
                        comment = null,
                        climbedAt = now,
                        synced = false,
                        climbName = climb.name,
                        difficultyAverage = climb.difficultyAverage,
                        climbFrames = climb.frames,
                        framesCount = climb.framesCount,
                        boardBrand = climb.boardBrand,
                        layoutId = climb.layoutId,
                    )
                    personalBoardRepo.recordClimbHistory(
                        climbUuid = climb.uuid,
                        climbName = climb.name,
                        angle = angle.toLong(),
                        difficultyAverage = climb.difficultyAverage,
                        boardBrand = climb.boardBrand,
                        layoutId = climb.layoutId,
                        climbedAt = now,
                        recordedAt = now,
                    )
                } else if (open != null) {
                    personalBoardRepo.updateBid(
                        uuid = open.uuid,
                        bidCount = open.bidCount + 1,
                        comment = open.comment,
                    )
                } else {
                    personalBoardRepo.insertBid(
                        uuid = uuid,
                        climbUuid = climb.uuid,
                        angle = angle.toLong(),
                        isMirror = false,
                        bidCount = 1,
                        comment = null,
                        climbedAt = now,
                        synced = false,
                        climbName = climb.name,
                        difficultyAverage = climb.difficultyAverage,
                        boardBrand = climb.boardBrand,
                        layoutId = climb.layoutId,
                    )
                }
            }
            climbNavState.statusDataChanged = true
            climbNavState.changedClimbUuids.add(climb.uuid)
            if (isSend) boardSessionManager.recordAscent() else boardSessionManager.recordBid()
            zoneManager.recompute()
            _state.update { it.copy(lastLogged = isSend) }
            playback.onClimbLogged(isSend)
        }
    }

    fun consumeLogFeedback() = _state.update { it.copy(lastLogged = null) }

    /** Retry publication after the host permission dialog completes.
     * BLE setup may involve a slow vendor stack during host handover, so it
     * must not run on Compose's main thread. */
    fun retrySharing() {
        viewModelScope.launch(Dispatchers.Default) {
            playback.retrySharing()
        }
    }

    fun requestStop() = _state.update { it.copy(showStopConfirm = true) }
    fun dismissStopConfirm() = _state.update { it.copy(showStopConfirm = false) }

    /** Stop playback and stage the summary sheet. */
    fun stop(endForEveryone: Boolean = false) {
        _state.update { it.copy(showStopConfirm = false) }
        val finished = playback.stop(endForEveryone) ?: return
        _state.update { it.copy(finishedSession = finished) }
        viewModelScope.safeLaunch(TAG) {
            val gradeScale = userPreferences.gradeScale.first()
            val summary = withContext(Dispatchers.IO) {
                val ascents = com.cruxcoach.android.ui.board.SessionSummaryBuilder.sessionRows(
                    personalBoardRepo, finished.startedAt, finished.endedAt ?: finished.startedAt
                )
                // True flashes need the FULL history — a first-try repeat of
                // an old project must not count as a flash.
                val flashUuids = com.cruxcoach.android.ui.board.BoardStatsComputer
                    .trueFlashUuids(personalBoardRepo.getUserAscentsAll())
                SessionSummaryBuilder.build(ascents, zoneManager.zones.value, gradeScale, flashUuids)
            }
            _state.update { it.copy(summary = summary, zones = zoneManager.zones.value) }
        }
    }

    fun consumeSummary() {
        _state.update { it.copy(finishedSession = null, summary = null) }
    }

    private companion object {
        const val TAG = "PlaylistPlayerVM"
    }
}
