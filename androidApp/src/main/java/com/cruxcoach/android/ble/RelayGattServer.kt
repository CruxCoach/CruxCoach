package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.cruxcoach.domain.relay.CompleteClimb
import com.cruxcoach.domain.relay.RelayFrameReassembler
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

/** A complete climb an official-app client wrote to the emulated board char. */
data class RelayInboundClimb(val deviceAddress: String, val climb: CompleteClimb)

/**
 * The board-emulation GATT server of CruxRelay (FEAT-044).
 *
 * Mirrors the write-only board: exposes the Nordic UART service
 * ([BoardBleUuids.DATA_TRANSFER_SERVICE] = 6E400001) with a single writable
 * characteristic ([BoardBleUuids.DATA_TRANSFER_CHAR] = 6E400002,
 * WRITE | WRITE_NO_RESPONSE) and NO read/notify/CCCD. The official Kilter app
 * connects here (it found us by the 4488B571 advertising UUID) and writes climb
 * packets exactly as it would to a real board.
 *
 * Inbound writes are reassembled PER CLIENT ([RelayFrameReassembler]) into
 * complete climbs and emitted on [climbs]; [CruxRelayManager] forwards them
 * byte-faithfully to the real board (and optionally captures them). Structure
 * mirrors [SessionGattServer] (server lifecycle, device tracking, stale-liveness
 * check) minus everything notify/read.
 */
class RelayGattServer(private val context: Context) {

    companion object {
        private const val TAG = "CruxRelay/Server"
        private const val MAX_CONNECTED_DEVICES = 4
        private const val LIVENESS_CHECK_INTERVAL_MS = 10_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var livenessJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _climbs = MutableSharedFlow<RelayInboundClimb>(extraBufferCapacity = 64)
    val climbs: SharedFlow<RelayInboundClimb> = _climbs.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 32)
    val connectionEvents: SharedFlow<GattConnectionEvent> = _connectionEvents.asSharedFlow()

    // All BLE callbacks run on the binder thread — guard shared state on [lock].
    private val lock = Any()
    private val connectedDevices = mutableSetOf<String>()
    private val reassemblers = HashMap<String, RelayFrameReassembler>()

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
                        reassemblers[address] = RelayFrameReassembler()
                    }
                    Log.d(TAG, "Client connected: $address")
                    _connectionEvents.tryEmit(GattConnectionEvent.Connected(address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) {
                        connectedDevices.remove(address)
                        reassemblers.remove(address)?.reset()
                    }
                    Log.d(TAG, "Client disconnected: $address")
                    _connectionEvents.tryEmit(GattConnectionEvent.Disconnected(address))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == BoardBleUuids.DATA_TRANSFER_CHAR && value != null) {
                // Reassemble per client; a complete climb (ONLY / FIRST..LAST)
                // may span many writes. Never act on a partial write.
                val completed = synchronized(lock) {
                    reassemblers[device.address]?.offer(value) ?: emptyList()
                }
                for (climb in completed) {
                    if (!_climbs.tryEmit(RelayInboundClimb(device.address, climb))) {
                        Log.w(TAG, "climbs buffer full — dropping a climb from ${device.address}")
                    }
                }
            }
            // The board char is write-only; the app usually writes WITHOUT
            // response, but honour a with-response write too.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_isRunning.value) return true
        val manager = bluetoothManager ?: return false
        val server = manager.openGattServer(context, gattCallback) ?: run {
            Log.e(TAG, "Failed to open GATT server"); return false
        }
        gattServer = server

        val service = BluetoothGattService(
            BoardBleUuids.DATA_TRANSFER_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val writeChar = BluetoothGattCharacteristic(
            BoardBleUuids.DATA_TRANSFER_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(writeChar)

        if (!server.addService(service)) {
            Log.e(TAG, "Failed to add relay GATT service")
            server.close(); gattServer = null; return false
        }
        _isRunning.value = true

        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive) { delay(LIVENESS_CHECK_INTERVAL_MS); checkForStaleConnections() }
        }
        Log.d(TAG, "Relay GATT server started")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_isRunning.value) return
        livenessJob?.cancel(); livenessJob = null
        synchronized(lock) {
            connectedDevices.clear()
            reassemblers.values.forEach { it.reset() }
            reassemblers.clear()
        }
        gattServer?.close(); gattServer = null
        _isRunning.value = false
        Log.d(TAG, "Relay GATT server stopped")
    }

    fun getConnectedCount(): Int = synchronized(lock) { connectedDevices.size }

    @SuppressLint("MissingPermission")
    private fun checkForStaleConnections() {
        val manager = bluetoothManager ?: return
        val osConnected = try {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).map { it.address }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query connected devices", e); return
        }
        val stale = synchronized(lock) {
            val s = connectedDevices.filter { it !in osConnected }
            for (a in s) { connectedDevices.remove(a); reassemblers.remove(a)?.reset() }
            s
        }
        for (a in stale) {
            Log.w(TAG, "Stale relay connection: $a")
            _connectionEvents.tryEmit(GattConnectionEvent.Disconnected(a))
        }
    }
}
