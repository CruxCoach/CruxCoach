package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FEAT-062 §8: the recovery code half of a restore.
 *
 * Shown as a section rather than a wizard because there is nothing to walk
 * through: generate a code, write it down, and prove you have it when you
 * restore. The screen states plainly that a restore stays blocked until
 * withdrawals have been re-checked, so nobody reads a successful restore as
 * "everything is shared again".
 */
@Composable
fun SharingRecoverySection(viewModel: SharingViewModel) {
    val code by viewModel.recoveryCode.collectAsStateWithLifecycle()
    val message by viewModel.recoveryMessage.collectAsStateWithLifecycle()
    var entered by rememberSaveable { mutableStateOf("") }
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val backupError by viewModel.backupError.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    val retryAvailable by viewModel.previewRetryAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF: the user picks where the file goes and where it comes from, so the
    // app never needs storage permission and never guesses a path.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = viewModel.exportBackupBytes() ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            }.onFailure { viewModel.clearBackupMessage() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null) viewModel.importBackupBytes(bytes, entered)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_recovery_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.sharing_recovery_body), style = MaterialTheme.typography.bodySmall)

            // Right where the person already is. A preview that only said "read
            // only" left them with the one way forward on another screen, with
            // nothing saying it was the way forward.
            if (retryAvailable) {
                SharingPreviewRetryCard(
                    onConfirm = { viewModel.confirmPreviewRetry() },
                    onDismiss = { viewModel.cancelPreviewRetry() },
                    enabled = !signing,
                )
            }
            // Restoring and backing up are signed too, and the controller takes
            // one mutation at a time. Without this the buttons would look live
            // while a prompt was open and then quietly do nothing.
            SharingSigningBanner(signing)

            code?.let { shown ->
                // Spelled out with spaces: a screen reader reads a dashed code
                // as one long word, which is unusable for something meant to be
                // copied onto paper.
                val spoken = stringResource(R.string.sharing_cd_recovery_code, shown.replace("-", " "))
                Text(
                    shown,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.newRecoveryCode() },
                    // A backup is sealed with the code that was showing when
                    // the export began. Replacing it while that export is still
                    // waiting for a signature would leave the file openable
                    // only by a code no longer on screen.
                    enabled = !signing,
                ) {
                    Text(stringResource(R.string.sharing_recovery_new))
                }
            }

            OutlinedTextField(
                value = entered,
                onValueChange = { entered = it },
                label = { Text(stringResource(R.string.sharing_recovery_enter)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Named for what it does. With no file there is nothing to restore
            // *from*: this asks the owner's key to mint a new authority
            // generation with this device as its only holder, and fences the
            // rest. Calling that "restore" is what let it be wired to a path
            // that did none of it.
            Text(
                stringResource(R.string.sharing_recovery_takeover_body),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { viewModel.restore(entered) },
                enabled = entered.isNotBlank() && code != null && !signing,
            ) {
                Text(stringResource(R.string.sharing_recovery_takeover))
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.sharing_backup_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(R.string.sharing_backup_body), style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = { exportLauncher.launch("cruxcoach-freigaben.ccshare") },
                // Without a code there is nothing to derive the file key from.
                enabled = code != null && !signing,
            ) {
                Text(stringResource(R.string.sharing_backup_export))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = entered.isNotBlank() && !signing,
            ) {
                Text(stringResource(R.string.sharing_backup_import))
            }

            backupMessage?.let { state ->
                val text = when (state) {
                    SharingViewModel.BackupMessage.EXPORTED -> stringResource(R.string.sharing_backup_exported)
                    SharingViewModel.BackupMessage.RESTORED -> stringResource(R.string.sharing_backup_restored)
                    SharingViewModel.BackupMessage.NOTHING_TO_DO -> stringResource(R.string.sharing_backup_nothing)
                    SharingViewModel.BackupMessage.FAILED ->
                        stringResource(SharingLabels.backupError(backupError))
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == SharingViewModel.BackupMessage.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.semantics { contentDescription = text },
                )
            }

            // Each refusal is a different thing to do about it, so each gets its
            // own sentence. They were all "that code is not correct", which sent
            // somebody whose signer had declined to retype the one thing that
            // was already right.
            val failure = when (message) {
                SharingViewModel.RecoveryMessage.WRONG_CODE -> R.string.sharing_recovery_wrong
                SharingViewModel.RecoveryMessage.SIGNER_REFUSED -> R.string.sharing_recovery_not_signed
                SharingViewModel.RecoveryMessage.PREVIEW_ONLY -> R.string.sharing_recovery_preview_only
                SharingViewModel.RecoveryMessage.REFUSED -> R.string.sharing_recovery_refused
                SharingViewModel.RecoveryMessage.RESTORED, null -> null
            }
            failure?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (message == SharingViewModel.RecoveryMessage.RESTORED) {
                Text(
                    stringResource(R.string.sharing_recovery_done),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
