package com.cruxcoach.android.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.android.ble.reservedLayerColors
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/** What one of CruxCoach's stable Quantum controller identities means in the UI. */
internal enum class QuantumLayerVisualState {
    FREE,
    PLANNED,
    SENDING,
    ON_BOARD,
    REPLACING,
    FAILED,
    UNKNOWN,
}

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
)

internal enum class QuantumLayerSuggestionBlock {
    NO_HOLDS,
    MULTI_FRAME_UNVERIFIED,
    UNKNOWN_LAYER,
    HOLD_CONFLICT,
    BOARD_FULL,
    NO_COLOR,
    NO_SLOT,
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
)

/**
 * Presentation-only Quantum rack policy.
 *
 * It keeps controller truth separate from a local plan and derives a stable,
 * non-destructive suggestion: an existing slot for this climb first, otherwise
 * the first genuinely free local identity when capacity, colour and known local
 * hold overlap permit it. Foreign users consume capacity and colour but are
 * never turned into selectable slots.
 */
internal object QuantumLayerUiPolicy {
    fun summarize(
        state: BoardLayerState,
        currentClimbUuid: String?,
        currentPlacements: Set<Int> = emptySet(),
        maxLayers: Int = BoardLayerManager.MAX_LAYER_IDENTITIES,
    ): QuantumLayerUiSummary {
        val bySlot = state.layers.associateBy { it.slot }
        val slots = (0 until maxLayers).map { slot ->
            val layer = bySlot[slot]
            QuantumLayerSlotUi(
                slot = slot,
                visualState = visualState(layer),
                plannedColor = layer?.color,
                confirmedColor = layer?.confirmedColor,
                climbName = layer?.climbName,
                confirmedClimbName = layer?.confirmedClimbName,
                currentClimb = currentClimbUuid != null && layer?.climbUuid == currentClimbUuid,
                layer = layer,
            )
        }
        val existing = slots.firstOrNull { it.currentClimb }?.slot
        val freeSlot = slots.firstOrNull { it.layer == null }?.slot
        val candidateSlot = existing ?: freeSlot
        val hasCapacity = candidateSlot != null &&
            (bySlot[candidateSlot]?.confirmedRouteUuid != null || state.occupiedCount < maxLayers)
        val freeColor = candidateSlot?.let { slot ->
            val existingColor = bySlot[slot]?.color
            existingColor?.takeIf { it !in state.reservedLayerColors(slot) }
                ?: BoardLayerManager.LAYER_COLORS.firstOrNull {
                    it !in state.reservedLayerColors(slot)
                }
        }
        val assessment = candidateSlot?.let { slot ->
            BoardLayerConflictPolicy.assessPlacements(
                candidate = currentPlacements,
                activeLayers = state.layers,
                externalLayers = state.externalLayers,
                replacingSlot = slot,
            )
        }
        val knownSharedHoldCount = candidateSlot?.let { slot ->
            knownSharedHoldCount(
                state = state,
                candidate = currentPlacements,
                replacingSlot = slot,
            )
        } ?: 0
        val block = when {
            currentPlacements.isEmpty() -> QuantumLayerSuggestionBlock.NO_HOLDS
            currentPlacements.size > com.cruxcoach.domain.board.QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT ->
                QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED
            candidateSlot == null -> QuantumLayerSuggestionBlock.NO_SLOT
            !hasCapacity -> QuantumLayerSuggestionBlock.BOARD_FULL
            assessment?.unknownLayerCount != 0 -> QuantumLayerSuggestionBlock.UNKNOWN_LAYER
            knownSharedHoldCount != 0 ->
                QuantumLayerSuggestionBlock.HOLD_CONFLICT
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
        )
    }

