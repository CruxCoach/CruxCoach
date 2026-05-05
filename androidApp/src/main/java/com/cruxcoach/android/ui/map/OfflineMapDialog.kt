package com.cruxcoach.android.ui.map

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R

/**
 * Shown when the Map screen opens without any network connection. Tiles
 * can't load from OpenFreeMap, so we surface this once on screen entry.
 * The map is still rendered behind so users with cached tiles can dismiss
 * and see their previously-viewed regions.
 */
@Composable
fun OfflineMapDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_offline_dialog_title)) },
        text = { Text(stringResource(R.string.map_offline_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.map_offline_dialog_close))
            }
        },
    )
}
