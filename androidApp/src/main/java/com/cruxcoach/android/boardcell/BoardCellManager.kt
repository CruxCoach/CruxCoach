package com.cruxcoach.android.boardcell

import android.content.Context
import android.util.Base64
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Productive binding between board connection lifecycle and the BoardCell reducer. */
@Singleton
class BoardCellManager @Inject constructor(
    @ApplicationContext context: Context,
    private val boardConnection: BoardBleConnection,
    private val runtime: FipsMeshRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = BoardCellMeshTransport(runtime)
    private lateinit var coordinator: BoardCellCoordinator
    private val store = context.getSharedPreferences("board_cells_v1", Context.MODE_PRIVATE)
    private val boardBindings = PhysicalBoardBindingStore(context)
    private var heldRuntime = false
    private val boardRealmAvailable = AtomicBoolean(false)
    private val _snapshots = MutableStateFlow<BoardCellSnapshot?>(null)
    val snapshots = _snapshots.asStateFlow()
    private val _sessionCommands = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val sessionCommands = _sessionCommands.asSharedFlow()

    init {
        current = this
        scope.launch { runtime.messages.collect { transport.receive(it.senderNpub, it.payload); persistSelected() } }
        scope.launch { maintenanceLoop() }
        scope.launch {
            transport.onSessionCommand = { sender, payload -> _sessionCommands.tryEmit(sender to payload) }
            boardConnection.connectedBoardDescriptor.collectLatest { board ->
                if (board == null) {
                    boardRealmAvailable.set(false)
                    if (heldRuntime) { runtime.release(); heldRuntime = false }
                    return@collectLatest
                }
                val physical = PhysicalBoardIdentity.resolve(board, boardBindings.bindingFor(board.address))
                BoardCellScopeRegistry.replaceProvisionalSelection(physical)
                val restored = restore(physical)
                val cellId = restored?.cellId ?: BoardCellId.forPhysical(physical)
                if (!runtime.activateRealm(FipsRealmContext(cellId.value, cellId.value))) return@collectLatest
                boardRealmAvailable.set(true)
                if (!heldRuntime) { runtime.acquire(); heldRuntime = true }
                coordinator = BoardCellCoordinator(runtime.localNpub, transport, settleMs = 2_000)
                transport.attach(coordinator)
                if (restored != null && coordinator.restoreTrustedSnapshot(restored) is BoardCellApplyResult.Applied) {
                    transport.rememberSnapshot(restored)
                    if (System.currentTimeMillis() > restored.leaseUntilMs &&
                        restored.controllerId == runtime.localNpub) {
                        val settled = claimAndSettle(physical, restored.cellId)
                        if (settled?.cellId == restored.cellId && settled.controllerId == runtime.localNpub) {
                            coordinator.resumeOwnController(physical, System.currentTimeMillis())
                        }
                    } else if (restored.controllerId == runtime.localNpub) {
                        transport.publishSnapshot(restored)
                    } else {
                        transport.requestSnapshot(restored.cellId, restored.sequence)
                    }
                } else {
                    claimAndSettle(physical, cellId)
                }
                persist(physical)
            }
        }
    }

    suspend fun project(projection: BoardProjection, boardWrite: suspend () -> Boolean): ProjectionResult {
        if (!boardRealmAvailable.get()) return ProjectionResult.Refused("board realm unavailable")
        if (!::coordinator.isInitialized) return ProjectionResult.Refused("FIPS/BoardCell unavailable")
        val board = BoardCellScopeRegistry.selected.value
            ?: return ProjectionResult.Refused("physical board not selected")
        return coordinator.project(board, projection, System.currentTimeMillis(), boardWrite).also { persist(board) }
    }

    suspend fun projectExternal(
        boardWrite: suspend () -> Boolean,
        identify: suspend () -> BoardProjection?,
    ): ProjectionResult {
        if (!boardRealmAvailable.get()) return ProjectionResult.Refused("board realm unavailable")
        if (!::coordinator.isInitialized) return ProjectionResult.Refused("FIPS/BoardCell unavailable")
        val board = BoardCellScopeRegistry.selected.value
            ?: return ProjectionResult.Refused("physical board not selected")
        return coordinator.projectExternal(board, System.currentTimeMillis(), boardWrite, identify)
            .also { persist(board) }
    }

    suspend fun replacePlaylist(state: BoardPlaylistState): Boolean {
        if (!::coordinator.isInitialized) return false
        val board = BoardCellScopeRegistry.selected.value ?: return false
        return (coordinator.replacePlaylist(board, state, System.currentTimeMillis()) != null).also { persist(board) }
    }

    fun sendSessionCommand(payload: ByteArray): Boolean {
        if (!::coordinator.isInitialized) return false
        val snapshot = snapshot() ?: return false
        return transport.sendSessionCommand(snapshot, UUID.randomUUID().toString(), payload)
    }

    /** Entry point for the explicit QR/manual fallback when a controller rotates addresses. */
    fun bindPhysicalBoardFallback(observedAddress: String, durableBindingId: String) {
        boardBindings.bind(observedAddress, durableBindingId)
    }

    fun requestOrderlyHandover() {
        if (!::coordinator.isInitialized) return
        val board = BoardCellScopeRegistry.selected.value ?: return
        val snapshot = coordinator.snapshot(board) ?: return
        if (BoardCellScopeRegistry.selected.value == board) _snapshots.value = snapshot
        val successor = snapshot.members.filter { it != runtime.localNpub }.minOrNull() ?: return
        scope.launch {
            coordinator.transferController(board, successor, System.currentTimeMillis())
            persist(board)
        }
    }

    /** Competition realm changes identity/routes; board writes wait for an explicit cell reconnect. */
    fun freezeForTransportRealmSwitch() {
        boardRealmAvailable.set(false)
        if (!::coordinator.isInitialized) return
        val board = BoardCellScopeRegistry.selected.value ?: return
        scope.launch {
            coordinator.freezeForTransportRealmSwitch(board)
            persist(board)
        }
    }

    fun approveMember(memberNpub: String) {
        if (!::coordinator.isInitialized) return
        val board = BoardCellScopeRegistry.selected.value ?: return
        scope.launch { coordinator.joinMember(board, memberNpub); persist(board) }
    }

    fun snapshot(): BoardCellSnapshot? = if (::coordinator.isInitialized)
        BoardCellScopeRegistry.selected.value?.let(coordinator::snapshot) else null

    private suspend fun maintenanceLoop() {
        while (true) {
            delay(5_000)
            if (!::coordinator.isInitialized) continue
            val now = System.currentTimeMillis()
            BoardCellScopeRegistry.selected.value?.let { board ->
                coordinator.renewLease(board, now)
                coordinator.freezeExpiredControllers(now)
                persist(board)
            }
            transport.retryOutbox()
            transport.antiEntropy()
        }
    }

    private suspend fun claimAndSettle(
        board: PhysicalBoardId,
        cellId: BoardCellId,
    ): BoardCellSnapshot? {
        val claim = coordinator.beginClaim(board, cellId, System.currentTimeMillis())
        // Direct FIPS neighbors can appear during radio/Noise setup. Repeating
        // one idempotent claim covers that setup window; the additional settle
        // loop also honors a deadline extended by a competing late claim.
        repeat(8) { delay(250); transport.publishClaim(claim) }
        repeat(9) {
            coordinator.settle(board, System.currentTimeMillis())?.let { return it }
            delay(250)
        }
        return coordinator.settle(board, System.currentTimeMillis())
    }

    private fun persistSelected() { BoardCellScopeRegistry.selected.value?.let(::persist) }
    private fun persist(board: PhysicalBoardId) {
        if (!::coordinator.isInitialized) return
        val snapshot = coordinator.snapshot(board) ?: return
        val bytes = BoardCellWireCodec.encode(BoardCellWireMessage.Snapshot(snapshot))
        store.edit().putString(board.value, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    private fun restore(board: PhysicalBoardId): BoardCellSnapshot? = runCatching {
        val encoded = store.getString(board.value, null) ?: return null
        (BoardCellWireCodec.decode(Base64.decode(encoded, Base64.NO_WRAP)) as BoardCellWireMessage.Snapshot).value
    }.getOrNull()

    companion object {
        @Volatile internal var current: BoardCellManager? = null
            private set
    }
}
