package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.BoardStateManager
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
    private val advertiser: ClimbBleAdvertiser,
    private val boardState: BoardStateManager,
) {
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

    /** Whether the connected board can dim [document] to its power limit at all: only its own Aurora board does. */
    fun mayDim(document: PaintDocument): Boolean {
        val brand = BoardBrand.fromWire(document.board.brand)
        return brand.usesAuroraProtocol && ble.connectedBoardBrand.value == brand
    }

    /**
     * Whether the connected board would show [document] dimmer than painted, to stay within
     * its power limit. False without a matching Aurora board ([mayDim]), or for a picture it refuses.
     */
    fun dims(document: PaintDocument, ledMap: Map<Int, Int>): Boolean {
        if (!mayDim(document)) return false
        val brand = BoardBrand.fromWire(document.board.brand)
        val apiLevel = ble.connectedBoard?.apiLevel ?: return false
        if (document.colors.keys.any { it !in ledMap }) return false
        return try { auroraFrame(document, ledMap, brand, apiLevel).dimmed }
        catch (_: PaintProjectionException) { false }
    }

    /** The LEDs an Aurora box gets for [document], and whether they are dimmed. Refuses what the box can't show. */
    private fun auroraFrame(document: PaintDocument, ledMap: Map<Int, Int>, brand: BoardBrand, apiLevel: Int): AuroraFrame {
        val ledsPerHold = BoardPacketEncoder.ledsPerHoldFor(brand)
        val painted = document.colors.map { (id, color) -> ledMap.getValue(id) to color }
        if (apiLevel >= 3) {
            // No budget in the protocol: stay within about what the maker's LED test draws (see fitV3PictureBudget).
            // Layouts that share a size map their own placements to the same LEDs.
            val fitted = BoardPacketEncoder.fitV3PictureBudget(painted.map { it.second }, ledMap.values.toSet().size)
            val leds = painted.zip(fitted) { (position, _), color -> position to color }
            return AuroraFrame(leds, dimmed = leds != painted)
        }
        // An API-2 box takes one packet, like hold cleaning (see cleaningHoldLimit).
        if (painted.size > BoardPacketEncoder.cleaningHoldLimit(apiLevel, ledsPerHold)) {
            throw PaintProjectionException(R.string.paintbox_api2_limit)
        }
        val encoder = BoardPacketEncoder(apiLevel, ledsPerHold, BoardPacketEncoder.V2_PICTURE_MAX_POWER)
        if (encoder.litNothing(painted)) throw PaintProjectionException(R.string.paintbox_too_bright)
        return AuroraFrame(painted, dimmed = encoder.dims(painted))
    }
    private class AuroraFrame(val leds: List<Pair<Int, Int>>, val dimmed: Boolean)

    suspend fun send(document: PaintDocument, ledMap: Map<Int, Int>, current: () -> Boolean): Boolean =
        write(document, ledMap, current).also { if (it) forgetReplacedClimb() }

    /**
     * The picture took the place of the climb on the board, so nearby sharing must stop
     * calling that climb the one on the board, here and to the phones around.
     */
    private suspend fun forgetReplacedClimb() {
        if (advertiser.hasActiveClimb()) {
            // With sharing off nothing went out, and clearClimb could start advertising the link.
            if (preferences.nearbyClimbSharing.first()) advertiser.clearClimb() else advertiser.clearActiveClimb()
        }
        // Live sends many frames: the saved climb is cleared once.
        if (boardState.lastClimb.value != null) boardState.clearLastClimb()
    }

    private suspend fun write(document: PaintDocument, ledMap: Map<Int, Int>, current: () -> Boolean): Boolean {
        requireBoardFree()
        val brand = BoardBrand.fromWire(document.board.brand)
        if (!current() || ble.connectedBoardBrand.value != brand) throw PaintProjectionException(R.string.paintbox_connect_error)
        document.validate()
        if (brand == BoardBrand.MOONBOARD) {
            val variant = MoonBoardVariant.fromLayoutId(document.board.layoutId.toLong())
                ?: throw PaintProjectionException(R.string.paintbox_missing_board)
            val mode = preferences.moonBoardLedMode.first()
            val frames = PaintPalette.moonFrames(document.colors)
            // The box takes a frame of so many LEDs; with LEDs above and below, a hold needs two.
            if (!MoonBoardFrameEncoder.fitsController(MoonBoardFrameEncoder.encode(frames, variant, mode))) {
                throw PaintProjectionException(
                    if (mode == MoonBoardLedMode.BOTH) R.string.paintbox_moon_limit_both else R.string.paintbox_moon_limit)
            }
            requireBoardFree()
            return current() && ble.sendMoonBoardClimb(frames, variant, mode)
        }
        if (document.colors.keys.any { it !in ledMap }) throw PaintProjectionException(R.string.paintbox_missing_led)
        if (brand.usesAuroraProtocol) {
            val apiLevel = ble.connectedBoard?.apiLevel ?: throw PaintProjectionException(R.string.paintbox_connect_error)
            val frame = auroraFrame(document, ledMap, brand, apiLevel)
            requireBoardFree()
            return current() && ble.sendRawLeds(frame.leds, expectedBrand = brand, maxPowerW = BoardPacketEncoder.V2_PICTURE_MAX_POWER)
        }
        if (brand != BoardBrand.QUANTUM) throw PaintProjectionException(R.string.paintbox_missing_board)
        val connected = ble.connectedBoard ?: throw PaintProjectionException(R.string.paintbox_connect_error)
        val fence = BoardLayerBoardIdentity(PhysicalBoardIdentity.resolve(connected).value, document.board.sizeId.toLong())
        val groups = document.colors.entries.groupBy({ it.value }, { it.key })
        if (groups.values.any { it.size > PAINT_QUANTUM_MOST }) throw PaintProjectionException(R.string.paintbox_quantum_limit)
        if (!ble.refreshQuantumState() || !current()) return false
        // Unknown foreign geometry cannot prove non-overlap. Fail before making any change.
        // This phone's own climbs count too, also one the controller accepted but does not list.
        if (ble.quantumControllerState.value.players.any { !layers.ownsPaintIdentity(it.userId) } ||
            (layers.isBoundTo(fence) && layers.state.value.layers.any { it.confirmedRouteUuid != null })
        ) {
            throw PaintProjectionException(R.string.paintbox_quantum_occupied)
        }
        requireBoardFree()
        if (!current()) return false
        // Remove the old picture first, so moving a diode between colors never overlaps.
        if (ble.removePaintBoxLayers(layers, fence) == null) return false
        for ((color, ids) in groups.toSortedMap()) {
            requireBoardFree()
            if (!current()) return false
            if (ble.quantumControllerState.value.players.any { !layers.ownsPaintIdentity(it.userId) }) return false
            val user = layers.paintIdentity(PaintPalette.quantum.indexOf(color))
            // Before the write: a colour the board took without an answer must
            // still be turned off later, and turning off an unlit one is harmless.
            layers.markPaintLit(user)
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
