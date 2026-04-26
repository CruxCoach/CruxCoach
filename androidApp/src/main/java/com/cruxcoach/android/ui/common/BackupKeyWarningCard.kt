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
 * Action surface (any combination of [onCopyNsec] / [onOpenAccount] /
 * [onAcknowledge] can be passed; null means the corresponding button
 * is hidden):
 *   - [onCopyNsec]: primary in-place save action — the card raises a
 *     confirm dialog explaining clipboard sensitivity, then invokes the
 *     callback (which is expected to put nsec on the clipboard with
 *     `sensitive=true`). Hide for AMBER mode (Amber holds the key, so
 *     "copy nsec" doesn't make sense — the user backs up Amber instead).
 *   - [onOpenAccount]: secondary "open the full key-management screen"
 *     navigation — useful for users who want the npub side, the NIP-49
 *     password-encrypted backup option, or to switch to Amber.
 *   - [onAcknowledge]: tertiary "I've saved my key" flow — raises a
 *     final confirm dialog before flipping
 *     [com.cruxcoach.android.data.UserPreferences.keyBackedUp] to true,
 *     so the warning disappears in Settings. Skipped during onboarding
 *     and what's-new (those are first-encounter moments — let the user
 *     act first, then acknowledge in Settings later).
 */
@Composable
fun BackupKeyWarningCard(
    signerMode: SignerMode,
    modifier: Modifier = Modifier,
    onCopyNsec: (() -> Unit)? = null,
    onOpenAccount: (() -> Unit)? = null,
    onAcknowledge: (() -> Unit)? = null,
    showPostOnboardingHint: Boolean = false,
) {
    var showAckDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }

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
            // Primary action — direct in-place copy. Hidden in Amber mode
            // (Amber holds the key, app never has the raw nsec to copy).
            if (onCopyNsec != null && signerMode == SignerMode.LOCAL) {
                Button(
                    onClick = { showCopyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_key_warning_copy_key))
                }
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

    // Copy-confirm dialog — explains clipboard sensitivity + "switch to
    // your password manager now". On confirm we invoke the caller's
    // [onCopyNsec], which is expected to actually place nsec on the
    // clipboard (sensitive=true) and surface a brief snackbar.
    if (showCopyDialog && onCopyNsec != null) {
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text(stringResource(R.string.backup_key_warning_copy_dialog_title)) },
            text = { Text(stringResource(R.string.backup_key_warning_copy_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCopyDialog = false
                    onCopyNsec()
                }) {
                    Text(stringResource(R.string.backup_key_warning_copy_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) {
                    Text(stringResource(R.string.backup_key_warning_ack_cancel))
                }
            },
        )
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
