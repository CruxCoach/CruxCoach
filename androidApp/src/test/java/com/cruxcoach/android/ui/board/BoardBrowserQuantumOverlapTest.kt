package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.QuantumOverlapFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardBrowserQuantumOverlapTest {
    private fun hold(id: Int) = BoardHold(id, HoldRole.HAND)

    @Test
    fun `filter is Quantum-only and inert on an empty wall`() {
        assertEquals(
            QuantumOverlapFilter.OFF,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.KILTER, QuantumOverlapFilter.NONE, setOf(1),
            ),
        )
        assertEquals(
            QuantumOverlapFilter.OFF,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.QUANTUM, QuantumOverlapFilter.AT_MOST_ONE, emptySet(),
            ),
        )
        assertEquals(
            QuantumOverlapFilter.AT_MOST_ONE,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.QUANTUM, QuantumOverlapFilter.AT_MOST_ONE, setOf(1),
            ),
        )
    }

    @Test
    fun `browser rack includes confirmed and external holds but excludes previews`() {
        val preview = BoardClimbLayer(
            slot = 0,
            climbUuid = "planned",
            routeUuid = "planned-route",
            climbName = "Plan",
            angle = 40,
            userUuid = "user",
            color = 0,
            holds = listOf(hold(99)),
            status = BoardLayerStatus.PREVIEW,
        )
        val confirmed = preview.copy(
            slot = 1,
            climbUuid = "live",
            routeUuid = "live-route",
            holds = listOf(hold(1)),
            status = BoardLayerStatus.CONFIRMED,
            confirmedRouteUuid = "live-route",
            confirmedHolds = listOf(hold(1), hold(2)),
        )
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            layers = listOf(preview, confirmed),
            externalLayers = listOf(
                ExternalBoardLayer("foreign", "other", 0, 30, holds = listOf(hold(3))),
            ),
        ).toBrowserQuantumLayers()

        assertEquals(setOf(1, 2, 3), state.litPlacements)
        assertEquals(2, state.layerCount)
        assertTrue(state.complete)
        assertFalse(99 in state.litPlacements)
    }

    @Test
    fun `unknown physical route marks result incomplete`() {
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            externalLayers = listOf(ExternalBoardLayer("foreign", "other", 0, 30)),
        ).toBrowserQuantumLayers()

        assertFalse(state.complete)
        assertEquals(1, state.layerCount)
    }

    @Test
    fun `switching away clears overlap mode and rack atomically`() {
        val quantum = BoardBrowserState(
            filter = BrowserFilterState(
                boardBrand = BoardBrand.QUANTUM.wireValue,
                quantumOverlapFilter = QuantumOverlapFilter.NONE,
            ),
            quantumLayers = BrowserQuantumLayerState(setOf(1), 1),
        )
        val switched = quantum.onBoardSwitch(
            40, 1, BoardBrand.KILTER.wireValue, emptyList(),
        )

        assertEquals(QuantumOverlapFilter.OFF, switched.filter.quantumOverlapFilter)
        assertEquals(BrowserQuantumLayerState(), switched.quantumLayers)
    }
}
