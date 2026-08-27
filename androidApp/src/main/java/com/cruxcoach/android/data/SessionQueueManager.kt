package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BoardLayerBoardIdentity
import com.cruxcoach.android.ble.BoardLayerControllerRouteKey
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerRouteDetails
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.PhysicalBoardIdentity
import com.cruxcoach.android.ble.reservedLayerColors
import com.cruxcoach.android.ble.hasCompleteQuantumLedMapping
import com.cruxcoach.android.ble.hasConfirmableQuantumDiodeCount
import com.cruxcoach.android.ble.matchesQuantumPlayers
import com.cruxcoach.android.ble.planKey
import com.cruxcoach.android.ui.board.BoardSendModePolicy
import com.cruxcoach.android.ui.board.QueueDeliveryPolicy
import com.cruxcoach.android.ui.settings.BoardConfigurationMismatch
import com.cruxcoach.android.ui.settings.BoardSendIdentity
import com.cruxcoach.android.ui.settings.boardSizeMismatch
import com.cruxcoach.android.ui.settings.resolveBoardConfigurationMismatch
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionQueueProtocol
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
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
    /** True only for the private, locally running playlist player. */
    val isPlaylist: Boolean = false,
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
     * Host migration must not silently turn a participant into a published
     * open-join server. While true, the promoted host keeps the queue locally
     * and the root UI asks for one explicit visibility choice.
     */
    val pendingHostVisibilityDecision: Boolean = false,
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
    /** Recoverable board identity/configuration failure from playlist delivery. */
    val boardMismatch: BoardConfigurationMismatch? = null,
) {
    val isActive: Boolean get() = role != SessionRole.NONE
    val currentClimb: QueueItem? get() = queue.getOrNull(currentIndex)
}

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
    /** Present in production; optional only so older focused tests can keep a
     *  lightweight queue fixture for non-Quantum transports. */
    private val boardLayerManager: BoardLayerManager? = null,
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

    private suspend fun <T> preferenceEvidence(read: suspend () -> T): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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
    }

    init {
        // The Quantum controller refreshes independently every ten seconds.
        // Keep the process-wide physical rack live regardless of which screen
        // or session mode is open. eWalls/other-device players are controller
        // truth too: reconcile them, resolve their catalogue routes, and let
        // every layer UI/conflict check/browser filter consume the same state.
        // Do not cancel catalogue hydration on every ten-second countdown
        // refresh. The controller revision always advances even when the
        // route/user/colour tuples are unchanged; tuple validation below
        // prevents stale application without starving slow devices forever.
        val layers = boardLayerManager
        if (layers != null) scope.launch {
            bleConnection.quantumControllerState.collect { controller ->
                if (controller.syncStatus ==
                    com.cruxcoach.android.ble.QuantumControllerSyncStatus.STALE
                ) {
                    layers.setQuantumSyncStatus(controller.syncStatus)
                    return@collect
                }
                if (bleConnection.connectedBoardBrand.value != BoardBrand.QUANTUM) {
                    return@collect
                }
                val descriptor = bleConnection.connectedBoardDescriptor.value
                val model = bleConnection.connectedQuantumModel.value
                if (descriptor == null || model == null) {
                    layers.setQuantumSyncStatus(controller.syncStatus)
                    return@collect
                }
                val physical = runCatching { PhysicalBoardIdentity.resolve(descriptor) }.getOrNull()
                    ?: return@collect
                // Bind as soon as fff5 identifies the controller, before the
                // first route-list response. A different Quantum must never
                // display the previous board's retained roster while loading.
                layers.bindBoard(BoardLayerBoardIdentity(physical.value, model.productSizeId))
                layers.setQuantumSyncStatus(controller.syncStatus)
                if (!controller.authoritative) return@collect
                applyQuantumControllerState(layers, model, controller)
            }
        }
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
        ) }
        bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
        Log.d(TAG, "Queue started (sessionId=$sessionId, hostName=$hostName)")
    }

    fun endQueue() {
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
        onRestRequested = null
        isPlaylistQueue = false
        bleConnection.releaseKeepAlive(BoardConnectionOwner.SESSION)
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
        if (visibility != SessionVisibility.LOCAL_ONLY) {
            Log.w(TAG, "loadPlaylist: coercing joinable request to local-only")
        }
        val current = _state.value
        if (current.isActive &&
            (current.role != SessionRole.HOST ||
                current.visibility != SessionVisibility.LOCAL_ONLY ||
                current.visibilityRequested != SessionVisibility.LOCAL_ONLY)
        ) {
            // A playlist must never silently repurpose a published host queue
            // or a participant connection. Its caller can explicitly leave
            // that session first; until then this is a fail-closed no-op.
            Log.w(TAG, "loadPlaylist: refused while a shared session is active")
            return
        }
        if (!_state.value.isActive) {
            startQueue(hostName, SessionVisibility.LOCAL_ONLY)
        }
        isPlaylistQueue = true
        lastSentClimbKey = null
        // Publish queue and playlist identity atomically so inline append
        // actions do not appear a recomposition late.
        _state.update {
            it.copy(
                queue = items,
                currentIndex = 0,
                isPlaylist = true,
                visibility = SessionVisibility.LOCAL_ONLY,
                visibilityRequested = SessionVisibility.LOCAL_ONLY,
            )
        }
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
        _state.update { it.copy(queue = items, currentIndex = currentIndex) }
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
        _state.update { it.copy(externalBoardOverride = true) }
    }

    fun setParticipantRole(sessionId: Int, hostName: String) {
        _state.update { it.copy(
            role = SessionRole.PARTICIPANT,
            sessionId = sessionId,
            hostName = hostName,
            isConnecting = false,
            visibility = SessionVisibility.JOINABLE,
            visibilityRequested = SessionVisibility.LOCAL_ONLY,
            pendingHostVisibilityDecision = false,
        ) }
    }

    /** The state in force. Does not touch [SessionQueueState.visibilityRequested]. */
    fun setVisibility(visibility: SessionVisibility) {
        _state.update { state ->
            if (state.role != SessionRole.HOST) state
            else if (state.isPlaylist && visibility == SessionVisibility.JOINABLE) {
                // A saved/running playlist is private by product contract. Keep
                // this invariant at the state boundary, not only in the one UI
                // entry point that currently creates it.
                state.copy(visibility = SessionVisibility.LOCAL_ONLY)
            } else state.copy(visibility = visibility)
        }
    }

    /** What the user asked for — set when they choose, never by a failure. */
    fun setVisibilityRequested(visibility: SessionVisibility) {
        _state.update { state ->
            if (state.role == SessionRole.HOST) {
                val allowed = if (state.isPlaylist) SessionVisibility.LOCAL_ONLY else visibility
                state.copy(
                    visibility = if (allowed == SessionVisibility.LOCAL_ONLY) {
                        SessionVisibility.LOCAL_ONLY
                    } else {
                        state.visibility
                    },
                    visibilityRequested = allowed,
                    pendingHostVisibilityDecision = false,
                )
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
            visibility = SessionVisibility.LOCAL_ONLY,
            visibilityRequested = SessionVisibility.LOCAL_ONLY,
            pendingHostVisibilityDecision = true,
            error = null,
        ) }
        bleConnection.acquireKeepAlive(BoardConnectionOwner.SESSION)
        Log.d(TAG, "Promoted to host (sessionId=$newSessionId, queue=${_state.value.queue.size} items)")
    }

    /** Update session info from host notification (participant side).
     *  The count from the host already includes the host (+1). */
    fun updateSessionInfo(
        hostName: String,
        participantCount: Int,
        awaitingExplicitSend: Boolean = false,
    ) {
        Log.d(TAG, "updateSessionInfo: hostName=$hostName, participantCount=$participantCount")
        _state.update { it.copy(
            hostName = hostName,
            participantCount = participantCount,
            awaitingExplicitSend = awaitingExplicitSend,
        ) }
    }

    /** Apply participant list from host notification (participant side).
     *  Updates the displayed list and recalculates our own index.
     *  Does NOT update participantCount — that comes solely from [updateSessionInfo]. */
    fun applyRemoteParticipants(names: List<String>) {
        Log.d(TAG, "applyRemoteParticipants: ${names.size} names: $names")
        _state.update { s ->
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

    /** Local-user escape hatch: the lamp is explicit authority to put the
     *  current climb back on the wall, even when it matches the dedup key. */
    fun resendCurrentClimb() = enqueueCurrentClimbSend(
        explicitRequest = true,
        forceResend = true,
    )

    fun clearBoardMismatch() {
        _state.update { it.copy(boardMismatch = null) }
    }

    /** A joined peer may ask the host to resend, but cannot manufacture the
     *  host user's explicit wall-write authority. AUTOMATIC mode may resend;
     *  EXPLICIT mode raises the host's lamp prompt instead. */
    internal fun requestRemoteResend() = enqueueCurrentClimbSend(
        explicitRequest = false,
        forceResend = true,
    )

    /**
     * @param explicitRequest true when the user asked for it — the lamp, or the
     *   first send of a freshly loaded queue. Advancing does not count: under
     *   the explicit send mode the wall stays as it is until asked.
     */
    fun sendCurrentClimbToBoard(explicitRequest: Boolean = false) {
        enqueueCurrentClimbSend(explicitRequest = explicitRequest, forceResend = false)
    }

    /** Queue the authority and dedup decision as one immutable send intent.
     *  Both are evaluated under [sendMutex], so a rapid SetCurrent + remote
     *  resend cannot clear shared state or inherit a later local action. */
    private fun enqueueCurrentClimbSend(explicitRequest: Boolean, forceResend: Boolean) {
        scope.launch {
            sendMutex.withLock {
                // Read state inside the lock so queued navigation events resolve
                // to the latest selection and collapse to one physical write.
                val queueState = _state.value
                val item = queueState.currentClimb ?: return@withLock

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
            if (!forceResend && key == lastSentClimbKey) {
                Log.d(TAG, "sendCurrentClimbToBoard: skipped dedup ${item.climbUuid.take(8)}")
                return@withLock
            }

            try {
                val climb = resolveClimb(item.climbUuid, item.angle)
                if (climb == null) {
                    Log.w(TAG, "Climb not found: ${item.climbUuid}")
                    return@withLock
                }
                val activeBrand = preferenceEvidence { userPreferences.boardBrand.first() }
                    ?.let(BoardBrand::fromWire)
                val identity = BoardSendIdentity(
                    climbBrand = climb.brand,
                    climbLayoutId = climb.layoutId,
                    activeBrand = activeBrand,
                    activeLayoutId = preferenceEvidence { userPreferences.boardLayoutId.first().toLong() },
                    activeProductSizeId = preferenceEvidence { userPreferences.boardProductSizeId.first() },
                    connectedBrand = runCatching { bleConnection.connectedBoardBrand.value }.getOrNull(),
                    connectedQuantumModel = runCatching { bleConnection.connectedQuantumModel.value }.getOrNull(),
                )
                resolveBoardConfigurationMismatch(identity)?.let { mismatch ->
                    _state.update { it.copy(boardMismatch = mismatch) }
                    onSessionInfoChanged?.invoke()
                    Log.w(TAG, "sendCurrentClimbToBoard: board mismatch ${mismatch.kind}")
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
                    val sent = bleConnection.sendMoonBoardClimb(
                        climb.frames,
                        variant,
                        userPreferences.moonBoardLedMode.first(),
                    )
                    if (sent) {
                        markCurrentClimbProjected(key)
                        Log.d(TAG, "sendCurrentClimbToBoard: sent MoonBoard ${item.climbUuid.take(8)} angle=${item.angle}")
                    }
                    return@withLock
                }
                val holds = BoardClimbParser.parseFrames(climb.frames)
                if (holds.isEmpty()) return@withLock
                val sent = if (climb.brand == BoardBrand.QUANTUM) {
                    sendQuantumPlaylistLayer(climb, item, holds)
                } else {
                    val productSizeId = userPreferences.boardProductSizeId.first()
                    // Brand-scope the LED map + colours, keyed off the CLIMB's own
                    // brand (mirrors BoardSendController). Aurora boards reuse
                    // Kilter's product_size ids, so the no-brand default would load
                    // Kilter's LED partition and the wrong per-board colours.
                    val brandWire = climb.brand.wireValue
                    val ledMap = boardRepository.getPlacementLedMap(productSizeId, brandWire)
                    if (ledMap.isNotEmpty() && holds.none { it.placementId in ledMap }) {
                        _state.update { it.copy(boardMismatch = boardSizeMismatch(identity)) }
                        onSessionInfoChanged?.invoke()
                        return@withLock
                    }
                    val roleColors = boardRepository.getRoleColorMapForBrand(brandWire).ifEmpty {
                        (if (climb.brand == BoardBrand.KILTER) userPreferences.ledHoldColors.first()
                         else LedHoldColors.standardFor(climb.brand)).toRoleColorMap()
                    }
                    bleConnection.sendClimb(
                        holds,
                        ledMap,
                        roleColors,
                        expectedBrand = climb.brand,
                    )
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
     * Project a private playlist occurrence through the same stable Quantum
     * identities as climb detail. The playlist remains an ordered, local-only
     * backlog; this method only chooses the single unambiguous free rack slot.
     * A full/unknown/conflicting rack requires an explicit choice on detail and
     * never evicts or overwrites another controller user.
     */
    private suspend fun sendQuantumPlaylistLayer(
        climb: com.cruxcoach.data.repository.ClimbWithStats,
        item: QueueItem,
        holds: List<com.cruxcoach.domain.board.BoardHold>,
    ): Boolean {
        val layers = boardLayerManager ?: return false
        if (!hasConfirmableQuantumDiodeCount(holds)) return false
        val descriptor = bleConnection.connectedBoardDescriptor.value ?: return false
        if (descriptor.boardBrand != BoardBrand.QUANTUM) return false
        val physical = runCatching { PhysicalBoardIdentity.resolve(descriptor) }.getOrNull()
            ?: return false
        val model = bleConnection.connectedQuantumModel.value ?: return false
        if (climb.layoutId != model.layoutId) return false
        val expectedBoard = BoardLayerBoardIdentity(physical.value, model.productSizeId)
        layers.bindBoard(expectedBoard)

        // A retained controller is shared mutable state. Refresh immediately
        // before allocating capacity, then enrich every route the controller
        // actually reported so conflicts include known eWalls users.
        if (!refreshQuantumPlaylistState(layers, model)) return false
        val ledMap = withContext(Dispatchers.IO) {
            boardRepository.getPlacementLedMap(
                expectedBoard.productSizeId.toInt(), BoardBrand.QUANTUM.wireValue,
            )
        }
        if (!layers.isBoundTo(expectedBoard) ||
            !hasCompleteQuantumLedMapping(holds, ledMap)
        ) return false

        val existing = layers.layerForClimb(climb.uuid)
        val slot = existing?.slot ?: layers.nextAvailableSlot(BoardBrand.QUANTUM) ?: return false
        val expectedCurrent = layers.state.value.layers.firstOrNull { it.slot == slot }?.planKey()
        if (!layers.hasControllerCapacityFor(slot)) return false
        val assessment = BoardLayerConflictPolicy.assess(
            candidate = holds,
            activeLayers = layers.state.value.layers,
            externalLayers = layers.state.value.externalLayers,
            replacingSlot = slot,
        )
        if (!assessment.canProveConflictFree) return false

        val color = existing?.color ?: layers.availableColors().firstOrNull() ?: return false
        val routeUuid = boardRepository.getQuantumExternalRouteUuid(climb.uuid) ?: climb.uuid
        val layer = BoardClimbLayer(
            slot = slot,
            climbUuid = climb.uuid,
            routeUuid = routeUuid,
            climbName = climb.name,
            angle = item.angle,
            userUuid = layers.identityForSlot(slot),
            color = color,
            holds = holds,
            status = BoardLayerStatus.PREVIEW,
        )
        if (!layers.assignPreviewIfCurrent(layer, expectedCurrent)) return false
        val expectedPlan = layer.planKey()

        // Catalogue hydration above may take disk time. Pull truth again at
        // the write boundary and repeat every safety predicate so a second
        // client cannot occupy the last slot/colour/hold in that interval.
        if (!refreshQuantumPlaylistState(layers, model)) {
            layers.failProjection(expectedPlan)
            return false
        }
        val expectedPlayers = bleConnection.quantumControllerState.value.players
        if (!layers.hasControllerCapacityFor(slot) ||
            !layers.state.value.matchesQuantumPlayers(expectedPlayers) ||
            layer.color in layers.state.value.reservedLayerColors(replacingSlot = slot) ||
            !BoardLayerConflictPolicy.assess(
                candidate = holds,
                activeLayers = layers.state.value.layers,
                externalLayers = layers.state.value.externalLayers,
                replacingSlot = slot,
            ).canProveConflictFree
        ) {
            layers.failProjection(expectedPlan)
            return false
        }
        if (!layers.beginProjection(expectedPlan)) return false
        val sent = bleConnection.sendClimb(
            holds = holds,
            placementToLed = ledMap,
            roleColors = emptyMap(),
            routeId = routeUuid,
            quantumUserId = layer.userUuid,
            quantumColor = color,
            expectedQuantumPlayers = expectedPlayers,
            expectedQuantumBoard = expectedBoard,
        )
        if (sent) layers.confirmProjection(expectedPlan) else layers.failProjection(expectedPlan)
        return sent
    }

    private suspend fun refreshQuantumPlaylistState(
        layers: BoardLayerManager,
        model: QuantumBoardModel,
    ): Boolean {
        if (!bleConnection.refreshQuantumState()) return false
        val controller = bleConnection.quantumControllerState.value
        if (!controller.authoritative) return false
        return applyQuantumControllerState(layers, model, controller)
    }

    private suspend fun applyQuantumControllerState(
        layers: BoardLayerManager,
        model: QuantumBoardModel,
        controller: com.cruxcoach.android.ble.QuantumControllerState,
    ): Boolean {
        val expectedBoard = layers.state.value.board ?: return false
        val productSizeId = model.productSizeId
        if (bleConnection.connectedQuantumModel.value != model ||
            !layers.isBoundTo(expectedBoard)
        ) return false
        layers.reconcile(controller.players)
        val details = withContext(Dispatchers.IO) {
            val ledMap = boardRepository.getPlacementLedMap(
                productSizeId.toInt(), BoardBrand.QUANTUM.wireValue,
            )
            controller.players.mapNotNull { player ->
                val known = boardRepository.getQuantumClimbByExternalRoute(
                    routeUuid = player.routeId,
                    model = model.wireValue,
                    allowDirectUuidFallback = layers.ownsIdentity(player.userId),
                ) ?: run {
                    Log.d(TAG, "Quantum route absent: ${player.routeId} model=${model.wireValue}")
                    return@mapNotNull null
                }
                val holds = BoardClimbParser.parseSingleFrameStrict(known.frames) ?: run {
                    Log.w(TAG, "Quantum route has invalid frames: ${player.routeId} app=${known.uuid}")
                    return@mapNotNull null
                }
                if (!hasCompleteQuantumLedMapping(holds, ledMap)) {
                    val missing = holds.map { it.placementId }.filterNot(ledMap::containsKey)
                    Log.w(
                        TAG,
                        "Quantum route has incomplete LED mapping: ${player.routeId} " +
                            "map=${ledMap.size} missing=$missing",
                    )
                    return@mapNotNull null
                }
                BoardLayerControllerRouteKey(player.routeId, player.userId) to
                    BoardLayerRouteDetails(
                        climbUuid = known.uuid,
                        climbName = known.name,
                        holds = holds,
                    )
            }.toMap()
        }
        val latestDescriptor = bleConnection.connectedBoardDescriptor.value ?: return false
        val latestPhysical = runCatching { PhysicalBoardIdentity.resolve(latestDescriptor) }.getOrNull()
            ?: return false
        // hydrateControllerRoutes applies by the exact route/user tuple to the
        // rack's *current* entries. It therefore cannot resurrect a removed
        // player or attach details to a replacement. Do not reject useful
        // catalogue results merely because another poll entered SYNCING or a
        // different player changed while this lookup was running.
        if (bleConnection.connectedQuantumModel.value != model ||
            latestPhysical.value != expectedBoard.physicalBoardId ||
            !layers.isBoundTo(expectedBoard)
        ) return false
        Log.d(
            TAG,
            "Quantum catalogue hydration: model=${model.wireValue} " +
                "players=${controller.players.size} resolved=${details.size}",
        )
        layers.hydrateControllerRoutes(details)
        return true
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
        val changed = _state.value.awaitingExplicitSend
        _state.update { it.copy(awaitingExplicitSend = false, boardMismatch = null) }
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
        return SessionQueueProtocol.encodeSessionInfo(
            s.hostName,
            s.participantCount,
            s.awaitingExplicitSend,
        )
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
