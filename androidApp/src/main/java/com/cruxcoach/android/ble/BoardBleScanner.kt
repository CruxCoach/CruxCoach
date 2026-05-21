package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.cruxcoach.domain.board.BoardBrand
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveredBoard(
    val displayName: String,
    val serial: String,
    val apiLevel: Int,
    val address: String,
    val rssi: Int,
    /** Board family — drives which BLE send protocol the connection
     *  speaks (Aurora binary vs MoonBoard ASCII). Defaults to KILTER
     *  so existing Aurora call sites are unaffected (FEAT-027). */
    val boardBrand: BoardBrand = BoardBrand.KILTER,
)

/**
 * Scans for Aurora Climbing boards + MoonBoard via BLE.
 * Scans without a UUID filter; identifies boards by advertising name —
 * Aurora boards as "BoardName#serial@apiLevel" / "BoardName@apiLevel",
 * MoonBoard as a bare "MoonBoard…" name (FEAT-027).
 */
class BoardBleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BoardBleScanner"
        private const val MAX_REGISTRATION_RETRIES = 3
        private const val REGISTRATION_RETRY_DELAY_MS = 1000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    /** Always fetch fresh — bluetoothLeScanner is null when BT is off. */
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private val _discoveredBoards = MutableStateFlow<List<DiscoveredBoard>>(emptyList())
    val discoveredBoards: StateFlow<List<DiscoveredBoard>> = _discoveredBoards.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val boardMap = ConcurrentHashMap<String, DiscoveredBoard>()
    private val scope = CoroutineScope(SupervisorJob())

    /** Tracks the number of consecutive errorCode=2 failures for retry logic. */
    private var registrationRetryCount = 0

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                // BT toggle clears leaked scan registrations — reset retry counter
                if (state == BluetoothAdapter.STATE_ON) {
                    registrationRetryCount = 0
                }
            }
        }
    }

    init {
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Try device.name first, fall back to scanRecord.deviceName (more reliable)
            val name = device.name
                ?: result.scanRecord?.deviceName
                ?: return
            Log.d(TAG, "BLE scan result: name=$name addr=${device.address} rssi=${result.rssi}")

            val board = if (isMoonBoardName(name)) {
                // MoonBoard advertises a bare "MoonBoard…" name with no
                // Aurora #serial@apiLevel suffix. apiLevel is an Aurora
                // concept and stays 0 for MoonBoard.
                DiscoveredBoard(
                    displayName = name,
                    serial = "",
                    apiLevel = 0,
                    address = device.address,
                    rssi = result.rssi,
                    boardBrand = BoardBrand.MOONBOARD,
                )
            } else {
                val parsed = parseBoardName(name) ?: return
                DiscoveredBoard(
                    displayName = parsed.first,
                    serial = parsed.second,
                    apiLevel = parsed.third,
                    address = device.address,
                    rssi = result.rssi,
                    boardBrand = BoardBrand.KILTER,
                )
            }
            boardMap[device.address] = board
            _discoveredBoards.value = boardMap.values.toList()
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN"
            }
            Log.e(TAG, "BLE scan failed: $reason (code=$errorCode)")
            _isScanning.value = false

            // ErrorCode 2 = APPLICATION_REGISTRATION_FAILED: BLE scan client slots are
            // exhausted. Common on Android 9 after app reinstall or crash. Retry after
            // a delay — the BLE stack sometimes frees slots asynchronously.
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
                && registrationRetryCount < MAX_REGISTRATION_RETRIES
            ) {
                registrationRetryCount++
                Log.w(TAG, "Retrying scan registration (attempt $registrationRetryCount/$MAX_REGISTRATION_RETRIES)")
                scope.launch {
                    delay(REGISTRATION_RETRY_DELAY_MS * registrationRetryCount)
                    startScan()
                }
            } else if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                Log.e(TAG, "Scan registration failed after $MAX_REGISTRATION_RETRIES retries. " +
                    "Toggling Bluetooth off/on should fix this.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val s = scanner ?: run {
            Log.w(TAG, "BLE scanner is null (Bluetooth off?)")
            return
        }
        if (_isScanning.value) return
        boardMap.clear()
        _discoveredBoards.value = emptyList()

        // Scan without UUID filter — rely on name parsing for board identification.
        // Some BLE peripherals (e.g. BlueZ bless) don't always include the service
        // UUID in ad data in a way Android's ScanFilter recognizes.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Starting BLE scan (no UUID filter, name-based detection)")
        try {
            s.startScan(null, settings, scanCallback)
            _isScanning.value = true
            registrationRetryCount = 0
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan SecurityException (missing permission?)", e)
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan failed to start", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        val s = scanner ?: return
        try {
            s.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        _isScanning.value = false
    }

    /**
     * True if a BLE advertising name looks like a MoonBoard. MoonBoard
     * hardware (and the MoonSimulator) advertises a bare "MoonBoard…" /
     * "Moonboard…" name with no Aurora-style #serial@apiLevel suffix.
     */
    fun isMoonBoardName(name: String): Boolean =
        name.startsWith("MoonBoard") || name.startsWith("Moonboard")

    /**
     * Parse Aurora board BLE name.
     * Supports two formats:
     *   - "BoardName#serial@apiLevel" (e.g. "KilterBoard#ABC123@3")
     *   - "BoardName@apiLevel" (e.g. "Kilter Board@3") — serial defaults to ""
     * The '@' suffix determines the API level (default 2 if absent).
     * @return Triple(displayName, serial, apiLevel) or null if unparseable
     */
    fun parseBoardName(name: String): Triple<String, String, Int>? {
        val hashIdx = name.indexOf('#')
        val atIdx = name.lastIndexOf('@')

        return when {
            // Format: "Name#serial@apiLevel"
            hashIdx >= 0 && atIdx > hashIdx -> {
                val displayName = name.substring(0, hashIdx)
                val serial = name.substring(hashIdx + 1, atIdx)
                val apiLevel = name.substring(atIdx + 1).toIntOrNull() ?: 2
                Triple(displayName, serial, apiLevel)
            }
            // Format: "Name#serial" (no apiLevel)
            hashIdx >= 0 -> {
                val displayName = name.substring(0, hashIdx)
                val serial = name.substring(hashIdx + 1)
                Triple(displayName, serial, 2)
            }
            // Format: "Name@apiLevel" (no serial)
            atIdx >= 0 -> {
                val displayName = name.substring(0, atIdx)
                val apiLevel = name.substring(atIdx + 1).toIntOrNull() ?: return null
                Triple(displayName, "", apiLevel)
            }
            else -> null
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
}
