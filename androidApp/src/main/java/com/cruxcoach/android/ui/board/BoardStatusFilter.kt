package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/** All controls share one status set, including the exclude-sent shortcut. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardStatusFilter(
    statuses: Set<ClimbStatusFilter>,
    onChange: (Set<ClimbStatusFilter>) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.board_filter_status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        val excludesSent = statusFilterExcludesSent(statuses)
        Row(
            modifier = Modifier.fillMaxWidth()
                .testTag("board_filter_exclude_sent")
                .toggleable(
                    value = excludesSent,
                    role = Role.Switch,
                    onValueChange = { onChange(statusFilterWithSentExcluded(statuses, it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.board_filter_exclude_sent),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = excludesSent, onCheckedChange = null)
        }
        Text(
            stringResource(R.string.board_filter_status_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Wrap instead of hiding statuses off-screen on small phones/large fonts.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(
                label = stringResource(R.string.board_filter_all),
                selected = statuses.isEmpty() || statuses.size == ClimbStatusFilter.entries.size,
                tag = "board_filter_status_all",
                onClick = { onChange(emptySet()) },
            )
            val options = listOf(
                ClimbStatusFilter.NEW to R.string.board_filter_status_new,
                ClimbStatusFilter.ATTEMPTED to R.string.board_filter_status_attempted,
                ClimbStatusFilter.SENT to R.string.board_filter_status_sent,
            )
            options.forEach { (status, label) ->
                StatusChip(
                    label = stringResource(label),
                    selected = status in statuses,
                    tag = "board_filter_status_${status.name.lowercase()}",
                    onClick = {
                        onChange(if (status in statuses) statuses - status else statuses + status)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        modifier = Modifier.testTag(tag),
    )
}
