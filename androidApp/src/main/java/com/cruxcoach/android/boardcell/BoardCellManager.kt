package com.cruxcoach.android.boardcell

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsConnectionStage
import com.cruxcoach.android.fips.FipsDebugLog
import com.cruxcoach.android.mesh.MeshOwner
import com.cruxcoach.android.mesh.MeshOwners
import com.cruxcoach.android.mesh.MeshRealmId
import com.cruxcoach.android.mesh.MeshRealmKind
import com.cruxcoach.android.mesh.MeshRealmManager
import com.cruxcoach.android.mesh.MeshRealmMetadata
import com.cruxcoach.android.mesh.MeshRealmSession
import com.cruxcoach.android.mesh.acquireOrNull
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

/**
 * A local-only playlist wants the wall while a joinable playlist is showing
 * something else. [sharedClimbUuid] is what the group currently has on it.
 */
data class LocalOverwriteRequest(
    val climbUuid: String,
    val angle: Int,
    val sharedClimbUuid: String,
    val sessionId: Int?,
)

/**
 * How the technical controller turns a canonical playlist entry into light on
 * the wall.
 *
 * Split into resolve and write so the canonical state can say *why* the wall
 * is dark: a climb this device simply does not have is a different, honest
 * situation from a board write that failed, and only the second is worth
 * retrying on its own.
 */
interface BoardPlaylistProjectionWriter {
    /** Null when this device cannot resolve the climb at all. */
    fun resolve(climbUuid: String, angle: Int): BoardProjection?
    suspend fun write(projection: BoardProjection): Boolean
}

private data class PendingProjectionRequest(
    val request: BoardProjectionRequest,
    var retryAtMs: Long,
    var attempts: Int = 0,
)

enum class ControllerRequestState { IDLE, WAITING, ACCEPTED, DENIED, TIMED_OUT, FAILED }
enum class MeshMembershipTransition { IDLE, LEAVING, JOINING, ERROR }

/** Guards the restore window where physical ownership outlives the projected snapshot. */
internal object BoardCellNearbyJoinPolicy {
    // Radio and FIPS authentication normally finish well before the host has
    // reconstructed canonical BoardCell authority after a cold start. Keep an
    // already-authenticated edge alive across that bounded recovery window so
    // the user never has to press Join a second time merely to receive the
    // first ACTIVE snapshot.
    const val HOST_READINESS_TIMEOUT_MS = 45_000L

    fun keepsActivePhysicalRealm(
        targetRealmId: String,
        activeRealmId: String?,
        runtimeRunning: Boolean,
        physicalBoardOwnerHeld: Boolean,
    ): Boolean = physicalBoardOwnerHeld && runtimeRunning && activeRealmId == targetRealmId

    fun hasActiveMembership(
        snapshot: BoardCellSnapshot?,
        cellId: BoardCellId,
        localNodeId: String,
    ): Boolean = snapshot?.cellId == cellId && localNodeId in snapshot.members &&
        snapshot.availability == BoardCellAvailability.ACTIVE
}

/** A live connection to the exact physical board is itself the recovery fence. */
internal object BoardCellLocalControllerFence {
    fun isHeld(
        expectedBoard: PhysicalBoardId,
        connectedBoard: PhysicalBoardId?,
        connectionState: ConnectionState,
    ): Boolean = expectedBoard == connectedBoard &&
        (connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.SENDING)
}

