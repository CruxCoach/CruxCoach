package com.cruxcoach.android.boardcell

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsConnectionStage
import com.cruxcoach.android.fips.FipsRealmContext
import com.cruxcoach.android.fips.FipsDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class BoardCellHandoverLifecycle(
    /** Releases the source's exclusive physical board connection before the target may connect. */
    val releaseSource: suspend (BoardCellSnapshot) -> Boolean,
    /** Must acquire the target HOST role, board keep-alive and prove the board is connected. */
    val prepareTarget: suspend (BoardCellSnapshot) -> Boolean,
    /** Called on the old controller only after canonical HANDOVER_COMPLETED. */
    val completeSource: suspend (BoardCellSnapshot) -> Unit,
    /** Restores the source's physical connection when a released transfer aborts. */
    val abortSource: suspend (BoardCellSnapshot) -> Unit,
    /** Rolls back a target that prepared local HOST resources before source abort. */
    val abortTarget: suspend (BoardCellSnapshot) -> Unit,
    /** Attempts the exact physical board connection for controller recovery. */
    val recoverController: suspend (BoardCellSnapshot) -> Boolean,
)

data class IncomingControllerRequest(
    val requestId: String,
    val requesterNpub: String,
)

private data class PendingProjectionRequest(
    val request: BoardProjectionRequest,
    var retryAtMs: Long,
    var attempts: Int = 0,
)

enum class ControllerRequestState { IDLE, WAITING, ACCEPTED, DENIED, TIMED_OUT, FAILED }
enum class MeshMembershipTransition { IDLE, LEAVING, JOINING, ERROR }

