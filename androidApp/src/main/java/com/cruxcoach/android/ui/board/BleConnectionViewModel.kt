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
import com.cruxcoach.android.ble.ConnectedAdvertisingProbeResult
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.NearbyPresenceManager
import com.cruxcoach.android.data.RememberedBoardController
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.cruxcoach.android.util.safeLaunch

data class BleConnectionState(
    val hasPermissions: Boolean = false,
    /** Permission needed for an existing/direct GATT link, without discovery scanning. */
    val hasConnectionPermission: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    /**
     * System-wide location-services switch. Only relevant for the discovery
     * scan on API ≤ 30 (see [BlePermissionHelper.isLocationRequired]); an
     * established or in-flight GATT connection never depends on it. Defaults
     * to true so no location prompt flashes before the first [checkState].
     */
    val isLocationEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val discoveredBoards: List<DiscoveredBoard> = emptyList(),
    val nearbySessions: List<NearbySession> = emptyList(),
    val lastUsedBoardAddresses: Map<BoardBrand, String> = emptyMap(),
    val rememberedBoardControllers: Map<BoardBrand, RememberedBoardController> = emptyMap(),
    val activeBoardBrand: BoardBrand = BoardBrand.KILTER,
    val rememberedBoardControllersLoaded: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedBoardName: String? = null,
    val connectedBoardBrand: BoardBrand? = null,
    val connectedBoard: DiscoveredBoard? = null,
    val isRequestingDisconnect: Boolean = false,
    val disconnectRequestNoResponse: Boolean = false,
    val climbSharingEnabled: Boolean = false,
    val allowRemoteDisconnect: Boolean = false,
    val showDisconnectRequestDialog: Boolean = false,
    val sessionRole: SessionRole = SessionRole.NONE,
    /**
     * True while a scan started via [BleConnectionViewModel.startScanWithAutoConnect]
     * is still inside its 2 s settling window. The sheet uses this to know whether
     * a 1-board result should auto-resolve into a connect (true) or whether the
     * user explicitly opened the sheet to inspect the list (false).
     */
    val isAutoConnectScan: Boolean = false,
    /** Localized reason (string-res id) why the last connect attempt was torn
     *  down at service discovery (e.g. unsupported RedBear-UART MoonBoard
     *  LED-kit generation). Null = none. */
    @androidx.annotation.StringRes val connectFailureReason: Int? = null,
)

