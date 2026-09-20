package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState

class BleBoardRowUi(
    val identifier: String,
    val name: String,
    val brandTitle: String,
    val serial: String,
    val rssi: Int,
    val lastUsed: Boolean,
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
)

/** Board connection screen. The phone is the BLE central; nothing here has run on hardware. */
class BleScreenModel(private val presenter: BoardConnectionPresenter) {

    val currentState: BleScreenState get() = map(presenter.state.value)

    fun watch(onState: (BleScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.close() }
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
            )
        },
        connection = UiCodes.connection(state.connection),
        connectedName = state.connectedBoard?.displayName ?: "",
        connected = state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.SENDING,
        sending = state.sending,
        sendResult = UiCodes.sendResult(state.lastSendResult),
        connectFailure = UiCodes.connectFailure(state.connectFailure),
    )

    fun activate() = presenter.activate()
    fun startScan() = presenter.startScan()
    fun stopScan() = presenter.stopScan()
    fun connect(identifier: String) = presenter.connect(identifier)
    fun disconnect() = presenter.disconnect()
    fun clearBoard() = presenter.clearBoard()
}
