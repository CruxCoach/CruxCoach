package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.QuantumOverlapFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Quantum-only browse filter, and the ways it must stay invisible.
 *
 * A filter nobody can see is the one kind that empties a list without
 * explaining itself. This one is gated twice — on the board and on whether
 * anything is actually lit — and reset in the same state transition that
 * publishes a new board, because the gap between the two is exactly where a
 * Kilter search would be narrowed by a Quantum wall.
 */
class BoardBrowserQuantumOverlapTest {

    private val lit = setOf(1, 2, 3)

    // ── Capability gating ─────────────────────────────────────────────────

    @Test
    fun `every board that shows one climb at a time ignores the filter`() {
        BoardBrand.entries.filter { it != BoardBrand.QUANTUM }.forEach { brand ->
            assertEquals(
                QuantumOverlapFilter.OFF,
                BoardBrowsePolicy.overlapFilter(brand, QuantumOverlapFilter.NONE, lit),
                brand.name,
            )
        }
    }

    @Test
    fun `Quantum keeps the requested state`() {
        assertEquals(
            QuantumOverlapFilter.NONE,
            BoardBrowsePolicy.overlapFilter(BoardBrand.QUANTUM, QuantumOverlapFilter.NONE, lit),
        )
        assertEquals(
            QuantumOverlapFilter.AT_MOST_ONE,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.QUANTUM, QuantumOverlapFilter.AT_MOST_ONE, lit,
            ),
        )
    }

    @Test
    fun `an empty wall makes the filter inert instead of scanning the catalogue`() {
        assertEquals(
            QuantumOverlapFilter.OFF,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.QUANTUM, QuantumOverlapFilter.NONE, emptySet(),
            ),
        )
    }

    // ── Board switch ──────────────────────────────────────────────────────

    private fun onQuantum() = BoardBrowserState(
        filter = BrowserFilterState(
            angle = 40,
            layoutId = 9101,
            boardBrand = BoardBrand.QUANTUM.wireValue,
            quantumOverlapFilter = QuantumOverlapFilter.AT_MOST_ONE,
            quantumRuleMask = 4L,
        ),
        quantumLayers = BrowserQuantumLayerState(litPlacements = lit, layerCount = 2),
    )

    @Test
    fun `switching away from Quantum drops the filter in the same transition`() {
        val switched = onQuantum().onBoardSwitch(
            angle = 40,
            layoutId = 1,
            boardBrand = BoardBrand.KILTER.wireValue,
            angleChips = emptyList(),
        )

        assertEquals(QuantumOverlapFilter.OFF, switched.filter.quantumOverlapFilter)
        assertEquals(0L, switched.filter.quantumRuleMask)
        // The wall the filter measured against belongs to a board this device
        // is no longer browsing.
        assertTrue(switched.quantumLayers.litPlacements.isEmpty())
        assertFalse(switched.quantumLayers.available)
    }

    @Test
    fun `switching between Quantum models keeps the filter and the rack`() {
        val switched = onQuantum().onBoardSwitch(
            angle = 40,
            layoutId = 9102,
            boardBrand = BoardBrand.QUANTUM.wireValue,
            angleChips = listOf(40),
        )

        assertEquals(QuantumOverlapFilter.AT_MOST_ONE, switched.filter.quantumOverlapFilter)
        assertEquals(4L, switched.filter.quantumRuleMask)
        assertEquals(lit, switched.quantumLayers.litPlacements)
    }

    @Test
    fun `a MoonBoard switch is as clean as a Kilter one`() {
        val switched = onQuantum().onBoardSwitch(
            angle = 40,
            layoutId = 17,
            boardBrand = BoardBrand.MOONBOARD.wireValue,
            angleChips = listOf(40),
        )

        assertEquals(QuantumOverlapFilter.OFF, switched.filter.quantumOverlapFilter)
        assertEquals(BrowserQuantumLayerState(), switched.quantumLayers)
    }

    // ── Defaults ──────────────────────────────────────────────────────────

    @Test
    fun `a fresh browser opens on the whole catalogue`() {
        val state = BoardBrowserState()

        assertEquals(QuantumOverlapFilter.OFF, state.filter.quantumOverlapFilter)
        assertFalse(state.quantumLayers.available)
        assertEquals(-1L, state.quantumLayers.matchCount)
        assertTrue(state.quantumLayers.complete)
    }

    @Test
    fun `an unresolvable layer marks the rack incomplete without emptying it`() {
        val partial = BrowserQuantumLayerState(
            litPlacements = setOf(9), layerCount = 2, complete = false,
        )

        assertTrue(partial.available)
        assertFalse(partial.complete)
        assertEquals(
            QuantumOverlapFilter.NONE,
            BoardBrowsePolicy.overlapFilter(
                BoardBrand.QUANTUM, QuantumOverlapFilter.NONE, partial.litPlacements,
            ),
            "partial knowledge still narrows; it just may not be called a guarantee",
        )
    }
}
