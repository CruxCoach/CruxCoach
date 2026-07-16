package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * File-based import / export — the "manual backup" variant that sits
 * next to the Nostr-driven BackupSettingsSection. Split off from the
 * original DataManagementSection so Import + Export can live near
 * the other backup UI while the destructive Delete actions stay at
 * the very bottom of the settings panel.
 */
@Composable
internal fun DataImportExportSection(
    deleteSuccess: String?,
    onNavigateToImport: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToAuroraMigration: () -> Unit,
    onDismissDeleteSuccess: () -> Unit,
) {
    // Import banner
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

    // Export banner
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

    Spacer(modifier = Modifier.height(8.dp))

    // Aurora migration — for users coming from the old Kilter / Tension /
    // Aurora-shared logbook. FEAT-005: imports the email-export JSON
    // Aurora support sends after a data-request.
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
    onShowDeleteBoardDataDialog: () -> Unit,
    onShowDeleteUserDataDialog: () -> Unit,
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

    // Confirmation dialogs
    if (showDeleteBoardDataDialog) {
        AlertDialog(
            onDismissRequest = onDismissDeleteDialog,
            title = { Text(stringResource(R.string.settings_data_delete_board_dialog_title)) },
            text = { Text(stringResource(R.string.settings_data_delete_board_dialog_message)) },
            confirmButton = {
                TextButton(onClick = onDeleteBoardData) {
                    Text(stringResource(R.string.action_delete), color = OrangeAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteUserDataDialog) {
        AlertDialog(
            onDismissRequest = onDismissDeleteDialog,
            title = { Text(stringResource(R.string.settings_data_delete_user_dialog_title)) },
            text = { Text(stringResource(R.string.settings_data_delete_user_dialog_message)) },
            confirmButton = {
                TextButton(onClick = onDeleteUserBoardData) {
                    Text(stringResource(R.string.settings_data_delete_user_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
    onStopAnimation: () -> Unit,
    onOpenSourceLicenses: () -> Unit,
) {
    val tapCount = remember { mutableIntStateOf(0) }
    val context = LocalContext.current

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
                            context.getString(R.string.settings_app_info_easter_unlocked),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    remaining in 1..3 -> {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_app_info_easter_remaining, remaining),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .padding(vertical = 4.dp)
    ) {
        Text(
            "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onOpenSourceLicenses,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.open_source_licenses_title))
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
