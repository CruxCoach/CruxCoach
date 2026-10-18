package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BoardConnectFailure
import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.DiscoveredBoard

class BleBoardRowUi(
    val identifier: String,
    val name: String,
    val brandTitle: String,
    val serial: String,
    val rssi: Int,
    val lastUsed: Boolean,
    /** The board family's wire value, e.g. `kilter`. */
    val brandWire: String,
)

class BleScreenState(
    /** unknown | resetting | unsupported | unauthorized | poweredOff | poweredOn */
    val adapter: String,
    val scanning: Boolean,
    val boards: List<BleBoardRowUi>,
    val remembered: List<BleBoardRowUi>,
    /** disconnected | connecting | connected | sending */
    val connection: String,
    val connectedName: String,
    val connected: Boolean,
    val sending: Boolean,
    /** none | ok | notConnected | boardMismatch | sendFailed */
    val sendResult: String,
    /** none | connectFailed | moonBoardGenerationUnsupported */
    val connectFailure: String,
    /**
     * The board screens' status chip: hidden | scanning | connecting | connected | sending |
     * failed | lost | off | unauthorized | unsupported. "lost" is a link that was up in this
     * run and dropped without the user disconnecting (out of range, board off, idle timeout).
     */
    val status: String,
    /** The board the chip names: the connected or connecting one, or the one that was lost. */
    val statusBoardName: String,
    /** A remembered board can be reconnected without a scan (Android's "Reconnect"). */
    val canReconnect: Boolean,
)

/** Board connection screen. The phone is the BLE central; nothing here has run on hardware. */
class BleScreenModel(private val presenter: BoardConnectionPresenter) {

    // The chip's memory, kept here because every board screen shares this one model. All of it
    // is touched on the main thread only (the presenter publishes there, Swift calls there).
    /** The last board that reached CONNECTED in this run. */
    private var lastLink: DiscoveredBoard? = null
    /** The user ended (or dismissed) the last link: its drop is not reported as "lost". */
    private var released = true
    /** A status the user dismissed; it stays hidden until the status changes. */
    private var dismissedStatus = ""

    val currentState: BleScreenState get() = map(presenter.state.value)

    /** Watchers to re-notify when only the chip's memory changed (the link state did not). */
    private val listeners = mutableListOf<(BleScreenState) -> Unit>()

    fun watch(onState: (BleScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        listeners += onState
        return Subscription {
            watch.close()
            listeners.remove(onState)
        }
    }

    private fun republish() {
        val state = map(presenter.state.value)
        listeners.toList().forEach { it(state) }
    }

    private fun map(state: BoardConnectionUiState) = BleScreenState(
        adapter = UiCodes.adapter(state.adapter),
        scanning = state.scanning,
        boards = state.boards.map {
            BleBoardRowUi(
                identifier = it.identifier,
                name = it.displayName,
                brandTitle = UiCodes.brandTitle(it.boardBrand),
                serial = it.serial,
                rssi = it.rssi,
                lastUsed = it.identifier in state.lastUsedIdentifiers,
                brandWire = it.boardBrand.wireValue,
            )
        },
        remembered = state.remembered.map {
            BleBoardRowUi(
                identifier = it.identifier,
                name = it.displayName,
                brandTitle = UiCodes.brandTitle(com.cruxcoach.domain.board.BoardBrand.fromWire(it.brand)),
                serial = it.serial,
                rssi = 0,
                lastUsed = true,
                brandWire = it.brand,
            )
        },
        connection = UiCodes.connection(state.connection),
        connectedName = state.connectedBoard?.displayName ?: "",
        connected = state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.SENDING,
        sending = state.sending,
        sendResult = UiCodes.sendResult(state.lastSendResult),
        connectFailure = UiCodes.connectFailure(state.connectFailure),
        status = status(state),
        statusBoardName = statusBoard(state)?.displayName ?: "",
        canReconnect = !linkBusy(state) && reconnectBrand(state) != null,
    )

    /** Idempotent: every watcher maps the same emission, so remembering must not depend on order. */
    private fun status(state: BoardConnectionUiState): String {
        if (linkUp(state)) {
            state.connectedBoard?.let { lastLink = it }
            released = false
        }
        val raw = when {
            state.connection == ConnectionState.SENDING -> "sending"
            state.connection == ConnectionState.CONNECTED -> "connected"
            state.connection == ConnectionState.CONNECTING -> "connecting"
            state.adapter == BleAdapterState.UNAUTHORIZED -> "unauthorized"
            state.adapter == BleAdapterState.POWERED_OFF -> "off"
            state.adapter == BleAdapterState.UNSUPPORTED -> "unsupported"
            state.connectFailure != null && state.connectFailure != BoardConnectFailure.BLUETOOTH_UNAVAILABLE -> "failed"
            lastLink != null && !released -> "lost"
            state.scanning -> "scanning"
            else -> "hidden"
        }
        if (raw != dismissedStatus) dismissedStatus = ""
        return if (raw == dismissedStatus) "hidden" else raw
    }

    private fun statusBoard(state: BoardConnectionUiState): DiscoveredBoard? =
        if (linkBusy(state)) state.connectedBoard else lastLink

    private fun linkUp(state: BoardConnectionUiState) =
        state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.SENDING

    private fun linkBusy(state: BoardConnectionUiState) =
        linkUp(state) || state.connection == ConnectionState.CONNECTING

    /** The lost board's family when it is remembered, else the first remembered board. */
    private fun reconnectBrand(state: BoardConnectionUiState): String? {
        val lost = lastLink?.boardBrand?.wireValue
        return state.remembered.firstOrNull { it.brand == lost }?.brand ?: state.remembered.firstOrNull()?.brand
    }

    fun activate() = presenter.activate()
    fun startScan() = presenter.startScan()
    fun stopScan() = presenter.stopScan()
    fun connect(identifier: String) = presenter.connect(identifier)
    fun disconnect() {
        released = true
        presenter.disconnect()
    }
    fun clearBoard() = presenter.clearBoard()

    /** The chip's "Reconnect": the lost (or last remembered) board, directly, one attempt. */
    fun reconnect() {
        val state = presenter.state.value
        if (linkBusy(state)) return
        val brand = reconnectBrand(state) ?: return
        presenter.activate()
        presenter.reconnectRemembered(brand)
    }

    /** Hides the chip's current status until it changes; a lost link is let go for good. */
    fun dismissStatus() {
        val current = status(presenter.state.value)
        if (current == "lost") released = true else if (current != "hidden") dismissedStatus = current
        republish()
    }
}
