package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.cruxcoach.android.ui.common.BackupKeyWarningCard

@Composable
internal fun BackupSettingsSection(
    state: BackupSettingsState,
    onSetBackupEnabled: (Boolean) -> Unit,
    onSetInterval: (SyncInterval) -> Unit,
    onRunBackupNow: () -> Unit,
    onTriggerRestore: () -> Unit,
    onRequestDeleteRemote: () -> Unit = {},
    onNavigateToKeyManagement: () -> Unit = {},
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

        Spacer(Modifier.height(4.dp))
        // Device-local exclusions the backup intentionally does not carry
        // (backup-compat audit, 0.2.0): board selection + browse/map filters
        // live in DataStore and are re-set in seconds, so they are not backed
        // up. Stated here so restore expectations are accurate.
        Text(
            stringResource(R.string.settings_backup_device_local_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // Explicit status line — stays visible regardless of
        // backupEnabled, so the user never has to infer state from
        // "the section went blank after I clicked delete". Covers
        // every combination of (hasKey × enabled × hasLastSync).
        val statusText = when {
            !state.hasNostrKey -> stringResource(R.string.settings_backup_status_no_key)
            !state.backupEnabled && state.lastBackupIso == null ->
                stringResource(R.string.settings_backup_status_disabled_no_history)
            !state.backupEnabled && state.lastBackupIso != null ->
                stringResource(R.string.settings_backup_status_disabled_with_history, state.lastBackupIso!!)
            state.backupEnabled && state.lastBackupIso == null ->
                stringResource(R.string.settings_backup_status_enabled_no_backup)
            else ->
                stringResource(R.string.settings_backup_status_enabled_with_backup, state.lastBackupIso!!)
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // Persistent backup-key warning card. Shown whenever the user has
        // enabled the cloud backup but hasn't acknowledged saving the key
        // somewhere safe (= the existing UserPreferences.keyBackedUp flag,
        // shared with KeyManagementScreen's "Mark as backed up" flow).
        // "Open Account" navigates to KeyManagementScreen for the actual
        // show + copy. "Schlüssel ist gesichert" raises an explicit
        // confirm-dialog before flipping the flag — defeats accidental
        // taps and makes the user-responsibility moment explicit.
        if (state.backupEnabled && !state.keyBackedUp) {
            Spacer(Modifier.height(12.dp))
            BackupKeyWarningCard(
                signerMode = state.signerMode,
                onOpenAccount = onNavigateToKeyManagement,
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
                        label = { Text(stringResource(interval.labelRes)) },
                    )
                }
            }

            // "Letzte Sicherung" / "Noch keine Sicherung" has moved up
            // into the always-visible status line above the toggle, so
            // we don't duplicate it here. Inside-this-block, go
            // straight to the actionable button.
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRunBackupNow,
                enabled = !state.isRunningOneShot,
            ) {
                if (state.isRunningOneShot) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_backup_run_now_progress))
                } else {
                    Text(stringResource(R.string.settings_backup_run_now))
                }
            }
        }

        if (state.hasNostrKey) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTriggerRestore,
                enabled = !state.isCheckingForBackup,
            ) {
                if (state.isCheckingForBackup) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_backup_restore_checking))
                } else {
                    Text(stringResource(R.string.settings_backup_restore))
                }
            }

            // FEAT-002 §20.2 active opt-out. Shown only when there is
            // plausibly something to delete (= a key exists); gated further
            // by a confirmation dialog.
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRequestDeleteRemote,
                enabled = !state.isDeletingRemote,
            ) {
                Text(
                    stringResource(
                        if (state.isDeletingRemote) {
                            R.string.settings_backup_delete_remote_in_progress
                        } else {
                            R.string.settings_backup_delete_remote
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun DeleteRemoteBackupsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_delete_remote_dialog_title)) },
        text = { Text(stringResource(R.string.settings_backup_delete_remote_dialog_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.settings_backup_delete_remote_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_backup_restore_cancel))
            }
        },
    )
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
