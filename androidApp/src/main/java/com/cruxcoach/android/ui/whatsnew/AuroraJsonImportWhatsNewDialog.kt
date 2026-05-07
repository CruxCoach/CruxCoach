package com.cruxcoach.android.ui.whatsnew

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * "What's new in 0.1.4" dialog announcing the FEAT-005 Aurora JSON
 * import. Simpler shape than [NostrBackupWhatsNewDialog]: no toggle,
 * no settings to capture — the dialog is just the discovery surface
 * for users who already have an Aurora email export sitting in their
 * Downloads. Two buttons: "Jetzt importieren" navigates to the full
 * `AuroraMigrationScreen`, "Später" just dismisses.
 *
 * Same pattern the spec mandates — see FEAT-005 §6.3.
 */
@Composable
internal fun AuroraJsonImportWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToAuroraMigration: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.whatsnew_aurora_import_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.whatsnew_aurora_import_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_aurora_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onNavigateToAuroraMigration()
            }) {
                Text(stringResource(R.string.whatsnew_aurora_import_action_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_aurora_import_action_later))
            }
        },
    )
}
