package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ui.board.BoardSendModePolicy
import com.cruxcoach.android.ui.board.QueueDeliveryPolicy
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionQueueProtocol
import com.cruxcoach.android.boardcell.BoardCellScopeRegistry
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.mesh.MeshOwners
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardPlaylistInstant
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistRest
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.ActiveBoardCellWriteGateway
import com.cruxcoach.android.boardcell.BoardCellWriteGateway
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionRole { NONE, HOST, PARTICIPANT }

/** Whether the host publishes the current queue for nearby users. */
enum class SessionVisibility { LOCAL_ONLY, JOINABLE }

data class SessionParticipant(val deviceAddress: String, val displayName: String)

data class SessionQueueState(
    val role: SessionRole = SessionRole.NONE,
    val sessionId: Int = 0,
    val hostName: String = "",
    val participantCount: Int = 0,
    val participants: List<SessionParticipant> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    /**
     * What the user asked for, as opposed to [visibility], which is what is
     * currently in force.
     *
     * They part company when sharing cannot start — Bluetooth off, permission
     * missing, GATT server refused. Until now the wish was simply overwritten
     * with LOCAL_ONLY, and that quietly disabled the recovery path, which
     * begins `if (visibility != JOINABLE) return`. So a session started as
     * joinable while Bluetooth was off stayed local for ever, including after
     * the user turned Bluetooth on. Keeping the wish separate lets the recovery
     * ask what was wanted rather than what was achieved.
     */
    val visibilityRequested: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    /**
     * The current climb is not on the wall and will not go there on its own.
     *
     * Set only under the explicit send mode, where advancing deliberately does
     * not light the wall — someone may be climbing the projected problem while
     * you flip through the queue. The player turns its resend lamp into the
     * send button while this is true.
     */
    val awaitingExplicitSend: Boolean = false,
    /** This participant's position in the join order (0-based). Used for host election. */
    val participantIndex: Int = -1,
    /** A compatible external board app last wrote the physical board through CruxRelay. */
    val externalBoardOverride: Boolean = false,
    /** Physical scope; null only for a backwards-compatible unscoped session. */
    val physicalBoardId: String? = null,
    val boardCellId: String? = null,
    /**
     * Canonical joinable-playlist state, mirrored from the BoardCell snapshot.
     *
     * Non-null exactly while this device is looking at the one joinable
     * playlist of its BoardCell. Everything in it is read-only here: the
     * canonical copy lives in the mesh and this class is its UI projection,
     * so local edits travel as commands rather than mutating a second truth.
     */
    val mesh: MeshPlaylistView? = null,
) {
    val isActive: Boolean get() = role != SessionRole.NONE
    val currentClimb: QueueItem? get() = queue.getOrNull(currentIndex)
}

/**
 * The BoardCell's shared playlist as this device currently sees it.
 *
 * [members] are the *board* members: there is no separate playlist
 * membership, no host and nothing to join, so this carries no role at all.
 * What it does carry is [synchronized], because having a copy of the playlist
 * and being up to date with the group are different things and only one of
 * them is safe to act on.
 */
data class MeshPlaylistView(
    val localNodeId: String,
    val members: List<String>,
    /** A rest is running; the remaining value is counted locally — see
     *  [com.cruxcoach.android.boardcell.BoardPlaylistRest]. */
    val activeRest: BoardPlaylistRest? = null,
    val pendingProjection: BoardPlaylistPendingProjection? = null,
    /** False during a partition: the copy on screen may already be stale. */
    val synchronized: Boolean = true,
    /**
     * The selected entry is the one the board last confirmed.
     *
     * Selecting an entry and putting it on the wall are separate decisions —
     * stepping through the list must not take the wall from whoever is
     * climbing on it — so these two facts are tracked separately and shown as
     * two facts rather than collapsed into one.
     */
    val selectionOnBoard: Boolean = false,
    /**
     * The technical controller serializes edits and is the only writer to the
     * physical board. It is deliberately not a product role and nothing in the
     * UI presents it as one; the playback layer reads it only to decide
     * whether a local timer or a canonical command ends a rest.
     */
    val localIsController: Boolean = false,
)

/**
 * Manages the climb queue — works both solo (no BLE sharing) and as
 * the data source for [SessionGattBridge] when sharing is active.
 *
 * Follows the [BoardSessionManager] pattern:
 * - Plain Kotlin class (not a ViewModel)
 * - Own CoroutineScope with SupervisorJob
 * - Exposes StateFlow for UI binding
 * - Hilt @Singleton via AppModule
 */
