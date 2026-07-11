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
 * "What's new in 0.2.1" dialog — leads with the release's own headline
 * features (zone search, Kilter circuits import, share links, cross-board
 * lists) and carries the 0.2.0 board-support recap as a hint line, because
 * that announcement was lost to the accidental-dismiss bug for part of the
 * 0.2.0 cohort (see [WhatsNewItems.RELEASE_021]). "Boards ansehen" keeps
 * the recap's discovery path into the Settings board picker.
 */
@Composable
internal fun Release021WhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Dismiss only via an explicit button — a stray scrim tap or back
        // press would advance the lastSeen watermark and permanently mark
        // this announcement read (the 0.2.0 bug this pattern fixes).
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
        title = {
            Text(stringResource(R.string.whatsnew_021_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.whatsnew_021_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_021_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_021_action_go))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onNavigateToSettings()
            }) {
                Text(stringResource(R.string.whatsnew_021_action_boards))
            }
        },
    )
}
