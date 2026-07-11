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
        // Aurora-family + info-layer families round-trip too.
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
    fun interactiveAndInfoLayerFamilies() {
        // FEAT-031: Kilter, MoonBoard and the five Aurora-family boards are
        // interactive; only AURORA (the original board) and 12climb remain
        // map-only info-layer brands.
        for (b in listOf(
            BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.TENSION,
            BoardBrand.GRASSHOPPER, BoardBrand.DECOY, BoardBrand.SOILL,
            BoardBrand.TOUCHSTONE,
        )) {
            assertEquals(true, b.isInteractive, "$b should be interactive")
            assertEquals(false, b in BoardBrand.INFO_LAYER, "$b not info-layer")
        }
        for (b in listOf(BoardBrand.AURORA, BoardBrand.TWELVECLIMB)) {
            assertEquals(false, b.isInteractive, "$b should be info-layer")
            assertEquals(true, b in BoardBrand.INFO_LAYER, "$b in INFO_LAYER")
        }
    }

    @Test
    fun auroraProtocolCapabilities() {
        // The Aurora-protocol boards (Kilter + the five) share placement / LED
        // / heatmap geometry. MoonBoard is interactive but photo-based, so it
        // is NOT Aurora-protocol; info-layer brands have no capabilities.
        for (b in listOf(
            BoardBrand.KILTER, BoardBrand.TENSION, BoardBrand.GRASSHOPPER,
            BoardBrand.DECOY, BoardBrand.SOILL, BoardBrand.TOUCHSTONE,
        )) {
            assertEquals(true, b.usesAuroraProtocol, "$b uses Aurora protocol")
            assertEquals(true, b.usesAuroraPlacements, "$b uses placements")
            assertEquals(true, b.usesLedPreview, "$b uses LED preview")
            assertEquals(true, b.hasHeatmap, "$b has heatmap")
        }
        assertEquals(false, BoardBrand.MOONBOARD.usesAuroraProtocol)
        assertEquals(false, BoardBrand.MOONBOARD.usesAuroraPlacements)
        assertEquals(false, BoardBrand.AURORA.usesAuroraProtocol)

        // Authoring is enabled for every INTERACTIVE board (Kilter, MoonBoard +
        // the Aurora family); the info-layer brands (aurora, 12climb) can't
        // author — no catalogue, no editor. supportsAuthoring == isInteractive.
        assertEquals(true, BoardBrand.KILTER.supportsAuthoring)
        assertEquals(true, BoardBrand.MOONBOARD.supportsAuthoring)
        assertEquals(true, BoardBrand.TENSION.supportsAuthoring)
        assertEquals(true, BoardBrand.GRASSHOPPER.supportsAuthoring)
        assertEquals(true, BoardBrand.DECOY.supportsAuthoring)
        assertEquals(true, BoardBrand.SOILL.supportsAuthoring)
        assertEquals(true, BoardBrand.TOUCHSTONE.supportsAuthoring)
        assertEquals(false, BoardBrand.AURORA.supportsAuthoring)
        assertEquals(false, BoardBrand.TWELVECLIMB.supportsAuthoring)
        BoardBrand.entries.forEach {
            assertEquals(it.isInteractive, it.supportsAuthoring)
        }
        // Official-app publish (push to the vendor's own app) remains
        // Kilter-only — Aurora/MoonBoard authoring is CruxCoach-community only.
        assertEquals(true, BoardBrand.KILTER.supportsOfficialAppPublish)
        assertEquals(false, BoardBrand.TENSION.supportsOfficialAppPublish)
        assertEquals(false, BoardBrand.MOONBOARD.supportsOfficialAppPublish)
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
    fun moonBoardVariantAnglesMatchBoardAdjustability() {
        // Adjustable boards (25° + 40°): 2016, 2024, and both Masters — the
        // official catalogue sets their problems at both angles. Genuine
        // fixed-40° boards: Mini 2020. (2016 + 2024 were widened from 40°-only
        // once the full official catalogue's 25° content became available.)
        assertEquals(listOf(25, 40), MoonBoardVariant.MOONBOARD_2016.angles)
        assertEquals(listOf(25, 40), MoonBoardVariant.MOONBOARD_2024.angles)
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
