package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * GATT connection event emitted by the server.
 */
sealed class GattConnectionEvent {
    data class Connected(val deviceAddress: String) : GattConnectionEvent()
    data class Disconnected(val deviceAddress: String) : GattConnectionEvent()
}

/**
 * GATT command received from a connected client.
 */
data class GattCommand(val deviceAddress: String, val data: ByteArray)

/**
 * BLE GATT Server for the Session Queue feature.
 * The host runs this server; participants connect as GATT clients.
 *
 * Exposes [commands] and [connectionEvents] as Flows instead of callbacks.
 * Characteristics are read via provider lambdas set by the SessionQueueManager.
 */
class SessionGattServer(private val context: Context) {

    companion object {
        private const val TAG = "SessionGattServer"
        private const val MAX_CONNECTED_DEVICES = 7
        private const val LIVENESS_CHECK_INTERVAL_MS = 10_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var livenessJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Buffer sized to absorb a burst from MAX_CONNECTED_DEVICES participants
    // issuing queue commands simultaneously — tryEmit on the BLE binder
    // thread must not silently drop user actions.
    private val _commands = MutableSharedFlow<GattCommand>(extraBufferCapacity = 128)
    val commands: SharedFlow<GattCommand> = _commands.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 32)
    val connectionEvents: SharedFlow<GattConnectionEvent> = _connectionEvents.asSharedFlow()

    // Tracks connected devices and their CCCD subscriptions.
    // All accesses must be synchronized on [lock] — BLE callbacks run on the binder thread.
    private val lock = Any()
    private val connectedDevices = mutableSetOf<String>()
    private val subscribedDevices = mutableMapOf<UUID, MutableSet<String>>()

    // Serializes notifyAll — pre-Android-13 notifyCharacteristicChanged reads
    // from the shared characteristic.value object, so concurrent writers can
    // corrupt in-flight notifications. Android 13+ has a value-parameter
    // overload that sidesteps this; we still serialize for simplicity.
    private val notifyLock = Any()

