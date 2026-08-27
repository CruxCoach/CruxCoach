package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
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

data class NearbySession(
    val sessionId: Int,
    val participantCount: Int,
    val hostName: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val deviceAddress: String,
    /** The BluetoothDevice from the scan result — use for connectGatt() instead of address string. */
    val device: android.bluetooth.BluetoothDevice? = null,
    /** Current climb being projected by this session host (from climb advertisement). */
    val currentClimbUuid: String? = null,
    val currentClimbAngle: Int = 0
)

data class NearbyClimb(
    val climbUuid: String,
    val angle: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val deviceAddress: String,
    val connectedOnly: Boolean = false,
    /** True when the sender has disconnected and this is its last projection metadata. */
    val isLastClimb: Boolean = false,
    /** Whether this device accepts disconnect requests (from BoardConnected flag). */
    val acceptsDisconnectRequests: Boolean = true,
    /** True when another client can connect without taking this sender's slot. */
    val supportsConcurrentConnections: Boolean = false,
    /** Whether the board family retains this projection after the sender disconnects. */
    val projectionSurvivesDisconnect: Boolean = true,
    /** Sender identity for BoardConnected; null for legacy peers and climb payloads. */
    val senderToken: Int? = null,
)

class NearbyClimbScanner(private val context: Context) {

