package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.QuantumCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quantum part of the link: ordered setup, framed writes, controller-state
 * tracking. Port of the Quantum paths of Android's BoardBleConnection. Android's
 * own documentation says this protocol is validated against the BoardSimulator
 * only, not physical Quantum hardware; the same holds here, on top of this port
 * never having run on iOS.
 */
internal class QuantumLinkSupport(private val controller: BoardConnectionController) {
    private companion object {
        const val CONFIRM_TIMEOUT_MS = 3000L
        const val REFRESH_INTERVAL_MS = 10_000L
        const val MUTATION_SETTLE_MS = 250L
        const val INTER_FRAME_DELAY_MS = 100L
        const val TURN_OFF_TO_ACTIVATE_DELAY_MS = 50L
        const val ROUTE_LIST_READ_DELAY_MS = 50L
    }

    private val _state = MutableStateFlow(QuantumControllerState())
    val state: StateFlow<QuantumControllerState> = _state.asStateFlow()

    private val accumulator = QuantumNotificationAccumulator()
    private var setupJob: Job? = null
    private var refreshJob: Job? = null
    private var metadataRead: CompletableDeferred<ByteArray?>? = null
    private var stateRead: CompletableDeferred<ByteArray?>? = null
    private var notifyEnabled: CompletableDeferred<Boolean>? = null
    private var routeListRequestActive = false

    /** Some controllers list fff4 but never answer reads; then only explicit route-list requests are used. */
    private var stateReadUsable = true

    /** `maximumWriteValueLength` for writes with response, sampled during setup. */
    private var maximumWriteLength = 0

    fun onConnectStarting(board: DiscoveredBoard) {
        cancelOperations()
        stateReadUsable = true
        if (board.boardBrand == BoardBrand.QUANTUM) {
            accumulator.reset()
            val previous = _state.value
            _state.value = QuantumControllerState(
                revision = previous.revision + 1,
                authoritative = false,
                lastAuthoritativeAtMs = previous.lastAuthoritativeAtMs,
            )
        }
    }

    fun cancelOperations() {
        setupJob?.cancel()
        setupJob = null
        refreshJob?.cancel()
        refreshJob = null
        metadataRead?.complete(null)
        metadataRead = null
        stateRead?.complete(null)
        stateRead = null
        notifyEnabled?.complete(false)
        notifyEnabled = null
        routeListRequestActive = false
        accumulator.reset()
    }

    fun markStale() {
        val current = _state.value
        _state.value = current.copy(
            authoritative = false,
            syncStatus = if (current.lastAuthoritativeAtMs == null) {
                QuantumControllerSyncStatus.UNSYNCED
            } else {
                QuantumControllerSyncStatus.STALE
            },
        )
    }

    // ── Setup: discover → write length → read fff5 → enable fff1 → only then CONNECTED ──

    fun beginSetup(link: BoardConnectionController.Link) {
        // Discovery is complete; each step below owns its own bounded wait.
        controller.stopConnectTimeout()
        setupJob?.cancel()
        setupJob = controller.launch {
            val ready = setUp(link)
            if (!ready) {
                controller.failAndDisconnect(BoardConnectFailure.CONNECT_FAILED)
                return@launch
            }
            controller.markReadyFromSetup(link)
        }
    }

    private suspend fun setUp(link: BoardConnectionController.Link): Boolean {
        val service = link.quantumService ?: return false
        // iOS negotiates the ATT MTU by itself during connection and offers no
        // request API; this replaces Android's requestMtu(512) step.
        maximumWriteLength = controller.centralMaximumWriteLength(link.identifier)
        if (!link.hasCharacteristic(service, BoardBleUuids.QUANTUM_METADATA_CHAR)) return false
        val pendingRead = CompletableDeferred<ByteArray?>()
        metadataRead = pendingRead
        controller.centralRead(link.identifier, service, BoardBleUuids.QUANTUM_METADATA_CHAR)
        val record = withTimeoutOrNull(BoardConnectionController.WRITE_TIMEOUT_MS) { pendingRead.await() }
        if (metadataRead === pendingRead) metadataRead = null
        val metadata = record?.let(::parseQuantumControllerMetadata) ?: return false
        if (controller.link !== link) return false
        controller.updateState { it.copy(quantumModel = metadata.model) }

        // Writing fff2 before the subscription is confirmed loses either the
        // subscription or the first route-list response on real stacks.
        if (!link.hasCharacteristic(service, BoardBleUuids.QUANTUM_NOTIFY_CHAR)) return false
        val pendingNotify = CompletableDeferred<Boolean>()
        notifyEnabled = pendingNotify
        controller.centralSetNotify(link.identifier, service, BoardBleUuids.QUANTUM_NOTIFY_CHAR)
        val enabled = withTimeoutOrNull(BoardConnectionController.WRITE_TIMEOUT_MS) { pendingNotify.await() }
        if (notifyEnabled === pendingNotify) notifyEnabled = null
        return enabled == true && controller.link === link
    }

