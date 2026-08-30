package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel

/** Information used to guide mismatch recovery. Exact controller evidence is
 * preferred; brands whose BLE identity cannot expose a physical variant use a
 * documented, common landing choice that the user can still change. */
data class BoardPickerPrefill(
    val brand: BoardBrand,
    val layoutId: Long? = null,
    val productSizeId: Int? = null,
    val source: BoardPickerPrefillSource,
)

enum class BoardPickerPrefillSource { CONNECTED_CONTROLLER, CLIMB, CONTROLLER_AND_CLIMB }

enum class BoardMismatchKind {
    CONNECTED_BRAND,
    ACTIVE_BRAND,
    CONNECTED_MODEL,
    ACTIVE_LAYOUT,
    ACTIVE_SIZE,
}

/** Typed explanation shared by detail delivery and playlist playback. */
data class BoardConfigurationMismatch(
    val kind: BoardMismatchKind,
    val climbBrand: BoardBrand,
    val expectedBrand: BoardBrand,
    val activeBrand: BoardBrand?,
    val connectedBrand: BoardBrand?,
    val climbLayoutId: Long?,
    val activeLayoutId: Long?,
    val prefill: BoardPickerPrefill,
)

data class BoardSendIdentity(
    val climbBrand: BoardBrand,
    val climbLayoutId: Long?,
    val activeBrand: BoardBrand?,
    val activeLayoutId: Long?,
    val activeProductSizeId: Int?,
    val connectedBrand: BoardBrand?,
    val connectedQuantumModel: QuantumBoardModel? = null,
)

/** Build the guided correction shown as soon as a controller connects under
 * a different active board. Quantum proves its exact model over BLE; Kilter
 * and MoonBoard land on their most useful common configurations. */
fun connectedBoardConfigurationMismatch(
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

/**
 * Resolve all identity mismatches that can be proven before an LED-map lookup.
 * Controller evidence wins over climb metadata. In particular, a Quantum
 * controller's verified model is safe to preselect. Generic Kilter and
 * MoonBoard BLE names prove only the brand, so their detail choice is a
 * user-editable default rather than claimed controller evidence.
 */
fun resolveBoardConfigurationMismatch(
    identity: BoardSendIdentity,
): BoardConfigurationMismatch? {
    val controllerBrand = identity.connectedBrand
    if (controllerBrand != null && controllerBrand != identity.climbBrand) {
        return identity.mismatch(
            kind = BoardMismatchKind.CONNECTED_BRAND,
            expectedBrand = controllerBrand,
            prefill = controllerPrefill(controllerBrand, identity.connectedQuantumModel),
        )
    }

    val expectedBrand = controllerBrand ?: identity.climbBrand
    if (identity.activeBrand != null && identity.activeBrand != expectedBrand) {
        return identity.mismatch(
            kind = BoardMismatchKind.ACTIVE_BRAND,
            expectedBrand = expectedBrand,
            prefill = identity.prefillForExpected(expectedBrand),
        )
    }

    val verifiedQuantum = identity.connectedQuantumModel
    if (expectedBrand == BoardBrand.QUANTUM && verifiedQuantum != null &&
        identity.climbLayoutId != null && identity.climbLayoutId != verifiedQuantum.layoutId
    ) {
        return identity.mismatch(
            kind = BoardMismatchKind.CONNECTED_MODEL,
            expectedBrand = expectedBrand,
            prefill = controllerPrefill(expectedBrand, verifiedQuantum),
        )
    }

    val requiredLayout = verifiedQuantum?.layoutId ?: identity.climbLayoutId
    if (requiredLayout != null && identity.activeLayoutId != null &&
        requiredLayout != identity.activeLayoutId
    ) {
        return identity.mismatch(
            kind = BoardMismatchKind.ACTIVE_LAYOUT,
            expectedBrand = expectedBrand,
            prefill = BoardPickerPrefill(
                brand = expectedBrand,
                layoutId = requiredLayout,
                productSizeId = verifiedQuantum?.productSizeId?.toInt(),
                source = if (verifiedQuantum != null) {
                    BoardPickerPrefillSource.CONNECTED_CONTROLLER
                } else {
                    BoardPickerPrefillSource.CLIMB
                },
            ),
        )
    }
    return null
}

/** A zero-overlap LED map proves a size/layout mismatch, but not which size is
 * physically installed. Preserve that uncertainty for an explicit choice. */
fun boardSizeMismatch(identity: BoardSendIdentity): BoardConfigurationMismatch =
    identity.mismatch(
        kind = BoardMismatchKind.ACTIVE_SIZE,
        expectedBrand = identity.connectedBrand ?: identity.climbBrand,
        prefill = BoardPickerPrefill(
            brand = identity.connectedBrand ?: identity.climbBrand,
            layoutId = identity.climbLayoutId,
            productSizeId = null,
            source = BoardPickerPrefillSource.CLIMB,
        ),
    )

private fun controllerPrefill(
    brand: BoardBrand,
    quantumModel: QuantumBoardModel?,
): BoardPickerPrefill {
    val layoutId = when (brand) {
        BoardBrand.KILTER -> BoardConstants.KILTER_ORIGINAL_LAYOUT.toLong()
        BoardBrand.MOONBOARD -> MoonBoardVariant.MOONBOARD_2016.layoutId
        BoardBrand.QUANTUM -> quantumModel?.layoutId
        else -> null
    }
    val productSizeId = when (brand) {
        BoardBrand.KILTER -> BoardConstants.KILTER_DEFAULT_SIZE
        BoardBrand.QUANTUM -> quantumModel?.productSizeId?.toInt()
        else -> null
    }
    return BoardPickerPrefill(
        brand = brand,
        layoutId = layoutId,
        productSizeId = productSizeId,
        source = BoardPickerPrefillSource.CONNECTED_CONTROLLER,
    )
}

private fun BoardSendIdentity.prefillForExpected(expectedBrand: BoardBrand): BoardPickerPrefill {
    val quantum = connectedQuantumModel.takeIf { expectedBrand == BoardBrand.QUANTUM }
    val fromController = connectedBrand == expectedBrand
    return BoardPickerPrefill(
        brand = expectedBrand,
        layoutId = quantum?.layoutId ?: climbLayoutId.takeUnless { fromController && expectedBrand != climbBrand },
        productSizeId = quantum?.productSizeId?.toInt(),
        source = when {
            quantum != null -> BoardPickerPrefillSource.CONNECTED_CONTROLLER
            fromController && climbLayoutId != null -> BoardPickerPrefillSource.CONTROLLER_AND_CLIMB
            fromController -> BoardPickerPrefillSource.CONNECTED_CONTROLLER
            else -> BoardPickerPrefillSource.CLIMB
        },
    )
}

private fun BoardSendIdentity.mismatch(
    kind: BoardMismatchKind,
    expectedBrand: BoardBrand,
    prefill: BoardPickerPrefill,
) = BoardConfigurationMismatch(
    kind = kind,
    climbBrand = climbBrand,
    expectedBrand = expectedBrand,
    activeBrand = activeBrand,
    connectedBrand = connectedBrand,
    climbLayoutId = climbLayoutId,
    activeLayoutId = activeLayoutId,
    prefill = prefill,
)
