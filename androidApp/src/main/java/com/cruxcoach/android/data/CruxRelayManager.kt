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
import com.cruxcoach.android.boardcell.BoardProjection
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
    /**
     * Monotonic clock for the grace deadline.
     *
     * Separate from [nowMs] on purpose: the wall clock can be stepped by NTP
     * or a timezone change, and a grace window measured against it can come
     * out arbitrarily long — or negative. `elapsedRealtime` only ever moves
     * forward, and keeps counting while the device sleeps, which is exactly
     * the property a "the board has been gone for N seconds" question needs.
     */
    private val monotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
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
    /** Whether guest writes are currently admitted; see [withdrawOfferNow]. */
    private var admissionOpen = false
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
        msSinceBoardLinkLost = boardLinkLostAtMs?.let { monotonicMs() - it },
    )

    /**
     * Whether a guest's write may be admitted at all right now.
     *
     * Answered on the GATT thread, so board health only — it is the question
     * "is there a wall on the other side of me", asked before anything is
     * accepted. Reporting a *delivery* is a different and later question; see
     * [mayReportDelivered].
     */
    fun mayAcknowledgeInboundWrite(): Boolean =
        CruxRelayOwnershipPolicy.mayAcknowledgeWrite(boardHealth())

    /**
     * Whether this device may tell a guest their climb was delivered — now.
     *
     * Asked again at the moment of answering, and that is the whole point. A
     * relayed operation suspends: the intent barrier travels the mesh, the
     * board write waits on chunks, the terminal playlist commit waits on the
     * controller. The link can drop inside any of them, and the lifecycle
     * deliberately keeps the forward collector alive through the grace window
     * so a blip does not tear a working relay down. Checking board health only
     * where the operation *started* therefore allowed the one sequence the
     * contract forbids:
     *
     *     accepted while healthy → operation runs → board link drops
     *     → advertising and admission withdrawn → grace → success ACK
     *
     * The lease is here for the same reason. A handover, a frozen cell or a
     * different board underneath means this device is no longer the one whose
     * word about that wall counts, whatever it was when the bytes arrived.
     *
     * A write that really did land is still recorded canonically — see the
     * call sites — so the guest's retry after recovery replays the success
     * without a second write or a second occurrence. Withholding the ACK costs
     * them a round trip; sending it would cost them the truth.
     */
    private fun mayReportDelivered(): Boolean {
        if (!mayAcknowledgeInboundWrite()) return false
        val snapshot = boardCellManager.snapshot() ?: return false
        if (snapshot.controllerId != boardCellManager.localNodeId()) return false
        if (snapshot.availability != BoardCellAvailability.ACTIVE) return false
        snapshot.handover?.let { if (it.phase != HandoverPhase.COMPLETED) return false }
        startedUnder?.let { lease ->
            if (lease.epoch != snapshot.epoch || lease.controllerTerm != snapshot.controllerTerm) {
                return false
            }
            if (lease.physicalBoardId != snapshot.physicalBoardId.value) return false
        }
        return true
    }

    /** The verdict for a guest write, refused unless this device may still say so. */
    private fun settleDelivered(pendingResponse: Int?, delivered: Boolean) {
        val may = delivered && mayReportDelivered()
        if (delivered && !may) {
            Log.w(TAG, "withholding a delivery ACK: the board path or the lease has moved")
        }
        relayServer.settle(pendingResponse, may)
    }

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
        // Withdraw first, publish second — and never the other way round.
        // Publishing the claim suspends (it commits through the coordinator and
        // travels the mesh), and a relay that is no longer entitled to guests
        // must not stay connectable for however long that takes. Nothing here
        // waits on anything: stopping the advertisement and closing write
        // admission are local and immediate.
        if (!required || offer is RelayOffer.Serving) withdrawOfferNow()
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
            // What actually happened, published after it happened. The claim
            // above was made before the attempt, so on its own it says the
            // relay is not advertising — and on a retry after a failed start it
            // says nothing at all, because it is unchanged and commits nothing.
            publishRelayClaim(currentOffer())
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
            boardLinkLostAtMs = monotonicMs()
            // Come back exactly at the deadline, so an offer that is merely
            // withdrawn becomes a relay that is properly stopped. Not a
            // millisecond later: eight seconds is the contractual ceiling for
            // how long a guest may sit in front of a wall this device can no
            // longer reach, and `delay` plus the deadline check both read the
            // same monotonic clock.
            val deadline = boardLinkLostAtMs!! + CruxRelayOwnershipPolicy.GRACE_MS
            scope.launch {
                delay((deadline - monotonicMs()).coerceAtLeast(0))
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
                admissionOpen = true
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
            free <= 0 -> withdrawOfferNow()
            !advertisingOffer -> {
                restartRelayAdvertising()
                if (advertisingOffer && !admissionOpen) reopenAdmission()
            }
            !admissionOpen -> reopenAdmission()
        }
        // Whatever the advertisement ended up doing, the cell is told the
        // truth about it — including a failed start, which retracts the claim
        // rather than leaving a canonical invitation standing.
        publishRelayClaim(currentOffer())
    }

    private fun reopenAdmission() {
        relayServer.admitWrite = { mayAcknowledgeInboundWrite() }
        admissionOpen = true
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
        // `offered` is what a member acts on — "there is a connectable relay
        // over there" — so it comes from the advertisement being out, not from
        // this device's intention to put one out. Publishing the intention
        // meant a permission failure or an advertising error left the cell
        // holding a canonical invitation to a relay that was never there.
        val claim = CruxRelayOwnershipPolicy.claimFor(snapshot, offer, boardHealth())
            .copy(offered = advertisingOffer)
        boardCellManager.publishRelayState(claim)
    }

    /**
     * Stop inviting guests, immediately and without suspending.
     *
     * Both halves of the invitation go together: the advertisement a stranger
     * scans for, and the admission that would accept their write if they got
     * in anyway. Anything that waits belongs after this, not before it.
     */
    private fun withdrawOfferNow() {
        stopAdvertisingOffer()
        relayServer.admitWrite = { false }
    }

    /**
     * The intention this guest write belongs to.
     *
     * Canonical state is consulted first and this device's memory not at all:
     * the record of an open request is replicated precisely so that a
     * controller which has never seen the write can still recognise the retry.
     * Nothing open means this is a new intention, which is exactly what a
     * second guest, or the same guest asking again later, is.
     */
    private fun cellId(): String = boardCellManager.snapshot()?.cellId?.value.orEmpty()

    private fun intentFor(
        fingerprint: String,
        deviceAddress: String,
        nowEpochMs: Long,
    ): RelayInboundGate.Operation {
        val guest = RelayIngressIdentity.guestKey(deviceAddress)
        val connected = relayServer.connectedAddresses()
            .map { RelayIngressIdentity.guestKey(it) }
            .toSet()
        val open = boardCellManager.playlist()?.let {
            RelayIngressIdentity.openIntent(it, fingerprint, guest, nowEpochMs, connected)
        }
        return open ?: RelayIngressIdentity.newIntent(fingerprint, guest, nowEpochMs)
    }

    /**
     * Whether this operation has outlived the answer the guest was given.
     *
     * The deadline is the server's own, carried on the write, so the two cannot
     * drift: the moment the guest has been told "failed", this device stops
     * starting anything new on their behalf — on either routing branch.
     */
    private fun operationExpired(deadlineAtMonotonicMs: Long): Boolean =
        monotonicMs() >= deadlineAtMonotonicMs

    /**
     * The canonical answer arrived: terminal on success, retryable otherwise.
     *
     * The canonical half of "finished" is not published from here. It rides in
     * the same command as the occurrence, so a refused commit leaves neither
     * behind — publishing it separately meant a refusal (or a stop, or a
     * handover) left the record open, and an open record is one a later send by
     * the same guest is put back onto.
     */
    private fun settleOperation(operation: RelayInboundGate.Operation, committed: Boolean) {
        val at = nowMs()
        if (committed) inboundGate.markLanded(operation, at)
        else inboundGate.markFailed(operation, at)
    }

    /**
     * Everything a `ProjectNow` decision does between the decision and the wall.
     *
     * Both transports run it — Aurora's reassembled climbs and MoonBoard's raw
     * stream — because everything in here is about the guest's request and
     * nothing about what the bytes mean: the board-path check, the two deadline
     * fences around the canonical intent barrier, the barrier itself, and the
     * board write through the shared serializer under the operation id decided
     * at ingress. The MoonBoard path used to have none of it.
     *
     * Returns null when the write never reached the wall; the guest has already
     * been answered in that case, and the operation left retryable.
     */
    private suspend fun projectRelayedWrite(
        operation: RelayInboundGate.Operation,
        pendingResponse: Int?,
        deadlineAtMs: Long,
        nowEpochMs: Long,
        chunks: List<ByteArray>,
        projection: BoardProjection?,
    ): ProjectionResult? {
        fun fail(error: RelayError, why: String): ProjectionResult? {
            Log.w(TAG, why)
            _state.update { it.copy(error = error) }
            // Not landed, so the guest's next attempt is a retry of this
            // operation rather than a request the list has never heard of.
            inboundGate.markFailed(operation, nowEpochMs)
            relayServer.settle(pendingResponse, accepted = false)
            return null
        }
        // Without a usable path to the wall there is nothing to deliver, and
        // the guest is told so rather than being handed a success for a write
        // that reached a dark board.
        if (!mayAcknowledgeInboundWrite()) {
            return fail(RelayError.BOARD_LOST, "a relayed write arrived without a usable board path")
        }
        _state.update { it.copy(inboundRefusal = null) }
        if (operationExpired(deadlineAtMs)) {
            return fail(RelayError.FORWARD_FAILED, "a relayed write passed its deadline before the barrier")
        }
        // Before the wall is touched, and *waited for*: a handover in the
        // middle of this write must find the intention already in canonical
        // state, or the successor mints a second one for the retry. A refusal —
        // a revision conflict, a controller that has moved on — means this
        // device has no right to write the wall for this guest yet, so it does
        // not.
        if (!gattBridge.recordRelayIntent(operation)) {
            return fail(RelayError.FORWARD_FAILED, "a relayed write has no canonical intention; not writing the board")
        }
        // The fence. Past the operation's deadline the guest has already been
        // told this failed, so starting a board write now would put something
        // on the wall nobody is waiting for and that their retry would ask for
        // again. A write already under way is a different matter and is never
        // cancelled — half a climb is a state the board protocol has no way to
        // undo.
        if (operationExpired(deadlineAtMs)) {
            return fail(RelayError.FORWARD_FAILED, "a relayed write passed its deadline before the board write")
        }
        val result = boardCellManager.projectExternal(
            boardWrite = { bleConnection.sendRawChunks(chunks) },
            identify = { projection },
            // The operation id decided at ingress. The canonical serializer
            // deduplicates by it, so a repeat after a handover — or a retry
            // after a terminal commit this device could not finish — writes the
            // wall exactly once.
            commandId = operation.operationId,
        )
        if (result !is ProjectionResult.Committed && result !is ProjectionResult.Duplicate) {
            return fail(RelayError.FORWARD_FAILED, "a relayed write was not canonically committed: $result")
        }
        return result
    }

    /**
     * The terminal half for a write that can never become an occurrence.
     *
     * There is no climb to put on the list, so the canonical `landed` record is
     * the whole of what "this request is finished" can mean — and it commits
     * before the guest is told, exactly as the occurrence does for a named
     * write. Recording it only in this device's memory, which is what this did,
     * left a successor after a handover and a restarted process with no reason
     * not to write the wall again for the same retry.
     */
    private suspend fun recordAnonymousDelivery(
        operation: RelayInboundGate.Operation,
        pendingResponse: Int?,
        nowEpochMs: Long,
    ) {
        val recorded = gattBridge.recordRelayIntent(
            operation.copy(stampedAtEpochMs = nowEpochMs, landed = true),
        )
        if (!recorded) {
            Log.w(TAG, "a delivered relay write could not be recorded as landed")
            _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
        }
        settleOperation(operation, recorded)
        settleDelivered(pendingResponse, recorded)
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
        // Which of the two ingress paths owns a guest's write — and therefore
        // owes it an answer. Exactly one of them does; see [rawWritesDecide].
        relayServer.rawWritesDecide = { board.boardBrand == BoardBrand.MOONBOARD }
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
                    // MoonBoard writes cannot be mapped back to a CruxCoach
                    // climb UUID, so they can never become an occurrence — but
                    // they are still a guest's command on somebody else's wall,
                    // and they now get everything that fact implies: a derived
                    // identity that survives a retry and a handover, the gate,
                    // the deadline, the canonical record, and an answer that
                    // waits for the wall instead of preceding it.
                    val now = nowMs()
                    val operation = intentFor(
                        RelayIngressIdentity.anonymousFingerprint(
                            cellId(), RelayIngressIdentity.contentHash(inbound.value),
                        ),
                        inbound.deviceAddress, now,
                    )
                    val decision = inboundGate.evaluate(
                        mode = userPreferences.relayInboundClimbMode.first(),
                        identity = RelayInboundGate.Identity.RAW_STREAM,
                        climbUuid = null,
                        angle = null,
                        climbBrand = null,
                        connectedBrand = bleConnection.connectedBoardBrand.value,
                        nowMs = now,
                        operation = operation,
                        canonicallyLanded = operation.landed,
                    )
                    when (decision) {
                        is RelayInboundGate.Decision.ProjectNow -> {
                            val result = projectRelayedWrite(
                                operation = decision.operation,
                                pendingResponse = inbound.pendingResponse,
                                deadlineAtMs = inbound.deadlineAtMs,
                                nowEpochMs = now,
                                chunks = listOf(inbound.value),
                                projection = null,
                            ) ?: return@collect
                            check(result is ProjectionResult.Committed ||
                                result is ProjectionResult.Duplicate)
                            advertiser.clearActiveClimb()
                            recordAnonymousDelivery(
                                decision.operation, inbound.pendingResponse, now,
                            )
                        }
                        is RelayInboundGate.Decision.AlreadyDelivered -> {
                            // The same bytes again from the same guest, already
                            // on the wall. Replaying the success is the only
                            // answer a retrying guest can act on — and it does
                            // not write the board a second time.
                            Log.i(TAG, "relayed write already delivered; replaying the success")
                            settleDelivered(inbound.pendingResponse, delivered = true)
                        }
                        is RelayInboundGate.Decision.Refused -> {
                            Log.i(TAG, "relayed write refused: ${decision.reason}")
                            _state.update { it.copy(inboundRefusal = decision.reason) }
                            relayServer.settle(inbound.pendingResponse, accepted = false)
                        }
                        is RelayInboundGate.Decision.AppendToEnd -> {
                            // Unreachable: a raw write has no occurrence to
                            // queue, so the gate refuses it under that mode
                            // rather than routing it here. Refusing rather
                            // than falling through keeps that a fact and not
                            // an assumption.
                            Log.w(TAG, "a raw relay write cannot be queued")
                            _state.update { it.copy(inboundRefusal = RelayInboundGate.Refusal.NOT_QUEUEABLE) }
                            relayServer.settle(inbound.pendingResponse, accepted = false)
                        }
                    }
                }
            } else {
                relayServer.climbs.collect { inbound ->
                    // Identify first: what the guest sent decides where it may
                    // go — including whether it may go anywhere at all. A write
                    // whose LEDs are not on this board, or that decodes into
                    // nothing, is refused here rather than being treated as an
                    // ordinary unnamed climb and forwarded.
                    val identity = projectionCoordinator.identifyExternal(inbound.climb)
                    val identified = (identity as? RelayWriteIdentity.Named)
                        ?.let { BoardProjection(it.climbUuid, it.angle) }
                    val climb = identified?.let {
                        runCatching { boardRepository.getClimbByUuid(it.climbUuid, it.angle) }.getOrNull()
                    }
                    val now = nowMs()
                    // The deadline the guest is being timed against, set where
                    // their bytes arrived. Starting a clock here instead —
                    // after the flow hop, the catalogue lookup and the
                    // preference reads — measured a different window from the
                    // one the guest's answer is on, so this device could still
                    // be inside "its" twenty seconds while the guest had
                    // already been told the write failed.
                    val deadlineAt = inbound.deadlineAtMs
                    // One intention, one nonce — and the intention is looked
                    // up in canonical state, not in this device's memory. A
                    // controller that has just taken the board over finds the
                    // guest's open request there and reuses its ids; two
                    // guests sending the same climb do not collide, because
                    // the guest is part of what "the same request" means; and
                    // a genuine re-send later finds nothing open and starts a
                    // new occurrence, which is what it is.
                    // Derived either way. An unnamed climb has only its bytes
                    // to be recognised by, and that is enough: it is the same
                    // request on a retry and the same request to a successor
                    // after a handover, which is exactly what the invented
                    // timestamp ids were not.
                    val operation = when (identity) {
                        is RelayWriteIdentity.Named -> intentFor(
                            RelayIngressIdentity.fingerprint(
                                cellId(), identity.climbUuid, identity.angle,
                                inbound.climb.framesHash,
                            ),
                            inbound.deviceAddress, now,
                        )
                        RelayWriteIdentity.Anonymous -> intentFor(
                            RelayIngressIdentity.anonymousFingerprint(
                                cellId(), inbound.climb.framesHash,
                            ),
                            inbound.deviceAddress, now,
                        )
                        RelayWriteIdentity.ForeignBoard, RelayWriteIdentity.Undecidable -> null
                    }
                    val decision = inboundGate.evaluate(
                        mode = userPreferences.relayInboundClimbMode.first(),
                        identity = when (identity) {
                            is RelayWriteIdentity.Named -> RelayInboundGate.Identity.NAMED
                            RelayWriteIdentity.Anonymous -> RelayInboundGate.Identity.ANONYMOUS
                            RelayWriteIdentity.ForeignBoard ->
                                RelayInboundGate.Identity.FOREIGN_BOARD
                            RelayWriteIdentity.Undecidable ->
                                RelayInboundGate.Identity.UNREADABLE
                        },
                        climbUuid = identified?.climbUuid,
                        angle = identified?.angle,
                        climbBrand = climb?.let { BoardBrand.fromWire(it.boardBrand) },
                        connectedBrand = bleConnection.connectedBoardBrand.value,
                        nowMs = now,
                        operation = operation,
                        climbLayoutId = climb?.layoutId,
                        connectedLayoutId = userPreferences.boardLayoutId.first().toLong(),
                        connectedAngle = userPreferences.boardAngle.first(),
                        // The record itself says whether this request was
                        // finished, and that is the whole of it. Deriving it
                        // from the wall — "is this occurrence still the current
                        // one, and is the projection still exactly it" — asked
                        // a different question: the group legitimately moves on,
                        // and under `APPEND_TO_END` the occurrence is never the
                        // current one at all, so the success path did not exist
                        // there. A successor or a restarted process reads the
                        // flag and replays the success.
                        canonicallyLanded = operation?.landed == true,
                    )
                    when (decision) {
                        is RelayInboundGate.Decision.Refused -> {
                            Log.i(TAG, "relayed climb refused: ${decision.reason}")
                            _state.update { it.copy(inboundRefusal = decision.reason) }
                            // A refusal the guest's app can see: board, layout
                            // or angle against the wrong board, or too fast.
                            relayServer.settle(inbound.pendingResponse, accepted = false)
                        }
                        is RelayInboundGate.Decision.AlreadyDelivered -> {
                            // Their climb is on the wall — this is the same
                            // request arriving again because the first answer
                            // did not get back to them. Nothing to write and
                            // nothing to add; the honest reply is success, and
                            // it is the only one a retrying guest can act on.
                            Log.i(TAG, "relayed climb already delivered; replaying the success")
                            _state.update { it.copy(inboundRefusal = null) }
                            // Fenced like any other delivery ACK. Their climb is
                            // canonically on the wall, but during grace this
                            // device cannot see the wall to say so — and the
                            // record keeps the fact, so the next retry after
                            // recovery replays it.
                            settleDelivered(inbound.pendingResponse, delivered = true)
                        }
                        is RelayInboundGate.Decision.AppendToEnd -> {
                            // Fenced like the projection is: the guest may
                            // already have timed out while the barrier below
                            // was being committed, and adding an occurrence for
                            // somebody who has been told their write failed is
                            // the same lie in a quieter place.
                            if (operationExpired(deadlineAt)) {
                                Log.w(TAG, "relayed climb passed its deadline before queueing")
                                _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                                inboundGate.markFailed(decision.operation, now)
                                relayServer.settle(inbound.pendingResponse, accepted = false)
                                return@collect
                            }
                            // The barrier, before anything else happens.
                            if (!gattBridge.recordRelayIntent(decision.operation)) {
                                Log.w(TAG, "relayed climb has no canonical intention; not queueing")
                                _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                                inboundGate.markFailed(decision.operation, now)
                                relayServer.settle(inbound.pendingResponse, accepted = false)
                                return@collect
                            }
                            // The wall keeps what it has; the climb joins the
                            // end of the list like any other add — under the
                            // occurrence id decided at ingress, so a repeat of
                            // the same write finds it already there.
                            _state.update { it.copy(inboundRefusal = null) }
                            // And again after it: the barrier suspends, so
                            // the window can close while it is in flight.
                            if (operationExpired(deadlineAt)) {
                                Log.w(TAG, "relayed climb passed its deadline during the barrier")
                                _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                                inboundGate.markFailed(decision.operation, now)
                                relayServer.settle(inbound.pendingResponse, accepted = false)
                                return@collect
                            }
                            // Terminal only when the controller says so. The
                            // submit is asynchronous, and marking it landed
                            // here — which is what this did — turned a refused
                            // playlist commit into a duplicate that could
                            // never be retried.
                            gattBridge.appendSharedPlaylistEntry(
                                identified!!.climbUuid, identified.angle, "relay_append",
                                entryId = decision.operation.entryId,
                                // One command: the occurrence and "this request
                                // is finished" commit together or not at all.
                                completing = decision.operation.copy(stampedAtEpochMs = now),
                                onTerminal = { committed ->
                                    settleOperation(decision.operation, committed)
                                    // Queued means queued — but the controller
                                    // answers this callback whenever it gets
                                    // round to it, and the link can have gone
                                    // in the meantime. The occurrence stays on
                                    // the list either way; only the ACK waits
                                    // for a device that can still speak for
                                    // this board.
                                    settleDelivered(inbound.pendingResponse, committed)
                                },
                            )
                        }
                        is RelayInboundGate.Decision.ProjectNow -> {
                            val result = projectRelayedWrite(
                                operation = decision.operation,
                                pendingResponse = inbound.pendingResponse,
                                deadlineAtMs = deadlineAt,
                                nowEpochMs = now,
                                chunks = inbound.climb.chunks,
                                projection = identified,
                            ) ?: return@collect
                            advertiser.clearActiveClimb()
                            // The guest is told after the occurrence and "this
                            // request is finished" have committed together, not
                            // after the bytes reached the wall. Answering
                            // earlier produced the one combination nobody can
                            // recover from: the board written, the guest told
                            // success, and a canonical playlist with no
                            // occurrence and no `landed`, so nobody has any
                            // reason to retry. "Both or neither" has to include
                            // the answer.
                            //
                            // A refusal, a stop or a handover therefore answers
                            // negative, and the ids stay retryable: the retry
                            // finds the same operation, the canonical
                            // serializer refuses to write the wall twice for
                            // it, and the list gains exactly one occurrence.
                            if (identified != null) {
                                gattBridge.adoptProjectedEntry(
                                    identified.climbUuid, identified.angle, "relay_project",
                                    entryId = decision.operation.entryId,
                                    completing = decision.operation.copy(stampedAtEpochMs = now),
                                    onTerminal = { committed ->
                                        settleOperation(decision.operation, committed)
                                        settleDelivered(inbound.pendingResponse, committed)
                                    },
                                )
                            } else {
                                recordAnonymousDelivery(
                                    decision.operation, inbound.pendingResponse, now,
                                )
                            }
                            identifyJob?.cancel()
                            identifyJob = scope.launch {
                                val projection = if (result is ProjectionResult.Committed) {
                                    (result.envelope.event as? BoardCellEvent.ProjectCommitted)?.projection
                                } else boardCellManager.snapshot()?.projection
                                projectionCoordinator.onCanonicalExternalBoardWrite(projection)
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
        admissionOpen = false
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
