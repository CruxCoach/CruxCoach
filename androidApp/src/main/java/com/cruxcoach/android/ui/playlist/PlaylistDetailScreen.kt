package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.GradeDisplayHelper

/**
 * Ordered playlist: climb rows + rest rows, edit mode with up/down reorder,
 * per-rest duration editing, and the Play entry into the session queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (String, Int) -> Unit,
    onPlay: (List<Pair<String, Int>>, String) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddRestDialog by rememberSaveable { mutableStateOf(false) }

    if (state.showRenameDialog) {
        RenameDialog(
            value = state.renameValue,
            onValueChange = viewModel::updateRenameValue,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissRenameDialog,
        )
    }

    state.editRestEntryId?.let { entryId ->
        val current = state.entries.firstOrNull { it.entryId == entryId }?.restSeconds ?: 60L
        RestDurationDialog(
            initialSeconds = current,
            onConfirm = { viewModel.updateRestSeconds(entryId, it) },
            onDismiss = viewModel::dismissEditRest,
        )
    }

    if (showAddRestDialog) {
        RestDurationDialog(
            initialSeconds = 180L,
            onConfirm = {
                viewModel.addRest(it)
                showAddRestDialog = false
            },
            onDismiss = { showAddRestDialog = false },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.name) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.toggleEditMode() },
                            modifier = Modifier.testTag("playlist_edit_toggle"),
                        ) {
                            Icon(
                                Icons.Default.Reorder,
                                contentDescription = stringResource(R.string.playlist_reorder),
                                tint = if (state.editMode) OrangeAccent
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.showRenameDialog()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_add_rest)) },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showAddRestDialog = true
                                },
                                modifier = Modifier.testTag("playlist_add_rest"),
                            )
                        }
                    },
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
        floatingActionButton = {
            val playable = state.entries.any { !it.isRest && it.climb != null }
            if (playable) {
                ExtendedFloatingActionButton(
                    onClick = { onPlay(viewModel.playableEntries(), state.name) },
                    containerColor = OrangeAccent,
                    icon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
                    },
                    text = {
                        Text(
                            stringResource(R.string.playlist_play),
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    modifier = Modifier.testTag("playlist_play_fab"),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (state.unavailableCount > 0) {
                Text(
                    stringResource(R.string.playlist_unavailable_climbs, state.unavailableCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = InfoBlue,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.playlist_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(state.entries, key = { _, e -> e.entryId }) { index, entry ->
                    if (entry.isRest) {
                        RestRow(
                            seconds = entry.restSeconds ?: 0L,
                            editMode = state.editMode,
                            onClick = { viewModel.showEditRest(entry.entryId) },
                            onRemove = { viewModel.removeEntry(entry.entryId) },
                            onMoveUp = if (index > 0) {
                                { viewModel.moveEntry(index, index - 1) }
                            } else null,
                            onMoveDown = if (index < state.entries.lastIndex) {
                                { viewModel.moveEntry(index, index + 1) }
                            } else null,
                            testTag = "playlist_rest_${entry.entryId}",
                        )
                    } else {
                        ClimbRow(
                            entry = entry,
                            gradeScale = state.gradeScale,
                            editMode = state.editMode,
                            onClick = {
                                val uuid = entry.climbUuid ?: return@ClimbRow
                                if (entry.climb == null) return@ClimbRow
                                val angle = entry.angle?.toInt() ?: 40
                                // Pager over the playlist's resolvable climbs.
                                viewModel.climbNavState.climbUuids =
                                    viewModel.playableEntries().map { it.first }.distinct()
                                viewModel.climbNavState.angle = angle
                                viewModel.climbNavState.source = ClimbNavigationSource.LIST
                                onNavigateToClimb(uuid, angle)
                            },
                            onRemove = { viewModel.removeEntry(entry.entryId) },
                            onMoveUp = if (index > 0) {
                                { viewModel.moveEntry(index, index - 1) }
                            } else null,
                            onMoveDown = if (index < state.entries.lastIndex) {
                                { viewModel.moveEntry(index, index + 1) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClimbRow(
    entry: PlaylistUiEntry,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val climb = entry.climb
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist_climb_${entry.entryId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (climb == null) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
            if (climb == null) Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb?.name ?: stringResource(R.string.playlist_climb_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (climb == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = buildString {
                    climb?.difficultyAverage?.let {
                        append(GradeDisplayHelper.formatDifficulty(it, gradeScale))
                    }
                    entry.angle?.let {
                        if (isNotEmpty()) append(" · ")
                        append(stringResource(R.string.playlist_angle_label, it))
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editMode) {
                ReorderControls(onMoveUp, onMoveDown, onRemove)
            }
        }
    }
}

@Composable
private fun RestRow(
    seconds: Long,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    testTag: String,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = InfoBlue.copy(alpha = 0.10f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.HourglassBottom,
                contentDescription = null,
                tint = InfoBlue,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                stringResource(R.string.playlist_rest_entry, formatRest(seconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = InfoBlue,
                modifier = Modifier.weight(1f),
            )
            if (editMode) {
                ReorderControls(onMoveUp, onMoveDown, onRemove)
            }
        }
    }
}

@Composable
private fun ReorderControls(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMoveUp ?: {}, enabled = onMoveUp != null, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.playlist_move_up))
        }
        IconButton(onClick = onMoveDown ?: {}, enabled = onMoveDown != null, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.playlist_move_down))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_delete),
                tint = ErrorRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_rename), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Rest duration editor: common presets + free numeric input (seconds). */
@Composable
private fun RestDurationDialog(
    initialSeconds: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialSeconds.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_rest_duration), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60L, 180L, 300L).forEach { preset ->
                        TextButton(onClick = { text = preset.toString() }) {
                            Text(formatRest(preset))
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.playlist_rest_seconds_label)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_rest_seconds_field"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { text.toLongOrNull()?.let(onConfirm) },
                enabled = (text.toLongOrNull() ?: 0L) >= 10L,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun formatRest(seconds: Long): String =
    if (seconds >= 60 && seconds % 60 == 0L) "${seconds / 60} min" else "$seconds s"
