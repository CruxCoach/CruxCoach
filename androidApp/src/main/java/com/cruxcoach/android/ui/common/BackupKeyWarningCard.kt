package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * actual key-view + copy + "I've saved my key" acknowledgement all live
 * on KeyManagementScreen, so we don't duplicate them across onboarding
 * / what's new / settings — one canonical place to view, save, and
 * acknowledge; every other surface just points there.
 */
@Composable
fun BackupKeyWarningCard(
    signerMode: SignerMode,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit,
    showPostOnboardingHint: Boolean = false,
) {
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
            Button(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_key_warning_view_account))
            }
        }
    }
}
