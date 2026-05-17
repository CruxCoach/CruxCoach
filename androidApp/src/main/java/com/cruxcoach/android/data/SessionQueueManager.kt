package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionQueueProtocol
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SessionRole { NONE, HOST, PARTICIPANT }

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
    /** This participant's position in the join order (0-based). Used for host election. */
    val participantIndex: Int = -1
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    companion object {
        private const val TAG = "SessionQueueManager"
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
                if (isConnected && !wasConnected && _state.value.isActive && _state.value.currentClimb != null) {
                    Log.d(TAG, "Board connected during active session — sending current climb")
                    sendCurrentClimbToBoard()
                }
                wasConnected = isConnected
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

    /** Remote command sender — set by SessionGattBridge for participant mode.
     *  When set, addClimb/removeClimb/etc. send commands to host instead of mutating locally. */
    @Volatile var remoteAddClimb: ((climbUuid: String, angle: Int) -> Unit)? = null

    // ===== Queue operations (work in all modes) =====

    fun startQueue(hostName: String = "") {
        if (_state.value.isActive) return
        val sessionId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        _state.update { SessionQueueState(
            role = SessionRole.HOST,
            sessionId = sessionId,
            hostName = hostName,
            participantCount = 1  // host counts as 1
        ) }
        bleConnection.suppressAutoDisconnect = true
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
        onFirstQueueClimbSent = null
        remoteAddClimb = null
        bleConnection.suppressAutoDisconnect = false
        Log.d(TAG, "endQueue(): complete, state reset to NONE")
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
            setCurrentClimb(s.currentIndex + 1)
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

    /** Apply current index change from host notification. */
    fun applyRemoteCurrentIndex(index: Int) {
        _state.update { s ->
            if (index in s.queue.indices) s.copy(currentIndex = index) else s
        }
    }

    fun setParticipantRole(sessionId: Int, hostName: String) {
        _state.update { it.copy(
            role = SessionRole.PARTICIPANT,
            sessionId = sessionId,
            hostName = hostName,
            isConnecting = false
        ) }
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
        bleConnection.suppressAutoDisconnect = true
        Log.d(TAG, "Promoted to host (sessionId=$newSessionId, queue=${_state.value.queue.size} items)")
    }

    /** Update session info from host notification (participant side).
     *  The count from the host already includes the host (+1). */
    fun updateSessionInfo(hostName: String, participantCount: Int) {
        Log.d(TAG, "updateSessionInfo: hostName=$hostName, participantCount=$participantCount")
        _state.update { it.copy(
            hostName = hostName,
            participantCount = participantCount
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

    /** Tracks last sent climb to prevent duplicate sends from multiple callers. */
    private var lastSentClimbKey: String? = null

    fun sendCurrentClimbToBoard() {
        scope.launch {
            val item = _state.value.currentClimb ?: return@launch
            if (bleConnection.connectionState.value != ConnectionState.CONNECTED) return@launch

            // Dedup: don't re-send the same climb (multiple callers can trigger this)
            val key = "${item.climbUuid}:${item.angle}"
            if (key == lastSentClimbKey) {
                Log.d(TAG, "sendCurrentClimbToBoard: skipped dedup ${item.climbUuid.take(8)}")
                return@launch
            }

            try {
                val climb = resolveClimb(item.climbUuid, item.angle)
                if (climb == null) {
                    Log.w(TAG, "Climb not found: ${item.climbUuid}")
                    return@launch
                }
                val holds = BoardClimbParser.parseFrames(climb.frames)
                if (holds.isEmpty()) return@launch
                val productSizeId = userPreferences.boardProductSizeId.first()
                val ledMap = boardRepository.getPlacementLedMap(productSizeId)
                bleConnection.sendClimb(holds, ledMap)
                lastSentClimbKey = key
                Log.d(TAG, "sendCurrentClimbToBoard: sent ${item.climbUuid.take(8)} angle=${item.angle}")
                // Clear last-projected-climb banner on first queue send
                onFirstQueueClimbSent?.invoke()
                onFirstQueueClimbSent = null // fire once
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send climb to board", e)
            }
        }
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

    fun encodeQueueState(): ByteArray {
        val s = _state.value
        return SessionQueueProtocol.encodeQueueState(s.currentIndex, s.queue)
    }

    fun encodeSessionInfo(): ByteArray {
        val s = _state.value
        return SessionQueueProtocol.encodeSessionInfo(s.hostName, s.participantCount)
    }

    fun encodeParticipantList(): ByteArray {
        return SessionQueueProtocol.encodeParticipantList(
            _state.value.participants.map { it.displayName }
        )
    }

    fun encodeCurrentClimb(): ByteArray {
        val item = _state.value.currentClimb
        return if (item != null) {
            SessionQueueProtocol.encodeQueueState(_state.value.currentIndex, listOf(item))
        } else {
            byteArrayOf(0xFF.toByte(), 0)
        }
    }
}
