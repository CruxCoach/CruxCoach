package com.cruxcoach.android.data

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.RelayGattServer
import kotlinx.coroutines.flow.first
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.android.boardcell.BoardCellEvent
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellPlatformPolicy
import com.cruxcoach.android.boardcell.HandoverPhase
import com.cruxcoach.android.boardcell.ProjectionResult
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.relay.RelayBoardName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Why the relay failed or stopped — mapped to localized strings in the UI
 *  (FEAT-044 §12: never fail silently). */
enum class RelayError {
    SERVER_START_FAILED,
    ADVERTISE_FAILED,
    NAME_SET_FAILED,
    BOARD_LOST,
    UNSUPPORTED_BOARD,

    /**
     * A relayed climb was accepted from the guest and then refused by the
     * board, with the link still up. Reported but not fatal: the guest's app
     * has already been told the write succeeded, so without this the wall
     * simply stayed dark on both sides of a relay that looked healthy.
     */
    FORWARD_FAILED,
}

data class CruxRelayState(
    val enabled: Boolean = false,
    val advertising: Boolean = false,
    val clientCount: Int = 0,
    val advertisedName: String? = null,
    val error: RelayError? = null,
    /** Raw technical detail for [error] (log-grade, appended to the message). */
    val errorDetail: String? = null,
    /**
     * Why the last inbound relay climb did not reach the wall.
     *
     * Separate from [error]: a refusal is the relay working — the guest's
     * write was understood and declined for a stated reason — where an error
     * means the relay itself is broken. Mixing them would put "sent the same
     * climb twice" next to "the board is gone".
     */
    val inboundRefusal: RelayInboundGate.Refusal? = null,
    /** Guests the radio can still take. Zero means the offer is withdrawn. */
    val availableSlots: Int = 0,
    /** Why no relay is being offered, when none is. */
    val suppression: RelaySuppression? = null,
)

/**
 * CruxRelay orchestration (FEAT-044): CruxCoach fronts the real board so
 * official-Kilter-app users can send climbs through it, transparently.
 *
 * Runs the board-emulation [RelayGattServer] + advertises the board's
 * 4488B571 UUID under a transparent [RelayBoardName]. A completed climb is
 * forwarded byte-faithfully to the real board via [BoardBleConnection.sendRawChunks]
 * (last-write-wins; the board's own writeMutex + one send-per-climb keep whole
 * climbs atomic). Queue and relay have independent lifecycles; the narrow
 * [BoardProjectionCoordinator] notification only prevents stale projection UI.
 *
 * The advertised name is set via the GLOBAL, persistent [android.bluetooth.BluetoothAdapter.setName]
 * (no per-advertiser API), snapshotted to a crash-safe flag so an abrupt death
 * still restores the phone's real Bluetooth name on next launch.
 *
 * NOTE: behaviour is validated on-device only (official app + real board) — see
 * docs/specs/0.2.2/FEAT-044 §10.
 */
