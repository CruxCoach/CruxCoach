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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A complete climb an official-app client wrote to the emulated board char. */
data class RelayInboundClimb(val deviceAddress: String, val climb: CompleteClimb)

/** One Nordic-UART write exactly as a guest sent it. MoonBoard uses an ASCII
 * stream instead of Aurora's framed packets, so these writes are forwarded
 * byte-for-byte without [RelayFrameReassembler]. */
data class RelayInboundWrite(val deviceAddress: String, val value: ByteArray)

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
 * byte-faithfully to the real board. Structure
 * mirrors [SessionGattServer] (server lifecycle, device tracking, stale-liveness
 * check) minus everything notify/read.
 */
class RelayGattServer(private val context: Context) {

    companion object {
        private const val TAG = "CruxRelay/Server"
        /**
         * The server's own ceiling. Not the answer on its own: the radio is
         * shared with the mesh and the board link, so [availableSlots] is what
         * actually decides — this only bounds it.
         */
        const val MAX_CONNECTED_DEVICES = 4
        private const val LIVENESS_CHECK_INTERVAL_MS = 10_000L
        private const val CLIENT_DISCONNECT_GRACE_MS = 300L
        private const val SERVICE_REGISTRATION_TIMEOUT_MS = 2_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var livenessJob: Job? = null
    private var serviceRegistration: CompletableDeferred<Boolean>? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Whether a guest write can be delivered at all, answered synchronously.
     *
     * Called on the GATT callback thread, so it must not touch the database or
     * suspend — it is the board-path question ("is there a wall on the other
     * side of me right now"), not the catalogue question ("is this a climb
     * this board can show"), which needs IO and happens after reassembly in
     * [CruxRelayManager]. Null means admit, so a server nobody has configured
     * behaves exactly as it did before.
     */
    var admitWrite: (() -> Boolean)? = null

    private val _climbs = MutableSharedFlow<RelayInboundClimb>(extraBufferCapacity = 64)
    val climbs: SharedFlow<RelayInboundClimb> = _climbs.asSharedFlow()

    private val _writes = MutableSharedFlow<RelayInboundWrite>(extraBufferCapacity = 256)
    val writes: SharedFlow<RelayInboundWrite> = _writes.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 32)
    val connectionEvents: SharedFlow<GattConnectionEvent> = _connectionEvents.asSharedFlow()

    // All BLE callbacks run on the binder thread — guard shared state on [lock].
    private val lock = Any()
    private val connectedDevices = mutableSetOf<String>()

    /**
     * How many guests the radio can still take, asked at accept time.
     *
     * A guest arriving is a radio slot leaving, and the mesh is what makes the
     * cell converge — so this is re-evaluated per connection rather than once
     * at start, and the relay yields rather than the mesh being starved.
     * Defaults to the server ceiling so a server used without a manager
     * behaves as it always did.
     */
    var availableSlots: () -> Int = { MAX_CONNECTED_DEVICES - getConnectedCount() }
    private val reassemblers = HashMap<String, RelayFrameReassembler>()

    /**
     * Address of the phone's own board link, when there is one.
     *
     * Android reports every device already connected to the local adapter to a
     * freshly opened GATT server — including the board WE are the client of.
     * It arrives as a "client connected" before [start] has even returned, and
     * it never disconnects while the board link is up, so the chip read
     * "2 verbunden" with a single real client and settled at 1 with none.
     * The board is a peer of this relay, never a client of it.
     */
    var boardAddressProvider: () -> String? = { null }

    private fun isOwnBoard(address: String): Boolean =
        boardAddressProvider()?.equals(address, ignoreCase = true) == true

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid == BoardBleUuids.DATA_TRANSFER_SERVICE) {
                val ready = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "Relay service registration finished: status=$status ready=$ready")
                serviceRegistration?.complete(ready)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (isOwnBoard(address)) {
                Log.d(TAG, "Ignoring own board link on the relay server: $address (newState=$newState)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) {
                        if (connectedDevices.size >= MAX_CONNECTED_DEVICES ||
                            runCatching { availableSlots() }.getOrDefault(0) <= 0
                        ) {
                            Log.w(TAG, "Max devices reached, rejecting $address")
                            gattServer?.cancelConnection(device)
                            return
                        }
                        connectedDevices.add(address)
                        reassemblers[address] = RelayFrameReassembler()
                    }
                    // Count + members: the chip shows this number, and a stale
                    // entry is the difference between "one client" and "two".
                    Log.d(TAG, "Client connected: $address — devices=$connectedDevices")
                    _connectionEvents.tryEmit(GattConnectionEvent.Connected(address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) {
                        connectedDevices.remove(address)
                        reassemblers.remove(address)?.reset()
                    }
                    Log.d(TAG, "Client disconnected: $address — devices=$connectedDevices")
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
            // Truthfulness first, and the honest scope of it: an ATT write
            // response says "received", never "the wall shows it". The board
            // protocol the official app speaks has no application-level ACK at
            // all — there is no frame to answer a climb with — so the delivery
            // result cannot be reported here without holding the ATT queue open
            // across a board round trip. What can be answered truthfully is
            // whether this relay will deliver the write at all, and that is
            // decided before the response rather than after it. A refusal is an
            // ATT error, not a success the guest's app will believe.
            val admitted = admitWrite?.invoke() ?: true
            if (!admitted) {
                Log.i(TAG, "refusing a write from ${device.address}: no usable board path")
                synchronized(lock) { reassemblers[device.address]?.reset() }
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }
            if (characteristic.uuid == BoardBleUuids.DATA_TRANSFER_CHAR && value != null) {
                // Preserve the exact write for protocols such as MoonBoard.
                // CruxRelayManager selects this stream only when the physical
                // board is not using Aurora packet framing.
                if (!_writes.tryEmit(RelayInboundWrite(device.address, value.copyOf()))) {
                    Log.w(TAG, "writes buffer full — dropping a write from ${device.address}")
                }
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
            // response, but honour a with-response write too. Reassembly and
            // admission are both above this, so a success here means the bytes
            // were taken for delivery — no more, and no less.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean {
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

        val registration = CompletableDeferred<Boolean>()
        serviceRegistration = registration
        if (!server.addService(service)) {
            Log.e(TAG, "Failed to add relay GATT service")
            serviceRegistration = null
            server.close(); gattServer = null; return false
        }
        val serviceReady = withTimeoutOrNull(SERVICE_REGISTRATION_TIMEOUT_MS) {
            registration.await()
        } == true
        serviceRegistration = null
        if (!serviceReady) {
            Log.e(TAG, "Relay GATT service was not registered before timeout")
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
    suspend fun stop() {
        if (!_isRunning.value) return
        serviceRegistration?.cancel(); serviceRegistration = null
        livenessJob?.cancel(); livenessJob = null

        // BluetoothGattServer.close() only releases the local server object on
        // some Android stacks; it does not reliably tell existing centrals
        // that their link is gone. Cancel every client explicitly first so a
        // guest CruxCoach immediately leaves CONNECTED instead of keeping a
        // stale "connected to CruxRelay" state until the supervision timeout.
        val server = gattServer
        val clientAddresses = synchronized(lock) { connectedDevices.toList() }
        clientAddresses.forEach { address ->
            runCatching {
                bluetoothManager?.adapter?.getRemoteDevice(address)?.let { device ->
                    server?.cancelConnection(device)
                }
            }.onFailure { Log.w(TAG, "Could not disconnect relay client $address", it) }
        }
        if (clientAddresses.isNotEmpty()) {
            // Let the controller put the disconnect over the air before close
            // tears down the server callback and its underlying native slot.
            delay(CLIENT_DISCONNECT_GRACE_MS)
        }
        synchronized(lock) {
            connectedDevices.clear()
            reassemblers.values.forEach { it.reset() }
            reassemblers.clear()
        }
        server?.close(); gattServer = null
        _isRunning.value = false
        Log.d(TAG, "Relay GATT server stopped")
    }

    fun getConnectedCount(): Int = synchronized(lock) { connectedDevices.size }

    /** Who is attached right now — used to tell a reconnect from a second guest. */
    fun connectedAddresses(): Set<String> = synchronized(lock) { connectedDevices.toSet() }

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
