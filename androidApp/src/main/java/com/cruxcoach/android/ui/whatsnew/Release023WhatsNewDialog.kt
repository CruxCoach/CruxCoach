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
 * 0.2.3 — competitions.
 *
 * The dismiss button opens the competitions screen rather than settings: the
 * feature is a place to go, not a preference to set, and an announcement that
 * only says "there is a new thing" makes the reader hunt for it.
 */
@Composable
internal fun Release023WhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToCompetitions: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
        title = { Text(stringResource(R.string.whatsnew_023_title)) },
        text = {
            // Same reason as 0.2.2: AlertDialog clips its text slot rather than
            // scrolling it, and these cannot be dismissed by tapping outside.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.whatsnew_023_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_023_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_023_action_go))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onNavigateToCompetitions()
            }) {
                Text(stringResource(R.string.whatsnew_023_action_open))
            }
        },
    )
}
