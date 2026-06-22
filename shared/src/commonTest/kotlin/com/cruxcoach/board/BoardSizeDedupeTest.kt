package com.cruxcoach.board

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.dedupeProductSizesByDimension
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * De-dup rule behind the holistic size picker
 * ([com.cruxcoach.data.repository.BoardRepository.getSelectableProductSizesForBrand]).
 */
class BoardSizeDedupeTest {

    private fun size(id: Long, productId: Long, name: String, l: Long, r: Long, b: Long, t: Long) =
        BoardSize(id, productId, name, l, r, b, t, null, BoardBrand.GRASSHOPPER)

    @Test
    fun grasshopper_collapsesDuplicateDimensionsToMostCompleteSize() {
        // Aurora lists Grasshopper's Master/Ninja twice — a 3-set and a 5-set
        // build, same dimensions; GrandMaster is unique. The picker must show
        // ONE entry per physical size, choosing the richer (more-sets) build.
        val input = listOf(
            size(2, 1, "Master", -44, 44, 0, 144) to 3,
            size(3, 1, "Ninja", -44, 44, 0, 120) to 3,
            size(4, 1, "GrandMaster", -68, 68, 0, 144) to 6,
            size(5, 1, "Master", -44, 44, 0, 144) to 5,
            size(6, 1, "Ninja", -44, 44, 0, 120) to 5,
        )
        // GrandMaster 4, Master 5 (not 2), Ninja 6 (not 3); sorted by id.
        assertEquals(listOf(4L, 5L, 6L), dedupeProductSizesByDimension(input).map { it.id })
    }

    @Test
    fun distinctDimensions_areAllKept() {
        // So iLL: 8x12 and 12x12 are different dimensions → both kept.
        val input = listOf(
            size(1, 1, "8 x 12", -48, 48, -16, 144) to 1,
            size(2, 1, "12 x 12", -72, 72, -16, 144) to 1,
        )
        assertEquals(listOf(1L, 2L), dedupeProductSizesByDimension(input).map { it.id })
    }

    @Test
    fun sameEdgesDifferentProduct_areNotCollapsed() {
        // Identical edge bbox but different product_id must stay separate.
        val input = listOf(
            size(10, 4, "A", 0, 96, 0, 156) to 4,
            size(20, 5, "B", 0, 96, 0, 156) to 4,
        )
        assertEquals(listOf(10L, 20L), dedupeProductSizesByDimension(input).map { it.id })
    }
}
