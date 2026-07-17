package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


/**
 * Advertises climb data to nearby CruxCoach users via BLE.
 *
 * Uses the AdvertisingSet API (API 26+) instead of the legacy startAdvertising/stopAdvertising.
 * This keeps the same BLE MAC address when updating payloads via [AdvertisingSet.setAdvertisingData],
 * so the receiving scanner sees one stable device address instead of a new one per climb switch.
 */
class ClimbBleAdvertiser(
    private val context: Context,
    private val boardStateManager: com.cruxcoach.android.data.BoardStateManager
) {

    companion object {
        private const val TAG = "CruxBLE/Advertiser"
        private const val DISCONNECT_REQUEST_TIMEOUT_MS = 20_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val advertiser get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private var disconnectTimeoutJob: Job? = null
    private var goneStopJob: Job? = null

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Priority state: session > climb > boardConnected > nothing.
    // When a session queue is active, individual climb advertising is suppressed —
    // the session handles sharing via GATT, not per-climb advertising.
    private data class ActiveClimb(
        val uuid: String,
        val angle: Int,
        val projectionSurvivesDisconnect: Boolean,
    )

    private var activeClimb: ActiveClimb? = null
    private var boardConnected: Boolean = false

    /** True when the local user has sent a climb to the board and is still connected. */
    fun hasActiveClimb(): Boolean = activeClimb != null

    /** Clears the active climb state without affecting BLE advertising.
     *  Used when a remote user overwrites the board LEDs — our climb is no longer displayed. */
    fun clearActiveClimb() {
        activeClimb = null
    }

    /** True when the board is connected (may or may not have an active climb). */
    fun isBoardConnected(): Boolean = boardConnected

    /** Returns the currently active climb (uuid, angle) or null. */
    fun getActiveClimb(): Pair<String, Int>? = activeClimb?.let { it.uuid to it.angle }

    /** Whether the active board is expected to retain its LEDs after disconnect. */
    fun activeProjectionSurvivesDisconnect(): Boolean =
        activeClimb?.projectionSurvivesDisconnect ?: true

    /**
     * When true, [advertiseClimb] and [advertiseConnected] are suppressed and
     * any active climb advertising is stopped. Set by [SessionGattBridge] when
     * a session starts (host or participant) and cleared when it ends.
     */
    @Volatile
    var suppressClimbAdvertising: Boolean = false
        set(value) {
            field = value
            if (value) {
                Log.d(TAG, "SUPPRESS climb ads (session starting)")
                stopClimbAdvertising()
            } else {
                Log.d(TAG, "UNSUPPRESS climb ads")
            }
        }

    // Primary AdvertisingSet — for climb/boardConnected/disconnectRequest
    private var currentSet: AdvertisingSet? = null
    // Dedicated Session AdvertisingSet — runs in parallel, independent of climb advertising
    private var sessionSet: AdvertisingSet? = null

    private val advertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Log.d(TAG, "AdvertisingSet started (txPower=$txPower)")
                currentSet = advertisingSet
                _isAdvertising.value = true
                _lastError.value = null
            } else {
                val reason = when (status) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    else -> "code=$status"
                }
                Log.e(TAG, "AdvertisingSet start failed: $reason")
                currentSet = null
                _isAdvertising.value = false
                _lastError.value = reason
            }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Log.w(TAG, "setAdvertisingData failed: status=$status")
                _lastError.value = "data update failed ($status)"
            } else {
                Log.d(TAG, "AdvertisingData updated in-place (same MAC)")
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            Log.d(TAG, "AdvertisingSet stopped")
            currentSet = null
            _isAdvertising.value = false
        }
    }

    /**
     * Returns `true` if advertising is supported, `false` if definitely not,
     * or `null` if we can't determine yet (e.g. Bluetooth is off).
     */
    fun checkSupported(): Boolean? {
        val adapter = bluetoothAdapter ?: run {
            _isSupported.value = false
            return false
        }
        if (!adapter.isEnabled) {
            _isSupported.value = null
            return null
        }
        val supported = adapter.bluetoothLeAdvertiser != null
        _isSupported.value = supported
        return supported
    }

    /** Advertises a specific climb. Highest priority — takes precedence over boardConnected.
     *  Suppressed when a session queue is active ([suppressClimbAdvertising] = true). */
    @SuppressLint("MissingPermission")
    fun advertiseClimb(
        climbUuid: String,
        angle: Int,
        sharingEnabled: Boolean = true,
        projectionSurvivesDisconnect: Boolean = true,
    ): String {
        activeClimb = ActiveClimb(climbUuid, angle, projectionSurvivesDisconnect)
        scope.launch {
            boardStateManager.setLastClimb(climbUuid, angle, projectionSurvivesDisconnect)
        }
        if (suppressClimbAdvertising) {
            Log.d(TAG, "advertiseClimb: suppressed (session active)")
            return "suppressed (session active)"
        }
        if (!sharingEnabled) return "sharing off (local only)"
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            return "no permission"
        }
        advertiser ?: return "no advertiser (BT off?)"
        disconnectTimeoutJob?.cancel()

        val payload = NearbyClimbProtocol.encodeClimbData(
            climbUuid,
            angle,
            projectionSurvivesDisconnect,
        )
        val data = buildAdvertiseData(payload)
        Log.d(TAG, "START ClimbData uuid=${climbUuid.take(8)} angle=$angle sharing=$sharingEnabled")

        return updateOrStartAdvertising(data)
    }

    /**
     * Marks the board as connected. Only broadcasts TYPE_BOARD_CONNECTED if no
     * climb is currently being advertised (climb has higher priority).
     */
    @SuppressLint("MissingPermission")
    fun advertiseConnected(acceptsDisconnect: Boolean = true): String {
        boardConnected = true
        if (suppressClimbAdvertising) {
            Log.d(TAG, "advertiseConnected: suppressed (session active)")
            return "suppressed (session active)"
        }
        if (activeClimb != null) {
            Log.d(TAG, "advertiseConnected: skipped, climb is active")
            return "skipped (climb active)"
        }
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            return "no permission"
        }
        advertiser ?: return "no advertiser (BT off?)"
        disconnectTimeoutJob?.cancel()

        val payload = NearbyClimbProtocol.encodeBoardConnected(acceptsDisconnect)
        val data = buildAdvertiseData(payload)
        return updateOrStartAdvertising(data)
    }

    /**
     * Clears the active climb. Falls back to advertising boardConnected
     * if the board is still connected; otherwise stops advertising.
     */
    @SuppressLint("MissingPermission")
    fun clearClimb() {
        activeClimb = null
        if (boardConnected) {
            Log.d(TAG, "clearClimb: falling back to boardConnected")
            if (!BlePermissionHelper.hasAdvertisingPermission(context)) return
            advertiser ?: return
            disconnectTimeoutJob?.cancel()
            val payload = NearbyClimbProtocol.encodeBoardConnected()
            val data = buildAdvertiseData(payload)
            updateOrStartAdvertising(data)
        } else {
            stopAdvertising()
        }
    }

    /**
     * Called when the board disconnects. If a climb was active, switches to
     * TYPE_LAST_CLIMB advertising for 30 seconds, then sends GONE
     * and stops. The payload explicitly distinguishes retained LEDs from a
     * MoonBoard climb that is only available for reconnect/resend.
     */
    @SuppressLint("MissingPermission")
    fun onBoardDisconnected(sharingEnabled: Boolean = true) {
        val lastClimb = activeClimb
        val wasConnected = boardConnected
        activeClimb = null
        boardConnected = false

        // Guard: duplicate call (e.g., second BleConnectionViewModel instance on another screen)
        // or initial DISCONNECTED emission on ViewModel creation. Don't touch lastProjectedClimb.
        if (lastClimb == null && !wasConnected) return

        if (lastClimb != null) {
            Log.d(TAG, "DISCONNECT lastClimb=${lastClimb.uuid.take(8)} retained=${lastClimb.projectionSurvivesDisconnect} wasConnected=$wasConnected → advertising LastClimb for 30s")
            // Dedup: setLastClimb was already called in advertiseClimb() (same UUID+angle → skip)
            scope.launch {
                boardStateManager.setLastClimb(
                    lastClimb.uuid,
                    lastClimb.angle,
                    lastClimb.projectionSurvivesDisconnect,
                )
            }

            // Try BLE advertising for remote visibility (only with sharing enabled)
            if (sharingEnabled && BlePermissionHelper.hasAdvertisingPermission(context) && advertiser != null) {
                val payload = NearbyClimbProtocol.encodeLastClimb(
                    lastClimb.uuid,
                    lastClimb.angle,
                    lastClimb.projectionSurvivesDisconnect,
                )
                val data = buildAdvertiseData(payload)
                updateOrStartAdvertising(data)
                // Bug 2: LastClimb advertising for 30s so remote scanners reliably receive it.
                // After 30s, send GONE and stop. If a new advertiseClimb()/advertiseConnected()
                // arrives before 30s, goneStopJob is cancelled by updateOrStartAdvertising().
                goneStopJob?.cancel()
                goneStopJob = scope.launch {
                    delay(30_000)
                    Log.d(TAG, "DISCONNECT LastClimb 30s expired → sending GONE")
                    sendGoneAndStop()
                }
            }
        } else if (sharingEnabled) {
            // Was connected but no climb was active — stop advertising.
            // Preserve the manager's previous last-climb metadata: a connection
            // without a new send gives us no evidence that it was overwritten.
            sendGoneAndStop()
        }
    }

    /**
     * Advertises a session queue on a **dedicated AdvertisingSet**, independent of
     * climb advertising. Both sets run in parallel so nearby users always see the
     * session, even while the host is projecting climbs.
     */
    @SuppressLint("MissingPermission")
    fun advertiseSession(
        sessionId: Int,
        participantCount: Int,
        hostName: String,
        climbUuid: String? = null,
        climbAngle: Int = 0
    ): String {
        Log.d(TAG, "advertiseSession: sessionId=$sessionId, count=$participantCount, " +
            "host='$hostName', climb=${climbUuid?.take(8)}, sessionSet=${sessionSet != null}")
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            Log.w(TAG, "advertiseSession: no advertising permission")
            return "no permission"
        }
        val adv = advertiser ?: run {
            Log.w(TAG, "advertiseSession: advertiser is null (BT off?)")
            return "no advertiser (BT off?)"
        }

        val payload = NearbyClimbProtocol.encodeSessionAdvertisement(sessionId, participantCount, hostName)
        val advData = buildAdvertiseData(payload)
        val scanResponse = buildSessionScanResponse(climbUuid, climbAngle)
        Log.d(TAG, "advertiseSession: payload=${payload.size} bytes (in ADV_IND, climb in SCAN_RSP)")

        val existingSet = sessionSet
        if (existingSet != null) {
            // Update ADV_IND + SCAN_RSP payloads in-place — keeps the same MAC
            try {
                existingSet.setAdvertisingData(advData)
                existingSet.setScanResponseData(scanResponse)
                Log.d(TAG, "Session advertising updated in-place (same MAC)")
                return "updated"
            } catch (e: Exception) {
                Log.w(TAG, "Session setAdvertisingData failed, restarting", e)
                stopSessionAdvertisingInternal()
            }
        }

        // Clean up any pending/active session advertising to avoid
        // "callback instance already associated" if recovery races with normal start.
        stopSessionAdvertisingInternal()

        // Start new session advertising set — connectable so GATT clients
        // can connect using the same address they see in the scan result
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // ~100ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        Log.d(TAG, "advertiseSession: starting NEW advertising set " +
            "(legacy, connectable, scannable, payload in ADV_IND, TX power in SCAN_RSP)")
        try {
            adv.startAdvertisingSet(params, advData, scanResponse, null, null, sessionSetCallback)
            Log.d(TAG, "Session advertising started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session advertising set", e)
            return "failed: ${e.message}"
        }
        return "started"
    }

    /** Stops session advertising. Sends GONE via the session set first so scanners clean up
     *  immediately instead of waiting for stale timeout. Does NOT affect climb advertising. */
    @SuppressLint("MissingPermission")
    fun stopSessionAdvertising() {
        val set = sessionSet
        if (set != null && BlePermissionHelper.hasAdvertisingPermission(context)) {
            try {
                val gonePayload = NearbyClimbProtocol.encodeGone()
                set.setAdvertisingData(buildAdvertiseData(gonePayload))
                // Brief delay for scanner to pick up GONE, then stop
                scope.launch {
                    delay(200)
                    stopSessionAdvertisingInternal()
                }
                Log.d(TAG, "Session advertising: sent GONE (via scan response), stopping shortly")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send session GONE, stopping immediately", e)
            }
        }
        stopSessionAdvertisingInternal()
        Log.d(TAG, "Session advertising stopped")
    }

    private val sessionSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                sessionSet = advertisingSet
                Log.d(TAG, "Session AdvertisingSet started (txPower=$txPower)")
            } else {
                Log.e(TAG, "Session AdvertisingSet start failed: status=$status")
                sessionSet = null
            }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                Log.w(TAG, "Session setAdvertisingData failed: status=$status")
                // Set might have been stopped (e.g. after GATT client connected).
                // Clear it so the next advertiseSession() call creates a new set.
                sessionSet = null
            }
        }

        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            if (!enable) {
                // BLE controller disabled advertising (e.g. after a client connected
                // to this connectable set). Clear the reference so it gets restarted.
                Log.d(TAG, "Session advertising disabled by controller (client connected?)")
                sessionSet = null
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            sessionSet = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopSessionAdvertisingInternal() {
        val set = sessionSet ?: return
        val adv = advertiser
        if (adv != null) {
            try {
                adv.stopAdvertisingSet(sessionSetCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping session advertising set", e)
            }
        }
        sessionSet = null
    }

    // --- CruxRelay board-emulation advertising (FEAT-044) ---
    private var relaySet: AdvertisingSet? = null
    /** Async start result of the current [startRelayAdvertising] attempt —
     *  completed with the controller status from [relaySetCallback] so the
     *  relay manager can surface a failure instead of assuming success. */
    private var relayStartResult: CompletableDeferred<Int>? = null
    private val relaySetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                relaySet = advertisingSet
                Log.d(TAG, "Relay advertising started (txPower=$txPower)")
            } else {
                Log.e(TAG, "Relay advertising failed: status=$status")
                relaySet = null
            }
            relayStartResult?.complete(status)
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) { relaySet = null }
    }

    /**
     * Advertise as a board so the OFFICIAL Kilter app lists CruxRelay. The
     * listing gate is the board ADVERTISING service UUID (4488B571), placed in
     * the connectable ADV_IND; the transparent name (set on the adapter by
     * [com.cruxcoach.android.data.CruxRelayManager]) rides the SCAN_RESPONSE —
     * the 128-bit UUID already fills the 31-byte ADV_IND. Connectable so the app
     * opens a GATT link to [RelayGattServer].
     */
    @SuppressLint("MissingPermission")
    fun startRelayAdvertising(): String {
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) return "no permission"
        val adv = advertiser ?: return "no advertiser (BT off?)"
        stopRelayAdvertisingInternal()
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BoardBleUuids.ADVERTISING_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) // the adapter's (transparent) name
            .build()
        val started = CompletableDeferred<Int>()
        relayStartResult = started
        return try {
            adv.startAdvertisingSet(params, advData, scanResponse, null, null, relaySetCallback)
            "started"
        } catch (e: Exception) {
            Log.e(TAG, "startRelayAdvertising failed", e)
            relayStartResult = null
            "failed: ${e.message}"
        }
    }

    /** Await the async controller result ([AdvertisingSetCallback.onAdvertisingSetStarted])
     *  of the in-flight [startRelayAdvertising]. Null when nothing is in flight
     *  (stopped meanwhile); otherwise the status ([AdvertisingSetCallback.ADVERTISE_SUCCESS]
     *  = 0 on success). The caller bounds the wait with its own timeout. */
    suspend fun awaitRelayAdvertisingStart(): Int? = relayStartResult?.await()

    @SuppressLint("MissingPermission")
    fun stopRelayAdvertising() = stopRelayAdvertisingInternal()

    @SuppressLint("MissingPermission")
    private fun stopRelayAdvertisingInternal() {
        val adv = advertiser ?: return
        // Unconditional stop: relaySet is only assigned in the ASYNC start
        // callback, so a stop racing that callback would otherwise skip
        // stopAdvertisingSet and leak a live advertising set.
        try { adv.stopAdvertisingSet(relaySetCallback) } catch (e: Exception) {
            Log.e(TAG, "Error stopping relay advertising", e)
        }
        relaySet = null
        relayStartResult = null
    }

    /** Broadcasts a disconnect response (accepted/rejected), then stops after 200ms. */
    @SuppressLint("MissingPermission")
    fun advertiseDisconnectResponse(accepted: Boolean) {
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) return
        advertiser ?: return
        goneStopJob?.cancel()
        goneStopJob = null

        val payload = NearbyClimbProtocol.encodeDisconnectResponse(accepted)
        val data = buildAdvertiseData(payload)
        stopAdvertisingInternal()
        startAdvertisingSet(data)
        scope.launch {
            delay(200)
            stopAdvertisingInternal()
        }
    }

    @SuppressLint("MissingPermission")
    fun advertiseDisconnectRequest() {
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            Log.w(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }
        advertiser ?: run {
            Log.w(TAG, "BLE advertiser unavailable (Bluetooth off?)")
            return
        }
        disconnectTimeoutJob?.cancel()
        // Cancel any pending GONE delayed stop — it would kill the disconnect request set
        goneStopJob?.cancel()
        goneStopJob = null

        val payload = NearbyClimbProtocol.encodeDisconnectRequest()
        val data = buildAdvertiseData(payload)
        // Disconnect request is ephemeral — stop existing set and start fresh
        // (different payload type, don't want to keep the old set)
        stopAdvertisingInternal()
        startAdvertisingSet(data)

        disconnectTimeoutJob = scope.launch {
            delay(DISCONNECT_REQUEST_TIMEOUT_MS)
            stopAdvertising()
        }
    }

    /**
     * Advertises the last climb via BLE LAST_CLIMB payload (remote visibility only).
     * Does NOT manage state — [BoardStateManager] is the single source of truth.
     */
    @SuppressLint("MissingPermission")
    fun advertiseLastClimb(
        climbUuid: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ) {
        Log.d(TAG, "advertiseLastClimb: $climbUuid angle=$angle retained=$projectionSurvivesDisconnect")
        if (BlePermissionHelper.hasAdvertisingPermission(context) && advertiser != null) {
            val payload = NearbyClimbProtocol.encodeLastClimb(
                climbUuid,
                angle,
                projectionSurvivesDisconnect,
            )
            val data = buildAdvertiseData(payload)
            updateOrStartAdvertising(data)
        }
    }

    /** Stops climb advertising (currentSet) without touching session advertising.
     *  Called when [suppressClimbAdvertising] is set to true. */
    private fun stopClimbAdvertising() {
        activeClimb = null
        // Preserve last-climb metadata while the session takes over.
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null
        sendGoneAndStop()
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null
        // Advertising lifecycle is separate from saved last-climb metadata.
        sendGoneAndStop()
    }

    /**
     * Sends a TYPE_GONE payload via the existing AdvertisingSet (preserving MAC so the
     * scanner recognises the sender), waits briefly for it to broadcast, then stops.
     * If no set is active, just stops immediately.
     */
    @SuppressLint("MissingPermission")
    private fun sendGoneAndStop() {
        goneStopJob?.cancel()
        val set = currentSet
        if (set != null && BlePermissionHelper.hasAdvertisingPermission(context)) {
            try {
                val gonePayload = NearbyClimbProtocol.encodeGone()
                set.setAdvertisingData(buildAdvertiseData(gonePayload))
                // Give the scanner ~200ms to pick up the GONE packet before tearing down
                goneStopJob = scope.launch {
                    delay(200)
                    stopAdvertisingInternal()
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send GONE payload, stopping immediately", e)
            }
        }
        stopAdvertisingInternal()
    }

    // --- Internal ---

    /**
     * Builds a non-empty scan response for session advertising.
     * Samsung BLE stacks silently drop ADV_IND results when SCAN_RSP is missing,
     * because ADV_IND is scannable by spec — Samsung sends SCAN_REQ and waits for
     * SCAN_RSP. Without a valid response, onScanResult() is never called.
     *
     * This is why Climb Share (non-scannable) works without scan response,
     * but Session Share (scannable+connectable) requires one.
     */
    @VisibleForTesting
    internal fun buildSessionScanResponse(climbUuid: String? = null, climbAngle: Int = 0): AdvertiseData {
        val builder = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
        if (climbUuid != null) {
            val climbPayload = NearbyClimbProtocol.encodeClimbData(climbUuid, climbAngle)
            builder.addManufacturerData(NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID, climbPayload)
        }
        return builder.build()
    }

    @VisibleForTesting
    internal fun buildAdvertiseData(payload: ByteArray): AdvertiseData {
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(NearbyClimbProtocol.COMPANY_ID, payload)
            .build()
    }

    /** Exposed for tests to verify session advertising configuration. */
    @VisibleForTesting
    internal fun getSessionSet(): AdvertisingSet? = sessionSet

    /**
     * If an AdvertisingSet is already running, updates its payload in-place
     * (preserving the MAC address). Otherwise starts a new set.
     */
    @SuppressLint("MissingPermission")
    private fun updateOrStartAdvertising(data: AdvertiseData): String {
        // Cancel any pending GONE delayed stop — it would kill this new advertising.
        goneStopJob?.cancel()
        goneStopJob = null
        val set = currentSet
        if (set != null) {
            // Update payload without stop/restart — keeps the same BLE MAC address.
            try {
                set.setAdvertisingData(data)
                return "updated"
            } catch (e: Exception) {
                Log.w(TAG, "setAdvertisingData failed, restarting set", e)
                stopAdvertisingInternal()
            }
        }
        startAdvertisingSet(data)
        return "started"
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingSet(data: AdvertiseData) {
        val adv = advertiser ?: return

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true) // BLE 4.x compatible (wider device support)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // ~100ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        try {
            adv.startAdvertisingSet(params, data, null, null, null, advertisingSetCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising set", e)
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal() {
        val set = currentSet
        if (set != null) {
            val adv = advertiser
            if (adv != null) {
                try {
                    adv.stopAdvertisingSet(advertisingSetCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping advertising set", e)
                }
            }
            currentSet = null
        }
        _isAdvertising.value = false
    }
}
