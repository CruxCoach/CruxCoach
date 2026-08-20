package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Resolved display payload for one queue row. */
data class QueueRowInfo(val name: String, val gradeLabel: String?)

@HiltViewModel
class SessionQueueViewModel @Inject constructor(
    private val queueManager: SessionQueueManager,
    private val gattBridge: SessionGattBridge,
    private val boardRepository: BoardRepository,
    private val playback: com.cruxcoach.android.data.PlaylistPlaybackCoordinator,
    private val userPreferences: com.cruxcoach.android.data.UserPreferences,
    val climbNavState: ClimbNavigationState
) : ViewModel() {

    val state: StateFlow<SessionQueueState> = queueManager.state
    val pendingCommandCount = gattBridge.pendingCommandCount
    val commandFeedback = gattBridge.commandFeedback

    private val _climbInfos = MutableStateFlow<Map<String, QueueRowInfo>>(emptyMap())
    /** uuid → (name, formatted grade) for the queue rows. */
    val climbInfos: StateFlow<Map<String, QueueRowInfo>> = _climbInfos.asStateFlow()

    init {
        // Resolve climb names + grades whenever the queue changes.
        viewModelScope.launch {
            queueManager.state.collect { s ->
                val newUuids = s.queue.map { it.climbUuid }.toSet()
                val existing = _climbInfos.value
                val missing = newUuids - existing.keys
                if (missing.isNotEmpty()) {
                    val gradeScale = userPreferences.gradeScale.first()
                    val resolved = withContext(Dispatchers.IO) {
                        missing.associateWith { uuid ->
                            val angle = s.queue.firstOrNull { it.climbUuid == uuid }?.angle ?: 40
                            val climb = boardRepository.getClimbByUuid(uuid, angle)
                                ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
                                ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle)
                            QueueRowInfo(
                                name = climb?.name ?: uuid.take(8),
                                gradeLabel = climb?.difficultyAverage?.let {
                                    GradeDisplayHelper.formatDifficulty(it, gradeScale)
                                },
                            )
                        }
                    }
                    _climbInfos.value = existing + resolved
                }
            }
        }
    }

    /**
     * In the shared playlist there is no local shortcut for anybody, the
     * technical controller included: the queue on screen is a projection of
     * canonical mesh state,
     * so an edit applied locally would be overwritten by the next snapshot and
     * would never reach the other members. [sendsCommand] is therefore about
     * where the truth lives, not about which role this device happens to hold.
     */
    private val sendsCommand: Boolean
        get() = state.value.mesh != null || state.value.role == SessionRole.PARTICIPANT

    // Transport goes through the coordinator, which owns the phase-aware rules
    // (advancing versus skipping a running rest) for local and remote alike.
    fun next() = playback.next()

    fun prev() = playback.previous()

    fun setCurrent(index: Int) = playback.setCurrent(index)

    fun removeClimb(index: Int) {
        if (sendsCommand) gattBridge.sendRemoveClimb(index) else queueManager.removeClimb(index)
    }

    fun moveClimb(from: Int, to: Int) {
        if (sendsCommand) gattBridge.sendMove(from, to) else queueManager.moveClimb(from, to)
    }

    fun endOrLeave() {
        // Single stop implementation lives in the coordinator (end-vs-leave
        // split + last-climb handover). The summary is only staged when the
        // player drives the stop — standalone sheet contexts skip it.
        playback.stop()
    }
}
