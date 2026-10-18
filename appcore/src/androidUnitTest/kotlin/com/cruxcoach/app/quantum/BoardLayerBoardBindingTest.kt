package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.BoardLayerBoardIdentity
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A layer is a diode plan for one controller.
 *
 * The rack is process-wide on purpose — a Quantum controller keeps its
 * projections across a disconnect, and a reconnect has to be able to recognise
 * and remove this installation's own slots again. That reasoning only holds for
 * the *same* board. Carried to a different controller the previews describe
 * holds that are not there, and on the same model they would send.
 */
class BoardLayerBoardBindingTest {

    // Port of Android's test; a fresh store stands in for the cleared preferences.
    private val context = FakeKeyValueStore()

    private val boardA = BoardLayerBoardIdentity("quantum:ble:AA:AA:AA:AA:AA:AA", productSizeId = 9202)
    /** Same model (L), different physical controller — the gym's second wall. */
    private val boardASibling = BoardLayerBoardIdentity("quantum:ble:BB:BB:BB:BB:BB:BB", productSizeId = 9202)
    /** Same controller, different model configured — XL rather than L. */
    private val boardADifferentModel = boardA.copy(productSizeId = 9201)

    private fun manager() = BoardLayerManager(context)

    private fun BoardLayerManager.stagePreview(slot: Int = 0, climbUuid: String = "climb-1") {
        assignPreview(
            BoardClimbLayer(
                slot = slot,
                climbUuid = climbUuid,
                routeUuid = climbUuid,
                climbName = "Staged",
                angle = 40,
                userUuid = identityForSlot(slot),
                color = BoardLayerManager.LAYER_COLORS[slot],
                holds = listOf(BoardHold(10 + slot, 12)),
                status = BoardLayerStatus.PREVIEW,
            )
        )
    }

    @Test
    fun `a preview staged on one board does not follow to another of the same model`() {
        val layers = manager()
        layers.bindBoard(boardA)
        layers.stagePreview()
        assertEquals(1, layers.state.value.layers.size)

        layers.bindBoard(boardASibling)

        assertEquals(emptyList<BoardClimbLayer>(), layers.state.value.layers, "the same model is not the same wall — these diodes belong to the other one")
        assertEquals(boardASibling, layers.state.value.board)
    }

    @Test
    fun `switching the configured model drops the rack too`() {
        val layers = manager()
        layers.bindBoard(boardA)
        layers.stagePreview()

        layers.bindBoard(boardADifferentModel)

        assertEquals(emptyList<BoardClimbLayer>(), layers.state.value.layers)
    }

    @Test
    fun `reconnecting to the same board keeps the rack`() {
        val layers = manager()
        layers.bindBoard(boardA)
        layers.stagePreview()

        // A disconnect resolves to no board at all; that is a reconnect in
        // progress, not a board change.
        layers.bindBoard(null)
        layers.bindBoard(boardA)

        assertEquals(1, layers.state.value.layers.size)
        assertEquals("climb-1", layers.state.value.layers.single().climbUuid)
    }

    @Test
    fun `a controller's own confirmed layers do not survive a board change either`() {
        val layers = manager()
        layers.bindBoard(boardA)
        layers.stagePreview()
        layers.reconcile(
            listOf(
                QuantumActivePlayer(
                    routeId = "climb-1",
                    userId = layers.identityForSlot(0),
                    remainingSeconds = 0,
                    color = 0x00ff00,
                ),
                QuantumActivePlayer(
                    routeId = "somebody-else",
                    userId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                    remainingSeconds = 0,
                    color = 0x00ffff,
                ),
            )
        )
        assertEquals(1, layers.state.value.layers.size)
        assertEquals(1, layers.state.value.externalLayers.size)

        layers.bindBoard(boardASibling)

        assertEquals(emptyList<BoardClimbLayer>(), layers.state.value.layers)
        assertEquals(emptyList<ExternalBoardLayer>(), layers.state.value.externalLayers, "foreign players were reported by the controller we just left")
    }

    @Test
    fun `reconciling does not lose the board the rack is bound to`() {
        val layers = manager()
        layers.bindBoard(boardA)
        layers.stagePreview()

        layers.reconcile(emptyList())

        assertEquals(boardA, layers.state.value.board)
    }

    @Test
    fun `isBoundTo answers for the board that was actually staged`() {
        val layers = manager()
        assertFalse(layers.isBoundTo(boardA), "nothing is staged before the first connection")

        layers.bindBoard(boardA)

        assertTrue(layers.isBoundTo(boardA))
        assertFalse(layers.isBoundTo(boardASibling))
        assertFalse(layers.isBoundTo(boardADifferentModel))
        assertFalse(layers.isBoundTo(null))
    }
}