    /**
     * Validate the complete local plan against the latest hydrated controller
     * snapshot without mutating it. The BLE path repeats this after an
     * authoritative refresh; the UI uses it so an obviously unsafe "Light all"
     * action is not presented as ready.
     */
    fun planBlock(
        state: BoardLayerState,
        slots: List<Int>,
        maxLayers: Int = BoardLayerManager.MAX_LAYER_IDENTITIES,
    ): QuantumLayerSuggestionBlock? {
        val targets = slots.distinct().map { slot ->
            state.layers.firstOrNull { it.slot == slot }
                ?: return QuantumLayerSuggestionBlock.NO_SLOT
        }
        val newIdentityCount = targets.count { it.confirmedRouteUuid == null }
        if (state.occupiedCount + newIdentityCount > maxLayers) {
            return QuantumLayerSuggestionBlock.BOARD_FULL
        }

        val plannedPlacements = mutableSetOf<Int>()
        for (target in targets) {
            if (target.holds.isEmpty()) return QuantumLayerSuggestionBlock.NO_HOLDS
            if (target.holds.size >
                com.cruxcoach.domain.board.QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT
            ) return QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED

            val placements = target.holds.mapTo(mutableSetOf(), BoardHold::placementId)
            if (placements.any { it in plannedPlacements }) {
                return QuantumLayerSuggestionBlock.HOLD_CONFLICT
            }
            plannedPlacements += placements

            val assessment = BoardLayerConflictPolicy.assess(
                candidate = target.holds,
                activeLayers = state.layers,
                externalLayers = state.externalLayers,
                replacingSlot = target.slot,
            )
            if (assessment.unknownLayerCount > 0) {
                return QuantumLayerSuggestionBlock.UNKNOWN_LAYER
            }
            if (assessment.sharedHoldCount > 0) {
                return QuantumLayerSuggestionBlock.HOLD_CONFLICT
            }
            if (target.color in state.reservedLayerColors(target.slot)) {
                return QuantumLayerSuggestionBlock.NO_COLOR
            }
        }
        return null
    }

    /**
     * Known diode conflicts for planning UI, including both sides of a staged
     * replacement. [BoardLayerConflictPolicy] intentionally models physical
     * controller occupancy, so an unsent preview is absent from that answer.
     * The rack has a second responsibility: it must not recommend or describe
     * two local plans as compatible when Send All will later reject them.
     */
    fun knownSharedHoldCount(
        state: BoardLayerState,
        candidate: Set<Int>,
        replacingSlot: Int?,
    ): Int {
        val occupied = buildSet {
            state.layers.filterNot { it.slot == replacingSlot }.forEach { layer ->
                layer.holds.mapTo(this, BoardHold::placementId)
                layer.confirmedHolds?.mapTo(this, BoardHold::placementId)
            }
            state.externalLayers.forEach { layer ->
                layer.holds?.mapTo(this, BoardHold::placementId)
            }
        }
        return candidate.count { it in occupied }
    }

    private fun visualState(layer: BoardClimbLayer?): QuantumLayerVisualState = when {
        layer == null -> QuantumLayerVisualState.FREE
        layer.status == BoardLayerStatus.SENDING -> QuantumLayerVisualState.SENDING
        layer.status == BoardLayerStatus.FAILED -> QuantumLayerVisualState.FAILED
        layer.confirmedRouteUuid != null &&
            (!layer.confirmedRouteUuid.equals(layer.routeUuid, ignoreCase = true) ||
                layer.confirmedColor != layer.color) ->
            QuantumLayerVisualState.REPLACING
        layer.confirmedRouteUuid != null && !layer.controllerDetailsKnown ->
            QuantumLayerVisualState.UNKNOWN
        layer.confirmedRouteUuid != null && layer.status == BoardLayerStatus.CONFIRMED ->
            QuantumLayerVisualState.ON_BOARD
        else -> QuantumLayerVisualState.PLANNED
    }
}

@StringRes
internal fun quantumLayerSuggestionBlockResource(block: QuantumLayerSuggestionBlock): Int = when (block) {
    QuantumLayerSuggestionBlock.NO_HOLDS -> R.string.board_send_error_no_led_data
    QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED ->
        R.string.board_layer_error_multi_frame_unverified
    QuantumLayerSuggestionBlock.UNKNOWN_LAYER -> R.string.board_layer_error_external_unknown
    QuantumLayerSuggestionBlock.HOLD_CONFLICT -> R.string.board_layer_error_shared_hold
    QuantumLayerSuggestionBlock.BOARD_FULL -> R.string.board_layer_error_board_full
    QuantumLayerSuggestionBlock.NO_COLOR -> R.string.board_layer_error_color_taken
    QuantumLayerSuggestionBlock.NO_SLOT -> R.string.board_layer_error_all_assigned
}

