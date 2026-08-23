package com.cruxcoach.android.ble

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The playlist's write, when the wall can hold four climbs at once.
 *
 * Everywhere else one board shows one climb, so "project this occurrence"
 * needs no address beyond the climb itself. A Quantum controller keeps up to
 * four independent players, each with a route, a user identity and a colour,
 * and it refuses a second route for a user identity that still holds one. So
 * the same request needs a lane, and the lane needs an identity that survives
 * a disconnect — otherwise the app can put light on a wall it can no longer
 * recognise or take down.
 *
 * That is the whole job here: turn "this occurrence, that lane" into the
 * identity/colour pair the controller understands, keep [BoardLayerManager] in
 * step so the rack and the list agree about what is lit, and refuse rather
 * than guess.
 *
 * Two refusals are deliberate and not failures of nerve:
 *
 *  - a lane whose holds would collide with another lit layer is not sent. The
 *    controller cannot give one diode two colours, and finding that out from a
 *    rejected write loses the reason.
 *  - a rack staged for a different board is not sent. Between a tap and a
 *    write somebody can walk to another wall.
 */
@Singleton
class QuantumLaneProjector @Inject constructor(
    private val bleConnection: BoardBleConnection,
    private val boardLayerManager: BoardLayerManager,
    private val lanePlanner: QuantumLanePlanner,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) {

    /**
     * Whether this write belongs to the layer path at all.
     *
     * Gated on the *connected* board rather than the preference: switching the
     * active board in settings never disconnects, and a Kilter board still on
     * the link must keep its byte-identical single-projection transport.
     */
    fun handles(climb: ClimbWithStats): Boolean = QuantumLaneWritePolicy.handles(
        climbBrand = climb.brand,
        connectedBrand = bleConnection.connectedBoardBrand.value,
    )

    /**
     * Put one occurrence on the wall in its lane.
     *
     * Returns false for every refusal, including the ones that never reach
     * BLE. The caller is the canonical projection transaction, which commits
     * its event only on a true — so a refusal here leaves the group's current
     * exactly where it was rather than moving it to a climb the wall never
     * showed.
     */
    suspend fun write(climb: ClimbWithStats, angle: Int, entryId: String?): Boolean {
        // The rack empties itself when the board changes; the plan has to
        // follow the same rule, and here is the moment it matters. A screen
        // that happened not to be open must not leave a lane preference from
        // another wall standing in front of a write.
        lanePlanner.syncBoard()
        val lane = laneFor(entryId)
        val holds = BoardClimbParser.parseFrames(climb.frames)
        if (holds.isEmpty()) return false
        val productSizeId = userPreferences.boardProductSizeId.first()
        val ledMap = boardRepository.getPlacementLedMap(productSizeId, BoardBrand.QUANTUM.wireValue)
        if (ledMap.isEmpty() || holds.none { it.placementId in ledMap }) return false

        // The rack is a diode plan for one controller and one model. Re-check
        // it here because the tap happened somewhere else and some time ago.
        val identity = boardLayerManager.state.value.board
        val descriptor = bleConnection.connectedBoardDescriptor.value
        if (identity != null && descriptor != null) {
            val physical = runCatching {
                com.cruxcoach.android.boardcell.PhysicalBoardIdentity.resolve(descriptor)
            }.getOrNull()
            if (physical != null &&
                !boardLayerManager.isBoundTo(
                    BoardLayerBoardIdentity(physical.value, productSizeId.toLong()),
                )
            ) {
                Log.w(TAG, "lane write refused: rack staged for another board")
                return false
            }
        }

        val rackState = boardLayerManager.state.value
        val candidate = holds.mapTo(HashSet()) { it.placementId }
        // Every other lit layer, this one excluded: replacing what lane 2
        // shows cannot conflict with what lane 2 shows.
        val others = rackState.layers.filter { it.slot != lane && it.confirmedRouteUuid != null }
        val conflicts = others.count { other ->
            other.holds.any { it.placementId in candidate }
        }
        if (conflicts > 0) {
            Log.w(TAG, "lane write refused: $conflicts lane(s) share a hold")
            return false
        }
        val takenColors = others.mapTo(mutableSetOf()) { it.confirmedColor ?: it.color } +
            rackState.externalLayers.map { it.color }
        val existing = rackState.layers.firstOrNull { it.slot == lane }
        val color = existing?.color?.takeIf { it !in takenColors }
            ?: BoardLayerManager.LAYER_COLORS.firstOrNull { it !in takenColors }
            ?: run {
                Log.w(TAG, "lane write refused: no free protocol colour")
                return false
            }
        if (existing?.confirmedRouteUuid == null &&
            rackState.occupiedCount >= BoardBrand.QUANTUM.maxSimultaneousClimbs
        ) {
            Log.w(TAG, "lane write refused: controller full")
            return false
        }

        val routeUuid = boardRepository.getQuantumExternalRouteUuid(climb.uuid) ?: climb.uuid
        val layer = BoardClimbLayer(
            slot = lane,
            climbUuid = climb.uuid,
            routeUuid = routeUuid,
            climbName = climb.name,
            angle = angle,
            userUuid = boardLayerManager.identityForSlot(lane),
            color = color,
            holds = holds,
            status = BoardLayerStatus.PREVIEW,
        )
        boardLayerManager.assignPreview(layer)
        boardLayerManager.beginProjection(lane)
        lanePlanner.noteSending(lane, entryId)
        val written = runCatching {
            bleConnection.sendClimb(
                holds = holds,
                placementToLed = ledMap,
                // Quantum takes one colour per player, not one per hold role.
                roleColors = emptyMap(),
                routeId = routeUuid,
                quantumUserId = layer.userUuid,
                quantumColor = color,
            )
        }.getOrDefault(false)
        // A request is not a projection. Only the controller readback inside
        // sendClimb turns this into CONFIRMED.
        if (written) boardLayerManager.confirmProjection(lane)
        else boardLayerManager.failProjection(lane)
        lanePlanner.noteSent(lane, entryId, written)
        return written
    }

    /**
     * Which lane this occurrence goes to.
     *
     * Three answers in order. An explicit assignment is a decision and wins.
     * Failing that, an occurrence already written to a lane goes back to the
     * same one — pressing the lamp twice is a resend, not a request to move
     * somebody's climb across the wall. Otherwise the canonical lane, which is
     * not a guess but a stable address: the shared list still has exactly one
     * current, and everybody needs to find it in the same place.
     */
    private fun laneFor(entryId: String?): Int {
        if (entryId == null) return CANONICAL_LANE
        val plan = lanePlanner.state.value
        val lane = plan.rack.laneFor(entryId)
            ?: plan.committed.entries.firstOrNull { it.value == entryId }?.key
            ?: CANONICAL_LANE
        return lane.coerceIn(0, BoardLayerManager.MAX_LAYER_IDENTITIES - 1)
    }

    private companion object {
        const val TAG = "QuantumLaneProjector"

        /** Where the group's canonical current lives when nobody said otherwise. */
        const val CANONICAL_LANE = 0
    }
}

/**
 * The one gate that keeps four working transports out of this file.
 *
 * Both halves are required and neither is the user's board preference.
 * Switching the active board in settings does not disconnect anything, so a
 * Kilter board still on the link would otherwise receive a Quantum layer
 * packet; and a Quantum controller cannot be handed a MoonBoard frame. Pulled
 * out as a plain function because "does a Kilter climb ever reach the layer
 * sender" is exactly the regression worth a test that needs no radio.
 */
object QuantumLaneWritePolicy {
    fun handles(climbBrand: BoardBrand, connectedBrand: BoardBrand?): Boolean =
        climbBrand == BoardBrand.QUANTUM && connectedBrand == BoardBrand.QUANTUM
}