class SessionQueueManager(
    private val bleConnection: BoardBleConnection,
    private val boardRepository: BoardRepository,
    private val climbNameResolver: ClimbNameResolver,
    private val userPreferences: UserPreferences,
    // Injectable for tests — production keeps the original Main-dispatched scope.
    // Tests MUST pass a scope they can cancel in @After, otherwise the internal
    // launchers (including a `withContext(Dispatchers.IO)` inside `state.collect`)
    // outlive `Dispatchers.resetMain()` and surface as UncaughtExceptionsBeforeTest
    // in whichever test runs next in the same JVM.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val fipsMeshRuntime: FipsMeshRuntime? = null,
    private val boardCellManager: BoardCellManager? = null,
    private val boardCellWriteGateway: BoardCellWriteGateway = ActiveBoardCellWriteGateway,
    /**
     * UTC wall clock, injectable so rest-synchronisation tests are exact
     * rather than sleeping and hoping.
     */
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "SessionQueueManager"
        private const val NO_CURRENT_CLIMB_INDEX = 0xFF
        private const val EXTERNAL_BOARD_OVERRIDE_FLAG = 0x01

        /**
         * Uses the legacy "no current climb" index plus a new flag byte. Older
         * clients see 0xFF and safely do nothing; current clients can still
         * distinguish an external board-app write from an empty queue.
         */
        fun isExternalBoardOverride(data: ByteArray): Boolean =
            data.size >= 2 &&
                (data[0].toInt() and 0xFF) == NO_CURRENT_CLIMB_INDEX &&
                (data[1].toInt() and 0xFF) == EXTERNAL_BOARD_OVERRIDE_FLAG
    }

    private val _state = MutableStateFlow(SessionQueueState())
    val state: StateFlow<SessionQueueState> = _state.asStateFlow()

    init {
        // Auto-send current queue climb when board connects during an active session.
        // This handles two scenarios:
        // 1. Session started before board connection (user starts queue, then connects)
        // 2. Board reconnects mid-session (BT toggle, distance, etc.)
        scope.launch {
            var wasConnected = false
            bleConnection.connectionState.collect { connState ->
                val isConnected = connState == ConnectionState.CONNECTED
                if (isConnected && !wasConnected &&
                    _state.value.role == SessionRole.HOST &&
                    _state.value.currentClimb != null
                ) {
                    Log.d(TAG, "Board connected during active session — sending current climb")
                    sendCurrentClimbToBoard()
                }
                wasConnected = isConnected
            }
        }
        // The canonical joinable playlist flows one way: mesh → local state.
        //
        // There used to be a second collector pushing the local HOST queue back
        // into BoardCellManager.replacePlaylist(). That call can only write on
        // the technical controller, so on a device that hosted the session but
        // was not the controller every playlist edit was silently dropped and
        // the canonical snapshot sat at playlistRevision=0 for ever. Local
        // edits now travel as authenticated commands (SessionGattBridge), and
        // this collector is the only writer of shared playlist state here —
        // which also removes the loop the two collectors formed.
        boardCellManager?.let { manager ->
            scope.launch {
                manager.snapshots.collect { snapshot -> applyCanonicalPlaylist(manager, snapshot) }
            }
        }
    }

    /**
     * Mirrors the canonical joinable playlist into the local UI/GATT state.
     *
     * Membership of the playlist — not of the mesh, and not the technical
     * controller role — decides whether this device follows it. A member that
     * has no session yet adopts one, so a start, a join, a process restart, an
     * anti-entropy repair and a controller handover all converge on the same
     * visible result without any of them needing their own code path.
     */
    private fun applyCanonicalPlaylist(
        manager: BoardCellManager,
        snapshot: com.cruxcoach.android.boardcell.BoardCellSnapshot?,
    ) {
        val current = _state.value
        if (snapshot == null) {
            if (current.mesh != null) leaveCanonicalPlaylist()
            return
        }
        if (snapshot.availability != com.cruxcoach.android.boardcell.BoardCellAvailability.ACTIVE) {
            if (current.mesh != null || current.boardCellId == snapshot.cellId.value) {
                freezeForController()
            }
            return
        }
        val playlist = snapshot.playlist
        val localNodeId = manager.localNodeId()
        if (snapshot.cellId.value != followedCellId) {
            // A different board group is a different playlist; whatever this
            // device decided about the last one says nothing about this one.
            followedCellId = snapshot.cellId.value
            stoppedFollowingSharedPlaylist = false
        }
        // An empty shared playlist is the board's resting state, not a running
        // session. Clearing it therefore ends the mirrored session everywhere,
        // and re-arms this device for the next thing anybody adds.
        if (playlist.entries.isEmpty()) stoppedFollowingSharedPlaylist = false
        if (localNodeId !in snapshot.members || playlist.entries.isEmpty() ||
            stoppedFollowingSharedPlaylist) {
            // Not in the BoardCell, nothing in its playlist, or this device
            // deliberately closed the player. A local-only playlist on this
            // device keeps running untouched; only a previously mirrored
            // shared one is torn down.
            if (current.mesh != null) leaveCanonicalPlaylist()
            return
        }
        // Board membership is playlist participation. There is nothing to
        // join, so a member that has no session yet simply adopts the one its
        // BoardCell already has — and a start, a join, a process restart, an
        // anti-entropy repair and a controller handover all converge on the
        // same visible result without any of them needing their own path.
        val canonicalItem = playlist.currentEntry()
        val selectionOnBoard = snapshot.projectionKnown && canonicalItem != null &&
            snapshot.projection?.let {
                it.climbUuid == canonicalItem.climbUuid && it.angle == canonicalItem.angle
            } == true
        val view = MeshPlaylistView(
            localNodeId = localNodeId,
            members = snapshot.members.sorted(),
            activeRest = playlist.activeRest,
            pendingProjection = playlist.pendingProjection,
            synchronized = manager.isPlaylistSynchronized(),
            localIsController = snapshot.controllerId == localNodeId,
            selectionOnBoard = selectionOnBoard,
        )
        val role = SessionRole.PARTICIPANT
        // Only a board this app did not write counts as an external override.
        // A selection nobody has sent yet is the ordinary resting state now,
        // not somebody else's app taking the wall.
        val externalBoardOverride = playlist.pendingProjection == null &&
            !selectionOnBoard && !snapshot.projectionKnown
        val adopting = !current.isActive || current.mesh == null
        if (adopting) {
            fipsMeshRuntime?.acquire(MeshOwners.SESSION.value)
            // Both are keyed by owner, so adopting on top of a local session
            // that already holds them is a no-op rather than a second claim.
            bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
            isPlaylistQueue = true
            // Reaching here means this device is in the BoardCell and the
            // board group has something in its playlist, which is all it takes
            // to be in it. A local-only queue that was running on this device
            // is therefore replaced by the group's list — the board is shared
            // hardware and its playlist is the shared thing on it.
            Log.d(TAG, "Adopting canonical playlist (entries=${playlist.entries.size}, " +
                "replacedLocalQueue=${current.isActive && current.mesh == null}, " +
                "localQueueSize=${current.queue.size})")
        }
        _state.update { state ->
            state.copy(
                role = role,
                sessionId = playlist.sessionId ?: state.sessionId,
                queue = playlist.entries.map {
                    QueueItem(it.climbUuid, it.angle, it.restAfterSeconds)
                },
                currentIndex = playlist.currentIndex,
                visibility = SessionVisibility.JOINABLE,
                visibilityRequested = SessionVisibility.JOINABLE,
                participantCount = snapshot.members.size,
                isConnecting = false,
                error = null,
                externalBoardOverride = externalBoardOverride,
                physicalBoardId = snapshot.physicalBoardId.value,
                boardCellId = snapshot.cellId.value,
                mesh = view,
            )
        }
        if (adopting) {
            onQueueChanged?.invoke()
            onCurrentClimbChanged?.invoke()
            onSessionInfoChanged?.invoke()
        }
        applyCanonicalRest(playlist.activeRest, publishesRestEnd = view.localIsController)
    }

    /**
     * Turns the canonical rest into this device's own countdown.
     *
     * The canonical state carries a UTC instant for the end of the rest, so a
     * device that joins 40 s into a two-minute pause counts down the remaining
     * 80 s rather than restarting the full two minutes — which is what a
     * duration-only rest did, and it left a late joiner resting while the rest
     * of the group had already gone back to the wall.
     *
     * [BoardPlaylistRest.generation] still separates a genuinely new rest from
     * a replay of the one this device already started, so an anti-entropy
     * repair or a reconnect that re-delivers the same state does not restart
     * the countdown.
     */
    private fun applyCanonicalRest(rest: BoardPlaylistRest?, publishesRestEnd: Boolean) {
        val previous = observedRestGeneration
        if (rest == null) {
            observedRestGeneration = null
            if (previous != null) onRestCleared?.invoke()
            return
        }
        val now = nowEpochMs()
        // A rest that has not begun yet, by this device's clock and beyond any
        // plausible skew, is not a rest to join — it is a wrong or hostile
        // clock on the arming device. Starting it anyway is what let a
        // far-future pause run its full length again on every process restart.
        if (rest.startsAfter(now, BoardPlaylistInstant.CLOCK_SKEW_TOLERANCE_MS)) {
            observedRestGeneration = rest.generation
            if (previous != null) onRestCleared?.invoke()
            Log.w(TAG, "Ignoring a canonical rest that starts in the future " +
                "(${rest.startedAtEpochMs - now} ms ahead)")
            if (publishesRestEnd) onCanonicalRestExpired?.invoke()
            return
        }
        val remaining = rest.remainingSeconds(now)
        if (remaining <= 0) {
            // Already over. Showing it as a fresh full pause would be a lie,
            // and leaving it running canonically would strand everyone behind
            // a countdown that has no time left in it.
            observedRestGeneration = rest.generation
            if (previous != null) onRestCleared?.invoke()
            if (publishesRestEnd) onCanonicalRestExpired?.invoke()
            return
        }
        observedRestGeneration = rest.generation
        if (rest.generation != previous) onRestRequested?.invoke(remaining)
    }

    /**
     * Whether this device can turn a canonical playlist entry into a physical
     * write, and with which projection semantics.
     *
     * Null means the climb is simply not in this device's database. There is
     * no peer climb transfer in this build, so that is a terminal answer for
     * this device and the canonical state says so rather than implying that a
     * fetch is under way.
     */
    fun resolveProjection(climbUuid: String, angle: Int): BoardProjection? {
        val climb = resolveClimb(climbUuid, angle) ?: return null
        return BoardProjection(climbUuid, angle,
            BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand))
    }

    /** The canonical playlist is empty, or this device is no longer in it. */
    private fun leaveCanonicalPlaylist() {
        Log.d(TAG, "Canonical playlist empty/left — clearing the mirrored session")
        finishQueue()
    }

    /**
     * Stop showing the board's shared playlist on this device.
     *
     * Purely local and purely about the screen: nothing canonical changes,
     * nobody else notices, and this device stays a full participant with every
     * editing right it had. It exists because closing the player has to close
     * the player — without it the next snapshot would immediately re-adopt the
     * playlist and the screen would reappear. Adding to the shared playlist
     * again, a clear, or moving to another board all re-arm it.
     */
    fun stopFollowingSharedPlaylist() {
        if (_state.value.mesh == null) return
        stoppedFollowingSharedPlaylist = true
        finishQueue()
    }

    /** The user acted on the shared playlist, so they want to see it again. */
    fun resumeFollowingSharedPlaylist() {
        stoppedFollowingSharedPlaylist = false
    }

    /** Resolved name of the current queue climb (null while loading or if not found). */
    private val _currentClimbName = MutableStateFlow<String?>(null)
    val currentClimbName: StateFlow<String?> = _currentClimbName.asStateFlow()

    /** Resolved display info (name + difficulty) of the current queue climb. */
    private val _currentClimbInfo = MutableStateFlow<ClimbDisplayInfo?>(null)
    val currentClimbInfo: StateFlow<ClimbDisplayInfo?> = _currentClimbInfo.asStateFlow()

    init {
        // Auto-resolve climb info whenever the current climb changes
        scope.launch {
            var lastUuid: String? = null
            state.collect { s ->
                val uuid = s.currentClimb?.climbUuid
                if (uuid == lastUuid) return@collect
                lastUuid = uuid
                if (uuid == null) {
                    _currentClimbName.value = null
                    _currentClimbInfo.value = null
                    return@collect
                }
                val info = withContext(Dispatchers.IO) {
                    climbNameResolver.resolveInfo(uuid, s.currentClimb!!.angle)
                }
                _currentClimbName.value = info?.name
                _currentClimbInfo.value = info
            }
        }
    }

    /** Listener for queue changes — set by SessionGattBridge to push updates to clients. */
    @Volatile var onQueueChanged: (() -> Unit)? = null
    @Volatile var onCurrentClimbChanged: (() -> Unit)? = null
    @Volatile var onParticipantsChanged: (() -> Unit)? = null
    @Volatile var onSessionInfoChanged: (() -> Unit)? = null

    /** Remote command sender — set by SessionGattBridge for participant mode.
     *  When set, addClimb/removeClimb/etc. send commands to host instead of mutating locally. */
    @Volatile var remoteAddClimb: ((climbUuid: String, angle: Int) -> Unit)? = null
    /** Routes an edit of a not-yet-mirrored shared playlist into the mesh. */
    @Volatile var addToSharedPlaylist: ((List<QueueItem>) -> Boolean)? = null

    // ===== Queue operations (work in all modes) =====

    fun startQueue(
        hostName: String = "",
        visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    ) {
        if (_state.value.isActive) return
        val sessionId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        _state.update { SessionQueueState(
            role = SessionRole.HOST,
            sessionId = sessionId,
            hostName = hostName,
            participantCount = 1,  // host counts as 1
            visibility = visibility,
            visibilityRequested = visibility,
            physicalBoardId = BoardCellScopeRegistry.selected.value?.value,
            boardCellId = BoardCellScopeRegistry.selectedCellId()?.value,
        ) }
        fipsMeshRuntime?.acquire(MeshOwners.SESSION.value)
        bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
        Log.d(TAG, "Queue started (sessionId=$sessionId, hostName=$hostName)")
    }

    fun endQueue(force: Boolean = false): Boolean {
        lastSentClimbKey = null
        val prev = _state.value
        if (!force && prev.role == SessionRole.HOST && prev.boardCellId != null && prev.participantCount > 1) {
            val successor = boardCellManager?.soleSuccessor()
            if (successor == null || boardCellManager.requestOrderlyHandover(successor).not()) {
                Log.w(TAG, "endQueue refused: no unique explicit BoardCell successor is ready for handover")
                return false
            }
            // The canonical completion callback ends the old host. Until then
            // the source must keep GATT, board keep-alive and write authority.
            return false
        }
        finishQueue()
        return true
    }

    /** Invoked only after the new controller canonically completed takeover. */
    fun completeTransferredQueue() {
        finishQueue()
    }

    private fun finishQueue() {
        lastSentClimbKey = null
        val prev = _state.value
        Log.d(TAG, "endQueue() called, role=${prev.role}, queue=${prev.queue.size}, " +
            "participants=${prev.participants.size}, " +
            "callbacks: onQueue=${onQueueChanged != null}, onParticipants=${onParticipantsChanged != null}")
        _state.update { SessionQueueState() }
        onQueueChanged = null
        onCurrentClimbChanged = null
        onParticipantsChanged = null
        onSessionInfoChanged = null
        onFirstQueueClimbSent = null
        remoteAddClimb = null
        // The rest hooks survive: a canonical playlist can be adopted without
        // anybody calling play() — a join, a process restart or a host
        // handover all arrive as snapshots — and a session that cleared them
        // would then count its pauses down in silence.
        observedRestGeneration = null
        isPlaylistQueue = false
        bleConnection.releaseKeepAlive(BoardConnectionOwner.SESSION)
        fipsMeshRuntime?.release(MeshOwners.SESSION.value)
        Log.d(TAG, "endQueue(): complete, state reset to NONE")
    }

    /**
     * Bulk-loads a playlist into the queue (replacing any existing items)
     * and sends the first climb. Starts the queue as HOST if none is
     * active. Rest blocks ride along as [QueueItem.restAfterSeconds] so
     * they survive reorder/remove, and [isPlaylistQueue] suppresses the
     * session-start nearby-climb auto-import — foreign climbs must not be
     * injected into a planned training session.
     */
    fun loadPlaylist(
        hostName: String,
        items: List<QueueItem>,
        visibility: SessionVisibility = SessionVisibility.LOCAL_ONLY,
    ) {
        if (items.isEmpty()) return
        if (!_state.value.isActive) {
            startQueue(hostName, visibility)
        }
        isPlaylistQueue = true
        lastSentClimbKey = null
        _state.update { it.copy(queue = items, currentIndex = 0) }
        onQueueChanged?.invoke()
        onCurrentClimbChanged?.invoke()
        // Starting a playlist *is* the explicit action — the second case the
        // parameter's own doc names, and until now the one with no caller.
        // Without it, EXPLICIT mode left the wall dark after "Play" and asked
        // for the lamp on top of the tap that started the thing.
        sendCurrentClimbToBoard(explicitRequest = true)
        Log.d(TAG, "loadPlaylist: ${items.size} items, host=$hostName")
    }

    fun addClimb(climbUuid: String, angle: Int) {
        val before = _state.value
        if (boardCellManager?.isCellMember() == true &&
            (before.mesh != null || before.visibilityRequested == SessionVisibility.JOINABLE)) {
            // The BoardCell already owns a playlist; this device just has not
            // mirrored it yet. Adding to the shared one is the only correct
            // reading of "add" here — starting a second, private list beside
            // it is what used to make one member's queue invisible to the rest.
            val shared = addToSharedPlaylist
            resumeFollowingSharedPlaylist()
            if (shared != null && shared(listOf(QueueItem(climbUuid, angle)))) {
                Log.d(TAG, "Climb routed to the shared BoardCell playlist")
                return
            }
        }
        // Participants send via GATT to host instead of mutating locally.
        // When remoteAddClimb is null (GATT disconnected during host migration),
        // fall through to local add so the climb isn't silently dropped.
        if (_state.value.role == SessionRole.PARTICIPANT) {
            val remote = remoteAddClimb
            if (remote != null) {
                Log.d(TAG, "addClimb as PARTICIPANT → routing via GATT")
                remote.invoke(climbUuid, angle)
                return
            }
            Log.d(TAG, "addClimb as PARTICIPANT → GATT disconnected (migration), adding locally")
        }
        Log.d(TAG, "addClimb: uuid=${climbUuid.take(8)} angle=$angle")
        val prevCurrentClimb = _state.value.currentClimb
        _state.update { s ->
            val newQueue = s.queue + QueueItem(climbUuid, angle)
            val newIndex = if (s.currentIndex < 0) 0 else s.currentIndex
            s.copy(queue = newQueue, currentIndex = newIndex)
        }
        onQueueChanged?.invoke()
        // If the current climb changed (e.g. first item added, index went from -1 to 0),
        // fire onCurrentClimbChanged so session advertising updates the climb immediately.
        val newCurrentClimb = _state.value.currentClimb
        if (newCurrentClimb != prevCurrentClimb) {
            onCurrentClimbChanged?.invoke()
        }
        // If this is the first climb, send to board
        if (_state.value.queue.size == 1) {
            sendCurrentClimbToBoard()
        }
    }

    fun removeClimb(index: Int) {
        val prevCurrentClimb = _state.value.currentClimb
        _state.update { s ->
            if (index < 0 || index >= s.queue.size) return@update s
            val newQueue = s.queue.toMutableList().apply { removeAt(index) }
            val newIndex = when {
                newQueue.isEmpty() -> -1
                index < s.currentIndex -> s.currentIndex - 1
                index == s.currentIndex -> s.currentIndex.coerceAtMost(newQueue.size - 1)
                else -> s.currentIndex
            }
            s.copy(queue = newQueue, currentIndex = newIndex)
        }
        onQueueChanged?.invoke()
        val newCurrentClimb = _state.value.currentClimb
        if (newCurrentClimb != prevCurrentClimb) {
            onCurrentClimbChanged?.invoke()
        }
        sendCurrentClimbToBoard()
    }

    /**
     * Drop entries [fromInclusive] until [toExclusive].
     *
     * Used when a send makes the remaining scheduled tries of that problem
     * pointless. Only ever removes entries AFTER the current one, so the
     * current index does not move and the board keeps showing what it shows.
     */
    fun removeRange(fromInclusive: Int, toExclusive: Int) {
        if (toExclusive <= fromInclusive) return
        _state.update { s ->
            val from = fromInclusive.coerceIn(0, s.queue.size)
            val to = toExclusive.coerceIn(from, s.queue.size)
            if (from <= s.currentIndex) return@update s
            s.copy(queue = s.queue.toMutableList().apply { subList(from, to).clear() })
        }
        onQueueChanged?.invoke()
    }

    /**
     * Replace the rest that follows [index].
     *
     * Used when dropping the remaining attempts of a problem that was sent
     * early: what is left standing is the short attempt rest, but the gap it
     * now spans is the one before a different problem.
     */
    fun setRestAfter(index: Int, seconds: Int) {
        _state.update { s ->
            if (index !in s.queue.indices) return@update s
            if (s.queue[index].restAfterSeconds == seconds) return@update s
            s.copy(
                queue = s.queue.toMutableList().apply {
                    this[index] = this[index].copy(restAfterSeconds = seconds)
                }
            )
        }
        onQueueChanged?.invoke()
    }

    fun setCurrentClimb(index: Int) {
        lastSentClimbKey = null // reset dedup so the new climb gets sent
        _state.update { s ->
            if (index < 0 || index >= s.queue.size) return@update s
            s.copy(currentIndex = index)
        }
        onCurrentClimbChanged?.invoke()
        sendCurrentClimbToBoard()
    }

    fun nextClimb() {
        val s = _state.value
        if (s.currentIndex < s.queue.size - 1) {
            // Playlist pacing: the climb we're leaving may carry a planned
            // rest block — arm the rest timer on sequential advance only
            // (jumping around via setCurrentClimb is a user override).
            val restSeconds = s.currentClimb?.restAfterSeconds ?: 0
            setCurrentClimb(s.currentIndex + 1)
            if (restSeconds > 0) {
                onRestRequested?.invoke(restSeconds)
            }
        }
    }

    fun previousClimb() {
        val s = _state.value
        if (s.currentIndex > 0) {
            setCurrentClimb(s.currentIndex - 1)
        }
    }

    fun moveClimb(from: Int, to: Int) {
        _state.update { s ->
            if (from < 0 || from >= s.queue.size || to < 0 || to >= s.queue.size || from == to) return@update s
            val newQueue = s.queue.toMutableList().apply {
                add(to, removeAt(from))
            }
            // Adjust currentIndex to follow the current climb
            val newIndex = when (s.currentIndex) {
                from -> to
                in minOf(from, to)..maxOf(from, to) -> {
                    if (from < to) s.currentIndex - 1 else s.currentIndex + 1
                }
                else -> s.currentIndex
            }
            s.copy(queue = newQueue, currentIndex = newIndex)
        }
        onQueueChanged?.invoke()
    }

    fun clearQueue() {
        val prevCurrentClimb = _state.value.currentClimb
        _state.update { it.copy(queue = emptyList(), currentIndex = -1) }
        onQueueChanged?.invoke()
        if (prevCurrentClimb != null) {
            onCurrentClimbChanged?.invoke()
        }
    }

    // ===== Participant management (called by SessionGattBridge) =====

    /** Adds a participant and returns their assigned index (join order).
     *  If the device already exists (re-join without disconnect), updates the name. */
    fun addParticipant(deviceAddress: String, displayName: String): Int {
        var assignedIndex = -1
        _state.update { s ->
            val existingIndex = s.participants.indexOfFirst { it.deviceAddress == deviceAddress }
            if (existingIndex >= 0) {
                // Re-join: update name, keep position
                Log.d(TAG, "Re-join from $deviceAddress (already at index $existingIndex)")
                assignedIndex = existingIndex
                val updated = s.participants.toMutableList()
                updated[existingIndex] = SessionParticipant(deviceAddress, displayName)
                return@update s.copy(participants = updated)
            }
            val newParticipants = s.participants + SessionParticipant(deviceAddress, displayName)
            assignedIndex = newParticipants.size - 1
            // +1 to include the host in the total count
            s.copy(
                participants = newParticipants,
                participantCount = newParticipants.size + 1
            )
        }
        Log.d(TAG, "addParticipant: $displayName, total=${_state.value.participantCount}")
        onParticipantsChanged?.invoke()
        return assignedIndex
    }

    fun removeParticipant(deviceAddress: String) {
        Log.d(TAG, "removeParticipant: $deviceAddress")
        _state.update { s ->
            val newParticipants = s.participants.filter { it.deviceAddress != deviceAddress }
            Log.d(TAG, "Participants after removal: ${newParticipants.size + 1} total")
            // +1 to include the host in the total count
            s.copy(
                participants = newParticipants,
                participantCount = newParticipants.size + 1
            )
        }
        onParticipantsChanged?.invoke()
    }

    /** Apply full state from host (used by participants after initial sync). */
    fun applyRemoteState(currentIndex: Int, items: List<QueueItem>) {
        _state.update {
            if (it.mesh != null) it else it.copy(queue = items, currentIndex = currentIndex)
        }
    }

    /**
     * Apply current index change from host notification.
     *
     * An index the local queue does not have yet is dropped: the host has
     * already moved on but our copy of the queue is still the older, shorter
     * one, and pointing currentIndex at a climb we cannot name would show the
     * wrong thing. It self-heals — the host sends the full queue with its own
     * currentIndex on the next push ([applyRemoteState]).
     *
     * Logged because the participant simply stops following while it lasts,
     * which from the outside is indistinguishable from "the button does
     * nothing".
     */
    fun applyRemoteCurrentIndex(index: Int) {
        _state.update { s ->
            if (s.mesh != null) return@update s
            if (index in s.queue.indices) {
                s.copy(currentIndex = index, externalBoardOverride = false)
            } else {
                Log.w(
                    TAG,
                    "applyRemoteCurrentIndex: dropping index $index — local queue " +
                        "holds ${s.queue.size} item(s); waiting for the next full push",
                )
                s
            }
        }
    }

    /** Marks a successful raw relay write whose climb ID is unknown to CruxCoach. */
    fun markExternalBoardWrite() {
        if (_state.value.role != SessionRole.HOST) return
        lastSentClimbKey = null
        _state.update { it.copy(externalBoardOverride = true) }
        onCurrentClimbChanged?.invoke()
    }

    /** Applies the host's external-write marker without touching the physical board. */
    fun applyRemoteExternalBoardWrite() {
        _state.update { if (it.mesh != null) it else it.copy(externalBoardOverride = true) }
    }

    fun setParticipantRole(sessionId: Int, hostName: String) {
        _state.update { it.copy(
            role = SessionRole.PARTICIPANT,
            sessionId = sessionId,
            hostName = hostName,
            isConnecting = false,
            visibility = SessionVisibility.JOINABLE,
        ) }
        boardCellManager?.freezeLegacyParticipantWrites()
    }

    /** The state in force. Does not touch [SessionQueueState.visibilityRequested]. */
    fun setVisibility(visibility: SessionVisibility) {
        _state.update { state ->
            if (state.role == SessionRole.HOST) state.copy(visibility = visibility) else state
        }
    }

    /** What the user asked for — set when they choose, never by a failure. */
    fun setVisibilityRequested(visibility: SessionVisibility) {
        _state.update { state ->
            if (state.role == SessionRole.HOST) {
                state.copy(visibility = visibility, visibilityRequested = visibility)
            } else {
                state
            }
        }
    }

    /** Promote this participant to host, keeping all queue data intact. */
    fun promoteToHost(hostName: String) {
        val newSessionId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        _state.update { it.copy(
            role = SessionRole.HOST,
            sessionId = newSessionId,
            hostName = hostName,
            participants = emptyList(),
            participantCount = 1,  // just the host
            isConnecting = false,
            error = null
        ) }
        bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
        Log.d(TAG, "Promoted to host (sessionId=$newSessionId, queue=${_state.value.queue.size} items)")
    }

    /** BoardCell handover keeps the canonical session id and complete queue. */
    fun promoteToHostForBoardCell(hostName: String) {
        _state.update { it.copy(
            role = SessionRole.HOST,
            hostName = hostName,
            participants = emptyList(),
            participantCount = 1,
            isConnecting = false,
            error = null,
        ) }
        bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
    }

    fun freezeForController() {
        _state.update { it.copy(error = "board_cell_controller_unreachable", isConnecting = false) }
    }

    /** Update session info from host notification (participant side).
     *  The count from the host already includes the host (+1). */
    fun updateSessionInfo(hostName: String, participantCount: Int,
        physicalBoardId: String? = null, boardCellId: String? = null,
        awaitingExplicitSend: Boolean = false): Boolean {
        val selected = BoardCellScopeRegistry.selected.value?.value
        val current = _state.value
        val mismatch = (physicalBoardId == null || boardCellId == null).let { unscoped ->
            unscoped && !BoardCellScopeRegistry.acceptsLegacyUnscoped()
        } || (selected != null && physicalBoardId != null && selected != physicalBoardId) ||
            (current.physicalBoardId != null && physicalBoardId != null && current.physicalBoardId != physicalBoardId) ||
            (current.boardCellId != null && boardCellId != null && current.boardCellId != boardCellId)
        if (mismatch) {
            Log.w(TAG, "Rejected session info for a different/ambiguous board cell")
            return false
        }
        if (physicalBoardId != null && boardCellId != null) {
            BoardCellScopeRegistry.joinCell(PhysicalBoardId(physicalBoardId), BoardCellId(boardCellId))
        }
        Log.d(TAG, "updateSessionInfo: hostName=$hostName, participantCount=$participantCount")
        _state.update { it.copy(
            hostName = hostName,
            participantCount = participantCount,
            physicalBoardId = physicalBoardId ?: it.physicalBoardId,
            boardCellId = boardCellId ?: it.boardCellId,
            awaitingExplicitSend = awaitingExplicitSend,
        ) }
        return true
    }

    /** Apply participant list from host notification (participant side).
     *  Updates the displayed list and recalculates our own index.
     *  Does NOT update participantCount — that comes solely from [updateSessionInfo]. */
    fun applyRemoteParticipants(names: List<String>) {
        Log.d(TAG, "applyRemoteParticipants: ${names.size} names: $names")
        _state.update { s ->
            if (s.mesh != null) return@update s
            val participants = names.mapIndexed { i, name ->
                SessionParticipant(deviceAddress = "remote-$i", displayName = name)
            }
            // Our index = our position in the list (best guess: use stored index if valid)
            val myIndex = if (s.participantIndex in names.indices) s.participantIndex
                else (names.size - 1).coerceAtLeast(0)
            s.copy(
                participants = participants,
                participantIndex = myIndex
            )
        }
    }

    fun setParticipantIndex(index: Int) {
        _state.update { it.copy(participantIndex = index) }
        Log.d(TAG, "Participant index set to $index")
    }

    fun setConnecting() {
        Log.d(TAG, "setConnecting(), current role=${_state.value.role}")
        _state.update { it.copy(isConnecting = true, error = null) }
    }

    fun setError(message: String) {
        _state.update { it.copy(isConnecting = false, error = message) }
    }

    // ===== Board control =====

    /** Called when a queue climb is first sent to the board — clears last-projected-climb
     *  banner and any stale climb advertising state. Set by SessionGattBridge. */
    @Volatile var onFirstQueueClimbSent: (() -> Unit)? = null

    /** Playlist rest hook: invoked with the planned rest seconds when the
     *  queue advances past a climb carrying [QueueItem.restAfterSeconds].
     *  Wired to BoardSessionManager.startRestTimer by the play glue. */
    @Volatile var onRestRequested: ((Int) -> Unit)? = null

    /** The canonical rest ended — it ran out, or somebody skipped it. */
    @Volatile var onRestCleared: (() -> Unit)? = null

    /**
     * A canonical rest whose end has already passed is still recorded.
     *
     * Invoked on one device only — the technical controller — so the group
     * does not race to publish the same end.
     */
    @Volatile var onCanonicalRestExpired: (() -> Unit)? = null

    /** Generation of the canonical rest this device has already started. */
    private var observedRestGeneration: Long? = null

    /**
     * This device closed the shared playlist's player and does not want it
     * back on screen until it acts on it again. Local display state only — see
     * [stopFollowingSharedPlaylist].
     */
    @Volatile private var stoppedFollowingSharedPlaylist = false

    /** Which board group the flag above was decided about. */
    @Volatile private var followedCellId: String? = null

    /** True while the queue content came from a playlist — suppresses the
     *  session-start nearby-climb auto-import (SessionGattBridge). */
    @Volatile var isPlaylistQueue: Boolean = false
        private set

    /** Tracks last sent climb to prevent duplicate sends from multiple callers. */
    private var lastSentClimbKey: String? = null

    /** Serializes BLE sends: rapid next/next/next queues callers up, but
     *  each one reads the LATEST current climb when it runs and the dedup
     *  key skips the stale ones — effectively latest-wins without ever
     *  cancelling an in-flight GATT write. */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** Force re-send of the current climb — the "board shows something
     *  else" escape hatch (someone re-lit the wall from another device;
     *  our dedup would otherwise skip the identical key). */
    fun resendCurrentClimb() {
        lastSentClimbKey = null
        sendCurrentClimbToBoard(explicitRequest = true)
    }

    /**
     * @param explicitRequest true when the user asked for it — the lamp, or the
     *   first send of a freshly loaded queue. Advancing does not count: under
     *   the explicit send mode the wall stays as it is until asked.
     */
    fun sendCurrentClimbToBoard(explicitRequest: Boolean = false) {
        scope.launch {
            sendMutex.withLock {
                // Read state inside the lock so queued navigation events resolve
                // to the latest selection and collapse to one physical write.
                val queueState = _state.value
                val item = queueState.currentClimb ?: return@withLock

                // A joinable playlist is projected by the technical BoardCell
                // controller, which serializes it for everybody and records
                // whether it reached the wall. Sending from here as well would
                // either be refused (this device is not the controller) or race
                // the canonical write with an identical one.
                if (queueState.mesh != null) {
                    Log.d(TAG, "sendCurrentClimbToBoard: canonical playlist projects via BoardCell")
                    return@withLock
                }

                // Participants only mutate the host queue via GATT; the host is
                // the sole writer to the physical board. Both that and the
                // connection are settled by the policy before the send mode is
                // even read — see QueueDeliveryPolicy for why the order matters.
                //
                // The mode itself: advancing through a queue used to light the
                // wall regardless, while the settings text claimed the opposite
                // ("queues always use an explicit action") and this class did
                // not so much as mention BoardSendMode. Under EXPLICIT the wall
                // now stays put and the player offers the lamp — someone may be
                // on the projected problem while the next one is lined up, and
                // nothing here can see that: a climber is not a BLE client.
                when (
                    QueueDeliveryPolicy.decide(
                        isHost = queueState.role == SessionRole.HOST,
                        boardConnected =
                            bleConnection.connectionState.value == ConnectionState.CONNECTED,
                        sendMode = resolveSendMode(),
                        explicitRequest = explicitRequest,
                    )
                ) {
                    QueueDeliveryPolicy.Decision.NONE -> {
                        Log.d(TAG, "sendCurrentClimbToBoard: skipped - role=${queueState.role}")
                        return@withLock
                    }
                    QueueDeliveryPolicy.Decision.AWAIT_EXPLICIT -> {
                        val changed = !_state.value.awaitingExplicitSend
                        _state.update { it.copy(awaitingExplicitSend = true) }
                        if (changed) onSessionInfoChanged?.invoke()
                        Log.d(TAG, "sendCurrentClimbToBoard: waiting for an explicit send")
                        return@withLock
                    }
                    QueueDeliveryPolicy.Decision.SEND -> Unit
                }

            // Dedup: don't re-send the same climb (multiple callers can trigger this)
            val key = "${item.climbUuid}:${item.angle}"
            if (key == lastSentClimbKey) {
                Log.d(TAG, "sendCurrentClimbToBoard: skipped dedup ${item.climbUuid.take(8)}")
                return@withLock
            }

            try {
                val climb = resolveClimb(item.climbUuid, item.angle)
                if (climb == null) {
                    Log.w(TAG, "Climb not found: ${item.climbUuid}")
                    return@withLock
                }
                // Board-match guard against the CONNECTED board (when known):
                // switching the active board in Settings never disconnects, so
                // the queue could otherwise push wrong-brand frames to the
                // board still on the link (e.g. a MoonBoard ASCII frame to an
                // Aurora board). Skip without marking lastSentClimbKey so the
                // climb is retried once the matching board (re)connects.
                val connectedBrand = bleConnection.connectedBoardBrand.value
                if (connectedBrand != null && climb.brand != connectedBrand) {
                    Log.w(TAG, "sendCurrentClimbToBoard: skipped — climb brand " +
                        "${climb.brand} != connected board $connectedBrand")
                    return@withLock
                }
                // Brand-aware transport: a MoonBoard climb sends an ASCII
                // frames payload via the Nordic-UART path, not the Kilter
                // placement→LED map. Resolve the variant from the climb's own
                // layout (the queue can hold any active-board climb).
                if (climb.brand == BoardBrand.MOONBOARD) {
                    if (climb.frames.isBlank()) return@withLock
                    val variant = com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(climb.layoutId)
                        ?: com.cruxcoach.domain.board.MoonBoardVariant.MOONBOARD_2016
                    val sent = boardCellWriteGateway.project(
                        BoardProjection(item.climbUuid, item.angle,
                            BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand))) {
                            bleConnection.sendMoonBoardClimb(
                                climb.frames,
                                variant,
                                userPreferences.moonBoardLedMode.first(),
                            )
                        }
                    if (sent) {
                        markCurrentClimbProjected(key)
                        Log.d(TAG, "sendCurrentClimbToBoard: sent MoonBoard ${item.climbUuid.take(8)} angle=${item.angle}")
                    }
                    return@withLock
                }
                val holds = BoardClimbParser.parseFrames(climb.frames)
                if (holds.isEmpty()) return@withLock
                val productSizeId = userPreferences.boardProductSizeId.first()
                // Brand-scope the LED map + colours, keyed off the CLIMB's own
                // brand (mirrors BoardSendController). Aurora boards reuse
                // Kilter's product_size ids, so the no-brand default would load
                // Kilter's LED partition and the wrong per-board colours.
                val brandWire = climb.brand.wireValue
                val ledMap = boardRepository.getPlacementLedMap(productSizeId, brandWire)
                val roleColors = boardRepository.getRoleColorMapForBrand(brandWire).ifEmpty {
                    (if (climb.brand == BoardBrand.KILTER) userPreferences.ledHoldColors.first()
                     else LedHoldColors.standardFor(climb.brand)).toRoleColorMap()
                }
                val sent = boardCellWriteGateway.project(
                    BoardProjection(item.climbUuid, item.angle,
                        BoardProjectionPolicy.projectionSurvivesDisconnect(climb.brand))) {
                        bleConnection.sendClimb(holds, ledMap, roleColors)
                    }
                if (sent) {
                    markCurrentClimbProjected(key)
                    Log.d(TAG, "sendCurrentClimbToBoard: sent ${item.climbUuid.take(8)} angle=${item.angle}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send climb to board", e)
            }
            }
        }
    }

    /**
     * The mode in force for the controller we are connected to, resolved the
     * same way the climb detail screen resolves it.
     */
    private suspend fun resolveSendMode(): BoardSendMode = runCatching {
        resolveSendModeOrThrow()
    }.getOrElse {
        // A mode we cannot read is not evidence that the user wants to press a
        // button. Sending is what the queue is for, and a preference lookup
        // that fails must not leave the wall dark with nothing on screen to
        // explain it.
        Log.w(TAG, "Could not resolve send mode — sending", it)
        BoardSendMode.AUTOMATIC
    }

    private suspend fun resolveSendModeOrThrow(): BoardSendMode = BoardSendModePolicy.resolve(
        connectionCapacity = BoardControllerProfiles
            .forBoard(bleConnection.connectedBoard).connectionCapacity,
        singleConnectionMode = userPreferences.singleConnectionBoardSendMode.first(),
        multiConnectionMode = userPreferences.multiConnectionBoardSendMode.first(),
        // A playlist is driven by its host. Participants do not turn a
        // physically single-connect controller into the generic relay case:
        // the host's preference for the actual controller capacity decides.
        hostingForOthers = false,
    )

    private fun markCurrentClimbProjected(key: String) {
        lastSentClimbKey = key
        // Deliberately does not write the queue into BoardCell any more. A
        // successful physical send is a projection, not a playlist edit, and
        // publishing the local queue from here made every send of a purely
        // local playlist look like a shared one.
        val changed = _state.value.awaitingExplicitSend
        _state.update { it.copy(awaitingExplicitSend = false) }
        if (changed) onSessionInfoChanged?.invoke()
        val hadExternalOverride = _state.value.externalBoardOverride
        if (hadExternalOverride) {
            _state.update { it.copy(externalBoardOverride = false) }
            onCurrentClimbChanged?.invoke()
        }
        onFirstQueueClimbSent?.invoke()
        onFirstQueueClimbSent = null
    }

    /**
     * Look up a climb by UUID, tolerating format differences between the GATT protocol
     * (uppercase-no-hyphens, e.g. `305ECF354AB54C9CAFD591AF0848004B`) and the database
     * (lowercase-with-hyphens, e.g. `305ecf35-4ab5-4c9c-afd5-91af0848004b`).
     */
    private fun resolveClimb(uuid: String, angle: Int): com.cruxcoach.data.repository.ClimbWithStats? {
        boardRepository.getClimbByUuid(uuid, angle)?.let { return it }
        boardRepository.getClimbByUuid(uuid.lowercase(), angle)?.let { return it }
        // Protocol decodes as uppercase-no-hyphens; DB may store lowercase-with-hyphens
        val bare = uuid.replace("-", "")
        if (bare.length == 32) {
            val hyphenated = "${bare.substring(0,8)}-${bare.substring(8,12)}-" +
                "${bare.substring(12,16)}-${bare.substring(16,20)}-${bare.substring(20)}"
            boardRepository.getClimbByUuid(hyphenated.lowercase(), angle)?.let { return it }
            boardRepository.getClimbByUuid(hyphenated.uppercase(), angle)?.let { return it }
        }
        return null
    }

    /** Resolve and encode a mesh participant's abstract projection on the
     * controller. Raw board frames never need to cross the mesh. */
    suspend fun writeProjectionToPhysical(projection: BoardProjection): Boolean {
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return false
        val climb = resolveClimb(projection.climbUuid, projection.angle) ?: return false
        val connectedBrand = bleConnection.connectedBoardBrand.value
        if (connectedBrand != null && connectedBrand != climb.brand) return false
        return if (climb.brand == BoardBrand.MOONBOARD) {
            if (climb.frames.isBlank()) false else {
                val variant = com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(climb.layoutId)
                    ?: com.cruxcoach.domain.board.MoonBoardVariant.MOONBOARD_2016
                bleConnection.sendMoonBoardClimb(
                    climb.frames,
                    variant,
                    userPreferences.moonBoardLedMode.first(),
                )
            }
        } else {
            val holds = BoardClimbParser.parseFrames(climb.frames)
            if (holds.isEmpty()) return false
            val productSizeId = userPreferences.boardProductSizeId.first()
            val brandWire = climb.brand.wireValue
            val ledMap = boardRepository.getPlacementLedMap(productSizeId, brandWire)
            if (ledMap.isEmpty()) return false
            val roleColors = boardRepository.getRoleColorMapForBrand(brandWire).ifEmpty {
                (if (climb.brand == BoardBrand.KILTER) userPreferences.ledHoldColors.first()
                else LedHoldColors.standardFor(climb.brand)).toRoleColorMap()
            }
            bleConnection.sendClimb(holds, ledMap, roleColors)
        }
    }

    // ===== Protocol helpers for SessionGattBridge =====

    /** Page 0 — what a plain characteristic read gets; its header says how
     *  many pages follow over notifications. */
    fun encodeQueueState(): ByteArray {
        val s = _state.value
        return SessionQueueProtocol.encodeQueueState(s.currentIndex, s.queue)
    }

    /** Every page, in order. A queue past 29 items does not fit one frame. */
    fun encodeQueueStatePages(): List<ByteArray> {
        val s = _state.value
        return (0 until SessionQueueProtocol.queueStatePageCount(s.queue.size)).map { page ->
            SessionQueueProtocol.encodeQueueState(s.currentIndex, s.queue, page)
        }
    }

    fun encodeSessionInfo(): ByteArray {
        val s = _state.value
        return SessionQueueProtocol.encodeSessionInfo(s.hostName, s.participantCount,
            s.physicalBoardId, s.boardCellId, s.awaitingExplicitSend)
    }

    fun encodeParticipantList(): ByteArray {
        return SessionQueueProtocol.encodeParticipantList(
            _state.value.participants.map { it.displayName }
        )
    }

    fun encodeCurrentClimb(): ByteArray {
        val state = _state.value
        if (state.externalBoardOverride) {
            return byteArrayOf(
                NO_CURRENT_CLIMB_INDEX.toByte(),
                EXTERNAL_BOARD_OVERRIDE_FLAG.toByte(),
            )
        }
        val item = state.currentClimb
        return if (item != null) {
            // Only byte 0 (the index) is read on the other side; the single
            // item rides along on the queue-state layout.
            SessionQueueProtocol.encodeQueueState(state.currentIndex, listOf(item))
        } else {
            byteArrayOf(NO_CURRENT_CLIMB_INDEX.toByte(), 0)
        }
    }
}
