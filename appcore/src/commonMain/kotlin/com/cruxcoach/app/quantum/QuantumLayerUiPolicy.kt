package com.cruxcoach.app.quantum

import com.cruxcoach.app.ble.MAX_LAYER_IDENTITIES
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder

// Port of the policy half of Android's ui/board/QuantumLayerUi.kt (no Compose).

/** What one of CruxCoach's stable Quantum controller identities means in the UI. */
internal enum class QuantumLayerVisualState { FREE, PLANNED, SENDING, ON_BOARD, REPLACING, FAILED, UNKNOWN, FOREIGN }

internal data class QuantumLayerSlotUi(
    val slot: Int,
    val visualState: QuantumLayerVisualState,
    /** Colour chosen for the local plan. */
    val plannedColor: Int?,
    /** Colour still reported by the controller, if any. */
    val confirmedColor: Int?,
    val climbName: String?,
    val confirmedClimbName: String?,
    val currentClimb: Boolean,
    val layer: BoardClimbLayer?,
    val externalLayer: ExternalBoardLayer? = null,
)

internal enum class QuantumLayerSuggestionBlock {
    NO_HOLDS, MULTI_FRAME_UNVERIFIED, UNKNOWN_LAYER, HOLD_CONFLICT, BOARD_FULL, NO_COLOR, NO_SLOT,
}

internal data class QuantumLayerUiSummary(
    val slots: List<QuantumLayerSlotUi>,
    val activeCount: Int,
    val foreignCount: Int,
    val foreignUnknownCount: Int,
    val suggestedSlot: Int?,
    val suggestedColor: Int?,
    val suggestionUsesExistingSlot: Boolean,
    val suggestionBlock: QuantumLayerSuggestionBlock?,
    /** Conservative preflight for every currently staged local layer. */
    val sendAllBlock: QuantumLayerSuggestionBlock?,
    /** Only for non-palette colours or a legacy local colour/slot collision. */
    val unplacedExternalLayers: List<ExternalBoardLayer>,
)

/**
 * Keeps controller truth apart from local plans and derives one stable,
 * non-destructive suggestion: this climb's existing slot first, otherwise the
 * first free identity when capacity, colour and known hold overlap allow it.
 * Foreign players use capacity and colour but stay read-only.
 */
internal object QuantumLayerUiPolicy {
    fun summarize(
        state: BoardLayerState,
        currentClimbUuid: String?,
        currentPlacements: Set<Int> = emptySet(),
        maxLayers: Int = MAX_LAYER_IDENTITIES,
    ): QuantumLayerUiSummary {
        val bySlot = state.layers.associateBy { it.slot }
        val externalBySlot = mutableMapOf<Int, ExternalBoardLayer>()
        val unplacedExternal = mutableListOf<ExternalBoardLayer>()
        state.externalLayers.forEach { external ->
            val colorSlot = BoardLayerManager.LAYER_COLORS.indexOf(external.color)
                .takeIf { it >= 0 && it !in bySlot && it !in externalBySlot }
            val displaySlot = colorSlot ?: (0 until maxLayers).firstOrNull { it !in bySlot && it !in externalBySlot }
            if (displaySlot == null) unplacedExternal += external else externalBySlot[displaySlot] = external
        }
        val slots = (0 until maxLayers).map { slot ->
            val layer = bySlot[slot]
            val external = externalBySlot[slot]
            QuantumLayerSlotUi(
                slot = slot,
                visualState = if (layer == null && external != null) QuantumLayerVisualState.FOREIGN else visualState(layer),
                plannedColor = layer?.color ?: external?.color,
                confirmedColor = layer?.confirmedColor ?: external?.color,
                climbName = layer?.climbName ?: external?.climbName ?: external?.routeUuid?.take(8),
                confirmedClimbName = layer?.confirmedClimbName,
                currentClimb = currentClimbUuid != null && layer?.climbUuid == currentClimbUuid,
                layer = layer,
                externalLayer = external,
            )
        }
        val existing = slots.firstOrNull { it.currentClimb }?.slot
        val freeSlot = slots.firstOrNull { it.layer == null && it.externalLayer == null }?.slot
        val candidateSlot = existing ?: freeSlot
        val hasCapacity = candidateSlot != null &&
            (bySlot[candidateSlot]?.confirmedRouteUuid != null || state.occupiedCount < maxLayers)
        val freeColor = candidateSlot?.let { slot ->
            val existingColor = bySlot[slot]?.color
            existingColor?.takeIf { it !in state.reservedLayerColors(slot) }
                ?: BoardLayerManager.LAYER_COLORS.firstOrNull { it !in state.reservedLayerColors(slot) }
        }
        val assessment = candidateSlot?.let { slot ->
            BoardLayerConflictPolicy.assessPlacements(currentPlacements, state.layers, state.externalLayers, slot)
        }
        val knownShared = candidateSlot?.let { slot -> knownSharedHoldCount(state, currentPlacements, slot) } ?: 0
        val block = when {
            currentPlacements.isEmpty() -> QuantumLayerSuggestionBlock.NO_HOLDS
            currentPlacements.size > QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT ->
                QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED
            candidateSlot == null && state.occupiedCount >= maxLayers -> QuantumLayerSuggestionBlock.BOARD_FULL
            candidateSlot == null -> QuantumLayerSuggestionBlock.NO_SLOT
            !hasCapacity -> QuantumLayerSuggestionBlock.BOARD_FULL
            assessment?.unknownLayerCount != 0 -> QuantumLayerSuggestionBlock.UNKNOWN_LAYER
            knownShared != 0 -> QuantumLayerSuggestionBlock.HOLD_CONFLICT
            freeColor == null -> QuantumLayerSuggestionBlock.NO_COLOR
            else -> null
        }
        return QuantumLayerUiSummary(
            slots = slots,
            activeCount = state.occupiedCount,
            foreignCount = state.externalLayers.size,
            foreignUnknownCount = state.externalLayers.count { it.holds == null },
            suggestedSlot = candidateSlot.takeIf { block == null },
            suggestedColor = freeColor.takeIf { block == null },
            suggestionUsesExistingSlot = existing != null && block == null,
            suggestionBlock = block,
            sendAllBlock = state.layers.takeIf { it.isNotEmpty() }?.let { layers ->
                planBlock(state, layers.map(BoardClimbLayer::slot), maxLayers)
            },
            unplacedExternalLayers = unplacedExternal,
        )
    }

