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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * without their backup key, the cloud backup cannot be restored on any
 * other device — the encryption key is the single point of recovery.
 *
 * Two body variants:
 *   - [SignerMode.LOCAL]: save the key in a password manager
 *     (suggests Bitwarden / 1Password / KeePass).
 *   - [SignerMode.AMBER]: rely on Amber's own backup, since Amber holds
 *     the key on the user's behalf.
 *
 * Surfaces:
 *   - Onboarding (backup toggle on): both callbacks typically null because
 *     the key may not yet exist and the flow is linear; pass
 *     [showPostOnboardingHint] = true so the card points at Settings →
 *     CruxCoach Account afterwards.
 *   - What's-new dialog (backup toggle on): pass [onOpenAccount] so the
 *     button dismisses the dialog and navigates to the account screen.
 *   - Settings → Cloud-Backup section: pass both [onOpenAccount] and
 *     [onAcknowledge]. The card is meant to stay visible until
 *     [onAcknowledge] sets [com.cruxcoach.android.data.UserPreferences.keyBackedUp]
 *     to true via the existing acknowledgement flow.
 */
@Composable
fun BackupKeyWarningCard(
    signerMode: SignerMode,
    modifier: Modifier = Modifier,
    onOpenAccount: (() -> Unit)? = null,
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
            if (onOpenAccount != null || onAcknowledge != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onOpenAccount?.let { handler ->
                        OutlinedButton(onClick = handler) {
                            Text(stringResource(R.string.backup_key_warning_view_account))
                        }
                    }
                    onAcknowledge?.let {
                        TextButton(onClick = { showAckDialog = true }) {
                            Text(stringResource(R.string.backup_key_warning_acknowledged))
                        }
                    }
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