    // Data providers set by SessionQueueManager
    var sessionInfoProvider: (() -> ByteArray)? = null
    var currentClimbProvider: (() -> ByteArray)? = null
    var queueStateProvider: (() -> ByteArray)? = null
    var participantListProvider: (() -> ByteArray)? = null

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) {
                        if (connectedDevices.size >= MAX_CONNECTED_DEVICES) {
                            Log.w(TAG, "Max devices reached, rejecting $address")
                            gattServer?.cancelConnection(device)
                            return
                        }
                        connectedDevices.add(address)
                        Log.d(TAG, "Device connected: $address (${connectedDevices.size} total)")
                    }
                    if (!_connectionEvents.tryEmit(GattConnectionEvent.Connected(address))) {
                        Log.w(TAG, "connectionEvents buffer full — dropping Connected($address)")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) {
                        connectedDevices.remove(address)
                        subscribedDevices.values.forEach { it.remove(address) }
                        Log.d(TAG, "Device disconnected: $address (${connectedDevices.size} remaining)")
                    }
                    if (!_connectionEvents.tryEmit(GattConnectionEvent.Disconnected(address))) {
                        Log.w(TAG, "connectionEvents buffer full — dropping Disconnected($address)")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = when (characteristic.uuid) {
                SessionGattUuids.SESSION_INFO -> sessionInfoProvider?.invoke()
                SessionGattUuids.CURRENT_CLIMB -> currentClimbProvider?.invoke()
                SessionGattUuids.QUEUE_STATE -> queueStateProvider?.invoke()
                SessionGattUuids.PARTICIPANT_LIST -> participantListProvider?.invoke()
                else -> null
            }

            Log.d(TAG, "Read request: uuid=${characteristic.uuid} offset=$offset dataSize=${data?.size} from=${device.address}")

            if (data == null) {
                Log.w(TAG, "No data provider for ${characteristic.uuid}")
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }

            // Support GATT Long Reads: respond with data starting at offset
            if (offset >= data.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            } else {
                val chunk = data.copyOfRange(offset, data.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == SessionGattUuids.QUEUE_COMMAND && value != null) {
                Log.d(TAG, "Write request: QUEUE_COMMAND ${value.size} bytes from ${device.address}")
                if (!_commands.tryEmit(GattCommand(device.address, value))) {
                    Log.w(TAG, "commands buffer full — dropping ${value.size}B from ${device.address}")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == SessionGattUuids.CCCD) {
                val charUuid = descriptor.characteristic.uuid
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                synchronized(lock) {
                    if (enabled) {
                        subscribedDevices.getOrPut(charUuid) { mutableSetOf() }.add(device.address)
                        Log.d(TAG, "${device.address} subscribed to $charUuid")
                    } else {
                        subscribedDevices[charUuid]?.remove(device.address)
                        Log.d(TAG, "${device.address} unsubscribed from $charUuid")
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_isRunning.value) return true
        val manager = bluetoothManager ?: return false

        val server = manager.openGattServer(context, gattCallback)
        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }
        gattServer = server

        val service = BluetoothGattService(
            SessionGattUuids.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Readable + Notifiable characteristics
        val readNotifyChars = listOf(
            SessionGattUuids.SESSION_INFO,
            SessionGattUuids.CURRENT_CLIMB,
            SessionGattUuids.QUEUE_STATE,
            SessionGattUuids.QUEUE_EVENT,
            SessionGattUuids.PARTICIPANT_LIST
        )
        for (uuid in readNotifyChars) {
            val char = BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            char.addDescriptor(BluetoothGattDescriptor(
                SessionGattUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            service.addCharacteristic(char)
        }

        // Writable command characteristic
        val cmdChar = BluetoothGattCharacteristic(
            SessionGattUuids.QUEUE_COMMAND,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(cmdChar)

        val added = server.addService(service)
        if (!added) {
            Log.e(TAG, "Failed to add GATT service")
            server.close()
            gattServer = null
            return false
        }

        _isRunning.value = true

        // Periodic liveness check: detect stale entries when onConnectionStateChange(DISCONNECTED)
        // doesn't fire (e.g., device goes out of range without graceful disconnect)
        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive) {
                delay(LIVENESS_CHECK_INTERVAL_MS)
                checkForStaleConnections()
            }
        }

        Log.d(TAG, "GATT server started")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_isRunning.value) return
        val deviceCount = synchronized(lock) { connectedDevices.size }
        Log.d(TAG, "stop() called, $deviceCount connected devices")
        livenessJob?.cancel()
        livenessJob = null
        synchronized(lock) {
            if (connectedDevices.isNotEmpty()) {
                Log.d(TAG, "stop(): clearing tracked devices: ${connectedDevices.toList()}")
            }
            connectedDevices.clear()
            subscribedDevices.clear()
        }
        gattServer?.close()
        gattServer = null
        _isRunning.value = false
        Log.d(TAG, "GATT server stopped")
    }

    /**
     * Notify all subscribed devices of a characteristic change.
     * Used for delta events (QUEUE_EVENT) and state updates.
     */
    @SuppressLint("MissingPermission")
    fun notifyAll(charUuid: UUID, value: ByteArray) {
        val server = gattServer
        if (server == null) {
            Log.w(TAG, "notifyAll: gattServer is null, skipping (uuid=...${charUuid.toString().substring(4, 8)})")
            return
        }
        val service = server.getService(SessionGattUuids.SERVICE)
        if (service == null) {
            Log.w(TAG, "notifyAll: service not found")
            return
        }
        val char = service.getCharacteristic(charUuid)
        if (char == null) {
            Log.w(TAG, "notifyAll: characteristic not found (uuid=...${charUuid.toString().substring(4, 8)})")
            return
        }

        val subscribers = synchronized(lock) {
            subscribedDevices[charUuid]?.toList()
        } ?: return
        if (subscribers.isEmpty()) {
            Log.d(TAG, "notifyAll: no subscribers for ...${charUuid.toString().substring(4, 8)}")
            return
        }

        Log.d(TAG, "notifyAll: uuid=...${charUuid.toString().substring(4, 8)}, " +
            "${value.size} bytes, ${subscribers.size} subscribers: $subscribers")
        val manager = bluetoothManager ?: return
        // Serialize the shared characteristic.value assignment + iteration so
        // concurrent notifyAll callers can't overwrite bytes mid-send.
        synchronized(notifyLock) {
            char.value = value
            for (address in subscribers) {
                val device = manager.adapter.getRemoteDevice(address)
                try {
                    val sent = server.notifyCharacteristicChanged(device, char, false)
                    if (!sent) Log.w(TAG, "notifyAll: notifyCharacteristicChanged returned false for $address")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify $address for $charUuid", e)
                }
            }
        }
    }

    fun getConnectedCount(): Int = synchronized(lock) { connectedDevices.size }

    /**
     * Proactively disconnect a device from the server side.
     * Used after processing a Leave command to ensure clean teardown even if
     * the client's disconnect doesn't trigger onConnectionStateChange.
     */
    @SuppressLint("MissingPermission")
    fun cancelDevice(deviceAddress: String) {
        val server = gattServer ?: return
        val manager = bluetoothManager ?: return
        try {
            val device = manager.adapter.getRemoteDevice(deviceAddress)
            server.cancelConnection(device)
            Log.d(TAG, "cancelConnection issued for $deviceAddress")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel connection for $deviceAddress", e)
        }
    }

    /**
     * Compares our tracked [connectedDevices] against the OS-reported connected devices.
     * Emits [GattConnectionEvent.Disconnected] for any stale entries the OS no longer knows about.
     */
    @SuppressLint("MissingPermission")
    private fun checkForStaleConnections() {
        val manager = bluetoothManager ?: return
        val osConnected = try {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
                .map { it.address }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query connected devices", e)
            return
        }

        val tracked = synchronized(lock) { connectedDevices.toSet() }
        if (tracked.isNotEmpty() || osConnected.isNotEmpty()) {
            Log.d(TAG, "livenessCheck: tracked=$tracked, os=$osConnected")
        }

        val stale = synchronized(lock) {
            val staleAddresses = connectedDevices.filter { it !in osConnected }
            for (address in staleAddresses) {
                connectedDevices.remove(address)
                subscribedDevices.values.forEach { it.remove(address) }
            }
            staleAddresses
        }

        for (address in stale) {
            Log.w(TAG, "Stale connection detected, emitting disconnect: $address")
            _connectionEvents.tryEmit(GattConnectionEvent.Disconnected(address))
        }
    }
}
