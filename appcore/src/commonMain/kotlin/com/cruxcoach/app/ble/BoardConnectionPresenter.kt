package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
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
    /** Quantum only: the model proven by the controller itself. Quantum SENDING is not exposed here yet. */
    val quantumModel: QuantumBoardModel? = null,
    val connectionCapacity: BoardConnectionCapacity = BoardConnectionCapacity.UNKNOWN,
    val connectFailure: BoardConnectFailure? = null,
    val sending: Boolean = false,
    /** Outcome of the most recent send or clear; null while one is running or before the first. */
    val lastSendResult: BoardSendResult? = null,
    /** Quantum only: routes the controller reports on the wall (all apps), and how fresh that roster is. */
    val quantumActivePlayers: Int = 0,
    val quantumSyncStatus: QuantumControllerSyncStatus = QuantumControllerSyncStatus.UNSYNCED,
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
    private val cleaningStorage = BoardCleaningStorage(keyValues)
    /** Process-wide Quantum layer rack: its identities are the only ones a scoped Quantum write may use. */
    internal val quantumLayers = com.cruxcoach.app.quantum.BoardLayerManager(keyValues)
    private val controller = BoardConnectionController(
        central, scope, clock,
        cleaning = BoardCleaningSession(cleaningStorage.load(), cleaningStorage::save) {
            cleaningLocalDate(clock.epochMillis())
        },
        ownsQuantumUserId = { quantumLayers.ownsIdentity(it) },
    )
    private val _state = MutableStateFlow(BoardConnectionUiState(remembered = remembered.all()))
    val state: StateFlow<BoardConnectionUiState> = _state.asStateFlow()

    /** What this phone last lit, for the nearby area's "your climb" (Android `BoardStateManager`). */
    internal val localBoardClimb = com.cruxcoach.app.nearby.LocalBoardClimbMemory(keyValues, clock::epochMillis, scope)

    init {
        scope.launch {
            var previous = ConnectionState.DISCONNECTED
            controller.state.collect { link ->
                if (link.connection == ConnectionState.CONNECTED && previous == ConnectionState.CONNECTING) {
                    link.connectedBoard?.let(remembered::remember)
                }
                previous = link.connection
                if (link.connection != ConnectionState.CONNECTED && link.connection != ConnectionState.SENDING) {
                    localBoardClimb.linkEnded()
                }
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
                        quantumModel = link.quantumModel,
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

    init {
        // Read-only Quantum wall status (Android's layer status strip); layers themselves are not ported.
        scope.launch {
            controller.quantumState.collect { quantum ->
                _state.update { it.copy(quantumActivePlayers = quantum.players.size, quantumSyncStatus = quantum.syncStatus) }
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
    ) = sendClimb(holds, placementToLed, roleColors, expectedBrandWire, sent = null)

    /** As [sendClimb]; a confirmed write records [sent] as this phone's climb on the wall. */
    internal fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        roleColors: Map<Int, Int>,
        expectedBrandWire: String,
        sent: SentClimb?,
    ) {
        val brand = BoardBrand.fromWireOrNull(expectedBrandWire)
        if (brand == null) {
            _state.update { it.copy(lastSendResult = BoardSendResult.BOARD_MISMATCH) }
            return
        }
        run(sent, survives = true) { controller.sendClimb(holds, placementToLed, roleColors, expectedBrand = brand) }
    }

    /** [layoutId] is the CLIMB's layout: the serpentine differs per variant. Unknown ids fall back to 2016, as on Android. */
    fun sendMoonBoardClimb(frames: String, layoutId: Long, ledModeWire: String?) =
        sendMoonBoardClimb(frames, layoutId, ledModeWire, sent = null)

    /** A MoonBoard's LEDs go out with the link (Android `BoardProjectionPolicy`). */
    internal fun sendMoonBoardClimb(frames: String, layoutId: Long, ledModeWire: String?, sent: SentClimb?) =
        run(sent, survives = false) {
            controller.sendMoonBoardClimb(
                frames,
                MoonBoardVariant.fromLayoutId(layoutId) ?: MoonBoardVariant.MOONBOARD_2016,
                MoonBoardLedMode.fromWire(ledModeWire),
            )
        }

    fun clearBoard() = run { controller.clearBoard() }

    /** The easter animation's frames: the result goes to the caller, not to the send banner. */
    internal suspend fun sendRawLeds(leds: List<Pair<Int, Int>>, expectedBrand: BoardBrand): BoardSendResult = try {
        controller.sendRawLeds(leds, expectedBrand)
    } catch (e: IllegalArgumentException) {
        BoardSendResult.INVALID_PAYLOAD
    }

    /** Clears the board only while [expectedBrand] is the one connected (the easter animation's end). */
    internal suspend fun clearBoardFor(expectedBrand: BoardBrand): BoardSendResult = controller.clearBoard(expectedBrand)

    // ── Clean holds ─────────────────────────────────────────────────────

    val cleaningState: StateFlow<BoardCleaningState> get() = controller.cleaningState

    fun refreshCleaningDay() = controller.refreshCleaningDay()

    /**
     * Runs on this presenter's scope, not the caller's: leaving the screen
     * must not interrupt a multi-chunk frame or let its outstanding write
     * callback acknowledge a new write. [onResult] gets false for any failure.
     */
    fun startCleaning(onResult: (Boolean) -> Unit) = cleaningCommand(onResult) { identifier ->
        controller.startCleaning(identifier)
    }

    fun finishCleaning(markCleaned: Boolean, onResult: (Boolean) -> Unit) = cleaningCommand(onResult) { identifier ->
        controller.finishCleaning(identifier, markCleaned)
    }

    private fun cleaningCommand(onResult: (Boolean) -> Unit, command: suspend (String) -> Boolean) {
        val identifier = controller.state.value.connectedBoard?.identifier
        if (identifier == null) {
            onResult(false)
            return
        }
        scope.launch {
            val success = try {
                command(identifier)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            onResult(success)
        }
    }

    /** Android refreshes a stale Quantum roster on every return to the foreground. */
    fun refreshQuantumOnForeground() = controller.refreshQuantumStateOnForeground()

    /** The connection as the Quantum layer controller needs it; writes run on this presenter's controller. */
    internal val quantumLink: com.cruxcoach.app.quantum.QuantumLink = object : com.cruxcoach.app.quantum.QuantumLink {
        override val connection: StateFlow<BoardConnectionUiState> get() = state
        override val controller: StateFlow<QuantumControllerState> get() = this@BoardConnectionPresenter.controller.quantumState
        override suspend fun refresh(): Boolean = this@BoardConnectionPresenter.controller.refreshQuantumState()
        override suspend fun sendClimb(
            holds: List<BoardHold>,
            placementToLed: Map<Int, Int>,
            routeId: String,
            userId: String,
            color: Int,
            expectedPlayers: List<com.cruxcoach.domain.board.QuantumActivePlayer>,
            expectedBoard: BoardLayerBoardIdentity,
        ): BoardSendResult = this@BoardConnectionPresenter.controller.sendQuantumClimb(
            holds, placementToLed, routeId, userId, color, expectedPlayers, expectedBoard,
        )
        override suspend fun removeLayer(userId: String, expectedRouteId: String, expectedBoard: BoardLayerBoardIdentity): BoardSendResult =
            this@BoardConnectionPresenter.controller.removeQuantumLayer(userId, expectedRouteId, expectedBoard)
    }

    fun dispose() {
        controller.disconnect()
        scope.cancel()
    }

    private fun run(operation: suspend () -> BoardSendResult) = run(null, survives = true, operation)

    private fun run(sent: SentClimb?, survives: Boolean, operation: suspend () -> BoardSendResult) {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, lastSendResult = null) }
        scope.launch {
            val result = try {
                operation()
            } catch (e: IllegalArgumentException) {
                // The shared encoders reject malformed frames/ids by throwing.
                BoardSendResult.INVALID_PAYLOAD
            }
            val board = _state.value.connectedBoard?.identifier
            if (result == BoardSendResult.OK && sent != null && board != null) {
                localBoardClimb.record(sent.uuid, sent.angle, sent.name, survives, board)
            }
            _state.update { it.copy(sending = false, lastSendResult = result) }
        }
    }
}

/** Which climb a send lights, for the nearby area's "your climb". */
internal data class SentClimb(val uuid: String, val angle: Int, val name: String)
