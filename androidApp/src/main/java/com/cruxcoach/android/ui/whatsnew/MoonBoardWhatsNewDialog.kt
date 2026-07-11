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
 * "What's new in 0.2.0" dialog announcing FEAT-027 MoonBoard support — the
 * release headline. Same shape as [BoardLocationsMapWhatsNewDialog]: a pure
 * discovery surface for users upgrading from 0.1.4. "Pick my board"
 * navigates to Settings where the board-type picker now offers MoonBoard;
 * "Later" dismisses without nagging again.
 */
@Composable
internal fun MoonBoardWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Dismiss only via an explicit button ("Später"/"Board wählen"), never a
        // stray scrim tap or back press. onDismiss -> dismissCurrent() advances the
        // lastSeen watermark, so an accidental dismissal — easy during the busy
        // first-0.2.0-launch board re-sync — would permanently mark this upgrade
        // announcement read without the user ever seeing it (the reported bug).
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
        title = {
            Text(stringResource(R.string.whatsnew_moonboard_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.whatsnew_moonboard_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_moonboard_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onNavigateToSettings()
            }) {
                Text(stringResource(R.string.whatsnew_moonboard_action_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_moonboard_action_later))
            }
        },
    )
}
