package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.QuantumActivePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SENDING
}

enum class QuantumCommandFailure {
    ROUTE_IN_USE, SPOT_UNAVAILABLE, COLOR_TAKEN, USER_ID_IN_USE,
    BOARD_FULL, ROUTESETTER_MODE, DIODE_MISSING, ACK_TIMEOUT, REFUSED,
}

data class QuantumControllerState(
    val players: List<QuantumActivePlayer> = emptyList(),
    val revision: Long = 0,
    /** Revision of the last complete controller snapshot. Delta acknowledgements
     * increment [revision] but must not satisfy a fresh-read precondition. */
    val authoritativeRevision: Long = 0,
    /** Revision of the last explicit REQUEST_USER_ROUTE_LIST response. fff4
     * reads and activation broadcasts can be complete snapshots, but cannot
     * confirm the freshness of a mutation precondition. */
    val routeListRevision: Long = 0,
    val lastFailure: QuantumCommandFailure? = null,
    /** True only after a controller broadcast supplied real state. */
    val authoritative: Boolean = false,
)

private data class QuantumGattRead(
    val status: Int,
    val value: ByteArray?,
)

private data class PendingGattWrite(
    val gatt: BluetoothGatt,
    val characteristic: BluetoothGattCharacteristic,
    val result: CompletableDeferred<Int>,
)

internal enum class QuantumControllerEvidence {
    AUTHORITATIVE,
    DELTA,
    FAILURE,
    INFORMATIONAL,
    UNSUPPORTED,
}

internal fun classifyQuantumControllerEvidence(
    broadcast: QuantumBroadcast?,
): QuantumControllerEvidence = when (broadcast) {
    is QuantumBroadcast.RouteList, QuantumBroadcast.BoardCleared ->
        QuantumControllerEvidence.AUTHORITATIVE
    is QuantumBroadcast.UserTurnedOff -> QuantumControllerEvidence.DELTA
    is QuantumBroadcast.Exception -> QuantumControllerEvidence.FAILURE
    is QuantumBroadcast.BoardLit -> QuantumControllerEvidence.INFORMATIONAL
    null -> QuantumControllerEvidence.UNSUPPORTED
}

internal fun hasFreshQuantumSnapshot(
    before: QuantumControllerState,
    after: QuantumControllerState,
): Boolean = after.lastFailure == null &&
    after.authoritative &&
    after.authoritativeRevision > before.authoritativeRevision

internal fun hasFreshExplicitQuantumRouteList(
    before: QuantumControllerState,
    after: QuantumControllerState,
): Boolean = after.lastFailure == null && after.authoritative &&
    after.routeListRevision > before.routeListRevision

internal fun quantumReadRequiresRouteListFallback(
    evidence: QuantumControllerEvidence,
): Boolean = evidence != QuantumControllerEvidence.AUTHORITATIVE

internal fun quantumNotificationSetupConfirmed(
    localNotificationsEnabled: Boolean,
    descriptorStatus: Int?,
): Boolean = localNotificationsEnabled && descriptorStatus == BluetoothGatt.GATT_SUCCESS

/** One service-discovery completion may advance a GATT attempt. Some Android
 * stacks deliver the callback after our populated-services fallback has fired;
 * others deliver the callback first and leave the fallback runnable queued. */
internal fun serviceDiscoveryCompletionAllowed(
    connecting: Boolean,
    currentGattMatches: Boolean,
    gattClosed: Boolean,
    alreadyHandled: Boolean,
): Boolean = connecting && currentGattMatches && !gattClosed && !alreadyHandled

/** Vendor GATT implementations may throw instead of returning false when a
 * permission changes or their operation queue is broken. Setup has already
 * retired the overall connect timeout, so every such exception must become a
 * normal setup failure rather than strand CONNECTING forever. */
internal suspend fun quantumGattSetupSucceeded(
    block: suspend () -> Boolean,
): Boolean = quantumGattOperationSucceeded(block)

/** Runtime refreshes use the same exception boundary as setup. Android vendor
 * stacks can throw after a permission change instead of reporting a callback
 * failure; cancellation remains structured and is never converted to false. */
internal suspend fun quantumGattOperationSucceeded(
    block: suspend () -> Boolean,
): Boolean = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

internal enum class GattConnectionCallbackRole { CURRENT, RETIRING_DISCONNECT, STALE }

internal enum class GattDisconnectRequestRole { RETIRE_ACTIVE, PRESERVE_RETIRING, NO_GATT }

internal fun classifyGattDisconnectRequest(
    activeGattPresent: Boolean,
    retiringGattPresent: Boolean,
): GattDisconnectRequestRole = when {
    activeGattPresent -> GattDisconnectRequestRole.RETIRE_ACTIVE
    retiringGattPresent -> GattDisconnectRequestRole.PRESERVE_RETIRING
    else -> GattDisconnectRequestRole.NO_GATT
}

internal fun classifyGattConnectionCallback(
    currentGattMatches: Boolean,
    retiringGattMatches: Boolean,
    newState: Int,
): GattConnectionCallbackRole = when {
    currentGattMatches -> GattConnectionCallbackRole.CURRENT
    retiringGattMatches && newState == BluetoothProfile.STATE_DISCONNECTED ->
        GattConnectionCallbackRole.RETIRING_DISCONNECT
    else -> GattConnectionCallbackRole.STALE
}

internal data class QuantumControllerMetadata(
    val model: QuantumBoardModel,
    val columns: Int,
    val rows: Int,
)

/** Strict decoder for eWalls' 41-byte fff5 controller record. The controller
 * type byte and dimensions must agree before they can fence model-scoped
 * writes; unknown firmware records deliberately leave the link non-writable. */
internal fun parseQuantumControllerMetadata(bytes: ByteArray): QuantumControllerMetadata? {
    if (bytes.size != 41) return null
    val model = when (bytes[34].toInt() and 0xff) {
        0 -> QuantumBoardModel.XL
        1 -> QuantumBoardModel.M
        2 -> QuantumBoardModel.S
        3 -> QuantumBoardModel.BELAY
        4 -> QuantumBoardModel.L
        else -> return null
    }
    val columns = ((bytes[35].toInt() and 0xff) shl 8) or (bytes[36].toInt() and 0xff)
    val rows = ((bytes[37].toInt() and 0xff) shl 8) or (bytes[38].toInt() and 0xff)
    if (columns != model.columns || rows != model.rows) return null
    return QuantumControllerMetadata(model, columns, rows)
}

internal fun isScopedQuantumUserId(
    userId: String,
    isOwnedByInstallation: (String) -> Boolean,
): Boolean = !userId.equals(QuantumBoardPacketEncoder.ZERO_UUID, ignoreCase = true) &&
    isOwnedByInstallation(userId)

internal const val QUANTUM_NOTIFICATION_NEED_MORE = 0
internal const val QUANTUM_NOTIFICATION_INVALID = -1

/** Distinguish an incomplete supported broadcast from a prefix which can never
 * become one. Quantum broadcasts have no CRC or delimiter, so invalid input is
 * discarded a byte at a time until the next 0x01 frame marker. */
internal fun quantumNotificationFrameSize(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return QUANTUM_NOTIFICATION_NEED_MORE
    if ((bytes[0].toInt() and 0xff) != 1) return QUANTUM_NOTIFICATION_INVALID
    if (bytes.size < 2) return QUANTUM_NOTIFICATION_NEED_MORE
    val command = bytes[1].toInt() and 0xff
    if (command and 0x80 != 0) return 3
    return when (command) {
        0x41, 0x44, 0x47 -> {
            if (bytes.size < 4) QUANTUM_NOTIFICATION_NEED_MORE
            else {
                val players = bytes[2].toInt() and 0xff
                if (players > BoardLayerManager.MAX_LAYER_IDENTITIES || bytes[3].toInt() != 0) {
                    QUANTUM_NOTIFICATION_INVALID
                }
                else 4 + players * QuantumBoardBroadcastParser.PLAYER_BYTES
            }
        }
        0x43 -> 21
        0x45 -> 6
        0x64 -> 3
        else -> QUANTUM_NOTIFICATION_INVALID
    }
}

internal data class RecoveredQuantumNotification(
    val bytes: ByteArray,
    /** True when the frame used bytes from more than one callback. With no
     * delimiter or CRC this is authoritative only inside a freshly reset,
     * explicit route-list request generation. */
    val crossedCallbackBoundary: Boolean,
)

/** Stateful fff1 decoder with an explicit generation reset. A pending prefix
 * can never make later callback bytes authoritative by itself: every frame
 * recovered from that callback is marked cross-boundary, including leftovers
 * after a fabricated first length. */
internal class QuantumNotificationAccumulator {
    private val buffer = mutableListOf<Byte>()

    fun reset() = synchronized(buffer) { buffer.clear() }

    fun consume(bytes: ByteArray): List<RecoveredQuantumNotification> = synchronized(buffer) {
        val crossedBoundary = buffer.isNotEmpty()
        buffer += bytes.toList()
        buildList {
            while (buffer.isNotEmpty()) {
                if ((buffer.first().toInt() and 0xff) != 1) {
                    buffer.removeAt(0)
                    continue
                }
                val candidate = buffer.toByteArray()
                val expected = quantumNotificationFrameSize(candidate)
                if (expected == QUANTUM_NOTIFICATION_NEED_MORE) break
                if (expected == QUANTUM_NOTIFICATION_INVALID) {
                    buffer.removeAt(0)
                    continue
                }
                if (candidate.size < expected) break
                add(
                    RecoveredQuantumNotification(
                        bytes = candidate.copyOf(expected),
                        crossedCallbackBoundary = crossedBoundary,
                    ),
                )
                repeat(expected) { buffer.removeAt(0) }
            }
        }
    }
}

internal fun quantumPlayersMatch(
    expected: List<QuantumActivePlayer>,
    actual: List<QuantumActivePlayer>,
): Boolean = expected.map {
    // remainingSeconds naturally ticks between the two reads and is not an
    // occupancy/conflict dimension. Route, owner and colour are the mutation
    // guard.
    listOf(it.routeId.lowercase(), it.userId.lowercase(), (it.color and 0xffffff).toString())
}.sortedBy { it.joinToString("|") } == actual.map {
    listOf(it.routeId.lowercase(), it.userId.lowercase(), (it.color and 0xffffff).toString())
}.sortedBy { it.joinToString("|") }

