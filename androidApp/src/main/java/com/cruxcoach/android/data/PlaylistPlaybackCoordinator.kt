package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.ListPlaybackAdvance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    /** What was asked for; differs from [visibility] while sharing cannot start. */
    val visibilityRequested: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    /** The current climb is waiting for an explicit send — see the send mode. */
    val awaitingExplicitSend: Boolean = false,
    /**
     * A board is connected right now.
     *
     * The send controls read this: without a connection there is nothing to
     * send to, and a lamp that stays lit after a disconnect invites a tap that
     * the send path silently discards.
     */
    val boardConnected: Boolean = false,
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
    /** Non-null while this device follows the BoardCell's joinable playlist. */
    val mesh: MeshPlaylistView? = null,
) {
    val isHost: Boolean get() = role == SessionRole.HOST

    /** The canonical playlist drives the queue; local edits travel as commands. */
    val isCanonicalPlaylist: Boolean get() = mesh != null

    /** The current entry is not on the wall and will not get there by itself. */
    val pendingProjection: com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection?
        get() = mesh?.pendingProjection

    /**
     * Sharing was asked for and is not in force — Bluetooth off, permission
     * missing, GATT server refused. Worth saying out loud: the session recovers
     * by itself once Bluetooth returns, but until then nobody can join and
     * nothing on screen explains why.
     */
    val sharingBlocked: Boolean
        get() = isHost &&
            visibilityRequested == SessionVisibility.JOINABLE &&
            visibility != SessionVisibility.JOINABLE
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
    private val bleConnection: BoardBleConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private var advanceMode: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL

    val state: StateFlow<PlaylistPlaybackState> = combine(
        queueManager.state,
        queueManager.currentClimbInfo,
        boardSessionManager.state,
        boardSessionManager.restTimer,
        bleConnection.connectionState,
    ) { queue, climbInfo, session, rest, connection ->
        buildState(queue, climbInfo, session, rest, connection)
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
            bleConnection.connectionState.value,
        ),
    )

    init {
        // A participant's next/prev arrives as a GATT command and used to go
        // straight into the queue, bypassing the phase-aware rules below.
        // During a host rest the queue already sits on the upcoming climb, so
        // that path advanced a second time and silently skipped a climb
        // nobody had tried. Routing it through the same entry point the local
        // buttons use makes remote and local control behave identically.
        //
        // Registered here rather than in play()/startEmpty() so a future
        // session entry point cannot forget it. Both handlers are host-only
        // in practice — only a host receives client commands — and next()
        // resolves the role itself, so there is nothing to guard.
        gattBridge.onRemoteNext = { next() }
        gattBridge.onRemotePrev = { previous() }
        // Installed once rather than per play(): a canonical playlist can be
        // adopted without anybody calling play() — a join, a process restart
        // or a host handover all arrive as snapshots — and the pauses have to
        // count down there too.
        queueManager.onRestRequested = { seconds -> boardSessionManager.startRestTimer(seconds) }
        queueManager.onRestCleared = { boardSessionManager.cancelRestTimer() }
        // A rest whose canonical end has already passed when this device
        // observes it (a late join, a reconnect, a restart) has no countdown
        // left to run out, so nothing would otherwise clear it. Only the
        // playlist host publishes the end, which keeps the single
        // responsibility that stops every member sending the same command.
        queueManager.onCanonicalRestExpired = { gattBridge.endCanonicalRest() }
        // Exactly one device clears the canonical rest when it runs out. Every
        // member counts the same planned duration down locally, but if they
        // all published the end the controller would serialize a burst of
        // identical commands for one pause.
        scope.launch {
            boardSessionManager.restTimer.collect { rest ->
                val mesh = queueManager.state.value.mesh ?: return@collect
                if (mesh.isHost && mesh.activeRest != null && rest.isFinished && !rest.isRunning) {
                    gattBridge.endCanonicalRest()
                }
            }
        }
    }

    private fun buildState(
        queue: SessionQueueState,
        climbInfo: ClimbDisplayInfo?,
        session: BoardSessionState,
        rest: RestTimerState,
        connection: ConnectionState = ConnectionState.DISCONNECTED,
    ): PlaylistPlaybackState = PlaylistPlaybackState(
        isActive = queue.isActive,
        isConnecting = queue.isConnecting,
        role = queue.role,
        hostName = queue.hostName,
        participantCount = queue.participantCount,
        participants = queue.participants,
        visibility = queue.visibility,
        visibilityRequested = queue.visibilityRequested,
        awaitingExplicitSend = queue.awaitingExplicitSend,
        boardConnected = connection == ConnectionState.CONNECTED,
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
        mesh = queue.mesh,
    )

    // ── Playback control (role-aware — the ONLY place that logic lives) ──

    /**
     * Next is PHASE-aware: while a rest counts down the queue already sits
     * on the upcoming climb (the pause was armed while advancing), so
     * "next" means "skip the pause, climb now" — NOT "advance past a climb
     * you haven't tried yet and arm the following pause".
     */
    fun next() {
        // In a canonical playlist there is no local shortcut: every member has
        // the same rights and every edit is one authenticated command, so the
        // host takes exactly the path a joiner takes.
        if (state.value.isCanonicalPlaylist) {
            if (state.value.phase is PlaybackPhase.Resting) {
                Log.i(TAG, "event=transport_requested action=next effect=skip_rest scope=mesh")
                gattBridge.endCanonicalRest()
            } else {
                Log.i(TAG, "event=transport_requested action=next scope=mesh")
                gattBridge.sendNext()
            }
            return
        }
        // Participants ask; they never decide. Both branches below are host
        // decisions — advancing the queue and skipping a pause — and the host
        // is the one driving the wall. A participant that resolved its own
        // rest locally would stop counting down while the host still was,
        // which is the desync this whole change exists to remove.
        if (state.value.isParticipant) {
            Log.i(TAG, "event=transport_requested action=next role=participant")
            gattBridge.sendNext()
            return
        }
        if (state.value.phase is PlaybackPhase.Resting) {
            Log.i(TAG, "event=transport_applied action=next role=host effect=skip_rest")
            skipRest()
            return
        }
        Log.i(TAG, "event=transport_applied action=next role=host effect=advance")
        queueManager.nextClimb()
    }

    /** Previous during a rest = undo the advance: cancel the pause (and
     *  resume the session clock it paused — same semantics as [skipRest])
     *  and step back to the climb you just left. */
    fun previous() {
        if (state.value.isCanonicalPlaylist) {
            if (state.value.phase is PlaybackPhase.Resting) gattBridge.endCanonicalRest()
            Log.i(TAG, "event=transport_requested action=prev scope=mesh")
            gattBridge.sendPrev()
            return
        }
        if (state.value.isParticipant) {
            Log.i(TAG, "event=transport_requested action=prev role=participant")
            gattBridge.sendPrev()
            return
        }
        if (state.value.phase is PlaybackPhase.Resting) {
            skipRest()
        }
        Log.i(TAG, "event=transport_applied action=prev role=host")
        queueManager.previousClimb()
    }

    /** Clears the lingering "rest finished" banner state once the player
     *  has visibly returned to climbing. */
    fun acknowledgeRestFinished() = boardSessionManager.dismissRestTimerFinished()

    fun setCurrent(index: Int) {
        if (state.value.isCanonicalPlaylist || state.value.isParticipant)
            gattBridge.sendSetCurrent(index)
        else queueManager.setCurrentClimb(index)
    }

    /**
     * End the rest block early and restart the clock.
     *
     * A participant routes this through the host instead of cancelling its own
     * timer: the host's `next()` during a rest skips it and broadcasts
     * RestEnded, which cancels the timer here anyway. Cancelling locally would
     * put this device back on the wall's climb while everyone else — and the
     * wall's own countdown — was still resting.
     */
    fun skipRest() {
        if (state.value.isCanonicalPlaylist) {
            Log.i(TAG, "event=transport_requested action=skip_rest scope=mesh")
            gattBridge.endCanonicalRest()
            return
        }
        if (state.value.isParticipant) {
            Log.i(TAG, "event=transport_requested action=skip_rest role=participant")
            gattBridge.sendNext()
            return
        }
        Log.i(TAG, "event=transport_applied action=skip_rest role=host")
        boardSessionManager.cancelRestTimer()
    }

    /** Force the current climb back onto the wall. Participants ask the host,
     * which remains the sole writer to the physical board. */
    fun resendCurrentClimb() {
        if (state.value.isCanonicalPlaylist) {
            gattBridge.retryPlaylistProjection()
            return
        }
        if (state.value.isParticipant) {
            Log.i(TAG, "event=transport_requested action=resend role=participant")
            gattBridge.sendResend()
        } else {
            queueManager.resendCurrentClimb()
        }
    }

    /** Applies the list's interaction rule after a successful quick-log write. */
    fun onClimbLogged(isSend: Boolean) {
        // A send ends the work on that problem. The hard-bouldering and 4x4
        // shapes schedule several tries of the SAME climb back to back, and
        // without this a climber who topped it first go was walked through the
        // remaining tries of a problem they had already done — the generator's
        // attempt count treated as a quota rather than a budget.
        if (isSend) skipRemainingAttemptsOfCurrentClimb()
        val shouldAdvance = when (advanceMode) {
            ListPlaybackAdvance.MANUAL -> false
            ListPlaybackAdvance.AFTER_SEND -> isSend
            ListPlaybackAdvance.AFTER_LOG -> true
        }
        if (shouldAdvance && state.value.isActive && state.value.hasNext) next()
    }

    /**
     * Drop the queued repeats of the climb just sent.
     *
     * Repeated attempts are consecutive entries carrying the same climb, which
     * is how the filler writes a set out. Only a participant's own view is
     * touched when they are not the host — the queue belongs to the host.
     */
    private fun skipRemainingAttemptsOfCurrentClimb() {
        val s = state.value
        if (!s.isActive || s.isParticipant) return
        val current = s.currentClimb ?: return
        val queue = s.queue
        var next = s.currentIndex + 1
        while (next < queue.size && queue[next].climbUuid == current.climbUuid) next++
        val repeats = next - s.currentIndex - 1
        if (repeats <= 0) return
        queueManager.removeRange(s.currentIndex + 1, next)
        // The rest that now follows is the one that separated two attempts on
        // the same problem — too short for what it has become, which is the
        // gap before a different problem. Carry over the rest the dropped
        // block ended on.
        queueManager.setRestAfter(s.currentIndex, queue[next - 1].restAfterSeconds)
    }

    /**
     * Start playing a playlist as HOST: session timer + queue bulk-load +
     * rest hook + optional GATT publication chosen for this run.
     */
    fun play(
        hostName: String,
        items: List<QueueItem>,
        advance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
        visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    ) {
        if (items.isEmpty()) return
        advanceMode = advance
        // A joinable playlist inside a BoardCell is canonical mesh state, not
        // a local queue that is published afterwards. Start it as a command
        // and let the canonical snapshot create the local session — that is
        // the same path a join, a reconnect and a controller handover take, so
        // there is one shape of "the playlist is running" instead of four.
        if (visibility == SessionVisibility.JOINABLE && canUseCanonicalPlaylist() &&
            gattBridge.startJoinablePlaylist(items, newSessionId())) {
            boardSessionManager.startSession()
            return
        }
        boardSessionManager.startSession()
        queueManager.loadPlaylist(hostName, items, visibility)
        if (visibility == SessionVisibility.JOINABLE) {
            gattBridge.startSharing()
        }
    }

    /**
     * Whether this device can put a playlist into the mesh at all.
     *
     * An API-28 device answers no and keeps the legacy GATT joinable path,
     * which is the whole hybrid: it can see, join and edit the one canonical
     * playlist through a gateway, but it cannot start or host one.
     */
    private fun canUseCanonicalPlaylist(): Boolean {
        val manager = com.cruxcoach.android.boardcell.BoardCellManager.current ?: return false
        val snapshot = manager.snapshot()
        return com.cruxcoach.android.boardcell.BoardCellPlatformPolicy.canStartCanonicalPlaylist(
            apiLevel = android.os.Build.VERSION.SDK_INT,
            cellIsActive = snapshot?.availability ==
                com.cruxcoach.android.boardcell.BoardCellAvailability.ACTIVE,
            localIsCellMember = snapshot != null && manager.localNodeId() in snapshot.members,
        )
    }

    private fun newSessionId(): Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

    /** Join the running joinable playlist of this BoardCell. */
    fun joinCanonicalPlaylist() {
        boardSessionManager.startSession()
        gattBridge.joinJoinablePlaylist()
    }

    /** The playlist host answers somebody else's request to start one. */
    fun decidePlaylistRequest(
        requestId: String,
        decision: com.cruxcoach.android.boardcell.BoardPlaylistProposalDecision,
    ) = gattBridge.decidePlaylistRequest(requestId, decision)

    /** Ask for the pending send to be tried again. Open to every member. */
    fun retryPlaylistProjection() = gattBridge.retryPlaylistProjection()

    /**
     * Start an ad-hoc session as HOST (browser session button). Seeds
     * the queue with the climb currently ON the board (the mini-player
     * banner shows it, so an empty player with "unknown climb" right after
     * would contradict what the user just saw lit on the wall).
     */
    fun startEmpty(
        hostName: String,
        visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    ) {
        advanceMode = ListPlaybackAdvance.MANUAL
        boardSessionManager.startSession()
        queueManager.startQueue(hostName, visibility)
        queueManager.onRestRequested = { seconds ->
            boardSessionManager.startRestTimer(seconds)
        }
        bleShareManager.uiState.value.onBoardClimb?.let { onBoard ->
            if (onBoard.climbUuid.isNotBlank()) {
                queueManager.addClimb(onBoard.climbUuid, onBoard.angle)
            }
        }
        if (visibility == SessionVisibility.JOINABLE) {
            gattBridge.startSharing()
        }
    }

    /** Join a nearby playlist as PARTICIPANT. */
    fun join(entry: NearbySessionEntry) {
        val device = entry.rawSession.device ?: return
        advanceMode = ListPlaybackAdvance.MANUAL
        boardSessionManager.startSession()
        // No startQueue() here: that would flash HOST before GATT connects;
        // joinSession() drives setConnecting() → setParticipantRole().
        gattBridge.joinSession(device)
    }

    /** Re-attempt a requested joinable publication after its platform gate
     * (normally BLUETOOTH_ADVERTISE) has been resolved. */
    fun retrySharing() {
        val current = state.value
        if (current.isHost &&
            current.visibilityRequested == SessionVisibility.JOINABLE &&
            current.visibility != SessionVisibility.JOINABLE
        ) {
            gattBridge.ensureHostSharing()
        }
    }

    /**
     * Stop playback — the end-vs-leave split that used to be duplicated
     * across BleStatusExpanded and the browser. Returns the finished
     * session row (for the summary) or null when nothing was recorded.
     *
     * @param endForEveryone host only: end the playlist for the whole group
     *   instead of handing it to the first participant.
     */
    fun stop(endForEveryone: Boolean = false): com.cruxcoach.data.repository.Board_sessions? {
        val queueState = queueManager.state.value
        val lastClimb = queueState.currentClimb
        val mesh = queueState.mesh
        if (mesh != null) {
            // Ending is only legal with one member left; otherwise leaving is
            // the way out, and the playlist carries on for everybody else. The
            // canonical state — not this device — decides which happened, so a
            // race with somebody joining cannot end a playlist under them.
            if (endForEveryone && mesh.canEnd) gattBridge.endJoinablePlaylist()
            else gattBridge.leaveJoinablePlaylist(successorId = preferredSuccessor(mesh))
            val finishedMesh = boardSessionManager.endSession()
            if (lastClimb != null) {
                bleShareManager.setLastClimbAfterSession(lastClimb.climbUuid, lastClimb.angle)
            }
            advanceMode = ListPlaybackAdvance.MANUAL
            return finishedMesh
        }
        if (queueState.role == SessionRole.HOST) {
            if (!endForEveryone && queueState.participantCount > 1) {
                // Keep the old host/session/board alive until the target emits
                // canonical HANDOVER_COMPLETED. The lifecycle callback performs
                // teardown and ends recording afterwards.
                queueManager.endQueue()
                return null
            }
            if (queueState.visibility == SessionVisibility.JOINABLE) {
                gattBridge.stopSharing(allowBoardRelease = true, endForEveryone = endForEveryone)
            }
            queueManager.endQueue(force = endForEveryone)
        } else {
            gattBridge.leaveSession()
        }
        val finished = boardSessionManager.endSession()
        // Keep "last on board" visible immediately (stopSharing's GATT
        // sentinel path does this too, but with a delay).
        if (lastClimb != null) {
            bleShareManager.setLastClimbAfterSession(lastClimb.climbUuid, lastClimb.angle)
        }
        advanceMode = ListPlaybackAdvance.MANUAL
        return finished
    }

    /**
     * Who a departing host hands the playlist to.
     *
     * The same longest-active member the canonical rule would pick anyway, so
     * an orderly leave and a lost host converge on the same successor and the
     * two paths cannot disagree.
     */
    private fun preferredSuccessor(mesh: MeshPlaylistView): String? =
        if (!mesh.isHost) null else mesh.members.firstOrNull { it != mesh.localNodeId }

    private companion object {
        /** Shares the CruxBLE prefix so one logcat filter covers the whole
         *  session path — bridge, queue and playback. */
        const val TAG = "CruxBLE/Playback"
    }
}
