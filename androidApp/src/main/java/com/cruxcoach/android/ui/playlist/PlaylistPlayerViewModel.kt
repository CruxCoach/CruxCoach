package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val climbNavState: ClimbNavigationState,
) : ViewModel() {

    val playbackState: StateFlow<PlaylistPlaybackState> = playback.state

    private val _state = MutableStateFlow(PlaylistPlayerState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(gradeScale = userPreferences.getBoardFilterSnapshot().gradeScale) }
        }
        // Re-load the board render whenever the current climb changes.
        viewModelScope.safeLaunch(TAG) {
            var lastKey: String? = null
            playback.state.collect { s ->
                val item = s.currentClimb
                val key = item?.let { "${it.climbUuid}:${it.angle}" }
                if (key == lastKey) return@collect
                lastKey = key
                if (item == null) {
                    _state.update { it.copy(render = null, renderLoading = false) }
                    return@collect
                }
                _state.update { it.copy(renderLoading = true) }
                val render = withContext(Dispatchers.IO) {
                    renderLoader.load(item.climbUuid, item.angle)
                }
                // Only apply if still current (rapid next/next).
                if (lastKey == key) {
                    _state.update { it.copy(render = render, renderLoading = false) }
                }
            }
        }
    }

    /**
     * Quick-log for the current climb: send (isSend) or attempt. Same
     * write path as the detail screen's AscentLogger — ascent/bid row,
     * Verlauf entry for sends, session counters, zone recompute — but
     * one tap instead of dialog + form. Defaults: 1 attempt, no comment.
     */
    fun quickLog(isSend: Boolean) {
        val climb = _state.value.render?.climb ?: return
        val angle = playback.state.value.currentClimb?.angle ?: return
        viewModelScope.safeLaunch(TAG) {
            withContext(Dispatchers.IO) {
                val uuid = java.util.UUID.randomUUID().toString()
                val now = com.cruxcoach.util.DateTimeUtil.nowIso()
                if (isSend) {
                    personalBoardRepo.insertAscent(
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
                val ascents = personalBoardRepo.getUserAscentsBetween(
                    finished.startedAt, finished.endedAt ?: finished.startedAt
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
