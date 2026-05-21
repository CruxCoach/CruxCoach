package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the FEAT-027 multi-board domain enums: [BoardBrand]
 * (typed `climbs.board_brand` column) and [MoonBoardVariant].
 */
class BoardBrandTest {

    @Test
    fun boardBrandRoundTripsWireValues() {
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("kilter"))
        assertEquals(BoardBrand.MOONBOARD, BoardBrand.fromWire("moonboard"))
        assertEquals("kilter", BoardBrand.KILTER.wireValue)
        assertEquals("moonboard", BoardBrand.MOONBOARD.wireValue)
    }

    @Test
    fun boardBrandDefaultsToKilterForUnknownOrNull() {
        // A missing / legacy / unknown brand can only be a pre-multi-board
        // row, which is Kilter by definition — never unclassifiable.
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire(null))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire(""))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("tension"))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("MoonBoard")) // case-sensitive
    }

    @Test
    fun moonBoardVariantResolvesByLayoutId() {
        assertEquals(MoonBoardVariant.MOONBOARD_2016, MoonBoardVariant.fromLayoutId(2L))
        assertEquals(MoonBoardVariant.MASTERS_2017, MoonBoardVariant.fromLayoutId(4L))
        assertEquals(MoonBoardVariant.MASTERS_2019, MoonBoardVariant.fromLayoutId(5L))
    }

    @Test
    fun moonBoardVariantReturnsNullForUnsupportedLayout() {
        // Kilter layouts (1 = Original, 8 = Homewall) and the deferred
        // Mini 2020 (layout 6) are not v0.2.0 MoonBoard variants.
        assertNull(MoonBoardVariant.fromLayoutId(1L))
        assertNull(MoonBoardVariant.fromLayoutId(8L))
        assertNull(MoonBoardVariant.fromLayoutId(6L))
        assertNull(MoonBoardVariant.fromLayoutId(999L))
    }

    @Test
    fun moonBoardVariantAnglesMatchDumpCoverage() {
        // Angles match the spookykat dump's per-variant files.
        assertEquals(listOf(40), MoonBoardVariant.MOONBOARD_2016.angles)
        assertEquals(listOf(25, 40), MoonBoardVariant.MASTERS_2017.angles)
        assertEquals(listOf(25, 40), MoonBoardVariant.MASTERS_2019.angles)
    }

    @Test
    fun moonBoardGridIs11x18() {
        assertEquals(11, MoonBoardVariant.GRID_COLUMNS)
        assertEquals(18, MoonBoardVariant.GRID_ROWS)
    }
}
