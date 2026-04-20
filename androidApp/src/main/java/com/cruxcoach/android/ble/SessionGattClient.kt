package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class SessionClientState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * BLE GATT Client for participants joining a Session Queue.
 * Connects to the host's GATT server, subscribes to notifications,
 * and sends commands.
 *
 * Follows the same patterns as [AuroraBleConnection]:
 * - Mutex + CompletableDeferred for write flow control
 * - Main-thread GATT operations
 * - Explicit disconnect/close lifecycle
 */
class SessionGattClient(private val context: Context) {

    companion object {
        private const val TAG = "SessionGattClient"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(SessionClientState.DISCONNECTED)
    val connectionState: StateFlow<SessionClientState> = _connectionState.asStateFlow()

    // Incoming notifications from host. Buffers are sized to absorb a
    // multi-device burst (e.g. 7 participants joining a session
    // simultaneously) so tryEmit never silently drops a BLE notification.
    private val _queueEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val queueEvents: SharedFlow<ByteArray> = _queueEvents.asSharedFlow()

    private val _sessionInfoUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val sessionInfoUpdates: SharedFlow<ByteArray> = _sessionInfoUpdates.asSharedFlow()

    private val _currentClimbUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val currentClimbUpdates: SharedFlow<ByteArray> = _currentClimbUpdates.asSharedFlow()

    private val _participantListUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val participantListUpdates: SharedFlow<ByteArray> = _participantListUpdates.asSharedFlow()

    private val _queueStateUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val queueStateUpdates: SharedFlow<ByteArray> = _queueStateUpdates.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var writeDeferred: CompletableDeferred<Int>? = null
    private var readDeferred: CompletableDeferred<Int>? = null
    private var descriptorDeferred: CompletableDeferred<Int>? = null
    private var timeoutJob: Job? = null
    private val writeMutex = Mutex()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Connection error: status=0x${status.toString(16)}")
                        cleanupGatt(gatt)
                        _connectionState.value = SessionClientState.DISCONNECTED
                        return
                    }
                    Log.d(TAG, "Connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=0x${status.toString(16)})")
                    // Complete all three deferred slots so suspended read/
                    // descriptor coroutines return immediately on peer
                    // disconnect instead of waiting out their 5s timeout.
                    writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    readDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    descriptorDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    cleanupGatt(gatt)
                    _connectionState.value = SessionClientState.DISCONNECTED
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                cleanupGatt(gatt)
                _connectionState.value = SessionClientState.DISCONNECTED
                return
            }

            val service = gatt.getService(SessionGattUuids.SERVICE)
            if (service == null) {
                Log.w(TAG, "Session service not found")
                cleanupGatt(gatt)
                _connectionState.value = SessionClientState.DISCONNECTED
                return
            }

            cmdCharacteristic = service.getCharacteristic(SessionGattUuids.QUEUE_COMMAND)

            // Request larger MTU for queue state reads
            gatt.requestMtu(512)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            // Subscribe to notifications on all event/state characteristics
            scope.launch {
                subscribeToNotifications(gatt)
                _connectionState.value = SessionClientState.CONNECTED
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            val uuidShort = characteristic.uuid.toString().substring(4, 8)
            Log.d(TAG, "onCharacteristicChanged: uuid=...$uuidShort, ${data.size} bytes" +
                if (data.isNotEmpty()) ", first=0x${"%02X".format(data[0])}" else "")
            val payload = data.copyOf()
            val (flow, flowName) = when (characteristic.uuid) {
                SessionGattUuids.QUEUE_EVENT -> _queueEvents to "queueEvents"
                SessionGattUuids.QUEUE_STATE -> _queueStateUpdates to "queueStateUpdates"
                SessionGattUuids.SESSION_INFO -> {
                    if (payload.isNotEmpty()) {
                        val count = payload[0].toInt() and 0xFF
                        Log.d(TAG, "SESSION_INFO notification: participantCount=$count " +
                            "(0=session-ended sentinel)")
                    }
                    _sessionInfoUpdates to "sessionInfoUpdates"
                }
                SessionGattUuids.CURRENT_CLIMB -> _currentClimbUpdates to "currentClimbUpdates"
                SessionGattUuids.PARTICIPANT_LIST -> _participantListUpdates to "participantListUpdates"
                else -> return
            }
            if (!flow.tryEmit(payload)) {
                Log.w(TAG, "$flowName buffer full — dropping ${payload.size}B notification")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            writeDeferred?.complete(status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            // Dispatch data like a notification, then signal the read deferred
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicChanged(gatt, characteristic)
            }
            readDeferred?.complete(status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            descriptorDeferred?.complete(status)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect() called, currentState=${_connectionState.value}, addr=${device.address}")
        if (_connectionState.value != SessionClientState.DISCONNECTED) {
            Log.d(TAG, "Cleaning up previous connection before retry")
            disconnect()
        }
        _connectionState.value = SessionClientState.CONNECTING

        val newGatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainHandler
        )

        if (newGatt == null) {
            Log.e(TAG, "connectGatt returned null")
            _connectionState.value = SessionClientState.DISCONNECTED
            return
        }

        gatt = newGatt

        // Connection timeout — cancel any previous timeout to avoid stale firings
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_connectionState.value == SessionClientState.CONNECTING) {
                Log.w(TAG, "Connection timeout")
                disconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "disconnect() called, currentState=${_connectionState.value}, " +
            "gatt=${gatt != null}, cmdChar=${cmdCharacteristic != null}")
        timeoutJob?.cancel()
        timeoutJob = null
        writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        readDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        descriptorDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        val g = gatt
        gatt = null
        cmdCharacteristic = null
        _connectionState.value = SessionClientState.DISCONNECTED
        if (g != null) {
            Log.d(TAG, "disconnect(): calling g.disconnect()")
            g.disconnect()
            // Delay close() so the server's onConnectionStateChange(DISCONNECTED) can fire.
            // Calling close() immediately after disconnect() suppresses the server callback
            // on many Android versions.
            mainHandler.postDelayed({
                Log.d(TAG, "disconnect(): delayed close() executing")
                try { g.close() } catch (_: Exception) {}
            }, 500)
        } else {
            Log.d(TAG, "disconnect(): gatt was already null, nothing to disconnect")
        }
    }

    suspend fun sendCommand(command: ByteArray): Boolean = writeMutex.withLock {
        val char = cmdCharacteristic
        if (char == null) {
            Log.w(TAG, "sendCommand: cmdCharacteristic is null (${command.size} bytes, " +
                "opcode=0x${if (command.isNotEmpty()) "%02X".format(command[0]) else "??"})")
            return false
        }
        val currentGatt = gatt
        if (currentGatt == null) {
            Log.w(TAG, "sendCommand: gatt is null (${command.size} bytes, " +
                "opcode=0x${if (command.isNotEmpty()) "%02X".format(command[0]) else "??"})")
            return false
        }

        Log.d(TAG, "sendCommand: ${command.size} bytes, " +
            "opcode=0x${if (command.isNotEmpty()) "%02X".format(command[0]) else "??"}")

        val deferred = CompletableDeferred<Int>()
        writeDeferred = deferred

        @SuppressLint("MissingPermission")
        char.value = command
        @SuppressLint("MissingPermission")
        val queued = currentGatt.writeCharacteristic(char)
        if (!queued) {
            Log.w(TAG, "sendCommand: writeCharacteristic returned false (not queued)")
            writeDeferred = null
            return false
        }

        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        writeDeferred = null
        val success = status == BluetoothGatt.GATT_SUCCESS
        Log.d(TAG, "sendCommand: complete, status=$status, success=$success")
        return success
    }

    /** Read initial state after connecting. Serialized — waits for each read callback. */
    @SuppressLint("MissingPermission")
    suspend fun readInitialState() {
        val g = gatt ?: return
        val service = g.getService(SessionGattUuids.SERVICE) ?: return
        val uuids = listOf(
            SessionGattUuids.SESSION_INFO,
            SessionGattUuids.QUEUE_STATE,
            SessionGattUuids.CURRENT_CLIMB,
            SessionGattUuids.PARTICIPANT_LIST
        )
        for (uuid in uuids) {
            val char = service.getCharacteristic(uuid) ?: continue
            val deferred = CompletableDeferred<Int>()
            readDeferred = deferred
            val queued = g.readCharacteristic(char)
            if (queued) {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
            }
            readDeferred = null
        }
        Log.d(TAG, "Initial state read complete")
    }

    // --- Internal ---

    @SuppressLint("MissingPermission")
    private suspend fun subscribeToNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(SessionGattUuids.SERVICE) ?: return
        val notifyUuids = listOf(
            SessionGattUuids.QUEUE_EVENT,
            SessionGattUuids.QUEUE_STATE,
            SessionGattUuids.SESSION_INFO,
            SessionGattUuids.CURRENT_CLIMB,
            SessionGattUuids.PARTICIPANT_LIST
        )
        for (uuid in notifyUuids) {
            val char = service.getCharacteristic(uuid) ?: continue
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(SessionGattUuids.CCCD) ?: continue
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val deferred = CompletableDeferred<Int>()
            descriptorDeferred = deferred
            val queued = gatt.writeDescriptor(cccd)
            if (queued) {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
            }
            descriptorDeferred = null
        }
        Log.d(TAG, "Subscribed to ${notifyUuids.size} notifications")
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt(g: BluetoothGatt) {
        try { g.close() } catch (e: Exception) { Log.w(TAG, "Error closing GATT", e) }
        if (gatt === g) {
            gatt = null
            cmdCharacteristic = null
        }
    }
}
