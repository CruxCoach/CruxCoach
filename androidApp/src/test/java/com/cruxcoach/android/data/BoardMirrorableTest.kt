package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror-gating truth table for [BoardConstants.isLayoutMirrorable], the
 * predicate the climb-detail mirror toggle is gated on. Values mirror Aurora's
 * `layouts.is_mirrored` (RE-verified across the bundled board DBs).
 *
 * The key case: Tension TB2 **Spray** (layout 11) must be NOT mirrorable even
 * though it shares the physically-symmetric TB2 holds with the Mirror layout —
 * Aurora flags it `is_mirrored=0` and a flip would light unpaired holds.
 */
class BoardMirrorableTest {

    @Test
    fun tension_tb1_and_tb2Mirror_areMirrorable_butSprayIsNot() {
        assertTrue("TB1 Original (9) is symmetric", BoardConstants.isLayoutMirrorable(BoardBrand.TENSION, 9))
        assertTrue("TB2 Mirror (10) is symmetric", BoardConstants.isLayoutMirrorable(BoardBrand.TENSION, 10))
        assertFalse("TB2 Spray (11) is an asymmetric spray wall", BoardConstants.isLayoutMirrorable(BoardBrand.TENSION, 11))
    }

    @Test
    fun tension_unknownLayout_isNotMirrorable() {
        assertFalse(BoardConstants.isLayoutMirrorable(BoardBrand.TENSION, 999))
    }

    @Test
    fun symmetricSingleProductAuroraBoards_areMirrorable() {
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.GRASSHOPPER, 1))
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.DECOY, 1))   // Dots
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.DECOY, 2))   // Dungeon Trainer
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.SOILL, 1))
    }

    @Test
    fun asymmetricAndFixedConfigBoards_areNotMirrorable() {
        assertFalse(BoardConstants.isLayoutMirrorable(BoardBrand.TOUCHSTONE, 1))
        assertFalse(BoardConstants.isLayoutMirrorable(BoardBrand.MOONBOARD, 4))
        assertFalse(BoardConstants.isLayoutMirrorable(BoardBrand.AURORA, 1))
        assertFalse(BoardConstants.isLayoutMirrorable(BoardBrand.TWELVECLIMB, 1))
    }

    @Test
    fun kilter_keepsThe014DisplayOnlyMirrorToggle_onEveryLayout() {
        // Product decision 2026-06-10: the 0.1.4 display-only mirror toggle
        // (geometric mirror-map fallback) stays available for Kilter —
        // removing it was an unintended upgrade regression.
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.KILTER, 1))   // Original
        assertTrue(BoardConstants.isLayoutMirrorable(BoardBrand.KILTER, 8))   // Homewall
    }

    @Test
    fun auroraVariant_carriesIsMirroredFlag() {
        assertEquals(true, BoardConstants.auroraVariant(BoardBrand.TENSION, 9)?.isMirrored)
        assertEquals(true, BoardConstants.auroraVariant(BoardBrand.TENSION, 10)?.isMirrored)
        assertEquals(false, BoardConstants.auroraVariant(BoardBrand.TENSION, 11)?.isMirrored)
    }

    @Test
    fun decoy_exposesOnlyDungeonTrainer() {
        // "Dots" (layout 1) is a password-gated internal/R&D layout, not a
        // consumer board — Decoy pins the single real layout 2 so the picker
        // never offers Dots and chunk-derive can't select it.
        val variants = BoardConstants.auroraVariants(BoardBrand.DECOY)
        assertEquals(1, variants.size)
        assertEquals(2, variants[0].layoutId) // Dungeon Trainer
        assertEquals(1, variants[0].productId)
        assertEquals(1, variants[0].defaultSizeId)
        assertTrue("Dungeon Trainer is symmetric", variants[0].isMirrored)
        assertEquals(2, BoardConstants.auroraVariant(BoardBrand.DECOY, 2)?.layoutId)
        assertNull("Dots must not resolve", BoardConstants.auroraVariant(BoardBrand.DECOY, 1))
    }
}