@StringRes
internal fun quantumLayerStatusResource(state: QuantumLayerVisualState): Int = when (state) {
    QuantumLayerVisualState.FREE -> R.string.quantum_layer_status_free
    QuantumLayerVisualState.PLANNED -> R.string.quantum_layer_status_planned
    QuantumLayerVisualState.SENDING -> R.string.quantum_layer_status_sending
    QuantumLayerVisualState.ON_BOARD -> R.string.quantum_layer_status_on_board
    QuantumLayerVisualState.REPLACING -> R.string.quantum_layer_status_replacing
    QuantumLayerVisualState.FAILED -> R.string.quantum_layer_status_failed
    QuantumLayerVisualState.UNKNOWN -> R.string.quantum_layer_status_unknown
}

@StringRes
internal fun quantumLayerShortStatusResource(state: QuantumLayerVisualState): Int = when (state) {
    QuantumLayerVisualState.FREE -> R.string.quantum_layer_status_short_free
    QuantumLayerVisualState.PLANNED -> R.string.quantum_layer_status_short_planned
    QuantumLayerVisualState.SENDING -> R.string.quantum_layer_status_short_sending
    QuantumLayerVisualState.ON_BOARD -> R.string.quantum_layer_status_short_on_board
    QuantumLayerVisualState.REPLACING -> R.string.quantum_layer_status_short_replacing
    QuantumLayerVisualState.FAILED -> R.string.quantum_layer_status_short_failed
    QuantumLayerVisualState.UNKNOWN -> R.string.quantum_layer_status_short_unknown
}

/** Compact rack used above the wall, in the playlist player and in its banner. */
@Composable
internal fun QuantumLayerStatusStrip(
    state: BoardLayerState,
    currentClimbUuid: String? = null,
    currentPlacements: Set<Int> = emptySet(),
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    testTag: String = "quantum_layer_status_strip",
) {
    val summary = QuantumLayerUiPolicy.summarize(state, currentClimbUuid, currentPlacements)
    val openLabel = stringResource(R.string.board_layers_open)
    val wallStatus = stringResource(
        R.string.quantum_wall_occupancy,
        summary.activeCount,
        summary.slots.size,
    )
    val slotDescriptions = summary.slots.map { quantumLayerSlotDescription(it) }
    val foreignDescriptions = state.externalLayers.map { quantumForeignLayerDescription(it) }
    val rackDescription = buildList {
        add(openLabel)
        add(wallStatus)
        addAll(slotDescriptions)
        addAll(foreignDescriptions)
    }.joinToString(". ")
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.quantum_wall_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (summary.foreignCount > 0) {
                        Text(
                            if (summary.foreignUnknownCount > 0) {
                                stringResource(
                                    R.string.quantum_wall_foreign_unknown_short,
                                    summary.foreignCount,
                                    summary.foreignUnknownCount,
                                )
                            } else {
                                stringResource(
                                    R.string.quantum_wall_foreign_known_short,
                                    summary.foreignCount,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        wallStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                summary.slots.forEach { slot ->
                    QuantumLayerSlotChip(slot, Modifier.weight(1f))
                }
            }
            if (state.externalLayers.isNotEmpty()) {
                QuantumForeignStatusRow(state.externalLayers)
            }
        }
    }
    if (onOpen != null) {
        Surface(
            onClick = onOpen,
            modifier = modifier
                .fillMaxWidth()
                .testTag(testTag)
                // The rack is one tap target. Give that merged target the wall
                // and per-layer answer explicitly while retaining Surface's
                // click action and unmerged child descriptions.
                .semantics(mergeDescendants = true) { contentDescription = rackDescription },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) { content() }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth().testTag(testTag),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) { content() }
    }
}

