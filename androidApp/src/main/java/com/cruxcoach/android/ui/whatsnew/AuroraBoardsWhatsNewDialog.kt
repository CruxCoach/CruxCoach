package com.cruxcoach.android.ui.whatsnew

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * "What's new in 0.2.0" dialog announcing FEAT-031 — the Aurora-family
 * boards (Tension, Grasshopper, Decoy, So iLL, Touchstone) becoming
 * interactive alongside Kilter + MoonBoard. Same shape as
 * [MoonBoardWhatsNewDialog]: a pure discovery surface. "Pick my board"
 * navigates to Settings where the board picker now offers them; "Later"
 * dismisses. Reuses the generic MoonBoard action labels.
 */
@Composable
internal fun AuroraBoardsWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.whatsnew_aurora_title))
        },
        text = {
            // AlertDialog clips its text slot rather than scrolling it, and
            // these dialogs cannot be dismissed by tapping outside — so on a
            // short screen, or at a large system font size, the part below the
            // fold was simply unreachable.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.whatsnew_aurora_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_aurora_hint),
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
