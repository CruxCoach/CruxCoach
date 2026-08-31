package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.domain.board.HistoryRetentionPeriod
import com.cruxcoach.domain.board.ProgressHistoryEntry
import com.cruxcoach.domain.board.ProgressHistoryScreenState

data class ProgressHistoryEntryLabels(
    val grade: String,
    val board: String,
    val date: String,
)

/** Fixture-friendly candidate body. App bars, navigation, and dialogs remain platform-owned. */
@Composable
fun ProgressHistoryContent(
    state: ProgressHistoryScreenState,
    labelsFor: (ProgressHistoryEntry) -> ProgressHistoryEntryLabels,
    onChooseRetention: (HistoryRetentionPeriod) -> Unit,
    onOpenEntry: (ProgressHistoryEntry) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ProgressHistoryScreenState.Loading -> HistoryLoading(modifier)
        is ProgressHistoryScreenState.Error -> HistoryError(onRetry, modifier)
        is ProgressHistoryScreenState.Empty -> HistoryBody(
            retention = state.retention,
            modifier = modifier,
            onChooseRetention = onChooseRetention,
        ) { HistoryEmpty() }
        is ProgressHistoryScreenState.Content -> HistoryBody(
            retention = state.retention,
            modifier = modifier,
            onChooseRetention = onChooseRetention,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("history_list"),
                contentPadding = PaddingValues(
                    start = CruxCoachSpacing.large,
                    end = CruxCoachSpacing.large,
                    top = CruxCoachSpacing.small,
                    bottom = CruxCoachSpacing.large,
                ),
                verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        labels = labelsFor(entry),
                        selected = entry.id in state.selectedIds,
                        selectionMode = state.hasSelection,
                        onOpen = { onOpenEntry(entry) },
                        onToggleSelection = { onToggleSelection(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryBody(
    retention: HistoryRetentionPeriod,
    modifier: Modifier,
    onChooseRetention: (HistoryRetentionPeriod) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        RetentionControls(retention, onChooseRetention)
        Text(
            text = stringResource(R.string.history_backup_local_only),
            modifier = Modifier.padding(
                horizontal = CruxCoachSpacing.large,
                vertical = CruxCoachSpacing.xSmall,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionControls(
    selected: HistoryRetentionPeriod,
    onChoose: (HistoryRetentionPeriod) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CruxCoachSpacing.large, vertical = CruxCoachSpacing.small),
        verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.xSmall),
    ) {
        Text(
            text = stringResource(R.string.history_retention_label),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small)) {
            HistoryRetentionPeriod.entries.forEach { retention ->
                FilterChip(
                    selected = selected == retention,
                    onClick = { onChoose(retention) },
                    label = { Text(stringResource(retention.labelResource())) },
                    modifier = Modifier
                        .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                        .testTag("history_retention_${retention.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ProgressHistoryEntry,
    labels: ProgressHistoryEntryLabels,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val accessibleLabel = stringResource(
        R.string.history_entry_semantics,
        entry.climbName,
        labels.grade,
        entry.angle,
        labels.board,
        labels.date,
    )
    val selectionLabel = stringResource(R.string.history_select_entry, entry.climbName)
    Surface(
        onClick = if (selectionMode) onToggleSelection else onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = accessibleLabel }
            .testTag("history_entry_${entry.id}"),
        shape = CruxCoachDesign.shapes.medium,
        color = if (selected) {
            CruxCoachDesign.colors.brandAccent.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(CruxCoachSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier
                    .sizeIn(
                        minWidth = CruxCoachSpacing.minimumTouchTarget,
                        minHeight = CruxCoachSpacing.minimumTouchTarget,
                    )
                    .semantics { contentDescription = selectionLabel }
                    .testTag("history_select_${entry.id}"),
            )
            Spacer(Modifier.width(CruxCoachSpacing.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.climbName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.history_entry_metadata,
                        labels.grade,
                        entry.angle,
                        labels.board,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(CruxCoachSpacing.small))
            Text(
                text = labels.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryLoading(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.history_loading),
                modifier = Modifier.padding(top = CruxCoachSpacing.large),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun HistoryEmpty() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(CruxCoachSpacing.xLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.History, contentDescription = null)
            Text(
                text = stringResource(R.string.history_empty_title),
                modifier = Modifier.padding(top = CruxCoachSpacing.large),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(top = CruxCoachSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HistoryError(onRetry: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(CruxCoachSpacing.xLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.history_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.history_error_body),
                modifier = Modifier.padding(top = CruxCoachSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = CruxCoachSpacing.large)
                    .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                    .testTag("history_retry"),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

private fun HistoryRetentionPeriod.labelResource(): Int = when (this) {
    HistoryRetentionPeriod.OFF -> R.string.history_retention_off
    HistoryRetentionPeriod.DAYS_30 -> R.string.history_retention_30_days
    HistoryRetentionPeriod.DAYS_90 -> R.string.history_retention_90_days
    HistoryRetentionPeriod.DAYS_365 -> R.string.history_retention_365_days
}