    companion object {
        private const val TAG = "CruxBLE/Scanner"
        private const val STALE_TIMEOUT_MS = 3_000L
        private const val NON_RETAINED_LAST_TIMEOUT_MS = 30_000L
        private const val SESSION_STALE_TIMEOUT_MS = 5_000L  // Bug 3: 5s avoids session flicker
        private const val CLEANUP_INTERVAL_MS = 1_000L
        private const val DEFAULT_RSSI_THRESHOLD = -82  // Bug 6: -82 filters weak signal noise
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private val _nearbyClimbs = MutableStateFlow<List<NearbyClimb>>(emptyList())
    val nearbyClimbs: StateFlow<List<NearbyClimb>> = _nearbyClimbs.asStateFlow()

    private val _nearbySessions = MutableStateFlow<List<NearbySession>>(emptyList())
    val nearbySessions: StateFlow<List<NearbySession>> = _nearbySessions.asStateFlow()

    private val _disconnectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnectRequests: SharedFlow<Unit> = _disconnectRequests.asSharedFlow()

    private val _disconnectResponses = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val disconnectResponses: SharedFlow<Boolean> = _disconnectResponses.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    var rssiThreshold: Int = DEFAULT_RSSI_THRESHOLD

    private val scope = CoroutineScope(SupervisorJob())
    private var cleanupJob: Job? = null

    // Track whether scanning was requested so we can auto-restart when BT becomes available
    private var wantScanning = false

    // Raw entries keyed by deviceAddress for dedup
    private val rawEntries = mutableMapOf<String, NearbyClimb>()
    private val rawSessionEntries = mutableMapOf<String, NearbySession>()

    // GONE dedup: once processed for an address, ignore subsequent GONEs for 10s.
    // BLE advertising is continuous, so GONE packets arrive every ~100ms until the
    // advertiser stops. Without dedup this floods the main thread.
    private val goneProcessedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val GONE_DEDUP_MS = 10_000L

    // BroadcastReceiver to detect BT on/off and auto-restart scan
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "Bluetooth turned OFF — marking scan as stopped")
                    cleanupJob?.cancel()
                    cleanupJob = null
                    _isScanning.value = false
                    synchronized(rawEntries) {
                        rawEntries.clear()
                        _nearbyClimbs.value = emptyList()
                    }
                    synchronized(rawSessionEntries) {
                        rawSessionEntries.clear()
                        _nearbySessions.value = emptyList()
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    if (wantScanning && !_isScanning.value) {
                        Log.d(TAG, "Bluetooth turned ON — restarting nearby scan")
                        startScan()
                    }
                }
            }
        }
    }

    init {
        // Intentionally never unregistered: this class is a @Singleton provided by Hilt,
        // so the receiver lives for the entire process lifetime — no leak.
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            if (rssi < rssiThreshold) return

            val scanRecord = result.scanRecord ?: return
            val manufacturerData = scanRecord.getManufacturerSpecificData(NearbyClimbProtocol.COMPANY_ID)
                ?: return

            val payload = NearbyClimbProtocol.decode(manufacturerData) ?: return

            when (payload) {
                is NearbyPayload.ClimbData, is NearbyPayload.LastClimb -> {
                    val (uuid, angle, last) = when (payload) {
                        is NearbyPayload.ClimbData -> Triple(payload.climbUuid, payload.angle, false)
                        is NearbyPayload.LastClimb -> Triple(payload.climbUuid, payload.angle, true)
                    }
                    val projectionSurvivesDisconnect = when (payload) {
                        is NearbyPayload.ClimbData -> payload.projectionSurvivesDisconnect
                        is NearbyPayload.LastClimb -> payload.projectionSurvivesDisconnect
                    }
                    val acceptsDisconnect = when (payload) {
                        is NearbyPayload.ClimbData -> payload.acceptsDisconnect
                        is NearbyPayload.LastClimb -> false
                    }
                    val supportsConcurrentConnections = when (payload) {
                        is NearbyPayload.ClimbData -> payload.supportsConcurrentConnections
                        is NearbyPayload.LastClimb -> false
                    }
                    val type = if (last) "LastClimb" else "ClimbData"
                    Log.d(TAG, "RECV $type from ${result.device.address} RSSI=$rssi uuid=${uuid.take(8)}... angle=$angle")
                    val now = System.currentTimeMillis()
                    val entry = NearbyClimb(
                        climbUuid = uuid,
                        angle = angle,
                        rssi = rssi,
                        lastSeenMs = now,
                        deviceAddress = result.device.address,
                        isLastClimb = last,
                        acceptsDisconnectRequests = acceptsDisconnect,
                        supportsConcurrentConnections = supportsConcurrentConnections,
                        projectionSurvivesDisconnect = projectionSurvivesDisconnect,
                    )
                    synchronized(rawEntries) {
                        convertOrRemoveStale(now, result.device.address)
                        if (!last) {
                            // ClimbData: LEDs were replaced — remove ALL LastClimb entries
                            val lastClimbAddrs = rawEntries.keys.filter { rawEntries[it]!!.isLastClimb }
                            lastClimbAddrs.forEach { rawEntries.remove(it) }
                        } else {
                            // A new LastClimb supersedes older board-state metadata.
                            val otherLastClimbs = rawEntries.keys.filter {
                                rawEntries[it]!!.isLastClimb && it != result.device.address
                            }
                            otherLastClimbs.forEach { rawEntries.remove(it) }
                        }
                        rawEntries[result.device.address] = entry
                        publishDeduped()
                    }
                }
                is NearbyPayload.BoardConnected -> {
                    Log.d(TAG, "RECV BoardConnected from ${result.device.address} RSSI=$rssi acceptsDisconnect=${payload.acceptsDisconnect}")
                    val now = System.currentTimeMillis()
                    val entry = NearbyClimb(
                        climbUuid = "",
                        angle = 0,
                        rssi = rssi,
                        lastSeenMs = now,
                        deviceAddress = result.device.address,
                        connectedOnly = true,
                        acceptsDisconnectRequests = payload.acceptsDisconnect,
                        supportsConcurrentConnections = payload.supportsConcurrentConnections,
                        senderToken = payload.senderToken,
                    )
                    synchronized(rawEntries) {
                        convertOrRemoveStale(now, result.device.address)
                        rawEntries[result.device.address] = entry
                        publishDeduped()
                    }
                }
                is NearbyPayload.DisconnectRequest -> {
                    Log.d(TAG, "Received DISCONNECT_REQUEST from ${result.device.address}")
                    _disconnectRequests.tryEmit(Unit)
                }
                is NearbyPayload.DisconnectResponse -> {
                    Log.d(TAG, "Received DISCONNECT_RESPONSE (accepted=${payload.accepted}) from ${result.device.address}")
                    _disconnectResponses.tryEmit(payload.accepted)
                }
                is NearbyPayload.Gone -> {
                    val addr = result.device.address
                    val now = System.currentTimeMillis()
                    // Atomic claim: compute returns `now` only for the caller
                    // that wins the race; later callers within GONE_DEDUP_MS
                    // get the existing timestamp back and bail out.
                    val claimed = goneProcessedAt.compute(addr) { _, existing ->
                        if (existing != null && now - existing < GONE_DEDUP_MS) existing else now
                    }
                    if (claimed != now) return // another thread already handled this GONE
                    Log.d(TAG, "Received GONE from $addr — removing immediately")
                    synchronized(rawEntries) {
                        rawEntries.remove(addr)
                        publishDeduped()
                    }
                    synchronized(rawSessionEntries) {
                        if (rawSessionEntries.remove(addr) != null) {
                            publishDedupedSessions()
                        }
                    }
                }
                is NearbyPayload.SessionAdvertisement -> {
                    val now = System.currentTimeMillis()
                    // Check scan response for embedded climb data (company ID 0xFFFE)
                    val climbData = scanRecord.getManufacturerSpecificData(
                        NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID
                    )
                    val sessionClimb = climbData?.let { NearbyClimbProtocol.decode(it) }
                    val climbUuid = when (sessionClimb) {
                        is NearbyPayload.ClimbData -> sessionClimb.climbUuid
                        else -> null
                    }
                    val climbAngle = when (sessionClimb) {
                        is NearbyPayload.ClimbData -> sessionClimb.angle
                        else -> 0
                    }
                    // Read existing + compute isNew + write all inside the lock
                    // so concurrent callbacks can't see a stale !containsKey()
                    // and log "new session discovered" for a device that was
                    // already tracked.
                    synchronized(rawSessionEntries) {
                        val existing = rawSessionEntries[result.device.address]
                        val isNew = existing == null
                        // BLE scan responses arrive separately from advertisements and may be
                        // missed intermittently. When the scan response is absent, retain the
                        // previous currentClimbUuid instead of overwriting with null.
                        val effectiveClimbUuid = climbUuid ?: existing?.currentClimbUuid
                        val effectiveClimbAngle = if (climbUuid != null) climbAngle
                            else (existing?.currentClimbAngle ?: 0)
                        val entry = NearbySession(
                            sessionId = payload.sessionId,
                            participantCount = payload.participantCount,
                            hostName = payload.hostName,
                            rssi = rssi,
                            lastSeenMs = now,
                            deviceAddress = result.device.address,
                            device = result.device,
                            currentClimbUuid = effectiveClimbUuid,
                            currentClimbAngle = effectiveClimbAngle
                        )
                        if (isNew) {
                            Log.d(TAG, "New session discovered: id=${payload.sessionId}, " +
                                "host='${payload.hostName}', count=${payload.participantCount}, " +
                                "climb=${climbUuid?.take(8)}, " +
                                "addr=${result.device.address}, rssi=$rssi")
                        }
                        rawSessionEntries[result.device.address] = entry
                        publishDedupedSessions()
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "Nearby scan failed: $reason (code=$errorCode)")
            _isScanning.value = false
            // No retry needed — btStateReceiver will restart when BT comes back on
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(clearExisting: Boolean = true) {
        Log.d(TAG, "startScan() called, wantScanning=$wantScanning, isScanning=${_isScanning.value}, clearExisting=$clearExisting")
        wantScanning = true
        if (!BlePermissionHelper.hasPermissions(context)) {
            Log.w(TAG, "Missing BLE scan permissions, skipping nearby scan")
            return
        }
        val s = scanner ?: run {
            Log.w(TAG, "BLE scanner is null (Bluetooth off?) — btStateReceiver will retry on BT ON")
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "startScan(): already scanning, skipping")
            return
        }

        if (clearExisting) {
            synchronized(rawEntries) {
                rawEntries.clear()
                _nearbyClimbs.value = emptyList()
            }
            synchronized(rawSessionEntries) {
                rawSessionEntries.clear()
                _nearbySessions.value = emptyList()
            }
        }

        val settings = buildScanSettings()
        val filters = buildScanFilters()

        // Hardware scan filters for CRUX manufacturer data prevent Android from
        // throttling the scan in the background (Android 8+ aggressively throttles
        // unfiltered scans after ~30s). Manual CRUX magic validation in onScanResult
        // remains as a secondary check.
        Log.d(TAG, "Starting nearby climb scan (${filters.size} hardware filters)")
        try {
            s.startScan(filters, settings, scanCallback)
            _isScanning.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Nearby scan SecurityException (missing permission?)", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Nearby scan failed to start", e)
            return
        }

        // Start stale entry cleanup
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                synchronized(rawEntries) {
                    val now = System.currentTimeMillis()
                    val staleCount = rawEntries.count { (_, entry) ->
                        !entry.isLastClimb && now - entry.lastSeenMs > STALE_TIMEOUT_MS
                    }
                    if (staleCount > 0) {
                        Log.d(TAG, "STALE cleanup: $staleCount entries exceeding ${STALE_TIMEOUT_MS}ms")
                        convertOrRemoveStale(now)
                        publishDeduped()
                    }
                }
                synchronized(rawSessionEntries) {
                    val now = System.currentTimeMillis()
                    val stale = rawSessionEntries.entries.filter { now - it.value.lastSeenMs > SESSION_STALE_TIMEOUT_MS }
                    if (stale.isNotEmpty()) {
                        Log.d(TAG, "Removing ${stale.size} stale session(s): " +
                            stale.map { "${it.value.sessionId}@${it.key} (${now - it.value.lastSeenMs}ms old)" })
                        stale.forEach { rawSessionEntries.remove(it.key) }
                        publishDedupedSessions()
                    }
                }
                // Clean up stale GONE dedup entries. Use computeIfPresent
                // with a null-return-to-remove pattern so each entry's read
                // and delete are atomic against concurrent callback writes.
                val now = System.currentTimeMillis()
                goneProcessedAt.keys.toList().forEach { key ->
                    goneProcessedAt.computeIfPresent(key) { _, v ->
                        if (now - v > GONE_DEDUP_MS) null else v
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan(preserveEntries: Boolean = false) {
        wantScanning = false
        if (!_isScanning.value) return
        cleanupJob?.cancel()
        cleanupJob = null
        val s = scanner ?: return
        try {
            s.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping nearby scan", e)
        }
        _isScanning.value = false
        if (!preserveEntries) {
            synchronized(rawEntries) {
                rawEntries.clear()
                _nearbyClimbs.value = emptyList()
            }
            synchronized(rawSessionEntries) {
                rawSessionEntries.clear()
                _nearbySessions.value = emptyList()
            }
            goneProcessedAt.clear()
        }
    }

    /**
     * Builds scan settings. MUST NOT use setLegacy(false) — this breaks Climb
     * Nearby Share scanning on some devices. Deep research confirmed that
     * setLegacyMode(true) on the advertiser produces correct Legacy PDUs,
     * so the default setLegacy(true) on the scanner is correct.
     */
    @VisibleForTesting
    internal fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    /**
     * Hardware scan filters for CRUX manufacturer data.
     * Matching on COMPANY_ID + CRUX magic prefix tells the BLE stack to deliver results
     * even when the app is in the background (Android 8+ throttles unfiltered scans).
     * Two filters: one for direct climb/session data (0xFFFF), one for embedded
     * session climb data in scan response (0xFFFE).
     */
    @VisibleForTesting
    internal fun buildScanFilters(): List<android.bluetooth.le.ScanFilter> {
        val magic = NearbyClimbProtocol.MAGIC
        // Mask: match only the first 4 bytes (CRUX magic), ignore the rest
        val mask = ByteArray(magic.size) { 0xFF.toByte() }
        return listOf(
            android.bluetooth.le.ScanFilter.Builder()
                .setManufacturerData(NearbyClimbProtocol.COMPANY_ID, magic, mask)
                .build(),
            android.bluetooth.le.ScanFilter.Builder()
                .setManufacturerData(NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID, magic, mask)
                .build()
        )
    }

    /**
     * Converts stale ClimbData entries to LastClimb and removes stale
     * BoardConnected entries. Must be called inside synchronized(rawEntries).
     *
     * When a sender's app is killed without sending GONE, the advertising simply stops.
     * Retaining controllers keep their LEDs on; volatile controllers such as a
     * stock MoonBoard keep only short-lived resend metadata.
     */
    private fun convertOrRemoveStale(now: Long, excludeAddress: String? = null) {
        // Retained LastClimb entries persist until replaced by GONE/new data/app
        // restart. Non-retained history expires even if the sender was killed
        // before it could send GONE.
        rawEntries.entries
            .filter { (_, entry) ->
                entry.isLastClimb &&
                    !entry.projectionSurvivesDisconnect &&
                    now - entry.lastSeenMs > NON_RETAINED_LAST_TIMEOUT_MS
            }
            .map { it.key }
            .forEach { rawEntries.remove(it) }

        // Convert stale active entries to LastClimb or remove
        val stale = rawEntries.entries.filter { (addr, entry) ->
            addr != excludeAddress &&
                !entry.isLastClimb &&
                now - entry.lastSeenMs > STALE_TIMEOUT_MS
        }
        // Find stale entries that should become LastClimb (have a climb UUID, not just BoardConnected)
        val toConvert = stale.filter { (_, e) -> e.climbUuid.isNotEmpty() && !e.connectedOnly }
        val toRemove = stale.filter { (_, e) -> e.climbUuid.isEmpty() || e.connectedOnly }

        toRemove.forEach { (addr, _) -> rawEntries.remove(addr) }

        if (toConvert.isNotEmpty()) {
            // The board can only show one climb at a time — keep only the most recently seen one.
            // A newly observed active climb makes older LastClimb metadata obsolete.
            rawEntries.keys.filter { rawEntries[it]!!.isLastClimb }
                .forEach { rawEntries.remove(it) }
            // Keep only the most recently active entry as LastClimb
            val newest = toConvert.maxBy { (_, e) -> e.lastSeenMs }
            toConvert.forEach { (addr, _) ->
                if (addr == newest.key) {
                    rawEntries[addr] = newest.value.copy(isLastClimb = true)
                    Log.d(TAG, "Stale ClimbData → LastClimb: ${newest.value.climbUuid} (advertising stopped without GONE)")
                } else {
                    rawEntries.remove(addr)
                }
            }
        }
    }

    /** Removes a specific entry by device address (e.g. when ignoring a stale LastClimb). */
    fun removeEntry(address: String) {
        synchronized(rawEntries) {
            if (rawEntries.remove(address) != null) {
                publishDeduped()
            }
        }
    }

    /** Deduplicates climb projections by UUID, keeping the strongest RSSI.
     *  BoardConnected has no climb UUID, so each sender must remain distinct;
     *  otherwise one nearby device can hide all other occupants (or our own
     *  loopback can hide a real peer).
     *  Only emits when the list structurally changed to avoid unnecessary
     *  combine re-triggers that cause UI flicker. */
    private fun publishDeduped() {
        val byUuid = rawEntries.values.groupBy {
            if (it.connectedOnly) "connected:${it.senderToken ?: it.deviceAddress}"
            else "climb:${it.climbUuid}"
        }
        val deduped = byUuid.map { (_, entries) ->
            entries.maxBy { it.rssi }
        }
        val current = _nearbyClimbs.value
        if (deduped.size == current.size && deduped.zip(current).all { (a, b) ->
            a.climbUuid == b.climbUuid && a.angle == b.angle &&
            a.isLastClimb == b.isLastClimb && a.connectedOnly == b.connectedOnly &&
            a.projectionSurvivesDisconnect == b.projectionSurvivesDisconnect &&
            a.senderToken == b.senderToken && a.deviceAddress == b.deviceAddress
        }) return
        _nearbyClimbs.value = deduped
        val sessionCount = rawSessionEntries.size
        Log.d(TAG, "PUBLISH climbs=${deduped.size} sessions=$sessionCount")
    }

    /** Deduplicates sessions by sessionId, keeping the entry with the strongest RSSI.
     *  Only emits when the list actually changed (structural equality) to avoid
     *  unnecessary combine re-triggers that cause UI flicker. */
    private fun publishDedupedSessions() {
        val byId = rawSessionEntries.values.groupBy { it.sessionId }
        val deduped = byId.map { (_, entries) ->
            entries.maxBy { it.rssi }
        }
        val current = _nearbySessions.value
        if (deduped.size == current.size && deduped.zip(current).all { (a, b) ->
            a.sessionId == b.sessionId && a.participantCount == b.participantCount &&
            a.currentClimbUuid == b.currentClimbUuid && a.currentClimbAngle == b.currentClimbAngle &&
            a.hostName == b.hostName
        }) return
        _nearbySessions.value = deduped
    }
}
