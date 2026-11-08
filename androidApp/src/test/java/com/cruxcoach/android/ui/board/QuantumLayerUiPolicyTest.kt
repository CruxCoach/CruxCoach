package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumLayerUiPolicyTest {
    @Test fun `foreign palette color occupies its matching read only visual layer`() {
        val cyan = foreign(holds = listOf(BoardHold(10, 1))).copy(
            color = BoardLayerManager.LAYER_COLORS[1],
            climbName = "The Board",
        )

        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, externalLayers = listOf(cyan)),
            currentClimbUuid = "candidate",
            currentPlacements = setOf(20),
        )

        assertEquals(QuantumLayerVisualState.FOREIGN, result.slots[1].visualState)
        assertEquals(cyan, result.slots[1].externalLayer)
        assertEquals("The Board", result.slots[1].climbName)
        assertEquals(0, result.suggestedSlot)
        assertEquals(BoardLayerManager.LAYER_COLORS[0], result.suggestedColor)
        assertTrue(result.unplacedExternalLayers.isEmpty())
    }

    @Test fun `known foreign overlap blocks the one-answer suggestion`() {
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            externalLayers = listOf(foreign(holds = listOf(BoardHold(10, 1)))),
        )

        val result = QuantumLayerUiPolicy.summarize(
            state, currentClimbUuid = "candidate", currentPlacements = setOf(10, 20),
        )

        assertNull(result.suggestedSlot)
        assertNull(result.suggestedColor)
        assertEquals(QuantumLayerSuggestionBlock.HOLD_CONFLICT, result.suggestionBlock)
    }

    @Test fun `staged local preview overlap blocks the one-answer suggestion`() {
        val preview = layer(slot = 0).copy(
            holds = listOf(BoardHold(10, 1)),
            status = BoardLayerStatus.PREVIEW,
        )

        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(preview)),
            currentClimbUuid = "candidate",
            currentPlacements = setOf(10, 20),
        )

        assertNull(result.suggestedSlot)
        assertNull(result.suggestedColor)
        assertEquals(QuantumLayerSuggestionBlock.HOLD_CONFLICT, result.suggestionBlock)
        assertEquals(
            1,
            QuantumLayerUiPolicy.knownSharedHoldCount(
                state = BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(preview)),
                candidate = setOf(10, 20),
                replacingSlot = 1,
            ),
        )
    }

    @Test fun `unknown foreign route fails closed instead of suggesting a layer`() {
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            externalLayers = listOf(foreign(holds = null)),
        )

        val result = QuantumLayerUiPolicy.summarize(
            state, currentClimbUuid = "candidate", currentPlacements = setOf(20),
        )

        assertNull(result.suggestedSlot)
        assertEquals(QuantumLayerSuggestionBlock.UNKNOWN_LAYER, result.suggestionBlock)
    }

    @Test fun `safe suggestion chooses one free slot and one unreserved color`() {
        val active = layer(slot = 0).copy(
            holds = listOf(BoardHold(10, 1)),
            status = BoardLayerStatus.CONFIRMED,
            confirmedRouteUuid = "route-0",
            confirmedColor = BoardLayerManager.LAYER_COLORS[0],
            confirmedHolds = listOf(BoardHold(10, 1)),
        )

        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(active)),
            currentClimbUuid = "candidate",
            currentPlacements = setOf(20),
        )

        assertEquals(1, result.suggestedSlot)
        assertEquals(BoardLayerManager.LAYER_COLORS[1], result.suggestedColor)
        assertFalse(result.suggestionUsesExistingSlot)
        assertNull(result.suggestionBlock)
    }

    @Test fun `existing climb suggestion is marked and keeps its layer`() {
        val existing = layer(slot = 2).copy(status = BoardLayerStatus.CONFIRMED)

        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(existing)),
            currentClimbUuid = existing.climbUuid,
            currentPlacements = existing.holds.mapTo(mutableSetOf()) { it.placementId },
        )

        assertEquals(existing.slot, result.suggestedSlot)
        assertEquals(existing.color, result.suggestedColor)
        assertTrue(result.suggestionUsesExistingSlot)
        assertNull(result.suggestionBlock)
    }

    @Test fun `empty climb produces an explicit no holds answer`() {
        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM),
            currentClimbUuid = "candidate",
            currentPlacements = emptySet(),
        )

        assertNull(result.suggestedSlot)
        assertEquals(QuantumLayerSuggestionBlock.NO_HOLDS, result.suggestionBlock)
    }

    @Test fun `route above one confirmable activation frame is never suggested`() {
        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM),
            currentClimbUuid = "candidate",
            currentPlacements = (1..93).toSet(),
        )

        assertNull(result.suggestedSlot)
        assertNull(result.suggestedColor)
        assertEquals(
            QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED,
            result.suggestionBlock,
        )
    }

    @Test fun `foreign occupancy produces an explicit board full answer`() {
        val foreignLayers = (0 until BoardLayerManager.MAX_LAYER_IDENTITIES).map { index ->
            foreign(holds = listOf(BoardHold(100 + index, 1))).copy(
                routeUuid = "foreign-route-$index",
                userUuid = "foreign-user-$index",
                color = 0xff000000.toInt() or (index + 1),
            )
        }
        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, externalLayers = foreignLayers),
            currentClimbUuid = "candidate",
            currentPlacements = setOf(20),
        )

        assertNull(result.suggestedSlot)
        assertEquals(QuantumLayerSuggestionBlock.BOARD_FULL, result.suggestionBlock)
    }

    @Test fun `a full local rack produces an explicit no slot answer`() {
        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(
                brand = BoardBrand.QUANTUM,
                layers = (0 until BoardLayerManager.MAX_LAYER_IDENTITIES).map(::layer),
            ),
            currentClimbUuid = "different-climb",
            currentPlacements = setOf(99),
        )

        assertNull(result.suggestedSlot)
        assertEquals(QuantumLayerSuggestionBlock.NO_SLOT, result.suggestionBlock)
    }

    @Test fun `all reserved colors produce an explicit no color answer`() {
        val planned = (0 until BoardLayerManager.MAX_LAYER_IDENTITIES).map { slot ->
            layer(slot).copy(
                holds = listOf(BoardHold(100 + slot, 1)),
            )
        }
        val result = QuantumLayerUiPolicy.summarize(
            BoardLayerState(brand = BoardBrand.QUANTUM, layers = planned),
            currentClimbUuid = "different-climb",
            currentPlacements = setOf(99),
            maxLayers = BoardLayerManager.MAX_LAYER_IDENTITIES + 1,
        )

        assertNull(result.suggestedSlot)
        assertEquals(QuantumLayerSuggestionBlock.NO_COLOR, result.suggestionBlock)
    }

    @Test fun `staged and controller truth have distinct visual states`() {
        val planned = layer(0)
        val replacing = layer(1).copy(
            confirmedRouteUuid = "previous-route",
            confirmedColor = BoardLayerManager.LAYER_COLORS[0],
            confirmedClimbName = "Previous",
            confirmedHolds = listOf(BoardHold(30, 1)),
        )
        val unknown = layer(2).copy(
            routeUuid = "unknown-route",
            confirmedRouteUuid = "unknown-route",
            confirmedColor = BoardLayerManager.LAYER_COLORS[2],
            status = BoardLayerStatus.CONFIRMED,
            controllerDetailsKnown = false,
        )
        val confirmed = layer(3).copy(
            confirmedRouteUuid = "route-3",
            confirmedColor = BoardLayerManager.LAYER_COLORS[3],
            confirmedHolds = listOf(BoardHold(40, 1)),
            status = BoardLayerStatus.CONFIRMED,
        )

        val slots = QuantumLayerUiPolicy.summarize(
            BoardLayerState(
                brand = BoardBrand.QUANTUM,
                layers = listOf(planned, replacing, unknown, confirmed),
            ),
            currentClimbUuid = null,
        ).slots

        assertEquals(QuantumLayerVisualState.PLANNED, slots[0].visualState)
        assertEquals(QuantumLayerVisualState.REPLACING, slots[1].visualState)
        assertEquals(BoardLayerManager.LAYER_COLORS[1], slots[1].plannedColor)
        assertEquals(BoardLayerManager.LAYER_COLORS[0], slots[1].confirmedColor)
        assertEquals("Previous", slots[1].confirmedClimbName)
        assertEquals(QuantumLayerVisualState.UNKNOWN, slots[2].visualState)
        assertEquals(QuantumLayerVisualState.ON_BOARD, slots[3].visualState)
    }

    @Test fun `send all preflight includes a known foreign route not shown on detail`() {
        val planned = layer(0).copy(holds = listOf(BoardHold(10, 1)))
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            layers = listOf(planned),
            externalLayers = listOf(foreign(holds = listOf(BoardHold(10, 2)))),
        )

        val result = QuantumLayerUiPolicy.summarize(
            state,
            currentClimbUuid = "different-detail-climb",
            currentPlacements = setOf(999),
        )

        assertEquals(QuantumLayerSuggestionBlock.HOLD_CONFLICT, result.sendAllBlock)
    }

    @Test fun `send all preflight fails closed for unknown foreign geometry`() {
        val state = BoardLayerState(
            brand = BoardBrand.QUANTUM,
            layers = listOf(layer(0)),
            externalLayers = listOf(foreign(holds = null)),
        )

        assertEquals(
            QuantumLayerSuggestionBlock.UNKNOWN_LAYER,
            QuantumLayerUiPolicy.summarize(state, null).sendAllBlock,
        )
    }

    private fun layer(slot: Int) = BoardClimbLayer(
        slot = slot,
        climbUuid = "climb-$slot",
        routeUuid = "route-$slot",
        climbName = "Climb $slot",
        angle = 40,
        userUuid = "user-$slot",
        color = BoardLayerManager.LAYER_COLORS[slot],
        holds = listOf(BoardHold(20 + slot, 1)),
        status = BoardLayerStatus.PREVIEW,
    )

    private fun foreign(holds: List<BoardHold>?) = ExternalBoardLayer(
        routeUuid = "foreign-route",
        userUuid = "foreign-user",
        color = 0xff123456.toInt(),
        remainingSeconds = 10,
        holds = holds,
    )
}
