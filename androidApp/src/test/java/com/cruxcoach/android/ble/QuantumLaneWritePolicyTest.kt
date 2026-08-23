package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that keeps every working transport out of the lane sender.
 *
 * Kilter, the Aurora family and MoonBoard each have a send path that works and
 * has its own wire format. Routing them through a generalised layer sender for
 * the sake of symmetry would risk four regressions to make one feature tidier,
 * so the layer path is entered only when both the climb and the board *on the
 * link* are the one that has layers.
 *
 * The connected board is what counts, not the preference: switching the active
 * board in settings never disconnects, so a stale preference must not decide
 * which bytes go down a live link.
 */
class QuantumLaneWritePolicyTest {

    @Test
    fun `only a Quantum climb on a connected Quantum board takes the layer path`() {
        assertTrue(QuantumLaneWritePolicy.handles(BoardBrand.QUANTUM, BoardBrand.QUANTUM))
    }

    @Test
    fun `every other board keeps its single-projection transport`() {
        BoardBrand.entries.filter { it != BoardBrand.QUANTUM }.forEach { brand ->
            assertFalse(
                QuantumLaneWritePolicy.handles(brand, brand),
                "${brand.name} must not reach the layer sender",
            )
            assertFalse(
                QuantumLaneWritePolicy.handles(brand, BoardBrand.QUANTUM),
                "a ${brand.name} climb is not a Quantum route",
            )
            assertFalse(
                QuantumLaneWritePolicy.handles(BoardBrand.QUANTUM, brand),
                "a Quantum climb must not be layered onto a ${brand.name} controller",
            )
        }
    }

    @Test
    fun `an unresolved connection takes no path at all`() {
        assertFalse(QuantumLaneWritePolicy.handles(BoardBrand.QUANTUM, null))
    }
}
