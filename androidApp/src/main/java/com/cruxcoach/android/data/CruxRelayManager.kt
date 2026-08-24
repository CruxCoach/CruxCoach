package com.cruxcoach.android.data

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
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
    /** No official-app client remained for the bounded sharing window. */
    IDLE_TIMEOUT,
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
    private val monotonicNowMs: () -> Long = SystemClock::elapsedRealtime,
    private val relayIdleTimeoutMs: Long = RELAY_IDLE_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "CruxRelay/Manager"
        private const val PREFS = "cruxrelay"
        private const val KEY_NAME_DIRTY = "adapter_name_dirty"
        private const val KEY_ORIGINAL_NAME = "adapter_name_original"
        private const val NAME_PROPAGATE_TIMEOUT_MS = 2_000L
        private const val ADVERTISE_START_TIMEOUT_MS = 3_000L
        private const val STOPPED_NOTIFICATION_ID = 4402
        internal const val RELAY_IDLE_TIMEOUT_MS = 90_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter
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
    private val idleWatchdog = RelayIdleWatchdog(
        scope = scope,
        timeoutMs = relayIdleTimeoutMs,
        nowMs = monotonicNowMs,
        clientCount = relayServer::getConnectedCount,
        onTimeout = {
            if (running && relayServer.getConnectedCount() == 0) {
                Log.i(TAG, "CruxRelay stopped after zero-client idle timeout")
                postStoppedNotification(R.string.relay_stopped_idle)
                _state.update {
                    it.copy(error = RelayError.IDLE_TIMEOUT, errorDetail = null)
                }
                disableInternal()
            }
        },
    )

    init {
        // Crash-safe: a previous run may have died with the adapter name still
        // changed. Restore it before anything else.
        restoreAdapterNameIfDirty()
        // React to BOTH the runtime toggle AND the board connection: the relay
        // runs only while enabled AND the real board link is up
        // (WAIT_BEFORE_ADVERTISE). A falling board link disables sharing
        // entirely, so it never outlives the connection it was started for.
        scope.launch {
            combine(enabledFlow, bleConnection.connectionState) { enabled, st ->
                enabled to st
            }.collect { (enabled, st) -> reconcile(enabled, st) }
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
                    if (shouldShare && !enabledFlow.value) requestEnable()
                    else if (!shouldShare &&
                        bleConnection.connectionState.value == ConnectionState.DISCONNECTED
                    ) disable()
                }
        }
    }

    /** The sole start entry point for manual, automatic and permission-retry
     * paths. No caller can enable transport before the persisted disclosure. */
    fun requestEnable() {
        val board = bleConnection.connectedBoard ?: return
        if (bleConnection.connectionState.value != ConnectionState.CONNECTED ||
            BoardRelayPolicy.availability(board) != BoardRelayAvailability.AVAILABLE
        ) {
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

    private suspend fun reconcile(enabled: Boolean, boardState: ConnectionState) {
        _state.update { it.copy(enabled = enabled) }
        // Only front the board while actually CONNECTED to it. (SENDING is a
        // transient connected sub-state during a write — neither starts nor
        // stops the relay, so a relayed send never tears itself down.)
        if (enabled && boardState == ConnectionState.CONNECTED && !running) {
            when (BoardRelayPolicy.availability(
                board = bleConnection.connectedBoard,
            )) {
                BoardRelayAvailability.AVAILABLE -> startRelay()
                BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
                BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED,
                BoardRelayAvailability.RELAY_ENDPOINT,
                ->
                    rejectEnable(RelayError.UNSUPPORTED_BOARD)
                BoardRelayAvailability.NO_BOARD -> Unit
            }
        } else if (running && (!enabled || boardState == ConnectionState.DISCONNECTED)) {
            stopRelay()
            if (boardState == ConnectionState.DISCONNECTED && enabled) {
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
                    idleWatchdog.activity()
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
                    idleWatchdog.activity()
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
                idleWatchdog.activity()
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
        idleWatchdog.start()

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
        idleWatchdog.stop()
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

    /** Final, auto-dismissible "sharing stopped" notification (FEAT-044 §12:
     *  never fail/stop silently). Posted BEFORE the enabled=false state change
     *  tears down [CruxRelayService]'s persistent notification, on the same
     *  channel (which the service created when sharing started). Best-effort:
     *  POST_NOTIFICATIONS may have been revoked. */
    private fun postStoppedNotification(@StringRes textRes: Int) {
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, CruxRelayService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(context.getString(R.string.relay_notification_title))
                .setContentText(context.getString(textRes))
                .setAutoCancel(true)
                .build()
            mgr.notify(STOPPED_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "failed to post relay-stopped notification", it) }
    }

    // --- Adapter name snapshot / restore (crash-safe) ---

    /** @return true once [desired] is live on the adapter — false on Bluetooth
     *  off or a setName that never propagated (surfaced as NAME_SET_FAILED). */
    @SuppressLint("MissingPermission")
    private suspend fun snapshotAndSetAdapterName(desired: String): Boolean {
        val a = adapter ?: return false
        val original = a.name
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) {
            prefs.edit().putString(KEY_ORIGINAL_NAME, original).putBoolean(KEY_NAME_DIRTY, true).apply()
        }
        if (a.name == desired) return true
        a.name = desired
        // setName is async — wait (bounded) for it to propagate before advertising,
        // since the scan-response name is read from the adapter.
        withTimeoutOrNull(NAME_PROPAGATE_TIMEOUT_MS) {
            while (adapter?.name != desired) delay(100)
        }
        return adapter?.name == desired
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) return
        val original = prefs.getString(KEY_ORIGINAL_NAME, null)
        if (original != null) runCatching { adapter?.name = original }
        prefs.edit().putBoolean(KEY_NAME_DIRTY, false).remove(KEY_ORIGINAL_NAME).apply()
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterNameIfDirty() {
        // On a fresh process, a set dirty flag means a prior run died without
        // restoring — put the phone's real Bluetooth name back.
        restoreAdapterName()
    }
}
