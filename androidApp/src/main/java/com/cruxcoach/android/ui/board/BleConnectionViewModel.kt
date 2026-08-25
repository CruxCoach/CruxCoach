package com.cruxcoach.android.ui.board

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardBleScanner
import com.cruxcoach.android.ble.BoardCapacityProbe
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.BoardConnectFlowPolicy
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
import com.cruxcoach.domain.board.QuantumBoardModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val connectedQuantumModel: QuantumBoardModel? = null,
    val connectedBoard: DiscoveredBoard? = null,
    /** One correction prompt per physical connection; the send fence remains
     * active even after the user deliberately dismisses it. */
    val connectionMismatchPromptDismissed: Boolean = false,
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
    /**
     * A speculative direct connect to the remembered controller is running.
     * Only ever on Android ≤ 11, where scanning would cost a location grant —
     * see [BoardConnectFlowPolicy].
     */
    val directReconnectInFlight: Boolean = false,
    /**
     * That attempt came back empty-handed, so the board is not where it was.
     * Discovery — and, on those versions, its location prompt — is justified now.
     */
    val directReconnectFailed: Boolean = false,
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
    private val capacityProbe: BoardCapacityProbe,
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
        /** Retries for a user-picked board — see BoardBleConnection.connect. */
        private const val DEFAULT_CONNECT_ATTEMPTS = 3
        /**
         * Android 9 commonly rejects the first address-only GATT attempt with
         * a transient status 133 even though the controller is advertising.
         * A second attempt makes the remembered-board path as reliable as a
         * post-scan connect without making an absent board consume the full
         * three-attempt picker budget.
         */
        private const val DIRECT_RECONNECT_ATTEMPTS = 2
        /** Two 10 s attempts plus legacy settle and retry delays. */
        private const val DIRECT_RECONNECT_TIMEOUT_MS = 24_000L
    }

    private val _state = MutableStateFlow(BleConnectionState())
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()
    private var pendingBoard: DiscoveredBoard? = null

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
                        connectionMismatchPromptDismissed =
                            if (connState == ConnectionState.CONNECTING ||
                                connState == ConnectionState.DISCONNECTED
                            ) false else it.connectionMismatchPromptDismissed,
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
            bleConnection.connectedQuantumModel.collect { model ->
                _state.update { it.copy(connectedQuantumModel = model) }
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

        // Scanner handover around a connect and the capacity observation that
        // follows it live in a singleton, not here: this ViewModel is scoped
        // per nav entry, and the instance that installed the callback last
        // takes them down with it when its screen goes away.
        capacityProbe.install()

        checkState()
    }

    fun dismissConnectionMismatchPrompt() {
        _state.update { it.copy(connectionMismatchPromptDismissed = true) }
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
            val target = BoardConnectFlowPolicy.autoConnectTarget(candidates)
            if (target != null) {
                Log.i(
                    "BleConnectionVM",
                    "auto-connect: single ${target.boardBrand} board ${target.address}, connecting"
                )
                connectToBoard(target)
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
        // A failed direct attempt is about this moment, not about the board:
        // the next time the sheet opens the climber may well be standing in
        // front of the wall, so try it again rather than going straight to a
        // location prompt.
        directReconnectJob?.cancel()
        directReconnectJob = null
        _state.update { it.copy(directReconnectInFlight = false, directReconnectFailed = false) }
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

    fun connectToBoard(board: DiscoveredBoard, maxAttempts: Int = DEFAULT_CONNECT_ATTEMPTS) {
        if (bleConnection.connectionState.value != ConnectionState.DISCONNECTED) return
        pendingBoard = board
        bleScanner.stopScan()
        bleConnection.connect(board.withKnownCapacity(), maxAttempts)
    }

    /**
     * Carries an established "accepts several clients" over to a board that
     * came from a scan.
     *
     * A scan result knows nothing about capacity, so connecting through the
     * picker used to throw away what an earlier connection had already proven
     * and left the board looking exclusive again until the next observation
     * happened to succeed. The remembered controller holds the answer; match
     * it by address, since the same brand may cover several walls.
     */
    private fun DiscoveredBoard.withKnownCapacity(): DiscoveredBoard {
        if (advertisesWhileConnected != null || isCruxRelay) return this
        val remembered = _state.value.rememberedBoardControllers[boardBrand] ?: return this
        if (!remembered.address.equals(address, ignoreCase = true)) return this
        val known = remembered.advertisesWhileConnected ?: return this
        return copy(advertisesWhileConnected = known)
    }

    /**
     * Android ≤ 11 entry point: reach for the remembered controller before
     * anyone is asked for location access.
     *
     * A direct GATT connect needs no scan, so this costs the user nothing. If
     * the board answers, the whole location question never comes up; if it does
     * not, [BleConnectionState.directReconnectFailed] hands the sheet over to
     * discovery, which is the point at which asking for location is honest.
     *
     * Two attempts: Android 9's first address-only connect often fails with a
     * transient status 133. Three would take ~32 s to conclude "not here";
     * two absorb that legacy-stack hiccup while keeping the fallback bounded.
     */
    fun tryRememberedControllerFirst() {
        val s = _state.value
        if (s.directReconnectInFlight || s.connectionState != ConnectionState.DISCONNECTED) return
        val remembered = s.rememberedBoardControllers[s.activeBoardBrand] ?: run {
            _state.update { it.copy(directReconnectFailed = true) }
            return
        }
        directReconnectJob?.cancel()
        _state.update { it.copy(directReconnectInFlight = true, directReconnectFailed = false) }
        directReconnectJob = viewModelScope.safeLaunch(TAG) {
            try {
                connectToBoard(
                    remembered.toDiscoveredBoard(),
                    maxAttempts = DIRECT_RECONNECT_ATTEMPTS,
                )
                val outcome = withTimeoutOrNull(DIRECT_RECONNECT_TIMEOUT_MS) {
                    bleConnection.connectionState.first { it != ConnectionState.CONNECTING }
                }
                val connected = outcome == ConnectionState.CONNECTED ||
                    outcome == ConnectionState.SENDING
                Log.i(TAG, "direct reconnect to ${remembered.address}: connected=$connected")
                _state.update {
                    it.copy(directReconnectInFlight = false, directReconnectFailed = !connected)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(directReconnectInFlight = false) }
                throw e
            }
        }
    }

    /** Give up on the remembered controller and go to discovery. */
    fun abandonDirectReconnect() {
        directReconnectJob?.cancel()
        directReconnectJob = null
        if (bleConnection.connectionState.value == ConnectionState.CONNECTING) {
            bleConnection.disconnect()
        }
        _state.update { it.copy(directReconnectInFlight = false, directReconnectFailed = true) }
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
        connectToBoard(remembered.toDiscoveredBoard())
    }

    private fun RememberedBoardController.toDiscoveredBoard() = DiscoveredBoard(
        displayName = displayName,
        serial = serial,
        apiLevel = apiLevel,
        address = address,
        rssi = 0,
        boardBrand = boardBrand,
        // Carry the stored positive observation over: a controller proven to
        // take several clients must not fall back to "exclusive" just because
        // this connection came from a reconnect rather than a scan.
        advertisesWhileConnected = advertisesWhileConnected,
    )

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
    private var directReconnectJob: Job? = null
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
