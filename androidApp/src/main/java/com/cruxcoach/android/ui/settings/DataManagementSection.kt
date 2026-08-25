package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.domain.board.BoardBrand

@Composable
internal fun BoardLogbookImportSection(
    onNavigateToAuroraMigration: () -> Unit,
    onNavigateToMoonBoardCsvImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onNavigateToMoonBoardCsvImport() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, tint = OrangeAccent)
            Column {
                Text(stringResource(R.string.settings_moon_csv_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_moon_csv_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Aurora migration — for users coming from the old Kilter / Tension /
    // Aurora-shared logbook.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToAuroraMigration() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = OrangeAccent)
            Column {
                Text(
                    stringResource(R.string.settings_aurora_migration_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.settings_aurora_migration_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** File-based whole-app import/export, kept beside encrypted backup. */
@Composable
internal fun AppDataTransferSection(
    deleteSuccess: String?,
    onNavigateToImport: () -> Unit,
    onNavigateToExport: () -> Unit,
    onDismissDeleteSuccess: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToImport() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, tint = OrangeAccent)
            Column {
                Text(
                    stringResource(R.string.settings_data_import),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.settings_data_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToExport() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.FileUpload, contentDescription = null, tint = OrangeAccent)
            Column {
                Text(
                    stringResource(R.string.settings_data_export),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.settings_data_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Success banner — shown for both Import/Export results and Delete
    // completions. Kept in the Import/Export section so the feedback
    // is near the action the user just took; a successful delete does
    // surface through this same state but is rare enough that putting
    // it up here is fine.
    deleteSuccess?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = SuccessGreen, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissDeleteSuccess) {
                    Text(stringResource(R.string.action_ok), color = SuccessGreen)
                }
            }
        }
    }
}

/**
 * Destructive actions — deliberately the last item in the data panel
 * so a user scrolling past the backup options isn't one miss-tap away
 * from wiping their logbook.
 */
@Composable
internal fun DataDeletionSection(
    showDeleteBoardDataDialog: Boolean,
    showDeleteUserDataDialog: Boolean,
    isDeletingBoardData: Boolean,
    selectedBrands: Set<BoardBrand>,
    onShowDeleteBoardDataDialog: () -> Unit,
    onShowDeleteUserDataDialog: () -> Unit,
    onToggleBrand: (BoardBrand) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDeleteBoardData: () -> Unit,
    onDeleteUserBoardData: () -> Unit,
) {
    // Delete board data. The deletion runs app-scoped for ~20s — while it
    // is in flight the button becomes a blocking progress row so the user
    // neither re-triggers it nor assumes the app hung.
    OutlinedButton(
        onClick = onShowDeleteBoardDataDialog,
        enabled = !isDeletingBoardData,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
    ) {
        if (isDeletingBoardData) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = OrangeAccent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_data_delete_board_progress))
        } else {
            Text(stringResource(R.string.settings_data_delete_board))
        }
    }
    Text(
        stringResource(R.string.settings_data_delete_board_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Delete user data
    val errorColor = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onShowDeleteUserDataDialog,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
        border = BorderStroke(1.dp, errorColor.copy(alpha = 0.5f))
    ) { Text(stringResource(R.string.settings_data_delete_user)) }
    Text(
        stringResource(R.string.settings_data_delete_user_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    )

    // Confirmation dialogs — both destructive actions are board-scoped:
    // a multiselect of the interactive board families replaces the old
    // all-or-nothing confirm.
    if (showDeleteBoardDataDialog) {
        BoardMultiSelectDeleteDialog(
            title = stringResource(R.string.settings_data_delete_board_dialog_title),
            message = stringResource(R.string.settings_data_delete_board_dialog_message),
            note = null,
            confirmLabel = stringResource(R.string.action_delete),
            confirmColor = OrangeAccent,
            selectedBrands = selectedBrands,
            onToggleBrand = onToggleBrand,
            onToggleSelectAll = onToggleSelectAll,
            onConfirm = onDeleteBoardData,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (showDeleteUserDataDialog) {
        BoardMultiSelectDeleteDialog(
            title = stringResource(R.string.settings_data_delete_user_dialog_title),
            message = stringResource(R.string.settings_data_delete_user_dialog_message),
            note = stringResource(R.string.settings_data_delete_user_sessions_note),
            confirmLabel = stringResource(R.string.settings_data_delete_user_confirm),
            confirmColor = MaterialTheme.colorScheme.error,
            selectedBrands = selectedBrands,
            onToggleBrand = onToggleBrand,
            onToggleSelectAll = onToggleSelectAll,
            onConfirm = onDeleteUserBoardData,
            onDismiss = onDismissDeleteDialog,
        )
    }
}

/**
 * Shared multiselect confirm dialog for the two destructive per-board
 * delete actions: every interactive board family is a checkbox row and
 * "all boards" toggles the full set. Opens with everything selected
 * (the pre-0.2.2 all-boards behaviour); confirm stays disabled while
 * nothing is selected. [note] is an optional caveat line under the
 * board list — the logbook dialog uses it for the sessions/lists
 * only-on-full-selection rule.
 */
@Composable
private fun BoardMultiSelectDeleteDialog(
    title: String,
    message: String,
    note: String?,
    confirmLabel: String,
    confirmColor: Color,
    selectedBrands: Set<BoardBrand>,
    onToggleBrand: (BoardBrand) -> Unit,
    onToggleSelectAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val boards = remember { BoardBrand.entries.filter { it.isInteractive } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // 7 board rows + copy exceed small-screen dialog height.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelectAll() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedBrands.containsAll(boards),
                        onCheckedChange = { onToggleSelectAll() },
                        colors = CheckboxDefaults.colors(checkedColor = confirmColor)
                    )
                    Text(
                        stringResource(R.string.settings_data_delete_select_all),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                boards.forEach { brand ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleBrand(brand) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = brand in selectedBrands,
                            onCheckedChange = { onToggleBrand(brand) },
                            colors = CheckboxDefaults.colors(checkedColor = confirmColor)
                        )
                        Text(brand.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                note?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selectedBrands.isNotEmpty()) {
                // No explicit color when disabled — it would override the
                // TextButton's dimmed disabled content color.
                if (selectedBrands.isNotEmpty()) {
                    Text(confirmLabel, color = confirmColor)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun AppInfoSection(
    easterAnimationsUnlocked: Boolean,
    isAnimating: Boolean,
    isBleConnected: Boolean,
    onUnlockEasterAnimations: () -> Unit,
    onPlayEasterAnimation: () -> Unit,
    onStopAnimation: () -> Unit
) {
    val tapCount = remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val resources = LocalResources.current

    Text(
        stringResource(R.string.settings_app_info_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (easterAnimationsUnlocked) return@clickable
                val count = tapCount.intValue + 1
                tapCount.intValue = count
                val remaining = 7 - count
                when {
                    remaining == 0 -> {
                        onUnlockEasterAnimations()
                        android.widget.Toast.makeText(
                            context,
                            resources.getString(R.string.settings_app_info_easter_unlocked),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    remaining in 1..3 -> {
                        android.widget.Toast.makeText(
                            context,
                            resources.getString(R.string.settings_app_info_easter_remaining, remaining),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .padding(vertical = 4.dp)
    ) {
        Text(
            "CruxCoach v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Open-Source Bouldering Training App",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = easterAnimationsUnlocked) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(8.dp))
            if (isAnimating) {
                OutlinedButton(
                    onClick = onStopAnimation,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_app_info_animation_stop))
                }
            } else {
                OutlinedButton(
                    onClick = onPlayEasterAnimation,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isBleConnected
                ) {
                    Text(if (isBleConnected) stringResource(R.string.settings_app_info_easter_play) else stringResource(R.string.settings_app_info_easter_no_board))
                }
            }
        }
    }
}
