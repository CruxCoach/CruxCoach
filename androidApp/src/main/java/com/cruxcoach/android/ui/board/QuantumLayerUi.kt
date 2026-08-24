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
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.reservedLayerColors
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
    val color: Int?,
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
                color = layer?.confirmedColor ?: layer?.color,
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
        val block = when {
            currentPlacements.isEmpty() -> QuantumLayerSuggestionBlock.NO_HOLDS
            currentPlacements.size > com.cruxcoach.domain.board.QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT ->
                QuantumLayerSuggestionBlock.MULTI_FRAME_UNVERIFIED
            candidateSlot == null -> QuantumLayerSuggestionBlock.NO_SLOT
            !hasCapacity -> QuantumLayerSuggestionBlock.BOARD_FULL
            assessment?.unknownLayerCount != 0 -> QuantumLayerSuggestionBlock.UNKNOWN_LAYER
            assessment?.sharedHoldCount?.let { it != 0 } == true ->
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
        )
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
                        stringResource(
                            R.string.board_layers_occupied,
                            summary.activeCount,
                            summary.slots.size,
                        ),
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
        }
    }
    if (onOpen != null) {
        Surface(
            onClick = onOpen,
            modifier = modifier
                .fillMaxWidth()
                .testTag(testTag)
                .semantics { contentDescription = openLabel },
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
    val statusLabel = stringResource(quantumLayerStatusResource(slot.visualState))
    val shortStatusLabel = stringResource(quantumLayerShortStatusResource(slot.visualState))
    val colorLabel = slot.color?.let { stringResource(boardLayerColorName(it)) }
    val description = buildString {
        append(stringResource(R.string.board_layer_number, slot.slot + 1))
        append(": ")
        append(statusLabel)
        colorLabel?.let { append(", "); append(it) }
        slot.climbName?.let { append(", "); append(it) }
        if (slot.layer?.confirmedRouteUuid != null &&
            slot.visualState in setOf(QuantumLayerVisualState.REPLACING, QuantumLayerVisualState.FAILED)
        ) {
            slot.confirmedClimbName?.let { append(". "); append(
                stringResource(R.string.quantum_layer_confirmed_previous, it)
            ) }
        }
    }
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
                Modifier.size(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (slot.visualState == QuantumLayerVisualState.SENDING) {
                    CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(9.dp))
                } else {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = CircleShape,
                        color = slot.color?.let(::Color)
                            ?: MaterialTheme.colorScheme.outlineVariant,
                    ) {}
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
                )
            }
        }
    }
}
