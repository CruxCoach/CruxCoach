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
        // Map-only info-layer families (FEAT-015 Phase 2) round-trip too.
        assertEquals(BoardBrand.TENSION, BoardBrand.fromWire("tension"))
        assertEquals(BoardBrand.TWELVECLIMB, BoardBrand.fromWire("12climb"))
    }

    @Test
    fun boardBrandDefaultsToKilterForUnknownOrNull() {
        // A missing / legacy / unrecognised brand falls back to Kilter — a
        // null brand can only be a pre-multi-board row (Kilter by
        // definition), and a genuinely unknown string is shown as the
        // historical default rather than dropped.
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire(null))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire(""))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("nonexistent-board"))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("MoonBoard")) // case-sensitive
    }

    @Test
    fun infoLayerBrandsAreTheNonInteractiveFamilies() {
        assertEquals(false, BoardBrand.KILTER in BoardBrand.INFO_LAYER)
        assertEquals(false, BoardBrand.MOONBOARD in BoardBrand.INFO_LAYER)
        assertEquals(true, BoardBrand.TENSION in BoardBrand.INFO_LAYER)
        assertEquals(true, BoardBrand.AURORA in BoardBrand.INFO_LAYER)
        assertEquals(true, BoardBrand.KILTER.isInteractive)
        assertEquals(false, BoardBrand.TENSION.isInteractive)
    }

    @Test
    fun boardBrandDerivesFromLayoutId() {
        // The single source of truth used by the draft-insert + community-
        // ingest write paths to persist the right board_brand from a layout
        // id alone. MoonBoard variants → MOONBOARD; Kilter layouts (and any
        // non-MoonBoard id) → KILTER.
        assertEquals(BoardBrand.MOONBOARD, BoardBrand.fromLayoutId(2L))
        assertEquals(BoardBrand.MOONBOARD, BoardBrand.fromLayoutId(4L))
        assertEquals(BoardBrand.MOONBOARD, BoardBrand.fromLayoutId(5L))
        assertEquals(BoardBrand.MOONBOARD, BoardBrand.fromLayoutId(6L))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromLayoutId(1L))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromLayoutId(8L))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromLayoutId(999L))
    }

    @Test
    fun moonBoardVariantResolvesByLayoutId() {
        assertEquals(MoonBoardVariant.MOONBOARD_2016, MoonBoardVariant.fromLayoutId(2L))
        assertEquals(MoonBoardVariant.MASTERS_2017, MoonBoardVariant.fromLayoutId(4L))
        assertEquals(MoonBoardVariant.MASTERS_2019, MoonBoardVariant.fromLayoutId(5L))
        assertEquals(MoonBoardVariant.MINI_2020, MoonBoardVariant.fromLayoutId(6L))
    }

    @Test
    fun moonBoardVariantReturnsNullForUnsupportedLayout() {
        // Kilter layouts (1 = Original, 8 = Homewall) are not MoonBoard
        // variants. Mini 2020 (layout 6) joined the supported set with
        // the bundled-image pipeline.
        assertNull(MoonBoardVariant.fromLayoutId(1L))
        assertNull(MoonBoardVariant.fromLayoutId(8L))
        assertNull(MoonBoardVariant.fromLayoutId(999L))
    }

    @Test
    fun moonBoardVariantAnglesMatchDumpCoverage() {
        // Angles match the spookykat dump's per-variant files.
        assertEquals(listOf(40), MoonBoardVariant.MOONBOARD_2016.angles)
        assertEquals(listOf(25, 40), MoonBoardVariant.MASTERS_2017.angles)
        assertEquals(listOf(25, 40), MoonBoardVariant.MASTERS_2019.angles)
        assertEquals(listOf(40), MoonBoardVariant.MINI_2020.angles)
    }

    @Test
    fun moonBoardGridIs11x18() {
        assertEquals(11, MoonBoardVariant.GRID_COLUMNS)
        assertEquals(18, MoonBoardVariant.GRID_ROWS)
    }
}
