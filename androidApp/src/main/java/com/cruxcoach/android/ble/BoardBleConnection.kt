package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cruxcoach.android.boardcell.BoardCellScopeRegistry
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class BoardBleConnection(private val context: Context) {
    /** Process-wide safety gate installed by BoardCellManager. This sits at the
     * connection boundary so auto-connect and non-UI callers cannot bypass the
     * single-controller rule. */
    @Volatile var connectionGuard: ((DiscoveredBoard) -> Boolean)? = null

    private companion object {
        const val TAG = "BoardBleConnection"
        const val WRITE_TIMEOUT_MS = 5000L
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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * A disabled adapter invalidates every GATT link immediately. Waiting for
     * the ordinary board idle timer leaves [connectionState] at CONNECTED for
     * up to a minute even though the kernel-side link no longer exists. React
     * while the adapter is turning off, when a graceful GATT close is still
     * most likely to reach the stack, and publish DISCONNECTED synchronously.
     *
     * This receiver intentionally lives for the process lifetime: the
     * connection is a Hilt singleton backed by the application context.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val adapterState = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR,
            )
            if (adapterState != BluetoothAdapter.STATE_TURNING_OFF &&
                adapterState != BluetoothAdapter.STATE_OFF
            ) return
            if (_connectionState.value == ConnectionState.DISCONNECTED && gatt == null) return
            Log.i(TAG, "Bluetooth disabled — immediately invalidating board GATT")
            disconnect()
        }
    }

    init {
        context.applicationContext.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

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

    // Localized reason (string-res id) why the last connect attempt was torn
    // down at service discovery — currently only the unsupported RedBear-UART
    // MoonBoard LED-kit generation. Survives the disconnect (so the sheet can
    // show it on the scan list the user lands back on) and is cleared on the
    // next connect attempt. Null = no known failure reason.
    private val _connectFailureReason = MutableStateFlow<Int?>(null)
    val connectFailureReason: StateFlow<Int?> = _connectFailureReason.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var encoder: BoardPacketEncoder = BoardPacketEncoder(3)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var disconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var closeSafetyJob: Job? = null
    private var connectJob: Job? = null

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
    /** True while a mesh/session/relay owns the physical board connection. */
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
    private var writeDeferred: CompletableDeferred<Int>? = null
    private val writeMutex = Mutex()

    // Track whether disconnect() was called by us (vs. remote disconnect).
    @Volatile
    private var userDisconnecting = false

    // Tracks whether close() has been called for the current GATT to prevent double-close.
    @Volatile
    private var gattClosed = false

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

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=0x${status.toString(16)} newState=$newState userDisc=$userDisconnecting SDK=${Build.VERSION.SDK_INT}")

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
                    if (_connectedBoardBrand.value == BoardBrand.QUANTUM && Build.VERSION.SDK_INT >= 21) {
                        // eWalls 2.0.14 negotiates 512 first (then 247/185 on
                        // failure). Writes remain conservatively fragmented at
                        // 20 bytes, so an MTU callback is an optimization only.
                        gatt.requestMtu(512)
                    }
                    // Bump BLE connection priority before service discovery
                    // — drops the connection interval from the default
                    // ~50 ms down to ~7.5–15 ms, which makes the rest of
                    // the connect (service discovery + every subsequent
                    // GATT write of a send-frame) 2-4× faster.
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        // Legacy stacks can stall discovery (or fail it with
                        // 129/133) when it races the connection-parameter
                        // update above — give the update a beat first. The
                        // attempt timeout keeps covering this window.
                        mainHandler.postDelayed({
                            if (_connectionState.value == ConnectionState.CONNECTING && !gattClosed) {
                                gatt.discoverServices()
                            }
                        }, DELAY_PRE_DISCOVERY_LEGACY_MS)
                    } else {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    // Snapshot-before-null so a concurrent writer that just
                    // installed a new deferred doesn't get its completion lost.
                    val d = writeDeferred
                    writeDeferred = null
                    d?.complete(BluetoothGatt.GATT_FAILURE)

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
            // Log.i so the R8 Log.d/v stripping rule doesn't erase the diagnostic marker.
            Log.i(TAG, "onServicesDiscovered status=$status services=${gatt.services.size}")
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BoardBleUuids.DATA_TRANSFER_SERVICE)
                writeCharacteristic = if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                    val quantumService = gatt.getService(BoardBleUuids.QUANTUM_SERVICE)
                        ?: gatt.getService(BoardBleUuids.QUANTUM_SERVICE_OLD)
                    quantumService?.getCharacteristic(BoardBleUuids.QUANTUM_WRITE_CHAR)?.also {
                        it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }.also {
                        val notify = quantumService?.getCharacteristic(BoardBleUuids.QUANTUM_NOTIFY_CHAR)
                        if (notify != null) {
                            gatt.setCharacteristicNotification(notify, true)
                            notify.getDescriptor(BoardBleUuids.CLIENT_CHARACTERISTIC_CONFIG)?.let { descriptor ->
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                } else service?.getCharacteristic(BoardBleUuids.DATA_TRANSFER_CHAR)
                if (writeCharacteristic != null) {
                    // NOW the GATT is fully ready — set CONNECTED so downstream
                    // auto-send and advertising see a usable connection.
                    Log.i(TAG, "GATT ready, state→CONNECTED (writes can start)")
                    _connectionState.value = ConnectionState.CONNECTED
                    resetIdleTimer()
                    onRestartScannersAfterConnect?.invoke()
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

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite failed: status=0x${status.toString(16)}")
            }
            writeDeferred?.complete(status)
        }

    }

    /** Finalize state after disconnect callback (or error). */
    private fun finalizeDisconnect(status: Int) {
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
        currentBoard = null
        _connectedBoardDescriptor.value = null
        gatt = null
        writeCharacteristic = null
    }

    /**
     * Close a GATT object: refresh() → close() → null.
     * Guards against double-close via [gattClosed] flag.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        if (gattClosed) {
            Log.d(TAG, "closeGatt: already closed, skipping")
            pendingClose?.complete(Unit)
            return
        }
        gattClosed = true

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
        if (connectionGuard?.invoke(board) == false) {
            Log.w(TAG, "Board connection blocked by active mesh controller policy")
            return
        }
        BoardCellScopeRegistry.select(board)
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        attemptBudget = maxAttempts.coerceIn(1, MAX_CONNECT_ATTEMPTS)
        userDisconnecting = false
        gattClosed = false
        _connectionState.value = ConnectionState.CONNECTING
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
        gattClosed = false

        // Abort if state changed during the wait
        if (_connectionState.value != ConnectionState.CONNECTING) {
            _connectedBoardName.value = null
            _connectedBoardBrand.value = null
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
                currentBoard = null
                _connectedBoardDescriptor.value = null
                onRestartScannersAfterConnect?.invoke()
            }
            return
        }

        gatt = newGatt

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
        writeDeferred = deferred

        characteristic.value = chunk
        val queued = currentGatt.writeCharacteristic(characteristic)
        if (!queued) {
            Log.w(TAG, "writeCharacteristic returned false (not queued)")
            writeDeferred = null
            delay(100)
            return false
        }

        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        writeDeferred = null

        if (status == null) {
            Log.w(TAG, "Write timed out after ${WRITE_TIMEOUT_MS}ms")
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
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        lastHolds = holds
        lastPlacementToLed = placementToLed

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
                    userId = QuantumBoardPacketEncoder.ZERO_UUID,
                    diodes = mapped,
                )
                if (!writeQuantumFrames(transition.take(1))) return false
                delay(50)
                return writeQuantumFrames(transition.drop(1))
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

    private suspend fun writeQuantumFrames(frames: List<ByteArray>): Boolean {
        for ((index, frame) in frames.withIndex()) {
            // 2.0.14 passes 15 as Android's native write fragment size.
            // This is transport fragmentation, independent of the 92-diode
            // logical command limit enforced by the encoder.
            val transportChunks = frame.toList().chunked(15).map { it.toByteArray() }
            if (!writeChunks(transportChunks)) return false
            if (index != frames.lastIndex) delay(100)
        }
        return true
    }

    suspend fun resendWithColors(roleColors: Map<Int, Int>): Boolean {
        val holds = lastHolds ?: return false
        val ledMap = lastPlacementToLed ?: return false
        return sendClimb(holds, ledMap, roleColors)
    }

    suspend fun sendRawChunks(chunks: List<ByteArray>): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        return writeChunks(chunks)
    }

    /**
     * Encode [leds] ((position, colourByte) pairs) with the CONNECTED board's
     * encoder — the apiLevel/ledsPerHold configured in [connect] — and write
     * them. Used by the easter animation so an @2 legacy board gets v2
     * packets instead of frames from a hardcoded API-3 encoder.
     */
    suspend fun sendRawLeds(leds: List<Pair<Int, Int>>): Boolean =
        sendRawChunks(encoder.encodeClimb(leds))

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
        if (_connectionState.value != ConnectionState.CONNECTED) return false

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

    suspend fun clearBoard(): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        _connectionState.value = ConnectionState.SENDING
        // Park any pending idle-disconnect so it can't fire mid-send.
        // Re-armed from the finally below once we flip back to CONNECTED.
        disconnectJob?.cancel()
        try {
            val chunks = if (_connectedBoardBrand.value == BoardBrand.QUANTUM) {
                QuantumBoardPacketEncoder.turnOffAll().toList()
                    .chunked(BoardPacketEncoder.BLE_MTU).map { it.toByteArray() }
            } else encoder.encodeClear()
            val success = writeChunks(chunks)
            return success
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
        closeSafetyJob?.cancel()

        run {
            val d = writeDeferred
            writeDeferred = null
            d?.complete(BluetoothGatt.GATT_FAILURE)
        }

        val g = gatt
        gatt = null
        writeCharacteristic = null
        currentBoard = null
        _connectedBoardDescriptor.value = null
        BoardCellScopeRegistry.clearSelection()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedBoardName.value = null
        _connectedBoardBrand.value = null

        if (g != null) {
            userDisconnecting = true
            gattClosed = false
            pendingClose = CompletableDeferred()
            g.disconnect()
            Log.d(TAG, "GATT disconnect() called, waiting for callback")

            // Safety timeout: if STATE_DISCONNECTED callback doesn't fire,
            // force-close the GATT to prevent leaked client slots.
            closeSafetyJob = scope.launch {
                delay(CLOSE_SAFETY_TIMEOUT_MS)
                if (!gattClosed) {
                    Log.w(TAG, "STATE_DISCONNECTED callback didn't fire, force-closing GATT")
                    closeGatt(g)
                    userDisconnecting = false
                }
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