@Singleton
class BoardCellManager @Inject constructor(
    @ApplicationContext context: Context,
    private val boardConnection: BoardBleConnection,
    private val runtime: FipsMeshRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meshTransport = BoardCellMeshTransport(runtime)
    private val durableStore = AndroidBoardCellDurableStore(context)
    private lateinit var coordinator: BoardCellCoordinator
    private val boardBindings = PhysicalBoardBindingStore(context)
    private var heldRuntime = false
    private val handoverRuntimeHeld = AtomicBoolean(false)
    private var nearbyRealmHeld = false
    private var meshBoardKeepAliveHeld = false
    private var activeNodeId = durableStore.localFallbackNodeId()
    private val boardRealmAvailable = AtomicBoolean(false)
    private val _snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    val snapshots = _snapshots.asStateFlow()
    private val _incomingControllerRequest = MutableStateFlow<IncomingControllerRequest?>(null)
    val incomingControllerRequest = _incomingControllerRequest.asStateFlow()
    private val _controllerRequestState = MutableStateFlow(ControllerRequestState.IDLE)
    val controllerRequestState = _controllerRequestState.asStateFlow()
    private val _membershipTransition = MutableStateFlow(MeshMembershipTransition.IDLE)
    val membershipTransition = _membershipTransition.asStateFlow()
    private var outgoingControllerRequestId: String? = null
    private var controllerRequestTimeoutJob: kotlinx.coroutines.Job? = null
    private var incomingControllerRequestTimeoutJob: kotlinx.coroutines.Job? = null
    // The suspendable transport callback provides real backpressure: after
    // ACCEPTED no command can disappear, while a hostile peer cannot grow RAM.
    private val sessionCommandChannel = Channel<InboundSessionCommand>(256)
    val sessionCommands = sessionCommandChannel.receiveAsFlow()
    private val commandAckChannel = Channel<BoardCommandAck>(256)
    val commandAcks = commandAckChannel.receiveAsFlow()
    private val projectionRequestChannel = Channel<InboundProjectionRequest>(64)
    val projectionRequests = projectionRequestChannel.receiveAsFlow()
    @Volatile private var handoverLifecycle: BoardCellHandoverLifecycle? = null
    private val handledHandoverPhase = ConcurrentHashMap.newKeySet<String>()
    private var recoveryJob: kotlinx.coroutines.Job? = null
    private var recoveryAttempt = 0
    private val sponsoredAt = ConcurrentHashMap<String, Long>()
    private val pendingProjectionRequests = ConcurrentHashMap<String, PendingProjectionRequest>()
    @Volatile private var authorizedRecoveryBoard: PhysicalBoardId? = null
    private var lastSnapshotTrace = ""
    private val nearbyJoinMutex = Mutex()
    private val pendingLocalLeave = AtomicBoolean(false)
    private val localRemovalCleanup = AtomicBoolean(false)

    init {
        current = this
        boardConnection.connectionGuard = { board -> !requiresControllerRequest(board) }
        meshTransport.onSessionCommand = { sessionCommandChannel.send(it) }
        meshTransport.onCommandAck = { _, ack ->
            if (ack.status != BoardCommandStatus.ACCEPTED) pendingProjectionRequests.remove(ack.commandId)
            commandAckChannel.send(ack)
        }
        meshTransport.onProjectionRequest = { projectionRequestChannel.send(it) }
        meshTransport.onControllerRequest = { sender, request ->
            val snapshot = snapshot()
            if (snapshot?.controllerId == activeNodeId && sender in snapshot.members) {
                val existing = _incomingControllerRequest.value
                if (existing != null && existing.requestId != request.requestId) {
                    meshTransport.sendControllerDecision(sender, snapshot,
                        BoardCellControllerDecision(request.requestId, accepted = false))
                } else {
                    _incomingControllerRequest.value = IncomingControllerRequest(request.requestId, sender)
                    incomingControllerRequestTimeoutJob?.cancel()
                    incomingControllerRequestTimeoutJob = scope.launch {
                        delay(CONTROLLER_REQUEST_TIMEOUT_MS)
                        if (_incomingControllerRequest.value?.requestId == request.requestId) {
                            _incomingControllerRequest.value = null
                        }
                    }
                    FipsDebugLog.event("handover", "controller_request_received",
                        "request" to FipsDebugLog.id(request.requestId),
                        "requester" to FipsDebugLog.id(sender))
                }
            }
        }
        meshTransport.onControllerDecision = { _, decision ->
            if (decision.requestId == outgoingControllerRequestId) {
                controllerRequestTimeoutJob?.cancel()
                outgoingControllerRequestId = null
                _controllerRequestState.value = if (decision.accepted) {
                    ControllerRequestState.ACCEPTED
                } else {
                    ControllerRequestState.DENIED
                }
                FipsDebugLog.event("handover", "controller_request_decided",
                    "request" to FipsDebugLog.id(decision.requestId), "accepted" to decision.accepted)
            }
        }
        scope.launch {
            runtime.messages.collect { message ->
                if (runtime.activeRealmId() == null) {
                    FipsDebugLog.event("boardcell", "mesh_message_dropped",
                        "reason" to "no active realm", "sender" to FipsDebugLog.id(message.senderNpub))
                    return@collect
                }
                val result = meshTransport.receive(message.senderNpub, message.payload, monotonicNow())
                FipsDebugLog.event("boardcell", "mesh_message_applied",
                    "sender" to FipsDebugLog.id(message.senderNpub), "bytes" to message.payload.size,
                    "result" to (result?.javaClass?.simpleName ?: "control"),
                    "reason" to ((result as? BoardCellApplyResult.Rejected)?.reason ?: "-"))
                refreshSelected()
                processHandover()
                processControllerRecovery()
            }
        }
        scope.launch { maintenanceLoop() }
        scope.launch {
            runtime.bluetoothAvailable.collectLatest { available ->
                if (!available) handleBluetoothOff()
            }
        }
        scope.launch {
            boardConnection.connectedBoardDescriptor.collectLatest { board ->
                if (board == null) {
                    FipsDebugLog.event("boardcell", "physical_board_disconnected",
                        "runtimeHeld" to heldRuntime)
                    boardRealmAvailable.set(false)
                    if (meshBoardKeepAliveHeld) {
                        boardConnection.releaseKeepAlive(BoardConnectionOwner.BOARD_MESH)
                        meshBoardKeepAliveHeld = false
                    }
                    // A physical-board disconnect alone does not prove that
                    // the FIPS mesh vanished. Bluetooth STATE_OFF is handled
                    // separately and ends live membership immediately.
                    val retained = _snapshots.value?.takeIf { activeNodeId in it.members }
                    // BoardBleConnection clears its physical UI selection on
                    // disconnect. Preserve the logical cell so maintenance,
                    // a transient GATT reconnect and fenced recovery keep running.
                    retained?.let { BoardCellScopeRegistry.joinCell(it.physicalBoardId, it.cellId) }
                    val memberStillActive = retained != null
                    if (!memberStillActive) BoardCellScopeRegistry.clearSelection()
                    if (heldRuntime && !memberStillActive) {
                        runtime.release(FipsMeshRuntime.OWNER_BOARD_CELL)
                        heldRuntime = false
                    }
                    return@collectLatest
                }
                if (nearbyRealmHeld) {
                    runtime.release(FipsMeshRuntime.OWNER_NEARBY_BOARD_CELL)
                    nearbyRealmHeld = false
                }
                val physical = PhysicalBoardIdentity.resolve(board, boardBindings.bindingFor(board.address))
                BoardCellScopeRegistry.replaceProvisionalSelection(physical)
                val restored = durableStore.snapshot(physical)
                val cellId = restored?.cellId ?: BoardCellId.forPhysical(physical)
                FipsDebugLog.event("boardcell", "physical_board_selected",
                    "address" to board.address, "brand" to board.boardBrand,
                    "physicalBoard" to FipsDebugLog.id(physical.value),
                    "cell" to FipsDebugLog.id(cellId.value), "durableSnapshot" to (restored != null))
                val meshAvailable = BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)
                if (meshAvailable && !heldRuntime) {
                    runtime.acquire(FipsMeshRuntime.OWNER_BOARD_CELL)
                    heldRuntime = true
                }
                if (meshAvailable) {
                    meshTransport.resetForRealm()
                    pendingProjectionRequests.clear()
                }
                val fipsActive = meshAvailable && runtime.activateRealm(FipsRealmContext(
                    cellId.value, cellId.value, meshName = board.displayName,
                ))
                if (!fipsActive && heldRuntime) {
                    runtime.release(FipsMeshRuntime.OWNER_BOARD_CELL)
                    heldRuntime = false
                }
                activeNodeId = if (fipsActive) runtime.localNpub else durableStore.localFallbackNodeId()
                FipsDebugLog.event("boardcell", "transport_selected", "api" to Build.VERSION.SDK_INT,
                    "transport" to if (fipsActive) "fips_l2cap" else "local_or_gatt_fallback",
                    "node" to FipsDebugLog.id(activeNodeId))
                val activeTransport: BoardCellTransport = if (fipsActive) meshTransport else NoOpBoardCellTransport
                boardRealmAvailable.set(true)
                coordinator = BoardCellCoordinator(activeNodeId, activeTransport, durableStore,
                    settleMs = 2_000, heartbeatTimeoutMs = CONTROLLER_LEASE_TIMEOUT_MS)
                if (fipsActive) meshTransport.attach(coordinator)
                val restoredForNode = restored?.takeIf { activeNodeId in it.members }
                if (!fipsActive && restoredForNode != null &&
                    coordinator.restoreTrustedSnapshot(restoredForNode, monotonicNow()) is BoardCellApplyResult.Applied) {
                    FipsDebugLog.event("boardcell", "durable_snapshot_restored",
                        "sequence" to restoredForNode.sequence, "term" to restoredForNode.controllerTerm,
                        "controller" to FipsDebugLog.id(restoredForNode.controllerId),
                        "role" to if (restoredForNode.controllerId == activeNodeId) "controller" else "member")
                    coordinator.recoverPendingWrite(physical)
                } else {
                    // FIPS membership is live. A process/radio return never
                    // resurrects the durable member set: first allow current
                    // members to sponsor this stable per-realm npub. Only an
                    // actually empty realm bootstraps after the grace period.
                    val knownSharedCell = fipsActive && restored != null &&
                        (restored.members - activeNodeId).isNotEmpty()
                    if (fipsActive && !knownSharedCell) durableStore.clearSnapshot(physical)
                    val rejoined = knownSharedCell && withTimeoutOrNull(REJOIN_SPONSOR_GRACE_MS) {
                        snapshots.filterNotNull().first { snapshot ->
                            snapshot.cellId == cellId && activeNodeId in snapshot.members
                        }
                    } != null
                    if (!knownSharedCell) {
                        FipsDebugLog.event("boardcell", "new_cell_claim_begin",
                            "cell" to FipsDebugLog.id(cellId.value), "node" to FipsDebugLog.id(activeNodeId))
                        _membershipTransition.value = if (claimAndSettle(physical, cellId) != null)
                            MeshMembershipTransition.IDLE else MeshMembershipTransition.ERROR
                    } else if (rejoined) {
                        _membershipTransition.value = MeshMembershipTransition.IDLE
                    } else if (!rejoined) {
                        // Never create a competing lineage merely because the
                        // previous live members are temporarily unreachable.
                        // The stable realm identity remains available for the
                        // normal permissionless sponsor/join path.
                        _membershipTransition.value = MeshMembershipTransition.ERROR
                        FipsDebugLog.warning("boardcell", "known_cell_rejoin_pending",
                            "cell" to FipsDebugLog.id(cellId.value),
                            "knownMembers" to restored.members.size)
                    }
                    coordinator.recoverPendingWrite(physical)
                }
                refreshSelected()
                processHandover()
            }
        }
    }

    suspend fun project(
        projection: BoardProjection,
        commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult {
        val board = writableBoard() ?: run {
            FipsDebugLog.warning("boardcell", "projection_refused", "command" to FipsDebugLog.id(commandId),
                "reason" to "BoardCell unavailable")
            return ProjectionResult.Refused("BoardCell unavailable")
        }
        FipsDebugLog.event("boardcell", "projection_requested", "command" to FipsDebugLog.id(commandId),
            "climb" to FipsDebugLog.id(projection.climbUuid), "angle" to projection.angle,
            "baseSequence" to baseSequence)
        return coordinator.project(board, projection, monotonicNow(), commandId, baseSequence, boardWrite).also {
            FipsDebugLog.event("boardcell", "projection_result", "command" to FipsDebugLog.id(commandId),
                "result" to it.javaClass.simpleName)
            refreshSelected()
        }
    }

    suspend fun projectExternal(
        boardWrite: suspend () -> Boolean,
        identify: suspend () -> BoardProjection?,
        commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null,
    ): ProjectionResult {
        val board = writableBoard() ?: return ProjectionResult.Refused("BoardCell unavailable")
        return coordinator.projectExternal(board, monotonicNow(), commandId, baseSequence, boardWrite, identify).also {
            refreshSelected()
        }
    }

    suspend fun replacePlaylist(state: BoardPlaylistState, commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null): Boolean {
        val board = writableBoard() ?: return false
        return (coordinator.replacePlaylist(board, state, monotonicNow(), commandId, baseSequence) != null).also {
            refreshSelected()
        }
    }

    fun sendSessionCommand(payload: ByteArray, context: BoardPlaylistCommandContext?,
        commandId: String = UUID.randomUUID().toString()): String? {
        if (!::coordinator.isInitialized || !BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) {
            FipsDebugLog.warning("playlist", "command_fips_unavailable",
                "command" to FipsDebugLog.id(commandId), "api" to Build.VERSION.SDK_INT)
            return null
        }
        val snapshot = snapshot() ?: return null
        val sent = meshTransport.sendSessionCommand(snapshot, commandId, payload, context)
        FipsDebugLog.event("playlist", if (sent) "command_sent" else "command_send_refused",
            "command" to FipsDebugLog.id(commandId), "kind" to context?.kind,
            "controller" to FipsDebugLog.id(snapshot.controllerId), "bytes" to payload.size,
            "baseRevision" to snapshot.playlistRevision)
        return commandId.takeIf { sent }
    }

    fun retrySessionCommand(payload: ByteArray, context: BoardPlaylistCommandContext?,
        commandId: String, basePlaylistRevision: Long): Boolean {
        val snapshot = snapshot() ?: return false
        return meshTransport.sendSessionCommand(snapshot, commandId, payload, context,
            basePlaylistRevision)
    }

    fun canSendViaMesh(): Boolean {
        val snapshot = snapshot() ?: return false
        return snapshot.availability == BoardCellAvailability.ACTIVE &&
            activeNodeId in snapshot.members && snapshot.controllerId != activeNodeId &&
            runtime.running.value
    }

    fun isLocalController(): Boolean = snapshot()?.controllerId == activeNodeId

    fun sendProjectionRequest(
        projection: BoardProjection,
        commandId: String = UUID.randomUUID().toString(),
    ): String? {
        val snapshot = snapshot() ?: return null
        val request = BoardProjectionRequest(commandId, projection, snapshot.sequence,
            snapshot.projection, snapshot.playlistRevision)
        if (!meshTransport.sendProjectionRequest(snapshot, request)) return null
        if (pendingProjectionRequests.size >= MAX_PENDING_PROJECTIONS) {
            pendingProjectionRequests.entries.minByOrNull { it.value.retryAtMs }?.key
                ?.let(pendingProjectionRequests::remove)
        }
        pendingProjectionRequests[commandId] = PendingProjectionRequest(
            request, monotonicNow() + PROJECTION_RETRY_INITIAL_MS)
        return commandId
    }

    suspend fun commitProjectionRequest(
        inbound: InboundProjectionRequest,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult {
        val board = writableBoard() ?: return ProjectionResult.Refused("BoardCell unavailable")
        val request = inbound.request
        val current = coordinator.snapshot(board)
            ?: return ProjectionResult.Refused("BoardCell unavailable")
        // Heartbeats and membership changes share the canonical sequence but
        // do not conflict with the board projection the participant saw. Only
        // rebase when both user-visible command domains are still unchanged.
        val semanticBase = request.semanticBaseSequence(current)
        return coordinator.project(board, request.projection, monotonicNow(), request.commandId,
            semanticBase, boardWrite).also { result ->
            val ack = when (result) {
                is ProjectionResult.Committed -> result.ack
                is ProjectionResult.Duplicate -> result.ack
                is ProjectionResult.Refused -> result.ack
                is ProjectionResult.BoardWriteFailed -> result.ack
            }
            if (ack != null) meshTransport.publishCommandAck(inbound.senderId, ack)
            refreshSelected()
        }
    }

    suspend fun commitSessionCommand(
        command: InboundSessionCommand,
        applyCommand: (BoardPlaylistState, Boolean) -> BoardPlaylistState?,
    ) {
        val board = BoardCellScopeRegistry.selected.value ?: return
        val committed = coordinator.applyPlaylistCommand(
            board, monotonicNow(), command.commandId, command.basePlaylistRevision, applyCommand)
        val snapshot = coordinator.snapshot(board) ?: return
        val ack = durableStore.commandAck(command.commandId) ?: BoardCommandAck(
            commandId = command.commandId,
            status = if (committed != null) BoardCommandStatus.COMMITTED else
                if (snapshot.controllerId != activeNodeId) BoardCommandStatus.NOT_CONTROLLER
                else BoardCommandStatus.REJECTED_STALE,
            cellId = snapshot.cellId,
            epoch = snapshot.epoch,
            controllerTerm = snapshot.controllerTerm,
            resultingSequence = snapshot.sequence,
            resultingHash = snapshot.stateHash,
        )
        if (durableStore.commandAck(command.commandId) == null) durableStore.recordAck(ack)
        FipsDebugLog.event("playlist", "command_decided", "command" to FipsDebugLog.id(command.commandId),
            "sender" to FipsDebugLog.id(command.senderId), "status" to ack.status,
            "baseRevision" to command.basePlaylistRevision,
            "resultSequence" to ack.resultingSequence, "detail" to ack.detail)
        meshTransport.publishCommandAck(command.senderId, ack)
        refreshSelected()
    }

    suspend fun commitLocalSessionCommand(
        commandId: String,
        basePlaylistRevision: Long,
        applyCommand: (BoardPlaylistState, Boolean) -> BoardPlaylistState?,
    ): BoardCommandAck? {
        val board = writableBoard() ?: return null
        coordinator.applyPlaylistCommand(board, monotonicNow(), commandId,
            basePlaylistRevision, applyCommand)
        val ack = durableStore.commandAck(commandId)
        refreshSelected()
        return ack
    }

    fun bindPhysicalBoardFallback(observedAddress: String, durableBindingId: String) {
        boardBindings.bind(observedAddress, durableBindingId)
    }

    /**
     * GATT admission supplies the full scope before a participant has a board
     * connection. This must exist before JOIN publishes the participant npub,
     * otherwise the first canonical membership snapshot would be dropped.
     */
    suspend fun prepareParticipantScope(physicalBoardId: String, boardCellId: String): Boolean {
        FipsDebugLog.event("boardcell", "participant_scope_prepare",
            "physicalBoard" to FipsDebugLog.id(physicalBoardId), "cell" to FipsDebugLog.id(boardCellId),
            "api" to Build.VERSION.SDK_INT)
        if (!BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) {
            FipsDebugLog.event("boardcell", "participant_scope_gatt_fallback", "reason" to "API below 29")
            return false
        }
        val physical = runCatching { PhysicalBoardId(physicalBoardId) }.getOrNull() ?: return false
        val cell = runCatching { BoardCellId(boardCellId) }.getOrNull() ?: return false
        if (BoardCellId.forPhysical(physical) != cell) return false
        BoardCellScopeRegistry.replaceProvisionalSelection(physical)
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        if (!withContext(Dispatchers.IO) {
                runtime.activateRealm(FipsRealmContext(cell.value, cell.value))
            }) return false
        activeNodeId = runtime.localNpub
        val existing = if (::coordinator.isInitialized) coordinator.snapshot(physical) else null
        if (existing?.cellId != cell || activeNodeId !in existing.members) {
            coordinator = BoardCellCoordinator(activeNodeId, meshTransport, durableStore,
                settleMs = 2_000, heartbeatTimeoutMs = CONTROLLER_LEASE_TIMEOUT_MS)
            meshTransport.attach(coordinator)
            durableStore.clearSnapshot(physical)
        }
        // A participant can replicate and issue scoped commands, but cannot
        // physically write until a committed handover plus board connection.
        boardRealmAvailable.set(false)
        FipsDebugLog.event("boardcell", "participant_scope_ready", "node" to FipsDebugLog.id(activeNodeId),
            "physicalWriteAllowed" to false)
        refreshSelected()
        return true
    }

    /** Enter a public BoardCell discovered over BLE. No physical-board pairing
     * or playlist-session approval is required. FIPS authenticates a direct
     * neighbor and CCJ1 verifies exact full realm/cell scope before admission. */
    suspend fun joinNearbyMesh(boardCellId: String, boardName: String? = null): Boolean =
        nearbyJoinMutex.withLock {
        if (!BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) return false
        val cell = runCatching { BoardCellId(boardCellId) }.getOrNull() ?: return false
        snapshot()?.let { active ->
            if (active.cellId == cell && activeNodeId in active.members &&
                active.availability == BoardCellAvailability.ACTIVE && runtime.running.value) return true
            _membershipTransition.value = MeshMembershipTransition.LEAVING
            if (!leaveCurrentMeshLocked()) {
                _membershipTransition.value = MeshMembershipTransition.ERROR
                return false
            }
        }
        _membershipTransition.value = MeshMembershipTransition.JOINING
        if (!nearbyRealmHeld) {
            runtime.acquire(FipsMeshRuntime.OWNER_NEARBY_BOARD_CELL)
            nearbyRealmHeld = true
        }
        // A failed handover/reconnect can leave this exact realm running even
        // though the local canonical replica was cleared. Reusing it also
        // reuses old connection progress and native peer entries, which can
        // make a join skip phases or reject the new channel as a duplicate.
        // Always make an explicit join without membership a fresh transport
        // generation; the persistent realm key keeps the node identity stable.
        if (runtime.activeRealmId() == cell.value && runtime.running.value) {
            runtime.endRealm(cell.value)
        }
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        val activated = try {
            withContext(Dispatchers.IO) {
                runtime.activateRealm(FipsRealmContext(cell.value, cell.value, meshName = boardName))
            }
        } catch (failure: CancellationException) {
            rollbackNearbyJoin(cell)
            _membershipTransition.value = MeshMembershipTransition.IDLE
            throw failure
        } catch (failure: Exception) {
            FipsDebugLog.warning("boardcell", "nearby_mesh_join_failed",
                "cell" to FipsDebugLog.id(cell.value),
                "error" to (failure.message ?: failure.javaClass.simpleName))
            false
        }
        if (!activated) {
            rollbackNearbyJoin(cell)
            _membershipTransition.value = MeshMembershipTransition.ERROR
            return false
        }
        activeNodeId = runtime.localNpub
        coordinator = BoardCellCoordinator(activeNodeId, meshTransport, durableStore,
            settleMs = 2_000, heartbeatTimeoutMs = CONTROLLER_LEASE_TIMEOUT_MS)
        meshTransport.attach(coordinator)
        boardRealmAvailable.set(false)
        FipsDebugLog.event("boardcell", "nearby_mesh_join_started",
            "cell" to FipsDebugLog.id(cell.value), "node" to FipsDebugLog.id(activeNodeId))
        refreshSelected()
        val failedPhase = try {
            JOIN_PHASES.firstOrNull { phase ->
                !awaitNearbyJoinPhase(cell, phase.stage, phase.timeoutMs)
            }
        } catch (failure: CancellationException) {
            rollbackNearbyJoin(cell)
            _membershipTransition.value = MeshMembershipTransition.IDLE
            throw failure
        }
        val joined = failedPhase == null && snapshot()?.let {
            it.cellId == cell && activeNodeId in it.members
        } == true
        if (joined) {
            _membershipTransition.value = MeshMembershipTransition.IDLE
            val current = snapshot()
            runtime.settleActiveMembership(cell.value)
            FipsDebugLog.event("boardcell", "nearby_mesh_join_succeeded",
                "cell" to FipsDebugLog.id(cell.value), "members" to current?.members?.size,
                "controller" to FipsDebugLog.id(current?.controllerId))
        } else {
            _membershipTransition.value = MeshMembershipTransition.ERROR
            FipsDebugLog.warning("boardcell", "nearby_mesh_join_timed_out",
                "cell" to FipsDebugLog.id(cell.value),
                "phase" to (failedPhase?.name ?: "membership_snapshot"),
                "timeoutMs" to failedPhase?.timeoutMs)
            rollbackNearbyJoin(cell)
        }
        return joined
    }

    private suspend fun awaitNearbyJoinPhase(
        cell: BoardCellId,
        expectedStage: FipsConnectionStage?,
        timeoutMs: Long,
    ): Boolean {
        val reached = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val current = snapshot()
                if (current?.cellId == cell && activeNodeId in current.members) return@withTimeoutOrNull true
                if (expectedStage != null) {
                    val progress = runtime.connectionProgress.value
                    if (progress.cellId == cell.value && progress.stage.ordinal >= expectedStage.ordinal) {
                        return@withTimeoutOrNull true
                    }
                }
                delay(JOIN_PHASE_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        FipsDebugLog.event("boardcell", if (reached) "nearby_mesh_join_phase_reached"
            else "nearby_mesh_join_phase_timed_out",
            "cell" to FipsDebugLog.id(cell.value),
            "phase" to (expectedStage?.name ?: "MEMBERSHIP_SNAPSHOT"),
            "timeoutMs" to timeoutMs)
        return reached
    }

    /** Voluntary leave is canonical when reachable and converges through the
     * same liveness timeout when the last request races a partition. */
    suspend fun leaveCurrentMesh(): Boolean = nearbyJoinMutex.withLock {
        _membershipTransition.value = MeshMembershipTransition.LEAVING
        val left = leaveCurrentMeshLocked()
        _membershipTransition.value = if (left) MeshMembershipTransition.IDLE
            else MeshMembershipTransition.ERROR
        left
    }

    /** A user-requested physical-board disconnect is also an unconditional
     * local mesh leave. Prefer the canonical leave/handover path, but never
     * retain or resurrect local membership merely because the last peer became
     * unreachable while the user was pressing Disconnect. Remote members will
     * converge through their normal heartbeat eviction in that partition. */
    suspend fun leaveMeshForBoardDisconnect(): Boolean = withContext(NonCancellable) {
        nearbyJoinMutex.withLock {
            _membershipTransition.value = MeshMembershipTransition.LEAVING
            val initial = snapshot()
            try {
                val canonical = runCatching { leaveCurrentMeshLocked() }.getOrElse { failure ->
                    FipsDebugLog.warning("boardcell", "user_disconnect_leave_failed",
                        "error" to (failure.message ?: failure.javaClass.simpleName))
                    false
                }
                if (!canonical && initial != null) {
                    FipsDebugLog.warning("boardcell", "user_disconnect_forced_local_leave",
                        "cell" to FipsDebugLog.id(initial.cellId.value),
                        "controller" to FipsDebugLog.id(initial.controllerId),
                        "members" to initial.members.size)
                    teardownLocalMembership(initial)
                }
                canonical
            } finally {
                _membershipTransition.value = MeshMembershipTransition.IDLE
            }
        }
    }

    private suspend fun leaveCurrentMeshLocked(): Boolean {
        val initial = snapshot() ?: return true
        if (activeNodeId !in initial.members) {
            teardownLocalMembership(initial)
            return true
        }
        pendingLocalLeave.set(true)
        try {
            if (initial.controllerId == activeNodeId) {
                val others = initial.members - activeNodeId
                if (others.isEmpty()) {
                    if (boardConnection.connectedBoard != null &&
                        handoverLifecycle?.releaseSource?.invoke(initial) != true) return false
                    teardownLocalMembership(initial)
                    return true
                }
                val target = coordinator.liveSuccessors(initial.physicalBoardId, monotonicNow(),
                    MEMBER_LIVENESS_TIMEOUT_MS).firstOrNull() ?: return false
                if (handoverRuntimeHeld.compareAndSet(false, true)) {
                    runtime.acquire(FipsMeshRuntime.OWNER_HANDOVER)
                }
                val prepared = coordinator.prepareHandover(initial.physicalBoardId, target, monotonicNow())
                if (prepared == null) {
                    releaseHandoverRuntime()
                    return false
                }
                processHandover()
                val completed = withTimeoutOrNull(HANDOVER_LEAVE_TIMEOUT_MS) {
                    snapshots.filterNotNull().first { snapshot ->
                        snapshot.cellId == initial.cellId && snapshot.controllerId != activeNodeId &&
                            snapshot.handover?.phase == HandoverPhase.COMPLETED
                    }
                } ?: return false
                processHandover()
                val sourceDisconnected = boardConnection.connectedBoard == null ||
                    withTimeoutOrNull(HANDOVER_SOURCE_CLEANUP_TIMEOUT_MS) {
                        boardConnection.connectedBoardDescriptor.first { it == null }
                        true
                    } == true
                if (!sourceDisconnected) return false
                requestCanonicalLeave(completed)
            } else {
                requestCanonicalLeave(initial)
            }
            val removed = withTimeoutOrNull(MEMBER_LEAVE_TIMEOUT_MS) {
                snapshots.first { snapshot -> snapshot == null || snapshot.cellId != initial.cellId ||
                    activeNodeId !in snapshot.members }
            }
            teardownLocalMembership(removed ?: initial)
            return true
        } finally {
            pendingLocalLeave.set(false)
        }
    }

    private suspend fun requestCanonicalLeave(currentSnapshot: BoardCellSnapshot) {
        val requestId = UUID.randomUUID().toString()
        repeat(3) {
            if (activeNodeId !in (snapshot()?.members ?: emptySet())) return
            meshTransport.sendMemberLeaveRequest(currentSnapshot, requestId)
            delay(250)
        }
    }

    private suspend fun teardownLocalMembership(
        snapshot: BoardCellSnapshot,
        preserveRejoinHint: Boolean = false,
    ) {
        boardRealmAvailable.set(false)
        if (::coordinator.isInitialized) coordinator.forgetLocalReplica(
            snapshot.physicalBoardId,
            clearDurableSnapshot = !preserveRejoinHint,
        )
        if (runtime.activeRealmId() == snapshot.cellId.value) runtime.endRealm(snapshot.cellId.value)
        if (nearbyRealmHeld) {
            runtime.release(FipsMeshRuntime.OWNER_NEARBY_BOARD_CELL)
            nearbyRealmHeld = false
        }
        if (heldRuntime) {
            runtime.release(FipsMeshRuntime.OWNER_BOARD_CELL)
            heldRuntime = false
        }
        releaseHandoverRuntime()
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        sponsoredAt.clear()
        _snapshots.value = null
        BoardCellScopeRegistry.clearSelection()
        if (meshBoardKeepAliveHeld) {
            boardConnection.releaseKeepAlive(BoardConnectionOwner.BOARD_MESH)
            meshBoardKeepAliveHeld = false
        }
        runtime.startNearbyDiscovery()
    }

    private suspend fun handleBluetoothOff() {
        val current = snapshot() ?: return
        pendingLocalLeave.set(true)
        try {
            // The radio is already unavailable, so no leave packet can be
            // forged or queued here. The controller's three missed member
            // heartbeats provide the canonical removal.
            teardownLocalMembership(current,
                preserveRejoinHint = (current.members - activeNodeId).isNotEmpty())
            _membershipTransition.value = MeshMembershipTransition.IDLE
        } finally {
            pendingLocalLeave.set(false)
        }
    }

    private suspend fun rollbackNearbyJoin(cell: BoardCellId) {
        snapshot()?.takeIf { it.cellId == cell }?.let {
            if (::coordinator.isInitialized) coordinator.forgetLocalReplica(it.physicalBoardId)
        }
        runtime.endRealm(cell.value)
        if (nearbyRealmHeld) {
            runtime.release(FipsMeshRuntime.OWNER_NEARBY_BOARD_CELL)
            nearbyRealmHeld = false
        }
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        sponsoredAt.clear()
        _snapshots.value = null
        if (boardConnection.connectedBoard == null) BoardCellScopeRegistry.clearSelection()
        runtime.startNearbyDiscovery()
    }

    fun installHandoverLifecycle(value: BoardCellHandoverLifecycle?) { handoverLifecycle = value }

    /** A member may discover the physical board, but must not open a second
     * controller connection while the canonical controller is another node. */
    fun requiresControllerRequest(board: com.cruxcoach.android.ble.DiscoveredBoard): Boolean {
        if (board.isCruxRelay) return false
        val snapshot = snapshot() ?: return false
        if (snapshot.controllerId == activeNodeId || activeNodeId !in snapshot.members) return false
        if (snapshot.handover?.targetControllerId == activeNodeId &&
            snapshot.handover.phase in setOf(HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY, HandoverPhase.COMMITTED)) return false
        val physical = runCatching {
            PhysicalBoardIdentity.resolve(board, boardBindings.bindingFor(board.address))
        }.getOrNull() ?: return false
        if (physical == authorizedRecoveryBoard) return false
        return physical == snapshot.physicalBoardId
    }

    /** Ask the current controller for an orderly transfer. Merely discovering
     * or tapping the board never opens GATT on the requesting device. */
    fun requestControllerTransfer(): Boolean {
        val snapshot = snapshot() ?: return false
        if (snapshot.controllerId == activeNodeId || activeNodeId !in snapshot.members) return false
        val request = BoardCellControllerRequest(UUID.randomUUID().toString(), activeNodeId)
        val sent = meshTransport.sendControllerRequest(snapshot, request)
        if (sent) {
            outgoingControllerRequestId = request.requestId
            _controllerRequestState.value = ControllerRequestState.WAITING
            controllerRequestTimeoutJob?.cancel()
            controllerRequestTimeoutJob = scope.launch {
                delay(CONTROLLER_REQUEST_TIMEOUT_MS)
                if (outgoingControllerRequestId == request.requestId &&
                    _controllerRequestState.value == ControllerRequestState.WAITING) {
                    outgoingControllerRequestId = null
                    _controllerRequestState.value = ControllerRequestState.TIMED_OUT
                }
            }
            FipsDebugLog.event("handover", "controller_request_sent",
                "request" to FipsDebugLog.id(request.requestId),
                "controller" to FipsDebugLog.id(snapshot.controllerId))
        }
        return sent
    }

    fun approveControllerTransfer(requestId: String) {
        val pending = _incomingControllerRequest.value?.takeIf { it.requestId == requestId } ?: return
        val board = BoardCellScopeRegistry.selected.value ?: return
        scope.launch {
            val snapshot = coordinator.snapshot(board) ?: return@launch
            if (snapshot.controllerId != activeNodeId || pending.requesterNpub !in snapshot.members) return@launch
            if (handoverRuntimeHeld.compareAndSet(false, true)) {
                runtime.acquire(FipsMeshRuntime.OWNER_HANDOVER)
            }
            val prepared = coordinator.prepareHandover(board, pending.requesterNpub, monotonicNow())
            if (prepared != null) {
                meshTransport.sendControllerDecision(pending.requesterNpub,
                    coordinator.snapshot(board) ?: snapshot,
                    BoardCellControllerDecision(requestId, accepted = true))
                _incomingControllerRequest.value = null
                incomingControllerRequestTimeoutJob?.cancel()
                refreshSelected()
                processHandover()
            } else {
                releaseHandoverRuntime()
            }
        }
    }

    fun denyControllerTransfer(requestId: String) {
        val pending = _incomingControllerRequest.value?.takeIf { it.requestId == requestId } ?: return
        val snapshot = snapshot() ?: return
        if (snapshot.controllerId != activeNodeId) return
        meshTransport.sendControllerDecision(pending.requesterNpub, snapshot,
            BoardCellControllerDecision(requestId, accepted = false))
        _incomingControllerRequest.value = null
        incomingControllerRequestTimeoutJob?.cancel()
    }

    /** No implicit election: caller must identify the intended, user-visible target. */
    fun requestOrderlyHandover(targetControllerId: String): Boolean {
        if (!::coordinator.isInitialized || targetControllerId.isBlank()) return false
        val board = BoardCellScopeRegistry.selected.value ?: return false
        val snapshot = coordinator.snapshot(board) ?: return false
        if (snapshot.controllerId != activeNodeId || targetControllerId !in snapshot.members) return false
        scope.launch {
            if (handoverRuntimeHeld.compareAndSet(false, true)) {
                runtime.acquire(FipsMeshRuntime.OWNER_HANDOVER)
            }
            FipsDebugLog.event("handover", "requested", "source" to FipsDebugLog.id(activeNodeId),
                "target" to FipsDebugLog.id(targetControllerId), "sequence" to snapshot.sequence,
                "term" to snapshot.controllerTerm)
            val prepared = coordinator.prepareHandover(board, targetControllerId, monotonicNow())
            if (prepared == null) releaseHandoverRuntime() else processHandover()
            refreshSelected()
        }
        return true
    }

    /** Safe convenience only when membership proves there is exactly one explicit successor. */
    fun soleSuccessor(): String? = snapshot()?.members?.filter { it != activeNodeId }?.singleOrNull()

    /** A controller with remaining members never tears the board mesh down.
     * Voluntary disconnect becomes an orderly transfer to the best reachable member. */
    fun handoverBeforeControllerDisconnect(): Boolean {
        val snapshot = snapshot() ?: return false
        if (snapshot.controllerId != activeNodeId) return false
        val others = snapshot.members - activeNodeId
        if (others.isEmpty()) return false
        val target = runtime.directAuthenticatedPeers().filter { it in others }.sorted().firstOrNull()
            ?: return true // block disconnect; no safe reachable successor yet
        requestOrderlyHandover(target)
        return true
    }

    fun freezeForTransportRealmSwitch() {
        boardRealmAvailable.set(false)
        if (!::coordinator.isInitialized) return
        BoardCellScopeRegistry.selected.value?.let { board -> scope.launch {
            coordinator.freezeForTransportRealmSwitch(board); refreshSelected()
        } }
    }

    /** API 28 GATT participants have no authenticated distributed term transfer. */
    fun freezeLegacyParticipantWrites() {
        if (BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT) || !::coordinator.isInitialized) return
        BoardCellScopeRegistry.selected.value?.let { board -> scope.launch {
            coordinator.freezeForTransportRealmSwitch(board)
            refreshSelected()
        } }
    }

    fun approveMember(memberNpub: String) {
        if (!::coordinator.isInitialized) return
        BoardCellScopeRegistry.selected.value?.let { board -> scope.launch {
            coordinator.joinMember(board, memberNpub, monotonicNow()); refreshSelected()
        } }
    }

    suspend fun operatorRecoverFork(): Boolean {
        val board = BoardCellScopeRegistry.selected.value ?: return false
        return coordinator.operatorRecoverFork(board, monotonicNow()) != null
    }

    suspend fun reprojectAfterRecovery(
        projection: BoardProjection,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult {
        val board = writableBoard() ?: return ProjectionResult.Refused("BoardCell unavailable")
        return coordinator.reprojectAfterRecovery(board, projection, monotonicNow(), boardWrite = boardWrite)
            .also { refreshSelected() }
    }

    fun snapshot(): BoardCellSnapshot? = if (::coordinator.isInitialized)
        BoardCellScopeRegistry.selected.value?.let(coordinator::snapshot) else null

    private suspend fun maintenanceLoop() {
        while (true) {
            delay(CONTROLLER_HEARTBEAT_INTERVAL_MS)
            if (!::coordinator.isInitialized) continue
            // Offline share snapshots and transfers deliberately suspend FIPS.
            // Advancing controller heartbeats while transport is unavailable
            // only fills the durable outbox periodically and competes
            // with SQLite/VACUUM for CPU. Freeze logical mesh time with the
            // native runtime and resume maintenance when sharing ends.
            BoardCellScopeRegistry.selected.value?.let { board ->
                val now = monotonicNow()
                val snapshot = coordinator.snapshot(board)
                if (snapshot != null && runtime.running.value && !runtime.isSuspendedForBulkTransfer()) {
                    val nearbyCandidates = runtime.directAuthenticatedPeers()
                    sponsoredAt.keys.removeAll { candidate ->
                        candidate !in nearbyCandidates
                    }
                    nearbyCandidates.forEach { peer ->
                        val last = sponsoredAt[peer]
                        if (last != null && now - last < MEMBER_SPONSOR_RETRY_MS) return@forEach
                        if (snapshot.controllerId == activeNodeId) {
                            if (peer in snapshot.members) {
                                if (meshTransport.sendSnapshotTo(snapshot, peer)) sponsoredAt[peer] = now
                            } else {
                                FipsDebugLog.event("boardcell", "nearby_member_auto_admitted",
                                    "peer" to FipsDebugLog.id(peer), "cell" to FipsDebugLog.id(snapshot.cellId.value))
                                coordinator.joinMember(board, peer, now)
                                sponsoredAt[peer] = now
                            }
                        } else {
                            if (meshTransport.sponsorMember(snapshot, peer)) {
                                sponsoredAt[peer] = now
                                FipsDebugLog.event("boardcell", "nearby_member_sponsored",
                                    "peer" to FipsDebugLog.id(peer),
                                    "controller" to FipsDebugLog.id(snapshot.controllerId))
                            }
                        }
                    }
                    pendingProjectionRequests.values.forEach { pending ->
                        if (snapshot.projection == pending.request.projection) {
                            pendingProjectionRequests.remove(pending.request.commandId)
                            return@forEach
                        }
                        if (now < pending.retryAtMs) return@forEach
                        if (!meshTransport.sendProjectionRequest(snapshot, pending.request)) return@forEach
                        pending.attempts++
                        pending.retryAtMs = now + minOf(PROJECTION_RETRY_MAX_MS,
                            PROJECTION_RETRY_INITIAL_MS shl pending.attempts.coerceAtMost(3))
                    }
                    if (activeNodeId in snapshot.members && snapshot.controllerId != activeNodeId &&
                        !pendingLocalLeave.get()) {
                        meshTransport.sendMemberHeartbeat(snapshot, now / CONTROLLER_HEARTBEAT_INTERVAL_MS)
                    }
                    if (snapshot.controllerId == activeNodeId) {
                        val evicted = coordinator.evictExpiredMembers(
                            board, now, MEMBER_LIVENESS_TIMEOUT_MS,
                        )
                        if (evicted.isNotEmpty()) {
                            val afterEviction = coordinator.snapshot(board)
                            if (afterEviction?.members == setOf(activeNodeId)) {
                                // Clear native ghost links only after canonical
                                // liveness has removed the final remote member.
                                // The controller continues advertising with the
                                // same realm identity and can accept a clean
                                // normal join immediately afterwards.
                                runtime.recycleIdleMeshTransport("last remote member timed out")
                            }
                        }
                    }
                }
                if (boardRealmAvailable.get() && !runtime.isSuspendedForBulkTransfer()) {
                    coordinator.heartbeat(board, monotonicNow())
                }
                // The source must still time out and roll back after its board
                // disconnect makes boardRealmAvailable false.
                if (!runtime.isSuspendedForBulkTransfer() || handoverRuntimeHeld.get()) {
                    coordinator.expireLocalDeadlines(monotonicNow())
                    refreshSelected()
                    processHandover()
                    processControllerRecovery()
                }
            }
            if (runtime.running.value) {
                meshTransport.retryOutbox()
                meshTransport.antiEntropy()
            }
        }
    }

    private suspend fun processHandover() {
        val board = BoardCellScopeRegistry.selected.value ?: return
        val snapshot = coordinator.snapshot(board) ?: return
        val h = snapshot.handover ?: return
        val phaseKey = "${h.transferId}:${h.phase}"
        if (!handledHandoverPhase.add(phaseKey)) return
        FipsDebugLog.event("handover", "phase_observed", "transfer" to FipsDebugLog.id(h.transferId),
            "phase" to h.phase, "source" to FipsDebugLog.id(h.sourceControllerId),
            "target" to FipsDebugLog.id(h.targetControllerId), "local" to FipsDebugLog.id(activeNodeId))
        val lifecycle = handoverLifecycle
        when {
            h.sourceControllerId == activeNodeId && h.phase == HandoverPhase.PREPARED -> {
                val released = lifecycle?.releaseSource?.invoke(snapshot) == true
                if (released) coordinator.sourceReleased(board, h.transferId, monotonicNow())
                else handledHandoverPhase.remove(phaseKey)
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.SOURCE_RELEASED -> {
                val ready = lifecycle?.prepareTarget?.invoke(snapshot) == true && boardRealmAvailable.get()
                if (ready) coordinator.targetReady(board, "host-board-ready:${h.transferId}")
                else handledHandoverPhase.remove(phaseKey)
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.COMMITTED -> {
                val ready = lifecycle?.prepareTarget?.invoke(snapshot) == true && boardRealmAvailable.get()
                if (ready) {
                    coordinator.completeHandover(board, h.transferId, monotonicNow())
                    _controllerRequestState.value = ControllerRequestState.IDLE
                }
                else handledHandoverPhase.remove(phaseKey)
            }
            h.sourceControllerId == activeNodeId && h.phase == HandoverPhase.COMPLETED -> {
                lifecycle?.completeSource?.invoke(snapshot)
                releaseHandoverRuntime()
            }
            h.sourceControllerId == activeNodeId && h.phase == HandoverPhase.ABORTED -> {
                lifecycle?.abortSource?.invoke(snapshot)
                releaseHandoverRuntime()
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.ABORTED -> {
                lifecycle?.abortTarget?.invoke(snapshot)
                _controllerRequestState.value = ControllerRequestState.FAILED
            }
        }
        refreshSelected()
    }

    private fun releaseHandoverRuntime() {
        if (handoverRuntimeHeld.compareAndSet(true, false)) {
            runtime.release(FipsMeshRuntime.OWNER_HANDOVER)
        }
    }

    private fun processControllerRecovery() {
        val snapshot = snapshot()
        if (snapshot?.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER ||
            activeNodeId !in snapshot.members) {
            recoveryJob?.cancel(); recoveryJob = null; recoveryAttempt = 0
            return
        }
        if (snapshot.controllerId in runtime.directAuthenticatedPeers() || recoveryJob?.isActive == true) return
        // Direct-neighbor views differ across a multi-hop mesh and therefore
        // cannot define a shared rank. Derive every candidate's bounded delay
        // from the canonical snapshot instead; stale members consume at most a
        // short slot and never serialize the whole election.
        val delayMs = BoardCellRecoveryElection.delayMs(snapshot, activeNodeId, recoveryAttempt)
            ?: return
        val baseTerm = snapshot.controllerTerm
        val baseHash = snapshot.stateHash
        recoveryJob = scope.launch {
            delay(delayMs)
            val current = snapshot()
            if (current?.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER ||
                current.controllerTerm != baseTerm || current.stateHash != baseHash ||
                current.controllerId in runtime.directAuthenticatedPeers()) return@launch
            authorizedRecoveryBoard = current.physicalBoardId
            val connected = try {
                handoverLifecycle?.recoverController?.invoke(current) == true
            } finally {
                authorizedRecoveryBoard = null
            }
            if (connected) {
                coordinator.recoverController(current.physicalBoardId,
                    "exclusive-board-connection:${UUID.randomUUID()}", monotonicNow())
                refreshSelected()
            } else {
                recoveryAttempt++
                if (recoveryAttempt >= MAX_LOCAL_RECOVERY_ATTEMPTS) {
                    val stale = snapshot()
                    if (stale?.availability == BoardCellAvailability.FROZEN_NEEDS_CONTROLLER &&
                        activeNodeId in stale.members) {
                        FipsDebugLog.warning("boardcell", "local_membership_expired",
                            "reason" to "stable mesh/controller disconnect",
                            "attempts" to recoveryAttempt)
                        teardownLocalMembership(stale,
                            preserveRejoinHint = (stale.members - activeNodeId).isNotEmpty())
                        _membershipTransition.value = MeshMembershipTransition.ERROR
                    }
                }
            }
        }
    }

    private suspend fun claimAndSettle(board: PhysicalBoardId, cellId: BoardCellId): BoardCellSnapshot? {
        val claim = coordinator.beginClaim(board, cellId, monotonicNow())
        FipsDebugLog.event("boardcell", "claim_created", "claimant" to FipsDebugLog.id(claim.claimantId),
            "lineage" to FipsDebugLog.id(claim.lineageId), "term" to claim.proposedTerm)
        repeat(8) { delay(250); if (BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) meshTransport.publishClaim(claim) }
        repeat(9) { coordinator.settle(board, monotonicNow())?.let {
            FipsDebugLog.event("boardcell", "claim_settled", "controller" to FipsDebugLog.id(it.controllerId),
                "sequence" to it.sequence, "availability" to it.availability)
            return it
        }; delay(250) }
        return coordinator.settle(board, monotonicNow()).also {
            FipsDebugLog.event("boardcell", "claim_settle_final", "success" to (it != null),
                "controller" to FipsDebugLog.id(it?.controllerId))
        }
    }

    private fun writableBoard(): PhysicalBoardId? {
        if (!boardRealmAvailable.get() || !::coordinator.isInitialized) return null
        return BoardCellScopeRegistry.selected.value
    }

    private fun refreshSelected() {
        val next = snapshot()
        _snapshots.value = next
        if (next != null && activeNodeId in next.members) {
            runtime.settleActiveMembership(next.cellId.value)
        }
        if (next != null && activeNodeId !in next.members && !pendingLocalLeave.get() &&
            localRemovalCleanup.compareAndSet(false, true)) {
            scope.launch {
                try { teardownLocalMembership(next) }
                finally { localRemovalCleanup.set(false) }
            }
        }
        val shouldHoldBoard = next?.let { activeNodeId in it.members } == true &&
            boardConnection.connectedBoard != null
        if (shouldHoldBoard && !meshBoardKeepAliveHeld) {
            boardConnection.acquireKeepAlive(BoardConnectionOwner.BOARD_MESH)
            meshBoardKeepAliveHeld = true
        } else if (!shouldHoldBoard && meshBoardKeepAliveHeld) {
            boardConnection.releaseKeepAlive(BoardConnectionOwner.BOARD_MESH)
            meshBoardKeepAliveHeld = false
        }
        val summary = next?.let {
            "${it.sequence}|${it.controllerTerm}|${it.controllerId}|${it.availability}|" +
                "${it.members.sorted()}|${it.playlistRevision}|${it.projection?.climbUuid}|${it.handover?.phase}"
        }.orEmpty()
        if (summary != lastSnapshotTrace) {
            lastSnapshotTrace = summary
            FipsDebugLog.event("boardcell", "state_changed",
                "cell" to FipsDebugLog.id(next?.cellId?.value),
                "sequence" to next?.sequence, "term" to next?.controllerTerm,
                "controller" to FipsDebugLog.id(next?.controllerId),
                "localRole" to when {
                    next == null -> "none"
                    next.controllerId == activeNodeId -> "controller"
                    activeNodeId in next.members -> "member"
                    else -> "observer"
                },
                "members" to (next?.members?.joinToString { FipsDebugLog.id(it) } ?: "none"),
                "availability" to next?.availability,
                "projection" to next?.projection?.let { "${FipsDebugLog.id(it.climbUuid)}@${it.angle}" },
                "playlistRevision" to next?.playlistRevision,
                "playlistIndex" to next?.playlist?.currentIndex,
                "playlistItems" to next?.playlist?.items?.size,
                "handover" to next?.handover?.phase)
        }
    }

    private fun monotonicNow(): Long = SystemClock.elapsedRealtime()

    companion object {
        @Volatile internal var current: BoardCellManager? = null
            private set
        private const val CONTROLLER_REQUEST_TIMEOUT_MS = 30_000L
        private const val MEMBER_SPONSOR_RETRY_MS = 6_000L
        private const val PROJECTION_RETRY_INITIAL_MS = 2_000L
        private const val PROJECTION_RETRY_MAX_MS = 15_000L
        private const val MAX_PENDING_PROJECTIONS = 128
        private const val JOIN_PHASE_POLL_MS = 100L
        private const val REJOIN_SPONSOR_GRACE_MS = 6_000L
        private const val MEMBER_LEAVE_TIMEOUT_MS = 7_000L
        private const val HANDOVER_LEAVE_TIMEOUT_MS = 50_000L
        private const val HANDOVER_SOURCE_CLEANUP_TIMEOUT_MS = 5_000L
        private const val CONTROLLER_HEARTBEAT_INTERVAL_MS = 2_000L

        private data class NearbyJoinPhase(
            val name: String,
            val stage: FipsConnectionStage?,
            val timeoutMs: Long,
        )
        private val JOIN_PHASES = listOf(
            NearbyJoinPhase("advertisement", FipsConnectionStage.ADVERTISEMENT_SEEN, 8_000L),
            NearbyJoinPhase("l2cap_channel", FipsConnectionStage.CHANNEL_OPEN, 12_000L),
            NearbyJoinPhase("fips_peer", FipsConnectionStage.PEER_AUTHENTICATED, 20_000L),
            NearbyJoinPhase("direct_admission", FipsConnectionStage.DIRECT_AUTHENTICATED, 12_000L),
            NearbyJoinPhase("membership_snapshot", null, 12_000L),
        )
        /** Three missed heartbeat windows trigger fenced physical recovery. */
        private const val CONTROLLER_LEASE_TIMEOUT_MS = 6_000L
        private const val MEMBER_LIVENESS_TIMEOUT_MS = 6_000L
        private const val MAX_LOCAL_RECOVERY_ATTEMPTS = 3
    }
}