@Composable
private fun QuantumLayerSlotChip(slot: QuantumLayerSlotUi, modifier: Modifier) {
    val shortStatusLabel = stringResource(quantumLayerShortStatusResource(slot.visualState))
    val description = quantumLayerSlotDescription(slot)
    val displayColor = when (slot.visualState) {
        QuantumLayerVisualState.ON_BOARD, QuantumLayerVisualState.UNKNOWN ->
            slot.confirmedColor ?: slot.plannedColor
        else -> slot.plannedColor ?: slot.confirmedColor
    }
    val showLiveColor = slot.confirmedColor != null &&
        slot.layer?.confirmedRouteUuid != null &&
        slot.visualState in setOf(QuantumLayerVisualState.REPLACING, QuantumLayerVisualState.FAILED)
    val borderColor = when (slot.visualState) {
        QuantumLayerVisualState.ON_BOARD -> SuccessGreen
        QuantumLayerVisualState.PLANNED, QuantumLayerVisualState.REPLACING,
        QuantumLayerVisualState.SENDING -> OrangeAccent
        QuantumLayerVisualState.FAILED -> ErrorRed
        QuantumLayerVisualState.UNKNOWN -> MaterialTheme.colorScheme.outline
        QuantumLayerVisualState.FREE -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier
            .testTag("quantum_layer_status_${slot.slot + 1}")
            .clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (slot.currentClimb) 2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (slot.visualState == QuantumLayerVisualState.SENDING) {
                    CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
                } else {
                    Surface(
                        modifier = Modifier
                            .size(if (showLiveColor) 10.dp else 9.dp)
                            .align(if (showLiveColor) Alignment.TopStart else Alignment.Center),
                        shape = CircleShape,
                        color = displayColor?.let(::Color)
                            ?: MaterialTheme.colorScheme.outlineVariant,
                    ) {}
                    if (showLiveColor) {
                        Surface(
                            modifier = Modifier.size(7.dp).align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = Color(requireNotNull(slot.confirmedColor)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
                        ) {}
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.quantum_layer_short, slot.slot + 1),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (slot.visualState) {
                        QuantumLayerVisualState.FAILED -> ErrorRed
                        QuantumLayerVisualState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        QuantumLayerVisualState.FREE -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    shortStatusLabel,
                    modifier = Modifier.testTag("quantum_layer_visible_state_${slot.slot + 1}"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = borderColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun quantumLayerSlotDescription(slot: QuantumLayerSlotUi): String {
    val statusLabel = stringResource(quantumLayerStatusResource(slot.visualState))
    val plannedColorLabel = slot.plannedColor?.let { stringResource(boardLayerColorName(it)) }
    val liveColorLabel = slot.confirmedColor?.let { stringResource(boardLayerColorName(it)) }
    return buildString {
        append(stringResource(R.string.board_layer_number, slot.slot + 1))
        append(": ")
        append(statusLabel)
        if (slot.visualState in setOf(QuantumLayerVisualState.REPLACING, QuantumLayerVisualState.FAILED)) {
            plannedColorLabel?.let {
                append(", ")
                append(stringResource(R.string.quantum_layer_planned_color, it))
            }
        } else {
            (liveColorLabel ?: plannedColorLabel)?.let { append(", "); append(it) }
        }
        slot.climbName?.let { append(", "); append(it) }
        if (slot.layer?.confirmedRouteUuid != null &&
            slot.visualState in setOf(QuantumLayerVisualState.REPLACING, QuantumLayerVisualState.FAILED)
        ) {
            slot.confirmedClimbName?.let { name ->
                append(". ")
                append(
                    if (liveColorLabel != null) {
                        stringResource(
                            R.string.quantum_layer_confirmed_previous_with_color,
                            name,
                            liveColorLabel,
                        )
                    } else {
                        stringResource(R.string.quantum_layer_confirmed_previous, name)
                    },
                )
            }
        }
    }
}

@Composable
private fun quantumForeignLayerDescription(layer: ExternalBoardLayer): String {
    val route = layer.climbName ?: layer.routeUuid.take(8)
    val color = stringResource(boardLayerColorName(layer.color))
    return stringResource(
        if (layer.holds == null) R.string.quantum_layer_foreign_unknown
        else R.string.quantum_layer_foreign_known,
        route,
        color,
    )
}

@Composable
private fun QuantumForeignStatusRow(layers: List<ExternalBoardLayer>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quantum_layer_foreign_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            stringResource(R.string.quantum_wall_foreign_strip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        layers.take(BoardLayerManager.MAX_LAYER_IDENTITIES).forEachIndexed { index, layer ->
            val description = quantumForeignLayerDescription(layer)
            Surface(
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (layer.holds == null) OrangeAccent else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Surface(Modifier.size(8.dp), CircleShape, color = Color(layer.color)) {}
                    Text(
                        buildString {
                            append("O")
                            append(index + 1)
                            if (layer.holds == null) append("?")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
