package com.cruxcoach.android.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.domain.relay.RelayBoardName
import com.cruxcoach.domain.relay.RelayCaptureDedup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One climb an official-app client relayed through CruxCoach, kept for the
 *  optional playlist capture. Raw frames carry no UUID/name/grade. */
data class CapturedRelayClimb(
    val deviceAddress: String,
    val framesHash: Long,
    val holdCount: Int,
    val rawBytes: ByteArray,
    val capturedAtMs: Long,
)

data class CruxRelayState(
    val enabled: Boolean = false,
    val advertising: Boolean = false,
    val clientCount: Int = 0,
    val advertisedName: String? = null,
    /** Capture relayed climbs into the playlist (runtime flag, off by default). */
    val captureToPlaylist: Boolean = false,
    val error: String? = null,
)

/**
 * CruxRelay orchestration (FEAT-044): CruxCoach fronts the real board so
 * official-Kilter-app users can send climbs through it, transparently.
 *
 * Runs the board-emulation [RelayGattServer] + advertises the board's
 * 4488B571 UUID under a transparent [RelayBoardName]. A completed climb is
 * forwarded byte-faithfully to the real board via [BoardBleConnection.sendRawChunks]
 * (last-write-wins; the board's own writeMutex + one send-per-climb keep whole
 * climbs atomic). When capture is on, the same climb is also recorded (deduped)
 * for optional playlist collection.
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
) {
    companion object {
        private const val TAG = "CruxRelay/Manager"
        private const val KEEP_ALIVE_OWNER = "relay"
        private const val PREFS = "cruxrelay"
        private const val KEY_NAME_DIRTY = "adapter_name_dirty"
        private const val KEY_ORIGINAL_NAME = "adapter_name_original"
        private const val NAME_PROPAGATE_TIMEOUT_MS = 2_000L
        private const val BOARD_RELEASE_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_IDLE_MS = 90_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(CruxRelayState())
    val state: StateFlow<CruxRelayState> = _state.asStateFlow()

    private val _captured = MutableStateFlow<List<CapturedRelayClimb>>(emptyList())
    val captured: StateFlow<List<CapturedRelayClimb>> = _captured.asStateFlow()

    // Sharing is deliberately NOT persisted (FEAT-044 §12): it is a momentary,
    // safety-relevant action. Default OFF every process; only setEnabled(true)
    // — a fresh user tap — turns it on, and a lost board turns it back off so
    // a later reconnect never silently re-fronts the board.
    private val enabledFlow = MutableStateFlow(false)

    private val captureDedup = RelayCaptureDedup()
    private var running = false
    private var forwardJob: Job? = null
    private var eventJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastActivityMs: Long = 0L

    init {
        // Crash-safe: a previous run may have died with the adapter name still
        // changed. Restore it before anything else.
        restoreAdapterNameIfDirty()
        // React to BOTH the runtime toggle AND the board connection: the relay
        // runs only while enabled AND the real board link is up
        // (WAIT_BEFORE_ADVERTISE). A falling board link disables sharing
        // entirely — it never re-arms on a later board connection.
        scope.launch {
            combine(enabledFlow, bleConnection.connectionState) { enabled, st ->
                enabled to st
            }.collect { (enabled, st) -> reconcile(enabled, st) }
        }
    }

    /** UI entry point — a deliberate user action; [init]'s collector does the rest. */
    fun setEnabled(enabled: Boolean) {
        enabledFlow.value = enabled
        if (enabled) _state.update { it.copy(error = null) }
    }

    /** Runtime capture flag (FEAT-044 §5) — never persisted, off each launch. */
    fun setCaptureToPlaylist(enabled: Boolean) {
        _state.update { it.copy(captureToPlaylist = enabled) }
    }

    private suspend fun reconcile(enabled: Boolean, boardState: ConnectionState) {
        _state.update { it.copy(enabled = enabled) }
        // Only front the board while actually CONNECTED to it. (SENDING is a
        // transient connected sub-state during a write — neither starts nor
        // stops the relay, so a relayed send never tears itself down.)
        if (enabled && boardState == ConnectionState.CONNECTED && !running) {
            startRelay()
        } else if (running && (!enabled || boardState == ConnectionState.DISCONNECTED)) {
            // Release the real board only if it's still up (user turned the relay
            // off = host leaving, §7 ordering); a dropped board is already gone.
            stopRelay(releaseBoard = boardState != ConnectionState.DISCONNECTED)
            if (boardState == ConnectionState.DISCONNECTED && enabled) {
                // Board loss while sharing: hard-disable so a later reconnect
                // never re-activates sharing without a fresh user action.
                enabledFlow.value = false
                _state.update { it.copy(enabled = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startRelay() {
        val boardName = bleConnection.connectedBoardName.value
        if (boardName == null) { Log.w(TAG, "startRelay: no connected board name"); return }
        running = true
        lastActivityMs = System.currentTimeMillis()

        // 1) Snapshot + set the transparent adapter name (crash-safe).
        val desired = RelayBoardName.transparent(boardName)
        snapshotAndSetAdapterName(desired)

        // 2) Keep the real board link parked, start server + advertising.
        bleConnection.acquireKeepAlive(KEEP_ALIVE_OWNER)
        if (!relayServer.start()) {
            Log.e(TAG, "relay server failed to start"); stopRelay(releaseBoard = false); return
        }
        val advResult = advertiser.startRelayAdvertising()
        _state.update { it.copy(advertising = true, advertisedName = desired, error = advResult.takeIf { r -> r != "started" && r != "updated" }) }

        // 3) Forward completed climbs to the real board; capture if enabled.
        forwardJob = scope.launch {
            relayServer.climbs.collect { inbound ->
                lastActivityMs = System.currentTimeMillis()
                val ok = bleConnection.sendRawChunks(inbound.climb.chunks)
                if (!ok) Log.w(TAG, "sendRawChunks failed for a relayed climb")
                if (_state.value.captureToPlaylist) captureIfNew(inbound.deviceAddress, inbound.climb.framesHash, inbound.climb.holdCount, inbound.climb.rawBytes)
            }
        }
        eventJob = scope.launch {
            relayServer.connectionEvents.collect { ev ->
                when (ev) {
                    is GattConnectionEvent.Connected -> lastActivityMs = System.currentTimeMillis()
                    is GattConnectionEvent.Disconnected -> captureDedup.onClientGone(ev.deviceAddress)
                }
                _state.update { it.copy(clientCount = relayServer.getConnectedCount()) }
            }
        }
        // (Board-loss teardown is handled by the init combine() collector, which
        // observes connectionState whether or not the relay is running.)
        // Watchdog: auto-disable after a long idle with no clients.
        watchdogJob = scope.launch {
            while (running) {
                delay(WATCHDOG_IDLE_MS / 3)
                val idle = System.currentTimeMillis() - lastActivityMs
                if (relayServer.getConnectedCount() == 0 && idle >= WATCHDOG_IDLE_MS) {
                    Log.d(TAG, "watchdog: idle ${idle}ms, no clients — auto-disabling relay")
                    setEnabled(false)
                    break
                }
            }
        }
        Log.i(TAG, "CruxRelay started as \"$desired\"")
    }

    /**
     * Host-leave ordering (FEAT-044 §7): release the REAL board FIRST so it
     * re-advertises and the official-app clients' own reconnect finds it, THEN
     * tear down the relay, THEN restore the adapter name.
     */
    @SuppressLint("MissingPermission")
    private suspend fun stopRelay(releaseBoard: Boolean) {
        if (!running) return
        running = false
        // Drain.
        forwardJob?.cancel(); forwardJob = null
        eventJob?.cancel(); eventJob = null
        watchdogJob?.cancel(); watchdogJob = null
        captureDedup.reset()

        if (releaseBoard && bleConnection.connectionState.value != ConnectionState.DISCONNECTED) {
            bleConnection.disconnect()
            withTimeoutOrNull(BOARD_RELEASE_TIMEOUT_MS) {
                bleConnection.connectionState.first { it == ConnectionState.DISCONNECTED }
            }
        }
        bleConnection.releaseKeepAlive(KEEP_ALIVE_OWNER)

        advertiser.stopRelayAdvertising()
        relayServer.stop()

        restoreAdapterName()
        _state.update { it.copy(advertising = false, clientCount = 0, advertisedName = null) }
        Log.i(TAG, "CruxRelay stopped (releaseBoard=$releaseBoard)")
    }

    private fun captureIfNew(device: String, framesHash: Long, holdCount: Int, rawBytes: ByteArray) {
        val now = System.currentTimeMillis()
        if (!captureDedup.shouldCapture(device, framesHash, now, _captured.value.size)) return
        _captured.update { it + CapturedRelayClimb(device, framesHash, holdCount, rawBytes, now) }
    }

    fun clearCaptured() { _captured.value = emptyList() }

    // --- Adapter name snapshot / restore (crash-safe) ---

    @SuppressLint("MissingPermission")
    private suspend fun snapshotAndSetAdapterName(desired: String) {
        val a = adapter ?: return
        val original = a.name
        if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) {
            prefs.edit().putString(KEY_ORIGINAL_NAME, original).putBoolean(KEY_NAME_DIRTY, true).apply()
        }
        if (a.name == desired) return
        a.name = desired
        // setName is async — wait (bounded) for it to propagate before advertising,
        // since the scan-response name is read from the adapter.
        withTimeoutOrNull(NAME_PROPAGATE_TIMEOUT_MS) {
            while (adapter?.name != desired) delay(100)
        }
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
