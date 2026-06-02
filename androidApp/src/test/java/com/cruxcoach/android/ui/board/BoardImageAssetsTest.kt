package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the FEAT-031 brand-namespaced background asset paths: Kilter keeps
 * the historical flat path, the other Aurora-family boards are namespaced, and
 * colliding product_size ids across brands resolve to distinct files.
 */
class BoardImageAssetsTest {

    @Test
    fun kilterUsesFlatHistoricalPath() {
        assertEquals("board_images/board_7.webp", boardImageAssetPath(BoardBrand.KILTER, 7))
    }

    @Test
    fun auroraFamilyBoardsAreBrandNamespaced() {
        assertEquals("board_images/tension/board_1.webp", boardImageAssetPath(BoardBrand.TENSION, 1))
        assertEquals("board_images/grasshopper/board_4.webp", boardImageAssetPath(BoardBrand.GRASSHOPPER, 4))
        assertEquals("board_images/decoy/board_2.webp", boardImageAssetPath(BoardBrand.DECOY, 2))
        assertEquals("board_images/soill/board_1.webp", boardImageAssetPath(BoardBrand.SOILL, 1))
        assertEquals("board_images/touchstone/board_1.webp", boardImageAssetPath(BoardBrand.TOUCHSTONE, 1))
    }

    @Test
    fun collidingSizeIdsAcrossBrandsResolveToDistinctPaths() {
        // Kilter size 7 and (a hypothetical) Tension size 7 must not alias.
        assertNotEquals(
            boardImageAssetPath(BoardBrand.KILTER, 7),
            boardImageAssetPath(BoardBrand.TENSION, 7),
        )
    }
}
