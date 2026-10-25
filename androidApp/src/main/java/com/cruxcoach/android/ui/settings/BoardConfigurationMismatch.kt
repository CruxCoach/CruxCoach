package com.cruxcoach.android.ui.settings

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.ShinyWallName
import com.cruxcoach.domain.board.ShinyWallVariant

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

/** The variant a connected Shiny Wall's advertised name suggests, if any. */
fun shinyWallVariantHint(connectedBrand: BoardBrand?, connectedName: String?): ShinyWallVariant? =
    connectedName?.takeIf { connectedBrand == BoardBrand.SHINYWALL }
        ?.let { ShinyWallName.parse(it)?.variantHint }

/** True for the suggestion raised only by a Shiny Wall controller's name. */
val BoardConfigurationMismatch.isShinyWallVariantSuggestion: Boolean
    get() = kind == BoardMismatchKind.CONNECTED_MODEL && connectedBrand == BoardBrand.SHINYWALL

/** Build the guided correction shown as soon as a controller connects under
 * a different active board. Quantum proves its exact model over BLE; Kilter
 * and MoonBoard land on their most useful common configurations. */
fun connectedBoardConfigurationMismatch(
    activeBrand: BoardBrand,
    connectedBrand: BoardBrand?,
    connectedQuantumModel: QuantumBoardModel? = null,
    activeLayoutId: Long? = null,
    /** Variant a Shiny Wall controller's advertised name suggests; a hint only,
     *  so a mismatch it raises is an editable, dismissible suggestion. */
    connectedShinyVariantHint: ShinyWallVariant? = null,
    /** The user already answered this controller's variant suggestion. Gyms
     *  name their controllers and the name does not always match the wall (Max
     *  walls named `STP_` exist), so it is asked once per controller, not on
     *  every connect. A brand switch still uses the hint as its prefill. */
    shinyVariantSuggestionAnswered: Boolean = false,
): BoardConfigurationMismatch? {
    val controllerBrand = connectedBrand ?: return null
    if (controllerBrand == activeBrand) {
        if (shinyVariantSuggestionAnswered) return null
        val hint = connectedShinyVariantHint?.takeIf { controllerBrand == BoardBrand.SHINYWALL }
            ?: return null
        if (activeLayoutId == null || hint.layoutId == activeLayoutId) return null
        return BoardConfigurationMismatch(
            kind = BoardMismatchKind.CONNECTED_MODEL,
            climbBrand = controllerBrand,
            expectedBrand = controllerBrand,
            activeBrand = activeBrand,
            connectedBrand = controllerBrand,
            climbLayoutId = hint.layoutId,
            activeLayoutId = activeLayoutId,
            prefill = controllerPrefill(controllerBrand, null, hint),
        )
    }
    return BoardConfigurationMismatch(
        kind = BoardMismatchKind.ACTIVE_BRAND,
        climbBrand = controllerBrand,
        expectedBrand = controllerBrand,
        activeBrand = activeBrand,
        connectedBrand = controllerBrand,
        climbLayoutId = connectedQuantumModel?.layoutId ?: connectedShinyVariantHint?.layoutId,
        activeLayoutId = null,
        prefill = controllerPrefill(controllerBrand, connectedQuantumModel, connectedShinyVariantHint),
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
    shinyVariantHint: ShinyWallVariant? = null,
): BoardPickerPrefill {
    val layoutId = when (brand) {
        BoardBrand.KILTER -> BoardConstants.KILTER_ORIGINAL_LAYOUT.toLong()
        BoardBrand.MOONBOARD -> MoonBoardVariant.MOONBOARD_2016.layoutId
        BoardBrand.QUANTUM -> quantumModel?.layoutId
        // The advertised name only hints at the variant; without a hint
        // Standard, the most common Shiny Wall, is the editable default.
        BoardBrand.SHINYWALL -> (shinyVariantHint ?: ShinyWallVariant.ST1).layoutId
        else -> null
    }
    val productSizeId = when (brand) {
        BoardBrand.KILTER -> BoardConstants.KILTER_DEFAULT_SIZE
        BoardBrand.QUANTUM -> quantumModel?.productSizeId?.toInt()
        BoardBrand.SHINYWALL -> (shinyVariantHint ?: ShinyWallVariant.ST1).productSizeId.toInt()
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

data class BoardSettingsCard(val brand: BoardBrand, val isActive: Boolean)

fun boardSettingsCards(activeBrand: BoardBrand): List<BoardSettingsCard> =
    listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD).map { BoardSettingsCard(it, it == activeBrand) }
