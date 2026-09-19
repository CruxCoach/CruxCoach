package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BoardConnectionUiState(
    val adapter: BleAdapterState = BleAdapterState.UNKNOWN,
    val scanning: Boolean = false,
    /** Strongest signal first. */
    val boards: List<DiscoveredBoard> = emptyList(),
    /** Identifiers of boards in [boards] this device connected to before ("last used" badge). */
    val lastUsedIdentifiers: Set<String> = emptySet(),
    val remembered: List<RememberedBoard> = emptyList(),
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedBoard: DiscoveredBoard? = null,
    val connectionCapacity: BoardConnectionCapacity = BoardConnectionCapacity.UNKNOWN,
    val connectFailure: BoardConnectFailure? = null,
    val sending: Boolean = false,
    /** Outcome of the most recent send or clear; null while one is running or before the first. */
    val lastSendResult: BoardSendResult? = null,
)

/** Handle returned by [BoardConnectionPresenter.watch]; Swift calls [close] from `onDisappear`/`deinit`. */
class StateWatch internal constructor(private val job: Job) {
    fun close() = job.cancel()
}

/**
 * Swift-facing facade over [BoardConnectionController]: plain calls in, one
 * StateFlow out, failures as enum codes, nothing thrown. Swift constructs it
 * with `CoreBluetoothCentral()`; tests pass a fake central and a test dispatcher.
 */
class BoardConnectionPresenter(
    central: BleCentral,
    keyValues: KeyValueStore,
    clock: WallClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val remembered = RememberedBoardStore(keyValues)
    private val controller = BoardConnectionController(central, scope, clock)
    private val _state = MutableStateFlow(BoardConnectionUiState(remembered = remembered.all()))
    val state: StateFlow<BoardConnectionUiState> = _state.asStateFlow()

    init {
        scope.launch {
            var previous = ConnectionState.DISCONNECTED
            controller.state.collect { link ->
                if (link.connection == ConnectionState.CONNECTED && previous == ConnectionState.CONNECTING) {
                    link.connectedBoard?.let(remembered::remember)
                }
                previous = link.connection
                val known = remembered.all()
                val knownIds = known.mapTo(mutableSetOf()) { it.identifier }
                _state.update { ui ->
                    ui.copy(
                        adapter = link.adapter,
                        scanning = link.scanning,
                        boards = link.boards.sortedByDescending { it.rssi },
                        lastUsedIdentifiers = link.boards.map { it.identifier }.filterTo(mutableSetOf()) { it in knownIds },
                        remembered = known,
                        connection = link.connection,
                        connectedBoard = link.connectedBoard,
                        connectionCapacity = if (link.connectedBoard == null) {
                            BoardConnectionCapacity.UNKNOWN
                        } else {
                            BoardControllerProfiles.forBoard(link.connectedBoard).connectionCapacity
                        },
                        connectFailure = link.failure,
                    )
                }
            }
        }
    }

    fun watch(onState: (BoardConnectionUiState) -> Unit): StateWatch =
        StateWatch(scope.launch { state.collect { onState(it) } })

    /** Shows the iOS Bluetooth permission prompt on first use; the adapter state follows in [state]. */
    fun activate() = controller.activate()
    fun startScan() = controller.startScan()
    fun stopScan() = controller.stopScan()

    /** Connects a board from the current scan list; unknown identifiers are ignored. */
    fun connect(identifier: String) {
        val board = controller.state.value.boards.firstOrNull { it.identifier == identifier } ?: return
        controller.connect(board)
    }

    /**
     * Reconnects the remembered board of [brandWire] without a scan. Whether
     * CoreBluetooth still knows the identifier is only learnt by trying, so this
     * gets a single attempt and fails into [BoardConnectionUiState.connectFailure].
     */
    fun reconnectRemembered(brandWire: String) {
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        val board = remembered.get(brand)?.toDiscovered() ?: return
        controller.connect(board, maxAttempts = 1)
    }

    fun forgetRemembered(brandWire: String) {
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        remembered.forget(brand)
        _state.update { it.copy(remembered = remembered.all()) }
    }

    fun disconnect() = controller.disconnect()

    fun setAutoDisconnectSeconds(seconds: Int) {
        controller.autoDisconnectSeconds = seconds.coerceAtLeast(0)
    }

    /**
     * Lights an Aurora-family climb. [roleColors] comes from [resolveRoleColors];
     * [expectedBrandWire] is the brand the LED map was loaded for and fences the
     * write against a board swapped underneath the screen.
     */
    fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        roleColors: Map<Int, Int>,
        expectedBrandWire: String,
    ) {
        val brand = BoardBrand.fromWireOrNull(expectedBrandWire)
        if (brand == null) {
            _state.update { it.copy(lastSendResult = BoardSendResult.BOARD_MISMATCH) }
            return
        }
        run { controller.sendClimb(holds, placementToLed, roleColors, expectedBrand = brand) }
    }

    /** [layoutId] is the CLIMB's layout: the serpentine differs per variant. Unknown ids fall back to 2016, as on Android. */
    fun sendMoonBoardClimb(frames: String, layoutId: Long, ledModeWire: String?) = run {
        controller.sendMoonBoardClimb(
            frames,
            MoonBoardVariant.fromLayoutId(layoutId) ?: MoonBoardVariant.MOONBOARD_2016,
            MoonBoardLedMode.fromWire(ledModeWire),
        )
    }

    fun clearBoard() = run { controller.clearBoard() }

    fun dispose() {
        controller.disconnect()
        scope.cancel()
    }

    private fun run(operation: suspend () -> BoardSendResult) {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, lastSendResult = null) }
        scope.launch {
            val result = try {
                operation()
            } catch (e: IllegalArgumentException) {
                // The shared encoders reject malformed frames/ids by throwing.
                BoardSendResult.INVALID_PAYLOAD
            }
            _state.update { it.copy(sending = false, lastSendResult = result) }
        }
    }
}
