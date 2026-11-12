package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class PaintProjectionException(val messageRes: Int) : Exception()

/** All writes use the existing transport mutex, controller encoder and board fences. */
class PaintProjector @Inject constructor(
    private val ble: BoardBleConnection,
    private val layers: BoardLayerManager,
    private val preferences: UserPreferences,
    private val sessions: SessionQueueManager,
    private val relay: CruxRelayManager,
) {
    /** The last picture went out dimmer than painted, to stay within the box's power. */
    var lastSendDimmed: Boolean = false
        private set

    /**
     * Why PaintBox must leave the board alone right now, or null. A shared session
     * owns what the wall shows, and board sharing forwards other phones' writes,
     * which a PaintBox frame could land in the middle of.
     */
    fun boardBusy(): Int? = busyReason(sessions.state.value.let { it.isActive || it.isConnecting }, relay.state.value.enabled)

    /** [boardBusy] as it changes. */
    val busy: Flow<Int?> = combine(sessions.state, relay.state) { session, relay ->
        busyReason(session.isActive || session.isConnecting, relay.enabled)
    }.distinctUntilChanged()

    private fun busyReason(session: Boolean, relay: Boolean): Int? = when {
        session -> R.string.paintbox_session_active
        relay -> R.string.paintbox_relay_active
        else -> null
    }

    /** Every frame asks again: a session or board sharing can start while a picture waits. */
    private fun requireBoardFree() {
        boardBusy()?.let { throw PaintProjectionException(it) }
    }

    suspend fun send(document: PaintDocument, ledMap: Map<Int, Int>, current: () -> Boolean): Boolean {
        // LED lab: everything this send writes is counted for the report, also when it is refused or fails.
        val recording = LedLab.begin()
        try {
            return sendPicture(document, ledMap, current)
        } finally {
            val brand = BoardBrand.fromWire(document.board.brand)
            // What a Quantum controller refused the send with tells at the wall why nothing lit.
            val answer = if (brand == BoardBrand.QUANTUM) runCatching { ble.quantumControllerState.value.lastFailure?.name }.getOrNull() else null
            LedLab.end(recording, brand, answer)
        }
    }

    private suspend fun sendPicture(document: PaintDocument, ledMap: Map<Int, Int>, current: () -> Boolean): Boolean {
        lastSendDimmed = false
        val lab = LedLab.switches.value
        requireBoardFree()
        val brand = BoardBrand.fromWire(document.board.brand)
        if (!current() || ble.connectedBoardBrand.value != brand) throw PaintProjectionException(R.string.paintbox_connect_error)
        document.validate()
        if (brand == BoardBrand.MOONBOARD) {
            val variant = MoonBoardVariant.fromLayoutId(document.board.layoutId.toLong())
                ?: throw PaintProjectionException(R.string.paintbox_missing_board)
            val mode = preferences.moonBoardLedMode.first()
            requireBoardFree()
            // LED lab: the pause between the 20-byte writes is a switch (the release build's by default).
            return current() && ble.sendMoonBoardClimb(PaintPalette.moonFrames(document.colors), variant, mode, lab.moonGapMs)
        }
        if (document.colors.keys.any { it !in ledMap }) throw PaintProjectionException(R.string.paintbox_missing_led)
        if (brand.usesAuroraProtocol) {
            val apiLevel = ble.connectedBoard?.apiLevel ?: throw PaintProjectionException(R.string.paintbox_connect_error)
            val ledsPerHold = BoardPacketEncoder.ledsPerHoldFor(brand)
            val painted = document.colors.map { (id, color) -> ledMap.getValue(id) to color }
            val leds: List<Pair<Int, Int>>
            val dimmed: Boolean
            if (apiLevel >= 3) {
                // No budget in the protocol: stay within about what the maker's LED test draws (see fitV3PictureBudget).
                // Layouts that share a size map their own placements to the same LEDs.
                val fitted = BoardPacketEncoder.fitV3PictureBudget(painted.map { it.second }, ledMap.values.toSet().size)
                leds = painted.zip(fitted) { (position, _), color -> position to color }
                dimmed = leds != painted
            } else {
                // An API-2 box takes one packet, like hold cleaning (see cleaningHoldLimit).
                // LED lab: with the switch on, a larger picture goes out in several packets (BoardPacketEncoder
                // splits it into first, middle and last). The power limit below still holds.
                if (!lab.api2SeveralPackets && painted.size > BoardPacketEncoder.cleaningHoldLimit(apiLevel, ledsPerHold)) {
                    throw PaintProjectionException(R.string.paintbox_api2_limit)
                }
                val encoder = BoardPacketEncoder(apiLevel, ledsPerHold, BoardPacketEncoder.V2_PICTURE_MAX_POWER)
                if (encoder.litNothing(painted)) throw PaintProjectionException(R.string.paintbox_too_bright)
                leds = painted
                dimmed = encoder.dims(painted)
            }
            requireBoardFree()
            val sent = current() && ble.sendRawLeds(leds, expectedBrand = brand, maxPowerW = BoardPacketEncoder.V2_PICTURE_MAX_POWER)
            lastSendDimmed = sent && dimmed
            return sent
        }
        if (brand != BoardBrand.QUANTUM) throw PaintProjectionException(R.string.paintbox_missing_board)
        val connected = ble.connectedBoard ?: throw PaintProjectionException(R.string.paintbox_connect_error)
        val fence = BoardLayerBoardIdentity(PhysicalBoardIdentity.resolve(connected).value, document.board.sizeId.toLong())
        val groups = document.colors.entries.groupBy({ it.value }, { it.key })
        // LED lab: with the switch on, a color of more than 92 LEDs goes out in several messages
        // (QuantumBoardPacketEncoder.activate splits it the way the eWalls app splits long routes).
        if (!lab.quantumSeveralMessages && groups.values.any { it.size > PAINT_QUANTUM_MOST }) {
            throw PaintProjectionException(R.string.paintbox_quantum_limit)
        }
        if (!ble.refreshQuantumState() || !current()) return false
        // Unknown foreign geometry cannot prove non-overlap. Fail before making any change.
        if (ble.quantumControllerState.value.players.any { !layers.ownsPaintIdentity(it.userId) }) {
            throw PaintProjectionException(R.string.paintbox_quantum_occupied)
        }
        val existing = ble.quantumControllerState.value.players.toList()
        // Remove old PaintBox geometry first, so moving a diode between colors never overlaps.
        for (player in existing) {
            requireBoardFree()
            if (!current()) return false
            if (!ble.removeQuantumLayer(player.userId, player.routeId, fence)) return false
        }
        for ((color, ids) in groups.toSortedMap()) {
            requireBoardFree()
            if (!current()) return false
            if (ble.quantumControllerState.value.players.any { !layers.ownsPaintIdentity(it.userId) }) return false
            val user = layers.paintIdentity(PaintPalette.quantum.indexOf(color))
            if (!ble.sendClimb(
                    holds = ids.map { BoardHold(it, HoldRole.HAND) },
                    placementToLed = ledMap,
                    routeId = UUID.randomUUID().toString(),
                    quantumUserId = user,
                    quantumColor = PaintPalette.rgb(color),
                    expectedQuantumPlayers = ble.quantumControllerState.value.players.toList(),
                    expectedQuantumBoard = fence,
                    expectedBrand = brand,
                )) return false
        }
        return true
    }
}