class CruxRelayManager(
    private val context: Context,
    private val relayServer: RelayGattServer,
    private val advertiser: ClimbBleAdvertiser,
    private val bleConnection: BoardBleConnection,
    private val projectionCoordinator: BoardProjectionCoordinator,
    private val boardCellManager: BoardCellManager,
    private val userPreferences: UserPreferences,
    private val gattBridge: SessionGattBridge,
    private val boardRepository: BoardRepository,
    private val fipsMeshRuntime: com.cruxcoach.android.fips.FipsMeshRuntime,
    /**
     * Wall clock, injectable.
     *
     * The grace window is a duration in real time, and a lifecycle that turns
     * on "has it been eight seconds" cannot be tested against a scheduler that
     * fast-forwards virtual time only. Production passes the real clock.
     */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "CruxRelay/Manager"
        private const val PREFS = "cruxrelay"
        private const val KEY_NAME_DIRTY = "adapter_name_dirty"
        private const val KEY_ORIGINAL_NAME = "adapter_name_original"
        private const val NAME_PROPAGATE_TIMEOUT_MS = 2_000L
        private const val ADVERTISE_START_TIMEOUT_MS = 3_000L
        private const val STOPPED_NOTIFICATION_ID = 4402
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(CruxRelayState())
    val state: StateFlow<CruxRelayState> = _state.asStateFlow()
    /**
     * CruxRelay is infrastructure of an active BoardCell, not a user toggle.
     * Exactly the canonical controller owns it; API 28 never becomes one.
     */
    private val relayRequiredFlow = MutableStateFlow(false)

    private val inboundGate = RelayInboundGate()

    /** The lease this offer was started under, so a superseded one knows itself. */
    private var startedUnder: RelayLease? = null

    /** Whether a connectable advertisement is currently out there. */
    private var advertisingOffer = false

    /**
     * When the board link went, so a blip can be told from a departure.
     *
     * Written and read only inside [lifecycleMutex]. It used to be stamped by
     * one collector while another was deciding health from it, and the two are
     * ordered by nothing: the deciding collector could run first and read a
     * link that had "never" dropped, turning a recoverable blip into a loss.
     */
    private var boardLinkLostAtMs: Long? = null

    /**
     * The relay's lifecycle, as one value rather than two booleans.
     *
     * Every transition happens inside [lifecycleMutex]. Four collectors call
     * [reconcile] — ownership, snapshots, mesh peers, guests — and before this
     * they could interleave halfway through a start or a stop, which is how a
     * healthy relay ended up being torn down by the very reconciliation the
     * claim it had just published woke up.
     */
    private enum class RelayLifecycle {
        /** No server, no advertisement. */
        STOPPED,

        /** Server up, advertisement out, guests may attach. */
        OFFERING,

        /**
         * Server up, advertisement withdrawn.
         *
         * Two reasons, and both are temporary by nature: the board dropped a
         * moment ago and is expected back, or every slot is in use. Connected
         * guests keep their links either way — theirs is not the problem.
         */
        RUNNING_WITHDRAWN,
    }

    private val lifecycleMutex = kotlinx.coroutines.sync.Mutex()
    private var lifecycle = RelayLifecycle.STOPPED
    private val running get() = lifecycle != RelayLifecycle.STOPPED
    private var forwardJob: Job? = null
    private var eventJob: Job? = null
    /** Climb identification for the most recent relayed write; see forwardJob. */
    private var identifyJob: Job? = null

    init {
        // Crash-safe: a previous run may have died with the adapter name still
        // changed. Restore it before anything else.
        restoreAdapterNameIfDirty()
        // Relay lifecycle follows canonical ownership and the physical link.
        // A reconnect re-arms it automatically because ownership did not turn
        // into an opt-in merely because Bluetooth was interrupted.
        scope.launch {
            combine(relayRequiredFlow, bleConnection.connectionState) { required, st ->
                required to st
            }.collect { reconcile() }
        }
        // Term/availability are part of the lease. A settling, frozen or
        // superseded controller must stop advertising immediately; the target
        // starts only after its ACTIVE snapshot is canonical.
        scope.launch {
            boardCellManager.snapshots.collect { reconcile() }
        }
        // Capacity is live, not a constant: a peer joining the mesh takes a
        // radio slot the relay was counting on, and the offer has to shrink
        // with it rather than keep advertising a slot that is gone.
        scope.launch {
            fipsMeshRuntime.directAuthenticatedPeers.collect { reconcile() }
        }
        // Board loss is a moment, and the grace window is measured from it —
        // inside the lifecycle step, by the same code that decides what the
        // moment means. The connection state is already an input of the
        // combine above, so every drop and every reconnect arrives there.
    }

    /** The current offer decision, from canonical state and live capacity. */
    private fun currentOffer(): RelayOffer = CruxRelayOwnershipPolicy.evaluate(
        localNodeId = boardCellManager.localNodeId(),
        snapshot = boardCellManager.snapshot(),
        startedUnder = startedUnder,
        meshAvailable = BoardCellPlatformPolicy.meshAvailable(Build.VERSION.SDK_INT),
        boardHealth = boardHealth(),
        meshPeers = fipsMeshRuntime.directAuthenticatedPeers.value.size,
        activeRelayClients = relayServer.getConnectedCount(),
        serverCeiling = RelayGattServer.MAX_CONNECTED_DEVICES,
    )

    private fun boardHealth(): RelayBoardLinkHealth = CruxRelayOwnershipPolicy.health(
        connectionState = bleConnection.connectionState.value,
        msSinceBoardLinkLost = boardLinkLostAtMs?.let { nowMs() - it },
    )

    /**
     * Whether a guest's write may be reported as landed.
     *
     * The GATT layer has already told the guest's app the write succeeded, so
     * this is the only place left that can decline to make a dark wall look
     * like a delivered climb.
     */
    fun mayAcknowledgeInboundWrite(): Boolean =
        CruxRelayOwnershipPolicy.mayAcknowledgeWrite(boardHealth())

    /** Legacy binary-compatible entry point; BoardCell ownership cannot be toggled. */
    @Deprecated("CruxRelay follows BoardCell controller ownership")
    fun enable() = setEnabled(true)

    /** Legacy binary-compatible entry point; BoardCell ownership cannot be toggled. */
    @Deprecated("CruxRelay follows BoardCell controller ownership")
    fun setEnabled(enabled: Boolean) {
        Log.i(TAG, "Ignoring manual relay ${if (enabled) "enable" else "disable"}; lifecycle is automatic")
    }

    /** Retry immediately after the Android permission result changes. */
    fun onPermissionsChanged() {
        scope.launch { reconcile() }
    }

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }

    /**
     * One lifecycle step, serialized.
     *
     * Four collectors call this — ownership, canonical snapshots, mesh peers,
     * relay guests — and a relay start suspends several times (adapter name,
     * GATT server, advertising). Without the lock a second caller walked into
     * the middle of that and decided the relay's fate from half-applied state.
     * Since pass 6 the relay also *causes* one of those wake-ups by publishing
     * its own claim, so the unserialized version reliably stopped a relay it
     * had just described as healthy.
     */
    private suspend fun reconcile() = lifecycleMutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked() {
        // Always read current values here. A relay stop suspends while
        // restoring the adapter name and closing GATT, so anything captured
        // earlier can describe the controller state from before a handover.
        observeBoardLink()
        val offer = currentOffer()
        // Serving counts as required: the relay is up and a guest is using it.
        // Only a suppression — not the controller, no board, no lease, or an
        // empty relay with no room — ends it.
        val required = offer !is RelayOffer.Suppressed
        relayRequiredFlow.value = required
        _state.update {
            it.copy(
                enabled = required,
                availableSlots = (offer as? RelayOffer.Offer)?.slots ?: 0,
                suppression = (offer as? RelayOffer.Suppressed)?.reason,
            )
        }
        publishRelayClaim(offer)
        if (required && !BlePermissionHelper.hasAdvertisingPermission(context)) {
            if (running) stopRelay()
            _state.update {
                it.copy(advertising = false, error = RelayError.ADVERTISE_FAILED,
                    errorDetail = "Bluetooth advertising permission denied")
            }
            return
        }
        if (required && !running) {
            startForOffer()
            return
        }
        if (required) {
            // The stable state, and the one that was missing: a healthy relay
            // that is already up stays up. All that is left to decide is
            // whether the advertisement matches the slots the radio has.
            matchAdvertisementToCapacity()
            return
        }
        if (!running) return

        val reason = (offer as? RelayOffer.Suppressed)?.reason
        // Recovery is the one suppression that keeps the server warm: the guest
        // stays connected and the board is expected back within the window. The
        // offer is withdrawn either way — nothing may be acknowledged as landed
        // while the wall cannot be reached.
        if (reason == RelaySuppression.BOARD_RECOVERING) {
            stopAdvertisingOffer()
            return
        }
        // Everything else — the lease is gone, the board is gone for good, the
        // cell is frozen, the radio is genuinely full — ends the relay.
        stopRelay()
        startedUnder = null
        val reportBoardLoss = BoardRelayPolicy.shouldReportBoardLoss(
            relayStillRequired = reason == RelaySuppression.BOARD_LOST,
            boardDisconnected = bleConnection.connectionState.value == ConnectionState.DISCONNECTED,
            membershipTransition = boardCellManager.membershipTransition.value,
        )
        if (reportBoardLoss) {
            postStoppedNotification(R.string.relay_error_board_lost)
            _state.update { it.copy(enabled = true, error = RelayError.BOARD_LOST) }
        } else {
            _state.update {
                if (it.error == RelayError.BOARD_LOST) {
                    it.copy(enabled = false, error = null, errorDetail = null)
                } else {
                    it.copy(enabled = false)
                }
            }
        }
    }

    /**
     * The board link, observed where the decision is made.
     *
     * The grace window is measured from the moment the link went, and the
     * device that decides whether this is a blip or a departure has to be the
     * one that stamped it — otherwise the two race and a reconnect can be read
     * as a loss.
     */
    private fun observeBoardLink() {
        val state = bleConnection.connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.SENDING) {
            boardLinkLostAtMs = null
        } else if (boardLinkLostAtMs == null) {
            boardLinkLostAtMs = nowMs()
            // Come back once the window is over, so an offer that is merely
            // withdrawn becomes a relay that is properly stopped.
            scope.launch {
                delay(CruxRelayOwnershipPolicy.GRACE_MS + 250)
                reconcile()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startForOffer() {
        when (BoardRelayPolicy.availability(board = bleConnection.connectedBoard)) {
            BoardRelayAvailability.AVAILABLE -> {
                // Remember the lease this offer belongs to. A resurrected
                // old owner recognises itself by it and stays quiet.
                boardCellManager.snapshot()?.let { snapshot ->
                    startedUnder = RelayLease(
                        epoch = snapshot.epoch,
                        controllerTerm = snapshot.controllerTerm,
                        physicalBoardId = snapshot.physicalBoardId.value,
                    )
                }
                relayServer.availableSlots = { currentRelaySlots() }
                // Answered on the GATT thread: only the board path, and
                // only what this device can know without asking anything.
                relayServer.admitWrite = { mayAcknowledgeInboundWrite() }
                inboundGate.reset()
                startRelay()
            }
            BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
            BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED,
            BoardRelayAvailability.RELAY_ENDPOINT,
            ->
                rejectEnable(RelayError.UNSUPPORTED_BOARD)
            BoardRelayAvailability.NO_BOARD -> Unit
        }
    }

    /**
     * A connectable advertisement means "there is room", so it is out exactly
     * while there is.
     *
     * The guaranteed slot is one slot. The guest who takes it fills it, and an
     * advertisement that stays up after that invites a connection the server
     * will refuse at accept time — the false promise the whole capacity model
     * exists to remove. This is also why a client connect reconciles rather
     * than blindly re-advertising: the answer depends on what is left.
     */
    private suspend fun matchAdvertisementToCapacity() {
        val free = currentRelaySlots()
        when {
            free <= 0 -> stopAdvertisingOffer()
            !advertisingOffer -> restartRelayAdvertising()
        }
    }

    /**
     * Tells the cell what its relay is doing, so a member can say so too.
     *
     * Nothing here decides anything: canonical relay state is descriptive, the
     * offer was already decided from `controllerId`, and a member reading it
     * cannot become the owner by believing it. A device that is not the
     * controller commits nothing — the coordinator refuses, and the reducer
     * would refuse after it.
     */
    private suspend fun publishRelayClaim(offer: RelayOffer) {
        val snapshot = boardCellManager.snapshot() ?: return
        if (snapshot.controllerId != boardCellManager.localNodeId()) return
        val claim = CruxRelayOwnershipPolicy.claimFor(snapshot, offer, boardHealth())
        boardCellManager.publishRelayState(claim)
    }

    /**
     * The shared list already shows this occurrence on the wall.
     *
     * Canonical, so it is the same answer on every device — which is the point:
     * a controller that has just taken over has no ledger entry for a guest
     * write the previous one completed, and this is how it finds out anyway.
     */
    private fun alreadyOnTheWall(entryId: String, climbUuid: String, angle: Int): Boolean {
        val snapshot = boardCellManager.snapshot() ?: return false
        val playlist = snapshot.playlist
        if (playlist.currentEntryId != entryId) return false
        if (playlist.entry(entryId) == null) return false
        val projection = snapshot.projection ?: return false
        return snapshot.projectionKnown &&
            projection.climbUuid.equals(climbUuid, ignoreCase = true) && projection.angle == angle
    }

    /** The canonical answer arrived: terminal on success, retryable otherwise. */
    private fun settleOperation(operation: RelayInboundGate.Operation, committed: Boolean) {
        val at = nowMs()
        if (committed) inboundGate.markLanded(operation, at)
        else inboundGate.markFailed(operation, at)
    }

    /** Slots the radio can actually spare right now, for the GATT server. */
    private fun currentRelaySlots(): Int = CruxRelayOwnershipPolicy.availableSlots(
        meshPeers = fipsMeshRuntime.directAuthenticatedPeers.value.size,
        boardLinkHeld = boardHealth() != RelayBoardLinkHealth.LOST,
        activeRelayClients = relayServer.getConnectedCount(),
        serverCeiling = RelayGattServer.MAX_CONNECTED_DEVICES,
    )

    private fun rejectEnable(error: RelayError) {
        _state.update { it.copy(enabled = relayRequiredFlow.value, error = error, errorDetail = null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startRelay() {
        val board = bleConnection.connectedBoard
        if (board == null) {
            Log.w(TAG, "startRelay: no connected board descriptor")
            rejectEnable(RelayError.UNSUPPORTED_BOARD)
            return
        }
        lifecycle = RelayLifecycle.RUNNING_WITHDRAWN

        // 1) Snapshot + set the transparent adapter name (crash-safe). An
        // unset name would advertise the relay under the phone's own name —
        // abort instead of impersonating nothing recognizable.
        val desired = RelayBoardName.transparentBoard(board.displayName, board.apiLevel)
        if (!snapshotAndSetAdapterName(desired)) {
            Log.e(TAG, "adapter name change did not propagate")
            abortStart(RelayError.NAME_SET_FAILED, null)
            return
        }

        // 2) Keep the real board link parked while this independent transport
        // is enabled. Relay never creates or tears down a shared queue.
        bleConnection.acquireKeepAlive(BoardConnectionOwner.RELAY)
        // Our own board link would otherwise register as the first relay
        // client the moment the server opens (see RelayGattServer).
        relayServer.boardAddressProvider = { bleConnection.connectedBoard?.address }
        if (!relayServer.start()) {
            Log.e(TAG, "relay server failed to start")
            abortStart(RelayError.SERVER_START_FAILED, null)
            return
        }

        // Identifying a relayed climb needs a one-time index build; start it
        // now so the first official-app write does not wait for it.
        scope.launch { projectionCoordinator.prepareForExternalWrites() }

        // Subscribe before advertising: MutableSharedFlow does not replay a
        // write that arrives while there is no collector.
        forwardJob = scope.launch {
            if (board.boardBrand == BoardBrand.MOONBOARD) {
                // MoonBoard speaks an ASCII Nordic-UART stream. Forward each
                // guest write in order and byte-for-byte; there is no Aurora
                // packet grouping for RelayFrameReassembler to perform.
                relayServer.writes.collect { inbound ->
                    // MoonBoard writes cannot currently be mapped back to a
                    // CruxCoach climb UUID, but they still cross the canonical
                    // serializer. Every replica therefore learns that the
                    // physical projection changed externally instead of
                    // continuing to claim the playlist climb is on the wall.
                    val result = boardCellManager.projectExternal(
                        boardWrite = { bleConnection.sendRawChunks(listOf(inbound.value)) },
                        identify = { null },
                    )
                    if (result is ProjectionResult.Committed || result is ProjectionResult.Duplicate) {
                        advertiser.clearActiveClimb()
                    } else {
                        Log.w(TAG, "canonical MoonBoard relay write failed: $result")
                        _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                    }
                }
            } else {
                relayServer.climbs.collect { inbound ->
                    // Identify first: what the guest sent decides where it may
                    // go, and an unidentifiable write can only ever be an
                    // external one.
                    val identified = projectionCoordinator.identifyExternal(inbound.climb)
                    val climb = identified?.let {
                        runCatching { boardRepository.getClimbByUuid(it.climbUuid, it.angle) }.getOrNull()
                    }
                    val now = nowMs()
                    // Derived, not minted: the cell, the climb and the hold
                    // data decide the ids, so whichever controller ends up
                    // serving this write computes the same pair. The guest's
                    // BLE address is deliberately not part of it — it rotates,
                    // and a reconnecting guest is the same person.
                    val operation = identified?.let {
                        RelayIngressIdentity.of(
                            cellId = boardCellManager.snapshot()?.cellId?.value.orEmpty(),
                            climbUuid = it.climbUuid,
                            angle = it.angle,
                            framesHash = inbound.climb.framesHash,
                        )
                    }
                    val decision = inboundGate.evaluate(
                        mode = userPreferences.relayInboundClimbMode.first(),
                        climbUuid = identified?.climbUuid,
                        angle = identified?.angle,
                        climbBrand = climb?.let { BoardBrand.fromWire(it.boardBrand) },
                        connectedBrand = bleConnection.connectedBoardBrand.value,
                        nowMs = now,
                        operation = operation,
                        climbLayoutId = climb?.layoutId,
                        connectedLayoutId = userPreferences.boardLayoutId.first().toLong(),
                        connectedAngle = userPreferences.boardAngle.first(),
                        // The ACK state a successor has and its ledger does not.
                        canonicallyLanded = operation != null && identified != null &&
                            alreadyOnTheWall(operation.entryId, identified.climbUuid, identified.angle),
                    )
                    when (decision) {
                        is RelayInboundGate.Decision.Refused -> {
                            Log.i(TAG, "relayed climb refused: ${decision.reason}")
                            _state.update { it.copy(inboundRefusal = decision.reason) }
                        }
                        is RelayInboundGate.Decision.AppendToEnd -> {
                            // The wall keeps what it has; the climb joins the
                            // end of the list like any other add — under the
                            // occurrence id decided at ingress, so a repeat of
                            // the same write finds it already there.
                            _state.update { it.copy(inboundRefusal = null) }
                            // Terminal only when the controller says so. The
                            // submit is asynchronous, and marking it landed
                            // here — which is what this did — turned a refused
                            // playlist commit into a duplicate that could
                            // never be retried.
                            gattBridge.appendSharedPlaylistEntry(
                                identified!!.climbUuid, identified.angle, "relay_append",
                                entryId = decision.operation.entryId,
                                onTerminal = { committed ->
                                    settleOperation(decision.operation, committed)
                                },
                            )
                        }
                        is RelayInboundGate.Decision.ProjectNow -> {
                            // The GATT layer has already told the guest's app
                            // the write succeeded. Without a usable path to the
                            // wall this is the last place that can decline to
                            // make a dark board look like a delivered climb.
                            if (!mayAcknowledgeInboundWrite()) {
                                Log.w(TAG, "relayed climb arrived without a usable board path")
                                _state.update { it.copy(error = RelayError.BOARD_LOST) }
                                // Not landed, so the guest's next attempt is a
                                // retry of this operation rather than a climb
                                // the list has never heard of.
                                inboundGate.markFailed(decision.operation, now)
                                return@collect
                            }
                            _state.update { it.copy(inboundRefusal = null) }
                            val result = boardCellManager.projectExternal(
                                boardWrite = { bleConnection.sendRawChunks(inbound.climb.chunks) },
                                identify = { identified },
                                // The operation id decided at ingress. The
                                // canonical serializer deduplicates by it, so a
                                // repeat after a handover writes the wall once.
                                commandId = decision.operation.operationId,
                            )
                            if (result is ProjectionResult.Committed || result is ProjectionResult.Duplicate) {
                                advertiser.clearActiveClimb()
                                // The guest's bytes are what lit the wall, so
                                // the list records the occurrence rather than
                                // re-encoding the climb a second time.
                                // The wall has it; the occurrence still has to
                                // be canonical before this counts as landed,
                                // or a refused commit would leave a lit wall
                                // with no occurrence and no way to retry.
                                if (identified != null) {
                                    gattBridge.adoptProjectedEntry(
                                        identified.climbUuid, identified.angle, "relay_project",
                                        entryId = decision.operation.entryId,
                                        onTerminal = { committed ->
                                            settleOperation(decision.operation, committed)
                                        },
                                    )
                                } else {
                                    settleOperation(decision.operation, true)
                                }
                                identifyJob?.cancel()
                                identifyJob = scope.launch {
                                    val projection = if (result is ProjectionResult.Committed) {
                                        (result.envelope.event as? BoardCellEvent.ProjectCommitted)?.projection
                                    } else boardCellManager.snapshot()?.projection
                                    projectionCoordinator.onCanonicalExternalBoardWrite(projection)
                                }
                            } else {
                                Log.w(TAG, "relayed climb was not canonically committed: $result")
                                _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                                inboundGate.markFailed(decision.operation, now)
                            }
                        }
                    }
                }
            }
        }
        eventJob = scope.launch {
            relayServer.connectionEvents.collect { _ ->
                _state.update { it.copy(clientCount = relayServer.getConnectedCount()) }
                // A guest arriving or leaving changes the number this relay is
                // advertising the existence of, so it goes through the same
                // step as every other capacity change: claim, offer and
                // advertisement move together, and the advertisement comes
                // back only if a slot is genuinely free. Blindly restarting it
                // here — which is what a connectable legacy advertising set
                // needs after a connection — kept the offer up with the one
                // guaranteed slot already in use.
                reconcile()
            }
        }

        val advertisingFailure = startRelayAdvertisingAndAwait()
        if (advertisingFailure != null) {
            abortStart(RelayError.ADVERTISE_FAILED, advertisingFailure)
            return
        }
        advertisingOffer = true
        lifecycle = RelayLifecycle.OFFERING
        _state.update { it.copy(advertising = true, advertisedName = desired) }

        // FGS keeps advertising alive (Android 12+ throttles background
        // advertising) + shows the mandatory persistent sharing notification.
        runCatching { CruxRelayService.start(context) }
            .onFailure { Log.e(TAG, "failed to start relay foreground service", it) }

        Log.i(TAG, "CruxRelay started as \"$desired\"")
    }

    private suspend fun startRelayAdvertisingAndAwait(): String? {
        val result = advertiser.startRelayAdvertising()
        if (result != "started" && result != "updated") return result
        val status = withTimeoutOrNull(ADVERTISE_START_TIMEOUT_MS) {
            advertiser.awaitRelayAdvertisingStart()
        }
        return if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            null
        } else {
            status?.let { "status=$it" } ?: "timeout"
        }
    }

    /**
     * Withdraw the offer without tearing the relay down.
     *
     * The one state where those differ: the board dropped a moment ago and is
     * expected back. Guests already connected stay connected — their link is
     * not the problem — but nothing new may attach to a relay that currently
     * cannot reach a wall, and nothing may be acknowledged as landed.
     */
    private fun stopAdvertisingOffer() {
        if (running) lifecycle = RelayLifecycle.RUNNING_WITHDRAWN
        if (!advertisingOffer) return
        advertisingOffer = false
        runCatching { advertiser.stopRelayAdvertising() }
            .onFailure { Log.w(TAG, "withdrawing the relay offer failed", it) }
        _state.update { it.copy(advertising = false) }
    }

    private suspend fun restartRelayAdvertising() {
        if (!running) return
        _state.update { it.copy(advertising = false) }
        val failure = startRelayAdvertisingAndAwait()
        if (!running) return
        if (failure == null) {
            advertisingOffer = true
            lifecycle = RelayLifecycle.OFFERING
            _state.update { it.copy(advertising = true, error = null, errorDetail = null) }
        } else {
            Log.e(TAG, "relay re-advertising failed: $failure")
            _state.update {
                it.copy(advertising = false, error = RelayError.ADVERTISE_FAILED, errorDetail = failure)
            }
        }
    }

    /** Failed mid-start: unwind what was set up (board stays connected — the
     *  user is still using it), disable the toggle, surface the error. */
    private suspend fun abortStart(error: RelayError, detail: String?) {
        stopRelay()
        _state.update { it.copy(enabled = relayRequiredFlow.value, error = error, errorDetail = detail) }
    }

    /** Stop only the relay transport. The direct board connection remains. */
    @SuppressLint("MissingPermission")
    private suspend fun stopRelay() {
        if (!running) return
        lifecycle = RelayLifecycle.STOPPED
        forwardJob?.cancel(); forwardJob = null
        eventJob?.cancel(); eventJob = null
        identifyJob?.cancel(); identifyJob = null
        bleConnection.releaseKeepAlive(BoardConnectionOwner.RELAY)

        advertisingOffer = false
        advertiser.stopRelayAdvertising()
        relayServer.stop()

        restoreAdapterName()
        _state.update { it.copy(advertising = false, clientCount = 0, advertisedName = null) }
        Log.i(TAG, "CruxRelay stopped; direct board connection preserved")
    }

    /** Final, auto-dismissible "sharing stopped" notification (FEAT-044 §12:
     *  never fail/stop silently). Posted BEFORE the enabled=false state change
     *  tears down [CruxRelayService]'s persistent notification, on the same
     *  channel (which the service created when sharing started). Best-effort:
     *  POST_NOTIFICATIONS may have been revoked. */
    private fun postStoppedNotification(@StringRes textRes: Int) {
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, CruxRelayService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(context.getString(R.string.relay_notification_title))
                .setContentText(context.getString(textRes))
                .setAutoCancel(true)
                .build()
            mgr.notify(STOPPED_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "failed to post relay-stopped notification", it) }
    }

    // --- Adapter name snapshot / restore (crash-safe) ---

    /** @return true once [desired] is live on the adapter — false on Bluetooth
     *  off or a setName that never propagated (surfaced as NAME_SET_FAILED). */
    @SuppressLint("MissingPermission")
    private suspend fun snapshotAndSetAdapterName(desired: String): Boolean {
        val a = adapter ?: return false
        val original = a.name
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) {
            prefs.edit().putString(KEY_ORIGINAL_NAME, original).putBoolean(KEY_NAME_DIRTY, true).apply()
        }
        if (a.name == desired) return true
        a.name = desired
        // setName is async — wait (bounded) for it to propagate before advertising,
        // since the scan-response name is read from the adapter.
        withTimeoutOrNull(NAME_PROPAGATE_TIMEOUT_MS) {
            while (adapter?.name != desired) delay(100)
        }
        return adapter?.name == desired
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) return
        val original = prefs.getString(KEY_ORIGINAL_NAME, null)
        if (original != null) runCatching { adapter?.name = original }
        prefs.edit().putBoolean(KEY_NAME_DIRTY, false).remove(KEY_ORIGINAL_NAME).apply()
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterNameIfDirty() {
        // On a fresh process, a set dirty flag means a prior run died without
        // restoring — put the phone's real Bluetooth name back.
        restoreAdapterName()
    }
}