    private fun BoardConnectionController.Link.hasCharacteristic(service: String, uuid: String): Boolean =
        services.firstOrNull { BoardBleUuids.sameUuid(it.uuid, service) }
            ?.characteristics?.any { BoardBleUuids.sameUuid(it.uuid, uuid) } == true

    fun onReady(link: BoardConnectionController.Link) {
        _state.value = _state.value.copy(authoritative = false, syncStatus = QuantumControllerSyncStatus.SYNCING)
        refreshJob?.cancel()
        refreshJob = controller.launch {
            if (!refresh()) return@launch
            while (controller.link === link) {
                delay(REFRESH_INTERVAL_MS)
                if (!refresh()) return@launch
            }
        }
    }

    // ── Central events ──

    fun onRead(characteristicUuid: String, value: ByteArray?) {
        when {
            BoardBleUuids.sameUuid(characteristicUuid, BoardBleUuids.QUANTUM_STATE_CHAR) ->
                stateRead?.complete(value?.copyOf())
            BoardBleUuids.sameUuid(characteristicUuid, BoardBleUuids.QUANTUM_METADATA_CHAR) ->
                metadataRead?.complete(value?.copyOf())
        }
    }

    fun onNotifyState(characteristicUuid: String, enabled: Boolean, success: Boolean) {
        if (BoardBleUuids.sameUuid(characteristicUuid, BoardBleUuids.QUANTUM_NOTIFY_CHAR)) {
            notifyEnabled?.complete(enabled && success)
        }
    }

    fun onNotification(characteristicUuid: String, value: ByteArray) {
        if (!BoardBleUuids.sameUuid(characteristicUuid, BoardBleUuids.QUANTUM_NOTIFY_CHAR)) return
        accumulator.consume(value).forEach { recovered ->
            // A frame spliced across callbacks is trusted only inside a freshly
            // reset explicit request generation.
            if (recovered.crossedCallbackBoundary && !routeListRequestActive) return@forEach
            apply(QuantumBoardBroadcastParser.parse(recovered.bytes), routeListRequestActive)
        }
    }

    private fun apply(broadcast: QuantumBroadcast?, explicitRouteListResponse: Boolean) {
        val current = _state.value
        val revision = current.revision + 1
        val now = controller.clock.epochMillis()
        when (broadcast) {
            is QuantumBroadcast.RouteList -> _state.value = QuantumControllerState(
                players = broadcast.players,
                revision = revision,
                authoritativeRevision = revision,
                routeListRevision = if (explicitRouteListResponse &&
                    broadcast.command == QuantumCommand.REQUEST_USER_ROUTE_LIST
                ) revision else current.routeListRevision,
                authoritative = true,
                syncStatus = QuantumControllerSyncStatus.LIVE,
                lastAuthoritativeAtMs = now,
            )
            is QuantumBroadcast.UserTurnedOff -> _state.value = current.copy(
                players = current.players.filterNot { it.userId.equals(broadcast.userId, ignoreCase = true) },
                revision = revision,
                lastFailure = null,
                syncStatus = if (current.authoritative) QuantumControllerSyncStatus.LIVE else current.syncStatus,
                lastAuthoritativeAtMs = if (current.authoritative) now else current.lastAuthoritativeAtMs,
            )
            QuantumBroadcast.BoardCleared -> _state.value = QuantumControllerState(
                revision = revision,
                authoritativeRevision = revision,
                routeListRevision = current.routeListRevision,
                authoritative = true,
                syncStatus = QuantumControllerSyncStatus.LIVE,
                lastAuthoritativeAtMs = now,
            )
            is QuantumBroadcast.Exception ->
                _state.value = current.copy(revision = revision, lastFailure = quantumFailure(broadcast.code))
            is QuantumBroadcast.BoardLit, null -> Unit
        }
    }

    // ── Locked operations (caller holds controller.writeMutex) ──

    private fun fenceMatches(expected: BoardLayerBoardIdentity?): Boolean = quantumBoardWriteFenceMatches(
        controller.link?.board, controller.state.value.quantumModel, expected,
    )

    private suspend fun writeFrames(frames: List<ByteArray>, expectedBoard: BoardLayerBoardIdentity?): BoardSendResult {
        for ((index, frame) in frames.withIndex()) {
            if (expectedBoard != null && !fenceMatches(expectedBoard)) return BoardSendResult.BOARD_MISMATCH
            // Never fragment: the controller parses one CRC-terminated frame per fff2 write.
            if (!quantumFrameFitsWriteLength(frame.size, maximumWriteLength)) return BoardSendResult.FRAME_TOO_LARGE
            val result = controller.writeChunks(listOf(frame))
            if (result != BoardSendResult.OK) return result
            if (index != frames.lastIndex) delay(INTER_FRAME_DELAY_MS)
        }
        return BoardSendResult.OK
    }

