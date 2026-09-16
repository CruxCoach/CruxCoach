package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand

@Composable
internal fun BoardLogbookImportSection(
    onNavigateToAuroraMigration: () -> Unit,
    onNavigateToMoonBoardCsvImport: () -> Unit,
) {
    SettingsDestinationRow(
        title = stringResource(R.string.settings_moon_csv_title),
        summary = stringResource(R.string.settings_moon_csv_desc),
        icon = Icons.Default.FolderOpen,
        onClick = onNavigateToMoonBoardCsvImport,
    )
    HorizontalDivider()
    SettingsDestinationRow(
        title = stringResource(R.string.settings_aurora_migration_title),
        summary = stringResource(R.string.settings_aurora_migration_desc),
        icon = Icons.Default.SwapHoriz,
        onClick = onNavigateToAuroraMigration,
    )
}

/** File-based whole-app import/export, kept beside encrypted backup. */
@Composable
internal fun AppDataTransferSection(
    deleteSuccess: String?,
    onNavigateToImport: () -> Unit,
    onNavigateToExport: () -> Unit,
    onDismissDeleteSuccess: () -> Unit,
) {
    SettingsDestinationRow(
        title = stringResource(R.string.settings_data_import),
        summary = stringResource(R.string.settings_data_import_desc),
        icon = Icons.Default.FolderOpen,
        onClick = onNavigateToImport,
    )
    HorizontalDivider()
    SettingsDestinationRow(
        title = stringResource(R.string.settings_data_export),
        summary = stringResource(R.string.settings_data_export_desc),
        icon = Icons.Default.Save,
        onClick = onNavigateToExport,
    )
    DataResultMessage(deleteSuccess, onDismissDeleteSuccess)
}

/** Keep completion feedback visible at the action, including the deletion page. */
@Composable
internal fun DataResultMessage(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
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
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    ) {
        if (isDeletingBoardData) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
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
        color = MaterialTheme.colorScheme.error
    )

    // Confirmation dialogs — both destructive actions are board-scoped:
    // a multiselect of the interactive board families replaces the old
    // all-or-nothing confirm.
    if (showDeleteBoardDataDialog) {
        BoardMultiSelectDialog(
            title = stringResource(R.string.settings_data_delete_board_dialog_title),
            message = stringResource(R.string.settings_data_delete_board_dialog_message),
            note = null,
            confirmLabel = stringResource(R.string.action_delete),
            confirmColor = MaterialTheme.colorScheme.primary,
            selectedBrands = selectedBrands,
            onToggleBrand = onToggleBrand,
            onToggleSelectAll = onToggleSelectAll,
            onConfirm = onDeleteBoardData,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (showDeleteUserDataDialog) {
        BoardMultiSelectDialog(
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
