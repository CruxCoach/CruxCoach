package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

@Composable
internal fun AccountBackupDialog(
    state: AccountBackupState,
    onChooseBackup: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onConfirmStored: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.step == AccountBackupStep.CLOSED) return
    val busy = state.step == AccountBackupStep.RUNNING || state.authenticating
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (state.step == AccountBackupStep.STORE && state.copiedInFlow)
            R.string.account_flow_copied_title else R.string.account_flow_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.step) {
                    AccountBackupStep.LOADING, AccountBackupStep.RUNNING -> {
                        CircularProgressIndicator()
                        if (state.step == AccountBackupStep.RUNNING) Text(stringResource(R.string.account_flow_running))
                    }
                    AccountBackupStep.COPY, AccountBackupStep.STORE -> {
                        Text(stringResource(if (state.step == AccountBackupStep.COPY)
                            R.string.account_flow_intro else if (state.copiedInFlow) R.string.account_flow_store else R.string.account_flow_already_stored))
                        if (state.step == AccountBackupStep.STORE) TextButton(onClick = onCopy, enabled = !busy) {
                            Text(stringResource(R.string.account_flow_copy_again))
                        }
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_backup_enable),
                            description = "",
                            checked = state.wantsBackup,
                            enabled = !state.alreadyEnabled && !state.authenticating,
                            onCheckedChange = onChooseBackup,
                        )
                        if (state.wantsBackup) Text(stringResource(
                            if (state.alreadyEnabled) R.string.account_flow_existing else R.string.account_flow_schedule))
                        Text(stringResource(R.string.backup_storage_short))
                    }
                    AccountBackupStep.SUCCESS -> Text(stringResource(
                        if (!state.wantsBackup) R.string.account_flow_key_only_success
                        else if (state.alreadyEnabled) R.string.account_flow_existing_success
                        else R.string.account_flow_success))
                    AccountBackupStep.ERROR -> Text(stringResource(
                        if (state.alreadyEnabled) R.string.account_flow_existing_error else R.string.account_flow_error))
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state.step) {
                AccountBackupStep.COPY -> TextButton(onClick = onCopy, enabled = !busy, modifier = Modifier.testTag("account_flow_copy")) {
                    Text(stringResource(R.string.account_flow_copy))
                }
                AccountBackupStep.STORE -> TextButton(onClick = onConfirmStored, enabled = !busy, modifier = Modifier.testTag("account_flow_confirm")) {
                    Text(stringResource(if (state.wantsBackup) R.string.account_flow_store_and_start else R.string.account_flow_store_only))
                }
                AccountBackupStep.ERROR -> TextButton(onClick = onConfirmStored, modifier = Modifier.testTag("account_flow_retry")) {
                    Text(stringResource(R.string.account_flow_retry))
                }
                AccountBackupStep.SUCCESS -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (!busy && state.step != AccountBackupStep.SUCCESS) TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
