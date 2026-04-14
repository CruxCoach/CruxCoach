package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SessionQueueViewModel @Inject constructor(
    private val queueManager: SessionQueueManager,
    private val gattBridge: SessionGattBridge,
    private val boardRepository: BoardRepository,
    private val sessionManager: BoardSessionManager,
    val climbNavState: ClimbNavigationState
) : ViewModel() {

    val state: StateFlow<SessionQueueState> = queueManager.state

    private val _climbNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val climbNames: StateFlow<Map<String, String>> = _climbNames.asStateFlow()

    init {
        // Resolve climb names whenever queue changes
        viewModelScope.launch {
            queueManager.state.collect { s ->
                val newUuids = s.queue.map { it.climbUuid }.toSet()
                val existing = _climbNames.value
                val missing = newUuids - existing.keys
                if (missing.isNotEmpty()) {
                    val resolved = withContext(Dispatchers.IO) {
                        missing.associateWith { uuid ->
                            val angle = s.queue.firstOrNull { it.climbUuid == uuid }?.angle ?: 40
                            (boardRepository.getClimbByUuid(uuid, angle)
                                ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
                                ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle))?.name
                                ?: uuid.take(8)
                        }
                    }
                    _climbNames.value = existing + resolved
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
        when (state.value.role) {
            SessionRole.HOST -> {
                gattBridge.stopSharing()
                queueManager.endQueue()
                sessionManager.endSession()
            }
            SessionRole.PARTICIPANT -> {
                gattBridge.leaveSession()
                sessionManager.endSession()
            }
            SessionRole.NONE -> { /* nothing */ }
        }
    }
}
