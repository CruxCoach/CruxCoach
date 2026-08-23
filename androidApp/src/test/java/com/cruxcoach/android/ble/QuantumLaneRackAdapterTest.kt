package com.cruxcoach.android.ble

import com.cruxcoach.android.boardcell.BoardQuantumRackPolicy
import com.cruxcoach.android.boardcell.BoardQuantumRackState
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumLaneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the rack this device has into the rack the rules reason about.
 *
 * The translation carries one rule that matters more than the rest: a layer
 * with no attached holds is unknown, not empty. A Quantum controller names its
 * players by route id, so every reconnected and every foreign layer arrives
 * that way, and reading it as "lights nothing" is exactly how an overlapping
 * send would look safe.
 */
class QuantumLaneRackAdapterTest {

    private fun holds(vararg placements: Int) = placements.map { BoardHold(it, 12) }

    private fun layer(
        slot: Int,
        status: BoardLayerStatus,
        holds: List<BoardHold> = holds(slot * 10),
        confirmedRoute: String? = null,
        color: Int = 0xFF00FF00.toInt(),
    ) = BoardClimbLayer(
        slot = slot,
        climbUuid = "climb-$slot",
        routeUuid = "route-$slot",
        climbName = "Climb $slot",
        angle = 40,
        userUuid = "user-$slot",
        color = color,
        holds = holds,
        status = status,
        confirmedRouteUuid = confirmedRoute,
    )

    @Test
    fun `layer status becomes lane source`() {
        val state = BoardLayerState(
            layers = listOf(
                layer(0, BoardLayerStatus.PREVIEW),
                layer(1, BoardLayerStatus.SENDING),
                layer(2, BoardLayerStatus.CONFIRMED, confirmedRoute = "route-2"),
            ),
        )

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4)

        assertEquals(QuantumLaneSource.PREVIEW, rack.first { it.lane == 0 }.source)
        assertEquals(QuantumLaneSource.SENDING, rack.first { it.lane == 1 }.source)
        assertEquals(QuantumLaneSource.CONFIRMED, rack.first { it.lane == 2 }.source)
    }

    @Test
    fun `a failed write leaves the layer that was already there`() {
        val state = BoardLayerState(
            layers = listOf(
                layer(0, BoardLayerStatus.FAILED, confirmedRoute = "route-0"),
                layer(1, BoardLayerStatus.FAILED, confirmedRoute = null),
            ),
        )

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4)

        // Lane 0 refused a replacement; what it was showing is still physical
        // truth and still occupies a controller place.
        assertEquals(QuantumLaneSource.CONFIRMED, rack.first { it.lane == 0 }.source)
        assertTrue(rack.first { it.lane == 0 }.physical)
        // Lane 1 never had anything on the wall.
        assertEquals(QuantumLaneSource.PREVIEW, rack.first { it.lane == 1 }.source)
        assertFalse(rack.first { it.lane == 1 }.physical)
    }

    @Test
    fun `a layer without holds is unknown rather than empty`() {
        val state = BoardLayerState(
            layers = listOf(layer(0, BoardLayerStatus.CONFIRMED, holds = emptyList(), confirmedRoute = "route-0")),
        )

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4)

        assertNull(rack.first { it.lane == 0 }.placements)
        assertTrue(rack.first { it.lane == 0 }.unknownHolds)
    }

    @Test
    fun `a resolved route hydrates the holds of a reconnected layer`() {
        val state = BoardLayerState(
            layers = listOf(layer(0, BoardLayerStatus.CONFIRMED, holds = emptyList(), confirmedRoute = "ROUTE-0")),
        )

        val rack = QuantumLaneRackAdapter.occupancies(
            state, maxLanes = 4, hydrated = mapOf("route-0" to setOf(5, 6)),
        )

        assertEquals(setOf(5, 6), rack.first { it.lane == 0 }.placements)
        assertFalse(rack.first { it.lane == 0 }.unknownHolds)
    }

    @Test
    fun `foreign players get negative ids so they compare but are never targets`() {
        val state = BoardLayerState(
            layers = listOf(layer(0, BoardLayerStatus.CONFIRMED, confirmedRoute = "route-0")),
            externalLayers = listOf(
                ExternalBoardLayer("other-route", "other-user", 0xFF00FFFF.toInt(), 120),
            ),
        )

        val rack = QuantumLaneRackAdapter.occupancies(
            state, maxLanes = 4, hydrated = mapOf("other-route" to setOf(77)),
        )

        val foreign = rack.first { it.foreignSlot }
        assertEquals(-1, foreign.lane)
        assertEquals(QuantumLaneSource.FOREIGN, foreign.source)
        assertEquals(setOf(77), foreign.placements)
        assertTrue(foreign.physical)
    }

    @Test
    fun `an unresolvable foreign player stays unknown`() {
        val state = BoardLayerState(
            externalLayers = listOf(ExternalBoardLayer("mystery", "somebody", 0xFF00FFFF.toInt(), 0)),
        )

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4)

        assertTrue(rack.single().unknownHolds)
    }

    @Test
    fun `lane labels come from the write record, then from the plan`() {
        val plan = QuantumLanePlan(
            rack = BoardQuantumRackPolicy.assign(BoardQuantumRackState(), "planned-entry", 1, 1),
            committed = mapOf(0 to "written-entry"),
        )
        val state = BoardLayerState(
            layers = listOf(
                layer(0, BoardLayerStatus.CONFIRMED, confirmedRoute = "route-0"),
                layer(1, BoardLayerStatus.PREVIEW),
            ),
        )

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4, plan = plan)

        assertEquals("written-entry", rack.first { it.lane == 0 }.entryId)
        assertEquals("planned-entry", rack.first { it.lane == 1 }.entryId)
    }

    @Test
    fun `an in-flight write labels its lane with the occurrence it is for`() {
        val plan = QuantumLanePlan(
            committed = mapOf(0 to "previous-entry"),
            sendingLane = 0,
            sendingEntryId = "new-entry",
        )
        val state = BoardLayerState(layers = listOf(layer(0, BoardLayerStatus.SENDING)))

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 4, plan = plan)

        assertEquals("new-entry", rack.first { it.lane == 0 }.entryId)
    }

    @Test
    fun `lanes beyond the controller's capability are ignored`() {
        val state = BoardLayerState(layers = listOf(layer(0, BoardLayerStatus.CONFIRMED), layer(3, BoardLayerStatus.CONFIRMED)))

        val rack = QuantumLaneRackAdapter.occupancies(state, maxLanes = 2)

        assertEquals(listOf(0), rack.map { it.lane })
    }

    @Test
    fun `unresolved routes name every layer whose holds are missing`() {
        val state = BoardLayerState(
            layers = listOf(
                layer(0, BoardLayerStatus.CONFIRMED, holds = holds(1), confirmedRoute = "route-0"),
                layer(1, BoardLayerStatus.CONFIRMED, holds = emptyList(), confirmedRoute = "Route-1"),
            ),
            externalLayers = listOf(ExternalBoardLayer("Foreign-Route", "u", 0, 0)),
        )

        val wanted = QuantumLaneRackAdapter.unresolvedRoutes(state, maxLanes = 4)

        assertEquals(setOf("route-1", "foreign-route"), wanted)
    }
}
