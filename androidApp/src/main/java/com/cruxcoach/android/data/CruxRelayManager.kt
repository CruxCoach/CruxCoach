package com.cruxcoach.android.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSetCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.relay.RelayBoardName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why the relay failed or stopped — mapped to localized strings in the UI
 *  (FEAT-044 §12: never fail silently). */
enum class RelayError {
    SERVER_START_FAILED,
    ADVERTISE_FAILED,
    NAME_SET_FAILED,
    BOARD_LOST,
    UNSUPPORTED_BOARD,

    /**
     * A relayed climb was accepted from the guest and then refused by the
     * board, with the link still up. Reported but not fatal: the guest's app
     * has already been told the write succeeded, so without this the wall
     * simply stayed dark on both sides of a relay that looked healthy.
     */
    FORWARD_FAILED,
}

data class CruxRelayState(
    val enabled: Boolean = false,
    val advertising: Boolean = false,
    val clientCount: Int = 0,
    val advertisedName: String? = null,
    val boardName: String? = null,
    val error: RelayError? = null,
    /** Raw technical detail for [error] (log-grade, appended to the message). */
    val errorDetail: String? = null,
    /** The one-time Bluetooth-name/non-affiliation disclosure is app-global,
     * so this state is rendered at the navigation root, not only in the
     * connection sheet. */
    val pendingDisclosure: Boolean = false,
)

/**
 * CruxRelay orchestration (FEAT-044): CruxCoach fronts the real board so
 * official-Kilter-app users can send climbs through it, transparently.
 *
 * Runs the board-emulation [RelayGattServer] + advertises the board's
 * 4488B571 UUID under a transparent [RelayBoardName]. A completed climb is
 * forwarded byte-faithfully to the real board via [BoardBleConnection.sendRawChunks]
 * (last-write-wins; the board's own writeMutex + one send-per-climb keep whole
 * climbs atomic). Queue and relay have independent lifecycles; the narrow
 * [BoardProjectionCoordinator] notification only prevents stale projection UI.
 *
 * The advertised name is set via the GLOBAL, persistent [android.bluetooth.BluetoothAdapter.setName]
 * (no per-advertiser API), snapshotted to a crash-safe flag so an abrupt death
 * still restores the phone's real Bluetooth name on next launch.
 *
 * NOTE: behaviour is validated on-device only (official app + real board) — see
 * docs/specs/0.2.2/FEAT-044 §10.
 */
