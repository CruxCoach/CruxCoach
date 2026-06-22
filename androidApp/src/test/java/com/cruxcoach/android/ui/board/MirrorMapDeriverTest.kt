package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ui.board.MirrorMapDeriver.Hold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorMapDeriverTest {

    // centre at x = 72, so reflection is x -> 144 - x.
    private val centerX2 = 144

    @Test
    fun rowSymmetricSet_pairsAtTheSameRow() {
        // A symmetric pair (set 1) at the same y, reflected x.
        val holds = listOf(
            Hold(placementId = 10, x = 40, y = 80, setId = 1),
            Hold(placementId = 11, x = 104, y = 80, setId = 1), // 144 - 40
        )
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertEquals(11, map[10])
        assertEquals(10, map[11])
    }

    @Test
    fun onAxisHold_mapsToItself_soIsOmitted() {
        // x == 72 == 144 - 72: its own mirror -> no entry (renders in place).
        val holds = listOf(Hold(placementId = 20, x = 72, y = 64, setId = 1))
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertNull(map[20])
        assertTrue(map.isEmpty())
    }

    @Test
    fun staggeredSet_pairsToTheNearestAdjacentRow() {
        // Kilter foot lattice: a hold at (28,76) reflects to x=116, which only
        // exists one row above/below (y=68, y=84). The old same-row match found
        // nothing and left the foot un-mirrored; now it pairs to the nearer row.
        val holds = listOf(
            Hold(placementId = 30, x = 28, y = 76, setId = 20),
            Hold(placementId = 31, x = 116, y = 84, setId = 20), // the only x=116 nearby
        )
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertEquals(31, map[30])
    }

    @Test
    fun staggeredTie_picksAntisymmetrically() {
        // Reflected column has a hole both above (y+8) and below (y-8). The hold
        // left of the axis (x=28 < mirrorX=116) reaches UP (+y).
        val holds = listOf(
            Hold(placementId = 40, x = 28, y = 76, setId = 20),
            Hold(placementId = 41, x = 116, y = 84, setId = 20), // above
            Hold(placementId = 42, x = 116, y = 68, setId = 20), // below
        )
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertEquals("left-of-axis hold reaches up (+y)", 41, map[40])
    }

    @Test
    fun noPartnerColumn_leavesHoldUnmapped() {
        // Reflected x has no hole in the same set -> no entry (stays in place).
        val holds = listOf(Hold(placementId = 50, x = 20, y = 40, setId = 1))
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertNull(map[50])
    }

    @Test
    fun differentSets_doNotPair() {
        // Geometric mirror exists but in a different set -> not a partner.
        val holds = listOf(
            Hold(placementId = 60, x = 40, y = 80, setId = 1),
            Hold(placementId = 61, x = 104, y = 80, setId = 2),
        )
        val map = MirrorMapDeriver.derive(holds, centerX2)
        assertNull(map[60])
    }
}
