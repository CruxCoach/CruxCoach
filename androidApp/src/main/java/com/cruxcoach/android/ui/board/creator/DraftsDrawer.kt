package com.cruxcoach.android.ui.board.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardClimbParser

/**
 * Bottom sheet showing the user's local drafts. Tap a row to load it
 * back into the editor; long-press / trailing trash icon to delete
 * (confirmation dialog gates the destructive op).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsDrawer(
    drafts: List<CommunityClimbRow>?,
    onSelect: (CommunityClimbRow) -> Unit,
    onDelete: (uuid: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var pendingDelete by remember { mutableStateOf<CommunityClimbRow?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.climb_creator_drafts_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                drafts?.let {
                    Text(
                        stringResource(R.string.climb_creator_drafts_count, it.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                drafts == null -> Text(
                    stringResource(R.string.climb_creator_drafts_loading),
                    style = MaterialTheme.typography.bodySmall,
                )
                drafts.isEmpty() -> Text(
                    stringResource(R.string.climb_creator_drafts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn {
                    items(drafts, key = { it.uuid }) { draft ->
                        DraftRow(
                            draft = draft,
                            onSelect = { onSelect(draft) },
                            onDelete = { pendingDelete = draft },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingDelete?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.climb_creator_drafts_delete_title)) },
            text = { Text(stringResource(R.string.climb_creator_drafts_delete_message, draft.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(draft.uuid)
                    pendingDelete = null
                }) { Text(stringResource(R.string.climb_creator_drafts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DraftRow(
    draft: CommunityClimbRow,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val holds = remember(draft.framesText) {
        BoardClimbParser.parseFrames(draft.framesText).size
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    draft.name.ifBlank { stringResource(R.string.climb_creator_drafts_unnamed) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(
                        onClick = onSelect,
                        label = { Text(stringResource(R.string.climb_creator_drafts_holds, holds)) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                    val createdAt = draft.createdAt
                    if (createdAt != null) {
                        AssistChip(
                            onClick = onSelect,
                            label = { Text(formatRelative(createdAt)) },
                            colors = AssistChipDefaults.assistChipColors(),
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.climb_creator_drafts_delete_action),
                )
            }
        }
    }
}

/** Best-effort relative timestamp ("vor 2 h" / "gestern" / ISO date fallback). */
private fun formatRelative(iso: String): String {
    return try {
        val instant = java.time.Instant.parse(iso)
        val now = java.time.Instant.now()
        val seconds = java.time.Duration.between(instant, now).seconds
        when {
            seconds < 60 -> "gerade eben"
            seconds < 3600 -> "vor ${seconds / 60} min"
            seconds < 86400 -> "vor ${seconds / 3600} h"
            seconds < 7 * 86400 -> "vor ${seconds / 86400} t"
            else -> instant.toString().take(10)
        }
    } catch (_: Exception) {
        iso.take(10)
    }
}
