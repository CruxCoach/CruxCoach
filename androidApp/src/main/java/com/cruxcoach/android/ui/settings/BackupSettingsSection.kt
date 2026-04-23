package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.nostr.backup.BackupInfo

@Composable
internal fun BackupSettingsSection(
    state: BackupSettingsState,
    onSetBackupEnabled: (Boolean) -> Unit,
    onSetInterval: (SyncInterval) -> Unit,
    onRunBackupNow: () -> Unit,
    onTriggerRestore: () -> Unit,
) {
    if (!state.featureEnabled) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_backup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_backup_description),
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))

        // Toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.settings_backup_enable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.backupEnabled,
                onCheckedChange = onSetBackupEnabled,
                enabled = state.hasNostrKey,
            )
        }

        if (!state.hasNostrKey) {
            Text(
                stringResource(R.string.settings_backup_needs_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.backupEnabled) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_backup_interval),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncInterval.entries.forEach { interval ->
                    FilterChip(
                        selected = state.interval == interval,
                        onClick = { onSetInterval(interval) },
                        label = { Text(interval.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            state.lastBackupIso?.let {
                Text(
                    stringResource(R.string.settings_backup_last_sync, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text(
                stringResource(R.string.settings_backup_never_synced),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRunBackupNow,
                enabled = !state.isRunningOneShot,
            ) {
                Text(stringResource(R.string.settings_backup_run_now))
            }
        }

        if (state.hasNostrKey) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTriggerRestore,
                enabled = !state.isCheckingForBackup,
            ) {
                Text(
                    if (state.isCheckingForBackup) {
                        stringResource(R.string.settings_backup_restore_checking)
                    } else {
                        stringResource(R.string.settings_backup_restore)
                    },
                )
            }
        }
    }
}

@Composable
internal fun BackupRestoreDialog(
    info: BackupInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_restore_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.settings_backup_restore_dialog_body,
                        formatSize(info.pointer.size),
                    ),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.settings_backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_backup_restore_cancel))
            }
        },
    )
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024) "${"%.0f".format(kb)} KB" else "${"%.1f".format(kb / 1024.0)} MB"
}