@Singleton
class BoardCellManager @Inject constructor(
    @ApplicationContext context: Context,
    private val boardConnection: BoardBleConnection,
    private val meshRealms: MeshRealmManager,
    /**
     * Radio plane only: discovery, bluetooth state, bulk-transfer suspension
     * and join progress are process-wide and realm-agnostic. Everything realm
     * scoped — leases, sends, inbound frames — goes through [meshRealms].
     */
    private val runtime: FipsMeshRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meshLink = BoardCellMeshSessionLink()
    private val meshTransport = BoardCellMeshTransport(meshLink)
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
    /** A physical reconnect explicitly requested by controller recovery or a
     * handover target. Its descriptor emission must preserve the live replica
     * instead of looking like a new, unrelated board selection. */
    @Volatile private var authorizedReplicaPreservingBoard: PhysicalBoardId? = null
    private var lastSnapshotTrace = ""
    private val nearbyJoinMutex = Mutex()
    private val pendingLocalLeave = AtomicBoolean(false)
    /** Once local teardown starts, queued frames from the departed realm must
     * not recreate the coordinator replica or its durable snapshot. Cleared
     * only by a new explicit entry into that same cell. */
    @Volatile private var locallyDepartedCell: BoardCellId? = null
    private val localRemovalCleanup = AtomicBoolean(false)
    private val playlistProjectionMutex = Mutex()
    @Volatile private var playlistProjectionWriter: BoardPlaylistProjectionWriter? = null
    @Volatile private var peerDiagnosticsProvider: (() -> BoardCellPeerDiagnostics)? = null
    @Volatile private var lastLocalPeerDiagnostics: BoardCellPeerDiagnostics? = null
    @Volatile private var lastPeerDiagnosticsSentAt = 0L
    private val playlistControlChannel = Channel<InboundPlaylistControl>(64)
    private val leafCommandChannel = Channel<InboundLeafCommand>(64)
    /** Queue edits a gateway is carrying for its own joined API-28 leaf. */
    val leafCommands = leafCommandChannel.receiveAsFlow()
    private val _localOverwriteRequest = MutableStateFlow<LocalOverwriteRequest?>(null)
    /** A local playlist is about to take the wall from the joinable one. */
    val localOverwriteRequest = _localOverwriteRequest.asStateFlow()
    @Volatile private var confirmedOverwriteSession: Int? = null

    init {
        current = this
        boardConnection.connectionGuard = { board -> !requiresControllerRequest(board) }
        meshTransport.onSessionCommand = { sessionCommandChannel.send(it) }
        meshTransport.onCommandAck = { _, ack ->
            if (ack.status != BoardCommandStatus.ACCEPTED) pendingProjectionRequests.remove(ack.commandId)
            commandAckChannel.send(ack)
        }
        meshTransport.onProjectionRequest = { projectionRequestChannel.send(it) }
        meshTransport.onPlaylistControl = { playlistControlChannel.send(it) }
        meshTransport.onLeafCommand = { leafCommandChannel.send(it) }
        scope.launch {
            for (inbound in playlistControlChannel) {
                commitPlaylistControl(inbound.senderId, inbound.control)
            }
        }
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
            // Realm and protocol filtering happen in the router: whatever
            // arrives here is a boardcell/v1 frame of the realm this cell
            // currently holds.
            meshLink.incoming.collect { envelope ->
                if (BoardCellLocalLeaveFrameFence.shouldDrop(
                        locallyDepartedCell,
                        envelope.realmId.value,
                    )) {
                    FipsDebugLog.event(
                        "boardcell", "post_leave_frame_dropped",
                        "sender" to FipsDebugLog.id(envelope.sender),
                        "realm" to FipsDebugLog.id(envelope.realmId.value),
                        "bytes" to envelope.payload.size,
                    )
                    return@collect
                }
                val result = meshTransport.receive(envelope.sender, envelope.payload, monotonicNow())
                FipsDebugLog.event("boardcell", "mesh_message_applied",
                    "sender" to FipsDebugLog.id(envelope.sender), "bytes" to envelope.payload.size,
                    "realm" to FipsDebugLog.id(envelope.realmId.value),
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
            runtime.directPeerTransportLosses.collect { loss ->
                val board = BoardCellScopeRegistry.selected.value ?: return@collect
                val snapshot = _snapshots.value ?: return@collect
                if (!::coordinator.isInitialized || snapshot.physicalBoardId != board ||
                    snapshot.cellId.value != loss.realmId) return@collect
                if (coordinator.suspectControllerTransportLoss(
                        board,
                        loss.peerNpub,
                        monotonicNow(),
                        CONTROLLER_TRANSPORT_LOSS_GRACE_MS,
                    )) {
                    FipsDebugLog.warning(
                        "boardcell", "controller_transport_loss_suspected",
                        "controller" to FipsDebugLog.id(loss.peerNpub),
                        "reason" to loss.reason,
                        "graceMs" to CONTROLLER_TRANSPORT_LOSS_GRACE_MS,
                    )
                }
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
                        releaseRealmLease(MeshOwners.BOARD_CELL)
                        heldRuntime = false
                    }
                    return@collectLatest
                }
                val physical = PhysicalBoardIdentity.resolve(board, boardBindings.bindingFor(board.address))
                val physicalCell = BoardCellId.forPhysical(physical)
                if (locallyDepartedCell == physicalCell) locallyDepartedCell = null
                // A reconnect that fenced recovery or handover itself asked for must not
                // take the ordinary selection path. That path builds a fresh
                // coordinator and re-activates the realm, which threw away the
                // frozen snapshot, term and membership the recovery was about
                // to commit against — the cell then had nothing left to
                // recover and fell back to a new claim.
                val reconnect = BoardCellReconnectPolicy.decide(physical, authorizedReplicaPreservingBoard,
                    _snapshots.value, activeNodeId)
                if (reconnect is BoardCellReconnectPolicy.Decision.PreserveReplica) {
                    // Act on the snapshot the decision was made about. Reading
                    // the flow again here would race a concurrent update or a
                    // teardown, and bind either a different cell or nothing.
                    val retained = reconnect.retained
                    FipsDebugLog.event("boardcell", "replica_reconnect_preserved",
                        "physicalBoard" to FipsDebugLog.id(physical.value),
                        "cell" to FipsDebugLog.id(retained.cellId.value),
                        "sequence" to retained.sequence, "term" to retained.controllerTerm)
                    BoardCellScopeRegistry.joinCell(physical, retained.cellId)
                    boardRealmAvailable.set(true)
                    refreshSelected()
                    return@collectLatest
                }
                BoardCellScopeRegistry.replaceProvisionalSelection(physical)
                val restored = durableStore.snapshot(physical)
                val cellId = restored?.cellId ?: BoardCellId.forPhysical(physical)
                FipsDebugLog.event("boardcell", "physical_board_selected",
                    "address" to board.address, "brand" to board.boardBrand,
                    "physicalBoard" to FipsDebugLog.id(physical.value),
                    "cell" to FipsDebugLog.id(cellId.value), "durableSnapshot" to (restored != null))
                val meshAvailable = BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)
                if (meshAvailable) {
                    meshTransport.resetForRealm()
                    pendingProjectionRequests.clear()
                    // A nearby lease on a *different* cell would make the board's
                    // own realm a foreign one, so it goes first. A lease on the
                    // same cell is kept until the board lease exists: releasing
                    // the last reference would end the realm we are about to use.
                    if (nearbyRealmHeld && meshRealms.activeRealm.value?.value != cellId.value) {
                        releaseRealmLease(MeshOwners.NEARBY_BOARD_CELL)
                        nearbyRealmHeld = false
                    }
                    // Realm changes are explicit. A stale physical-board lease
                    // must be released before this board can acquire its scope.
                    meshRealms.session(MeshOwners.BOARD_CELL)
                        ?.takeIf { it.realmId.value != cellId.value }
                        ?.let { releaseRealmLease(MeshOwners.BOARD_CELL) }
                }
                val fipsActive = meshAvailable &&
                    acquireRealmLease(MeshOwners.BOARD_CELL, cellId, board.displayName)
                heldRuntime = fipsActive
                if (nearbyRealmHeld) {
                    releaseRealmLease(MeshOwners.NEARBY_BOARD_CELL)
                    nearbyRealmHeld = false
                }
                activeNodeId = if (fipsActive) meshLink.localNpub else durableStore.localFallbackNodeId()
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
                    val localFallbackMigration = fipsActive &&
                        BoardCellFipsBootstrapPolicy.isLocalFallbackSingleton(restored)
                    val knownSharedCell = fipsActive &&
                        BoardCellFipsBootstrapPolicy.hasKnownSharedCell(restored, activeNodeId)
                    if (localFallbackMigration) {
                        FipsDebugLog.event("boardcell", "local_fallback_snapshot_migrated",
                            "cell" to FipsDebugLog.id(cellId.value),
                            "previousNode" to FipsDebugLog.id(restored?.controllerId),
                            "fipsNode" to FipsDebugLog.id(activeNodeId))
                    }
                    if (fipsActive && !knownSharedCell) durableStore.clearSnapshot(physical)
                    val rejoined = knownSharedCell && withTimeoutOrNull(REJOIN_SPONSOR_GRACE_MS) {
                        snapshots.filterNotNull().first { snapshot ->
                            snapshot.cellId == cellId && activeNodeId in snapshot.members
                        }
                    } != null
                    val resumedController = knownSharedCell && !rejoined && restoreDurableControllerSeed(
                        BoardCellDurableResumePolicy.controllerSeed(restored, cellId, activeNodeId),
                        reason = "physical_board_reconnect",
                    )
                    val restoredRecoveryBase = knownSharedCell && !rejoined && !resumedController &&
                        restoreDurableMemberRecoveryBase(
                            BoardCellDurableResumePolicy.memberRecoverySeed(
                                restored, cellId, activeNodeId,
                            ),
                            reason = "physical_board_reconnect",
                        )
                    if (!knownSharedCell) {
                        FipsDebugLog.event("boardcell", "new_cell_claim_begin",
                            "cell" to FipsDebugLog.id(cellId.value), "node" to FipsDebugLog.id(activeNodeId))
                        _membershipTransition.value = if (claimAndSettle(physical, cellId) != null)
                            MeshMembershipTransition.IDLE else MeshMembershipTransition.ERROR
                    } else if (rejoined || resumedController || restoredRecoveryBase) {
                        _membershipTransition.value = MeshMembershipTransition.IDLE
                    } else {
                        // Never create a competing lineage merely because the
                        // previous live members are temporarily unreachable.
                        // The stable realm identity remains available for the
                        // normal permissionless sponsor/join path.
                        _membershipTransition.value = MeshMembershipTransition.ERROR
                        FipsDebugLog.warning("boardcell", "known_cell_rejoin_pending",
                            "cell" to FipsDebugLog.id(cellId.value),
                            "knownMembers" to (restored?.members?.size ?: 0))
                    }
                    coordinator.recoverPendingWrite(physical)
                }
                refreshSelected()
                processHandover()
                // A durable non-controller host is restored above as a
                // deliberately frozen recovery base. Start its fenced election
                // immediately; waiting for the periodic maintenance tick makes
                // host readiness depend on coroutine scheduling at exactly the
                // moment a nearby participant may already be joining.
                processControllerRecovery()
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

    fun installPlaylistProjectionWriter(value: BoardPlaylistProjectionWriter?) {
        playlistProjectionWriter = value
    }

    /** Supplies non-canonical operational state for cross-device logcat diagnosis. */
    fun installPeerDiagnosticsProvider(value: (() -> BoardCellPeerDiagnostics)?) {
        peerDiagnosticsProvider = value
    }

    private fun peerDiagnostics(snapshot: BoardCellSnapshot): BoardCellPeerDiagnostics {
        val supplied = runCatching { peerDiagnosticsProvider?.invoke() }.getOrNull()
            ?: BoardCellPeerDiagnostics()
        val localRole = when (activeNodeId) {
            snapshot.controllerId -> "controller"
            in snapshot.members -> "member"
            else -> "excluded"
        }
        return supplied.copy(
            meshRole = localRole,
            meshMemberCount = snapshot.members.size,
            controllerAvailable = snapshot.availability == BoardCellAvailability.ACTIVE,
            canonicalPlaylist = snapshot.playlist.isJoinable,
            playlistHost = snapshot.playlist.hostId == activeNodeId,
            playlistMember = activeNodeId in snapshot.playlist.members,
        )
    }

    /** Emit immediately on a decision-relevant change, otherwise as a sparse checkpoint. */
    private fun duePeerDiagnostics(
        snapshot: BoardCellSnapshot,
        nowMonotonicMs: Long,
    ): BoardCellPeerDiagnostics? {
        val current = peerDiagnostics(snapshot)
        val changed = current != lastLocalPeerDiagnostics
        if (!changed &&
            nowMonotonicMs - lastPeerDiagnosticsSentAt < PEER_DIAGNOSTICS_CHECKPOINT_MS
        ) return null
        lastLocalPeerDiagnostics = current
        lastPeerDiagnosticsSentAt = nowMonotonicMs
        BoardCellPeerDiagnosticsLog.emit(
            if (changed) "local_state_changed" else "local_checkpoint",
            activeNodeId,
            current,
        )
        return current
    }

    /**
     * Whether a purely local playlist may light the wall over the top of the
     * joinable playlist's current climb.
     *
     * A local playlist stays local: its queue is never published and the shared
     * playlist and index never move because of it. What it cannot be is
     * invisible — the wall is shared hardware, so the send itself becomes a
     * normal BoardCell projection everybody sees. Taking the wall away from a
     * group mid-session is worth one clear question, asked once per playlist.
     *
     * Returns false and publishes [localOverwriteRequest] when the user has to
     * answer it first.
     */
    fun mayOverwriteSharedProjection(projection: BoardProjection): Boolean {
        val playlist = snapshot()?.playlist ?: return true
        if (!BoardPlaylistPolicy.requiresOverwriteConsent(playlist, activeNodeId,
                projection.climbUuid, projection.angle, confirmedOverwriteSession)) return true
        val current = playlist.currentItem() ?: return true
        _localOverwriteRequest.value = LocalOverwriteRequest(
            climbUuid = projection.climbUuid,
            angle = projection.angle,
            sharedClimbUuid = current.first,
            sessionId = playlist.sessionId,
        )
        FipsDebugLog.event("playlist", "local_overwrite_confirmation_required",
            "climb" to FipsDebugLog.id(projection.climbUuid),
            "sharedClimb" to FipsDebugLog.id(current.first))
        return false
    }

    /** The user accepted; remember it for this playlist only. */
    fun confirmLocalOverwrite() {
        confirmedOverwriteSession = _localOverwriteRequest.value?.sessionId
        _localOverwriteRequest.value = null
    }

    fun dismissLocalOverwrite() { _localOverwriteRequest.value = null }

    /** The local node's canonical identity inside the BoardCell. */
    fun localNodeId(): String = activeNodeId

    fun playlist(): BoardPlaylistState? = snapshot()?.playlist

    fun isPlaylistHost(): Boolean = snapshot()?.playlist?.hostId == activeNodeId

    fun isPlaylistMember(): Boolean = snapshot()?.playlist?.members?.contains(activeNodeId) == true

    /**
     * Routes one playlist lifecycle command to whoever can serialize it.
     *
     * This is the fix for the observed defect: a device that is the session
     * host but not the technical BoardCell controller used to call
     * [replacePlaylist], which can only ever write on the controller, so the
     * canonical snapshot stayed at revision 0 for ever. The product rule is
     * that the technical controller has no visible authority, so a member
     * simply sends the command and the controller applies it.
     *
     * Returns the ack when the command was decided locally, or a synthetic
     * ACCEPTED when it went out over the mesh and the real ack will arrive on
     * [commandAcks].
     */
    suspend fun submitPlaylistControl(control: BoardPlaylistControl): BoardCommandAck? {
        if (!::coordinator.isInitialized) {
            FipsDebugLog.warning("playlist", "control_no_cell",
                "command" to FipsDebugLog.id(control.commandId))
            return null
        }
        val snapshot = snapshot() ?: return null
        if (activeNodeId !in snapshot.members) {
            FipsDebugLog.warning("playlist", "control_not_member",
                "command" to FipsDebugLog.id(control.commandId))
            return null
        }
        if (snapshot.controllerId == activeNodeId) {
            return commitPlaylistControl(activeNodeId, control)
        }
        if (!BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) {
            FipsDebugLog.warning("playlist", "control_fips_unavailable",
                "command" to FipsDebugLog.id(control.commandId), "api" to Build.VERSION.SDK_INT)
            return null
        }
        val sent = meshTransport.sendPlaylistControl(snapshot, control)
        FipsDebugLog.event("playlist", if (sent) "control_sent" else "control_send_refused",
            "command" to FipsDebugLog.id(control.commandId),
            "kind" to control.javaClass.simpleName,
            "controller" to FipsDebugLog.id(snapshot.controllerId),
            "baseRevision" to control.basePlaylistRevision)
        return if (!sent) null else BoardCommandAck(control.commandId,
            BoardCommandStatus.ACCEPTED, snapshot.cellId, snapshot.epoch, snapshot.controllerTerm,
            snapshot.sequence, snapshot.stateHash)
    }

    /**
     * Commit a joined GATT leaf's projection retry on its behalf, as the
     * technical controller.
     */
    suspend fun retryProjectionForLeaf(
        commandId: String = UUID.randomUUID().toString(),
    ): BoardCommandAck? {
        val board = writableBoard() ?: return null
        val snapshot = coordinator.snapshot(board) ?: return null
        val ack = coordinator.applyPlaylistControl(board, monotonicNow(), activeNodeId,
            BoardPlaylistControl.RetryProjection(commandId, snapshot.playlistRevision),
            BoardPlaylistAuthority.GATEWAY_PROXY)
        refreshSelected()
        if (ack?.status == BoardCommandStatus.COMMITTED) syncPlaylistProjection(force = true)
        return ack
    }

    /**
     * Send a joined GATT leaf's queue edit to the controller under bounded
     * proxy authority.
     *
     * Replaces an earlier join-then-send: the gateway used to fire a playlist
     * `Join` and immediately send the edit, which raced — the controller could
     * commit the edit first and refuse it as "not a playlist member" — and
     * which also made the gateway a full playlist member, so it could inherit
     * the host role and lose its own local queue. It now lends its
     * authenticated hop and stays out of the playlist entirely.
     */
    fun sendLeafSessionCommand(
        payload: ByteArray,
        context: BoardPlaylistCommandContext?,
        commandId: String = UUID.randomUUID().toString(),
    ): String? {
        if (!::coordinator.isInitialized ||
            !BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) return null
        val snapshot = snapshot() ?: return null
        val sent = meshTransport.sendLeafSessionCommand(snapshot, commandId, payload, context)
        FipsDebugLog.event("playlist", if (sent) "leaf_command_sent" else "leaf_command_refused",
            "command" to FipsDebugLog.id(commandId), "kind" to context?.kind,
            "controller" to FipsDebugLog.id(snapshot.controllerId))
        return commandId.takeIf { sent }
    }

    fun sendLeafRetryProjection(commandId: String = UUID.randomUUID().toString()): String? {
        if (!::coordinator.isInitialized ||
            !BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) return null
        val snapshot = snapshot() ?: return null
        return commandId.takeIf { meshTransport.sendLeafRetryProjection(snapshot, commandId) }
    }

    /**
     * Serialize an inbound leaf command on the controller.
     *
     * [applyCommand] is null for a projection retry, which moves nothing about
     * the queue and only re-attempts the physical write.
     */
    suspend fun commitLeafCommand(
        command: InboundLeafCommand,
        applyCommand: ((BoardPlaylistState, Boolean) -> BoardPlaylistState?)?,
    ) {
        val board = writableBoard() ?: return
        val ack = if (applyCommand == null) {
            coordinator.applyPlaylistControl(board, monotonicNow(), command.senderId,
                BoardPlaylistControl.RetryProjection(command.commandId, command.basePlaylistRevision),
                BoardPlaylistAuthority.GATEWAY_PROXY)
        } else {
            coordinator.applyPlaylistCommand(board, monotonicNow(), command.commandId,
                command.basePlaylistRevision, command.senderId,
                BoardPlaylistAuthority.GATEWAY_PROXY, applyCommand)
            durableStore.commandAck(command.commandId)
        }
        val snapshot = coordinator.snapshot(board) ?: return
        val resolved = ack ?: BoardCommandAck(
            commandId = command.commandId,
            status = BoardCommandStatus.REJECTED_STALE,
            cellId = snapshot.cellId, epoch = snapshot.epoch,
            controllerTerm = snapshot.controllerTerm,
            resultingSequence = snapshot.sequence, resultingHash = snapshot.stateHash,
        )
        FipsDebugLog.event("playlist", "leaf_command_decided",
            "command" to FipsDebugLog.id(command.commandId),
            "gateway" to FipsDebugLog.id(command.senderId), "status" to resolved.status)
        meshTransport.publishCommandAck(command.senderId, resolved)
        refreshSelected()
        if (resolved.status == BoardCommandStatus.COMMITTED) {
            syncPlaylistProjection(force = applyCommand == null)
        }
    }

    /** Re-send an unacknowledged control command with its original identity. */
    fun retryPlaylistControl(control: BoardPlaylistControl): Boolean {
        val snapshot = snapshot() ?: return false
        if (snapshot.controllerId == activeNodeId) return false
        return meshTransport.sendPlaylistControl(snapshot, control)
    }

    private suspend fun commitPlaylistControl(
        senderId: String,
        control: BoardPlaylistControl,
    ): BoardCommandAck? {
        val board = BoardCellScopeRegistry.selected.value ?: return null
        if (!::coordinator.isInitialized) return null
        val ack = coordinator.applyPlaylistControl(board, monotonicNow(), senderId, control)
        FipsDebugLog.event("playlist", "control_decided",
            "command" to FipsDebugLog.id(control.commandId),
            "sender" to FipsDebugLog.id(senderId), "kind" to control.javaClass.simpleName,
            "status" to ack?.status, "detail" to ack?.detail)
        if (ack != null && senderId != activeNodeId) meshTransport.publishCommandAck(senderId, ack)
        refreshSelected()
        if (ack?.status == BoardCommandStatus.COMMITTED) {
            // A retry deliberately forces the physical write even though the
            // canonical queue and index did not move; everything else only
            // projects when the canonical current entry is not on the wall.
            syncPlaylistProjection(force = control is BoardPlaylistControl.RetryProjection)
        }
        return ack
    }

    /**
     * Puts the canonical current playlist entry on the physical board.
     *
     * Runs on the technical controller only, which is exactly why the playlist
     * host does not have to be the controller. Failure is not an error state
     * for the playlist: it stays started and records why the wall is dark, so
     * any playlist member can press retry.
     */
    suspend fun syncPlaylistProjection(force: Boolean = false): Boolean =
        playlistProjectionMutex.withLock {
            val board = writableBoard() ?: return@withLock false
            val snapshot = coordinator.snapshot(board) ?: return@withLock false
            val playlist = snapshot.playlist
            if (!playlist.isJoinable) return@withLock false
            val item = playlist.currentItem() ?: return@withLock false
            val projected = snapshot.projection
            val alreadyProjected = snapshot.projectionKnown && projected != null &&
                projected.climbUuid == item.first && projected.angle == item.second
            val pending = playlist.pendingProjection
            val pendingForCurrent = pending != null &&
                pending.climbUuid == item.first && pending.angle == item.second
            // An already-recorded pending state waits for a deliberate retry.
            // Without that, the periodic reconciliation below would turn one
            // failed write into a permanent write storm against the board.
            if (!force && (alreadyProjected || pendingForCurrent)) return@withLock alreadyProjected
            val writer = playlistProjectionWriter
            val resolved = writer?.resolve(item.first, item.second)
            if (writer == null || resolved == null) {
                recordPendingProjection(BoardPlaylistPendingProjection(item.first, item.second,
                    BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE))
                return@withLock false
            }
            val result = coordinator.project(board, resolved, monotonicNow(),
                UUID.randomUUID().toString(), null) { writer.write(resolved) }
            FipsDebugLog.event("playlist", "projection_attempted",
                "climb" to FipsDebugLog.id(item.first), "angle" to item.second,
                "result" to result.javaClass.simpleName, "forced" to force)
            val committed = result is ProjectionResult.Committed || result is ProjectionResult.Duplicate
            if (!committed) {
                recordPendingProjection(BoardPlaylistPendingProjection(item.first, item.second,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED))
            }
            refreshSelected()
            committed
        }

    /**
     * Records the pending-send state canonically. A successful projection
     * clears it inside the reducer, so this never has to be undone by hand
     * and a duplicate retry cannot clear it twice.
     */
    private suspend fun recordPendingProjection(pending: BoardPlaylistPendingProjection) {
        val board = BoardCellScopeRegistry.selected.value ?: return
        val snapshot = coordinator.snapshot(board) ?: return
        if (snapshot.playlist.pendingProjection == pending) return
        coordinator.applyPlaylistControl(board, monotonicNow(), activeNodeId,
            BoardPlaylistControl.ProjectionPending(
                commandId = UUID.randomUUID().toString(),
                basePlaylistRevision = snapshot.playlistRevision,
                pending = pending,
            ))
        refreshSelected()
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
        // An ordinary session command is a member acting for itself, so it
        // gets no proxy authority: a gateway carrying a leaf's verb has its
        // own message type and arrives via commitLeafCommand instead.
        val committed = coordinator.applyPlaylistCommand(board, monotonicNow(), command.commandId,
            command.basePlaylistRevision, command.senderId, BoardPlaylistAuthority.MEMBER,
            applyCommand)
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
        if (committed != null) syncPlaylistProjection()
    }

    suspend fun commitLocalSessionCommand(
        commandId: String,
        basePlaylistRevision: Long,
        authority: BoardPlaylistAuthority = BoardPlaylistAuthority.MEMBER,
        applyCommand: (BoardPlaylistState, Boolean) -> BoardPlaylistState?,
    ): BoardCommandAck? {
        val board = writableBoard() ?: return null
        val committed = coordinator.applyPlaylistCommand(board, monotonicNow(), commandId,
            basePlaylistRevision, activeNodeId, authority, applyCommand)
        val ack = durableStore.commandAck(commandId)
        refreshSelected()
        if (committed != null) syncPlaylistProjection()
        return ack
    }

    fun bindPhysicalBoardFallback(observedAddress: String, durableBindingId: String) {
        boardBindings.bind(observedAddress, durableBindingId)
    }

    /** Match a scan result against canonical cell identity, including an
     * explicit QR/manual binding for controllers whose BLE address rotates. */
    fun matchesPhysicalBoard(
        board: com.cruxcoach.android.ble.DiscoveredBoard,
        snapshot: BoardCellSnapshot,
    ): Boolean = PhysicalBoardIdentity.matches(
        board = board,
        expectedBoardId = snapshot.physicalBoardId,
        expectedCellId = snapshot.cellId,
        persistentFallback = boardBindings.bindingFor(board.address),
    )

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
        // The user explicitly chose this cell again, so frames for its new
        // membership attempt are no longer post-leave stragglers.
        if (locallyDepartedCell == cell) locallyDepartedCell = null
        if (BoardCellId.forPhysical(physical) != cell) return false
        BoardCellScopeRegistry.replaceProvisionalSelection(physical)
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        // The participant lease is its own owner: a GATT participant has no
        // board connection, so it must not be torn down by the physical-board
        // disconnect path that owns the BOARD_CELL lease.
        meshRealms.session(MeshOwners.PARTICIPANT)
            ?.takeIf { it.realmId.value != cell.value }
            ?.let { releaseRealmLease(MeshOwners.PARTICIPANT) }
        if (!withContext(Dispatchers.IO) { acquireRealmLease(MeshOwners.PARTICIPANT, cell) }) return false
        activeNodeId = meshLink.localNpub
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
        if (locallyDepartedCell == cell) locallyDepartedCell = null
        FipsDebugLog.event(
            "boardcell", "nearby_mesh_join_requested",
            "cell" to FipsDebugLog.id(cell.value),
            "active" to FipsDebugLog.id(snapshot()?.cellId?.value),
            "runtimeRealm" to FipsDebugLog.id(runtime.activeRealmId()),
        )
        snapshot()?.let { active ->
            if (active.cellId == cell && activeNodeId in active.members &&
                active.availability == BoardCellAvailability.ACTIVE && runtime.running.value) return true
            _membershipTransition.value = MeshMembershipTransition.LEAVING
            if (!leaveCurrentMeshLocked()) {
                _membershipTransition.value = MeshMembershipTransition.ERROR
                return false
            }
        }
        // The native radio can rediscover its own advertisement while the
        // coordinator is restoring the durable controller snapshot. In that
        // window snapshot() is null, but heldRuntime still proves that the
        // physical-board lifecycle owns this exact running realm. Treat a UI
        // join of it as an idempotent no-op. Ending/restarting here discards
        // peers and controller state and was the root cause of the observed
        // host becoming a nearby participant after a successful join.
        if (BoardCellNearbyJoinPolicy.keepsActivePhysicalRealm(
                targetRealmId = cell.value,
                activeRealmId = runtime.activeRealmId(),
                runtimeRunning = runtime.running.value,
                physicalBoardOwnerHeld = heldRuntime,
            )) {
            FipsDebugLog.event(
                "boardcell", "nearby_mesh_join_kept_active_controller",
                "cell" to FipsDebugLog.id(cell.value),
            )
            _membershipTransition.value = MeshMembershipTransition.IDLE
            return true
        }
        _membershipTransition.value = MeshMembershipTransition.JOINING
        // Entering another cell is an explicit realm change. This feature's own
        // leases on the previous realm go first, so the new realm is granted
        // instead of denied as a conflicting concurrent realm.
        if (meshRealms.activeRealm.value?.value != cell.value) releaseAllRealmLeases()
        // A failed handover/reconnect can leave this exact realm running even
        // though the local canonical replica was cleared. Reusing it also
        // reuses old connection progress and native peer entries, which can
        // make a join skip phases or reject the new channel as a duplicate.
        // Always make an explicit join without membership a fresh transport
        // generation; the persistent realm key keeps the node identity stable.
        val reusesLiveRealm = meshRealms.activeRealm.value?.value == cell.value && runtime.running.value
        meshTransport.resetForRealm()
        pendingProjectionRequests.clear()
        val activated = try {
            withContext(Dispatchers.IO) {
                acquireRealmLease(MeshOwners.NEARBY_BOARD_CELL, cell, boardName).also { granted ->
                    if (granted) {
                        nearbyRealmHeld = true
                        if (reusesLiveRealm) {
                            meshLink.session?.recycleTransport("explicit nearby join")
                        }
                    }
                }
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
            FipsDebugLog.warning(
                "boardcell", "nearby_mesh_join_activation_denied",
                "cell" to FipsDebugLog.id(cell.value),
                "activeRealm" to FipsDebugLog.id(meshRealms.activeRealm.value?.value),
            )
            rollbackNearbyJoin(cell)
            _membershipTransition.value = MeshMembershipTransition.ERROR
            return false
        }
        activeNodeId = meshLink.localNpub
        coordinator = BoardCellCoordinator(activeNodeId, meshTransport, durableStore,
            settleMs = 2_000, heartbeatTimeoutMs = CONTROLLER_LEASE_TIMEOUT_MS)
        meshTransport.attach(coordinator)
        // A durable snapshot in which this device used to be controller is
        // historical recovery material here, not authority to compete with
        // the live controller whose advertisement the user selected.
        restoreDurableControllerSeed(
            BoardCellDurableResumePolicy.controllerSeed(
                durableStore.snapshotForCell(cell),
                cell,
                activeNodeId,
                BoardCellDurableResumePolicy.Context.LIVE_NEARBY_JOIN,
            ),
            reason = "nearby_mesh_join",
        )
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
            it.cellId == cell && activeNodeId in it.members &&
                it.availability == BoardCellAvailability.ACTIVE
        } == true
        if (joined) {
            _membershipTransition.value = MeshMembershipTransition.IDLE
            val current = snapshot()
            meshLink.session?.settleMembership()
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
                if (expectedStage != null) {
                    val progress = runtime.connectionProgress.value
                    if (progress.cellId == cell.value && progress.stage.ordinal >= expectedStage.ordinal) {
                        return@withTimeoutOrNull true
                    }
                } else {
                    val current = snapshot()
                    if (BoardCellNearbyJoinPolicy.hasActiveMembership(
                            current, cell, activeNodeId)) {
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

    /**
     * Restores only the canonical controller's durable ACTIVE replica. On a
     * nearby join [boardRealmAvailable] remains false, so this grants enough
     * authority to repair membership but never physical-board write access.
     */
    private suspend fun restoreDurableControllerSeed(
        seed: BoardCellSnapshot?,
        reason: String,
    ): Boolean {
        seed ?: return false
        val restored = coordinator.restoreTrustedSnapshot(seed, monotonicNow())
        if (restored !is BoardCellApplyResult.Applied) return false
        meshTransport.rememberSnapshot(restored.snapshot)
        BoardCellScopeRegistry.joinCell(restored.snapshot.physicalBoardId, restored.snapshot.cellId)
        FipsDebugLog.event(
            "boardcell", "durable_controller_resumed",
            "reason" to reason,
            "cell" to FipsDebugLog.id(restored.snapshot.cellId.value),
            "sequence" to restored.snapshot.sequence,
            "term" to restored.snapshot.controllerTerm,
            "members" to restored.snapshot.members.size,
        )
        refreshSelected()
        return true
    }

    /**
     * Restore a former member only as an already-silent recovery base.  The
     * physical board is connected on this path, but canonical authority still
     * changes solely through the existing frozen, fenced recovery protocol.
     */
    private suspend fun restoreDurableMemberRecoveryBase(
        seed: BoardCellSnapshot?,
        reason: String,
    ): Boolean {
        seed ?: return false
        val now = monotonicNow()
        val observedAt = (now - CONTROLLER_LEASE_TIMEOUT_MS).coerceAtLeast(0L)
        val restored = coordinator.restoreTrustedSnapshot(seed, observedAt)
        if (restored !is BoardCellApplyResult.Applied) return false
        coordinator.expireLocalDeadlines(now)
        val recoveryBase = coordinator.snapshot(seed.physicalBoardId) ?: return false
        if (recoveryBase.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER) return false
        meshTransport.rememberSnapshot(recoveryBase)
        BoardCellScopeRegistry.joinCell(recoveryBase.physicalBoardId, recoveryBase.cellId)
        FipsDebugLog.event(
            "boardcell", "durable_member_recovery_restored",
            "reason" to reason,
            "cell" to FipsDebugLog.id(recoveryBase.cellId.value),
            "sequence" to recoveryBase.sequence,
            "term" to recoveryBase.controllerTerm,
            "controller" to FipsDebugLog.id(recoveryBase.controllerId),
        )
        refreshSelected()
        return true
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
                    acquireHandoverLease()
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
        // Set the fence before forgetting. An already queued snapshot can be
        // delivered between forgetLocalReplica() and the realm unbind; without
        // this fence it recreates both the in-memory and durable membership.
        locallyDepartedCell = snapshot.cellId
        if (::coordinator.isInitialized) coordinator.forgetLocalReplica(
            snapshot.physicalBoardId,
            clearDurableSnapshot = !preserveRejoinHint,
        )
        // The realm ends with its last reference; another feature holding the
        // same realm keeps the transport, never this cell's membership.
        releaseAllRealmLeases()
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
        releaseAllRealmLeases()
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
        if (physical == authorizedReplicaPreservingBoard) return false
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
                acquireHandoverLease()
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
                acquireHandoverLease()
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
        val target = meshLink.directAuthenticatedPeers().filter { it in others }.sorted().firstOrNull()
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
                    val nearbyCandidates = meshLink.directAuthenticatedPeers()
                    sponsoredAt.keys.removeAll { candidate ->
                        candidate !in nearbyCandidates
                    }
                    nearbyCandidates.forEach { peer ->
                        // A native direct-peer entry is an admission fact, not liveness:
                        // Android/FIPS may retain it for about a minute after its L2CAP
                        // channel closed. Join seeds a full grace window and only received,
                        // correctly scoped frames renew it in BoardCellWire.
                        val last = sponsoredAt[peer]
                        if (last != null && now - last < MEMBER_SPONSOR_RETRY_MS) return@forEach
                        if (peer in snapshot.members) {
                            if (snapshot.controllerId == activeNodeId) {
                                if (meshTransport.sendSnapshotTo(snapshot, peer)) sponsoredAt[peer] = now
                            }
                        } else if (snapshot.controllerId == activeNodeId) {
                            FipsDebugLog.event("boardcell", "nearby_member_auto_admitted",
                                "peer" to FipsDebugLog.id(peer), "cell" to FipsDebugLog.id(snapshot.cellId.value))
                            val admitted = coordinator.joinMember(board, peer, now) != null
                            // A recently departed member is deliberately fenced for one
                            // liveness window.  Do not apply the normal successful-sponsor
                            // backoff to that rejection: doing so can move the first legal
                            // retry just beyond the joiner's membership-snapshot timeout.
                            if (admitted) {
                                sponsoredAt[peer] = now
                            } else {
                                sponsoredAt.remove(peer)
                                FipsDebugLog.event(
                                    "boardcell", "nearby_member_admission_deferred",
                                    "peer" to FipsDebugLog.id(peer),
                                    "cell" to FipsDebugLog.id(snapshot.cellId.value),
                                )
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
                    val diagnostics = duePeerDiagnostics(snapshot, now)
                    if (activeNodeId in snapshot.members && snapshot.controllerId != activeNodeId &&
                        !pendingLocalLeave.get()) {
                        meshTransport.sendMemberHeartbeat(
                            snapshot,
                            now / CONTROLLER_HEARTBEAT_INTERVAL_MS,
                            diagnostics,
                        )
                    }
                    if (snapshot.controllerId == activeNodeId) {
                        diagnostics?.let {
                            meshTransport.sendControllerDiagnostics(
                                snapshot,
                                now / CONTROLLER_HEARTBEAT_INTERVAL_MS,
                                it,
                            )
                        }
                        val evicted = coordinator.evictExpiredMembers(
                            board, now, MEMBER_LIVENESS_TIMEOUT_MS,
                        )
                        if (evicted.isNotEmpty()) {
                            val afterEviction = coordinator.snapshot(board)
                            if (afterEviction?.members == setOf(activeNodeId)) {
                                // Keep the radio/runtime alive. Recycling here
                                // used to discard fresh scan/PSM knowledge at
                                // exactly the moment a distant member might
                                // come back into range, turning a recoverable
                                // physical outage into a permanent one.
                                FipsDebugLog.event(
                                    "boardcell", "last_remote_member_timed_out",
                                    "action" to "runtime retained for rediscovery",
                                )
                            }
                        }
                    }
                }
                if (boardRealmAvailable.get() && !runtime.isSuspendedForBulkTransfer()) {
                    coordinator.heartbeat(board, monotonicNow())
                    // Reconciles the wall with the canonical playlist after a
                    // technical controller handover or recovery, where the new
                    // controller inherits a playlist it never projected. Both
                    // the already-lit and the already-pending cases return
                    // immediately, so this is a no-op in the steady state.
                    syncPlaylistProjection()
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
                // The requested GATT connection emits connectedBoardDescriptor
                // asynchronously. Fence that emission exactly like controller
                // recovery: reinitializing here destroys the SOURCE_RELEASED
                // replica before TARGET_READY can be sent.
                authorizedReplicaPreservingBoard = snapshot.physicalBoardId
                val boardReady = try {
                    lifecycle?.prepareTarget?.invoke(snapshot) == true
                } finally {
                    authorizedReplicaPreservingBoard = null
                }
                val realmReady = boardRealmAvailable.get()
                val snapshotRetained = coordinator.snapshot(board)
                    ?.handover?.transferId == h.transferId
                FipsDebugLog.event(
                    "handover", "target_prepare_result",
                    "transfer" to FipsDebugLog.id(h.transferId),
                    "boardReady" to boardReady,
                    "realmReady" to realmReady,
                    "snapshotRetained" to snapshotRetained,
                )
                if (boardReady && realmReady && snapshotRetained) {
                    coordinator.targetReady(board, "host-board-ready:${h.transferId}")
                    FipsDebugLog.event(
                        "handover", "target_ready_sent",
                        "transfer" to FipsDebugLog.id(h.transferId),
                        "source" to FipsDebugLog.id(h.sourceControllerId),
                    )
                } else handledHandoverPhase.remove(phaseKey)
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.COMMITTED -> {
                authorizedReplicaPreservingBoard = snapshot.physicalBoardId
                val boardReady = try {
                    lifecycle?.prepareTarget?.invoke(snapshot) == true
                } finally {
                    authorizedReplicaPreservingBoard = null
                }
                val ready = boardReady && boardRealmAvailable.get()
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
            meshRealms.releaseAll(MeshOwners.HANDOVER)
        }
    }

    /** Takes or shares this cell's realm and points the wire at the resulting session. */
    private suspend fun acquireRealmLease(
        owner: MeshOwner,
        cell: BoardCellId,
        boardName: String? = null,
    ): Boolean {
        // A lifecycle lease is retained, not stacked. The manager itself stays
        // reference-counted for clients that intentionally call acquire more
        // than once, while reconnect/restore callbacks remain idempotent.
        meshRealms.session(owner)?.takeIf { it.realmId.value == cell.value }?.let {
            rebindMeshLink()
            return true
        }
        val session: MeshRealmSession? = meshRealms.acquireOrNull(
            owner,
            MeshRealmId(cell.value),
            MeshRealmMetadata(MeshRealmKind.BOARD_CELL, cell.value, boardName),
        )
        rebindMeshLink()
        return session != null
    }

    /** Keeps the realm alive across a transfer without owning its wire binding. */
    private suspend fun acquireHandoverLease() {
        val cell = snapshot()?.cellId ?: return
        acquireRealmLease(MeshOwners.HANDOVER, cell)
    }

    private fun releaseRealmLease(owner: MeshOwner) {
        meshRealms.releaseAll(owner)
        rebindMeshLink()
    }

    /** Drops every lease this cell holds; the realm ends with its last reference. */
    private fun releaseAllRealmLeases() {
        releaseHandoverRuntime()
        REALM_LEASE_OWNERS.forEach(meshRealms::releaseAll)
        heldRuntime = false
        nearbyRealmHeld = false
        rebindMeshLink()
    }

    /** The wire follows the strongest lease this cell holds, or nothing at all. */
    private fun rebindMeshLink() {
        meshLink.bind(REALM_LEASE_OWNERS.firstNotNullOfOrNull(meshRealms::session))
    }

    /** Canonical controller silence for the selected board, in milliseconds. */
    private fun controllerSilentMs(board: PhysicalBoardId?): Long? =
        if (board == null || !::coordinator.isInitialized) null
        else coordinator.controllerSilentForMs(board, monotonicNow())

    private fun hasLocalControllerFence(snapshot: BoardCellSnapshot): Boolean {
        val connected = boardConnection.connectedBoard
        if (connected == null || connected.isCruxRelay) return false
        val physical = runCatching {
            PhysicalBoardIdentity.resolve(
                connected,
                boardBindings.bindingFor(connected.address),
            )
        }.getOrNull()
        return BoardCellLocalControllerFence.isHeld(
            expectedBoard = snapshot.physicalBoardId,
            connectedBoard = physical,
            connectionState = boardConnection.connectionState.value,
        )
    }

    private fun processControllerRecovery() {
        val snapshot = snapshot()
        if (snapshot == null || !BoardCellRecoveryFence.mayAttemptRecovery(snapshot, activeNodeId,
                controllerSilentMs(snapshot.physicalBoardId), CONTROLLER_LEASE_TIMEOUT_MS)) {
            if (snapshot?.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER ||
                activeNodeId !in snapshot.members) {
                recoveryJob?.cancel(); recoveryJob = null; recoveryAttempt = 0
            }
            return
        }
        if (recoveryJob?.isActive == true) return
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
            // Revalidate before taking the exclusive board connection: term,
            // hash and canonical liveness all have to still say the same
            // thing, so a controller that came back or a peer that already
            // recovered cannot be seized from underneath.
            if (!BoardCellRecoveryFence.stillRecoverable(current, activeNodeId, baseTerm, baseHash,
                    controllerSilentMs(current?.physicalBoardId), CONTROLLER_LEASE_TIMEOUT_MS)) {
                FipsDebugLog.event("boardcell", "recovery_abandoned_before_connect",
                    "reason" to "state moved on")
                return@launch
            }
            checkNotNull(current)
            // Held across the commit, not just the connect: the physical
            // reconnect surfaces asynchronously on connectedBoardDescriptor,
            // and clearing this too early let that emission re-initialize the
            // cell and destroy the base being recovered.
            authorizedReplicaPreservingBoard = current.physicalBoardId
            val existingFence = hasLocalControllerFence(current)
            val connected = try {
                existingFence || handoverLifecycle?.recoverController?.invoke(current) == true
            } catch (failure: CancellationException) {
                authorizedReplicaPreservingBoard = null
                throw failure
            }
            if (connected) {
                FipsDebugLog.event(
                    "boardcell", "controller_recovery_board_fenced",
                    "existingConnection" to existingFence,
                    "physicalBoard" to FipsDebugLog.id(current.physicalBoardId.value),
                )
                // Revalidate once more immediately before the commit; the
                // connect can take seconds and the coordinator re-checks the
                // same facts under its own lock.
                val beforeCommit = snapshot()
                if (BoardCellRecoveryFence.stillRecoverable(beforeCommit, activeNodeId, baseTerm,
                        baseHash, controllerSilentMs(beforeCommit?.physicalBoardId),
                        CONTROLLER_LEASE_TIMEOUT_MS)) {
                    coordinator.recoverController(current.physicalBoardId,
                        "exclusive-board-connection:${UUID.randomUUID()}", monotonicNow())
                } else {
                    FipsDebugLog.warning("boardcell", "recovery_abandoned_before_commit",
                        "reason" to "term/hash/liveness moved on")
                }
                authorizedReplicaPreservingBoard = null
                refreshSelected()
            } else {
                authorizedReplicaPreservingBoard = null
                recoveryAttempt++
                if (recoveryAttempt >= MAX_LOCAL_RECOVERY_ATTEMPTS) {
                    val stale = snapshot()
                    if (stale?.availability == BoardCellAvailability.FROZEN_NEEDS_CONTROLLER &&
                        activeNodeId in stale.members) {
                        // Failure to take the physical board is not a leave.
                        // Preserve the canonical membership and retry at the
                        // election's bounded maximum delay; the old behavior
                        // converted a transient transport/OEM fault into the
                        // misleading "mesh connection expired" state.
                        recoveryAttempt = MAX_LOCAL_RECOVERY_ATTEMPTS
                        FipsDebugLog.warning("boardcell", "controller_recovery_deferred",
                            "reason" to "physical board not yet reachable",
                            "attempts" to recoveryAttempt,
                            "membership" to "retained")
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
            meshLink.session?.settleMembership()
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

        /**
         * Every realm lease this cell can hold, in wire-binding preference
         * order. The handover lease is deliberately absent: it keeps the realm
         * alive across a transfer but never owns the wire.
         */
        private val REALM_LEASE_OWNERS = listOf(
            MeshOwners.BOARD_CELL, MeshOwners.NEARBY_BOARD_CELL, MeshOwners.PARTICIPANT,
        )
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
            NearbyJoinPhase(
                "membership_snapshot",
                null,
                BoardCellNearbyJoinPolicy.HOST_READINESS_TIMEOUT_MS,
            ),
        )
        /**
         * Transport repair (RPA rotation, Android advertiser restart, cross
         * probing) must finish inside the lease. Membership is removed only
         * after a sustained physical outage, not three missed app heartbeats.
         */
        private const val CONTROLLER_LEASE_TIMEOUT_MS = 60_000L
        private const val CONTROLLER_TRANSPORT_LOSS_GRACE_MS = 6_000L
        private const val MEMBER_LIVENESS_TIMEOUT_MS = 60_000L
        private const val PEER_DIAGNOSTICS_CHECKPOINT_MS = 10_000L
        private const val MAX_LOCAL_RECOVERY_ATTEMPTS = 3
    }
}