internal fun hasCompleteQuantumLedMapping(
    holds: List<BoardHold>,
    placementToLed: Map<Int, Int>,
): Boolean = holds.isNotEmpty() && holds.all { hold ->
    placementToLed[hold.placementId]?.let { it in 0..0xffff } == true
}

internal fun hasConfirmableQuantumDiodeCount(holds: List<BoardHold>): Boolean =
    holds.size <= QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT

internal fun boardScopedCommandAllowed(
    connectedBrand: BoardBrand?,
    expectedBrand: BoardBrand?,
): Boolean = expectedBrand == null || connectedBrand == expectedBrand

internal fun quantumBoardWriteFenceMatches(
    connectedBoard: DiscoveredBoard?,
    connectedModel: QuantumBoardModel?,
    expectedBoard: BoardLayerBoardIdentity?,
): Boolean {
    if (connectedBoard?.boardBrand != BoardBrand.QUANTUM || expectedBoard == null) return false
    val expectedModel = QuantumBoardModel.fromProductSizeId(expectedBoard.productSizeId)
        ?: return false
    if (connectedModel != expectedModel) return false
    val physical = runCatching { PhysicalBoardIdentity.resolve(connectedBoard) }.getOrNull()
        ?: return false
    return physical.value == expectedBoard.physicalBoardId
}

/** fff4 can expose the controller's cached last event. A complete route list
 * is usable as an observational snapshot, but a cached TURN_OFF_ALL event is
 * not proof that the shared wall is currently empty. */
internal fun classifyQuantumFff4Evidence(
    broadcast: QuantumBroadcast?,
): QuantumControllerEvidence = when (broadcast) {
    QuantumBroadcast.BoardCleared -> QuantumControllerEvidence.INFORMATIONAL
    else -> classifyQuantumControllerEvidence(broadcast)
}

internal fun quantumFff4PublishesSnapshot(broadcast: QuantumBroadcast?): Boolean =
    broadcast is QuantumBroadcast.RouteList

/** A read from fff4 immediately following our serialized route-list request is
 * the controller's response on deployed Quantum XL firmware. Keep the command
 * check: a cached ACTIVATE_WALL/BOARD_SWIPE snapshot is useful observation but
 * must not confirm the explicit mutation fence. */
internal fun quantumFff4ConfirmsExplicitRouteList(broadcast: QuantumBroadcast?): Boolean =
    broadcast is QuantumBroadcast.RouteList &&
        broadcast.command == com.cruxcoach.domain.board.QuantumCommand.REQUEST_USER_ROUTE_LIST

internal fun genericBoardClearAllowed(connectedBrand: BoardBrand?): Boolean =
    connectedBrand != BoardBrand.QUANTUM

internal fun moonBoardCommandAllowed(connectedBrand: BoardBrand?): Boolean =
    connectedBrand == BoardBrand.MOONBOARD

internal fun quantumRefreshFailureRequiresDisconnect(
    currentGattMatches: Boolean,
    connectedBrand: BoardBrand?,
): Boolean = currentGattMatches && connectedBrand == BoardBrand.QUANTUM

internal fun isQuantumProjectionConfirmed(
    state: QuantumControllerState,
    playersBefore: List<QuantumActivePlayer>,
    routeId: String,
    userId: String,
    color: Int,
): Boolean {
    if (!state.authoritative || state.lastFailure != null) return false
    val nonTargetBefore = playersBefore.filterNot { it.userId.equals(userId, ignoreCase = true) }
    val nonTargetAfter = state.players.filterNot { it.userId.equals(userId, ignoreCase = true) }
    return quantumPlayersMatch(nonTargetBefore, nonTargetAfter) && state.players.any {
        it.userId.equals(userId, ignoreCase = true) &&
            it.routeId.equals(routeId, ignoreCase = true) &&
            it.color == (color and 0xffffff)
    }
}

internal fun isQuantumScopedRemovalConfirmed(
    state: QuantumControllerState,
    playersBefore: List<QuantumActivePlayer>,
    userId: String,
): Boolean {
    if (!state.authoritative || state.lastFailure != null) return false
    val nonTargetBefore = playersBefore.filterNot { it.userId.equals(userId, ignoreCase = true) }
    return state.players.none { it.userId.equals(userId, ignoreCase = true) } &&
        quantumPlayersMatch(nonTargetBefore, state.players)
}

/**
 * Manages GATT connection to an Aurora Climbing board and sends hold/clear packets.
 *
 * Write flow control: waits for onCharacteristicWrite callback before sending
 * the next chunk, preventing BLE write queue overflow on Android 9.
 *
 * GATT lifecycle on Android <12 (research-backed):
 *  1. connectGatt() MUST be called from Main-Thread (callback delivery depends on caller's Looper)
 *  2. disconnect() → wait for STATE_DISCONNECTED callback → delay 300ms → refresh() → close() → null
 *  3. Wait 1000ms before next connectGatt() (GATT slot release is async on Android 9-11)
 *  4. Stop BLE scanners 500ms before connectGatt() (shared radio contention)
 *  5. Always use TRANSPORT_LE, never TRANSPORT_AUTO
 */
