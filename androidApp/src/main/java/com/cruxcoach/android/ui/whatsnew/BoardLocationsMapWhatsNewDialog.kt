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
 * "What's new in 0.1.5" dialog announcing the FEAT-015 board-locations
 * map. Same shape as [AuroraJsonImportWhatsNewDialog]: pure discovery
 * surface for users upgrading from 0.1.4, no settings to capture. Two
 * buttons: "Karte öffnen" navigates straight to the new BOARD_MAP
 * route, "Später" dismisses without nagging again.
 */
@Composable
internal fun BoardLocationsMapWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToBoardMap: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.whatsnew_board_map_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.whatsnew_board_map_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.whatsnew_board_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onNavigateToBoardMap()
            }) {
                Text(stringResource(R.string.whatsnew_board_map_action_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whatsnew_board_map_action_later))
            }
        },
    )
}
