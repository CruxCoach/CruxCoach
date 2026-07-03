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

    fun next() {
        if (state.value.role == SessionRole.PARTICIPANT) {
            gattBridge.sendNext()
        } else {
            queueManager.nextClimb()
        }
    }

    fun prev() {
        if (state.value.role == SessionRole.PARTICIPANT) {
            gattBridge.sendPrev()
        } else {
            queueManager.previousClimb()
        }
    }

    fun setCurrent(index: Int) {
        if (state.value.role == SessionRole.PARTICIPANT) {
            gattBridge.sendSetCurrent(index)
        } else {
            queueManager.setCurrentClimb(index)
        }
    }

    fun removeClimb(index: Int) {
        if (state.value.role == SessionRole.PARTICIPANT) {
            gattBridge.sendRemoveClimb(index)
        } else {
            queueManager.removeClimb(index)
        }
    }

    fun moveClimb(from: Int, to: Int) {
        if (state.value.role == SessionRole.PARTICIPANT) {
            gattBridge.sendMove(from, to)
        } else {
            queueManager.moveClimb(from, to)
        }
    }

    fun endOrLeave() {
        // Single stop implementation lives in the coordinator (end-vs-leave
        // split + last-climb handover). The summary is only staged when the
        // player drives the stop — standalone sheet contexts skip it.
        playback.stop()
    }
}
