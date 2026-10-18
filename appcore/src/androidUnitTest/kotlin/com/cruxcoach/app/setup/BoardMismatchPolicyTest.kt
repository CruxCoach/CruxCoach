package com.cruxcoach.app.setup

import com.cruxcoach.app.ui.BoardMismatchUi
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Android BoardConfigurationMismatchTest, ported, plus the Swift snapshot. */
class BoardMismatchPolicyTest {
    @Test
    fun `connection mismatch prefills common MoonBoard 2016 landing choice`() {
        val mismatch = BoardMismatchPolicy.connected(BoardBrand.KILTER, BoardBrand.MOONBOARD)
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.ACTIVE_BRAND, mismatch.kind)
        assertEquals(BoardBrand.MOONBOARD, mismatch.prefill.brand)
        assertEquals(BoardPrefillSource.CONNECTED_CONTROLLER, mismatch.prefill.source)
        assertEquals(MoonBoardVariant.MOONBOARD_2016.layoutId, mismatch.prefill.layoutId)
        assertNull(mismatch.prefill.productSizeId)
    }

    @Test
    fun `matching connection does not interrupt the user`() {
        assertNull(BoardMismatchPolicy.connected(BoardBrand.TENSION, BoardBrand.TENSION))
        assertNull(BoardMismatchPolicy.connected(BoardBrand.TENSION, null))
        assertFalse(BoardMismatchUi.of(null).active)
    }

    @Test
    fun `connection mismatch trusts verified Quantum model`() {
        val mismatch = BoardMismatchPolicy.connected(BoardBrand.KILTER, BoardBrand.QUANTUM, QuantumBoardModel.XL)
        assertNotNull(mismatch)
        assertEquals(QuantumBoardModel.XL.layoutId, mismatch.prefill.layoutId)
        assertEquals(QuantumBoardModel.XL.productSizeId.toInt(), mismatch.prefill.productSizeId)
        val ui = BoardMismatchUi.of(mismatch)
        assertEquals("quantum", ui.prefillBrandWire)
        assertEquals(QuantumBoardModel.XL.layoutId.toInt(), ui.prefillLayoutId)
        assertEquals("Kilter", ui.messageArg1)
        assertEquals("Quantum Board", ui.messageArg2)
    }

    @Test
    fun `connected Kilter controller wins and prefills 12x12 with kickboard`() {
        val mismatch = BoardMismatchPolicy.resolve(
            BoardSendIdentity(
                climbBrand = BoardBrand.MOONBOARD,
                climbLayoutId = MoonBoardVariant.MOONBOARD_2016.layoutId,
                activeBrand = BoardBrand.MOONBOARD,
                activeLayoutId = MoonBoardVariant.MOONBOARD_2016.layoutId,
                activeProductSizeId = null,
                connectedBrand = BoardBrand.KILTER,
            ),
        )
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.CONNECTED_BRAND, mismatch.kind)
        assertEquals(BoardBrand.KILTER, mismatch.prefill.brand)
        assertEquals(BoardPrefillSource.CONNECTED_CONTROLLER, mismatch.prefill.source)
        assertEquals(1L, mismatch.prefill.layoutId)
        assertEquals(10, mismatch.prefill.productSizeId)
        val ui = BoardMismatchUi.of(mismatch)
        assertEquals("board_mismatch_connected_brand", ui.messageKey)
        assertEquals("Kilter", ui.messageArg1)
        assertEquals("MoonBoard", ui.messageArg2)
    }

    @Test
    fun `verified Quantum controller prefills exact model`() {
        val model = QuantumBoardModel.M
        val mismatch = BoardMismatchPolicy.resolve(
            BoardSendIdentity(
                climbBrand = BoardBrand.QUANTUM,
                climbLayoutId = QuantumBoardModel.XL.layoutId,
                activeBrand = BoardBrand.QUANTUM,
                activeLayoutId = QuantumBoardModel.XL.layoutId,
                activeProductSizeId = QuantumBoardModel.XL.productSizeId.toInt(),
                connectedBrand = BoardBrand.QUANTUM,
                connectedQuantumModel = model,
            ),
        )
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.CONNECTED_MODEL, mismatch.kind)
        assertEquals(model.layoutId, mismatch.prefill.layoutId)
        assertEquals(model.productSizeId.toInt(), mismatch.prefill.productSizeId)
        assertEquals("board_mismatch_connected_model", BoardMismatchUi.of(mismatch).messageKey)
    }

    @Test
    fun `an active board of another family is fixed from the climb when no controller speaks`() {
        val mismatch = BoardMismatchPolicy.resolve(
            BoardSendIdentity(
                climbBrand = BoardBrand.KILTER,
                climbLayoutId = 8L,
                activeBrand = BoardBrand.TENSION,
                activeLayoutId = 9L,
                activeProductSizeId = null,
                connectedBrand = null,
            ),
        )
        assertNotNull(mismatch)
        assertEquals(BoardMismatchKind.ACTIVE_BRAND, mismatch.kind)
        assertEquals(BoardPrefillSource.CLIMB, mismatch.prefill.source)
        assertEquals(8L, mismatch.prefill.layoutId)
        assertEquals("board_mismatch_detected_climb", BoardMismatchUi.of(mismatch).sourceKey)
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
        assertNull(BoardMismatchPolicy.resolve(identity))
        val mismatch = BoardMismatchPolicy.size(identity)
        assertEquals(BoardMismatchKind.ACTIVE_SIZE, mismatch.kind)
        assertEquals(8L, mismatch.prefill.layoutId)
        assertNull(mismatch.prefill.productSizeId)
        assertEquals(0, BoardMismatchUi.of(mismatch).prefillProductSizeId)
    }
}
