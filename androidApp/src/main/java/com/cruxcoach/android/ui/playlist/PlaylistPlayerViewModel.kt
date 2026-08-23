package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.board.ClimbRenderData
import com.cruxcoach.android.ui.board.ClimbRenderLoader
import com.cruxcoach.android.ui.board.EnhancedSessionSummary
import com.cruxcoach.android.ui.board.SessionSummaryBuilder
import com.cruxcoach.android.ui.board.RandomBoardClimbPicker
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** Local occurrence focus. It never writes the shared playlist cursor/current. */
    val focusedEntryId: String? = null,
    val focusedIndex: Int = -1,
)

/**
 * Player over [PlaylistPlaybackCoordinator]: exposes its state verbatim,
 * loads the render payload whenever the current climb changes, and builds
 * the end-of-playlist summary on stop.
 */
@HiltViewModel
class PlaylistPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val playback: PlaylistPlaybackCoordinator,
    private val boardCellManager: BoardCellManager,
    private val renderLoader: ClimbRenderLoader,
    private val userPreferences: UserPreferences,
    private val personalBoardRepo: PersonalBoardRepository,
    private val zoneManager: IntensityZoneManager,
    private val boardSessionManager: com.cruxcoach.android.data.BoardSessionManager,
    val climbNavState: ClimbNavigationState,
    private val randomClimbPicker: RandomBoardClimbPicker,
) : ViewModel() {

    private val requestedEntryId: String? = savedStateHandle["entryId"]

    val playbackState: StateFlow<PlaylistPlaybackState> = playback.state

    private val _state = MutableStateFlow(PlaylistPlayerState())
    val state = _state.asStateFlow()
    private val _randomAddUnavailable =
        MutableSharedFlow<com.cruxcoach.android.ui.board.RandomClimbRoll>(extraBufferCapacity = 1)
    val randomAddUnavailable: SharedFlow<com.cruxcoach.android.ui.board.RandomClimbRoll> =
        _randomAddUnavailable

    fun addRandomClimb() {
        viewModelScope.launch {
            when (val roll = withContext(Dispatchers.IO) { randomClimbPicker.roll() }) {
                is com.cruxcoach.android.ui.board.RandomClimbRoll.Picked ->
                    playback.addClimb(roll.climbUuid, roll.angle)
                else -> _randomAddUnavailable.emit(roll)
            }
        }
    }

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
            var lastKey: String? = null
            playback.state.collect { s ->
                val mesh = s.mesh
                val previousFocus = _state.value.focusedEntryId
                val focusId = if (mesh != null) PlaylistOccurrenceFocus.resolve(
                    mesh.entryIds, requestedEntryId, previousFocus, mesh.currentEntryId,
                ) else null
                val focusIndex = focusId?.let { id -> mesh?.entryIds?.indexOf(id) } ?: s.currentIndex
                val item = s.queue.getOrNull(focusIndex)
                _state.update { it.copy(focusedEntryId = focusId, focusedIndex = focusIndex) }
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
        val angle = focusedItem()?.angle ?: return
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

    fun previous() {
        val s = playback.state.value
        if (!s.isCanonicalPlaylist) return playback.previous()
        focusEntry(PlaylistOccurrenceFocus.step(
            s.mesh!!.entryIds, _state.value.focusedEntryId, -1,
        ))
    }

    fun next() {
        val s = playback.state.value
        if (!s.isCanonicalPlaylist) return playback.next()
        focusEntry(PlaylistOccurrenceFocus.step(
            s.mesh!!.entryIds, _state.value.focusedEntryId, 1,
        ))
    }

    fun resendFocused() {
        val s = playback.state.value
        val item = focusedItem() ?: return
        val entryId = _state.value.focusedEntryId
        if (!s.isCanonicalPlaylist || entryId == null) {
            playback.resendCurrentClimb()
            return
        }
        viewModelScope.safeLaunch(TAG) {
            boardCellManager.lightNow(item.climbUuid, item.angle, entryId)
        }
    }

    private fun focusEntry(entryId: String?) {
        val playbackState = playback.state.value
        val mesh = playbackState.mesh ?: return
        val index = mesh.entryIds.indexOf(entryId).takeIf { it >= 0 } ?: return
        _state.update { it.copy(focusedEntryId = entryId, focusedIndex = index) }
        val item = playbackState.queue.getOrNull(index) ?: return
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(renderLoading = true) }
            val render = withContext(Dispatchers.IO) {
                renderLoader.load(item.climbUuid, item.angle)
            }
            if (_state.value.focusedEntryId == entryId) {
                _state.update { it.copy(render = render, renderLoading = false) }
            }
        }
    }

    private fun focusedItem() = playback.state.value.let { s ->
        s.queue.getOrNull(if (s.isCanonicalPlaylist) _state.value.focusedIndex else s.currentIndex)
    }

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

    /**
     * Stop playback and stage the summary sheet.
     *
     * Stopping a board playlist closes it on this device and nothing more. It
     * belongs to the board group and carries on for everybody else; emptying
     * it for the whole group is a separate, explicitly labelled action in the
     * queue sheet.
     */
    fun stop(endForEveryone: Boolean = false) {
        _state.update { it.copy(showStopConfirm = false) }
        val finished = playback.stop(endForEveryone = endForEveryone) ?: return
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
