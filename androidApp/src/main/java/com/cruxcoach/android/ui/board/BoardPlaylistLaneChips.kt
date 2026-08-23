package com.cruxcoach.android.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.domain.board.QuantumLaneBadge
import com.cruxcoach.domain.board.QuantumLaneBadgeKind
import com.cruxcoach.domain.board.QuantumLaneEligibility
import com.cruxcoach.domain.board.QuantumLaneSource

/**
 * A row's four lane chips.
 *
 * Number and symbol, never colour alone. Two of the four Quantum protocol
 * colours are hard to tell apart even for people who see all of them, and a
 * list of forty climbs has no room for a sentence per lane — so the sentence
 * lives in the content description and the chip carries `L2 ·1`.
 *
 * Tapping a chip plans; it does not light. Planning is what somebody does
 * while looking at a busy wall and working out what can still go on it, and
 * the lamp stays the one action in the whole screen that changes a diode.
 */
@Composable
internal fun BoardPlaylistLaneChips(
    lanes: BoardPlaylistRowLanes,
    /** False for a device that may read the rack but not write to it. */
    interactive: Boolean,
    onAssign: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lanes.badges.isEmpty()) return
    Row(
        modifier = modifier.testTag("board_playlist_lane_chips"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lanes.badges.forEach { badge ->
            LaneChip(
                badge = badge,
                assigned = lanes.assignedLane == badge.lane,
                // With one eligible lane there is one tick and no question to
                // ask. With several, the recommended one is marked and the
                // choice stays with the person.
                suggested = lanes.assignedLane == null &&
                    lanes.suggestedLane == badge.lane &&
                    lanes.eligibleLanes.size > 1,
                interactive = interactive,
                onAssign = { onAssign(badge.lane) },
            )
        }
    }
}

@Composable
private fun LaneChip(
    badge: QuantumLaneBadge,
    assigned: Boolean,
    suggested: Boolean,
    interactive: Boolean,
    onAssign: () -> Unit,
) {
    val tint = laneChipTint(badge.kind)
    val suggestedSuffix = stringResource(R.string.board_lane_cd_suggested)
    val unassignSuffix = stringResource(R.string.board_lane_unassign)
    val description = laneChipDescription(badge) + when {
        // Tapping the chosen lane again is the way back to "wherever the
        // group's current belongs"; without saying so it is an invisible door.
        assigned -> ". " + unassignSuffix
        suggested -> ". " + suggestedSuffix
        else -> ""
    }
    val label = badge.label
    Surface(
        onClick = onAssign,
        enabled = interactive,
        shape = RoundedCornerShape(6.dp),
        color = when (badge.kind) {
            QuantumLaneBadgeKind.ON_BOARD -> SuccessGreen.copy(alpha = 0.24f)
            QuantumLaneBadgeKind.PLANNED -> OrangeAccent.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surface
        },
        // The preference is drawn as a ring rather than a fill so it can never
        // be mistaken for the confirmed state next to it.
        border = BorderStroke(
            if (assigned || suggested) 2.dp else 1.dp,
            when {
                assigned -> OrangeAccent
                // Lighter than a decision, heavier than nothing: a
                // recommendation somebody may ignore.
                suggested -> OrangeAccent.copy(alpha = 0.55f)
                else -> tint
            },
        ),
        modifier = Modifier
            .testTag("board_playlist_lane_chip_${badge.lane + 1}")
            // Described, not cleared: the chip is a control, and a screen
            // reader needs to know it is one and whether it can be used. Only
            // the four-character label below is suppressed, because "L2
            // middle-dot 1" read aloud is noise in front of the sentence.
            .semantics { contentDescription = description },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .clearAndSetSemantics {},
        )
    }
}

private fun laneChipTint(kind: QuantumLaneBadgeKind): Color = when (kind) {
    QuantumLaneBadgeKind.ON_BOARD -> SuccessGreen
    QuantumLaneBadgeKind.PLANNED, QuantumLaneBadgeKind.SENDING -> OrangeAccent
    QuantumLaneBadgeKind.COMPATIBLE -> SuccessGreen
    QuantumLaneBadgeKind.NEAR, QuantumLaneBadgeKind.CONFLICT -> WarningYellow
    QuantumLaneBadgeKind.UNKNOWN -> Color(0xFF9E9E9E)
    QuantumLaneBadgeKind.BLOCKED -> ErrorRed
}

/**
 * The whole sentence, for the people who need it.
 *
 * The chip is four characters wide; this is where "one hold in the way, and
 * the board will still refuse it" actually gets said.
 */
@Composable
private fun laneChipDescription(badge: QuantumLaneBadge): String {
    val lane = badge.lane + 1
    val conflicts = badge.conflictingLanes.joinToString(", ") { "L${it + 1}" }
    val base = when (badge.kind) {
        QuantumLaneBadgeKind.ON_BOARD -> stringResource(R.string.board_lane_cd_on_board, lane)
        QuantumLaneBadgeKind.PLANNED -> stringResource(R.string.board_lane_cd_planned, lane)
        QuantumLaneBadgeKind.SENDING -> stringResource(R.string.board_lane_cd_sending, lane)
        QuantumLaneBadgeKind.COMPATIBLE -> stringResource(R.string.board_lane_cd_compatible, lane)
        QuantumLaneBadgeKind.UNKNOWN -> stringResource(R.string.board_lane_cd_unknown, lane)
        QuantumLaneBadgeKind.BLOCKED -> stringResource(R.string.board_lane_cd_blocked, lane)
        QuantumLaneBadgeKind.NEAR, QuantumLaneBadgeKind.CONFLICT -> pluralStringResource(
            R.plurals.board_lane_cd_overlap, badge.overlapCount, lane, badge.overlapCount,
        )
    }
    return if (conflicts.isEmpty()) base
    else "$base. " + stringResource(R.string.board_lane_conflict_with, conflicts)
}