    private suspend fun readStateLocked() {
        if (!stateReadUsable) return
        val link = controller.link ?: return
        val service = link.quantumService ?: return
        if (!link.hasCharacteristic(service, BoardBleUuids.QUANTUM_STATE_CHAR)) return
        val pending = CompletableDeferred<ByteArray?>()
        stateRead = pending
        controller.centralRead(link.identifier, service, BoardBleUuids.QUANTUM_STATE_CHAR)
        val answered = withTimeoutOrNull(BoardConnectionController.WRITE_TIMEOUT_MS) { pending.await() ?: ByteArray(0) }
        if (stateRead === pending) stateRead = null
        if (answered == null || answered.isEmpty()) {
            // Read callbacks carry no token: never read fff4 on this link again,
            // so a late answer is harmless. fff1 remains usable.
            stateReadUsable = false
            return
        }
        val broadcast = QuantumBoardBroadcastParser.parse(answered)
        if (quantumFff4PublishesSnapshot(broadcast)) {
            apply(broadcast, quantumFff4ConfirmsExplicitRouteList(broadcast))
        }
    }

    private suspend fun requestRouteListLocked(expectedBoard: BoardLayerBoardIdentity? = null): BoardSendResult {
        if (expectedBoard != null && !fenceMatches(expectedBoard)) return BoardSendResult.BOARD_MISMATCH
        val before = _state.value
        _state.value = before.copy(lastFailure = null)
        accumulator.reset()
        routeListRequestActive = true
        try {
            val written = writeFrames(listOf(QuantumBoardPacketEncoder.requestRouteList()), expectedBoard)
            if (written != BoardSendResult.OK) return written
            // Deployed XL firmware answers through fff4 rather than fff1; read it
            // under the same lock. Notification-only controllers are covered by
            // the wait below.
            delay(ROUTE_LIST_READ_DELAY_MS)
            if (hasFreshExplicitQuantumRouteList(before, _state.value)) return BoardSendResult.OK
            readStateLocked()
            if (hasFreshExplicitQuantumRouteList(before, _state.value)) return BoardSendResult.OK
            val answered = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                state.first {
                    it.revision > before.revision &&
                        (it.lastFailure != null || it.routeListRevision > before.routeListRevision)
                }
            }
            if (answered == null) {
                // Retire the link so a late response cannot confirm a later request.
                controller.disconnect()
                return BoardSendResult.QUANTUM_STATE_UNAVAILABLE
            }
            return if (hasFreshExplicitQuantumRouteList(before, answered)) {
                BoardSendResult.OK
            } else {
                BoardSendResult.QUANTUM_STATE_UNAVAILABLE
            }
        } finally {
            routeListRequestActive = false
            accumulator.reset()
        }
    }

    private fun connectedToQuantum(): Boolean =
        controller.connection == ConnectionState.CONNECTED && controller.connectedBrand == BoardBrand.QUANTUM

    /** Pull the controller's authoritative player list; a failure retires the link. */
    suspend fun refresh(): Boolean {
        val operationLink = controller.link
        val result = controller.writeMutex.withLock {
            if (!connectedToQuantum()) return@withLock BoardSendResult.NOT_CONNECTED
            requestRouteListLocked()
        }
        if (result != BoardSendResult.OK && operationLink != null && controller.link === operationLink &&
            controller.connectedBrand == BoardBrand.QUANTUM
        ) {
            controller.disconnect()
        }
        return result == BoardSendResult.OK
    }

    fun refreshOnForeground() {
        if (!connectedToQuantum() ||
            !quantumForegroundRefreshRequired(_state.value, controller.clock.epochMillis(), REFRESH_INTERVAL_MS)
        ) return
        _state.value = _state.value.copy(syncStatus = QuantumControllerSyncStatus.SYNCING)
        controller.launch { refresh() }
    }

    /** TURN_OFF_USER → 50 ms → ACTIVATE_WALL → 250 ms → route list; success only on exact readback. */
    suspend fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        routeId: String,
        userId: String,
        color: Int,
        expectedPlayers: List<QuantumActivePlayer>,
        expectedBoard: BoardLayerBoardIdentity,
    ): BoardSendResult = controller.writeMutex.withLock {
        if (controller.connection != ConnectionState.CONNECTED) return BoardSendResult.NOT_CONNECTED
        if (controller.connectedBrand != BoardBrand.QUANTUM) return BoardSendResult.WRONG_BOARD_FAMILY
        if (!fenceMatches(expectedBoard)) return BoardSendResult.BOARD_MISMATCH
        // The anonymous sentinel is shared by other clients and never a scope.
        if (!isScopedQuantumUserId(userId, controller.ownsQuantumUserId)) {
            return BoardSendResult.QUANTUM_PRECONDITION_FAILED
        }
        // Re-read under the same lock as TURN_OFF_USER; reject any occupancy
        // change since the caller's conflict/capacity preflight.
        val listed = requestRouteListLocked(expectedBoard)
        if (listed != BoardSendResult.OK) return listed
        if (!quantumPlayersMatch(expectedPlayers, _state.value.players) ||
            !hasCompleteQuantumLedMapping(holds, placementToLed) ||
            !hasConfirmableQuantumDiodeCount(holds)
        ) return BoardSendResult.QUANTUM_PRECONDITION_FAILED

        controller.sending {
            val transition = try {
                QuantumBoardPacketEncoder.replaceUserRoute(
                    routeId = routeId,
                    userId = userId,
                    diodes = holds.mapNotNull { placementToLed[it.placementId] },
                    color = color and 0xffffff,
                )
            } catch (e: IllegalArgumentException) {
                return@sending BoardSendResult.INVALID_PAYLOAD
            }
            _state.value = _state.value.copy(lastFailure = null)
            val turnedOff = writeFrames(transition.take(1), expectedBoard)
            if (turnedOff != BoardSendResult.OK) return@sending turnedOff
            delay(TURN_OFF_TO_ACTIVATE_DELAY_MS)
            val activated = writeFrames(transition.drop(1), expectedBoard)
            if (activated != BoardSendResult.OK) return@sending activated
            // An acknowledged write proves transport only; the roster updates shortly after.
            delay(MUTATION_SETTLE_MS)
            val confirmed = requestRouteListLocked(expectedBoard)
            if (confirmed != BoardSendResult.OK) return@sending confirmed
            if (isQuantumProjectionConfirmed(_state.value, expectedPlayers, routeId, userId, color)) {
                BoardSendResult.OK
            } else {
                BoardSendResult.QUANTUM_NOT_CONFIRMED
            }
        }
    }

    /** Remove exactly one installation-owned layer, never all users. Absence is already success. */
    suspend fun removeLayer(
        userId: String,
        expectedRouteId: String,
        expectedBoard: BoardLayerBoardIdentity,
    ): BoardSendResult = controller.writeMutex.withLock {
        if (!connectedToQuantum()) return BoardSendResult.NOT_CONNECTED
        if (!fenceMatches(expectedBoard)) return BoardSendResult.BOARD_MISMATCH
        if (!isScopedQuantumUserId(userId, controller.ownsQuantumUserId)) {
            return BoardSendResult.QUANTUM_PRECONDITION_FAILED
        }
        val listed = requestRouteListLocked(expectedBoard)
        if (listed != BoardSendResult.OK) return listed
        val playersBefore = _state.value.players
        val current = playersBefore.firstOrNull { it.userId.equals(userId, ignoreCase = true) }
            ?: return BoardSendResult.OK
        if (!current.routeId.equals(expectedRouteId, ignoreCase = true)) {
            return BoardSendResult.QUANTUM_PRECONDITION_FAILED
        }
        controller.sending {
            _state.value = _state.value.copy(lastFailure = null)
            val frame = try {
                QuantumBoardPacketEncoder.turnOffUser(userId)
            } catch (e: IllegalArgumentException) {
                return@sending BoardSendResult.INVALID_PAYLOAD
            }
            val written = writeFrames(listOf(frame), expectedBoard)
            if (written != BoardSendResult.OK) return@sending written
            val confirmed = requestRouteListLocked(expectedBoard)
            if (confirmed != BoardSendResult.OK) return@sending confirmed
            if (isQuantumScopedRemovalConfirmed(_state.value, playersBefore, userId)) {
                BoardSendResult.OK
            } else {
                BoardSendResult.QUANTUM_NOT_CONFIRMED
            }
        }
    }

    /** TURN_OFF_ALL removes every climber's route: only behind an explicit, clearly worded user action. */
    suspend fun clearEntireWall(expectedBoard: BoardLayerBoardIdentity): BoardSendResult =
        controller.writeMutex.withLock {
            if (!connectedToQuantum()) return BoardSendResult.NOT_CONNECTED
            if (!fenceMatches(expectedBoard)) return BoardSendResult.BOARD_MISMATCH
            controller.sending {
                val before = requestRouteListLocked(expectedBoard)
                if (before != BoardSendResult.OK) return@sending before
                val written = writeFrames(listOf(QuantumBoardPacketEncoder.turnOffAll()), expectedBoard)
                if (written != BoardSendResult.OK) return@sending written
                val after = requestRouteListLocked(expectedBoard)
                if (after != BoardSendResult.OK) return@sending after
                if (_state.value.players.isEmpty()) BoardSendResult.OK else BoardSendResult.QUANTUM_NOT_CONFIRMED
            }
        }
}
