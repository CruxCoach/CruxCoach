package com.cruxcoach.android.boardcell

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.fips.FipsMeshRuntime
import com.cruxcoach.android.fips.FipsRealmContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class BoardCellHandoverLifecycle(
    /** Must acquire the target HOST role, board keep-alive and prove the board is connected. */
    val prepareTarget: suspend (BoardCellSnapshot) -> Boolean,
    /** Called on the old controller only after canonical HANDOVER_COMPLETED. */
    val completeSource: suspend (BoardCellSnapshot) -> Unit,
    /** Rolls back a target that prepared local HOST resources before source abort. */
    val abortTarget: suspend (BoardCellSnapshot) -> Unit,
)

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
    private var activeNodeId = durableStore.localFallbackNodeId()
    private val boardRealmAvailable = AtomicBoolean(false)
    private val _snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    val snapshots = _snapshots.asStateFlow()
    // The suspendable transport callback provides real backpressure: after
    // ACCEPTED no command can disappear, while a hostile peer cannot grow RAM.
    private val sessionCommandChannel = Channel<InboundSessionCommand>(256)
    val sessionCommands = sessionCommandChannel.receiveAsFlow()
    private val commandAckChannel = Channel<BoardCommandAck>(256)
    val commandAcks = commandAckChannel.receiveAsFlow()
    @Volatile private var handoverLifecycle: BoardCellHandoverLifecycle? = null
    private val handledHandoverPhase = mutableSetOf<String>()

    init {
        current = this
        meshTransport.onSessionCommand = { sessionCommandChannel.send(it) }
        meshTransport.onCommandAck = { _, ack -> commandAckChannel.send(ack) }
        scope.launch {
            runtime.messages.collect { message ->
                meshTransport.receive(message.senderNpub, message.payload, monotonicNow())
                refreshSelected()
                processHandover()
            }
        }
        scope.launch { maintenanceLoop() }
        scope.launch {
            boardConnection.connectedBoardDescriptor.collectLatest { board ->
                if (board == null) {
                    boardRealmAvailable.set(false)
                    if (heldRuntime) { runtime.release(); heldRuntime = false }
                    return@collectLatest
                }
                val physical = PhysicalBoardIdentity.resolve(board, boardBindings.bindingFor(board.address))
                BoardCellScopeRegistry.replaceProvisionalSelection(physical)
                val restored = durableStore.snapshot(physical)
                val cellId = restored?.cellId ?: BoardCellId.forPhysical(physical)
                val fipsActive = BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT) &&
                    runtime.activateRealm(FipsRealmContext(cellId.value, cellId.value))
                activeNodeId = if (fipsActive) runtime.localNpub else durableStore.localFallbackNodeId()
                val activeTransport: BoardCellTransport = if (fipsActive) meshTransport else NoOpBoardCellTransport
                boardRealmAvailable.set(true)
                if (fipsActive && !heldRuntime) { runtime.acquire(); heldRuntime = true }
                coordinator = BoardCellCoordinator(activeNodeId, activeTransport, durableStore, settleMs = 2_000)
                if (fipsActive) meshTransport.attach(coordinator)
                val restoredForNode = restored?.takeIf { activeNodeId in it.members }
                if (restoredForNode != null && coordinator.restoreTrustedSnapshot(restoredForNode, monotonicNow()) is BoardCellApplyResult.Applied) {
                    if (fipsActive) {
                        meshTransport.rememberSnapshot(restoredForNode)
                        if (restoredForNode.controllerId == activeNodeId ||
                            (restoredForNode.handover?.phase == HandoverPhase.COMMITTED &&
                                restoredForNode.handover.sourceControllerId == activeNodeId)) {
                            meshTransport.publishSnapshot(restoredForNode)
                        }
                        else meshTransport.requestSnapshot(restoredForNode.cellId, restoredForNode.sequence)
                    }
                    coordinator.recoverPendingWrite(physical)
                } else {
                    claimAndSettle(physical, cellId)
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
        val board = writableBoard() ?: return ProjectionResult.Refused("BoardCell unavailable")
        return coordinator.project(board, projection, monotonicNow(), commandId, baseSequence, boardWrite).also {
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
        if (!::coordinator.isInitialized || !BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) return null
        val snapshot = snapshot() ?: return null
        return commandId.takeIf { meshTransport.sendSessionCommand(snapshot, commandId, payload, context) }
    }

    fun retrySessionCommand(payload: ByteArray, context: BoardPlaylistCommandContext?,
        commandId: String, basePlaylistRevision: Long): Boolean {
        val snapshot = snapshot() ?: return false
        return meshTransport.sendSessionCommand(snapshot, commandId, payload, context,
            basePlaylistRevision)
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
        if (!BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) return false
        val physical = runCatching { PhysicalBoardId(physicalBoardId) }.getOrNull() ?: return false
        val cell = runCatching { BoardCellId(boardCellId) }.getOrNull() ?: return false
        if (BoardCellId.forPhysical(physical) != cell) return false
        BoardCellScopeRegistry.replaceProvisionalSelection(physical)
        if (!runtime.activateRealm(FipsRealmContext(cell.value, cell.value))) return false
        activeNodeId = runtime.localNpub
        val existing = if (::coordinator.isInitialized) coordinator.snapshot(physical) else null
        if (existing?.cellId != cell || activeNodeId !in existing.members) {
            coordinator = BoardCellCoordinator(activeNodeId, meshTransport, durableStore, settleMs = 2_000)
            meshTransport.attach(coordinator)
            durableStore.snapshot(physical)?.takeIf { it.cellId == cell && activeNodeId in it.members }?.let {
                coordinator.restoreTrustedSnapshot(it, monotonicNow())
                meshTransport.rememberSnapshot(it)
            }
        }
        // A participant can replicate and issue scoped commands, but cannot
        // physically write until a committed handover plus board connection.
        boardRealmAvailable.set(false)
        refreshSelected()
        return true
    }

    fun installHandoverLifecycle(value: BoardCellHandoverLifecycle?) { handoverLifecycle = value }

    /** No implicit election: caller must identify the intended, user-visible target. */
    fun requestOrderlyHandover(targetControllerId: String): Boolean {
        if (!::coordinator.isInitialized || targetControllerId.isBlank()) return false
        val board = BoardCellScopeRegistry.selected.value ?: return false
        val snapshot = coordinator.snapshot(board) ?: return false
        if (snapshot.controllerId != activeNodeId || targetControllerId !in snapshot.members) return false
        scope.launch {
            coordinator.prepareHandover(board, targetControllerId, monotonicNow())
            refreshSelected()
        }
        return true
    }

    /** Safe convenience only when membership proves there is exactly one explicit successor. */
    fun soleSuccessor(): String? = snapshot()?.members?.filter { it != activeNodeId }?.singleOrNull()

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
            coordinator.joinMember(board, memberNpub); refreshSelected()
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
            delay(5_000)
            if (!::coordinator.isInitialized) continue
            BoardCellScopeRegistry.selected.value?.let { board ->
                coordinator.heartbeat(board, monotonicNow())
                coordinator.expireLocalDeadlines(monotonicNow())
                refreshSelected()
                processHandover()
            }
            meshTransport.retryOutbox()
            meshTransport.antiEntropy()
        }
    }

    private suspend fun processHandover() {
        val board = BoardCellScopeRegistry.selected.value ?: return
        val snapshot = coordinator.snapshot(board) ?: return
        val h = snapshot.handover ?: return
        val phaseKey = "${h.transferId}:${h.phase}"
        if (!handledHandoverPhase.add(phaseKey)) return
        val lifecycle = handoverLifecycle
        when {
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.PREPARED -> {
                val ready = lifecycle?.prepareTarget?.invoke(snapshot) == true && boardRealmAvailable.get()
                if (ready) coordinator.targetReady(board, "host-board-ready:${h.transferId}")
                else handledHandoverPhase.remove(phaseKey)
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.COMMITTED -> {
                val ready = lifecycle?.prepareTarget?.invoke(snapshot) == true && boardRealmAvailable.get()
                if (ready) coordinator.completeHandover(board, h.transferId, monotonicNow())
                else handledHandoverPhase.remove(phaseKey)
            }
            h.sourceControllerId == activeNodeId && h.phase == HandoverPhase.COMPLETED -> {
                lifecycle?.completeSource?.invoke(snapshot)
            }
            h.targetControllerId == activeNodeId && h.phase == HandoverPhase.ABORTED -> {
                lifecycle?.abortTarget?.invoke(snapshot)
            }
        }
        refreshSelected()
    }

    private suspend fun claimAndSettle(board: PhysicalBoardId, cellId: BoardCellId): BoardCellSnapshot? {
        val claim = coordinator.beginClaim(board, cellId, monotonicNow())
        repeat(8) { delay(250); if (BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT)) meshTransport.publishClaim(claim) }
        repeat(9) { coordinator.settle(board, monotonicNow())?.let { return it }; delay(250) }
        return coordinator.settle(board, monotonicNow())
    }

    private fun writableBoard(): PhysicalBoardId? {
        if (!boardRealmAvailable.get() || !::coordinator.isInitialized) return null
        return BoardCellScopeRegistry.selected.value
    }

    private fun refreshSelected() {
        _snapshots.value = snapshot()
    }

    private fun monotonicNow(): Long = SystemClock.elapsedRealtime()

    companion object {
        @Volatile internal var current: BoardCellManager? = null
            private set
    }
}
