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
 * "What's new in 0.1.5" dialog announcing the FEAT-007 Phase 1 board
 * picker — *Settings → Board-Größe → Ändern → Halle suchen*. Discovery
 * surface only; the actual picker dialog lives one tap deeper in the
 * Settings screen.
 */
@Composable
internal fun GymBoardPickerWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.whatsnew_gym_picker_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.whatsnew_gym_picker_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_gym_picker_hint),
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
                Text(stringResource(R.string.whatsnew_gym_picker_action_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_gym_picker_action_later))
            }
        },
    )
}
