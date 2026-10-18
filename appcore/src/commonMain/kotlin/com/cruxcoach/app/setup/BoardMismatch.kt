package com.cruxcoach.app.setup

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel

/**
 * Android `BoardConfigurationMismatch.kt`: what the guided board picker is
 * opened with when the configured board cannot be the one on the link or the
 * one a climb needs. Exact controller evidence is preferred; brands whose BLE
 * identity cannot expose a physical variant land on a documented common choice
 * the user can still change. Nothing here changes the active board.
 */
internal enum class BoardPrefillSource { CONNECTED_CONTROLLER, CLIMB, CONTROLLER_AND_CLIMB }

internal enum class BoardMismatchKind { CONNECTED_BRAND, ACTIVE_BRAND, CONNECTED_MODEL, ACTIVE_LAYOUT, ACTIVE_SIZE }

internal data class BoardPickerPrefill(
    val brand: BoardBrand,
    val layoutId: Long? = null,
    val productSizeId: Int? = null,
    val source: BoardPrefillSource,
)

internal data class BoardConfigurationMismatch(
    val kind: BoardMismatchKind,
    val climbBrand: BoardBrand,
    val expectedBrand: BoardBrand,
    val activeBrand: BoardBrand?,
    val connectedBrand: BoardBrand?,
    val climbLayoutId: Long?,
    val activeLayoutId: Long?,
    val prefill: BoardPickerPrefill,
)

internal data class BoardSendIdentity(
    val climbBrand: BoardBrand,
    val climbLayoutId: Long?,
    val activeBrand: BoardBrand?,
    val activeLayoutId: Long?,
    val activeProductSizeId: Int?,
    val connectedBrand: BoardBrand?,
    val connectedQuantumModel: QuantumBoardModel? = null,
)

internal object BoardMismatchPolicy {
    /** Android `connectedBoardConfigurationMismatch`: a controller connected under another active board. */
    fun connected(
        activeBrand: BoardBrand,
        connectedBrand: BoardBrand?,
        connectedQuantumModel: QuantumBoardModel? = null,
    ): BoardConfigurationMismatch? {
        val controllerBrand = connectedBrand ?: return null
        if (controllerBrand == activeBrand) return null
        return BoardConfigurationMismatch(
            kind = BoardMismatchKind.ACTIVE_BRAND,
            climbBrand = controllerBrand,
            expectedBrand = controllerBrand,
            activeBrand = activeBrand,
            connectedBrand = controllerBrand,
            climbLayoutId = connectedQuantumModel?.layoutId,
            activeLayoutId = null,
            prefill = controllerPrefill(controllerBrand, connectedQuantumModel),
        )
    }

    /** Android `resolveBoardConfigurationMismatch`: controller evidence wins over climb metadata. */
    fun resolve(identity: BoardSendIdentity): BoardConfigurationMismatch? {
        val controllerBrand = identity.connectedBrand
        if (controllerBrand != null && controllerBrand != identity.climbBrand) {
            return identity.mismatch(
                BoardMismatchKind.CONNECTED_BRAND, controllerBrand,
                controllerPrefill(controllerBrand, identity.connectedQuantumModel),
            )
        }
        val expectedBrand = controllerBrand ?: identity.climbBrand
        if (identity.activeBrand != null && identity.activeBrand != expectedBrand) {
            return identity.mismatch(BoardMismatchKind.ACTIVE_BRAND, expectedBrand, identity.prefillForExpected(expectedBrand))
        }
        val verifiedQuantum = identity.connectedQuantumModel
        if (expectedBrand == BoardBrand.QUANTUM && verifiedQuantum != null &&
            identity.climbLayoutId != null && identity.climbLayoutId != verifiedQuantum.layoutId
        ) {
            return identity.mismatch(BoardMismatchKind.CONNECTED_MODEL, expectedBrand, controllerPrefill(expectedBrand, verifiedQuantum))
        }
        val requiredLayout = verifiedQuantum?.layoutId ?: identity.climbLayoutId
        if (requiredLayout != null && identity.activeLayoutId != null && requiredLayout != identity.activeLayoutId) {
            return identity.mismatch(
                BoardMismatchKind.ACTIVE_LAYOUT, expectedBrand,
                BoardPickerPrefill(
                    brand = expectedBrand,
                    layoutId = requiredLayout,
                    productSizeId = verifiedQuantum?.productSizeId?.toInt(),
                    source = if (verifiedQuantum != null) BoardPrefillSource.CONNECTED_CONTROLLER else BoardPrefillSource.CLIMB,
                ),
            )
        }
        return null
    }

    /** Android `boardSizeMismatch`: zero LED overlap proves a wrong size, not which size is installed. */
    fun size(identity: BoardSendIdentity): BoardConfigurationMismatch {
        val brand = identity.connectedBrand ?: identity.climbBrand
        return identity.mismatch(
            BoardMismatchKind.ACTIVE_SIZE, brand,
            BoardPickerPrefill(brand = brand, layoutId = identity.climbLayoutId, productSizeId = null, source = BoardPrefillSource.CLIMB),
        )
    }

    private fun controllerPrefill(brand: BoardBrand, quantumModel: QuantumBoardModel?): BoardPickerPrefill {
        val layoutId = when (brand) {
            BoardBrand.KILTER -> BrowsePreferences.KILTER_ORIGINAL_LAYOUT.toLong()
            BoardBrand.MOONBOARD -> MoonBoardVariant.MOONBOARD_2016.layoutId
            BoardBrand.QUANTUM -> quantumModel?.layoutId
            else -> null
        }
        val productSizeId = when (brand) {
            BoardBrand.KILTER -> BrowsePreferences.KILTER_DEFAULT_SIZE
            BoardBrand.QUANTUM -> quantumModel?.productSizeId?.toInt()
            else -> null
        }
        return BoardPickerPrefill(brand, layoutId, productSizeId, BoardPrefillSource.CONNECTED_CONTROLLER)
    }

    private fun BoardSendIdentity.prefillForExpected(expectedBrand: BoardBrand): BoardPickerPrefill {
        val quantum = connectedQuantumModel.takeIf { expectedBrand == BoardBrand.QUANTUM }
        val fromController = connectedBrand == expectedBrand
        return BoardPickerPrefill(
            brand = expectedBrand,
            layoutId = quantum?.layoutId ?: climbLayoutId.takeUnless { fromController && expectedBrand != climbBrand },
            productSizeId = quantum?.productSizeId?.toInt(),
            source = when {
                quantum != null -> BoardPrefillSource.CONNECTED_CONTROLLER
                fromController && climbLayoutId != null -> BoardPrefillSource.CONTROLLER_AND_CLIMB
                fromController -> BoardPrefillSource.CONNECTED_CONTROLLER
                else -> BoardPrefillSource.CLIMB
            },
        )
    }

    private fun BoardSendIdentity.mismatch(kind: BoardMismatchKind, expectedBrand: BoardBrand, prefill: BoardPickerPrefill) =
        BoardConfigurationMismatch(
            kind = kind,
            climbBrand = climbBrand,
            expectedBrand = expectedBrand,
            activeBrand = activeBrand,
            connectedBrand = connectedBrand,
            climbLayoutId = climbLayoutId,
            activeLayoutId = activeLayoutId,
            prefill = prefill,
        )
}
