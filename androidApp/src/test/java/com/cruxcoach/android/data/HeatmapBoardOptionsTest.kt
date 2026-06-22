package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [BoardConstants.heatmapBoardOptions] — the FEAT-039 stats-heatmap
 * board selector's option list, enumerated from the SAME picker model the
 * board picker uses (Kilter Original/Homewall, Aurora variants, MoonBoard
 * variants) but GATED to the board TYPES the user has actually logged on:
 * the heatmap plots the user's own ascents, so it must never offer a board
 * (brand or layout) with no logs.
 */
class HeatmapBoardOptionsTest {

    private fun kilter(layoutId: Int) = BoardBrand.KILTER.wireValue to layoutId
    private fun tension(layoutId: Int) = BoardBrand.TENSION.wireValue to layoutId
    private fun moonboard(layoutId: Int) = BoardBrand.MOONBOARD.wireValue to layoutId

    @Test
    fun `no logged boards yields no options`() {
        assertTrue(BoardConstants.heatmapBoardOptions(emptySet()).isEmpty())
    }

    @Test
    fun `Kilter with both layouts logged yields Original and Homewall`() {
        val opts = BoardConstants.heatmapBoardOptions(
            setOf(
                kilter(BoardConstants.KILTER_ORIGINAL_LAYOUT),
                kilter(BoardConstants.KILTER_HOMEWALL_LAYOUT),
            )
        )
        assertEquals(2, opts.size)
        val original = opts.first { it.layoutId == BoardConstants.KILTER_ORIGINAL_LAYOUT }
        val homewall = opts.first { it.layoutId == BoardConstants.KILTER_HOMEWALL_LAYOUT }
        assertEquals(BoardBrand.KILTER.wireValue, original.brandWire)
        assertEquals(BoardConstants.KILTER_DEFAULT_SIZE, original.sizeId)
        assertEquals("Kilter Original", original.displayName)
        assertEquals(BoardConstants.KILTER_HOMEWALL_DEFAULT_SIZE, homewall.sizeId)
        assertEquals("Kilter Homewall", homewall.displayName)
    }

    @Test
    fun `Kilter with only Original logged yields only Original`() {
        val opts = BoardConstants.heatmapBoardOptions(
            setOf(kilter(BoardConstants.KILTER_ORIGINAL_LAYOUT))
        )
        assertEquals(1, opts.size)
        assertEquals(BoardConstants.KILTER_ORIGINAL_LAYOUT, opts.single().layoutId)
        assertFalse(opts.any { it.layoutId == BoardConstants.KILTER_HOMEWALL_LAYOUT })
    }

    @Test
    fun `Tension yields one option per logged variant`() {
        val variants = BoardConstants.auroraVariants(BoardBrand.TENSION)
        val opts = BoardConstants.heatmapBoardOptions(
            variants.map { tension(it.layoutId) }.toSet()
        )
        assertEquals(variants.size, opts.size)
        variants.forEach { v ->
            val match = opts.first { it.layoutId == v.layoutId }
            assertEquals(BoardBrand.TENSION.wireValue, match.brandWire)
            assertEquals(v.defaultSizeId, match.sizeId)
            assertEquals(v.displayName, match.displayName)
        }
        // Distinct layouts per option (no collisions).
        assertEquals(opts.size, opts.map { it.layoutId }.distinct().size)
    }

    @Test
    fun `Tension with only one variant logged yields only that variant`() {
        val variants = BoardConstants.auroraVariants(BoardBrand.TENSION)
        val only = variants.first()
        val opts = BoardConstants.heatmapBoardOptions(setOf(tension(only.layoutId)))
        assertEquals(1, opts.size)
        assertEquals(only.layoutId, opts.single().layoutId)
        assertEquals(only.displayName, opts.single().displayName)
    }

