package com.cruxcoach.android.data

import com.cruxcoach.android.ble.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the player is doing right now. */
sealed interface PlaybackPhase {
    /** A climb is on the board (or waiting to be sent). */
    data object Climbing : PlaybackPhase

    /** A planned rest block is counting down. */
    data class Resting(val secondsRemaining: Int, val totalSeconds: Int) : PlaybackPhase
}

/**
 * The single source of truth for the running playlist — everything the
 * player screen and the mini-player render, in one immutable snapshot.
 */
data class PlaylistPlaybackState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val role: SessionRole = SessionRole.NONE,
    val hostName: String = "",
    val participantCount: Int = 0,
    val participants: List<SessionParticipant> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val currentClimb: QueueItem? = null,
    val currentClimbName: String? = null,
    val currentClimbDifficulty: Double? = null,
    val phase: PlaybackPhase = PlaybackPhase.Climbing,
    /** Timer state of the surrounding training session. */
    val isPaused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val ascentCount: Int = 0,
    val bidCount: Int = 0,
) {
    val isHost: Boolean get() = role == SessionRole.HOST
    val isParticipant: Boolean get() = role == SessionRole.PARTICIPANT
    val isResting: Boolean get() = phase is PlaybackPhase.Resting

    /** During a rest, "next" skips the pause — always available then. */
    val hasNext: Boolean get() = isResting || currentIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = currentIndex > 0 || isResting
    val upNext: QueueItem? get() = queue.getOrNull(currentIndex + 1)

    /**
     * (attempt, totalAttempts) when the current climb is part of a run of
     * consecutive identical climbs — the limit/projecting attempt
     * structure. Null for single occurrences.
     */
    val attemptInfo: Pair<Int, Int>?
        get() {
            val current = currentClimb ?: return null
            if (currentIndex !in queue.indices) return null
            var start = currentIndex
            while (start > 0 && queue[start - 1].climbUuid == current.climbUuid) start--
            var end = currentIndex
            while (end < queue.size - 1 && queue[end + 1].climbUuid == current.climbUuid) end++
            val total = end - start + 1
            return if (total > 1) (currentIndex - start + 1) to total else null
        }
}

/**
 * Facade over the playlist-playback machinery: [SessionQueueManager]
 * (queue + roles + board send), [BoardSessionManager] (session timer +
 * rest timer) and [SessionGattBridge] (multi-user GATT). UI code — the
 * player screen, the mini-player, the queue sheet — talks ONLY to this
 * class, so control logic (role-aware next/prev, the end-vs-leave split,
 * rest skipping) lives in exactly one place instead of being copied into
 * every surface.
 *
 * The BLE/GATT layer stays untouched — this is presentation-side
 * consolidation.
 */