@HiltViewModel
class BleConnectionViewModel @Inject constructor(
    private val application: Application,
    private val bleScanner: BoardBleScanner,
    private val bleConnection: BoardBleConnection,
    private val userPreferences: UserPreferences,
    private val nearbyClimbScanner: NearbyClimbScanner,
    private val climbAdvertiser: ClimbBleAdvertiser,
    private val sessionQueueManager: SessionQueueManager,
    private val nearbyPresenceManager: NearbyPresenceManager,
    private val sessionGattBridge: SessionGattBridge,
    private val boardSessionManager: BoardSessionManager,
) : ViewModel() {

    companion object {
        private const val TAG = "BleConnectionViewModel"
        /** Outer deadline for finding the *first* board after scan starts.
         *  If nothing's in range within this, auto-connect bails (manual
         *  pick UI stays). Used as a cap on the event-driven wait, not
         *  as a blind sleep. */
        private const val SETTLING_WINDOW_MS = 2_000L
        /** Cool-down *after* the first board is seen, to give a possible
         *  sibling time to advertise. Sized for the worst common BLE
         *  adv interval (≈1 s on battery-optimised peripherals) so we
         *  never auto-connect to a single board while a slower sibling
         *  is still ramping its first packet. Faster than the prior
         *  blind 2 s sleep, safe against slow sibling adv. */
        private const val SIBLING_WINDOW_MS = 1_000L
    }

    private val _state = MutableStateFlow(BleConnectionState())
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()
    private var pendingBoard: DiscoveredBoard? = null
    private var capacityProbeJob: Job? = null

    init {
        viewModelScope.safeLaunch(TAG) {
            bleScanner.discoveredBoards.collect { boards ->
                _state.update { it.copy(discoveredBoards = boards) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleScanner.isScanning.collect { scanning ->
                _state.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.connectionState.collect { connState ->
                _state.update {
                    it.copy(
                        connectionState = connState,
                        connectedBoard = if (connState == ConnectionState.DISCONNECTED) {
                            null
                        } else {
                            bleConnection.connectedBoard
                        },
                    )
                }
                // Auto-advertise "board connected" so nearby users can send disconnect requests
                if (connState == ConnectionState.CONNECTED) {
                    pendingBoard?.let { board ->
                        if (!board.isCruxRelay) {
                            userPreferences.setRememberedBoardController(
                                RememberedBoardController(
                                    displayName = board.displayName,
                                    serial = board.serial,
                                    apiLevel = board.apiLevel,
                                    address = board.address,
                                    boardBrand = board.boardBrand,
                                )
                            )
                        }
                        pendingBoard = null
                    }
                    suppressDisconnectDialog = false
                    if (_state.value.climbSharingEnabled) {
                        val capacity = BoardControllerProfiles.forBoard(bleConnection.connectedBoard)
                            .connectionCapacity
                        climbAdvertiser.advertiseConnected(
                            acceptsDisconnect = _state.value.allowRemoteDisconnect &&
                                capacity == BoardConnectionCapacity.SINGLE,
                            supportsConcurrentConnections =
                                capacity == BoardConnectionCapacity.MULTIPLE,
                        )
                    }
                } else if (connState == ConnectionState.DISCONNECTED) {
                    capacityProbeJob?.cancel()
                    capacityProbeJob = null
                    pendingBoard = null
                    climbAdvertiser.onBoardDisconnected(_state.value.climbSharingEnabled)
                }
                // Successful service discovery restarts nearby scanning after
                // the short capacity probe. This disconnected-state fallback
                // covers cancellation, connect failure and timeout paths.
                if (connState == ConnectionState.DISCONNECTED) {
                    nearbyClimbScanner.startScan(clearExisting = false)
                }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.connectedBoardName.collect { name ->
                _state.update { it.copy(connectedBoardName = name) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.connectedBoardBrand.collect { brand ->
                _state.update { it.copy(connectedBoardBrand = brand) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.connectedBoardDescriptor.collect { board ->
                _state.update { it.copy(connectedBoard = board) }
                if (board != null &&
                    bleConnection.connectionState.value == ConnectionState.CONNECTED &&
                    _state.value.climbSharingEnabled
                ) {
                    val capacity = BoardControllerProfiles.forBoard(board).connectionCapacity
                    climbAdvertiser.advertiseConnected(
                        acceptsDisconnect = _state.value.allowRemoteDisconnect &&
                            capacity == BoardConnectionCapacity.SINGLE,
                        supportsConcurrentConnections =
                            capacity == BoardConnectionCapacity.MULTIPLE,
                    )
                }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            nearbyClimbScanner.nearbySessions.collect { sessions ->
                _state.update { it.copy(nearbySessions = sessions.sortedByDescending(NearbySession::rssi)) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.connectFailureReason.collect { reason ->
                _state.update { it.copy(connectFailureReason = reason) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleScanner.bluetoothEnabled.collect { enabled ->
                _state.update { it.copy(isBluetoothEnabled = enabled) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            bleConnection.autoDisconnectSeconds = userPreferences.bleAutoDisconnectSeconds.first()
            userPreferences.bleAutoDisconnectSeconds.collect { seconds ->
                bleConnection.autoDisconnectSeconds = seconds
            }
        }
        viewModelScope.safeLaunch(TAG) {
            userPreferences.nearbyClimbSharing.collect { enabled ->
                _state.update { it.copy(climbSharingEnabled = enabled) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            userPreferences.allowRemoteDisconnect.collect { allowed ->
                _state.update { it.copy(allowRemoteDisconnect = allowed) }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            combine(
                userPreferences.boardBrand,
                userPreferences.lastUsedBoardAddresses,
                userPreferences.rememberedBoardControllers,
            ) { brandWire, addresses, controllers ->
                Triple(BoardBrand.fromWire(brandWire), addresses, controllers)
            }.collect { (activeBrand, addresses, controllers) ->
                _state.update {
                    it.copy(
                        activeBoardBrand = activeBrand,
                        lastUsedBoardAddresses = addresses,
                        rememberedBoardControllers = controllers,
                        rememberedBoardControllersLoaded = true,
                    )
                }
            }
        }
        // Receive disconnect requests from nearby users (works on any screen)
        viewModelScope.safeLaunch(TAG) {
            nearbyClimbScanner.disconnectRequests.collect {
                val s = _state.value
                val now = System.currentTimeMillis()
                if (s.connectionState != ConnectionState.CONNECTED) return@collect
                val isExclusive = BoardControllerProfiles.forBoard(s.connectedBoard)
                    .connectionCapacity == BoardConnectionCapacity.SINGLE
                if (!s.allowRemoteDisconnect || !isExclusive) {
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

        // Only the session host owns the physical board connection. Participants
        // send queue commands to the host and connect to the board only after a
        // host-migration promotion changes their role to HOST.
        var previousQueueRole = SessionRole.NONE
        viewModelScope.safeLaunch(TAG) {
            sessionQueueManager.state.collect { queueState ->
                val newRole = queueState.role
                _state.update { it.copy(sessionRole = newRole) }
                if (newRole != previousQueueRole) {
                    Log.d(TAG, "Queue role changed: $previousQueueRole → $newRole, " +
                        "connectionState=${_state.value.connectionState}")
                }
                if (BoardDeliveryPolicy.shouldAutoConnectSessionHost(
                        newRole,
                        previousQueueRole,
                        _state.value.connectionState,
                    )
                ) {
                    Log.d(TAG, "Role became $newRole while disconnected → triggering auto-connect")
                    startAutoConnectForSession()
                }
                if (BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                        newRole,
                        previousQueueRole,
                        _state.value.connectionState,
                        BoardControllerProfiles.forBoard(bleConnection.connectedBoard)
                            .connectionCapacity,
                        bleConnection.hasOtherKeepAliveOwners(BoardConnectionOwner.SESSION),
                    )
                ) {
                    Log.d(TAG, "Participant role acquired — releasing local board connection")
                    bleConnection.disconnect()
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
            capacityProbeJob?.cancel()
            val board = bleConnection.connectedBoard
            if (bleConnection.connectionState.value == ConnectionState.CONNECTED &&
                board != null && !board.isCruxRelay
            ) {
                capacityProbeJob = viewModelScope.safeLaunch(TAG) {
                    // Avoid competing scan registrations on legacy Android
                    // stacks. Board writes are already available while this
                    // short, address-filtered observation runs.
                    nearbyClimbScanner.stopScan(preserveEntries = true)
                    try {
                        when (bleScanner.probeAdvertisingWhileConnected(board.address)) {
                            ConnectedAdvertisingProbeResult.CONNECTABLE_ADVERTISEMENT_OBSERVED ->
                                bleConnection.recordAdvertisingWhileConnected(
                                    address = board.address,
                                    observed = true,
                                )
                            ConnectedAdvertisingProbeResult.NOT_OBSERVED ->
                                bleConnection.recordAdvertisingWhileConnected(
                                    address = board.address,
                                    observed = false,
                                )
                            ConnectedAdvertisingProbeResult.INCONCLUSIVE ->
                                Log.w(TAG, "Controller-capacity probe was inconclusive")
                        }
                    } finally {
                        nearbyClimbScanner.startScan(clearExisting = false)
                    }
                }
            } else {
                nearbyClimbScanner.startScan(clearExisting = false)
            }
        }

        checkState()
    }

    fun checkState() {
        _state.update { it.copy(
            hasPermissions = BlePermissionHelper.hasPermissions(application),
            hasConnectionPermission = BlePermissionHelper.hasConnectionPermission(application),
            isBluetoothEnabled = bleScanner.isBluetoothEnabled(),
            isLocationEnabled = BlePermissionHelper.isLocationServicesEnabled(application)
        ) }
    }

    fun onPermissionsGranted() {
        _state.update { it.copy(hasPermissions = true) }
        checkState()
        // Bug 5: Retry nearby scanner after permission grant (first app start)
        nearbyPresenceManager.retryScan()
    }

    fun startScan() {
        viewModelScope.safeLaunch(TAG) {
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
     * Event-driven wait used by auto-connect / quick-send to decide
     * whether to single-shot connect or fall back to the manual list:
     *  1) Wait for the first discovered board, capped at
     *     [SETTLING_WINDOW_MS]. Bail (empty list) if nothing shows up.
     *  2) Once we have one, wait a short [SIBLING_WINDOW_MS] cool-down
     *     for a possible sibling adv — short-circuits as soon as a
     *     second board appears.
     * Replaces the previous blind [delay] which paid the full 2 s even
     * when only one board was present from the first packet onward.
     */
    private suspend fun awaitBoardsForAutoConnect(): List<DiscoveredBoard> {
        // Phase 1: wait for the first board *from an active scan*. The
        // isScanning gate is critical — without it, a stale
        // discoveredBoards list from a previous scan in this session
        // would resolve `.first { isNotEmpty }` immediately and trigger
        // a wrong-board auto-connect (or an empty-bail if the stale
        // list happens to be empty + we race the new scan start).
        val gated = withTimeoutOrNull(SETTLING_WINDOW_MS) {
            combine(bleScanner.isScanning, bleScanner.discoveredBoards) { scanning, boards ->
                scanning to boards
            }.first { (scanning, boards) -> scanning && boards.isNotEmpty() }
        }
        if (gated == null) {
            Log.i("BleConnectionVM", "awaitBoardsForAutoConnect: timeout, no board seen during active scan")
            return emptyList()
        }
        // Phase 2: short sibling cool-down (short-circuits on a 2nd board).
        val finalSet = withTimeoutOrNull(SIBLING_WINDOW_MS) {
            bleScanner.discoveredBoards.first { it.size >= 2 }
        } ?: bleScanner.discoveredBoards.value
        Log.i("BleConnectionVM", "awaitBoardsForAutoConnect: settled with ${finalSet.size} board(s)")
        return finalSet
    }

    /**
     * Narrow [boards] to those matching the ACTIVE board's brand, falling back
     * to the full list when none match. Auto-connect previously took any
     * discovered board by discovery order — in a gym with e.g. a MoonBoard
     * next to a Kilter (or a neighbour's Tension in BLE range) it could
     * silently grab the wrong wall, and every later send then fails with a
     * brand mismatch that never explains the connection itself is wrong.
     */
    private suspend fun preferActiveBrand(boards: List<DiscoveredBoard>): List<DiscoveredBoard> {
        val activeBrand = BoardBrand.fromWire(userPreferences.boardBrand.first())
        return boards.filter { it.boardBrand == activeBrand }.ifEmpty { boards }
    }

    /**
     * Scan with auto-connect on single result. Uses [awaitBoardsForAutoConnect]
     * — fast when one board is present (typically ~discovery + ~600 ms cool-down,
     * was always a blind 2 s). Outcomes:
     *  - exactly 1 board → connect to it.
     *  - 2+ boards → leave the list visible for manual pick.
     *  - 0 boards → keep scanning, fall back to manual pick after the user waits.
     */
    fun startScanWithAutoConnect() {
        autoConnectScanJob?.cancel()
        _state.update { it.copy(isAutoConnectScan = true) }
        autoConnectScanJob = viewModelScope.safeLaunch(TAG) {
            startScan()
            val boards = awaitBoardsForAutoConnect()
            val s = _state.value
            // Bail out if state changed during the wait: user disconnected the
            // sheet, scan stopped, or a connect already happened in another
            // thread.
            if (s.connectionState != ConnectionState.DISCONNECTED || !s.isScanning) {
                _state.update { it.copy(isAutoConnectScan = false) }
                return@safeLaunch
            }
            val candidates = preferActiveBrand(boards)
            if (candidates.size == 1) {
                Log.i("BleConnectionVM", "auto-connect: single ${candidates.first().boardBrand} board, connecting")
                connectToBoard(candidates.first())
            } else {
                Log.i("BleConnectionVM", "auto-connect: ${candidates.size} candidate boards (${boards.size} discovered), leaving manual pick")
            }
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

    /** Stop discovery owned by a dismissed picker, without interrupting a
     * connect or the post-connect controller-capacity probe. */
    fun onConnectionSheetDismissed() {
        if (_state.value.connectionState == ConnectionState.DISCONNECTED &&
            bleScanner.isScanning.value
        ) {
            stopScan()
        }
    }

    /** Joins the exact session selected by the user; multiple nearby hosts stay unambiguous. */
    fun joinNearbySession(session: NearbySession) {
        val device = session.device
        if (device != null) {
            boardSessionManager.startSession()
            sessionGattBridge.joinSession(device)
        } else {
            Log.w(TAG, "joinNearbySession: selected session has no connectable device")
        }
    }

    fun connectToBoard(board: DiscoveredBoard) {
        if (bleConnection.connectionState.value != ConnectionState.DISCONNECTED) return
        pendingBoard = board
        bleScanner.stopScan()
        bleConnection.connect(board)
    }

    /**
     * Reuses the active board family's last successful physical-controller
     * descriptor. This is a direct GATT connect and intentionally performs no
     * discovery scan; on Android 8-11 it therefore needs no location access.
     */
    fun reconnectRememberedBoard() {
        checkState()
        if (!_state.value.hasConnectionPermission) return
        val remembered = _state.value
            .rememberedBoardControllers[_state.value.activeBoardBrand]
            ?: return
        connectToBoard(
            DiscoveredBoard(
                displayName = remembered.displayName,
                serial = remembered.serial,
                apiLevel = remembered.apiLevel,
                address = remembered.address,
                rssi = 0,
                boardBrand = remembered.boardBrand,
            )
        )
    }

    /**
     * Whether the currently-connected board is a MoonBoard (FEAT-027).
     * Derived from the advertising name — MoonBoard advertises a bare
     * "MoonBoard…" name, Aurora boards a Kilter-style parsed name. Used by
     * the connection sheet to brand-label the connected device.
     */
    fun isConnectedBoardMoonBoard(): Boolean {
        val name = _state.value.connectedBoardName ?: return false
        return bleScanner.isMoonBoardName(name)
    }

    fun disconnect() {
        bleConnection.disconnect()
    }

    private var disconnectTimeoutJob: Job? = null
    private var autoConnectJob: Job? = null
    private var autoConnectScanJob: Job? = null
    private var disconnectCooldownUntil = 0L
    /** Set after accepting a remote disconnect — suppresses the dialog until next connect. */
    private var suppressDisconnectDialog = false

    fun requestDisconnect() {
        climbAdvertiser.advertiseDisconnectRequest()
        _state.update { it.copy(
            isRequestingDisconnect = true,
            disconnectRequestNoResponse = false
        ) }

        // Watch nearby advertising and wait until no sender still owns an
        // active connection. LastClimb is metadata from an already-released
        // connection; its retention flag separately says whether LEDs remain.
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.safeLaunch(TAG) {
            nearbyClimbScanner.nearbyClimbs.first { climbs ->
                climbs.none { !it.isLastClimb } && _state.value.isRequestingDisconnect
            }
            // Other user disconnected (stopped advertising) — now connect.
            // Prefer the active board's brand over raw discovery order.
            disconnectTimeoutJob?.cancel()
            bleScanner.startScan()
            val board = preferActiveBrand(
                bleScanner.discoveredBoards.first { it.isNotEmpty() }
            ).first()
            bleScanner.stopScan()
            connectToBoard(board)
            _state.update { it.copy(
                isRequestingDisconnect = false,
                disconnectRequestNoResponse = false
            ) }
        }

        // Timeout: no response after 20s
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = viewModelScope.safeLaunch(TAG) {
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
        if (BoardControllerProfiles.forBoard(_state.value.connectedBoard)
                .connectionCapacity != BoardConnectionCapacity.SINGLE
        ) {
            dismissDisconnectRequest()
            climbAdvertiser.advertiseDisconnectResponse(accepted = false)
            return
        }
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
        autoConnectJob = viewModelScope.safeLaunch(TAG) {
            Log.d(TAG, "startAutoConnectForSession: waiting for board to become vacant")
            nearbyClimbScanner.nearbyClimbs.first { climbs ->
                val vacant = climbs.none {
                    !it.isLastClimb && !it.supportsConcurrentConnections
                }
                Log.d(TAG, "startAutoConnectForSession: nearbyClimbs=${climbs.size}, " +
                    "activeClimbs=${climbs.count { !it.isLastClimb }}, vacant=$vacant")
                vacant
            }
            val currentRole = sessionQueueManager.state.value.role
            if (currentRole == SessionRole.NONE) {
                Log.d(TAG, "startAutoConnectForSession: role is NONE, aborting")
                return@safeLaunch
            }
            if (_state.value.connectionState != ConnectionState.DISCONNECTED) {
                Log.d(TAG, "startAutoConnectForSession: connected while waiting, aborting")
                return@safeLaunch
            }
            Log.d(TAG, "startAutoConnectForSession: board vacant, awaiting GATT close then scanning")
            bleConnection.awaitGattClosed()
            bleScanner.startScan()
            // Prefer the active board's brand over raw discovery order — the
            // session projects onto the active board, so grabbing whatever
            // advertised first could light a different wall.
            val board = preferActiveBrand(
                bleScanner.discoveredBoards.first { it.isNotEmpty() }
            ).first()
            bleScanner.stopScan()
            Log.d(TAG, "startAutoConnectForSession: found board '${board.displayName}', connecting")
            connectToBoard(board)
        }
        // Timeout: stop trying after 30s
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = viewModelScope.safeLaunch(TAG) {
            delay(30_000L)
            autoConnectJob?.cancel()
            bleScanner.stopScan()
        }
    }

}
