package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.relay.RelayBoardName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ConnectedAdvertisingProbeResult {
    CONNECTABLE_ADVERTISEMENT_OBSERVED,
    NOT_OBSERVED,
    INCONCLUSIVE,
}

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
    /** True when the endpoint is another CruxCoach user's connectable relay. */
    val isCruxRelay: Boolean = false,
    /**
     * Runtime-only result from scanning for this controller after GATT became
     * ready. Null means the probe has not completed or failed; false is an
     * operational hint for this connection and must not be persisted as a
     * firmware fact.
     */
    val advertisesWhileConnected: Boolean? = null,
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
        private const val CONNECTED_ADVERTISING_PROBE_MS = 4_000L
        /** Retried, not lengthened: a controller puts advertising back up a
         *  moment after the connection completes, and the first window starts
         *  while the stack is still finishing service discovery. */
        private const val CONNECTED_ADVERTISING_PROBE_WINDOWS = 3
        private const val CONNECTED_ADVERTISING_PROBE_GAP_MS = 1_500L
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
            // Prefer the freshly-advertised local name from the scan record
            // over BluetoothDevice.getName(): Android caches getName() per MAC
            // address, so a board that re-advertises under a different name on
            // the same adapter (e.g. the BoardSimulator switching board type)
            // would otherwise keep showing the first name ever seen for that MAC.
            val name = result.scanRecord?.deviceName
                ?: device.name
                ?: return
            Log.d(TAG, "BLE scan result: name=$name addr=${device.address} rssi=${result.rssi}")

            val isRelay = RelayBoardName.isRelayName(name)
            val boardName = RelayBoardName.unwrap(name)
            val board = if (isMoonBoardName(boardName)) {
                // MoonBoard advertises a bare "MoonBoard…" name with no
                // Aurora #serial@apiLevel suffix. apiLevel is an Aurora
                // concept and stays 0 for MoonBoard.
                DiscoveredBoard(
                    displayName = boardName,
                    serial = "",
                    apiLevel = 0,
                    address = device.address,
                    rssi = result.rssi,
                    boardBrand = BoardBrand.MOONBOARD,
                    isCruxRelay = isRelay,
                )
            } else {
                val parsed = parseBoardName(boardName) ?: return
                DiscoveredBoard(
                    displayName = parsed.first,
                    serial = parsed.second,
                    apiLevel = parsed.third,
                    address = device.address,
                    rssi = result.rssi,
                    // FEAT-031: infer the Aurora-family brand from the advertised
                    // name so the correct LED map + colours are selected; KILTER
                    // for "Kilter Board" and any unrecognised Aurora-named board.
                    boardBrand = auroraBrandFromName(parsed.first),
                    isCruxRelay = isRelay,
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
     * Watches for the connected controller's own advertisement.
     *
     * A peripheral can only be connected to while it advertises connectably, so
     * seeing that advertisement while WE hold the link is proof the controller
     * has a slot left for someone else. This is the only capacity evidence a
     * single phone can gather — a second `connectGatt` from this app would
     * share the same ACL link and prove nothing.
     *
     * The observation has to be made as permissively as the radio allows,
     * because every restriction here turns into a false "exclusive":
     *  - **no [ScanFilter]**. An address filter is offloaded to the controller,
     *    and offloaded filters are exactly what the main scan already avoids
     *    ("BlueZ peripherals don't always advertise in a way Android's
     *    ScanFilter recognizes"). The address is matched in software instead.
     *  - **[ScanSettings.Builder.setLegacy] `false`**. The default reports
     *    LEGACY advertisements ONLY. A controller that re-enables advertising
     *    after a connection may well do it on an extended advertising set — the
     *    BoardSimulator does — and a legacy-only scan cannot see one at all.
     *  - **several short windows**. The first one starts right after service
     *    discovery, when the stack is still busy, and BlueZ takes a moment to
     *    put advertising back up after a connection completes.
     *
     * A scan that could not complete stays inconclusive and changes nothing.
     * A scan that ran to the end and saw no advertisement is evidence in its
     * own right, and the caller does downgrade on it — that is the only way a
     * controller swapped for an exclusive one gets corrected.
     */
    @SuppressLint("MissingPermission")
    internal suspend fun probeAdvertisingWhileConnected(
        address: String,
        windowMs: Long = CONNECTED_ADVERTISING_PROBE_MS,
        windows: Int = CONNECTED_ADVERTISING_PROBE_WINDOWS,
    ): ConnectedAdvertisingProbeResult {
        // Scan permission only. The probe observes advertisements; requiring
        // the connect permission as well made it bail on every reconnect,
        // which is exactly the flow that runs without scan rights on legacy
        // Android — the capacity then stayed unverified forever.
        if (!BlePermissionHelper.hasScanPermission(context)) {
            return ConnectedAdvertisingProbeResult.INCONCLUSIVE
        }
        val s = scanner ?: return ConnectedAdvertisingProbeResult.INCONCLUSIVE

        // A manual board scan should already have stopped before connect, but
        // make the probe self-contained and avoid two callbacks competing for
        // the same Android BLE scan client.
        stopScan()

        var lastFailure: Int? = null
        repeat(windows) { attempt ->
            val result = CompletableDeferred<ConnectedAdvertisingProbeResult>()
            val seen = ConcurrentHashMap.newKeySet<String>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                    seen.add(scanResult.device.address)
                    if (scanResult.device.address.equals(address, ignoreCase = true) &&
                        scanResult.isConnectable
                    ) {
                        result.complete(
                            ConnectedAdvertisingProbeResult.CONNECTABLE_ADVERTISEMENT_OBSERVED
                        )
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Connected-advertising probe failed: code=$errorCode")
                    lastFailure = errorCode
                    result.complete(ConnectedAdvertisingProbeResult.INCONCLUSIVE)
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setLegacy(false) }
                .build()

            val outcome = try {
                s.startScan(null, settings, callback)
                withTimeoutOrNull(windowMs) { result.await() }
            } catch (e: SecurityException) {
                Log.w(TAG, "Connected-advertising probe lacks permission", e)
                return ConnectedAdvertisingProbeResult.INCONCLUSIVE
            } catch (e: Exception) {
                Log.w(TAG, "Connected-advertising probe could not start", e)
                return ConnectedAdvertisingProbeResult.INCONCLUSIVE
            } finally {
                runCatching { s.stopScan(callback) }
                    .onFailure { Log.w(TAG, "Could not stop connected-advertising probe", it) }
            }
            // Which devices the window DID see separates "the board is silent"
            // from "this phone reports nothing while connected" — the two
            // failure modes look identical from the result alone.
            Log.d(
                TAG,
                "capacity probe window ${attempt + 1}/$windows for $address: " +
                    "${outcome ?: "no advertisement"} — saw ${seen.size} device(s) $seen"
            )
            if (outcome == ConnectedAdvertisingProbeResult.CONNECTABLE_ADVERTISEMENT_OBSERVED) {
                return outcome
            }
            if (attempt < windows - 1) delay(CONNECTED_ADVERTISING_PROBE_GAP_MS)
        }
        return if (lastFailure != null) {
            ConnectedAdvertisingProbeResult.INCONCLUSIVE
        } else {
            ConnectedAdvertisingProbeResult.NOT_OBSERVED
        }
    }

    /**
     * True if a BLE advertising name looks like a MoonBoard. MoonBoard
     * hardware (and the MoonSimulator) advertises a bare "MoonBoard…" /
     * "Moonboard…" name with no Aurora-style #serial@apiLevel suffix.
     */
    fun isMoonBoardName(name: String): Boolean =
        name.startsWith("MoonBoard") || name.startsWith("Moonboard")

    /**
     * Infer the Aurora-family brand from an advertised board name (FEAT-031).
     * Normalises like BoardSesh's parser — lowercase, strip spaces/hyphens —
     * then matches the family prefix ("Tension Board" → TENSION, "So iLL
     * Board" / "So-iLL Board" → SOILL). Defaults to [BoardBrand.KILTER] for
     * "Kilter Board" and any unrecognised Aurora-named board (the historical
     * default; the Aurora BLE transport is identical across the family, so a
     * misread only mis-selects the LED map/colour table, never the protocol).
     */
    fun auroraBrandFromName(displayName: String): BoardBrand {
        val n = displayName.lowercase().replace(" ", "").replace("-", "")
        return when {
            n.startsWith("tension") -> BoardBrand.TENSION
            n.startsWith("grasshopper") -> BoardBrand.GRASSHOPPER
            n.startsWith("decoy") -> BoardBrand.DECOY
            n.startsWith("soill") -> BoardBrand.SOILL
            n.startsWith("touchstone") -> BoardBrand.TOUCHSTONE
            else -> BoardBrand.KILTER
        }
    }

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
