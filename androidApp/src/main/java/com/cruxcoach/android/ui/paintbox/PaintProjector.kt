package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.*
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class PaintProjectionException(val messageRes: Int) : Exception()

/** All writes use the existing transport mutex, controller encoder and board fences. */
class PaintProjector @Inject constructor(
    private val ble: BoardBleConnection,
    private val layers: BoardLayerManager,
    private val preferences: UserPreferences,
) {
    suspend fun send(document: PaintDocument, ledMap: Map<Int, Int>, current: () -> Boolean): Boolean {
        val brand = BoardBrand.fromWire(document.board.brand)
        if (!current() || ble.connectedBoardBrand.value != brand) throw PaintProjectionException(R.string.paintbox_connect_error)
        document.validate()
        if (brand == BoardBrand.MOONBOARD) {
            val variant = MoonBoardVariant.fromLayoutId(document.board.layoutId.toLong())
                ?: throw PaintProjectionException(R.string.paintbox_missing_board)
            val mode = preferences.moonBoardLedMode.first()
            return current() && ble.sendMoonBoardClimb(PaintPalette.moonFrames(document.colors), variant, mode)
        }
        if (document.colors.keys.any { it !in ledMap }) throw PaintProjectionException(R.string.paintbox_missing_led)
        if (brand.usesAuroraProtocol) {
            return current() && ble.sendRawLeds(document.colors.map { (id, color) -> ledMap.getValue(id) to color }, expectedBrand = brand)
        }
        if (brand != BoardBrand.QUANTUM) throw PaintProjectionException(R.string.paintbox_missing_board)
        val connected = ble.connectedBoard ?: throw PaintProjectionException(R.string.paintbox_connect_error)
        val fence = BoardLayerBoardIdentity(PhysicalBoardIdentity.resolve(connected).value, document.board.sizeId.toLong())
        val groups = document.colors.entries.groupBy({ it.value }, { it.key })
        if (groups.values.any { it.size > 92 }) throw PaintProjectionException(R.string.paintbox_quantum_limit)
        if (!ble.refreshQuantumState() || !current()) return false
        // Unknown foreign geometry cannot prove non-overlap. Fail before making any change.
        if (ble.quantumControllerState.value.players.any { !layers.ownsPaintIdentity(it.userId) }) {
            throw PaintProjectionException(R.string.paintbox_quantum_occupied)
        }
        val existing = ble.quantumControllerState.value.players.toList()
        // Remove old PaintBox geometry first, so moving a diode between colors never overlaps.
        for (player in existing) {
            if (!current()) return false
            if (!ble.removeQuantumLayer(player.userId, player.routeId, fence)) return false
        }
        for ((color, ids) in groups.toSortedMap()) {
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
