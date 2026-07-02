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
import kotlinx.coroutines.withContext

data class PlaylistPlayerState(
    val render: ClimbRenderData? = null,
    val renderLoading: Boolean = false,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    /** Set after stop — drives the summary sheet before leaving. */
    val finishedSession: Board_sessions? = null,
    val summary: EnhancedSessionSummary? = null,
    val zones: IntensityZones? = null,
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

    /** Stop playback and stage the summary sheet. */
    fun stop() {
        val finished = playback.stop() ?: return
        _state.update { it.copy(finishedSession = finished) }
        viewModelScope.safeLaunch(TAG) {
            val gradeScale = userPreferences.gradeScale.first()
            val summary = withContext(Dispatchers.IO) {
                val ascents = personalBoardRepo.getUserAscentsBetween(
                    finished.startedAt, finished.endedAt ?: finished.startedAt
                )
                SessionSummaryBuilder.build(ascents, zoneManager.zones.value, gradeScale)
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
