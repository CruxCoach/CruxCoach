// Port of androidApp/.../athlete/force/ProgressorClient.kt (0e1bad0c6) onto the shared
// [BleCentral] (CoreBluetooth on iOS): scan by name, connect, subscribe to the data point,
// tare / start / stop / battery commands, stream [ForceSample]s. Same timeouts and statuses.
// iOS has no runtime permission call: an unauthorized adapter is NO_PERMISSION.
package com.cruxcoach.app.force

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BleCentral
import com.cruxcoach.app.ble.BleCentralListener
import com.cruxcoach.app.ble.BleServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ProgressorStatus { IDLE, SCANNING, CONNECTING, CONNECTED, NOT_FOUND, NO_PERMISSION, BLUETOOTH_OFF, FAILED }

/**
 * Minimal client for a Tindeq Progressor (FEAT-071, experimental). [scope] must be
 * single-threaded (the main scope on iOS): every listener event hops onto it, so the state
 * below is only ever touched from one thread. One write at a time; every request ends in a
 * status, never in a crash. [onSample] receives samples on [scope] while measuring.
 */
internal class ProgressorClient(
    private val central: BleCentral,
    private val scope: CoroutineScope,
    private val millis: () -> Long,
) : BleCentralListener {

    private val _status = MutableStateFlow(ProgressorStatus.IDLE)
    val status: StateFlow<ProgressorStatus> = _status.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _batteryMv = MutableStateFlow<Int?>(null)
    val batteryMv: StateFlow<Int?> = _batteryMv.asStateFlow()

    private val _lowPower = MutableStateFlow(false)
    val lowPower: StateFlow<Boolean> = _lowPower.asStateFlow()

    var onSample: ((ForceSample) -> Unit)? = null

    private val adapter = MutableStateFlow(BleAdapterState.UNKNOWN)
    private val found = LinkedHashMap<String, Pair<String, Int>>() // identifier -> (name, rssi)
    private var scanning = false
    private var device: String? = null
    private var controlWithResponse = true
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var notifyDeferred: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()
    private var scanJob: Job? = null
    private var timeoutJob: Job? = null
    private var lastCommand: Byte = 0

    init {
        central.setListener(this)
    }

    /** Scans up to [timeoutMs] for the strongest Progressor and connects to it. */
    fun connectNearest(timeoutMs: Long = SCAN_TIMEOUT_MS) {
        val s = _status.value
        if (s == ProgressorStatus.SCANNING || s == ProgressorStatus.CONNECTING || s == ProgressorStatus.CONNECTED) return
        _status.value = ProgressorStatus.SCANNING
        scanJob?.cancel()
        scanJob = scope.launch {
            adapter.value = central.adapterState()
            // Bringing the manager up is what shows the Bluetooth prompt; its answer arrives as an event.
            central.activate()
            val state = withTimeoutOrNull(ADAPTER_WAIT_MS) {
                var current = adapter.value
                while (current == BleAdapterState.UNKNOWN || current == BleAdapterState.RESETTING) {
                    delay(100)
                    current = adapter.value
                }
                current
            }
            when (state) {
                BleAdapterState.POWERED_ON -> Unit
                BleAdapterState.UNAUTHORIZED -> { _status.value = ProgressorStatus.NO_PERMISSION; return@launch }
                BleAdapterState.POWERED_OFF, BleAdapterState.UNSUPPORTED -> { _status.value = ProgressorStatus.BLUETOOTH_OFF; return@launch }
                else -> { _status.value = ProgressorStatus.FAILED; return@launch }
            }
            found.clear()
            scanning = true
            central.startScan()
            // Stop early once something was seen for a moment; otherwise wait the full timeout.
            val started = millis()
            while (millis() - started < timeoutMs) {
                delay(300)
                if (found.isNotEmpty() && millis() - started > EARLY_STOP_MS) break
            }
            stopScan()
            val best = found.entries.maxByOrNull { it.value.second }
            if (best == null) _status.value = ProgressorStatus.NOT_FOUND
            else connect(best.key, best.value.first)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        central.stopScan()
    }

    private fun connect(identifier: String, name: String) {
        _status.value = ProgressorStatus.CONNECTING
        _deviceName.value = name
        device = identifier
        central.connect(identifier)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_status.value == ProgressorStatus.CONNECTING) { disconnect(); _status.value = ProgressorStatus.FAILED }
        }
    }

    suspend fun tare(): Boolean = send(ProgressorProtocol.CMD_TARE)
    suspend fun startMeasuring(): Boolean = send(ProgressorProtocol.CMD_START_WEIGHT)
    suspend fun stopMeasuring(): Boolean = send(ProgressorProtocol.CMD_STOP_WEIGHT)
    suspend fun requestBattery(): Boolean = send(ProgressorProtocol.CMD_GET_BATTERY)

    private suspend fun send(opcode: Byte): Boolean = writeMutex.withLock {
        val id = device ?: return false
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        lastCommand = opcode
        central.write(id, ProgressorProtocol.SERVICE, ProgressorProtocol.CONTROL_POINT, ProgressorProtocol.command(opcode), controlWithResponse)
        val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        writeDeferred = null
        ok == true
    }

    fun disconnect() {
        timeoutJob?.cancel()
        scanJob?.cancel()
        stopScan()
        writeDeferred?.complete(false)
        notifyDeferred?.complete(false)
        val id = device
        device = null
        if (id != null) central.cancelConnection(id)
        val s = _status.value
        if (s != ProgressorStatus.NO_PERMISSION && s != ProgressorStatus.BLUETOOTH_OFF) _status.value = ProgressorStatus.IDLE
    }

    /**
     * Stops the measurement stream (so the device does not keep sampling and draining its
     * battery), then disconnects, lets go of the central and ends [scope]: the client is not
     * reused afterwards. [scope] is the client's own, so the screen may already be gone.
     */
    fun shutdown() {
        scope.launch {
            if (_status.value == ProgressorStatus.CONNECTED) withTimeoutOrNull(1_000) { stopMeasuring() }
            disconnect()
            central.setListener(null)
            scope.cancel()
        }
    }

    private fun onData(bytes: ByteArray) {
        when (val frame = ProgressorProtocol.parse(bytes)) {
            is ProgressorProtocol.Frame.Weight -> frame.samples.forEach { sample -> onSample?.invoke(sample) }
            is ProgressorProtocol.Frame.CommandResponse ->
                if (lastCommand == ProgressorProtocol.CMD_GET_BATTERY) ProgressorProtocol.batteryMillivolts(frame.payload)?.let { _batteryMv.value = it }
            ProgressorProtocol.Frame.LowPower -> _lowPower.value = true
            else -> Unit
        }
    }

    private fun fail() {
        val id = device
        device = null
        if (id != null) central.cancelConnection(id)
        _status.value = ProgressorStatus.FAILED
    }

    // ── BleCentralListener: hop onto the scope, ignore other peripherals ──

    override fun onAdapterState(state: BleAdapterState) {
        scope.launch {
            adapter.value = state
            if (state != BleAdapterState.POWERED_ON && device != null) {
                writeDeferred?.complete(false)
                notifyDeferred?.complete(false)
                device = null
                _status.value = if (state == BleAdapterState.UNAUTHORIZED) ProgressorStatus.NO_PERMISSION else ProgressorStatus.BLUETOOTH_OFF
            }
        }
    }

    override fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) {
        val name = advertisedName ?: peripheralName ?: return
        if (!name.startsWith(ProgressorProtocol.NAME_PREFIX)) return
        scope.launch { if (scanning) found[identifier] = name to rssi }
    }

    override fun onConnected(identifier: String) {
        scope.launch {
            if (identifier != device || _status.value != ProgressorStatus.CONNECTING) return@launch
            central.discoverServices(identifier)
        }
    }

    override fun onDisconnected(identifier: String) {
        scope.launch {
            if (identifier != device) return@launch
            writeDeferred?.complete(false)
            notifyDeferred?.complete(false)
            device = null
            _status.value = ProgressorStatus.IDLE
        }
    }

    override fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?) {
        scope.launch {
            if (identifier != device) return@launch
            val service = services?.firstOrNull { sameUuid(it.uuid, ProgressorProtocol.SERVICE) }
            val data = service?.characteristics?.firstOrNull { sameUuid(it.uuid, ProgressorProtocol.DATA_POINT) }
            val control = service?.characteristics?.firstOrNull { sameUuid(it.uuid, ProgressorProtocol.CONTROL_POINT) }
            if (data == null || control == null || !data.canNotify || !(control.canWrite || control.canWriteWithoutResponse)) {
                fail()
                return@launch
            }
            controlWithResponse = control.canWrite
            val deferred = CompletableDeferred<Boolean>()
            notifyDeferred = deferred
            central.setNotify(identifier, ProgressorProtocol.SERVICE, ProgressorProtocol.DATA_POINT, true)
            val subscribed = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } == true
            notifyDeferred = null
            if (device != identifier) return@launch
            if (!subscribed) { fail(); return@launch }
            timeoutJob?.cancel()
            _status.value = ProgressorStatus.CONNECTED
            requestBattery()
        }
    }

    override fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean) {
        scope.launch {
            if (identifier == device && sameUuid(characteristicUuid, ProgressorProtocol.CONTROL_POINT)) writeDeferred?.complete(success)
        }
    }

    override fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?) = Unit

    override fun onNotifyStateChanged(identifier: String, characteristicUuid: String, enabled: Boolean, success: Boolean) {
        scope.launch {
            if (identifier == device && sameUuid(characteristicUuid, ProgressorProtocol.DATA_POINT)) notifyDeferred?.complete(success && enabled)
        }
    }

    override fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray) {
        val copy = value.copyOf()
        scope.launch {
            if (identifier == device && sameUuid(characteristicUuid, ProgressorProtocol.DATA_POINT)) onData(copy)
        }
    }

    companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val EARLY_STOP_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 3_000L
        /** How long the first Bluetooth prompt may take before the attempt is given up. */
        const val ADAPTER_WAIT_MS = 30_000L

        /** CoreBluetooth reports 128-bit UUIDs in upper case. */
        fun sameUuid(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
    }
}