class BoardBleConnection(
    private val context: Context,
    private val ownsQuantumUserId: (String) -> Boolean = { false },
) {

    private companion object {
        const val TAG = "BoardBleConnection"
        const val WRITE_TIMEOUT_MS = 5000L
        const val QUANTUM_CONFIRM_TIMEOUT_MS = 3000L
        const val QUANTUM_REFRESH_INTERVAL_MS = 10_000L
        const val CLOSE_SAFETY_TIMEOUT_MS = 5000L

        // Per-attempt connect budget × silent retries. Legacy stacks (9-11)
        // routinely fail a first direct connect with a transient status 133;
        // retrying quietly beats surfacing every radio hiccup as a silent
        // drop the user must re-tap through. Worst case ≈ 3 × (10 s + 0.6 s),
        // close to the old single 30 s window — but a transient failure now
        // recovers unattended in seconds.
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 10_000L
        const val MAX_CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 600L

        // Timing delays for Android <12 BLE stack quirks
        const val DELAY_CLOSE_AFTER_DISCONNECT_MS = 300L
        const val DELAY_RECONNECT_LEGACY_MS = 1000L
        const val DELAY_RECONNECT_MODERN_MS = 200L
        const val DELAY_SCAN_SETTLE_MS = 500L
        const val DELAY_PRE_DISCOVERY_LEGACY_MS = 300L
        const val QUANTUM_MTU_TIMEOUT_MS = 3_000L
        const val SERVICE_DISCOVERY_CALLBACK_FALLBACK_MS = 3_500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedBoardName = MutableStateFlow<String?>(null)
    val connectedBoardName: StateFlow<String?> = _connectedBoardName.asStateFlow()

    // Brand of the board this GATT actually belongs to (from the
    // DiscoveredBoard passed to connect()). Send paths must guard against
    // THIS, not the active-board pref: switching the active board in
    // Settings never disconnects, so the pref can diverge from the board
    // that is still on the other end of the link. Null while disconnected.
    private val _connectedBoardBrand = MutableStateFlow<BoardBrand?>(null)
    val connectedBoardBrand: StateFlow<BoardBrand?> = _connectedBoardBrand.asStateFlow()

    /** Model proven by the connected controller's fff5 metadata. Null means
     * model-scoped Quantum writes must remain fenced off. */
    private val _connectedQuantumModel = MutableStateFlow<QuantumBoardModel?>(null)
    val connectedQuantumModel: StateFlow<QuantumBoardModel?> =
        _connectedQuantumModel.asStateFlow()

    // Localized reason (string-res id) why the last connect attempt was torn
    // down at service discovery — currently only the unsupported RedBear-UART
    // MoonBoard LED-kit generation. Survives the disconnect (so the sheet can
    // show it on the scan list the user lands back on) and is cleared on the
    // next connect attempt. Null = no known failure reason.
    private val _connectFailureReason = MutableStateFlow<Int?>(null)
    val connectFailureReason: StateFlow<Int?> = _connectFailureReason.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var serviceDiscoveryGatt: BluetoothGatt? = null
    private var serviceDiscoveryHandledGatt: BluetoothGatt? = null
    private var quantumMtuGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var encoder: BoardPacketEncoder = BoardPacketEncoder(3)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var disconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var closeSafetyJob: Job? = null
    private var connectJob: Job? = null
    private var quantumSetupJob: Job? = null
    private var quantumRefreshJob: Job? = null

    // Silent-retry bookkeeping for the in-flight connect: the board being
    // connected and which attempt (1-based) is currently running. State stays
    // CONNECTING across quiet retries so the UI shows one continuous attempt.
    private var currentBoard: DiscoveredBoard? = null
    /** Full scan descriptor for features that must preserve the controller API level. */
    val connectedBoard: DiscoveredBoard?
        get() = _connectedBoardDescriptor.value
    private val _connectedBoardDescriptor = MutableStateFlow<DiscoveredBoard?>(null)
    val connectedBoardDescriptor: StateFlow<DiscoveredBoard?> =
        _connectedBoardDescriptor.asStateFlow()
    private var connectAttempt = 0
    /** Retry budget of the in-flight connect; see [connect]. */
    private var attemptBudget = MAX_CONNECT_ATTEMPTS

    var autoDisconnectSeconds: Int = 0
        set(value) {
            field = value
            // Settings can change while GATT is already idle. Re-evaluate the
            // live timer immediately instead of waiting for the next write or
            // connection-state callback.
            resetIdleTimer()
        }
    // FEAT-044 coexistence: several features (a shared session, the CruxRelay)
    // can independently keep the board link parked. The idle timer is suppressed
    // while ANY owner holds it; auto-disconnect fires only once ALL have
    // released. Set semantics = idempotent per owner, so a feature may acquire
    // more than once (e.g. startQueue + promoteToHost) and release once.
    private val keepAliveOwners = mutableSetOf<String>()
    private val keepAliveLock = Any()
    private val _keepAliveActive = MutableStateFlow(false)
    /** True while a session or the relay owns the physical board connection. */
    val keepAliveActive: StateFlow<Boolean> = _keepAliveActive.asStateFlow()
    /** Diagnostic truth: whether the current idle countdown can disconnect GATT. */
    val idleDisconnectArmed: Boolean get() = disconnectJob?.isActive == true

    fun acquireKeepAlive(owner: String) {
        val owners = synchronized(keepAliveLock) {
            keepAliveOwners.add(owner)
            _keepAliveActive.value = keepAliveOwners.isNotEmpty()
            keepAliveOwners.toList()
        }
        Log.d(TAG, "keepAlive acquired by $owner — holders=$owners")
        // A timer armed BEFORE the acquire keeps running otherwise: sharing
        // started 3 s before a 60 s idle timer expired dropped the board out
        // from under the relay. Suppression has to cancel what is pending,
        // not just skip the next arming.
        resetIdleTimer()
    }

    fun releaseKeepAlive(owner: String) {
        val owners = synchronized(keepAliveLock) {
            keepAliveOwners.remove(owner)
            _keepAliveActive.value = keepAliveOwners.isNotEmpty()
            keepAliveOwners.toList()
        }
        Log.d(TAG, "keepAlive released by $owner — holders=$owners")
        if (owners.isEmpty()) resetIdleTimer() // last owner let go → allow idle-disconnect again
    }

    /** True when disconnecting for [owner] would interrupt another feature. */
    fun hasOtherKeepAliveOwners(owner: String): Boolean =
        synchronized(keepAliveLock) { keepAliveOwners.any { it != owner } }

    private fun isKeepAliveHeld(): Boolean =
        synchronized(keepAliveLock) { keepAliveOwners.isNotEmpty() }

    /**
     * Records what a completed advertising probe saw while we hold GATT.
     *
     * [advertises] true means the controller was seen advertising connectably
     * — a peripheral is reachable exactly while it advertises, so that settles
     * it. False is only ever passed for a scan that ran to the end and saw
     * nothing, which is the one thing that can correct a stale "accepts
     * several"; an inconclusive scan does not call this at all.
     *
     * Both directions land on the live descriptor, not just in storage. The
     * downgrade used to be written to preferences only, so for the rest of
     * that connection the app still treated a controller it had just proven
     * exclusive as shared: the "share this board" control stayed hidden,
     * idle release stayed suppressed, and the correction took effect only on
     * the next connect.
     */
    fun recordAdvertisingWhileConnected(address: String, advertises: Boolean = true) {
        if (_connectionState.value != ConnectionState.CONNECTED &&
            _connectionState.value != ConnectionState.SENDING
        ) return
        val board = currentBoard?.takeIf { it.address.equals(address, ignoreCase = true) } ?: return
        if (board.advertisesWhileConnected == advertises) return
        val updated = board.copy(advertisesWhileConnected = advertises)
        currentBoard = updated
        _connectedBoardDescriptor.value = updated
        Log.i(
            TAG,
            if (advertises) "Controller advertises while connected — accepts more clients"
            else "Controller did not advertise on a completed scan — exclusive",
        )
        // Capacity just changed; idle release only applies to an exclusive board.
        resetIdleTimer()
    }

    // Write flow control: signaled by onCharacteristicWrite callback.
    // @Volatile: callback may arrive on a GATT-stack Binder thread on some
    // Android versions, so writer/reader visibility must be guaranteed.
    @Volatile
    private var pendingWrite: PendingGattWrite? = null
    @Volatile
    private var descriptorDeferred: CompletableDeferred<Int>? = null
    @Volatile
    private var quantumReadDeferred: CompletableDeferred<QuantumGattRead>? = null
    @Volatile
    private var quantumMetadataReadDeferred: CompletableDeferred<QuantumGattRead>? = null
    /** Some deployed Quantum controllers expose fff4 in their GATT table but
     * never answer reads. Once that is observed for the current GATT, skip the
     * optional characteristic and use the explicit route-list command. This
     * also prevents a late, tokenless read callback from being mistaken for a
     * later fff4 read without throwing away an otherwise writable link. */
    @Volatile
    private var quantumStateReadUsable = true
    private val writeMutex = Mutex()

    private val _quantumControllerState = MutableStateFlow(QuantumControllerState())
    val quantumControllerState: StateFlow<QuantumControllerState> =
        _quantumControllerState.asStateFlow()
    private val quantumNotificationAccumulator = QuantumNotificationAccumulator()
    @Volatile
    private var quantumRouteListRequestActive = false

    // Track whether disconnect() was called by us (vs. remote disconnect).
    @Volatile
    private var userDisconnecting = false

    // Identity-scoped close fence. A delayed callback for an older retry must
    // never consume the close flag belonging to a newer GATT attempt.
    @Volatile
    private var closedGatt: BluetoothGatt? = null

    // disconnect() clears [gatt] immediately so callers see DISCONNECTED, but
    // the old object still needs its eventual callback/close without being
    // mistaken for a later retry attempt.
    @Volatile
    private var userDisconnectGatt: BluetoothGatt? = null

    private fun isGattClosed(candidate: BluetoothGatt): Boolean = closedGatt === candidate

    // Signals when GATT close() completes. On Android <12, the BLE stack releases
    // client slots asynchronously — reconnecting before close() finishes causes
    // slot exhaustion and permanent connection failure.
    private var pendingClose: CompletableDeferred<Unit>? = null

    // Callback to stop external scanners before GATT connect.
    // Set by the caller (e.g. BleConnectionViewModel) to pause NearbyClimbScanner.
    var onStopScannersForConnect: (() -> Unit)? = null
    var onRestartScannersAfterConnect: (() -> Unit)? = null

    // Remember last sent climb for live color preview
    private var lastHolds: List<BoardHold>? = null
    private var lastPlacementToLed: Map<Int, Int>? = null
    private var lastSendBoardAddress: String? = null
    private var lastSendBoardBrand: BoardBrand? = null

    private fun cancelQuantumGattOperations() {
        quantumSetupJob?.cancel()
        quantumSetupJob = null
        quantumRefreshJob?.cancel()
        quantumRefreshJob = null
        descriptorDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        descriptorDeferred = null
        quantumReadDeferred?.complete(QuantumGattRead(BluetoothGatt.GATT_FAILURE, null))
        quantumReadDeferred = null
        quantumMetadataReadDeferred?.complete(QuantumGattRead(BluetoothGatt.GATT_FAILURE, null))
        quantumMetadataReadDeferred = null
        quantumRouteListRequestActive = false
        quantumNotificationAccumulator.reset()
    }

    private fun resetIdleTimer() {
        disconnectJob?.cancel()
        val seconds = autoDisconnectSeconds
        // Only arm the timer while the connection is truly idle. SENDING
        // is "writes in flight" — the send path re-arms us from its
        // finally block once it flips state back to CONNECTED. Without
        // this guard, a small autoDisconnectSeconds (e.g. 1 s, used as
        // a replacement for the old Quick-Send macro) could fire mid-
        // send on long climbs.
        val profile = BoardControllerProfiles.forBoard(currentBoard)
        val suppressed = isKeepAliveHeld()
        val arm = BoardProjectionPolicy.shouldArmIdleDisconnect(
            seconds = seconds,
            connectionState = _connectionState.value,
            explicitlySuppressed = suppressed,
            connectionCapacity = profile.connectionCapacity,
            projectionSurvivesDisconnect =
                profile.projectionLifetime == BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
        )
        // All five inputs, because a wrong auto-disconnect is otherwise
        // indistinguishable from a link the board dropped on its own.
        Log.d(
            TAG,
            "idleTimer arm=$arm seconds=$seconds state=${_connectionState.value} " +
                "suppressed=$suppressed capacity=${profile.connectionCapacity} " +
                "projectionSurvives=${profile.projectionLifetime}"
        )
        if (arm) {
            disconnectJob = scope.launch {
                delay(seconds * 1_000L)
                disconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(currentGatt: BluetoothGatt) {
        if (_connectionState.value != ConnectionState.CONNECTING ||
            gatt !== currentGatt || isGattClosed(currentGatt) || serviceDiscoveryGatt === currentGatt
        ) return
        serviceDiscoveryGatt = currentGatt
        val queued = currentGatt.discoverServices()
        Log.d(TAG, "discoverServices queued=$queued")
        if (!queued) {
            serviceDiscoveryGatt = null
            return
        }
        // Some vendor stacks populate BluetoothGatt.services but omit the app
        // callback. Use the already-discovered required characteristic as a
        // conservative completion signal; never accept a partial service list.
        mainHandler.postDelayed({
            if (!serviceDiscoveryCompletionAllowed(
                    connecting = _connectionState.value == ConnectionState.CONNECTING,
                    currentGattMatches = gatt === currentGatt,
                    gattClosed = isGattClosed(currentGatt),
                    alreadyHandled = serviceDiscoveryHandledGatt === currentGatt,
                )
            ) return@postDelayed
            val expectedWrite = if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                (currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE)
                    ?: currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD))
                    ?.getCharacteristic(BoardBleUuids.QUANTUM_WRITE_CHAR)
            } else {
                currentGatt.getService(BoardBleUuids.DATA_TRANSFER_SERVICE)
                    ?.getCharacteristic(BoardBleUuids.DATA_TRANSFER_CHAR)
            }
            if (expectedWrite != null) {
                Log.w(TAG, "service discovery callback missing; completing from populated GATT services")
                gattCallback.onServicesDiscovered(currentGatt, BluetoothGatt.GATT_SUCCESS)
            }
        }, SERVICE_DISCOVERY_CALLBACK_FALLBACK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun finishGattSetup(currentGatt: BluetoothGatt) {
        if (_connectionState.value != ConnectionState.CONNECTING ||
            gatt !== currentGatt || isGattClosed(currentGatt) || writeCharacteristic == null
        ) return
        quantumMtuGatt = null
        if (_connectedBoardBrand.value != BoardBrand.QUANTUM) {
            markGattReady(currentGatt)
            return
        }
        // MTU negotiation has completed (or reached its guarded timeout).
        // Notification setup below owns its own bounded descriptor wait.
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null

        // Android GATT permits one outstanding operation. Enabling the local
        // notification route and queueing its CCCD write is not completion:
        // writing fff2 before onDescriptorWrite is a race which loses either
        // the subscription or the first route-list request on real stacks.
        quantumSetupJob?.cancel()
        quantumSetupJob = scope.launch {
            val ready = quantumGattSetupSucceeded {
                // Android GATT permits one outstanding operation. Read and
                // verify the model before queueing the CCCD write, then expose
                // the link as writable only after both operations complete.
                val metadata = readQuantumMetadata(currentGatt) ?: return@quantumGattSetupSucceeded false
                if (gatt !== currentGatt || isGattClosed(currentGatt)) {
                    return@quantumGattSetupSucceeded false
                }
                _connectedQuantumModel.value = metadata.model
                val notificationsReady = enableQuantumNotifications(currentGatt)
                notificationsReady && gatt === currentGatt && !isGattClosed(currentGatt)
            }
            if (!ready) {
                Log.w(TAG, "Quantum fff5/notification setup failed; refusing writable connection")
                if (_connectFailureReason.value == null) {
                    _connectFailureReason.value = R.string.board_ble_connect_failed_hint
                }
                disconnect()
                onRestartScannersAfterConnect?.invoke()
                return@launch
            }
            markGattReady(currentGatt)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readQuantumMetadata(currentGatt: BluetoothGatt): QuantumControllerMetadata? {
        val quantumService = currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE)
            ?: currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD)
            ?: return null
        val metadataCharacteristic =
            quantumService.getCharacteristic(BoardBleUuids.QUANTUM_METADATA_CHAR) ?: return null
        val deferred = CompletableDeferred<QuantumGattRead>()
        quantumMetadataReadDeferred = deferred
        val queued = currentGatt.readCharacteristic(metadataCharacteristic)
        if (!queued) {
            quantumMetadataReadDeferred = null
            return null
        }
        val result = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        if (quantumMetadataReadDeferred === deferred) quantumMetadataReadDeferred = null
        if (result == null) {
            // No operation token is attached to read callbacks. Retire this
            // GATT in the setup caller so a late fff5 callback cannot satisfy
            // a future attempt.
            return null
        }
        if (result.status != BluetoothGatt.GATT_SUCCESS) return null
        return result.value?.let(::parseQuantumControllerMetadata)
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableQuantumNotifications(currentGatt: BluetoothGatt): Boolean {
        val quantumService = currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE)
            ?: currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD)
            ?: return false
        val notify = quantumService.getCharacteristic(BoardBleUuids.QUANTUM_NOTIFY_CHAR)
            ?: return false
        val localNotificationsEnabled = currentGatt.setCharacteristicNotification(notify, true)
        if (!localNotificationsEnabled) return false
        val descriptor = notify.getDescriptor(BoardBleUuids.CLIENT_CHARACTERISTIC_CONFIG)
            ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val deferred = CompletableDeferred<Int>()
        descriptorDeferred = deferred
        val queued = currentGatt.writeDescriptor(descriptor)
        if (!queued) {
            descriptorDeferred = null
            return false
        }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        if (descriptorDeferred === deferred) descriptorDeferred = null
        return quantumNotificationSetupConfirmed(localNotificationsEnabled, status)
    }

    private fun markGattReady(currentGatt: BluetoothGatt) {
        if (_connectionState.value != ConnectionState.CONNECTING ||
            gatt !== currentGatt || isGattClosed(currentGatt) || writeCharacteristic == null
        ) return
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        Log.i(TAG, "GATT ready, state→CONNECTED (writes can start)")
        _connectionState.value = ConnectionState.CONNECTED
        resetIdleTimer()
        onRestartScannersAfterConnect?.invoke()
        if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
            quantumRefreshJob?.cancel()
            quantumRefreshJob = scope.launch {
                // fff4 is the eWalls current-state read characteristic. A
                // structurally valid complete snapshot avoids an unnecessary
                // controller command; unsupported/delta evidence falls back to
                // REQUEST_USER_ROUTE_LIST.
                if (!refreshQuantumState()) return@launch
                while (gatt === currentGatt &&
                    _connectedBoardBrand.value == BoardBrand.QUANTUM
                ) {
                    delay(QUANTUM_REFRESH_INTERVAL_MS)
                    if (!refreshQuantumState()) return@launch
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=0x${status.toString(16)} newState=$newState userDisc=$userDisconnecting SDK=${Build.VERSION.SDK_INT}")

            when (classifyGattConnectionCallback(
                currentGattMatches = this@BoardBleConnection.gatt === gatt,
                retiringGattMatches = userDisconnectGatt === gatt,
                newState = newState,
            )) {
                GattConnectionCallbackRole.CURRENT -> Unit
                // A normal disconnect deliberately clears the active field
                // before its callback. Allow that exact retiring object to
                // finish closing, but never let it cancel/retry a newer GATT.
                GattConnectionCallbackRole.RETIRING_DISCONNECT -> {
                    val finishRetiredClose = {
                        if (userDisconnectGatt === gatt) userDisconnectGatt = null
                        closeGatt(gatt)
                        closeSafetyJob?.cancel()
                        closeSafetyJob = null
                        userDisconnecting = false
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        mainHandler.postDelayed(finishRetiredClose, DELAY_CLOSE_AFTER_DISCONNECT_MS)
                    } else {
                        finishRetiredClose()
                    }
                    return
                }
                GattConnectionCallbackRole.STALE -> {
                    Log.w(TAG, "Ignoring stale connection-state callback from an older GATT attempt")
                    return
                }
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Check for connection error BEFORE touching the success path
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Connection error status=0x${status.toString(16)}, cleaning up")
                        connectionTimeoutJob?.cancel()
                        connectionTimeoutJob = null
                        closeGatt(gatt)
                        retryOrFinalize(status)
                        return
                    }
                    if (userDisconnecting) {
                        Log.w(TAG, "Ignoring STATE_CONNECTED during user disconnect")
                        return
                    }
                    // Don't set CONNECTED yet — wait for onServicesDiscovered to find
                    // the write characteristic. Otherwise auto-send races with service
                    // discovery and fails because writeCharacteristic is still null.
                    // Keep connectionTimeoutJob running to cover service discovery too.
                    Log.d(TAG, "GATT connected, discovering services...")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        // Give legacy stacks a beat after STATE_CONNECTED.
                        // The attempt timeout keeps covering this window.
                        mainHandler.postDelayed({
                            if (_connectionState.value == ConnectionState.CONNECTING &&
                                this@BoardBleConnection.gatt === gatt && !isGattClosed(gatt)
                            ) {
                                startServiceDiscovery(gatt)
                            }
                        }, DELAY_PRE_DISCOVERY_LEGACY_MS)
                    } else {
                        startServiceDiscovery(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    cancelQuantumGattOperations()
                    // Snapshot-before-null so a concurrent writer that just
                    // installed a new deferred doesn't get its completion lost.
                    val pending = pendingWrite?.takeIf { it.gatt === gatt }
                    if (pendingWrite === pending) pendingWrite = null
                    pending?.result?.complete(BluetoothGatt.GATT_FAILURE)

                    // On Android <12, delay before close() — the BLE stack needs time
                    // after STATE_DISCONNECTED to fully release internal resources.
                    // retryOrFinalize: a failure while still CONNECTING (the classic
                    // transient status-133 on legacy stacks) retries quietly;
                    // established-link drops and user disconnects finalize as before.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        mainHandler.postDelayed({
                            closeGatt(gatt)
                            retryOrFinalize(status)
                        }, DELAY_CLOSE_AFTER_DISCONNECT_MS)
                    } else {
                        closeGatt(gatt)
                        retryOrFinalize(status)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!serviceDiscoveryCompletionAllowed(
                    connecting = _connectionState.value == ConnectionState.CONNECTING,
                    currentGattMatches = this@BoardBleConnection.gatt === gatt,
                    gattClosed = isGattClosed(gatt),
                    alreadyHandled = serviceDiscoveryHandledGatt === gatt,
                )
            ) return
            // Claim this attempt before MTU/CCCD work. A delayed duplicate
            // callback or the populated-services fallback must not replay it.
            serviceDiscoveryHandledGatt = gatt
            // Log.i so the R8 Log.d/v stripping rule doesn't erase the diagnostic marker.
            Log.i(TAG, "onServicesDiscovered status=$status services=${gatt.services.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BoardBleUuids.DATA_TRANSFER_SERVICE)
                writeCharacteristic = if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                    val quantumService = gatt.getService(BoardBleUuids.QUANTUM_SERVICE)
                        ?: gatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD)
                    quantumService?.getCharacteristic(BoardBleUuids.QUANTUM_WRITE_CHAR)?.also {
                        it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                } else service?.getCharacteristic(BoardBleUuids.DATA_TRANSFER_CHAR)
                if (writeCharacteristic != null) {
                    if (_connectedBoardBrand.value == BoardBrand.QUANTUM && Build.VERSION.SDK_INT >= 21) {
                        // Match eWalls 2.0.14: finish service retrieval first,
                        // then negotiate MTU, then register notifications. The
                        // previous concurrent discoverServices/requestMtu pair
                        // made Nokia Android 10 populate services internally
                        // without delivering onServicesDiscovered.
                        quantumMtuGatt = gatt
                        val queued = gatt.requestMtu(512)
                        Log.d(TAG, "requestMtu after services queued=$queued")
                        if (queued) {
                            mainHandler.postDelayed({
                                if (quantumMtuGatt === gatt) {
                                    Log.w(TAG, "MTU callback timeout; continuing with negotiated/default MTU")
                                    finishGattSetup(gatt)
                                }
                            }, QUANTUM_MTU_TIMEOUT_MS)
                        } else {
                            finishGattSetup(gatt)
                        }
                    } else {
                        finishGattSetup(gatt)
                    }
                } else {
                    Log.w(TAG, "DATA_TRANSFER_CHAR not found in service")
                    // Pre-2017 MoonBoard LED kits speak the RedBear UART
                    // service instead of the Nordic UART we implement. Flag
                    // it so the UI can show an honest "this MoonBoard
                    // generation is not supported yet" instead of leaving
                    // the user in a silent connect/disconnect loop.
                    if (_connectedBoardBrand.value == BoardBrand.MOONBOARD &&
                        gatt.getService(BoardBleUuids.REDBEAR_UART_SERVICE) != null
                    ) {
                        Log.w(TAG, "RedBear UART service present — unsupported MoonBoard LED-kit generation")
                        _connectFailureReason.value = R.string.board_ble_moonboard_generation_unsupported
                    } else if (_connectFailureReason.value == null) {
                        // Unknown service layout — retrying won't change it,
                        // but the user must not get a silent drop-back either.
                        _connectFailureReason.value = R.string.board_ble_connect_failed_hint
                    }
                    // Tear the link down properly. Only flipping the state
                    // would leak a live GATT: the board stops advertising
                    // while connected, so it vanishes from scans until a
                    // Bluetooth toggle. disconnect() runs the full teardown
                    // (gatt.disconnect → close, pendingClose, safety timer).
                    disconnect()
                    onRestartScannersAfterConnect?.invoke()
                }
            } else {
                Log.w(TAG, "onServicesDiscovered failed: status=$status")
                if (canRetryConnect()) {
                    // Transient discovery failure (129/133 on legacy stacks):
                    // quiet teardown + retry instead of a silent full drop.
                    scheduleRetry("discovery status=$status")
                } else {
                    if (_connectFailureReason.value == null) {
                        _connectFailureReason.value = R.string.board_ble_connect_failed_hint
                    }
                    // Same teardown as the missing-characteristic arm above.
                    disconnect()
                    onRestartScannersAfterConnect?.invoke()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            if (quantumMtuGatt === gatt) finishGattSetup(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite failed: status=0x${status.toString(16)}")
            }
            pendingWrite?.takeIf {
                this@BoardBleConnection.gatt === gatt &&
                    it.gatt === gatt && it.characteristic === characteristic
            }?.result?.complete(status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@BoardBleConnection.gatt === gatt &&
                descriptor.uuid == BoardBleUuids.CLIENT_CHARACTERISTIC_CONFIG
            ) {
                descriptorDeferred?.complete(status)
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeQuantumRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeQuantumRead(gatt, characteristic, value, status)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (this@BoardBleConnection.gatt === gatt &&
                characteristic.uuid == BoardBleUuids.QUANTUM_NOTIFY_CHAR
            ) {
                consumeQuantumNotification(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@BoardBleConnection.gatt === gatt &&
                characteristic.uuid == BoardBleUuids.QUANTUM_NOTIFY_CHAR
            ) {
                consumeQuantumNotification(value)
            }
        }

    }

    private fun completeQuantumRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ) {
        if (gatt !== callbackGatt) return
        val result = QuantumGattRead(status, value?.copyOf())
        when (characteristic.uuid) {
            BoardBleUuids.QUANTUM_STATE_CHAR -> quantumReadDeferred?.complete(result)
            BoardBleUuids.QUANTUM_METADATA_CHAR -> quantumMetadataReadDeferred?.complete(result)
        }
    }

    private fun consumeQuantumNotification(bytes: ByteArray) {
        quantumNotificationAccumulator.consume(bytes).forEach { recovered ->
            if (recovered.crossedCallbackBoundary && !quantumRouteListRequestActive) {
                // A truncated callback followed by another fragmented event can
                // otherwise splice into a structurally plausible player list.
                // Keep observing complete callback-local events, but wait for
                // the next reset explicit request before trusting a recovered
                // cross-callback frame.
                Log.w(TAG, "Ignoring uncorrelated fragmented Quantum notification")
                return@forEach
            }
            applyQuantumBroadcast(
                QuantumBoardBroadcastParser.parse(recovered.bytes),
                explicitRouteListResponse = quantumRouteListRequestActive,
            )
        }
    }

    /** Apply only evidence represented by the recovered eWalls broadcast
     * contract. Complete route lists/all-off acknowledgements advance
     * [QuantumControllerState.authoritativeRevision]; deltas never do. */
    private fun applyQuantumBroadcast(
        broadcast: QuantumBroadcast?,
        explicitRouteListResponse: Boolean = false,
    ): QuantumControllerEvidence {
        val evidence = classifyQuantumControllerEvidence(broadcast)
        val current = _quantumControllerState.value
        val revision = current.revision + 1
        when (broadcast) {
            is QuantumBroadcast.RouteList -> _quantumControllerState.value =
                QuantumControllerState(
                    players = broadcast.players,
                    revision = revision,
                    authoritativeRevision = revision,
                    routeListRevision = if (
                        explicitRouteListResponse &&
                        broadcast.command == com.cruxcoach.domain.board.QuantumCommand.REQUEST_USER_ROUTE_LIST
                    ) revision else current.routeListRevision,
                    authoritative = true,
                )
            is QuantumBroadcast.UserTurnedOff -> _quantumControllerState.value =
                current.copy(
                    players = current.players.filterNot {
                        it.userId.equals(broadcast.userId, ignoreCase = true)
                    },
                    revision = revision,
                    lastFailure = null,
                )
            QuantumBroadcast.BoardCleared -> _quantumControllerState.value =
                QuantumControllerState(
                    revision = revision,
                    authoritativeRevision = revision,
                    routeListRevision = current.routeListRevision,
                    authoritative = true,
                )
            is QuantumBroadcast.Exception -> _quantumControllerState.value =
                current.copy(
                    revision = revision,
                    lastFailure = quantumFailure(broadcast.code),
                )
            is QuantumBroadcast.BoardLit, null -> Unit
        }
        return evidence
    }

    private fun quantumFailure(code: Int): QuantumCommandFailure = when (code) {
        5 -> QuantumCommandFailure.ROUTE_IN_USE
        6 -> QuantumCommandFailure.SPOT_UNAVAILABLE
        7 -> QuantumCommandFailure.COLOR_TAKEN
        8 -> QuantumCommandFailure.USER_ID_IN_USE
        9 -> QuantumCommandFailure.BOARD_FULL
        10 -> QuantumCommandFailure.ROUTESETTER_MODE
        11 -> QuantumCommandFailure.DIODE_MISSING
        254 -> QuantumCommandFailure.ACK_TIMEOUT
        else -> QuantumCommandFailure.REFUSED
    }

    /** Finalize state after disconnect callback (or error). */
    private fun finalizeDisconnect(status: Int) {
        cancelQuantumGattOperations()
        if (userDisconnecting) {
            closeSafetyJob?.cancel()
            closeSafetyJob = null
            userDisconnecting = false
            Log.d(TAG, "User disconnect complete, GATT closed in callback")
            return
        }
        // Remote disconnect or error
        Log.d(TAG, "Remote/error disconnect (status=0x${status.toString(16)})")
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedBoardName.value = null
        _connectedBoardBrand.value = null
        _connectedQuantumModel.value = null
        currentBoard = null
        _connectedBoardDescriptor.value = null
        gatt = null
        writeCharacteristic = null
    }

    /**
     * Close a GATT object: refresh() → close() → null.
     * Guards against double-close by GATT object identity.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        if (isGattClosed(g)) {
            Log.d(TAG, "closeGatt: already closed, skipping")
            pendingClose?.complete(Unit)
            return
        }
        closedGatt = g

        // refresh() clears cached GATT handles. Call BEFORE close(), AFTER disconnect.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                val refreshMethod = BluetoothGatt::class.java.getMethod("refresh")
                val refreshed = refreshMethod.invoke(g) as? Boolean ?: false
                Log.d(TAG, "GATT refresh() = $refreshed")
            } catch (e: Exception) {
                Log.d(TAG, "GATT refresh() not available: ${e.message}")
            }
        }

        try {
            g.close()
            Log.d(TAG, "GATT close() completed")
        } catch (e: Exception) {
            Log.w(TAG, "GATT close() error (non-fatal)", e)
        }

        pendingClose?.complete(Unit)
    }

    /**
     * Connect to a board. On Android <12, uses Main-Thread handler and settling delays.
     *
     * Key fixes from Nordic BLE Library research:
     * - connectGatt() on Main-Thread with explicit Handler (callback Looper issue on API 28-30)
     * - Wait for pending GATT close before connecting (slot exhaustion prevention)
     * - Stop scanners before connect (shared radio contention on single-radio controllers)
     * - Always TRANSPORT_LE, never TRANSPORT_AUTO
     */
    /**
     * @param maxAttempts how often a radio-level failure may be retried
     *   quietly. The default absorbs the transient status-133 failures legacy
     *   stacks produce. A speculative connect — "is the remembered board even
     *   here?" — passes 1 instead: three attempts take ~32 s, and a flow that
     *   falls back to asking for a permission cannot make the user wait that
     *   long to find out the board is absent.
     */
    @SuppressLint("MissingPermission")
    fun connect(board: DiscoveredBoard, maxAttempts: Int = MAX_CONNECT_ATTEMPTS) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        cancelQuantumGattOperations()
        quantumStateReadUsable = true
        _connectedQuantumModel.value = null
        attemptBudget = maxAttempts.coerceIn(1, MAX_CONNECT_ATTEMPTS)
        userDisconnecting = false
        _connectionState.value = ConnectionState.CONNECTING
        if (board.boardBrand == BoardBrand.QUANTUM) {
            quantumNotificationAccumulator.reset()
            _quantumControllerState.value = QuantumControllerState(
                revision = _quantumControllerState.value.revision + 1,
                authoritative = false,
            )
        }
        _connectedBoardName.value = board.displayName
        _connectedBoardBrand.value = board.boardBrand
        // Fresh attempt — drop any failure reason from the previous one.
        _connectFailureReason.value = null
        currentBoard = board
        _connectedBoardDescriptor.value = board
        connectAttempt = 1
        // FEAT-031: ledsPerHold (Kilter = 2, other Aurora boards = 1) feeds the
        // @2 LED power-budget scaling; harmless on @3 (where it is unused).
        encoder = BoardPacketEncoder(board.apiLevel, BoardPacketEncoder.ledsPerHoldFor(board.boardBrand))

        // Stop external scanners before GATT connect (radio contention on Android <12)
        onStopScannersForConnect?.invoke()

        connectJob = scope.launch {
            // Wait for any pending GATT close from a previous session.
            pendingClose?.let { deferred ->
                Log.d(TAG, "Waiting for pending GATT close before connecting")
                withTimeoutOrNull(CLOSE_SAFETY_TIMEOUT_MS + 1000) { deferred.await() }
                pendingClose = null
                Log.d(TAG, "Pending close resolved")
            }

            // Scanner settle delay — BLE radio needs time after scan stop
            if (onStopScannersForConnect != null) {
                delay(DELAY_SCAN_SETTLE_MS)
            }

            // On Android <12, extra delay for GATT slot release
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                delay(DELAY_RECONNECT_LEGACY_MS)
            } else {
                delay(DELAY_RECONNECT_MODERN_MS)
            }

            startGattAttempt(board)
        }
    }

    /** True while the in-flight connect may quietly retry: still CONNECTING,
     *  not user-cancelled, attempts left. */
    private fun canRetryConnect(): Boolean =
        _connectionState.value == ConnectionState.CONNECTING &&
            !userDisconnecting &&
            connectAttempt < attemptBudget &&
            currentBoard != null

    /** Route a failed radio-level attempt: quiet retry while attempts remain,
     *  else surface the generic failure hint and finalize the disconnect. */
    private fun retryOrFinalize(status: Int) {
        if (canRetryConnect()) {
            scheduleRetry("status=0x${status.toString(16)}")
        } else {
            if (_connectionState.value == ConnectionState.CONNECTING &&
                !userDisconnecting && _connectFailureReason.value == null
            ) {
                _connectFailureReason.value = R.string.board_ble_connect_failed_hint
            }
            finalizeDisconnect(status)
        }
    }

    /** Tear the current attempt's GATT down WITHOUT leaving CONNECTING and
     *  schedule the next attempt after a short backoff. Safe against a
     *  callback-side closeGatt that already ran (double-close guarded). */
    @SuppressLint("MissingPermission")
    private fun scheduleRetry(trigger: String) {
        connectAttempt += 1
        Log.i(TAG, "Connect attempt failed ($trigger) — retrying quietly (attempt $connectAttempt/$attemptBudget)")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        cancelQuantumGattOperations()
        _connectedQuantumModel.value = null
        gatt?.let { g ->
            runCatching { g.disconnect() }
            closeGatt(g)
        }
        gatt = null
        writeCharacteristic = null
        val board = currentBoard ?: run { finalizeDisconnect(BluetoothGatt.GATT_FAILURE); return }
        connectJob = scope.launch {
            delay(CONNECT_RETRY_DELAY_MS)
            if (_connectionState.value != ConnectionState.CONNECTING) return@launch
            startGattAttempt(board)
        }
    }

    /** One radio-level connect attempt: fresh connectGatt + per-attempt
     *  timeout. Called from connect()'s prelude and again by scheduleRetry(). */
    @SuppressLint("MissingPermission")
    private fun startGattAttempt(board: DiscoveredBoard) {
        _connectedQuantumModel.value = null

        // Abort if state changed during the wait
        if (_connectionState.value != ConnectionState.CONNECTING) {
            _connectedBoardName.value = null
            _connectedBoardBrand.value = null
            _connectedQuantumModel.value = null
            currentBoard = null
            _connectedBoardDescriptor.value = null
            return
        }

        // Safety: close stale GATT if still open (Nordic MCP pattern)
        gatt?.let { oldGatt ->
            Log.w(TAG, "Closing stale GATT before reconnect")
            try { oldGatt.close() } catch (e: Exception) { Log.w(TAG, "Failed to close old GATT", e) }
            gatt = null
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(board.address)

        // CRITICAL: connectGatt() on Main-Thread with explicit callback Handler.
        // On Android 9, the BT stack dispatches callbacks via the calling thread's Looper.
        // If called from a coroutine dispatcher without a Looper, callbacks are silently dropped.
        // The Handler overload (API 26+) forces callbacks onto the Main Looper.
        // Log.i so this start-of-connect marker survives R8's Log.d-stripping rule.
        Log.i(TAG, "connectGatt() for ${board.address} (SDK=${Build.VERSION.SDK_INT}, attempt=$connectAttempt/$attemptBudget)")
        val newGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainHandler
        )

        if (newGatt == null) {
            Log.e(TAG, "connectGatt returned null — GATT client slot exhausted?")
            if (canRetryConnect()) {
                // Slot exhaustion is usually transient while a previous close
                // settles — exactly what the backoff retry is for.
                scheduleRetry("connectGatt=null")
            } else {
                if (_connectFailureReason.value == null) {
                    _connectFailureReason.value = R.string.board_ble_connect_failed_hint
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedBoardName.value = null
                _connectedBoardBrand.value = null
                _connectedQuantumModel.value = null
                currentBoard = null
                _connectedBoardDescriptor.value = null
                onRestartScannersAfterConnect?.invoke()
            }
            return
        }

        gatt = newGatt
        serviceDiscoveryGatt = null
        serviceDiscoveryHandledGatt = null
        quantumMtuGatt = null

        // Per-attempt timeout: a hung CONNECTING is torn down and retried
        // quietly; only the final attempt surfaces the failure.
        connectionTimeoutJob = scope.launch {
            delay(CONNECT_ATTEMPT_TIMEOUT_MS)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                Log.w(TAG, "Connect attempt timed out after ${CONNECT_ATTEMPT_TIMEOUT_MS}ms")
                if (canRetryConnect()) {
                    scheduleRetry("timeout")
                } else {
                    if (_connectFailureReason.value == null) {
                        _connectFailureReason.value = R.string.board_ble_connect_failed_hint
                    }
                    disconnect()
                }
            }
        }
    }

    /**
     * Write a single BLE chunk with flow control.
     * Waits for onCharacteristicWrite callback before returning.
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        val deferred = CompletableDeferred<Int>()
        val pending = PendingGattWrite(currentGatt, characteristic, deferred)
        pendingWrite = pending

        characteristic.value = chunk
        val queued = currentGatt.writeCharacteristic(characteristic)
        if (!queued) {
            Log.w(TAG, "writeCharacteristic returned false (not queued)")
            if (pendingWrite === pending) pendingWrite = null
            delay(100)
            return false
        }

        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        if (pendingWrite === pending) pendingWrite = null

        if (status == null) {
            Log.w(TAG, "Write timed out after ${WRITE_TIMEOUT_MS}ms")
            // Android does not give write callbacks an operation token. Retire
            // this GATT so a late callback cannot acknowledge a later write on
            // the same characteristic.
            disconnect()
            return false
        }
        return status == BluetoothGatt.GATT_SUCCESS
    }

    /**
     * Write multiple BLE chunks with flow control.
     * Each chunk waits for the previous write to complete.
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunks(chunks: List<ByteArray>): Boolean {
        val characteristic = writeCharacteristic ?: return false
        val currentGatt = gatt ?: return false

        for ((i, chunk) in chunks.withIndex()) {
            val success = writeChunk(currentGatt, characteristic, chunk)
            if (!success) {
                Log.w(TAG, "Write failed at chunk $i/${chunks.size}")
                return false
            }
        }
        return true
    }

    /**
     * Send a climb's holds to the connected board, lighting up the LEDs.
     * Uses mutex to prevent concurrent sends and callback-based flow control.
     */
    suspend fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        roleColors: Map<Int, Int>? = null,
        routeId: String? = null,
        quantumUserId: String = QuantumBoardPacketEncoder.ZERO_UUID,
        quantumColor: Int = 0x00ffff,
        expectedQuantumPlayers: List<QuantumActivePlayer>? = null,
        expectedQuantumBoard: BoardLayerBoardIdentity? = null,
        expectedBrand: BoardBrand? = null,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        if (!boardScopedCommandAllowed(_connectedBoardBrand.value, expectedBrand)) {
            Log.w(TAG, "Refusing projection for $expectedBrand on ${_connectedBoardBrand.value}")
            return false
        }
        val quantumPlayersBefore = if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
            expectedQuantumPlayers ?: return false
        } else {
            emptyList()
        }

        if (_connectedBoardBrand.value == BoardBrand.QUANTUM &&
            !quantumBoardWriteFenceMatches(
                currentBoard,
                _connectedQuantumModel.value,
                expectedQuantumBoard,
            )
        ) {
            Log.w(TAG, "Refusing Quantum projection after board/model fence changed")
            return false
        }
        if (_connectedBoardBrand.value == BoardBrand.QUANTUM &&
            !isScopedQuantumUserId(quantumUserId, ownsQuantumUserId)
        ) {
            // Reject before changing connection state or the generic resend
            // cache. Editor/settings callers do not own the anonymous
            // sentinel and must not perturb a later scoped Quantum resend.
            Log.w(TAG, "Refusing Quantum projection with anonymous user identity")
            return false
        }
        if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
            // The controller list is shared mutable state. Re-read under the
            // same mutex as TURN_OFF_USER and reject any occupancy change since
            // the caller's conflict/capacity preflight.
            if (!requestQuantumRouteListLocked(expectedQuantumBoard) ||
                !quantumPlayersMatch(quantumPlayersBefore, _quantumControllerState.value.players)
            ) return false
        }
        if (_connectedBoardBrand.value == BoardBrand.QUANTUM &&
            !hasCompleteQuantumLedMapping(holds, placementToLed)
        ) {
            // Route-list readback confirms only route/user/color. It cannot
            // prove which diodes were accepted, so a partial Quantum mapping
            // must be rejected before any controller mutation.
            Log.w(TAG, "Refusing partial Quantum projection: incomplete LED mapping")
            return false
        }
        if (_connectedBoardBrand.value == BoardBrand.QUANTUM &&
            !hasConfirmableQuantumDiodeCount(holds)
        ) {
            // Route-list readback cannot prove append/atomicity across multiple
            // ACTIVATE_WALL frames. Fail closed until captured hardware evidence
            // establishes that contract.
            Log.w(TAG, "Refusing unverified multi-frame Quantum activation")
            return false
        }

        lastHolds = holds
        lastPlacementToLed = placementToLed
        lastSendBoardAddress = currentBoard?.address
        lastSendBoardBrand = _connectedBoardBrand.value

        _connectionState.value = ConnectionState.SENDING
        // Park any pending idle-disconnect so it can't fire mid-send.
        // Re-armed from the finally below once we flip back to CONNECTED.
        disconnectJob?.cancel()
        try {
            // Holds without an LED mapping (outside the configured product
            // size, e.g. kickboard rows on a no-kickboard board) are skipped
            // by both encode branches below — the wall then shows a partial
            // climb. Log it so a "sent ok but holds missing" report is
            // triageable; the detail-screen send path surfaces a UI warning.
            val unmapped = holds.count { placementToLed[it.placementId] == null }
            if (unmapped > 0) {
                Log.w(TAG, "sendClimb: $unmapped/${holds.size} holds have no LED mapping — board will light a partial climb")
            }
            if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                val mapped = holds.mapNotNull { placementToLed[it.placementId] }
                // Mirror eWalls 2.0.14's route transition: a Quantum
                // controller keeps per-user route ownership and rejects a
                // second ACTIVATE_WALL until that user's previous route is
                // released. This is deliberately TURN_OFF_USER (not ALL), so
                // other climbers sharing a capable controller are untouched.
                val transition = QuantumBoardPacketEncoder.replaceUserRoute(
                    routeId = routeId ?: QuantumBoardPacketEncoder.ZERO_UUID,
                    userId = quantumUserId,
                    diodes = mapped,
                    color = quantumColor and 0xffffff,
                )
                _quantumControllerState.value = _quantumControllerState.value.copy(lastFailure = null)
                if (!writeQuantumFrames(transition.take(1), expectedQuantumBoard)) return false
                delay(50)
                if (!writeQuantumFrames(transition.drop(1), expectedQuantumBoard)) return false
                if (!requestQuantumRouteListLocked(expectedQuantumBoard)) return false
                val snapshot = _quantumControllerState.value
                return isQuantumProjectionConfirmed(
                    state = snapshot,
                    playersBefore = quantumPlayersBefore,
                    routeId = routeId ?: QuantumBoardPacketEncoder.ZERO_UUID,
                    userId = quantumUserId,
                    color = quantumColor,
                )
            }
            val chunks = if (roleColors != null) {
                // Resolve each hold's colour by canonical role CLASS, not raw
                // code. A climb authored on an Aurora board carries the editor's
                // Kilter-style codes (12-15) while the board's colour map is
                // keyed by its native codes (1-4), so a raw-id lookup would miss
                // and fall back to the wrong palette. roleClass folds 1-4 /
                // 12-15 / 42-44 onto the same role, so authored and catalogue
                // climbs light identically in the board's own colours.
                val byClass = roleColors.entries.associate { HoldRole.roleClass(it.key) to it.value }
                val holdPairs = holds.mapNotNull { hold ->
                    val led = placementToLed[hold.placementId] ?: return@mapNotNull null
                    led to (byClass[HoldRole.roleClass(hold.roleId)] ?: BoardPacketEncoder.roleToColor(hold.roleId))
                }
                encoder.encodeClimb(holdPairs)
            } else {
                encoder.encodeClimbFromHolds(holds, placementToLed)
            }

            return writeChunks(chunks)
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            // Arm the idle-disconnect timer with the post-send state.
            resetIdleTimer()
        }
    }

    private suspend fun writeQuantumFrames(
        frames: List<ByteArray>,
        expectedQuantumBoard: BoardLayerBoardIdentity? = null,
    ): Boolean {
        for ((index, frame) in frames.withIndex()) {
            if (expectedQuantumBoard != null &&
                !quantumBoardWriteFenceMatches(
                    currentBoard,
                    _connectedQuantumModel.value,
                    expectedQuantumBoard,
                )
            ) return false
            // 2.0.14 passes 15 as Android's native write fragment size.
            // This is transport fragmentation, independent of the 92-diode
            // logical command limit enforced by the encoder.
            val transportChunks = frame.toList().chunked(15).map { it.toByteArray() }
            if (!writeChunks(transportChunks)) return false
            if (index != frames.lastIndex) delay(100)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun readQuantumStateLocked(
        explicitRouteListResponse: Boolean = false,
    ): QuantumControllerEvidence {
        if (!quantumStateReadUsable) return QuantumControllerEvidence.UNSUPPORTED
        val currentGatt = gatt ?: return QuantumControllerEvidence.UNSUPPORTED
        val service = currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE)
            ?: currentGatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD)
            ?: return QuantumControllerEvidence.UNSUPPORTED
        val stateCharacteristic = service.getCharacteristic(BoardBleUuids.QUANTUM_STATE_CHAR)
            ?: return QuantumControllerEvidence.UNSUPPORTED
        val deferred = CompletableDeferred<QuantumGattRead>()
        quantumReadDeferred = deferred
        val queued = currentGatt.readCharacteristic(stateCharacteristic)
        if (!queued) {
            quantumReadDeferred = null
            quantumStateReadUsable = false
            return QuantumControllerEvidence.UNSUPPORTED
        }
        val result = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        if (quantumReadDeferred === deferred) quantumReadDeferred = null
        if (result == null) {
            // Android does not attach an operation token to read callbacks.
            // Never issue another fff4 read on this GATT; a late callback is
            // then harmless. The explicit route-list notification path is a
            // separate callback and remains safe to use on the current link.
            quantumStateReadUsable = false
            Log.w(TAG, "Quantum fff4 read timed out; using explicit route-list requests")
            return QuantumControllerEvidence.UNSUPPORTED
        }
        if (result.status != BluetoothGatt.GATT_SUCCESS) {
            quantumStateReadUsable = false
            Log.w(TAG, "Quantum fff4 read failed with status=${result.status}; using explicit route-list requests")
            return QuantumControllerEvidence.UNSUPPORTED
        }
        val broadcast = result.value?.let(QuantumBoardBroadcastParser::parse)
        val evidence = classifyQuantumFff4Evidence(broadcast)
        if (quantumFff4PublishesSnapshot(broadcast)) {
            // An fff4 route list may be used for periodic observation, but it
            // never advances routeListRevision: only the notification received
            // inside an explicit REQUEST_USER_ROUTE_LIST generation can
            // confirm a mutation precondition or postcondition.
            applyQuantumBroadcast(
                broadcast,
                explicitRouteListResponse = explicitRouteListResponse &&
                    quantumFff4ConfirmsExplicitRouteList(broadcast),
            )
        }
        return evidence
    }

    private suspend fun requestQuantumRouteListLocked(
        expectedQuantumBoard: BoardLayerBoardIdentity? = null,
    ): Boolean {
        if (expectedQuantumBoard != null &&
            !quantumBoardWriteFenceMatches(
                currentBoard,
                _connectedQuantumModel.value,
                expectedQuantumBoard,
            )
        ) return false
        val before = _quantumControllerState.value
        _quantumControllerState.value = before.copy(lastFailure = null)
        quantumNotificationAccumulator.reset()
        quantumRouteListRequestActive = true
        try {
            if (!writeQuantumFrames(
                    listOf(QuantumBoardPacketEncoder.requestRouteList()),
                    expectedQuantumBoard,
                )
            ) return false
            // Real Quantum XL controllers acknowledge the write transport but
            // do not emit an fff1 notification for REQUEST_USER_ROUTE_LIST.
            // They publish the requested complete 0x47 snapshot through fff4
            // instead. Read it under the same GATT/write mutex and generation;
            // notification-only controllers remain supported by the wait
            // below (including a notification arriving during this read).
            delay(50)
            if (hasFreshExplicitQuantumRouteList(before, _quantumControllerState.value)) {
                return true
            }
            readQuantumStateLocked(explicitRouteListResponse = true)
            val afterRead = _quantumControllerState.value
            if (hasFreshExplicitQuantumRouteList(before, afterRead)) return true
            val state = awaitQuantumState(before.revision) { snapshot ->
                snapshot.lastFailure != null ||
                    snapshot.routeListRevision > before.routeListRevision
            }
            if (state == null) {
                // Neither the serialized fff4 read nor fff1 produced the
                // requested 0x47 snapshot. Retire the GATT so a late response
                // can never confirm a later request.
                disconnect()
                return false
            }
            return hasFreshExplicitQuantumRouteList(before, state)
        } finally {
            quantumRouteListRequestActive = false
            quantumNotificationAccumulator.reset()
        }
    }

    /** Prefer eWalls' fff4 state characteristic. Only a complete supported
     * snapshot is sufficient; deltas, informational records, malformed reads
     * and unavailable fff4 all fall back to an explicit route-list request. */
    private suspend fun refreshQuantumStateLocked(): Boolean {
        val readEvidence = readQuantumStateLocked()
        if (!quantumReadRequiresRouteListFallback(readEvidence)) return true
        return requestQuantumRouteListLocked()
    }

    /** Pull the controller's authoritative active-player list. */
    suspend fun refreshQuantumState(): Boolean {
        val operationGatt = gatt
        val refreshed = quantumGattOperationSucceeded {
            writeMutex.withLock {
                if (_connectionState.value != ConnectionState.CONNECTED ||
                    _connectedBoardBrand.value != BoardBrand.QUANTUM
                ) return@withLock false
                refreshQuantumStateLocked()
            }
        }
        if (!refreshed && quantumRefreshFailureRequiresDisconnect(
                currentGattMatches = operationGatt != null && gatt === operationGatt,
                connectedBrand = _connectedBoardBrand.value,
            )
        ) {
            Log.w(TAG, "Quantum authoritative refresh failed; retiring GATT")
            disconnect()
        }
        return refreshed
    }

    /** Remove exactly one installation-owned Quantum layer, never all users. */
    suspend fun removeQuantumLayer(
        userId: String,
        expectedRouteId: String? = null,
        expectedQuantumBoard: BoardLayerBoardIdentity? = null,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            _connectedBoardBrand.value != BoardBrand.QUANTUM
        ) return false
        if (!quantumBoardWriteFenceMatches(
                currentBoard,
                _connectedQuantumModel.value,
                expectedQuantumBoard,
            )
        ) return false
        // Never allow the protocol's anonymous sentinel to become a deletion
        // target. Installation layer identities are stable, non-zero UUIDs.
        if (!isScopedQuantumUserId(userId, ownsQuantumUserId) || expectedRouteId == null) return false
        // A retained wall can change through another eWalls client while our
        // rack is open. Refresh immediately before the scoped command; absence
        // is already the desired result and must not produce a speculative write.
        if (!requestQuantumRouteListLocked(expectedQuantumBoard)) return false
        val currentPlayer = _quantumControllerState.value.players.firstOrNull {
            it.userId.equals(userId, ignoreCase = true)
        } ?: return true
        val playersBefore = _quantumControllerState.value.players
        if (!currentPlayer.routeId.equals(expectedRouteId, ignoreCase = true)) return false
        _connectionState.value = ConnectionState.SENDING
        disconnectJob?.cancel()
        try {
            _quantumControllerState.value = _quantumControllerState.value.copy(lastFailure = null)
            if (!writeQuantumFrames(
                    listOf(QuantumBoardPacketEncoder.turnOffUser(userId)),
                    expectedQuantumBoard,
                )
            ) return false
            // Confirmation is a fresh complete snapshot, not an optimistic
            // local deletion or a delta copied over an older player list.
            if (!requestQuantumRouteListLocked(expectedQuantumBoard)) return false
            return isQuantumScopedRemovalConfirmed(
                state = _quantumControllerState.value,
                playersBefore = playersBefore,
                userId = userId,
            )
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            resetIdleTimer()
        }
    }

    private suspend fun awaitQuantumState(
        afterRevision: Long,
        predicate: (QuantumControllerState) -> Boolean,
    ): QuantumControllerState? = withTimeoutOrNull(QUANTUM_CONFIRM_TIMEOUT_MS) {
        quantumControllerState.first { it.revision > afterRevision && predicate(it) }
    }

    suspend fun resendWithColors(roleColors: Map<Int, Int>): Boolean {
        val holds = lastHolds ?: return false
        val ledMap = lastPlacementToLed ?: return false
        if (lastSendBoardBrand != _connectedBoardBrand.value ||
            lastSendBoardAddress?.equals(currentBoard?.address, ignoreCase = true) != true
        ) return false
        return sendClimb(holds, ledMap, roleColors)
    }

    suspend fun sendRawChunks(
        chunks: List<ByteArray>,
        expectedBrand: BoardBrand? = null,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            _connectedBoardBrand.value == BoardBrand.QUANTUM ||
            !boardScopedCommandAllowed(_connectedBoardBrand.value, expectedBrand)
        ) return false
        return writeChunks(chunks)
    }

    /**
     * Encode [leds] ((position, colourByte) pairs) with the CONNECTED board's
     * encoder — the apiLevel/ledsPerHold configured in [connect] — and write
     * them. Used by the easter animation so an @2 legacy board gets v2
     * packets instead of frames from a hardcoded API-3 encoder.
     */
    suspend fun sendRawLeds(
        leds: List<Pair<Int, Int>>,
        expectedBrand: BoardBrand? = null,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            _connectedBoardBrand.value == BoardBrand.QUANTUM ||
            !boardScopedCommandAllowed(_connectedBoardBrand.value, expectedBrand)
        ) return false
        return writeChunks(encoder.encodeClimb(leds))
    }

    /**
     * Send a MoonBoard climb to the connected board (FEAT-027).
     *
     * MoonBoard speaks the Nordic UART Service — the same GATT service
     * Aurora boards use — so [connect] and the [writeChunks] transport
     * are reused unchanged. Only the payload differs: an ASCII
     * `l#<token><pos>,…#` frame ([MoonBoardFrameEncoder]) instead of
     * Aurora's binary packets, split into BLE-MTU-sized writes.
     *
     * @param frames the climb's `p{holdId}r{roleCode}` frames string.
     * @param variant the MoonBoard variant of the active board; drives the
     *   per-column-height serpentine arithmetic in the encoder (18 for the
     *   standard 11×18 boards, 12 for Mini 2020).
     * @param ledMode selects the known strip position below, above, or on both
     *   sides of each hold. Finish holds always fall back to below.
     */
    suspend fun sendMoonBoardClimb(
        frames: String,
        variant: com.cruxcoach.domain.board.MoonBoardVariant,
        ledMode: MoonBoardLedMode = MoonBoardLedMode.BELOW,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            !moonBoardCommandAllowed(_connectedBoardBrand.value)
        ) return false

        _connectionState.value = ConnectionState.SENDING
        // Park any pending idle-disconnect so it can't fire mid-send.
        // Re-armed from the finally below once we flip back to CONNECTED.
        disconnectJob?.cancel()
        try {
            val payload = MoonBoardFrameEncoder.encode(frames, variant, ledMode)
            val chunks = payload.toList()
                .chunked(BoardPacketEncoder.BLE_MTU)
                .map { it.toByteArray() }
            val success = writeChunks(chunks)
            return success
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            // Arm the idle-disconnect timer with the post-send state —
            // resetIdleTimer() only arms while CONNECTED, so it must run
            // AFTER the state flip above (mirrors clearBoard / sendClimb).
            resetIdleTimer()
        }
    }

    /** Generic single-projection clear. Quantum is intentionally excluded:
     * its global command removes every eWalls user, so only the distinctly
     * named explicit API below may authorize it. [expectedBrand] fences
     * long-running producers such as Kilter animations across board swaps. */
    suspend fun clearBoard(expectedBrand: BoardBrand? = null): Boolean = writeMutex.withLock {
        val connectedBrand = _connectedBoardBrand.value
        if (_connectionState.value != ConnectionState.CONNECTED ||
            !boardScopedCommandAllowed(connectedBrand, expectedBrand) ||
            !genericBoardClearAllowed(connectedBrand)
        ) return false

        return clearConnectedBoardLocked(
            allowQuantumGlobalClear = false,
            expectedQuantumBoard = null,
        )
    }

    /** Deliberate global Quantum clear. Callers must put this behind an
     * explicit user action that describes its effect on every wall user. The
     * expected physical controller and model are mandatory because this is the
     * protocol's only operation allowed to remove foreign users. */
    suspend fun clearQuantumBoardExplicitly(
        expectedQuantumBoard: BoardLayerBoardIdentity,
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            _connectedBoardBrand.value != BoardBrand.QUANTUM ||
            !quantumBoardWriteFenceMatches(
                currentBoard,
                _connectedQuantumModel.value,
                expectedQuantumBoard,
            )
        ) return false
        return clearConnectedBoardLocked(
            allowQuantumGlobalClear = true,
            expectedQuantumBoard = expectedQuantumBoard,
        )
    }

    private suspend fun clearConnectedBoardLocked(
        allowQuantumGlobalClear: Boolean,
        expectedQuantumBoard: BoardLayerBoardIdentity?,
    ): Boolean {
        _connectionState.value = ConnectionState.SENDING
        // Park any pending idle-disconnect so it can't fire mid-send.
        // Re-armed from the finally below once we flip back to CONNECTED.
        disconnectJob?.cancel()
        try {
            if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                if (!allowQuantumGlobalClear || expectedQuantumBoard == null) return false
                // TURN_OFF_ALL is reserved for this explicit clear action. The
                // ordinary rack paths use TURN_OFF_USER only. Fresh explicit
                // route-list responses fence both sides of this destructive
                // shared-controller operation; cached fff4 state is never a
                // precondition or confirmation.
                if (!requestQuantumRouteListLocked(expectedQuantumBoard)) return false
                if (!writeQuantumFrames(
                        listOf(QuantumBoardPacketEncoder.turnOffAll()),
                        expectedQuantumBoard,
                    )
                ) {
                    return false
                }
                if (!requestQuantumRouteListLocked(expectedQuantumBoard)) return false
                return _quantumControllerState.value.players.isEmpty()
            }
            return writeChunks(encoder.encodeClear())
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            // Arm the idle-disconnect timer with the post-send state.
            resetIdleTimer()
        }
    }

    /**
     * Disconnect from the board and release GATT resources.
     *
     * Flow: disconnect() → BLE stack processes → STATE_DISCONNECTED callback →
     *       delay (Android <12) → refresh() → close() → pendingClose completes.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "disconnect() called (SDK=${Build.VERSION.SDK_INT})")
        connectJob?.cancel()
        connectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        // A repeated disconnect while the previous GATT is already retiring
        // must not cancel its only remaining force-close path.
        val activeGatt = gatt
        when (classifyGattDisconnectRequest(
            activeGattPresent = activeGatt != null,
            retiringGattPresent = userDisconnectGatt != null,
        )) {
            GattDisconnectRequestRole.PRESERVE_RETIRING -> Unit
            GattDisconnectRequestRole.RETIRE_ACTIVE,
            GattDisconnectRequestRole.NO_GATT -> {
                closeSafetyJob?.cancel()
                closeSafetyJob = null
            }
        }
        cancelQuantumGattOperations()

        run {
            val pending = pendingWrite
            pendingWrite = null
            pending?.result?.complete(BluetoothGatt.GATT_FAILURE)
        }

        gatt = null
        if (activeGatt != null) userDisconnectGatt = activeGatt
        writeCharacteristic = null
        currentBoard = null
        _connectedBoardDescriptor.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedBoardName.value = null
        _connectedBoardBrand.value = null
        _connectedQuantumModel.value = null
        serviceDiscoveryGatt = null
        serviceDiscoveryHandledGatt = null
        quantumMtuGatt = null

        if (activeGatt != null) {
            userDisconnecting = true
            pendingClose = CompletableDeferred()
            val disconnectQueued = runCatching {
                activeGatt.disconnect()
                true
            }.getOrElse { error ->
                Log.w(TAG, "GATT disconnect() threw; force-closing", error)
                false
            }
            if (!disconnectQueued) {
                if (userDisconnectGatt === activeGatt) userDisconnectGatt = null
                closeGatt(activeGatt)
                userDisconnecting = false
                return
            }
            Log.d(TAG, "GATT disconnect() called, waiting for callback")

            // Safety timeout: if STATE_DISCONNECTED callback doesn't fire,
            // force-close the GATT to prevent leaked client slots.
            closeSafetyJob = scope.launch {
                delay(CLOSE_SAFETY_TIMEOUT_MS)
                if (userDisconnectGatt === activeGatt) userDisconnectGatt = null
                if (!isGattClosed(activeGatt)) {
                    Log.w(TAG, "STATE_DISCONNECTED callback didn't fire, force-closing GATT")
                    // Clear the retiring identity before closeGatt completes
                    // pendingClose and allows a new connection to begin. A late
                    // callback from this object is stale and cannot cancel a
                    // later connection's safety job.
                    closeGatt(activeGatt)
                }
                userDisconnecting = false
            }
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Suspends until any pending GATT close operation completes, with a safety timeout.
     *
     * Android suppresses connectable scan results for devices whose GATT handle is still
     * open (pending close). Callers that need to scan for a recently-disconnected board
     * should await this before starting a BLE scan.
     *
     * On Android 9, if the STATE_DISCONNECTED callback is never delivered (e.g. because
     * R8 obfuscated the callback class), the safety timeout in disconnect() should fire
     * after 5s. We add an additional safety timeout here to prevent hanging forever.
     */
    suspend fun awaitGattClosed() {
        val deferred = pendingClose ?: return
        val result = withTimeoutOrNull(CLOSE_SAFETY_TIMEOUT_MS + 2000) { deferred.await() }
        if (result == null) {
            Log.w(TAG, "awaitGattClosed timed out — forcing pendingClose completion")
            deferred.complete(Unit)
        }
    }
}