    @Test
    fun `single-layout Aurora board yields one option with its largest bundled size`() {
        // Single-layout boards have exactly one type; the rendered layout comes
        // straight from the user's logged ascent (no catalogue lookup).
        val opts = BoardConstants.heatmapBoardOptions(
            loggedBoards = setOf(BoardBrand.GRASSHOPPER.wireValue to 7),
        )
        assertEquals(1, opts.size)
        val opt = opts.single()
        assertEquals(BoardBrand.GRASSHOPPER.wireValue, opt.brandWire)
        assertEquals("Grasshopper", opt.displayName)
        // Layout is the logged one, not a catalogue default.
        assertEquals(7, opt.layoutId)
        // Largest bundled size = GrandMaster (id 4) per AuroraBundledSizesTest.
        val largest = BoardConstants.auroraBundledSizes(BoardBrand.GRASSHOPPER)
            .maxByOrNull { (it.edgeRight - it.edgeLeft) * (it.edgeTop - it.edgeBottom) }!!
        assertEquals(largest.id.toInt(), opt.sizeId)
    }

    @Test
    fun `MoonBoard is omitted until it can render a heatmap`() {
        // MoonBoard has no Aurora placements (hasHeatmap = false), so the hold-
        // heatmap can't render — its variants are not offered as a dead-end pick
        // (FEAT-040 adds a real MoonBoard heatmap). Guarded on hasHeatmap so the
        // assertion flips meaning the moment MoonBoard rendering is enabled.
        val opts = BoardConstants.heatmapBoardOptions(
            MoonBoardVariant.entries.map { moonboard(it.layoutId.toInt()) }.toSet()
        )
        if (BoardBrand.MOONBOARD.hasHeatmap) {
            assertEquals(MoonBoardVariant.entries.size, opts.size)
            MoonBoardVariant.entries.forEach { v ->
                val match = opts.first { it.layoutId == v.layoutId.toInt() }
                assertEquals(BoardBrand.MOONBOARD.wireValue, match.brandWire)
                assertEquals(v.displayName, match.displayName)
            }
        } else {
            assertTrue(opts.none { it.brandWire == BoardBrand.MOONBOARD.wireValue })
        }
    }

    @Test
    fun `only logged board types are offered`() {
        // Kilter Original logged, Tension never → no Tension options.
        val opts = BoardConstants.heatmapBoardOptions(
            setOf(kilter(BoardConstants.KILTER_ORIGINAL_LAYOUT))
        )
        assertFalse(opts.any { it.brandWire == BoardBrand.TENSION.wireValue })
        assertTrue(opts.all { it.brandWire == BoardBrand.KILTER.wireValue })
    }

    @Test
    fun `multiple logged brands are all enumerated together`() {
        val tensionVariants = BoardConstants.auroraVariants(BoardBrand.TENSION)
        val logged = buildSet {
            add(kilter(BoardConstants.KILTER_ORIGINAL_LAYOUT))
            add(kilter(BoardConstants.KILTER_HOMEWALL_LAYOUT))
            tensionVariants.forEach { add(tension(it.layoutId)) }
            MoonBoardVariant.entries.forEach { add(moonboard(it.layoutId.toInt())) }
        }
        val opts = BoardConstants.heatmapBoardOptions(logged)
        // MoonBoard is only enumerated once it can render a heatmap (FEAT-040).
        val moonCount = if (BoardBrand.MOONBOARD.hasHeatmap) MoonBoardVariant.entries.size else 0
        val expected = 2 + // Kilter Original + Homewall
            tensionVariants.size +
            moonCount
        assertEquals(expected, opts.size)
        assertTrue(opts.any { it.brandWire == BoardBrand.KILTER.wireValue })
        assertTrue(opts.any { it.brandWire == BoardBrand.TENSION.wireValue })
        assertEquals(
            BoardBrand.MOONBOARD.hasHeatmap,
            opts.any { it.brandWire == BoardBrand.MOONBOARD.wireValue }
        )
    }
}
