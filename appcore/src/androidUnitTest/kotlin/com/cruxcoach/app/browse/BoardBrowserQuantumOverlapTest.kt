package com.cruxcoach.app.browse

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.domain.board.QuantumOverlapIndex
import com.cruxcoach.data.repository.ClimbWithStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The two BoardLayerState.toBrowserQuantumLayers cases stay with the BLE layer model (app.ble).
class BoardBrowserQuantumOverlapTest {
    private fun hold(id: Int) = BoardHold(id, HoldRole.HAND)

    private fun climb(uuid: String, frames: String = "") = ClimbWithStats(
        uuid = uuid,
        layoutId = 1,
        setterUsername = null,
        name = uuid,
        frames = frames,
        framesCount = 1,
        difficultyAverage = 10.0,
        qualityAverage = null,
        ascensionistCount = 1,
    )

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
    fun `overlap filter uses hydrated geometry omitted by browse rows`() {
        val rows = listOf(climb("fits"), climb("overlaps"), climb("unknown"))
        val filtered = filterQuantumOverlapClimbs(
            climbs = rows,
            hydratedFrames = mapOf(
                "fits" to "p3r15p4r12",
                "overlaps" to "p1r15p4r12",
            ),
            index = QuantumOverlapIndex(setOf(1, 2)),
            filter = QuantumOverlapFilter.NONE,
        )

        assertEquals(listOf("fits"), filtered.map { it.uuid })
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

    @Test
    fun `page from query before filter change is rejected`() {
        val gate = BrowseRequestGate()
        val unfilteredPage = gate.current()

        val filteredSearch = gate.invalidate()

        assertFalse(gate.accepts(unfilteredPage))
        assertTrue(gate.accepts(filteredSearch))
        assertTrue(gate.accepts(gate.current()))
    }
}
