// Port of the participant half of androidApp/.../data/SessionGattBridge.kt and of the
// participant-side queue rules of SessionQueueManager (0e1bad0c6): join (JOIN, initial reads,
// paged queue reassembly), applying the host's events (queue, current climb, rest phase,
// command results, session info and its end sentinels, participant list), commands with
// semantic context and Android's 5 s result window, leave, and the host-loss election.
//
// What an iPhone cannot do, and therefore does differently (each shown in the UI):
// - It cannot host (iOS does not advertise manufacturer data), so it never promotes itself
//   when the host leaves. Android's participant at election index 0 promotes; here every
//   index waits its Android delay (1 s + 3 s per index) and then offers the strongest other
//   nearby session for one explicit answer, else the session ends with the queue kept on screen.
// - It does not project the queue onto its own board connection: the host drives the wall.
//
// The host's rest phase drives the countdown on this screen and, as on Android ("drive the
// participant's OWN rest timer rather than inventing a second kind of rest UI"), the phone's own
// rest timer through [HostRestSink]: the app-wide rest banner and its end notification.
package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.nearby.NearbySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

internal enum class ParticipantPhase { IDLE, CONNECTING, JOINED, HOST_LOST, ENDED }

internal enum class ParticipantEndReason { NONE, CONNECT_FAILED, HOST_LEFT, ENDED_BY_HOST }

internal enum class CommandFeedbackKind { CONFLICT, UNAVAILABLE, FAILED }

/** An unsigned nearby advertisement offered after the host left; it does not authenticate the peer. */
internal data class SuccessorOffer(val identifier: String, val sessionId: Int, val hostName: String)

internal data class ParticipantState(
    val phase: ParticipantPhase = ParticipantPhase.IDLE,
    val endReason: ParticipantEndReason = ParticipantEndReason.NONE,
    val hostIdentifier: String = "",
    /** The host's advertised session id; the context of every mutating command. */
    val sessionId: Int = 0,
    val hostName: String = "",
    val participantCount: Int = 0,
    val participants: List<String> = emptyList(),
    /** This phone's position in the join order (0-based), for the host-loss election. */
    val participantIndex: Int = -1,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val awaitingExplicitSend: Boolean = false,
    /** A compatible external app last wrote the host's board. */
    val externalBoardOverride: Boolean = false,
    /** Seconds left in the host's rest; 0 while none runs. */
    val restRemainingSeconds: Int = 0,
    val pendingCommands: Int = 0,
    val feedback: CommandFeedbackKind? = null,
    /** Increments with every feedback, so the UI shows each one once. */
    val feedbackSeq: Int = 0,
    val successor: SuccessorOffer? = null,
) {
    val currentClimb: QueueItem? get() = queue.getOrNull(currentIndex)
}

/**
 * The phone's own rest timer as the host's rest drives it (Android's `SessionGattBridge`:
 * RestStarted → `startRestTimer(remainingSeconds)`, RestEnded → `cancelRestTimer()`).
 */
internal interface HostRestSink {
    fun hostRestStarted(seconds: Int)
    fun hostRestEnded()
}

