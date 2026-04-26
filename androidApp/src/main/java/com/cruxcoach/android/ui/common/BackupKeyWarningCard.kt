package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.nostr.SignerMode

/**
 * Prominent warning card surfaced wherever the user is enabling or has
 * already enabled the encrypted-cloud-backup feature. Reminds them that
 * without their CruxCoach Account key, the cloud backup cannot be
 * restored on any other device — and the Nostr identity behind their
 * account is gone too, since both share the same nsec.
 *
 * Action surface: a single "Open CruxCoach Account" navigation. The
 * actual key-view + copy lives on the KeyManagementScreen, so we don't
 * duplicate it across onboarding/what's new/settings — one canonical
 * place to view and save the key, every other surface just points there.
 *
 * The Settings → Cloud-Backup section additionally gets the
 * "I've saved my key" acknowledgement button, since it's persistent and
 * needs a way to disappear once the user has stored the key elsewhere
 * (the KeyManagementScreen warning shares the same flag).
 */
@Composable
fun BackupKeyWarningCard(
    signerMode: SignerMode,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit,
    onAcknowledge: (() -> Unit)? = null,
    showPostOnboardingHint: Boolean = false,
) {
    var showAckDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.backup_key_warning_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                stringResource(
                    if (signerMode == SignerMode.AMBER) R.string.backup_key_warning_body_amber
                    else R.string.backup_key_warning_body_local
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (showPostOnboardingHint) {
                Text(
                    stringResource(R.string.backup_key_warning_post_onboarding_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            // Primary action: navigate to the canonical CruxCoach Account
            // screen where the user can view + copy + decide.
            Button(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_key_warning_view_account))
            }
            // Secondary "I've saved my key" — only shown in Settings,
            // where the card is persistent and needs a way to disappear
            // once the user has stored the key. Confirm dialog ensures
            // this isn't an accidental tap.
            onAcknowledge?.let {
                TextButton(onClick = { showAckDialog = true }) {
                    Text(stringResource(R.string.backup_key_warning_acknowledged))
                }
            }
        }
    }

    if (showAckDialog && onAcknowledge != null) {
        AlertDialog(
            onDismissRequest = { showAckDialog = false },
            title = { Text(stringResource(R.string.backup_key_warning_ack_dialog_title)) },
            text = { Text(stringResource(R.string.backup_key_warning_ack_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAckDialog = false
                    onAcknowledge()
                }) {
                    Text(stringResource(R.string.backup_key_warning_ack_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAckDialog = false }) {
                    Text(stringResource(R.string.backup_key_warning_ack_cancel))
                }
            },
        )
    }
}