class CruxRelayManager(
    private val context: Context,
    private val relayServer: RelayGattServer,
    private val advertiser: ClimbBleAdvertiser,
    private val bleConnection: BoardBleConnection,
    private val projectionCoordinator: BoardProjectionCoordinator,
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val adapterProvider: () -> BluetoothAdapter? = {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    },
) {
    companion object {
        private const val TAG = "CruxRelay/Manager"
        internal const val PREFS = "cruxrelay"
        internal const val KEY_NAME_DIRTY = "adapter_name_dirty"
        internal const val KEY_ORIGINAL_NAME = "adapter_name_original"
        private const val NAME_PROPAGATE_TIMEOUT_MS = 2_000L
        private const val ADVERTISE_START_TIMEOUT_MS = 3_000L
    }

    private val adapter get() = adapterProvider()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CruxRelayState())
    val state: StateFlow<CruxRelayState> = _state.asStateFlow()
    // Not persisted: this is runtime state, rebuilt on every process from the
    // board link and the user's standing choice. A lost board still turns it
    // off — sharing never outlives the connection it belongs to.
    private val enabledFlow = MutableStateFlow(false)

    private var running = false
    private var forwardJob: Job? = null
    private var eventJob: Job? = null
    /** Climb identification for the most recent relayed write; see forwardJob. */
    private var identifyJob: Job? = null
    private var disclosureJob: Job? = null
    /** Board identity for the disclosure currently shown. A consent answer
     * must never carry across a disconnect/reconnect to a different wall. */
    private var pendingDisclosureBoardAddress: String? = null
    /** A cancelled automatic disclosure stays cancelled for this physical
     * connection. Board writes briefly transition CONNECTED -> SENDING ->
     * CONNECTED and must not turn that transition into another prompt. */
    private var autoDisclosureDismissedBoardAddress: String? = null
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                BluetoothAdapter.STATE_ON
            ) {
                // The first process-start restore can legitimately run while
                // Bluetooth is off. Retain the recovery record and retry once
                // the adapter is authoritative again.
                restoreAdapterNameIfDirty()
            }
        }
    }
    init {
        // Crash-safe: a previous run may have died with the adapter name still
        // changed. Restore it before anything else.
        restoreAdapterNameIfDirty()
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The relay runs only while enabled AND the real board link is up
        // (WAIT_BEFORE_ADVERTISE). A falling board link disables sharing
        // entirely, so it never outlives the connection it was started for.
        scope.launch {
            combine(enabledFlow, bleConnection.connectionState) { enabled, st ->
                enabled to st
            }.collect { (enabled, st) -> reconcile(enabled, st) }
        }
        // The capacity probe updates the live descriptor a few seconds after
        // connect. Observe it independently so an already-running relay or an
        // in-flight disclosure is withdrawn as soon as it proves unnecessary.
        scope.launch {
            bleConnection.connectedBoardDescriptor.collect { board ->
                if (bleConnection.connectionState.value == ConnectionState.CONNECTED) {
                    when (BoardRelayPolicy.availability(board)) {
                        BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED -> {
                            if (running) stopRelay()
                            settleMultiConnectCapacity()
                        }
                        BoardRelayAvailability.AVAILABLE -> {
                            // A fresh controller is UNKNOWN for the brief
                            // post-connect capacity probe. Do not flash a relay
                            // disclosure that becomes a no-op as soon as the
                            // same controller proves multi-connect. A completed
                            // single-connect observation re-enters here with
                            // `advertisesWhileConnected == false`.
                            if (board?.advertisesWhileConnected != null &&
                                !userPreferences.relayManualStart.first()
                            ) {
                                requestAutomaticEnable()
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        // Normal mode follows the physical board connection. Users who opt in
        // to manual start keep the explicit button in the connection sheet.
        scope.launch {
            combine(
                userPreferences.relayManualStart,
                bleConnection.connectionState,
            ) { manual, st -> !manual && st == ConnectionState.CONNECTED }
                .distinctUntilChanged()
                .collect { shouldShare ->
                    if (shouldShare && !enabledFlow.value) requestAutomaticEnable()
                    else if (!shouldShare &&
                        bleConnection.connectionState.value == ConnectionState.DISCONNECTED
                    ) {
                        autoDisclosureDismissedBoardAddress = null
                        disable()
                    }
                }
        }
    }

    /** The sole start entry point for manual, automatic and permission-retry
     * paths. No caller can enable transport before the persisted disclosure. */
    fun requestEnable() {
        autoDisclosureDismissedBoardAddress = null
        requestEnableInternal()
    }

    private fun requestAutomaticEnable() {
        val board = bleConnection.connectedBoard ?: return
        val address = board.address
        if (autoDisclosureDismissedBoardAddress == address) return
        // When scanning is available, BoardCapacityProbe will shortly replace
        // UNKNOWN with authoritative true/false evidence. Waiting prevents a
        // multi-connect wall from showing a consent dialog whose confirm action
        // must then be rejected. If probing is impossible, preserve the safe
        // historical fallback: UNKNOWN is treated as an exclusive controller.
        if (board.advertisesWhileConnected == null &&
            BlePermissionHelper.wantsCapacityProbe(
                capacityKnown = false,
                hasScanPermission = BlePermissionHelper.hasScanPermission(context),
                locationEnabled = BlePermissionHelper.isLocationServicesEnabled(context),
            )
        ) return
        requestEnableInternal()
    }

    private fun requestEnableInternal() {
        val board = bleConnection.connectedBoard ?: return
        val availability = BoardRelayPolicy.availability(board)
        if (availability == BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED) {
            settleMultiConnectCapacity()
            return
        }
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED ||
            availability != BoardRelayAvailability.AVAILABLE) {
            rejectEnable(RelayError.UNSUPPORTED_BOARD)
            return
        }
        val expectedAddress = board.address
        disclosureJob?.cancel()
        pendingDisclosureBoardAddress = null
        _state.update { it.copy(pendingDisclosure = false) }
        disclosureJob = scope.launch {
            val seen = userPreferences.relayDisclosureSeen.first()
            val currentBoard = bleConnection.connectedBoard
            if (bleConnection.connectionState.value != ConnectionState.CONNECTED ||
                currentBoard?.address != expectedAddress ||
                BoardRelayPolicy.availability(currentBoard) != BoardRelayAvailability.AVAILABLE
            ) return@launch
            if (seen) enableInternal()
            else {
                pendingDisclosureBoardAddress = expectedAddress
                _state.update {
                    it.copy(
                        enabled = false,
                        pendingDisclosure = true,
                        error = null,
                        errorDetail = null,
                    )
                }
            }
        }
    }

    /** Persist first, then revalidate the exact connected board before the
     * transport can become enabled. */
    fun confirmDisclosureAndEnable() {
        if (!_state.value.pendingDisclosure) return
        val expectedAddress = pendingDisclosureBoardAddress ?: return
        pendingDisclosureBoardAddress = null
        _state.update { it.copy(pendingDisclosure = false) }
        disclosureJob?.cancel()
        disclosureJob = scope.launch {
            userPreferences.setRelayDisclosureSeen()
            val board = bleConnection.connectedBoard
            if (bleConnection.connectionState.value == ConnectionState.CONNECTED &&
                board?.address == expectedAddress &&
                BoardRelayPolicy.availability(board) == BoardRelayAvailability.AVAILABLE
            ) {
                enableInternal()
            }
        }
    }

    fun dismissDisclosure() {
        autoDisclosureDismissedBoardAddress = pendingDisclosureBoardAddress
        disclosureJob?.cancel()
        disclosureJob = null
        pendingDisclosureBoardAddress = null
        _state.update { it.copy(pendingDisclosure = false) }
    }

    /** One-tap stop used by every UI/service surface. */
    fun disable() {
        disclosureJob?.cancel()
        disclosureJob = null
        pendingDisclosureBoardAddress = null
        _state.update { it.copy(pendingDisclosure = false) }
        disableInternal()
    }

    private fun enableInternal() {
        pendingDisclosureBoardAddress = null
        enabledFlow.value = true
        _state.update {
            it.copy(
                pendingDisclosure = false,
                error = null,
                errorDetail = null,
            )
        }
    }

    private fun disableInternal() {
        enabledFlow.value = false
    }

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }

    private suspend fun reconcile(
        enabled: Boolean,
        boardState: ConnectionState,
    ) {
        _state.update { it.copy(enabled = enabled) }
        val board: DiscoveredBoard? = bleConnection.connectedBoard
        val availability = BoardRelayPolicy.availability(board)
        if (boardState == ConnectionState.CONNECTED &&
            availability == BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED
        ) {
            // The observation may arrive while transport startup or the
            // disclosure is already in flight. Settle every such race as a
            // normal capability result, never as a relay failure.
            if (running) stopRelay()
            settleMultiConnectCapacity()
            return
        }
        // Only front the board while actually CONNECTED to it. (SENDING is a
        // transient connected sub-state during a write — neither starts nor
        // stops the relay, so a relayed send never tears itself down.)
        if (enabled && boardState == ConnectionState.CONNECTED && !running) {
            when (availability) {
                BoardRelayAvailability.AVAILABLE -> startRelay()
                BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED ->
                    settleMultiConnectCapacity()
                BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
                BoardRelayAvailability.RELAY_ENDPOINT,
                ->
                    rejectEnable(RelayError.UNSUPPORTED_BOARD)
                BoardRelayAvailability.NO_BOARD -> Unit
            }
        } else if (running &&
            (!enabled || boardState !in setOf(ConnectionState.CONNECTED, ConnectionState.SENDING))
        ) {
            stopRelay()
            if (boardState !in setOf(ConnectionState.CONNECTED, ConnectionState.SENDING) && enabled) {
                // Board loss while sharing: hard-disable so a later reconnect
                // never re-activates sharing without a fresh user action, and
                // surface the loss (never silent — §12). The persistent FGS
                // notification dies with enabled=false, so leave a final
                // auto-dismissible one for background users.
                disableInternal()
                _state.update { it.copy(enabled = false, boardName = null, error = null, errorDetail = null) }
            }
        }
    }

    private fun settleMultiConnectCapacity() {
        disclosureJob?.cancel()
        disclosureJob = null
        pendingDisclosureBoardAddress = null
        disableInternal()
        _state.update {
            it.copy(
                enabled = false,
                boardName = null,
                error = null,
                errorDetail = null,
            )
        }
    }

    private fun rejectEnable(error: RelayError) {
        pendingDisclosureBoardAddress = null
        enabledFlow.value = false
        _state.update {
            it.copy(enabled = false, pendingDisclosure = false, error = error, errorDetail = null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startRelay() {
        val board = bleConnection.connectedBoard
        if (board == null) {
            Log.w(TAG, "startRelay: no connected board descriptor")
            rejectEnable(RelayError.UNSUPPORTED_BOARD)
            return
        }
        // ADVERTISE can be missing even though SCAN and CONNECT were already
        // granted. Fail before renaming the adapter or opening a GATT server;
        // the root UI observes this precise result, shows Android's runtime
        // permission dialog, and retries after a grant.
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            Log.w(TAG, "startRelay: Bluetooth advertising permission missing")
            enabledFlow.value = false
            _state.update {
                it.copy(
                    enabled = false,
                    advertising = false,
                    error = RelayError.ADVERTISE_FAILED,
                    errorDetail = "no permission",
                )
            }
            return
        }
        running = true

        // 1) Snapshot + set the transparent adapter name (crash-safe). An
        // unset name would advertise the relay under the phone's own name —
        // abort instead of impersonating nothing recognizable.
        val desired = RelayBoardName.transparentBoard(board.displayName, board.apiLevel)
        if (!snapshotAndSetAdapterName(desired)) {
            Log.e(TAG, "adapter name change did not propagate")
            abortStart(RelayError.NAME_SET_FAILED, null)
            return
        }

        // 2) Keep the real board link parked while this independent transport
        // is enabled. Relay never creates or tears down a shared queue.
        bleConnection.acquireKeepAlive(BoardConnectionOwner.RELAY)
        // Our own board link would otherwise register as the first relay
        // client the moment the server opens (see RelayGattServer).
        relayServer.boardAddressProvider = { bleConnection.connectedBoard?.address }
        if (!relayServer.start()) {
            Log.e(TAG, "relay server failed to start")
            abortStart(RelayError.SERVER_START_FAILED, null)
            return
        }

        // Identifying a relayed climb needs a one-time index build; start it
        // now so the first official-app write does not wait for it.
        scope.launch { projectionCoordinator.prepareForExternalWrites() }

        // Subscribe before advertising: MutableSharedFlow does not replay a
        // write that arrives while there is no collector.
        forwardJob = scope.launch {
            if (board.boardBrand == BoardBrand.MOONBOARD) {
                // MoonBoard speaks an ASCII Nordic-UART stream. Forward each
                // guest write in order and byte-for-byte; there is no Aurora
                // packet grouping for RelayFrameReassembler to perform.
                relayServer.writes.collect { inbound ->
                    if (bleConnection.sendRawChunks(
                            listOf(inbound.value),
                            expectedBrand = board.boardBrand,
                        )
                    ) {
                        advertiser.clearActiveClimb()
                    } else {
                        Log.w(TAG, "sendRawChunks failed for a relayed MoonBoard write")
                        _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                    }
                }
            } else {
                relayServer.climbs.collect { inbound ->
                    val ok = bleConnection.sendRawChunks(
                        inbound.climb.chunks,
                        expectedBrand = board.boardBrand,
                    )
                    if (ok) {
                        advertiser.clearActiveClimb()
                        // Hand the raw climb along: it is the only thing that can
                        // still name what an official app just put on the wall.
                        // Identification stays off the forwarding path.
                        identifyJob?.cancel()
                        identifyJob = scope.launch {
                            projectionCoordinator.onExternalBoardWrite(inbound.climb)
                        }
                    } else {
                        Log.w(TAG, "sendRawChunks failed for a relayed climb")
                        _state.update { it.copy(error = RelayError.FORWARD_FAILED) }
                    }
                }
            }
        }
        eventJob = scope.launch {
            relayServer.connectionEvents.collect { event ->
                _state.update { it.copy(clientCount = relayServer.getConnectedCount()) }
                if (event is GattConnectionEvent.Connected) {
                    // A connectable legacy advertising set may stop after one
                    // connection. Restart it so further clients can join the
                    // same relay without queue semantics.
                    restartRelayAdvertising()
                }
            }
        }

        val advertisingFailure = startRelayAdvertisingAndAwait()
        if (advertisingFailure != null) {
            abortStart(RelayError.ADVERTISE_FAILED, advertisingFailure)
            return
        }
        val boardLabel = if (board.serial.isNotBlank()) {
            "${board.displayName} #${board.serial}"
        } else board.displayName
        _state.update { it.copy(advertising = true, advertisedName = desired, boardName = boardLabel) }

        // FGS keeps advertising alive (Android 12+ throttles background
        // advertising) + shows the mandatory persistent sharing notification.
        runCatching { CruxRelayService.start(context) }
            .onFailure { Log.e(TAG, "failed to start relay foreground service", it) }

        Log.i(TAG, "CruxRelay started as \"$desired\"")
    }

    private suspend fun startRelayAdvertisingAndAwait(): String? {
        val result = advertiser.startRelayAdvertising()
        if (result != "started" && result != "updated") return result
        val status = withTimeoutOrNull(ADVERTISE_START_TIMEOUT_MS) {
            advertiser.awaitRelayAdvertisingStart()
        }
        return if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            null
        } else {
            status?.let { "status=$it" } ?: "timeout"
        }
    }

    private suspend fun restartRelayAdvertising() {
        if (!running) return
        _state.update { it.copy(advertising = false) }
        val failure = startRelayAdvertisingAndAwait()
        if (!running) return
        if (failure == null) {
            _state.update { it.copy(advertising = true, error = null, errorDetail = null) }
        } else {
            Log.e(TAG, "relay re-advertising failed: $failure")
            _state.update {
                it.copy(advertising = false, error = RelayError.ADVERTISE_FAILED, errorDetail = failure)
            }
        }
    }

    /** Failed mid-start: unwind what was set up (board stays connected — the
     *  user is still using it), disable the toggle, surface the error. */
    private suspend fun abortStart(error: RelayError, detail: String?) {
        stopRelay()
        enabledFlow.value = false
        _state.update { it.copy(enabled = false, error = error, errorDetail = detail) }
    }

    /** Stop only the relay transport. The direct board connection remains. */
    @SuppressLint("MissingPermission")
    private suspend fun stopRelay() {
        if (!running) return
        running = false
        forwardJob?.cancel(); forwardJob = null
        eventJob?.cancel(); eventJob = null
        identifyJob?.cancel(); identifyJob = null
        bleConnection.releaseKeepAlive(BoardConnectionOwner.RELAY)

        advertiser.stopRelayAdvertising()
        relayServer.stop()

        restoreAdapterName()
        _state.update { it.copy(advertising = false, clientCount = 0, advertisedName = null, boardName = null) }
        Log.i(TAG, "CruxRelay stopped; direct board connection preserved")
    }

    // --- Adapter name snapshot / restore (crash-safe) ---

    /** @return true once [desired] is live on the adapter — false on Bluetooth
     *  off or a setName that never propagated (surfaced as NAME_SET_FAILED). */
    @SuppressLint("MissingPermission")
    internal suspend fun snapshotAndSetAdapterName(desired: String): Boolean {
        val a = adapter ?: return false
        val dirty = prefs.getBoolean(KEY_NAME_DIRTY, false)
        val original = if (dirty) {
            prefs.getString(KEY_ORIGINAL_NAME, null)
        } else {
            runCatching { a.name }.getOrNull()
        }
        // Never mutate a device-global setting unless its exact prior value is
        // durably known. Otherwise even an orderly stop cannot restore it.
        if (original.isNullOrBlank()) return false
        if (!dirty) {
            val persisted = withContext(Dispatchers.IO) {
                prefs.edit()
                    .putString(KEY_ORIGINAL_NAME, original)
                    .putBoolean(KEY_NAME_DIRTY, true)
                    .commit()
            }
            if (!persisted) return false
        }
        if (runCatching { a.name }.getOrNull() == desired) return true
        if (!runCatching { a.setName(desired) }.getOrDefault(false)) return false
        // setName is async — wait (bounded) for it to propagate before advertising,
        // since the scan-response name is read from the adapter.
        withTimeoutOrNull(NAME_PROPAGATE_TIMEOUT_MS) {
            while (runCatching { adapter?.name }.getOrNull() != desired) delay(100)
        }
        return runCatching { adapter?.name }.getOrNull() == desired
    }

    @SuppressLint("MissingPermission")
    internal suspend fun restoreAdapterName(): Boolean {
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) return true
        val original = prefs.getString(KEY_ORIGINAL_NAME, null)
        if (original.isNullOrBlank()) return false
        val a = adapter ?: return false
        val before = runCatching { a.name }.getOrNull()
        val accepted = before == original ||
            runCatching { a.setName(original) }.getOrDefault(false)
        if (accepted && before != original) {
            // BluetoothAdapter.setName() is asynchronous on real controllers.
            // Keep the crash-recovery marker until Android reports the original
            // name, just as the relay start waits for its advertised name.
            withTimeoutOrNull(NAME_PROPAGATE_TIMEOUT_MS) {
                while (runCatching { adapter?.name }.getOrNull() != original) delay(100)
            }
        }
        val restored = accepted && runCatching { adapter?.name }.getOrNull() == original
        if (!restored) {
            Log.w(TAG, "Adapter-name restore did not apply; retaining recovery record")
            return false
        }
        return prefs.edit()
            .remove(KEY_NAME_DIRTY)
            .remove(KEY_ORIGINAL_NAME)
            .commit()
    }

    @SuppressLint("MissingPermission")
    internal fun restoreAdapterNameIfDirty() {
        // On a fresh process, a set dirty flag means a prior run died without
        // restoring — put the phone's real Bluetooth name back.
        scope.launch { restoreAdapterName() }
    }
}
