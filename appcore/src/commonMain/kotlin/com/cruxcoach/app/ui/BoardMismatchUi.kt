package com.cruxcoach.app.ui

import com.cruxcoach.app.setup.BoardConfigurationMismatch
import com.cruxcoach.app.setup.BoardMismatchKind
import com.cruxcoach.app.setup.BoardPrefillSource

/**
 * Android's guided board-picker explanation (`BoardMismatchExplanation`) as data:
 * Swift renders [messageKey] with [messageArg1]/[messageArg2] and [sourceKey] with
 * [prefillBrandTitle], and preselects the prefilled board. [active] is false when
 * the configured board matches.
 */
class BoardMismatchUi(
    val active: Boolean,
    /** connectedBrand | activeBrand | connectedModel | activeLayout | activeSize; empty when none */
    val kindCode: String,
    val messageKey: String,
    val messageArg1: String,
    val messageArg2: String,
    /** board_mismatch_detected_controller | _climb | _both */
    val sourceKey: String,
    val prefillBrandWire: String,
    val prefillBrandTitle: String,
    /** 0 when the evidence does not prove a layout. */
    val prefillLayoutId: Int,
    /** 0 when the evidence does not prove a size. */
    val prefillProductSizeId: Int,
) {
    internal companion object {
        val NONE = BoardMismatchUi(false, "", "", "", "", "", "", "", 0, 0)

        fun of(mismatch: BoardConfigurationMismatch?): BoardMismatchUi {
            val m = mismatch ?: return NONE
            val (kind, key) = when (m.kind) {
                BoardMismatchKind.CONNECTED_BRAND -> "connectedBrand" to "board_mismatch_connected_brand"
                BoardMismatchKind.ACTIVE_BRAND -> "activeBrand" to "board_mismatch_active_brand"
                BoardMismatchKind.CONNECTED_MODEL -> "connectedModel" to "board_mismatch_connected_model"
                BoardMismatchKind.ACTIVE_LAYOUT -> "activeLayout" to "board_mismatch_active_layout"
                BoardMismatchKind.ACTIVE_SIZE -> "activeSize" to "board_mismatch_active_size"
            }
            val (arg1, arg2) = when (m.kind) {
                BoardMismatchKind.CONNECTED_BRAND ->
                    (m.connectedBrand ?: m.expectedBrand).displayName to m.climbBrand.displayName
                BoardMismatchKind.ACTIVE_BRAND ->
                    (m.activeBrand ?: m.expectedBrand).displayName to m.expectedBrand.displayName
                else -> "" to ""
            }
            return BoardMismatchUi(
                active = true,
                kindCode = kind,
                messageKey = key,
                messageArg1 = arg1,
                messageArg2 = arg2,
                sourceKey = when (m.prefill.source) {
                    BoardPrefillSource.CONNECTED_CONTROLLER -> "board_mismatch_detected_controller"
                    BoardPrefillSource.CLIMB -> "board_mismatch_detected_climb"
                    BoardPrefillSource.CONTROLLER_AND_CLIMB -> "board_mismatch_detected_both"
                },
                prefillBrandWire = m.prefill.brand.wireValue,
                prefillBrandTitle = m.prefill.brand.displayName,
                prefillLayoutId = m.prefill.layoutId?.toInt() ?: 0,
                prefillProductSizeId = m.prefill.productSizeId ?: 0,
            )
        }
    }
}