    /** Validates a whole local plan against the latest hydrated snapshot without mutating it. */
    fun planBlock(
        state: BoardLayerState,
        slots: List<Int>,
        maxLayers: Int = MAX_LAYER_IDENTITIES,
    ): QuantumLayerSuggestionBlock? {
        val targets = slots.distinct().map { slot ->
            state.layers.firstOrNull { it.slot == slot } ?: return QuantumLayerSuggestionBlock.NO_SLOT
        }
        val newIdentityCount = targets.count { it.confirmedRouteUuid == null }
        if (state.occupiedCount + newIdentityCount > maxLayers) return QuantumLayerSuggestionBlock.BOARD_FULL

        val plannedPlacements = mutableSetOf<Int>()
        for (target in targets) {
            if (target.holds.isEmpty()) return QuantumLayerSuggestionBlock.NO_HOLDS
            if (target.holds.size > QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT) {
                return QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED
            }
            val placements = target.holds.mapTo(mutableSetOf(), BoardHold::placementId)
            if (placements.any { it in plannedPlacements }) return QuantumLayerSuggestionBlock.HOLD_CONFLICT
            plannedPlacements += placements
            val assessment = BoardLayerConflictPolicy.assess(target.holds, state.layers, state.externalLayers, target.slot)
            if (assessment.unknownLayerCount > 0) return QuantumLayerSuggestionBlock.UNKNOWN_LAYER
            if (assessment.sharedHoldCount > 0) return QuantumLayerSuggestionBlock.HOLD_CONFLICT
            if (target.color in state.reservedLayerColors(target.slot)) return QuantumLayerSuggestionBlock.NO_COLOR
        }
        return null
    }

    /** Known diode conflicts for planning, both sides of a staged replacement included. */
    fun knownSharedHoldCount(state: BoardLayerState, candidate: Set<Int>, replacingSlot: Int?): Int {
        val occupied = buildSet {
            state.layers.filterNot { it.slot == replacingSlot }.forEach { layer ->
                layer.holds.mapTo(this, BoardHold::placementId)
                layer.confirmedHolds?.mapTo(this, BoardHold::placementId)
            }
            state.externalLayers.forEach { layer -> layer.holds?.mapTo(this, BoardHold::placementId) }
        }
        return candidate.count { it in occupied }
    }

    private fun visualState(layer: BoardClimbLayer?): QuantumLayerVisualState = when {
        layer == null -> QuantumLayerVisualState.FREE
        layer.status == BoardLayerStatus.SENDING -> QuantumLayerVisualState.SENDING
        layer.status == BoardLayerStatus.FAILED -> QuantumLayerVisualState.FAILED
        layer.confirmedRouteUuid != null &&
            (!layer.confirmedRouteUuid.equals(layer.routeUuid, ignoreCase = true) || layer.confirmedColor != layer.color) ->
            QuantumLayerVisualState.REPLACING
        layer.confirmedRouteUuid != null && !layer.controllerDetailsKnown -> QuantumLayerVisualState.UNKNOWN
        layer.confirmedRouteUuid != null && layer.status == BoardLayerStatus.CONFIRMED -> QuantumLayerVisualState.ON_BOARD
        else -> QuantumLayerVisualState.PLANNED
    }
}
