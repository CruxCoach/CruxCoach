package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.MoonBoardHoldSetPreview
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * The MoonBoard hold-set picker in board settings (FEAT-049 §3.5).
 *
 * Renders nothing at all when the active board is not a MoonBoard, or is
 * MoonBoard 2010 — one set is not a choice, and a single checkbox that cannot
 * be unticked would be worse than no picker (edge case 5).
 *
 * The two levels mirror how the holds are actually sold. Level 1 is the
 * complete setup: one line, preselected, and the state every existing install
 * is already in. Level 2 is the correction for someone who deviated — an
 * upgrader with leftovers from 2016, or a person who bought Wooden Holds
 * separately — and stays collapsed until they say so.
 *
 * No explanatory copy beyond the labels: the previews carry it, exactly as
 * they do in the official app. The one addition is the live climbable count,
 * which turns an abstract choice into a number.
 */
@Composable
internal fun MoonBoardHoldSetSection() {
    val viewModel: MoonBoardHoldSetViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    if (!state.loaded) return
    val variant = state.variant ?: return

    HorizontalDivider()
    Column(
        modifier = Modifier.fillMaxWidth().testTag("moonboard_hold_sets"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.moonboard_hold_sets_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.moonboard_hold_sets_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Before a catalogue with a populated hsm arrives, deselecting a set
        // would change nothing at all. Say so and stop, rather than offering a
        // control whose effect is invisible (§3.7 / edge case 4).
        if (!state.catalogueHasHoldSetData) {
            NeedsSyncNotice()
            return@Column
        }

        CompleteSetupRow(
            variantName = variant.displayName,
            selected = state.isCompleteSetup,
            onSelect = viewModel::selectCompleteSetup,
        )

        PartialSetupHeader(
            expanded = state.expanded,
            summary = if (state.isCompleteSetup) null else stringResource(
                R.string.moonboard_hold_sets_summary,
                state.selectedSetIds.size,
                state.sets.size,
            ),
            onToggle = { viewModel.setExpanded(!state.expanded) },
        )

        AnimatedVisibility(visible = state.expanded) {
            HoldSetList(
                variant = variant,
                state = state,
                onToggleSet = viewModel::toggleSet,
            )
        }

        state.counts?.let { counts ->
            Text(
                stringResource(
                    R.string.moonboard_hold_sets_climbable,
                    counts.climbable.toString(),
                    counts.total.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("moonboard_hold_sets_climbable"),
            )
        }
    }
}

@Composable
private fun NeedsSyncNotice() {
    Surface(
        color = InfoBlue.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("moonboard_hold_sets_needs_sync"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = InfoBlue,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.moonboard_hold_sets_needs_sync),
                style = MaterialTheme.typography.bodySmall,
                color = InfoBlue,
            )
        }
    }
}

@Composable
private fun CompleteSetupRow(
    variantName: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("moonboard_setup_complete"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.moonboard_setup_complete, variantName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.moonboard_setup_complete_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PartialSetupHeader(
    expanded: Boolean,
    summary: String?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("moonboard_setup_partial"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.moonboard_setup_partial),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Only once the selection has actually left "complete" is the
            // n-of-m line worth showing; on Level 1 it would just restate the
            // row above it.
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = OrangeAccent,
                )
            }
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HoldSetList(
    variant: MoonBoardVariant,
    state: MoonBoardHoldSetState,
    onToggleSet: (Long) -> Unit,
) {
    // One decode for the whole list — every preview rings the same board.
    val assetState = rememberMoonBoardAsset(variant.layoutId)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.showMinimumOneWarning) {
            Text(
                stringResource(R.string.moonboard_hold_sets_min_one),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("moonboard_hold_sets_min_one"),
            )
        }
        state.sets.forEach { set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleSet(set.id) }
                    .testTag("moonboard_hold_set_${set.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = set.id in state.selectedSetIds,
                    onCheckedChange = { onToggleSet(set.id) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    // Product name — English in every locale, like the
                    // official app and the catalogue.
                    Text(set.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.moonboard_hold_sets_count,
                            MoonBoardHoldSets.holdIdsFor(variant, set.id).size.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The picture is the explanation: which holds this set covers,
                // drawn on the board the user owns.
                MoonBoardHoldSetPreview(
                    variant = variant,
                    setId = set.id,
                    assetState = assetState,
                    modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}
