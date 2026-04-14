package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cruxcoach.domain.board.AuroraPacketEncoder
import com.cruxcoach.domain.board.BoardHold
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
class AuroraBleConnection(private val context: Context) {

    private companion object {
        const val TAG = "AuroraBleConnection"
        const val WRITE_TIMEOUT_MS = 5000L
        const val CLOSE_SAFETY_TIMEOUT_MS = 5000L
        const val CONNECTION_TIMEOUT_MS = 30_000L

        // Timing delays for Android <12 BLE stack quirks
        const val DELAY_CLOSE_AFTER_DISCONNECT_MS = 300L
        const val DELAY_RECONNECT_LEGACY_MS = 1000L
        const val DELAY_RECONNECT_MODERN_MS = 200L
        const val DELAY_SCAN_SETTLE_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedBoardName = MutableStateFlow<String?>(null)
    val connectedBoardName: StateFlow<String?> = _connectedBoardName.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var encoder: AuroraPacketEncoder = AuroraPacketEncoder(3)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var disconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var closeSafetyJob: Job? = null
    private var connectJob: Job? = null
    var autoDisconnectMinutes: Int = 0
    /** When true, the idle timer is suppressed (e.g. during an active shared session). */
    var suppressAutoDisconnect: Boolean = false

    // Write flow control: signaled by onCharacteristicWrite callback
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
        if (suppressAutoDisconnect) return
        val minutes = autoDisconnectMinutes
        if (minutes > 0 && _connectionState.value != ConnectionState.DISCONNECTED) {
            disconnectJob = scope.launch {
                delay(minutes * 60_000L)
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
                        finalizeDisconnect(status)
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
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    writeDeferred = null

                    // On Android <12, delay before close() — the BLE stack needs time
                    // after STATE_DISCONNECTED to fully release internal resources.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        mainHandler.postDelayed({
                            closeGatt(gatt)
                            finalizeDisconnect(status)
                        }, DELAY_CLOSE_AFTER_DISCONNECT_MS)
                    } else {
                        closeGatt(gatt)
                        finalizeDisconnect(status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(AuroraBleUuids.DATA_TRANSFER_SERVICE)
                writeCharacteristic = service?.getCharacteristic(AuroraBleUuids.DATA_TRANSFER_CHAR)
                if (writeCharacteristic != null) {
                    // NOW the GATT is fully ready — set CONNECTED so downstream
                    // auto-send and advertising see a usable connection.
                    _connectionState.value = ConnectionState.CONNECTED
                    resetIdleTimer()
                    onRestartScannersAfterConnect?.invoke()
                } else {
                    Log.w(TAG, "DATA_TRANSFER_CHAR not found in service")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            } else {
                Log.w(TAG, "onServicesDiscovered failed: status=$status")
                _connectionState.value = ConnectionState.DISCONNECTED
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
    @SuppressLint("MissingPermission")
    fun connect(board: DiscoveredBoard) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        userDisconnecting = false
        gattClosed = false
        _connectionState.value = ConnectionState.CONNECTING
        _connectedBoardName.value = board.displayName
        encoder = AuroraPacketEncoder(board.apiLevel)

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

            // Abort if state changed during the wait
            if (_connectionState.value != ConnectionState.CONNECTING) {
                _connectedBoardName.value = null
                return@launch
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
            Log.d(TAG, "connectGatt() for ${board.address} (SDK=${Build.VERSION.SDK_INT})")
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
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedBoardName.value = null
                onRestartScannersAfterConnect?.invoke()
                return@launch
            }

            gatt = newGatt

            // Connection timeout
            connectionTimeoutJob = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                if (_connectionState.value == ConnectionState.CONNECTING) {
                    Log.w(TAG, "Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
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
        roleColors: Map<Int, Int>? = null
    ): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        lastHolds = holds
        lastPlacementToLed = placementToLed

        _connectionState.value = ConnectionState.SENDING
        try {
            val chunks = if (roleColors != null) {
                val holdPairs = holds.mapNotNull { hold ->
                    val led = placementToLed[hold.placementId] ?: return@mapNotNull null
                    led to (roleColors[hold.roleId] ?: AuroraPacketEncoder.roleToColor(hold.roleId))
                }
                encoder.encodeClimb(holdPairs)
            } else {
                encoder.encodeClimbFromHolds(holds, placementToLed)
            }

            val success = writeChunks(chunks)
            resetIdleTimer()
            return success
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }
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

    suspend fun clearBoard(): Boolean = writeMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        _connectionState.value = ConnectionState.SENDING
        try {
            val chunks = encoder.encodeClear()
            val success = writeChunks(chunks)
            resetIdleTimer()
            return success
        } finally {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
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

        writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        writeDeferred = null

        val g = gatt
        gatt = null
        writeCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedBoardName.value = null

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
