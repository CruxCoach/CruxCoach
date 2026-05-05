package com.cruxcoach.android.ui.board

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardBleScanner
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.data.NearbyPresenceManager
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class BleConnectionState(
    val hasPermissions: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val discoveredBoards: List<DiscoveredBoard> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedBoardName: String? = null,
    val isRequestingDisconnect: Boolean = false,
    val disconnectRequestNoResponse: Boolean = false,
    val climbSharingEnabled: Boolean = false,
    val allowRemoteDisconnect: Boolean = false,
    val showDisconnectRequestDialog: Boolean = false,
    /**
     * Mirror of [UserPreferences.quickBoardSend]. The detail screen reads this
     * to decide whether the BLE icon opens the connection sheet (off) or
     * triggers the [BleConnectionViewModel.startQuickSend] macro (on).
     */
    val quickBoardSendEnabled: Boolean = false,
    /**
     * True while a scan started via [BleConnectionViewModel.startScanWithAutoConnect]
     * is still inside its 2 s settling window. The sheet uses this to know whether
     * a 1-board result should auto-resolve into a connect (true) or whether the
     * user explicitly opened the sheet to inspect the list (false).
     */
    val isAutoConnectScan: Boolean = false,
)

/**
 * Status emitted by the Quick-Send macro (Settings → "Schnell-Senden").
 * The screen renders this as a status overlay / snackbar; only one quick-send
 * job runs at a time.
 */
sealed class QuickSendStatus {
    data object Idle : QuickSendStatus()
    data object Scanning : QuickSendStatus()
    /** 2+ boards found in the settling window — fall back to manual pick UI. */
    data class NeedsManualPick(val boards: List<DiscoveredBoard>) : QuickSendStatus()
    data class Connecting(val boardName: String) : QuickSendStatus()
    data object Sending : QuickSendStatus()
    data object Disconnecting : QuickSendStatus()
    data object Done : QuickSendStatus()
    data class Error(val reason: ErrorReason) : QuickSendStatus()

    enum class ErrorReason {
        NoBoardsFound,
        ConnectFailed,
        SendFailed,
        BluetoothOff,
        NoPermissions,
    }
}

