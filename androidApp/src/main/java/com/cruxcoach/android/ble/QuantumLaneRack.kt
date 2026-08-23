package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.QuantumLaneOccupancy
import com.cruxcoach.domain.board.QuantumLaneSource

/**
 * The rack the compatibility rules see, built from the rack the controller
 * and this device actually have.
 *
 * One translation, in one place, because the three inputs disagree in useful
 * ways and flattening them early is how a UI starts lying:
 *
 *  - [BoardLayerState.layers] is this installation's own lanes, with a status
 *    that says whether each is planned, in flight or physically confirmed;
 *  - [BoardLayerState.externalLayers] is everybody else's players. They light
 *    holds and they take a place on the controller, but they are not lanes
 *    this device can write to;
 *  - the local lane plan says which occurrence each lane was written for.
 *
 * The one rule worth stating: an empty hold list is translated to `null`, not
 * to an empty set. A layer reconstructed from a controller snapshot after a
 * reconnect genuinely has no holds attached — the controller reports a route
 * id, not a diode list — and "this layer lights nothing" would make an
 * overlapping send look safe.
 */
object QuantumLaneRackAdapter {

    /**
     * @param hydrated placement sets recovered for route ids whose holds this
     *   device did not already have. Absent means unresolvable, which stays
     *   unresolvable: never substitute an empty set.
     */
    fun occupancies(
        layerState: BoardLayerState,
        maxLanes: Int,
        plan: QuantumLanePlan = QuantumLanePlan(),
        hydrated: Map<String, Set<Int>> = emptyMap(),
    ): List<QuantumLaneOccupancy> {
        val own = layerState.layers.filter { it.slot in 0 until maxLanes }.map { layer ->
            val placements = layer.holds
                .takeIf { it.isNotEmpty() }
                ?.mapTo(HashSet()) { it.placementId }
                ?: hydrated[layer.routeUuid.lowercase()]
            QuantumLaneOccupancy(
                lane = layer.slot,
                source = when (layer.status) {
                    BoardLayerStatus.PREVIEW -> QuantumLaneSource.PREVIEW
                    BoardLayerStatus.SENDING -> QuantumLaneSource.SENDING
                    BoardLayerStatus.CONFIRMED -> QuantumLaneSource.CONFIRMED
                    // A refused write left the previous layer where it was.
                    // Its confirmed route is the physical truth; without one
                    // the lane holds a plan that did not land.
                    BoardLayerStatus.FAILED ->
                        if (layer.confirmedRouteUuid != null) QuantumLaneSource.CONFIRMED
                        else QuantumLaneSource.PREVIEW
                },
                routeKey = layer.confirmedRouteUuid ?: layer.routeUuid,
                placements = placements,
                color = layer.confirmedColor ?: layer.color,
                entryId = plan.sendingEntryId?.takeIf { plan.sendingLane == layer.slot }
                    ?: plan.entryForLane(layer.slot)
                    ?: plan.rack.entryInLane(layer.slot),
            )
        }
        // Negative ids: in every comparison, in no target list. See
        // QuantumLaneOccupancy.lane for why that is one collection and not two.
        val foreign = layerState.externalLayers.mapIndexed { index, external ->
            QuantumLaneOccupancy(
                lane = -(index + 1),
                source = QuantumLaneSource.FOREIGN,
                routeKey = external.routeUuid,
                placements = hydrated[external.routeUuid.lowercase()],
                color = external.color,
            )
        }
        return own.sortedBy { it.lane } + foreign
    }

    /** Route ids on the rack whose holds this device does not already have. */
    fun unresolvedRoutes(layerState: BoardLayerState, maxLanes: Int): Set<String> {
        val own = layerState.layers
            .filter { it.slot in 0 until maxLanes && it.holds.isEmpty() }
            .map { (it.confirmedRouteUuid ?: it.routeUuid).lowercase() }
        val foreign = layerState.externalLayers.map { it.routeUuid.lowercase() }
        return (own + foreign).filterTo(HashSet()) { it.isNotBlank() }
    }
}
