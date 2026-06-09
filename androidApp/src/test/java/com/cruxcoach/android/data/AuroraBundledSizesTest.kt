package com.cruxcoach.android.data

import com.cruxcoach.data.repository.dedupeProductSizesByDimension
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [BoardConstants.AURORA_BUNDLED_SIZES] — the pre-sync size tier the
 * picker offers for EVERY interactive Aurora board. The bundle must mirror
 * exactly what the synced catalogue yields (`dedupeProductSizesByDimension` over
 * the published chunk's product_sizes, minus [BoardConstants.AURORA_EXCLUDED_SIZES]),
 * so the picker's option list does not change once a board finishes syncing.
 */
class AuroraBundledSizesTest {

    @Test
    fun `every interactive Aurora board has bundled sizes, Kilter and MoonBoard do not`() {
        // Variant boards now bundle every product's sizes too (so the tier shows
        // pre-sync; the dialog narrows to the active variant's product).
        assertEquals(9, BoardConstants.auroraBundledSizes(BoardBrand.TENSION).size)   // TB1 5 + TB2 4
        assertEquals(3, BoardConstants.auroraBundledSizes(BoardBrand.DECOY).size)
        // Single-layout boards expose their real sizes immediately (pre-sync).
        assertEquals(3, BoardConstants.auroraBundledSizes(BoardBrand.GRASSHOPPER).size)
        assertEquals(2, BoardConstants.auroraBundledSizes(BoardBrand.SOILL).size)
        assertEquals(1, BoardConstants.auroraBundledSizes(BoardBrand.TOUCHSTONE).size)
        // Kilter / MoonBoard never use the bundle.
        assertTrue(BoardConstants.auroraBundledSizes(BoardBrand.KILTER).isEmpty())
        assertTrue(BoardConstants.auroraBundledSizes(BoardBrand.MOONBOARD).isEmpty())
    }

    @Test
    fun `bundled sizes are dimension-distinct so pre-sync equals post-sync`() {
        // If two bundled sizes shared a (productId, edges) dimension, the
        // catalogue's dedupeProductSizesByDimension would collapse them post-sync
        // while the bundle showed both pre-sync — a jarring list change. Aurora
        // chunks omit board_images (set_count 0 → keep-first), mirrored here by
        // passing set_count 0 for every size. Variant boards dedup PER product.
        for (brand in listOf(
            BoardBrand.TENSION, BoardBrand.DECOY,
            BoardBrand.GRASSHOPPER, BoardBrand.SOILL, BoardBrand.TOUCHSTONE,
        )) {
            val bundle = BoardConstants.auroraBundledSizes(brand)
            val deduped = dedupeProductSizesByDimension(bundle.map { it to 0 })
            assertEquals("$brand bundle must already be dimension-distinct", bundle.size, deduped.size)
        }
    }

    @Test
    fun `single-layout default size is the largest by area`() {
        // Single-layout boards have no variant default, so the picker falls back
        // to the largest size — which must match the catalogue's area-ordered
        // getDefaultProductSizeForBrand. (Variant boards default per variant.)
        fun largestId(brand: BoardBrand) =
            BoardConstants.auroraBundledSizes(brand)
                .maxByOrNull { (it.edgeRight - it.edgeLeft) * (it.edgeTop - it.edgeBottom) }?.id?.toInt()
        assertEquals(4, largestId(BoardBrand.GRASSHOPPER))   // GrandMaster
        assertEquals(2, largestId(BoardBrand.SOILL))         // 12 x 12
        assertEquals(1, largestId(BoardBrand.TOUCHSTONE))    // Full Size
        listOf(BoardBrand.GRASSHOPPER, BoardBrand.SOILL, BoardBrand.TOUCHSTONE).forEach { b ->
            assertTrue(BoardConstants.auroraBundledSizes(b).all { it.productId == 1L })
        }
    }

    @Test
    fun `variant board bundles cover exactly their catalogue products`() {
        // Tension's bundle must span TB1 (product 4) + TB2 (product 5); the
        // dialog filters the bundle by the active variant's productId, so a
        // missing product would blank that variant's size tier.
        val tension = BoardConstants.auroraBundledSizes(BoardBrand.TENSION)
        assertEquals(setOf(4L, 5L), tension.map { it.productId }.toSet())
        assertEquals(5, tension.count { it.productId == 4L })  // TB1 kickboard configs
        assertEquals(4, tension.count { it.productId == 5L })  // TB2 sizes
        BoardConstants.auroraVariants(BoardBrand.TENSION).forEach { v ->
            assertTrue(
                "Tension variant product ${v.productId} has no bundled sizes",
                tension.any { it.productId.toInt() == v.productId },
            )
        }
        // Decoy ships both layouts under one product (1).
        assertTrue(BoardConstants.auroraBundledSizes(BoardBrand.DECOY).all { it.productId == 1L })
    }

    @Test
    fun `excluded Aurora sizes are recognised and never bundled`() {
        // Tension id 10 ("12 high x 16 wide") is an Aurora-side phantom (no such
        // commercial size; absent from the APK extract) — hidden from the picker.
        assertTrue(BoardConstants.isExcludedAuroraSize(BoardBrand.TENSION, 10))
        assertFalse(BoardConstants.isExcludedAuroraSize(BoardBrand.TENSION, 6))
        assertFalse(BoardConstants.isExcludedAuroraSize(BoardBrand.GRASSHOPPER, 10))
        // The bundle must never ship an excluded size (else it'd show pre-sync).
        BoardBrand.entries.forEach { brand ->
            BoardConstants.auroraBundledSizes(brand).forEach { s ->
                assertFalse(
                    "$brand size ${s.id} is excluded yet bundled",
                    BoardConstants.isExcludedAuroraSize(brand, s.id.toInt()),
                )
            }
        }
    }

    @Test
    fun `auroraSizeLabel appends WxH for dimensionless names and leaves dimensioned names alone`() {
        val gh = BoardConstants.auroraBundledSizes(BoardBrand.GRASSHOPPER).associateBy { it.name }
        assertEquals("Master (8 x 12)",
            BoardConstants.auroraSizeLabel(BoardBrand.GRASSHOPPER, gh.getValue("Master")))
        assertEquals("Ninja (8 x 10)",
            BoardConstants.auroraSizeLabel(BoardBrand.GRASSHOPPER, gh.getValue("Ninja")))
        assertEquals("GrandMaster (12 x 12)",
            BoardConstants.auroraSizeLabel(BoardBrand.GRASSHOPPER, gh.getValue("GrandMaster")))
        // Touchstone's single "Full Size" gains its fixed 12 x 12 dimension.
        val ts = BoardConstants.auroraBundledSizes(BoardBrand.TOUCHSTONE).first()
        assertEquals("Full Size (12 x 12)", BoardConstants.auroraSizeLabel(BoardBrand.TOUCHSTONE, ts))
        // Names that already carry the dimension are returned unchanged.
        val soill = BoardConstants.auroraBundledSizes(BoardBrand.SOILL).first()
        assertEquals(soill.name, BoardConstants.auroraSizeLabel(BoardBrand.SOILL, soill))
        val tb2 = BoardConstants.auroraBundledSizes(BoardBrand.TENSION).first { it.name.contains("high") }
        assertEquals(tb2.name, BoardConstants.auroraSizeLabel(BoardBrand.TENSION, tb2))
        // Tension TB1 configs are not dimensions and stay as-is.
        val fullWall = BoardConstants.auroraBundledSizes(BoardBrand.TENSION).first { it.name == "Full Wall" }
        assertEquals("Full Wall", BoardConstants.auroraSizeLabel(BoardBrand.TENSION, fullWall))
    }
}