@HiltViewModel
class BleConnectionViewModel @Inject constructor(
    private val application: Application,
    private val bleScanner: BoardBleScanner,
    private val bleConnection: BoardBleConnection,
    private val userPreferences: UserPreferences,
    private val nearbyClimbScanner: NearbyClimbScanner,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val nearbyPresenceManager: NearbyPresenceManager
) : ViewModel() {

    companion object {
        private const val TAG = "BleConnectionViewModel"
        /** How long to wait for additional ad packets after the first board is seen. */
        private const val SETTLING_WINDOW_MS = 2_000L
        /** Total scan deadline when no board has been found yet at the settling cutoff. */
        private const val SCAN_EXTENDED_MS = 6_000L
        /** Timeout for the GATT connect handshake (CONNECTING → CONNECTED). */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /** How long to wait for the auto-send to flip ConnectionState to SENDING. */
        private const val SEND_START_TIMEOUT_MS = 3_000L
    }

    private val _state = MutableStateFlow(BleConnectionState())
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            bleScanner.discoveredBoards.collect { boards ->
                _state.update { it.copy(discoveredBoards = boards) }
            }
        }
        viewModelScope.launch {
            bleScanner.isScanning.collect { scanning ->
                _state.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.launch {
            bleConnection.connectionState.collect { connState ->
                _state.update { it.copy(connectionState = connState) }
                // Auto-advertise "board connected" so nearby users can send disconnect requests
                if (connState == ConnectionState.CONNECTED) {
                    suppressDisconnectDialog = false
                    if (_state.value.climbSharingEnabled) {
                        climbAdvertiser.advertiseConnected(
                            acceptsDisconnect = _state.value.allowRemoteDisconnect
                        )
                    }
                } else if (connState == ConnectionState.DISCONNECTED) {
                    climbAdvertiser.onBoardDisconnected(_state.value.climbSharingEnabled)
                }
                // Always ensure nearby scanner is running after any connection state change.
                // onStopScannersForConnect kills it before GATT connect, but
                // onRestartScannersAfterConnect only fires on successful service discovery.
                // If the user dismisses the BLE sheet mid-connect, or the connection
                // fails/times out, the nearby scanner stays dead until app restart.
                if (connState != ConnectionState.CONNECTING) {
                    nearbyClimbScanner.startScan(clearExisting = false)
                }
            }
        }
        viewModelScope.launch {
            bleConnection.connectedBoardName.collect { name ->
                _state.update { it.copy(connectedBoardName = name) }
            }
        }
        viewModelScope.launch {
            bleScanner.bluetoothEnabled.collect { enabled ->
                _state.update { it.copy(isBluetoothEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            bleConnection.autoDisconnectSeconds = userPreferences.bleAutoDisconnectSeconds.first()
            userPreferences.bleAutoDisconnectSeconds.collect { seconds ->
                bleConnection.autoDisconnectSeconds = seconds
            }
        }
        viewModelScope.launch {
            userPreferences.nearbyClimbSharing.collect { enabled ->
                _state.update { it.copy(climbSharingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.allowRemoteDisconnect.collect { allowed ->
                _state.update { it.copy(allowRemoteDisconnect = allowed) }
            }
        }
        viewModelScope.launch {
            userPreferences.quickBoardSend.collect { enabled ->
                _state.update { it.copy(quickBoardSendEnabled = enabled) }
            }
        }
        // Receive disconnect requests from nearby users (works on any screen)
        viewModelScope.launch {
            nearbyClimbScanner.disconnectRequests.collect {
                val s = _state.value
                val now = System.currentTimeMillis()
                if (s.connectionState != ConnectionState.CONNECTED) return@collect
                if (!s.allowRemoteDisconnect) {
                    // Auto-reject: broadcast rejection so the sender knows immediately
                    climbAdvertiser.advertiseDisconnectResponse(accepted = false)
                    return@collect
                }
                if (!s.showDisconnectRequestDialog
                    && !suppressDisconnectDialog
                    && now >= disconnectCooldownUntil
                ) {
                    _state.update { it.copy(showDisconnectRequestDialog = true) }
                }
            }
        }

        // Auto-connect to board when entering a session (HOST or PARTICIPANT) while not connected.
        // HOST: SessionGattBridge already sent the DisconnectRequest — wait for board to become vacant.
        // PARTICIPANT: GATT connection to host succeeded — connect to board for LED control.
        var previousQueueRole = SessionRole.NONE
        viewModelScope.launch {
            sessionQueueManager.state.collect { queueState ->
                val newRole = queueState.role
                if (newRole != previousQueueRole) {
                    Log.d(TAG, "Queue role changed: $previousQueueRole → $newRole, " +
                        "connectionState=${_state.value.connectionState}")
                }
                if ((newRole == SessionRole.HOST || newRole == SessionRole.PARTICIPANT)
                    && previousQueueRole != newRole
                    && _state.value.connectionState == ConnectionState.DISCONNECTED
                ) {
                    Log.d(TAG, "Role became $newRole while disconnected → triggering auto-connect")
                    startAutoConnectForSession()
                }
                previousQueueRole = newRole
            }
        }

        // Wire scanner stop/restart callbacks for GATT connect (radio contention fix).
        // preserveEntries=true / clearExisting=false prevents banner flash during connect.
        bleConnection.onStopScannersForConnect = {
            bleScanner.stopScan()
            nearbyClimbScanner.stopScan(preserveEntries = true)
        }
        bleConnection.onRestartScannersAfterConnect = {
            nearbyClimbScanner.startScan(clearExisting = false)
        }

        checkState()
    }

    fun checkState() {
        _state.update { it.copy(
            hasPermissions = BlePermissionHelper.hasPermissions(application),
            isBluetoothEnabled = bleScanner.isBluetoothEnabled()
        ) }
    }

    fun onPermissionsGranted() {
        _state.update { it.copy(hasPermissions = true) }
        checkState()
        // Bug 5: Retry nearby scanner after permission grant (first app start)
        nearbyPresenceManager.retryScan()
    }

    fun startScan() {
        viewModelScope.launch {
            // Wait for any pending GATT close to finish before scanning.
            // Android suppresses connectable scan results for a device whose GATT
            // handle is still open — the board won't appear until close() completes.
            bleConnection.awaitGattClosed()

            // On Android <12, stop the nearby scanner first to avoid BLE scan conflicts.
            // Some Android 9 BLE stacks fail with SCAN_FAILED_ALREADY_STARTED when
            // multiple scan registrations are active simultaneously.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                nearbyClimbScanner.stopScan(preserveEntries = true)
            }

            bleScanner.startScan()
        }
    }

    /**
     * Scan with auto-connect on single result. Settles for [SETTLING_WINDOW_MS]
     * after the scan starts, then:
     *  - exactly 1 board → connect to it (sheet flips through "Connecting…" to
     *    the connected state without the user tapping a list entry).
     *  - 2+ boards → leave the list visible for manual pick (existing UX).
     *  - 0 boards → keep scanning, fall back to manual pick after the user waits.
     *
     * The settling window absorbs BLE adv jitter — boards ad every 100-1000 ms,
     * so racing on the first packet would auto-connect to a board that "won
     * the race" while a sibling board's first ad arrives 200 ms later.
     */
    fun startScanWithAutoConnect() {
        autoConnectScanJob?.cancel()
        _state.update { it.copy(isAutoConnectScan = true) }
        autoConnectScanJob = viewModelScope.launch {
            startScan()
            delay(SETTLING_WINDOW_MS)
            val s = _state.value
            // Bail out if state changed during the wait: user disconnected the
            // sheet, scan stopped, or a connect already happened in another
            // thread.
            if (s.connectionState != ConnectionState.DISCONNECTED || !s.isScanning) {
                _state.update { it.copy(isAutoConnectScan = false) }
                return@launch
            }
            val boards = s.discoveredBoards
            if (boards.size == 1) {
                connectToBoard(boards.first())
            }
            // Leave isAutoConnectScan=true for 0/2+: at 0 the user keeps
            // waiting, at 2+ the list is now visible and they pick — flag
            // doesn't drive UI past this point but reads useful in logcat.
            _state.update { it.copy(isAutoConnectScan = false) }
        }
    }

    fun stopScan() {
        autoConnectScanJob?.cancel()
        _state.update { it.copy(isAutoConnectScan = false) }
        bleScanner.stopScan()
        // Restart nearby scanner if it was stopped for the board scan
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            nearbyClimbScanner.startScan(clearExisting = false)
        }
    }

    fun connectToBoard(board: DiscoveredBoard) {
        bleScanner.stopScan()
        bleConnection.connect(board)
    }

    fun disconnect() {
        bleConnection.disconnect()
    }

    private var disconnectTimeoutJob: Job? = null
    private var autoConnectJob: Job? = null
    private var autoConnectScanJob: Job? = null
    private var quickSendJob: Job? = null
    private var disconnectCooldownUntil = 0L
    /** Set after accepting a remote disconnect — suppresses the dialog until next connect. */
    private var suppressDisconnectDialog = false

    private val _quickSend = MutableStateFlow<QuickSendStatus>(QuickSendStatus.Idle)
    val quickSend: StateFlow<QuickSendStatus> = _quickSend.asStateFlow()

    /**
     * Quick-Send macro: scan → auto-connect (or fall back to manual pick) →
     * the existing CONNECTED-collector in BoardClimbDetailViewModel auto-fires
     * a send → wait for SENDING→CONNECTED transition → disconnect.
     *
     * Reuses the existing pipeline: BoardClimbDetailViewModel already auto-
     * triggers `sendController.sendToBoard()` on the DISCONNECTED→CONNECTED
     * transition when holds are present, and BoardBleConnection flips its
     * state to SENDING during the actual write. We just observe those state
     * machine edges from here — no new send-callback needed.
     *
     * For routes ([isRoute] = true) the macro stops after the connect — only
     * frame 0 gets auto-sent and the user is expected to start route
     * playback manually + disconnect when they're done. Auto-disconnecting
     * after the first frame would strand a multi-frame route mid-playback.
     */
    fun startQuickSend(isRoute: Boolean = false) {
        quickSendJob?.cancel()
        quickSendJob = viewModelScope.launch {
            try {
                if (!_state.value.hasPermissions) {
                    _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.NoPermissions)
                    return@launch
                }
                if (!_state.value.isBluetoothEnabled) {
                    _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.BluetoothOff)
                    return@launch
                }

                // Already connected → skip scan/connect. The screen will
                // tap into existing send pipeline; we only own the
                // disconnect-after (boulders only). Routes bail silently —
                // the user already sees the green BLE icon, and a
                // "sent + disconnected" snackbar would be a lie since
                // we kept the connection alive on purpose.
                if (bleConnection.connectionState.value == ConnectionState.CONNECTED) {
                    if (isRoute) {
                        _quickSend.value = QuickSendStatus.Idle
                    } else {
                        awaitSendAndDisconnect()
                    }
                    return@launch
                }

                _quickSend.value = QuickSendStatus.Scanning
                bleConnection.awaitGattClosed()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    nearbyClimbScanner.stopScan(preserveEntries = true)
                }
                bleScanner.startScan()

                // Settling window — give the scan time to find sibling boards
                // before deciding "single → auto-connect".
                delay(SETTLING_WINDOW_MS)
                val initial = bleScanner.discoveredBoards.value

                val target: DiscoveredBoard = when {
                    initial.size == 1 -> initial.first()
                    initial.size > 1 -> {
                        bleScanner.stopScan()
                        _quickSend.value = QuickSendStatus.NeedsManualPick(initial)
                        return@launch
                    }
                    else -> {
                        // 0 boards yet — keep scanning up to the extended deadline.
                        val later = withTimeoutOrNull(SCAN_EXTENDED_MS) {
                            bleScanner.discoveredBoards.first { it.isNotEmpty() }
                        } ?: emptyList()
                        when {
                            later.isEmpty() -> {
                                bleScanner.stopScan()
                                _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.NoBoardsFound)
                                return@launch
                            }
                            later.size == 1 -> later.first()
                            else -> {
                                bleScanner.stopScan()
                                _quickSend.value = QuickSendStatus.NeedsManualPick(later)
                                return@launch
                            }
                        }
                    }
                }

                _quickSend.value = QuickSendStatus.Connecting(target.displayName)
                bleScanner.stopScan()
                bleConnection.connect(target)

                val terminal = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    bleConnection.connectionState.first {
                        it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
                    }
                }
                if (terminal != ConnectionState.CONNECTED) {
                    _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.ConnectFailed)
                    return@launch
                }

                if (isRoute) {
                    // Route: connect succeeded, frame 0 will auto-send via
                    // ClimbDetailVM's CONNECTED-collector — but we don't
                    // chase the SENDING→CONNECTED→disconnect chain because
                    // the user still needs the connection alive for the
                    // remaining frames during playback. Reset to Idle so
                    // no "sent + disconnected" snackbar fires.
                    _quickSend.value = QuickSendStatus.Idle
                } else {
                    awaitSendAndDisconnect()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "quickSend failed", e)
                bleScanner.stopScan()
                _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.SendFailed)
            }
        }
    }

    /**
     * After the screen-side ClimbDetailVM auto-fires `sendController.sendToBoard()`
     * on the CONNECTED transition, BoardBleConnection flips state to SENDING
     * for the duration of the BLE write, then back to CONNECTED. We watch that
     * transition (with a fallback timeout if no send actually fired — e.g. the
     * climb's holds list was empty) and then disconnect.
     */
    private suspend fun awaitSendAndDisconnect() {
        _quickSend.value = QuickSendStatus.Sending
        // Wait for SENDING to start (within a short fallback window — if the
        // ClimbDetailVM's auto-send-on-connect didn't trigger, e.g. the climb
        // still had no holds, we don't want to hang forever).
        val sendStarted = withTimeoutOrNull(SEND_START_TIMEOUT_MS) {
            bleConnection.connectionState.first { it == ConnectionState.SENDING }
        } != null
        if (!sendStarted) {
            // No SENDING signal — ClimbDetailVM didn't fire a send.
            // Disconnect anyway so we don't strand the user on a connected
            // board they didn't expect to use long-term.
            Log.w(TAG, "quickSend: send did not start within ${SEND_START_TIMEOUT_MS}ms — disconnecting anyway")
            _quickSend.value = QuickSendStatus.Disconnecting
            bleConnection.disconnect()
            _quickSend.value = QuickSendStatus.Error(QuickSendStatus.ErrorReason.SendFailed)
            return
        }
        // Wait for SENDING → CONNECTED (success) or → DISCONNECTED (peer
        // dropped or write threw).
        val terminal = bleConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
        }
        _quickSend.value = QuickSendStatus.Disconnecting
        bleConnection.disconnect()
        _quickSend.value = if (terminal == ConnectionState.CONNECTED) {
            QuickSendStatus.Done
        } else {
            QuickSendStatus.Error(QuickSendStatus.ErrorReason.SendFailed)
        }
    }

    fun resetQuickSend() {
        _quickSend.value = QuickSendStatus.Idle
    }

    /**
     * Banner-free quick-send for the climb editor: connect, await
     * [send] (which must itself await the BLE write to completion),
     * disconnect.
     *
     * Differs from [startQuickSend] in three ways:
     *  * Never touches [_quickSend] — the editor doesn't surface a
     *    "Sending"/"Connecting"/"Done" snackbar (would be visual noise
     *    on every hold-tap).
     *  * Caller supplies the [send] action so the macro doesn't depend
     *    on the detail-VM's auto-send-on-CONNECTED collector.
     *  * Always disconnects after the send (no isRoute exemption).
     *
     * [send] MUST suspend until the underlying sendClimb call returns.
     * Earlier the macro observed the SENDING→CONNECTED edge instead,
     * but a fire-and-forget [send] would let `first { != SENDING }`
     * resolve immediately on the still-CONNECTED value and tear GATT
     * down before any bytes hit the wire.
     *
     * If the BLE prerequisites are missing (no permissions, BT off, or
     * the scan returns 0 / >1 boards) the macro silently exits — quick-
     * send mode is best-effort by design and the editor screen has no
     * non-banner channel to nag the user.
     */
    fun silentQuickSend(send: suspend () -> Unit) {
        quickSendJob?.cancel()
        quickSendJob = viewModelScope.launch {
            try {
                if (!_state.value.hasPermissions || !_state.value.isBluetoothEnabled) {
                    Log.d(TAG, "silentQuickSend: prereqs missing — skipping")
                    return@launch
                }

                // Already connected → just send + disconnect. send()
                // is suspending and awaits the actual BLE write, so
                // we can disconnect straight after — no state-machine
                // observation needed.
                if (bleConnection.connectionState.value == ConnectionState.CONNECTED) {
                    send()
                    bleConnection.disconnect()
                    return@launch
                }

                bleConnection.awaitGattClosed()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    nearbyClimbScanner.stopScan(preserveEntries = true)
                }
                bleScanner.startScan()
                delay(SETTLING_WINDOW_MS)
                val initial = bleScanner.discoveredBoards.value
                val target = when {
                    initial.size == 1 -> initial.first()
                    initial.size > 1 -> {
                        bleScanner.stopScan()
                        Log.d(TAG, "silentQuickSend: ${initial.size} boards in range — skip (no manual-pick UI in editor)")
                        return@launch
                    }
                    else -> withTimeoutOrNull(SCAN_EXTENDED_MS) {
                        bleScanner.discoveredBoards.first { it.isNotEmpty() }
                    }?.let { later ->
                        if (later.size == 1) later.first() else null
                    } ?: run {
                        bleScanner.stopScan()
                        Log.d(TAG, "silentQuickSend: no boards in range")
                        return@launch
                    }
                }
                bleScanner.stopScan()
                bleConnection.connect(target)
                val terminal = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    bleConnection.connectionState.first {
                        it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
                    }
                }
                if (terminal != ConnectionState.CONNECTED) {
                    Log.d(TAG, "silentQuickSend: connect failed terminal=$terminal")
                    return@launch
                }
                send()
                bleConnection.disconnect()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "silentQuickSend failed", e)
                bleScanner.stopScan()
            }
        }
    }

    fun cancelQuickSend() {
        quickSendJob?.cancel()
        bleScanner.stopScan()
        _quickSend.value = QuickSendStatus.Idle
    }

    fun requestDisconnect() {
        climbAdvertiser.advertiseDisconnectRequest()
        _state.update { it.copy(
            isRequestingDisconnect = true,
            disconnectRequestNoResponse = false
        ) }

        // Watch nearby advertising — wait until there are no active climb connections
        // (LastClimb entries are OK — they just mean LEDs still on from a disconnected device).
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            nearbyClimbScanner.nearbyClimbs.first { climbs ->
                climbs.none { !it.isLastClimb } && _state.value.isRequestingDisconnect
            }
            // Other user disconnected (stopped advertising) — now connect
            disconnectTimeoutJob?.cancel()
            bleScanner.startScan()
            val board = bleScanner.discoveredBoards.first { it.isNotEmpty() }.first()
            bleScanner.stopScan()
            bleConnection.connect(board)
            _state.update { it.copy(
                isRequestingDisconnect = false,
                disconnectRequestNoResponse = false
            ) }
        }

        // Timeout: no response after 20s
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = viewModelScope.launch {
            delay(20_000L)
            autoConnectJob?.cancel()
            bleScanner.stopScan()
            _state.update { it.copy(
                isRequestingDisconnect = false,
                disconnectRequestNoResponse = false
            ) }
        }
    }

    fun dismissDisconnectNoResponse() {
        _state.update { it.copy(disconnectRequestNoResponse = false) }
    }

    fun acceptRemoteDisconnect() {
        _state.update { it.copy(showDisconnectRequestDialog = false) }
        suppressDisconnectDialog = true
        disconnectCooldownUntil = System.currentTimeMillis() + 30_000L
        climbAdvertiser.advertiseDisconnectResponse(accepted = true)
        bleConnection.disconnect()
        // Don't call stopAdvertising() here — the connection state collector calls
        // onBoardDisconnected() when DISCONNECTED fires, which transitions to
        // LAST_CLIMB advertising (keeping the last boulder visible to nearby users).
    }

    fun dismissDisconnectRequest() {
        _state.update { it.copy(showDisconnectRequestDialog = false) }
        disconnectCooldownUntil = System.currentTimeMillis() + 30_000L
    }

    /**
     * Waits for the board to become vacant (no active climb connections), then scans + connects.
     * Called automatically when entering a session (HOST or PARTICIPANT) while not connected.
     * For HOST: the disconnect request was already sent by [SessionGattBridge.startSharing].
     * For PARTICIPANT: connects to the board for LED control after GATT join succeeds.
     */
    private fun startAutoConnectForSession() {
        Log.d(TAG, "startAutoConnectForSession: connectionState=${_state.value.connectionState}")
        if (_state.value.connectionState != ConnectionState.DISCONNECTED) {
            Log.d(TAG, "startAutoConnectForSession: already connected, skipping")
            return
        }
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            Log.d(TAG, "startAutoConnectForSession: waiting for board to become vacant")
            nearbyClimbScanner.nearbyClimbs.first { climbs ->
                val vacant = climbs.none { !it.isLastClimb }
                Log.d(TAG, "startAutoConnectForSession: nearbyClimbs=${climbs.size}, " +
                    "activeClimbs=${climbs.count { !it.isLastClimb }}, vacant=$vacant")
                vacant
            }
            val currentRole = sessionQueueManager.state.value.role
            if (currentRole == SessionRole.NONE) {
                Log.d(TAG, "startAutoConnectForSession: role is NONE, aborting")
                return@launch
            }
            if (_state.value.connectionState != ConnectionState.DISCONNECTED) {
                Log.d(TAG, "startAutoConnectForSession: connected while waiting, aborting")
                return@launch
            }
            Log.d(TAG, "startAutoConnectForSession: board vacant, awaiting GATT close then scanning")
            bleConnection.awaitGattClosed()
            bleScanner.startScan()
            val board = bleScanner.discoveredBoards.first { it.isNotEmpty() }.first()
            bleScanner.stopScan()
            Log.d(TAG, "startAutoConnectForSession: found board '${board.displayName}', connecting")
            bleConnection.connect(board)
        }
        // Timeout: stop trying after 30s
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = viewModelScope.launch {
            delay(30_000L)
            autoConnectJob?.cancel()
            bleScanner.stopScan()
        }
    }

}
