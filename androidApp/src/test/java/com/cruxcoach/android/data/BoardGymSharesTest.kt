package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGymSharesTest {
    // Kilter Original: 12 x 12 with kickboard (10), 12 x 14 (7); Homewall 10 x 12 (25).
    private val walls = mapOf(10 to 90L, 7 to 1L, 28 to 9L, 25 to 30L)
    private val locations = mapOf(
        ("kilter" to 1) to 80L,
        ("moonboard" to 2) to 60L,
        ("moonboard" to 4) to 39L,
        ("moonboard" to 6) to 1L,
        ("moonboard" to null) to 150L,
        ("tension" to null) to 20L,
        ("12climb" to null) to 1L,
    )
    private val shares = BoardGymShares.from(walls, locations)

    @Test fun `a Kilter size counts among the walls of its own product`() {
        assertEquals(0.9, shares.kilterSize(10)!!, 1e-9)
        assertEquals(0.01, shares.kilterSize(7)!!, 1e-9)
        assertTrue(BoardGymShares.isRare(shares.kilterSize(7)!!))
        assertFalse(BoardGymShares.isRare(shares.kilterSize(28)!!))
    }

    @Test fun `a size no gym has is rare, a product with too few walls has no share`() {
        assertEquals(0.0, shares.kilterSize(27)!!, 1e-9)
        // Homewall: 30 walls in all, enough; one more product with 5 walls would not be.
        assertEquals(1.0, shares.kilterSize(25)!!, 1e-9)
        val few = BoardGymShares.from(mapOf(25 to 5L, 10 to 50L), locations)
        assertNull(few.kilterSize(25))
    }

    @Test fun `a MoonBoard version counts among the MoonBoards that name their version`() {
        assertEquals(0.6, shares.moonBoard(2L)!!, 1e-9)
        assertEquals(0.0, shares.moonBoard(3L)!!, 1e-9)
        assertTrue(BoardGymShares.isRare(shares.moonBoard(6L)!!))
    }

    @Test fun `boards count Kilter walls and one board per gym of every other brand`() {
        // 130 Kilter walls with a known size + 250 MoonBoards + 20 Tension + 1 map-only board.
        val total = 401.0
        assertEquals(100 / total, shares.board(BoardBrand.KILTER, BoardConstants.KILTER_PRODUCT_ID)!!, 1e-9)
        assertEquals(30 / total, shares.board(BoardBrand.KILTER, BoardConstants.KILTER_HOMEWALL_PRODUCT_ID)!!, 1e-9)
        assertEquals(250 / total, shares.board(BoardBrand.MOONBOARD)!!, 1e-9)
        assertEquals(20 / total, shares.board(BoardBrand.TENSION)!!, 1e-9)
    }

    @Test fun `a board the gym list does not cover gets no share`() {
        assertNull(shares.board(BoardBrand.QUANTUM))
    }

    @Test fun `without walls on the device sizes use the bundled counts and boards stay empty`() {
        val bundled = BoardGymShares.from(emptyMap(), locations)
        assertEquals(BoardConstants.DEFAULT_SIZE_FREQUENCY, bundled.kilterSizeFrequency)
        assertTrue(BoardGymShares.isRare(bundled.kilterSize(7)!!))
        assertNull(bundled.board(BoardBrand.MOONBOARD))
    }
}
