package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Why the last connect ended; survives the disconnect, cleared by the next connect. */
enum class BoardConnectFailure {
    /** Radio, discovery or setup failed after every quiet retry. */
    CONNECT_FAILED,

    /** Pre-2017 MoonBoard LED kit (RedBear UART): recognised, not supported. */
    MOONBOARD_GENERATION_UNSUPPORTED,

    /** Bluetooth is off, unauthorised or absent; see [BoardLinkState.adapter]. */
    BLUETOOTH_UNAVAILABLE,
}

enum class BoardSendResult {
    OK,
    NOT_CONNECTED,

    /** The connected board is not the brand (or Quantum controller) the payload was built for. */
    BOARD_MISMATCH,

    /** The command is not valid for the connected board family. */
    WRONG_BOARD_FAMILY,
    WRITE_FAILED,

    /** No write callback within 5 s; the link was torn down. */
    WRITE_TIMEOUT,

    /** Quantum: the frame exceeds one ATT write; frames are never fragmented. */
    FRAME_TOO_LARGE,
    QUANTUM_PRECONDITION_FAILED,
    QUANTUM_STATE_UNAVAILABLE,
    QUANTUM_NOT_CONFIRMED,
}

data class BoardLinkState(
    val adapter: BleAdapterState = BleAdapterState.UNKNOWN,
    val scanning: Boolean = false,
    val boards: List<DiscoveredBoard> = emptyList(),
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedBoard: DiscoveredBoard? = null,
    /** Model proven by the controller's fff5 record; null fences model-scoped Quantum writes. */
    val quantumModel: QuantumBoardModel? = null,
    val failure: BoardConnectFailure? = null,
    val keepAliveActive: Boolean = false,
)

/**
 * Connection and send state machine: a port of Android's BoardBleConnection and
 * BoardBleScanner onto [BleCentral]. Bytes, ordering, pacing, retry counts and
 * timeouts are Android's. Android-stack workarounds (GATT refresh/close, legacy
 * settle delays, populated-services fallback) have no CoreBluetooth counterpart
 * and are not reproduced.
 *
 * Single-threaded: [scope] must use a confined dispatcher (Main on iOS). Central
 * events are re-dispatched onto it, so no state here is shared between threads.
 */
