package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.BoardSelection
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android BoardSendController's board-match guard as the detail and the Quantum rack use it:
 * the climb's family and layout against the configured board and the board on the link.
 */
class BoardFixCheckTest {
    private val climbs = mapOf(
        "kilter-original" to (BoardBrand.KILTER to 1L),
        "kilter-homewall" to (BoardBrand.KILTER to 8L),
        "tension" to (BoardBrand.TENSION to 10L),
        "quantum-l" to (BoardBrand.QUANTUM to QuantumBoardModel.L.layoutId),
    )

    private fun check(
        active: BoardSelection?,
        connected: BoardBrand? = null,
        quantum: QuantumBoardModel? = null,
        failLookup: Boolean = false,
    ) = BoardFixCheck(
        climb = { uuid -> if (failLookup) error("db closed") else climbs[uuid] },
        activeBoard = { active },
        connectedBrand = { connected },
        connectedQuantum = { quantum },
    )

    private val kilterOriginal = BoardSelection("kilter", 1, 10, 40)

    @Test
    fun `a climb of the configured board on its own board is not refused`() {
        assertFalse(check(kilterOriginal, BoardBrand.KILTER).forClimb("kilter-original").active)
        assertFalse(check(kilterOriginal).forClimb("kilter-original").active)
    }

    @Test
    fun `the board on the link wins over the configured one`() {
        val ui = check(kilterOriginal, BoardBrand.MOONBOARD).forClimb("kilter-original")
        assertTrue(ui.active)
        assertEquals("connectedBrand", ui.kindCode)
        assertEquals("moonboard", ui.prefillBrandWire)
        assertEquals("board_mismatch_detected_controller", ui.sourceKey)
    }

    @Test
    fun `a climb of another family than the configured board offers the climb's family`() {
        val ui = check(kilterOriginal).forClimb("tension")
        assertTrue(ui.active)
        assertEquals("activeBrand", ui.kindCode)
        assertEquals("tension", ui.prefillBrandWire)
        assertEquals(10, ui.prefillLayoutId)
        assertEquals("board_mismatch_detected_climb", ui.sourceKey)
    }

    @Test
    fun `a climb of another layout of the same family offers that layout`() {
        val ui = check(kilterOriginal, BoardBrand.KILTER).forClimb("kilter-homewall")
        assertTrue(ui.active)
        assertEquals("activeLayout", ui.kindCode)
        assertEquals(8, ui.prefillLayoutId)
        assertEquals(0, ui.prefillProductSizeId, "the climb proves no size")
    }

    @Test
    fun `a verified Quantum model that is not the climb's is refused with the controller's model`() {
        val active = BoardSelection("quantum", QuantumBoardModel.XL.layoutId.toInt(), QuantumBoardModel.XL.productSizeId.toInt(), 40)
        val ui = check(active, BoardBrand.QUANTUM, QuantumBoardModel.XL).forClimb("quantum-l")
        assertTrue(ui.active)
        assertEquals("connectedModel", ui.kindCode)
        assertEquals(QuantumBoardModel.XL.layoutId.toInt(), ui.prefillLayoutId)
        assertEquals(QuantumBoardModel.XL.productSizeId.toInt(), ui.prefillProductSizeId)
    }

    @Test
    fun `an unknown climb or a failed lookup does not block the send`() {
        assertFalse(check(kilterOriginal, BoardBrand.MOONBOARD).forClimb("logbook-only").active)
        assertFalse(check(kilterOriginal, BoardBrand.MOONBOARD, failLookup = true).forClimb("tension").active)
    }
}
