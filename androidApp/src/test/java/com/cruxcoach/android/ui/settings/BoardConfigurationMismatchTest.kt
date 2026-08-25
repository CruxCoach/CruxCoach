package com.cruxcoach.android.ui.settings

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class BoardConfigurationMismatchTest {
    @Test
    fun `connection mismatch immediately prefills safely known MoonBoard brand`() {
        val mismatch = connectedBoardConfigurationMismatch(
            activeBrand = BoardBrand.KILTER,
            connectedBrand = BoardBrand.MOONBOARD,
        )

        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.ACTIVE_BRAND, mismatch.kind)
        assertEquals(BoardBrand.MOONBOARD, mismatch.prefill.brand)
        assertEquals(BoardPickerPrefillSource.CONNECTED_CONTROLLER, mismatch.prefill.source)
        assertNull(mismatch.prefill.layoutId)
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
    fun `connected controller brand wins and unknown fields stay unconfirmed`() {
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
        assertNull(mismatch.prefill.layoutId)
        assertNull(mismatch.prefill.productSizeId)
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
