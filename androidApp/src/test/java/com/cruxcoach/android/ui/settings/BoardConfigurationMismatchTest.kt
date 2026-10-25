package com.cruxcoach.android.ui.settings

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.ShinyWallVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class BoardConfigurationMismatchTest {
    @Test
    fun `connection mismatch prefills common MoonBoard 2016 landing choice`() {
        val mismatch = connectedBoardConfigurationMismatch(
            activeBrand = BoardBrand.KILTER,
            connectedBrand = BoardBrand.MOONBOARD,
        )

        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.ACTIVE_BRAND, mismatch.kind)
        assertEquals(BoardBrand.MOONBOARD, mismatch.prefill.brand)
        assertEquals(BoardPickerPrefillSource.CONNECTED_CONTROLLER, mismatch.prefill.source)
        assertEquals(MoonBoardVariant.MOONBOARD_2016.layoutId, mismatch.prefill.layoutId)
        assertNull(mismatch.prefill.productSizeId)
    }

    @Test
    fun `matching connection does not interrupt the user`() {
        assertNull(
            connectedBoardConfigurationMismatch(
                activeBrand = BoardBrand.TENSION,
                connectedBrand = BoardBrand.TENSION,
            ),
        )
    }

    @Test
    fun `a Shiny Wall name suggesting another variant offers to switch`() {
        val mismatch = connectedBoardConfigurationMismatch(
            activeBrand = BoardBrand.SHINYWALL,
            connectedBrand = BoardBrand.SHINYWALL,
            activeLayoutId = ShinyWallVariant.ST1.layoutId,
            connectedShinyVariantHint = ShinyWallVariant.ST4,
        )
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.CONNECTED_MODEL, mismatch.kind)
        assertEquals(ShinyWallVariant.ST4.layoutId, mismatch.prefill.layoutId)
        assertEquals(ShinyWallVariant.ST4.productSizeId.toInt(), mismatch.prefill.productSizeId)
        // Matching variant or no hint: nothing to ask.
        assertNull(
            connectedBoardConfigurationMismatch(
                activeBrand = BoardBrand.SHINYWALL,
                connectedBrand = BoardBrand.SHINYWALL,
                activeLayoutId = ShinyWallVariant.ST4.layoutId,
                connectedShinyVariantHint = ShinyWallVariant.ST4,
            ),
        )
        assertNull(
            connectedBoardConfigurationMismatch(
                activeBrand = BoardBrand.SHINYWALL,
                connectedBrand = BoardBrand.SHINYWALL,
                activeLayoutId = ShinyWallVariant.ST1.layoutId,
            ),
        )
    }

    @Test
    fun `a Shiny Wall connected under another board prefills the hinted variant`() {
        val mismatch = connectedBoardConfigurationMismatch(
            activeBrand = BoardBrand.KILTER,
            connectedBrand = BoardBrand.SHINYWALL,
            connectedShinyVariantHint = ShinyWallVariant.ST5,
        )
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.ACTIVE_BRAND, mismatch.kind)
        assertEquals(ShinyWallVariant.ST5.layoutId, mismatch.prefill.layoutId)
        val noHint = connectedBoardConfigurationMismatch(BoardBrand.KILTER, BoardBrand.SHINYWALL)
        assertEquals(ShinyWallVariant.ST1.layoutId, noHint?.prefill?.layoutId)
    }

    @Test
    fun `connection mismatch trusts verified Quantum model`() {
        val mismatch = connectedBoardConfigurationMismatch(
            activeBrand = BoardBrand.KILTER,
            connectedBrand = BoardBrand.QUANTUM,
            connectedQuantumModel = QuantumBoardModel.XL,
        )

        assertNotNull(mismatch)
        assertEquals(QuantumBoardModel.XL.layoutId, mismatch.prefill.layoutId)
        assertEquals(QuantumBoardModel.XL.productSizeId.toInt(), mismatch.prefill.productSizeId)
    }

    @Test
    fun `connected Kilter controller wins and prefills 12x12 with kickboard`() {
        val mismatch = resolveBoardConfigurationMismatch(
            BoardSendIdentity(
                climbBrand = BoardBrand.MOONBOARD,
                climbLayoutId = MoonBoardVariant.MOONBOARD_2016.layoutId,
                activeBrand = BoardBrand.MOONBOARD,
                activeLayoutId = MoonBoardVariant.MOONBOARD_2016.layoutId,
                activeProductSizeId = null,
                connectedBrand = BoardBrand.KILTER,
            )
        )

        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.CONNECTED_BRAND, mismatch.kind)
        assertEquals(BoardBrand.KILTER, mismatch.prefill.brand)
        assertEquals(BoardPickerPrefillSource.CONNECTED_CONTROLLER, mismatch.prefill.source)
        assertEquals(1L, mismatch.prefill.layoutId)
        assertEquals(10, mismatch.prefill.productSizeId)
    }

    @Test
    fun `verified Quantum controller prefills exact model`() {
        val model = QuantumBoardModel.M
        val mismatch = resolveBoardConfigurationMismatch(
            BoardSendIdentity(
                climbBrand = BoardBrand.QUANTUM,
                climbLayoutId = QuantumBoardModel.XL.layoutId,
                activeBrand = BoardBrand.QUANTUM,
                activeLayoutId = QuantumBoardModel.XL.layoutId,
                activeProductSizeId = QuantumBoardModel.XL.productSizeId.toInt(),
                connectedBrand = BoardBrand.QUANTUM,
                connectedQuantumModel = model,
            )
        )

        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.CONNECTED_MODEL, mismatch.kind)
        assertEquals(model.layoutId, mismatch.prefill.layoutId)
        assertEquals(model.productSizeId.toInt(), mismatch.prefill.productSizeId)
    }

    @Test
    fun `zero overlap keeps size unresolved`() {
        val identity = BoardSendIdentity(
            climbBrand = BoardBrand.KILTER,
            climbLayoutId = 8L,
            activeBrand = BoardBrand.KILTER,
            activeLayoutId = 8L,
            activeProductSizeId = 12,
            connectedBrand = BoardBrand.KILTER,
        )

        val mismatch = boardSizeMismatch(identity)

        assertEquals(BoardMismatchKind.ACTIVE_SIZE, mismatch.kind)
        assertEquals(8L, mismatch.prefill.layoutId)
        assertNull(mismatch.prefill.productSizeId)
    }

}