/**
 * The rack in one line, above the list.
 *
 * The same job the detail screen's layer strip does: say what is happening at
 * once, so the ordered list underneath can go on being an ordered list. It
 * names lanes whose entry has left the backlog, because a lit lane nobody can
 * point at is exactly the state somebody needs to be told about.
 */
@Composable
internal fun BoardPlaylistLaneStrip(
    laneState: BoardPlaylistLaneState,
    modifier: Modifier = Modifier,
) {
    if (!laneState.available) return
    Row(
        modifier = modifier.testTag("board_playlist_lane_strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            stringResource(R.string.board_lane_title),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        laneState.lanes.forEach { card ->
            val orphaned = card.lane in laneState.orphanedLanes
            val description = laneCardDescription(card, orphaned)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when (card.source) {
                    QuantumLaneSource.CONFIRMED -> SuccessGreen.copy(alpha = 0.22f)
                    QuantumLaneSource.SENDING -> OrangeAccent.copy(alpha = 0.22f)
                    QuantumLaneSource.PREVIEW -> OrangeAccent.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    if (orphaned) 2.dp else 1.dp,
                    when {
                        orphaned -> WarningYellow
                        card.source == QuantumLaneSource.CONFIRMED -> SuccessGreen
                        card.source == QuantumLaneSource.FREE ->
                            MaterialTheme.colorScheme.outlineVariant
                        else -> OrangeAccent
                    },
                ),
                modifier = Modifier
                    .testTag("board_playlist_lane_strip_${card.lane + 1}")
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                    },
            ) {
                Text(
                    stringResource(R.string.board_lane_short, card.lane + 1),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .clearAndSetSemantics {},
                )
            }
        }
        if (laneState.externalLayers > 0) {
            Spacer(Modifier.width(2.dp))
            Text(
                pluralStringResource(
                    R.plurals.board_lane_external_layers,
                    laneState.externalLayers,
                    laneState.externalLayers,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (laneState.unknownLayers > 0) {
            Spacer(Modifier.width(2.dp))
            Text(
                pluralStringResource(
                    R.plurals.board_lane_unknown_layers,
                    laneState.unknownLayers,
                    laneState.unknownLayers,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = WarningYellow,
            )
        }
    }
}

@Composable
private fun laneCardDescription(card: BoardPlaylistLaneCard, orphaned: Boolean): String {
    val lane = card.lane + 1
    val base = when {
        card.source == QuantumLaneSource.FREE -> stringResource(R.string.board_lane_short, lane) +
            ": " + stringResource(R.string.board_lane_free)
        card.source == QuantumLaneSource.CONFIRMED ->
            stringResource(R.string.board_lane_cd_on_board, lane)
        card.source == QuantumLaneSource.SENDING ->
            stringResource(R.string.board_lane_cd_sending, lane)
        else -> stringResource(R.string.board_lane_cd_planned, lane)
    }
    val name = card.climbName?.let { ": $it" }.orEmpty()
    return if (orphaned) {
        "$base$name. " + stringResource(R.string.board_lane_orphaned, "L$lane")
    } else "$base$name"
}

/**
 * Lane refusals, in the terms the person can act on.
 *
 * Resolved through the context rather than `stringResource` because a refusal
 * arrives in a coroutine, after composition, and carries a lane number that
 * has to go into the sentence. "It did not work" is the one error message
 * guaranteed not to help somebody standing at a wall.
 */
@Composable
internal fun boardPlaylistLaneMessages(): (BoardPlaylistLaneFeedback) -> String {
    val context = LocalContext.current
    return { feedback ->
        when (feedback) {
            is BoardPlaylistLaneFeedback.Blocked -> context.getString(
                when (feedback.reason) {
                    BoardPlaylistLaneBlock.MESH_CANNOT_CARRY_LAYERS ->
                        R.string.board_lane_blocked_mesh
                    BoardPlaylistLaneBlock.NOT_CONNECTED ->
                        R.string.board_lane_blocked_disconnected
                },
            )
            is BoardPlaylistLaneFeedback.NoEligibleLane ->
                context.getString(R.string.board_lane_no_eligible)
            is BoardPlaylistLaneFeedback.LaneRefused -> when (feedback.reason) {
                QuantumLaneEligibility.NO_CAPACITY ->
                    context.getString(R.string.board_lane_refused_capacity)
                QuantumLaneEligibility.NO_COLOR ->
                    context.getString(R.string.board_lane_refused_color)
                QuantumLaneEligibility.UNKNOWN_LAYER -> context.getString(
                    R.string.board_lane_refused_unknown, feedback.lane + 1,
                )
                QuantumLaneEligibility.LANE_BUSY -> context.getString(
                    R.string.board_lane_refused_busy, feedback.lane + 1,
                )
                QuantumLaneEligibility.CLAIMED -> context.getString(
                    R.string.board_lane_refused_claimed, feedback.lane + 1,
                )
                // ELIGIBLE cannot reach a refusal; if the rack changed between
                // the tap and the check, the honest fallback is the conflict
                // that the check would have found.
                QuantumLaneEligibility.HOLD_CONFLICT,
                QuantumLaneEligibility.ELIGIBLE -> context.getString(
                    R.string.board_lane_refused_conflict, feedback.lane + 1,
                )
            }
        }
    }
}