internal class SharedSessionParticipant(
    private val client: SessionGattClient,
    /** The live nearby sessions: the session id of a joined row and successor candidates. */
    private val nearbySessions: () -> List<NearbySession>,
    private val scope: CoroutineScope,
    private val millis: () -> Long,
    /** Null: the host's rest shows on the sessions screen only. */
    private val hostRest: HostRestSink? = null,
) {
    companion object {
        const val MIGRATION_BASE_DELAY_MS = 1000L
        const val MIGRATION_INDEX_STEP_MS = 3000L
        const val COMMAND_RESULT_TIMEOUT_MS = 5000L
        const val LEAVE_GRACE_MS = 300L
        const val QUEUE_REREAD_INTERVAL_MS = 2000L
        private const val NO_CURRENT_CLIMB_INDEX = 0xFF
        private const val EXTERNAL_BOARD_OVERRIDE_FLAG = 0x01

        /** Android `SessionQueueManager.isExternalBoardOverride`. */
        fun isExternalBoardOverride(data: ByteArray): Boolean =
            data.size >= 2 &&
                (data[0].toInt() and 0xFF) == NO_CURRENT_CLIMB_INDEX &&
                (data[1].toInt() and 0xFF) == EXTERNAL_BOARD_OVERRIDE_FLAG
    }

    private val _state = MutableStateFlow(ParticipantState())
    val state: StateFlow<ParticipantState> = _state.asStateFlow()

    private var joinJob: Job? = null
    private var migrationJob: Job? = null
    private var migrationGeneration = 0L
    private var restJob: Job? = null
    /** The phone's own rest timer runs a rest the host started (so leaving only ends that one). */
    private var hostRestOnTimer = false
    private var isRejoining = false
    private var lastHostSessionId = 0
    private var lastQueueReread = Long.MIN_VALUE / 2
    private var nextRequestId = Random.nextLong(1L, Long.MAX_VALUE / 2)
    private val pendingCommands = LinkedHashMap<Long, String>()

    // ===== Join / leave =====

    /** Joins the session advertised by [identifier] (a row of the nearby list). */
    fun join(identifier: String) {
        cancelMigration()
        join(identifier, expectedSessionId = null)
    }

    private fun join(identifier: String, expectedSessionId: Int?) {
        _state.update { it.copy(successor = null) }
        val selected = expectedSessionId ?: nearbySessions().firstOrNull { it.identifier == identifier }?.sessionId
        if (selected != null) lastHostSessionId = selected
        // The previous collectors must not read the old link's DISCONNECTED as a host loss.
        isRejoining = true
        joinJob?.cancel()
        _state.update {
            // A successor join keeps the queue on screen until the new host's state arrives.
            val keep = it.phase == ParticipantPhase.HOST_LOST
            ParticipantState(
                phase = ParticipantPhase.CONNECTING,
                hostIdentifier = identifier,
                sessionId = lastHostSessionId,
                queue = if (keep) it.queue else emptyList(),
                currentIndex = if (keep) it.currentIndex else -1,
                participantIndex = if (keep) it.participantIndex else -1,
                feedbackSeq = it.feedbackSeq,
            )
        }
        stopRest()
        client.connect(identifier)
        joinJob = scope.launch {
            isRejoining = false
            launch { client.connectionState.collect { onConnectionState(it) } }
            launch {
                client.queueEvents.collect { data ->
                    SessionQueueProtocol.decodeEvent(data)?.let { applyRemoteEvent(it) }
                }
            }
            launch { client.sessionInfoUpdates.collect { onSessionInfo(it) } }
            launch { client.currentClimbUpdates.collect { onCurrentClimb(it) } }
            launch { collectQueueState() }
            launch {
                client.participantListUpdates.collect { data ->
                    SessionQueueProtocol.decodeParticipantList(data)?.let { applyRemoteParticipants(it) }
                }
            }
        }
    }

    private suspend fun onConnectionState(connection: SessionClientState) {
        when (connection) {
            SessionClientState.CONNECTED -> {
                cancelMigration()
                client.sendCommand(SessionQueueProtocol.encodeJoin(""))
                client.readInitialState()
                _state.update {
                    it.copy(phase = ParticipantPhase.JOINED, sessionId = lastHostSessionId,
                        endReason = ParticipantEndReason.NONE)
                }
            }
            SessionClientState.DISCONNECTED -> {
                if (isRejoining) return
                val s = _state.value
                when (s.phase) {
                    // A successor join failed: run the election again with the kept queue.
                    ParticipantPhase.CONNECTING -> if (s.queue.isNotEmpty()) {
                        migrationJob = null
                        _state.update { it.copy(phase = ParticipantPhase.HOST_LOST) }
                        attemptHostMigration()
                    } else {
                        endSession(ParticipantEndReason.CONNECT_FAILED, keepQueue = false)
                    }
                    ParticipantPhase.JOINED -> attemptHostMigration()
                    else -> Unit
                }
            }
            SessionClientState.CONNECTING -> Unit
        }
    }

    /** Android `leaveSession`: end locally at once, then LEAVE, a short grace, disconnect. */
    fun leave() {
        cancelMigration()
        joinJob?.cancel()
        joinJob = null
        stopRest()
        // Android's leaveSession → endSession → cancelRestTimer.
        endHostRest()
        clearPending()
        _state.update { ParticipantState(feedbackSeq = it.feedbackSeq) }
        scope.launch {
            client.sendCommand(SessionQueueProtocol.encodeLeave())
            delay(LEAVE_GRACE_MS)
            client.disconnect()
        }
    }

    /** Clears an ended session from the screen. */
    fun dismiss() {
        if (_state.value.phase == ParticipantPhase.ENDED) _state.update { ParticipantState(feedbackSeq = it.feedbackSeq) }
    }

    // ===== Host → participant =====

    private fun onSessionInfo(data: ByteArray) {
        val info = SessionQueueProtocol.decodeSessionInfo(data) ?: return
        if (info.participantCount == 0) {
            if (SessionQueueProtocol.isFinalSessionEnd(data)) handleSessionEndedForEveryone()
            else handleSessionEndedByHost()
            return
        }
        _state.update {
            it.copy(hostName = info.hostName, participantCount = info.participantCount,
                awaitingExplicitSend = info.awaitingExplicitSend)
        }
    }

    private fun onCurrentClimb(data: ByteArray) {
        if (data.isEmpty()) return
        val index = data[0].toInt() and 0xFF
        if (isExternalBoardOverride(data)) {
            _state.update { it.copy(externalBoardOverride = true) }
        } else if (index != NO_CURRENT_CLIMB_INDEX) {
            applyRemoteCurrentIndex(index)
        }
    }

    /** Pages are applied only once a whole set arrived, so a half queue never replaces a whole one. */
    private suspend fun collectQueueState() {
        val pages = mutableMapOf<Int, List<QueueItem>>()
        var expectedPageCount = -1
        var pendingIndex = 0
        client.queueStateUpdates.collect { data ->
            val parsed = SessionQueueProtocol.decodeQueueState(data)
            if (parsed == null) {
                // iOS-only recovery: the host's notification was cut at this link's MTU. Page 0
                // by long read is never cut (it arrives back through this same flow).
                // Throttled: the read's own answer comes back here and must not loop.
                val time = millis()
                if (data.size >= 4 && time - lastQueueReread >= QUEUE_REREAD_INTERVAL_MS) {
                    lastQueueReread = time
                    scope.launch { client.readQueueState() }
                }
                return@collect
            }
            if (parsed.pageCount != expectedPageCount) {
                pages.clear()
                expectedPageCount = parsed.pageCount
            }
            pendingIndex = parsed.currentIndex
            pages[parsed.page] = parsed.items
            if (pages.size < expectedPageCount) return@collect
            val items = (0 until expectedPageCount).flatMap { pages[it].orEmpty() }
            pages.clear()
            expectedPageCount = -1
            _state.update { it.copy(queue = items, currentIndex = pendingIndex) }
        }
    }

    /** An index the local queue does not hold yet is dropped; the next full push heals it. */
    private fun applyRemoteCurrentIndex(index: Int) {
        _state.update { s ->
            if (index in s.queue.indices) s.copy(currentIndex = index, externalBoardOverride = false) else s
        }
    }

    private fun applyRemoteParticipants(names: List<String>) {
        _state.update { s ->
            val myIndex = if (s.participantIndex in names.indices) s.participantIndex else (names.size - 1).coerceAtLeast(0)
            s.copy(participants = names, participantIndex = myIndex)
        }
    }

    private fun applyRemoteEvent(event: SessionEvent) {
        when (event) {
            // Android hosts never send it; appended locally like SessionQueueManager.addClimb.
            is SessionEvent.Added -> _state.update { s ->
                s.copy(queue = s.queue + QueueItem(event.climbUuid, event.angle),
                    currentIndex = if (s.currentIndex < 0) 0 else s.currentIndex)
            }
            is SessionEvent.Removed -> _state.update { s -> removeAt(s, event.index) }
            is SessionEvent.CurrentChanged -> setCurrent(event.index)
            is SessionEvent.Cleared -> _state.update { it.copy(queue = emptyList(), currentIndex = -1) }
            is SessionEvent.ParticipantJoined, is SessionEvent.ParticipantLeft -> Unit
            is SessionEvent.RestStarted -> {
                // The host says where the queue landed; trust it if CurrentChanged was dropped.
                if (event.nextIndex != _state.value.currentIndex) setCurrent(event.nextIndex)
                if (event.remainingSeconds > 0) {
                    startRest(event.remainingSeconds)
                    hostRest?.let {
                        it.hostRestStarted(event.remainingSeconds)
                        hostRestOnTimer = true
                    }
                }
            }
            is SessionEvent.RestEnded -> {
                stopRest()
                endHostRest()
            }
            is SessionEvent.CommandResult -> {
                pendingCommands.remove(event.requestId) ?: return
                publishPending()
                when (event.result) {
                    SessionCommandResult.COMMITTED -> Unit
                    SessionCommandResult.CONFLICT -> feedback(CommandFeedbackKind.CONFLICT)
                    SessionCommandResult.FAILED -> feedback(CommandFeedbackKind.FAILED)
                }
            }
        }
    }

    private fun setCurrent(index: Int) {
        _state.update { s -> if (index < 0 || index >= s.queue.size) s else s.copy(currentIndex = index) }
    }

    private fun removeAt(s: ParticipantState, index: Int): ParticipantState {
        if (index < 0 || index >= s.queue.size) return s
        val queue = s.queue.toMutableList().apply { removeAt(index) }
        val current = when {
            queue.isEmpty() -> -1
            index < s.currentIndex -> s.currentIndex - 1
            index == s.currentIndex -> s.currentIndex.coerceAtMost(queue.size - 1)
            else -> s.currentIndex
        }
        return s.copy(queue = queue, currentIndex = current)
    }

    private fun startRest(seconds: Int) {
        restJob?.cancel()
        val endsAt = millis() + seconds * 1000L
        _state.update { it.copy(restRemainingSeconds = seconds) }
        restJob = scope.launch {
            while (true) {
                delay(1000)
                val left = ((endsAt - millis() + 999) / 1000).toInt().coerceAtLeast(0)
                _state.update { it.copy(restRemainingSeconds = left) }
                if (left == 0) break
            }
        }
    }

    private fun endHostRest() {
        if (!hostRestOnTimer) return
        hostRestOnTimer = false
        hostRest?.hostRestEnded()
    }

    private fun stopRest() {
        restJob?.cancel()
        restJob = null
        if (_state.value.restRemainingSeconds != 0) _state.update { it.copy(restRemainingSeconds = 0) }
    }

    // ===== Participant → host =====

    fun addClimb(climbUuid: String, angle: Int) = send("add", SessionCommand.Add(climbUuid, angle))
    fun removeClimb(index: Int) = send("remove", SessionCommand.Remove(index))
    fun next() = send("next", SessionCommand.Next)
    fun previous() = send("prev", SessionCommand.Prev)
    fun setCurrentClimb(index: Int) = send("setCurrent($index)", SessionCommand.SetCurrent(index))
    fun moveClimb(from: Int, to: Int) = send("move($from→$to)", SessionCommand.Move(from, to))
    fun resend() = send("resend", SessionCommand.Resend)

    /**
     * Android `sendParticipantCommand`: fire-and-forget with a visible failure. A stale local
     * index is a local conflict; an old host that never answers ages the indicator out after
     * 5 s without a retry (a retry would duplicate an Add).
     */
    private fun send(label: String, command: SessionCommand) {
        scope.launch {
            val s = _state.value
            if (s.phase != ParticipantPhase.JOINED) {
                feedback(CommandFeedbackKind.UNAVAILABLE)
                return@launch
            }
            val context = SessionCommandRebaser.context(command, s.sessionId, s.currentIndex, s.queue)
            if (context == null) {
                feedback(CommandFeedbackKind.CONFLICT)
                return@launch
            }
            val requestId = nextRequestId++
            val extended = runCatching { SessionQueueProtocol.encodeCommandRequest(requestId, command, context) }.getOrNull()
            if (extended == null) {
                // A climb id that is not a UUID cannot travel in this protocol (Android would throw).
                feedback(CommandFeedbackKind.FAILED)
                return@launch
            }
            pendingCommands[requestId] = label
            publishPending()
            val payload = if (client.supportsCommandSize(extended.size)) {
                extended
            } else {
                // Tiny-MTU fallback: the host converges through its broadcast; no result id.
                pendingCommands.remove(requestId)
                publishPending()
                SessionQueueProtocol.encodeCommand(command)
            }
            if (client.sendCommand(payload)) {
                if (payload === extended) {
                    delay(COMMAND_RESULT_TIMEOUT_MS)
                    if (pendingCommands.remove(requestId) != null) publishPending()
                }
            } else {
                pendingCommands.remove(requestId)
                publishPending()
                feedback(CommandFeedbackKind.UNAVAILABLE)
            }
        }
    }

    private fun publishPending() {
        val count = pendingCommands.size
        _state.update { it.copy(pendingCommands = count) }
    }

    private fun clearPending() {
        pendingCommands.clear()
        publishPending()
    }

    private fun feedback(kind: CommandFeedbackKind) {
        _state.update { it.copy(feedback = kind, feedbackSeq = it.feedbackSeq + 1) }
    }

    // ===== Host loss =====

    private fun handleSessionEndedForEveryone() {
        cancelMigration()
        joinJob?.cancel()
        joinJob = null
        client.disconnect()
        endSession(ParticipantEndReason.ENDED_BY_HOST, keepQueue = false)
    }

    private fun handleSessionEndedByHost() {
        joinJob?.cancel()
        joinJob = null
        client.disconnect()
        attemptHostMigration()
    }

    private fun attemptHostMigration() {
        if (migrationJob?.isActive == true) return
        val s = _state.value
        if (s.successor != null) return
        if (s.queue.isEmpty()) {
            endSession(ParticipantEndReason.HOST_LEFT, keepQueue = false)
            return
        }
        clearPending()
        stopRest()
        _state.update { it.copy(phase = ParticipantPhase.HOST_LOST) }
        val waitMs = MIGRATION_BASE_DELAY_MS + s.participantIndex.coerceAtLeast(0) * MIGRATION_INDEX_STEP_MS
        val generation = ++migrationGeneration
        migrationJob = scope.launch {
            delay(waitMs)
            if (migrationGeneration != generation || _state.value.phase != ParticipantPhase.HOST_LOST) return@launch
            val candidate = nearbySessions()
                .filter { it.sessionId != lastHostSessionId && it.identifier.isNotBlank() }
                .maxByOrNull { it.rssi }
            if (candidate != null) {
                _state.update { it.copy(successor = SuccessorOffer(candidate.identifier, candidate.sessionId, candidate.hostName.trim())) }
            } else {
                // Android would promote the queue to a local host here; an iPhone cannot host.
                endSession(ParticipantEndReason.HOST_LEFT, keepQueue = true)
            }
        }
    }

    /** Joins only the exact still-live advertisement the user approved once. */
    fun acceptSuccessor() {
        val offer = _state.value.successor ?: return
        _state.update { it.copy(successor = null) }
        if (_state.value.phase != ParticipantPhase.HOST_LOST) return
        val live = nearbySessions().firstOrNull { it.sessionId == offer.sessionId && it.identifier == offer.identifier }
        if (live == null) {
            endSession(ParticipantEndReason.HOST_LEFT, keepQueue = true)
            return
        }
        cancelMigration()
        join(offer.identifier, offer.sessionId)
    }

    fun declineSuccessor() {
        if (_state.value.successor == null) return
        _state.update { it.copy(successor = null) }
        cancelMigration()
        endSession(ParticipantEndReason.HOST_LEFT, keepQueue = true)
    }

    private fun cancelMigration() {
        migrationGeneration++
        migrationJob?.cancel()
        migrationJob = null
    }

    private fun endSession(reason: ParticipantEndReason, keepQueue: Boolean) {
        stopRest()
        // Android's handleSessionEndedForEveryone ends the rest; a lost host does not (its
        // participant takes the session over there, and the rest keeps counting).
        if (reason == ParticipantEndReason.ENDED_BY_HOST) endHostRest()
        clearPending()
        _state.update {
            ParticipantState(
                phase = ParticipantPhase.ENDED,
                endReason = reason,
                hostName = it.hostName,
                queue = if (keepQueue) it.queue else emptyList(),
                currentIndex = if (keepQueue) it.currentIndex else -1,
                feedbackSeq = it.feedbackSeq,
            )
        }
    }
}
