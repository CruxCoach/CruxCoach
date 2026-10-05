package com.cruxcoach.android.athlete.force

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cruxcoach.android.ble.BlePermissionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

enum class ProgressorStatus { IDLE, SCANNING, CONNECTING, CONNECTED, NOT_FOUND, NO_PERMISSION, BLUETOOTH_OFF, FAILED }

/**
 * Minimal BLE client for a Tindeq Progressor (FEAT-071, experimental):
 * scan by name, connect, subscribe to the data point, send tare / start /
 * stop / battery commands, and stream [ForceSample]s. Follows the GATT
 * habits of [com.cruxcoach.android.ble.SessionGattClient]: callbacks on the
 * main thread, one write at a time, explicit disconnect and delayed close.
 * Every Android BLE call is guarded — missing permissions or Bluetooth off
 * end in a status, never in a crash.
 */
class ProgressorClient(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _status = MutableStateFlow(ProgressorStatus.IDLE)
    val status: StateFlow<ProgressorStatus> = _status.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _batteryMv = MutableStateFlow<Int?>(null)
    val batteryMv: StateFlow<Int?> = _batteryMv.asStateFlow()

    private val _lowPower = MutableStateFlow(false)
    val lowPower: StateFlow<Boolean> = _lowPower.asStateFlow()

    /** Samples as they arrive while measuring; buffered for bursts of notifications. */
    private val _samples = MutableSharedFlow<ForceSample>(extraBufferCapacity = 4096)
    val samples: SharedFlow<ForceSample> = _samples.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var writeDeferred: CompletableDeferred<Int>? = null
    private var descriptorDeferred: CompletableDeferred<Int>? = null
    private val writeMutex = Mutex()
    private var scanJob: Job? = null
    private var timeoutJob: Job? = null
    @Volatile private var lastCommand: Byte = 0

    private var scanCallback: ScanCallback? = null

    fun hasPermissions(): Boolean = BlePermissionHelper.hasPermissions(context)

    /** Scans up to [timeoutMs] for the strongest Progressor and connects to it. */
    @SuppressLint("MissingPermission")
    fun connectNearest(timeoutMs: Long = SCAN_TIMEOUT_MS) {
        if (_status.value == ProgressorStatus.SCANNING || _status.value == ProgressorStatus.CONNECTING ||
            _status.value == ProgressorStatus.CONNECTED) return
        if (!hasPermissions()) { _status.value = ProgressorStatus.NO_PERMISSION; return }
        val a = adapter
        if (a == null || !a.isEnabled) { _status.value = ProgressorStatus.BLUETOOTH_OFF; return }
        val scanner = a.bluetoothLeScanner ?: run { _status.value = ProgressorStatus.BLUETOOTH_OFF; return }
        _status.value = ProgressorStatus.SCANNING
        val found = mutableMapOf<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (name != null && name.startsWith(ProgressorProtocol.NAME_PREFIX)) found[result.device.address] = result
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }
        scanCallback = callback
        val ok = runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }.isSuccess
        if (!ok) { _status.value = ProgressorStatus.FAILED; scanCallback = null; return }
        scanJob?.cancel()
        scanJob = scope.launch {
            // Stop early once something was seen for a moment; otherwise wait the full timeout.
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < timeoutMs) {
                delay(300)
                if (found.isNotEmpty() && System.currentTimeMillis() - started > EARLY_STOP_MS) break
            }
            stopScan()
            val best = found.values.maxByOrNull { it.rssi }
            if (best == null) _status.value = ProgressorStatus.NOT_FOUND
            else connect(best.device, best.scanRecord?.deviceName ?: runCatching { best.device.name }.getOrNull())
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, name: String?) {
        _status.value = ProgressorStatus.CONNECTING
        _deviceName.value = name ?: device.address
        val g = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mainHandler)
        }.getOrNull()
        if (g == null) { _status.value = ProgressorStatus.FAILED; return }
        gatt = g
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

    @SuppressLint("MissingPermission")
    private suspend fun send(opcode: Byte): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val c = control ?: return false
        val deferred = CompletableDeferred<Int>()
        writeDeferred = deferred
        lastCommand = opcode
        val bytes = ProgressorProtocol.command(opcode)
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }.getOrDefault(false)
        if (!queued) { writeDeferred = null; return false }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        writeDeferred = null
        status == BluetoothGatt.GATT_SUCCESS
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        timeoutJob?.cancel()
        scanJob?.cancel()
        stopScan()
        writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        descriptorDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        val g = gatt
        gatt = null
        control = null
        if (g != null) {
            runCatching { g.disconnect() }
            mainHandler.postDelayed({ runCatching { g.close() } }, 500)
        }
        if (_status.value != ProgressorStatus.NO_PERMISSION && _status.value != ProgressorStatus.BLUETOOTH_OFF) {
            _status.value = ProgressorStatus.IDLE
        }
    }

    /** Ends everything; the client is not reused afterwards. */
    fun close() {
        disconnect()
        scope.cancel()
    }

    /**
     * Stops the measurement stream (so the device does not keep sampling and
     * draining its battery), then disconnects and ends the client. Runs on the
     * client's own scope, so it may be called from a ViewModel's onCleared.
     */
    fun shutdown() {
        scope.launch {
            if (_status.value == ProgressorStatus.CONNECTED) withTimeoutOrNull(1_000) { stopMeasuring() }
            close()
        }
    }

    private fun onData(bytes: ByteArray) {
        when (val frame = ProgressorProtocol.parse(bytes)) {
            is ProgressorProtocol.Frame.Weight -> frame.samples.forEach { if (!_samples.tryEmit(it)) Log.w(TAG, "sample buffer full") }
            is ProgressorProtocol.Frame.CommandResponse ->
                if (lastCommand == ProgressorProtocol.CMD_GET_BATTERY) ProgressorProtocol.batteryMillivolts(frame.payload)?.let { _batteryMv.value = it }
            ProgressorProtocol.Frame.LowPower -> _lowPower.value = true
            else -> Unit
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) { fail(g); return }
                    if (runCatching { g.discoverServices() }.getOrDefault(false) != true) fail(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    descriptorDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    runCatching { g.close() }
                    if (gatt === g) { gatt = null; control = null; _status.value = ProgressorStatus.IDLE }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(ProgressorProtocol.SERVICE)
            val data = service?.getCharacteristic(ProgressorProtocol.DATA_POINT)
            val ctrl = service?.getCharacteristic(ProgressorProtocol.CONTROL_POINT)
            if (status != BluetoothGatt.GATT_SUCCESS || data == null || ctrl == null) { fail(g); return }
            control = ctrl
            scope.launch {
                val subscribed = subscribe(g, data)
                if (gatt !== g) return@launch
                if (!subscribed) { fail(g); return@launch }
                timeoutJob?.cancel()
                _status.value = ProgressorStatus.CONNECTED
                requestBattery()
            }
        }

        // Android 12 and older; API 33+ calls the variant with the value below instead.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != ProgressorProtocol.DATA_POINT) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onData(value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == ProgressorProtocol.DATA_POINT) onData(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDeferred?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorDeferred?.complete(status)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun subscribe(g: BluetoothGatt, data: BluetoothGattCharacteristic): Boolean {
        if (runCatching { g.setCharacteristicNotification(data, true) }.getOrDefault(false) != true) return false
        val cccd = data.getDescriptor(ProgressorProtocol.CCCD) ?: return false
        val deferred = CompletableDeferred<Int>()
        descriptorDeferred = deferred
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }.getOrDefault(false)
        if (!queued) { descriptorDeferred = null; return false }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
        descriptorDeferred = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    @SuppressLint("MissingPermission")
    private fun fail(g: BluetoothGatt) {
        runCatching { g.close() }
        if (gatt === g) { gatt = null; control = null }
        _status.value = ProgressorStatus.FAILED
    }

    companion object {
        private const val TAG = "ProgressorClient"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val EARLY_STOP_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
    }
}