class PlaylistPlaybackCoordinator(
    private val queueManager: SessionQueueManager,
    private val boardSessionManager: BoardSessionManager,
    private val gattBridge: SessionGattBridge,
    private val bleShareManager: BleShareManager,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    val state: StateFlow<PlaylistPlaybackState> = combine(
        queueManager.state,
        queueManager.currentClimbInfo,
        boardSessionManager.state,
        boardSessionManager.restTimer,
    ) { queue, climbInfo, session, rest ->
        buildState(queue, climbInfo, session, rest)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        // Seed with the REAL current values, not a blank default: the
        // player's ended-elsewhere auto-close reads isActive on first
        // frame — a false-y placeholder made it pop right back out of a
        // freshly started playlist before combine's first emission.
        buildState(
            queueManager.state.value,
            queueManager.currentClimbInfo.value,
            boardSessionManager.state.value,
            boardSessionManager.restTimer.value,
        ),
    )

    private fun buildState(
        queue: SessionQueueState,
        climbInfo: ClimbDisplayInfo?,
        session: BoardSessionState,
        rest: RestTimerState,
    ): PlaylistPlaybackState = PlaylistPlaybackState(
        isActive = queue.isActive,
        isConnecting = queue.isConnecting,
        role = queue.role,
        hostName = queue.hostName,
        participantCount = queue.participantCount,
        participants = queue.participants,
        queue = queue.queue,
        currentIndex = queue.currentIndex,
        currentClimb = queue.currentClimb,
        currentClimbName = climbInfo?.name,
        currentClimbDifficulty = climbInfo?.difficultyAverage,
        phase = if (rest.isRunning) {
            PlaybackPhase.Resting(rest.secondsRemaining, rest.totalSeconds)
        } else {
            PlaybackPhase.Climbing
        },
        isPaused = session.isPaused,
        elapsedSeconds = session.elapsedSeconds,
        ascentCount = session.ascentCount,
        bidCount = session.bidCount,
    )

    // ── Playback control (role-aware — the ONLY place that logic lives) ──

    /**
     * Next is PHASE-aware: while a rest counts down the queue already sits
     * on the upcoming climb (the pause was armed while advancing), so
     * "next" means "skip the pause, climb now" — NOT "advance past a climb
     * you haven't tried yet and arm the following pause".
     */
    fun next() {
        if (state.value.phase is PlaybackPhase.Resting) {
            skipRest()
            return
        }
        if (state.value.isParticipant) gattBridge.sendNext() else queueManager.nextClimb()
    }

    /** Previous during a rest = undo the advance: cancel the pause (and
     *  resume the session clock it paused — same semantics as [skipRest])
     *  and step back to the climb you just left. */
    fun previous() {
        if (state.value.phase is PlaybackPhase.Resting) {
            skipRest()
        }
        if (state.value.isParticipant) gattBridge.sendPrev() else queueManager.previousClimb()
    }

    /** Clears the lingering "rest finished" banner state once the player
     *  has visibly returned to climbing. */
    fun acknowledgeRestFinished() = boardSessionManager.dismissRestTimerFinished()

    fun setCurrent(index: Int) {
        if (state.value.isParticipant) gattBridge.sendSetCurrent(index) else queueManager.setCurrentClimb(index)
    }

    fun togglePause() {
        if (boardSessionManager.state.value.isPaused) boardSessionManager.resumeSession()
        else boardSessionManager.pauseSession()
    }

    /** End the rest block early and resume the session clock. */
    fun skipRest() {
        boardSessionManager.cancelRestTimer()
        if (boardSessionManager.state.value.isPaused) boardSessionManager.resumeSession()
    }

    /** Host-only: force re-send when someone else re-lit the wall. */
    fun resendCurrentClimb() = queueManager.resendCurrentClimb()

    /**
     * Start playing a playlist as HOST: session timer + queue bulk-load +
     * rest hook + GATT advertising (behind the privacy toggle).
     */
    fun play(hostName: String, items: List<QueueItem>) {
        if (items.isEmpty()) return
        boardSessionManager.startSession()
        queueManager.onRestRequested = { seconds ->
            boardSessionManager.startRestTimer(seconds)
        }
        queueManager.loadPlaylist(hostName, items)
        if (bleShareManager.uiState.value.sharingEnabled) {
            gattBridge.startSharing()
        }
    }

    /**
     * Start an ad-hoc playlist as HOST (browser "Playlist" button). Seeds
     * the queue with the climb currently ON the board (the mini-player
     * banner shows it, so an empty player with "unknown climb" right after
     * would contradict what the user just saw lit on the wall).
     */
    fun startEmpty(hostName: String) {
        boardSessionManager.startSession()
        queueManager.startQueue(hostName)
        queueManager.onRestRequested = { seconds ->
            boardSessionManager.startRestTimer(seconds)
        }
        bleShareManager.uiState.value.onBoardClimb?.let { onBoard ->
            if (onBoard.climbUuid.isNotBlank()) {
                queueManager.addClimb(onBoard.climbUuid, onBoard.angle)
            }
        }
        if (bleShareManager.uiState.value.sharingEnabled) {
            gattBridge.startSharing()
        }
    }

    /** Join a nearby playlist as PARTICIPANT. */
    fun join(entry: NearbySessionEntry) {
        val device = entry.rawSession.device ?: return
        boardSessionManager.startSession()
        // No startQueue() here: that would flash HOST before GATT connects;
        // joinSession() drives setConnecting() → setParticipantRole().
        gattBridge.joinSession(device)
    }

    /**
     * Stop playback — the end-vs-leave split that used to be duplicated
     * across BleStatusExpanded and the browser. Returns the finished
     * session row (for the summary) or null when nothing was recorded.
     */
    fun stop(): com.cruxcoach.data.repository.Board_sessions? {
        val lastClimb = queueManager.state.value.currentClimb
        if (queueManager.state.value.role == SessionRole.HOST) {
            gattBridge.stopSharing()
            queueManager.endQueue()
        } else {
            gattBridge.leaveSession()
        }
        val finished = boardSessionManager.endSession()
        // Keep "last on board" visible immediately (stopSharing's GATT
        // sentinel path does this too, but with a delay).
        if (lastClimb != null) {
            bleShareManager.setLastClimbAfterSession(lastClimb.climbUuid, lastClimb.angle)
        }
        return finished
    }
}