class BoardConnectionController(
    private val central: BleCentral,
    private val scope: CoroutineScope,
    internal val clock: WallClock,
    internal val ownsQuantumUserId: (String) -> Boolean = { false },
) {
    companion object {
        const val WRITE_TIMEOUT_MS = 5000L
        const val CLOSE_SAFETY_TIMEOUT_MS = 5000L
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 10_000L
        const val MAX_CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 600L

        /** Android's modern-stack pause before a fresh connect. */
        const val DELAY_RECONNECT_MS = 200L

        /**
         * MoonBoard's NUS bridge acknowledges an ATT write before its UART
         * parser has consumed it; back-to-back fragments arrive as an
         * incomplete command. One short pause between fragments.
         */
        const val MOONBOARD_UART_INTER_CHUNK_DELAY_MS = 100L
    }

    internal class Link(val board: DiscoveredBoard) {
        val identifier: String get() = board.identifier
        var services: List<BleServiceInfo> = emptyList()
        var writeService: String? = null
        var writeCharacteristic: String? = null
        var writeWithResponse: Boolean = true
        var discoveryHandled = false
        var quantumService: String? = null
    }

    private val _state = MutableStateFlow(BoardLinkState(adapter = central.adapterState()))
    val state: StateFlow<BoardLinkState> = _state.asStateFlow()

    internal var link: Link? = null
    private var encoder = BoardPacketEncoder(3)
    private val boardMap = LinkedHashMap<String, DiscoveredBoard>()
    private var connectAttempt = 0
    private var attemptBudget = MAX_CONNECT_ATTEMPTS
    private var connectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var idleJob: Job? = null
    private var closeSafetyJob: Job? = null
    private var scanWhenPoweredOn = false

    /** Completed by the disconnect event of a link we cancelled; a new attempt waits for it. */
    private var pendingClose: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    internal val writeMutex = Mutex()
    private val keepAliveOwners = mutableSetOf<String>()
    internal val quantum = QuantumLinkSupport(this)

    var autoDisconnectSeconds: Int = 0
        set(value) {
            field = value
            resetIdleTimer()
        }

    val idleDisconnectArmed: Boolean get() = idleJob?.isActive == true

    init {
        central.setListener(Events())
    }

    internal val connection: ConnectionState get() = _state.value.connection
    internal val connectedBrand: BoardBrand? get() = _state.value.connectedBoard?.boardBrand
    internal fun updateState(transform: (BoardLinkState) -> BoardLinkState) = _state.update(transform)
    internal fun launch(block: suspend () -> Unit): Job = scope.launch { block() }

    // ── Scan ────────────────────────────────────────────────────────────

    /** No filter: boards are identified purely by advertised name. Refused unless the adapter is on. */
    fun startScan() {
        if (_state.value.scanning) return
        val adapter = central.adapterState()
        if (adapter != BleAdapterState.POWERED_ON) {
            _state.update { it.copy(adapter = adapter) }
            // First use: the adapter state is not known until the manager is
            // up (and, on iOS, the user has answered the permission prompt).
            if (adapter == BleAdapterState.UNKNOWN) {
                scanWhenPoweredOn = true
                central.activate()
            }
            return
        }
        scanWhenPoweredOn = false
        boardMap.clear()
        _state.update { it.copy(scanning = true, boards = emptyList()) }
        central.startScan()
    }

    /** Brings Bluetooth up without scanning, so the adapter state (and iOS's prompt) resolves. */
    fun activate() = central.activate()

    fun stopScan() {
        scanWhenPoweredOn = false
        if (!_state.value.scanning) return
        central.stopScan()
        _state.update { it.copy(scanning = false) }
    }

    // ── Keep-alive / idle ───────────────────────────────────────────────

    fun acquireKeepAlive(owner: String) {
        keepAliveOwners.add(owner)
        _state.update { it.copy(keepAliveActive = true) }
        // A timer armed before the acquire would otherwise keep running.
        resetIdleTimer()
    }

    fun releaseKeepAlive(owner: String) {
        keepAliveOwners.remove(owner)
        _state.update { it.copy(keepAliveActive = keepAliveOwners.isNotEmpty()) }
        if (keepAliveOwners.isEmpty()) resetIdleTimer()
    }

    internal fun parkIdleTimer() {
        idleJob?.cancel()
    }

    internal fun resetIdleTimer() {
        idleJob?.cancel()
        val seconds = autoDisconnectSeconds
        val profile = BoardControllerProfiles.forBoard(link?.board)
        val arm = BoardProjectionPolicy.shouldArmIdleDisconnect(
            seconds = seconds,
            connectionState = connection,
            explicitlySuppressed = keepAliveOwners.isNotEmpty(),
            connectionCapacity = profile.connectionCapacity,
            projectionSurvivesDisconnect =
                profile.projectionLifetime == BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
        )
        if (arm) {
            idleJob = scope.launch {
                delay(seconds * 1_000L)
                disconnect()
            }
        }
    }

    // ── Connect ─────────────────────────────────────────────────────────

    /**
     * [maxAttempts] bounds the quiet retries of radio-level failures; a
     * speculative reconnect to a remembered board passes fewer than three.
     */
    fun connect(board: DiscoveredBoard, maxAttempts: Int = MAX_CONNECT_ATTEMPTS) {
        if (connection != ConnectionState.DISCONNECTED) return
        val adapter = central.adapterState()
        if (adapter != BleAdapterState.POWERED_ON) {
            _state.update { it.copy(adapter = adapter, failure = BoardConnectFailure.BLUETOOTH_UNAVAILABLE) }
            return
        }
        quantum.onConnectStarting(board)
        attemptBudget = maxAttempts.coerceIn(1, MAX_CONNECT_ATTEMPTS)
        connectAttempt = 1
        // MoonBoard advertises no API level, so this is BoardPacketEncoder(0): see clearBoard.
        encoder = BoardPacketEncoder(board.apiLevel, BoardPacketEncoder.ledsPerHoldFor(board.boardBrand))
        if (_state.value.scanning) {
            central.stopScan()
        }
        _state.update {
            it.copy(
                scanning = false,
                connection = ConnectionState.CONNECTING,
                connectedBoard = board,
                quantumModel = null,
                failure = null,
            )
        }
        connectJob = scope.launch {
            awaitPendingClose()
            delay(DELAY_RECONNECT_MS)
            startAttempt(board)
        }
    }

    private suspend fun awaitPendingClose() {
        val closing = pendingClose ?: return
        withTimeoutOrNull(CLOSE_SAFETY_TIMEOUT_MS + 1000) { closing.await() }
        if (pendingClose === closing) pendingClose = null
    }

    private fun canRetryConnect(): Boolean =
        connection == ConnectionState.CONNECTING && connectAttempt < attemptBudget && link != null

    private fun startAttempt(board: DiscoveredBoard) {
        if (connection != ConnectionState.CONNECTING) return
        _state.update { it.copy(quantumModel = null) }
        link = Link(board)
        central.connect(board.identifier)
        connectionTimeoutJob = scope.launch {
            delay(CONNECT_ATTEMPT_TIMEOUT_MS)
            if (connection == ConnectionState.CONNECTING) {
                if (canRetryConnect()) scheduleRetry() else failAndDisconnect(BoardConnectFailure.CONNECT_FAILED)
            }
        }
    }

    /** Tear the attempt down without leaving CONNECTING, then try again after the backoff. */
    private fun scheduleRetry() {
        connectAttempt += 1
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        quantum.cancelOperations()
        _state.update { it.copy(quantumModel = null) }
        val board = link?.board
        link?.let { retire(it) }
        link = null
        if (board == null) {
            finalizeRemoteDisconnect()
            return
        }
        connectJob = scope.launch {
            delay(CONNECT_RETRY_DELAY_MS)
            awaitPendingClose()
            if (connection != ConnectionState.CONNECTING) return@launch
            startAttempt(board)
        }
    }

    /** Cancel a link and fence its eventual disconnect event away from any later attempt. */
    private fun retire(retiring: Link) {
        val closing = CompletableDeferred<Unit>()
        pendingClose = closing
        central.cancelConnection(retiring.identifier)
        closeSafetyJob?.cancel()
        closeSafetyJob = scope.launch {
            delay(CLOSE_SAFETY_TIMEOUT_MS)
            closing.complete(Unit)
        }
    }

    internal fun failAndDisconnect(failure: BoardConnectFailure) {
        if (_state.value.failure == null) _state.update { it.copy(failure = failure) }
        disconnect()
    }

    private fun markReady(current: Link) {
        if (connection != ConnectionState.CONNECTING || link !== current || current.writeCharacteristic == null) return
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        _state.update { it.copy(connection = ConnectionState.CONNECTED) }
        resetIdleTimer()
        if (current.board.boardBrand == BoardBrand.QUANTUM) quantum.onReady(current)
    }

    internal fun markReadyFromSetup(current: Link) = markReady(current)

    internal fun stopConnectTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /** Remote drop or failed final attempt. */
    private fun finalizeRemoteDisconnect() {
        val wasQuantum = connectedBrand == BoardBrand.QUANTUM
        quantum.cancelOperations()
        if (wasQuantum) quantum.markStale()
        link = null
        idleJob?.cancel()
        _state.update {
            it.copy(connection = ConnectionState.DISCONNECTED, connectedBoard = null, quantumModel = null)
        }
    }

    fun disconnect() {
        val wasQuantum = connectedBrand == BoardBrand.QUANTUM
        connectJob?.cancel()
        connectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        idleJob?.cancel()
        idleJob = null
        quantum.cancelOperations()
        if (wasQuantum) quantum.markStale()
        failPendingWrite()
        val active = link
        link = null
        _state.update {
            it.copy(connection = ConnectionState.DISCONNECTED, connectedBoard = null, quantumModel = null)
        }
        if (active != null) retire(active)
    }

    private fun failPendingWrite() {
        val pending = pendingWrite
        pendingWrite = null
        pending?.complete(false)
    }

    // ── Writes ──────────────────────────────────────────────────────────

    internal suspend fun writeChunk(current: Link, chunk: ByteArray): BoardSendResult {
        val service = current.writeService ?: return BoardSendResult.NOT_CONNECTED
        val characteristic = current.writeCharacteristic ?: return BoardSendResult.NOT_CONNECTED
        val acknowledged = CompletableDeferred<Boolean>()
        pendingWrite = acknowledged
        central.write(current.identifier, service, characteristic, chunk, current.writeWithResponse)
        val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { acknowledged.await() }
        if (pendingWrite === acknowledged) pendingWrite = null
        if (ok == null) {
            // Write callbacks carry no operation token. Retire the link so a
            // late callback cannot acknowledge a later write.
            disconnect()
            return BoardSendResult.WRITE_TIMEOUT
        }
        return if (ok) BoardSendResult.OK else BoardSendResult.WRITE_FAILED
    }

    /** Each chunk waits for the previous write callback; the delay applies between chunks only. */
    internal suspend fun writeChunks(chunks: List<ByteArray>, interChunkDelayMs: Long = 0L): BoardSendResult {
        val current = link ?: return BoardSendResult.NOT_CONNECTED
        for ((i, chunk) in chunks.withIndex()) {
            val result = writeChunk(current, chunk)
            if (result != BoardSendResult.OK) return result
            if (interChunkDelayMs > 0L && i < chunks.lastIndex) delay(interChunkDelayMs)
        }
        return BoardSendResult.OK
    }

    internal suspend fun <T> sending(block: suspend () -> T): T {
        _state.update { it.copy(connection = ConnectionState.SENDING) }
        // Park any pending idle-disconnect so it cannot fire mid-send.
        parkIdleTimer()
        try {
            return block()
        } finally {
            if (connection == ConnectionState.SENDING) {
                _state.update { it.copy(connection = ConnectionState.CONNECTED) }
            }
            resetIdleTimer()
        }
    }

    /**
     * Light an Aurora-family climb. [roleColors] is resolved by role class, not
     * raw code: an authored climb carries Kilter-style codes (12-15) while an
     * Aurora board's colour map is keyed 1-4. Unmapped holds are skipped, as on
     * Android; the caller warns about a partial climb.
     */
    suspend fun sendClimb(
        holds: List<BoardHold>,
        placementToLed: Map<Int, Int>,
        roleColors: Map<Int, Int>? = null,
        expectedBrand: BoardBrand? = null,
    ): BoardSendResult = writeMutex.withLock {
        if (connection != ConnectionState.CONNECTED) return BoardSendResult.NOT_CONNECTED
        if (!boardScopedCommandAllowed(connectedBrand, expectedBrand)) return BoardSendResult.BOARD_MISMATCH
        // Quantum projections carry a route, identity and colour: sendQuantumClimb only.
        if (connectedBrand == BoardBrand.QUANTUM) return BoardSendResult.WRONG_BOARD_FAMILY
        sending {
            val chunks = if (roleColors != null) {
                val byClass = roleColors.entries.associate { HoldRole.roleClass(it.key) to it.value }
                encoder.encodeClimb(
                    holds.mapNotNull { hold ->
                        val led = placementToLed[hold.placementId] ?: return@mapNotNull null
                        led to (byClass[HoldRole.roleClass(hold.roleId)] ?: BoardPacketEncoder.roleToColor(hold.roleId))
                    },
                )
            } else {
                encoder.encodeClimbFromHolds(holds, placementToLed)
            }
            writeChunks(chunks)
        }
    }

    /** ASCII `l#…#` frame over the same Nordic UART characteristic, in 20-byte writes 100 ms apart. */
    suspend fun sendMoonBoardClimb(
        frames: String,
        variant: MoonBoardVariant,
        ledMode: MoonBoardLedMode = MoonBoardLedMode.BELOW,
    ): BoardSendResult = writeMutex.withLock {
        if (connection != ConnectionState.CONNECTED) return BoardSendResult.NOT_CONNECTED
        if (!moonBoardCommandAllowed(connectedBrand)) return BoardSendResult.WRONG_BOARD_FAMILY
        sending {
            val payload = MoonBoardFrameEncoder.encode(frames, variant, ledMode)
            val chunks = payload.toList().chunked(BoardPacketEncoder.BLE_MTU).map { it.toByteArray() }
            writeChunks(chunks, MOONBOARD_UART_INTER_CHUNK_DELAY_MS)
        }
    }

    /**
     * Generic single-projection clear; refused on Quantum, whose only clear
     * removes every user on the wall.
     *
     * Android quirk, reproduced on purpose: a MoonBoard link's encoder was built
     * from the advertised apiLevel 0, so this sends the Aurora v2 empty packet
     * (01 01 AF 02 50 03), not a MoonBoard `l##` frame. Whether a MoonBoard
     * controller acts on it is unverified; changing it here would make iOS
     * differ from the shipped Android behaviour.
     */
    suspend fun clearBoard(expectedBrand: BoardBrand? = null): BoardSendResult = writeMutex.withLock {
        if (connection != ConnectionState.CONNECTED) return BoardSendResult.NOT_CONNECTED
        if (!boardScopedCommandAllowed(connectedBrand, expectedBrand)) return BoardSendResult.BOARD_MISMATCH
        if (!genericBoardClearAllowed(connectedBrand)) return BoardSendResult.WRONG_BOARD_FAMILY
        sending { writeChunks(encoder.encodeClear()) }
    }

    // ── Central events ──────────────────────────────────────────────────

    private inner class Events : BleCentralListener {
        override fun onAdapterState(state: BleAdapterState) = post { handleAdapterState(state) }

        override fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) =
            post { handleAdvertisement(identifier, advertisedName, peripheralName, rssi) }

        override fun onConnected(identifier: String) = post { handleConnected(identifier) }
        override fun onDisconnected(identifier: String) = post { handleDisconnected(identifier) }

        override fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?) =
            post { handleServices(identifier, services) }

        override fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean) = post {
            val current = link
            if (current != null && current.identifier == identifier &&
                current.writeCharacteristic?.let { BoardBleUuids.sameUuid(it, characteristicUuid) } == true
            ) {
                pendingWrite?.complete(success)
            }
        }

        override fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?) =
            post { if (link?.identifier == identifier) quantum.onRead(characteristicUuid, value) }

        override fun onNotifyStateChanged(
            identifier: String,
            characteristicUuid: String,
            enabled: Boolean,
            success: Boolean,
        ) = post { if (link?.identifier == identifier) quantum.onNotifyState(characteristicUuid, enabled, success) }

        override fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray) =
            post { if (link?.identifier == identifier) quantum.onNotification(characteristicUuid, value) }

        private fun post(block: () -> Unit) {
            scope.launch { block() }
        }
    }

    private fun handleAdapterState(adapter: BleAdapterState) {
        _state.update { it.copy(adapter = adapter) }
        if (adapter == BleAdapterState.POWERED_ON) {
            if (scanWhenPoweredOn) startScan()
            return
        }
        if (adapter != BleAdapterState.UNKNOWN && adapter != BleAdapterState.RESETTING) scanWhenPoweredOn = false
        // CoreBluetooth invalidates scans and links without per-peripheral events.
        _state.update { it.copy(scanning = false) }
        if (connection != ConnectionState.DISCONNECTED) {
            connectJob?.cancel()
            connectionTimeoutJob?.cancel()
            failPendingWrite()
            _state.update { it.copy(failure = it.failure ?: BoardConnectFailure.BLUETOOTH_UNAVAILABLE) }
            finalizeRemoteDisconnect()
        }
        pendingClose?.complete(Unit)
    }

    private fun handleAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) {
        if (!_state.value.scanning) return
        // The platform caches the peripheral name per device; a board that
        // re-advertises under another name would keep its first one.
        val name = advertisedName ?: peripheralName ?: return
        val board = BoardBleNames.classify(name, identifier, rssi) ?: return
        boardMap[identifier] = board
        _state.update { it.copy(boards = boardMap.values.toList()) }
    }

    private fun handleConnected(identifier: String) {
        val current = link
        if (current == null || current.identifier != identifier || connection != ConnectionState.CONNECTING) {
            // Nobody owns this link; leaving it up would hide the board from every scan.
            if (current?.identifier != identifier) central.cancelConnection(identifier)
            return
        }
        // Not CONNECTED yet: an automatic send would race service discovery.
        // The attempt timeout keeps covering discovery.
        central.discoverServices(identifier)
    }

    private fun handleDisconnected(identifier: String) {
        val current = link
        if (current == null || current.identifier != identifier) {
            // The event of a link we retired ourselves.
            pendingClose?.complete(Unit)
            return
        }
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        quantum.cancelOperations()
        failPendingWrite()
        if (canRetryConnect()) {
            // The platform already closed this link: nothing to cancel or await.
            connectAttempt += 1
            val board = current.board
            link = null
            _state.update { it.copy(quantumModel = null) }
            connectJob = scope.launch {
                delay(CONNECT_RETRY_DELAY_MS)
                if (connection != ConnectionState.CONNECTING) return@launch
                startAttempt(board)
            }
        } else {
            if (connection == ConnectionState.CONNECTING && _state.value.failure == null) {
                _state.update { it.copy(failure = BoardConnectFailure.CONNECT_FAILED) }
            }
            finalizeRemoteDisconnect()
        }
    }

    private fun handleServices(identifier: String, services: List<BleServiceInfo>?) {
        val current = link ?: return
        if (current.identifier != identifier || connection != ConnectionState.CONNECTING || current.discoveryHandled) {
            return
        }
        current.discoveryHandled = true
        if (services == null) {
            if (canRetryConnect()) scheduleRetry() else failAndDisconnect(BoardConnectFailure.CONNECT_FAILED)
            return
        }
        current.services = services
        val isQuantum = current.board.boardBrand == BoardBrand.QUANTUM
        val service = if (isQuantum) {
            services.find(BoardBleUuids.QUANTUM_SERVICE) ?: services.find(BoardBleUuids.QUANTUM_SERVICE_OLD)
        } else {
            services.find(BoardBleUuids.DATA_TRANSFER_SERVICE)
        }
        val characteristic = service?.characteristics?.firstOrNull {
            BoardBleUuids.sameUuid(
                it.uuid,
                if (isQuantum) BoardBleUuids.QUANTUM_WRITE_CHAR else BoardBleUuids.DATA_TRANSFER_CHAR,
            )
        }
        if (service == null || characteristic == null) {
            val unsupportedMoonBoard = current.board.boardBrand == BoardBrand.MOONBOARD &&
                services.find(BoardBleUuids.REDBEAR_UART_SERVICE) != null
            // Retrying cannot change a service layout. A full disconnect, not a
            // state flip: a live link hides the board from every later scan.
            if (unsupportedMoonBoard) {
                _state.update { it.copy(failure = BoardConnectFailure.MOONBOARD_GENERATION_UNSUPPORTED) }
            }
            failAndDisconnect(BoardConnectFailure.CONNECT_FAILED)
            return
        }
        current.writeService = service.uuid
        current.writeCharacteristic = characteristic.uuid
        // Quantum frames are always written with response. Elsewhere this is
        // Android's default write type: BluetoothGattCharacteristic picks
        // NO_RESPONSE only when the characteristic does not declare WRITE.
        current.writeWithResponse = isQuantum || characteristic.canWrite || !characteristic.canWriteWithoutResponse
        if (isQuantum) {
            current.quantumService = service.uuid
            quantum.beginSetup(current)
        } else {
            markReady(current)
        }
    }

    private fun List<BleServiceInfo>.find(uuid: String): BleServiceInfo? =
        firstOrNull { BoardBleUuids.sameUuid(it.uuid, uuid) }
}

internal fun boardScopedCommandAllowed(connectedBrand: BoardBrand?, expectedBrand: BoardBrand?): Boolean =
    expectedBrand == null || connectedBrand == expectedBrand

internal fun genericBoardClearAllowed(connectedBrand: BoardBrand?): Boolean = connectedBrand != BoardBrand.QUANTUM

internal fun moonBoardCommandAllowed(connectedBrand: BoardBrand?): Boolean = connectedBrand == BoardBrand.MOONBOARD
