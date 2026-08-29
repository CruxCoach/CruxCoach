package com.cruxcoach.android.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.cruxcoach.android.R

/** Explicit membership boundary for an unauthenticated nearby BLE session. */
@Composable
fun SuccessorJoinDialog(
    hostName: String,
    onJoin: () -> Unit,
    onKeepQueue: () -> Unit,
) {
    val displayName = hostName.ifBlank {
        stringResource(R.string.ble_successor_join_unknown_name)
    }
    AlertDialog(
        onDismissRequest = onKeepQueue,
        title = {
            Text(
                stringResource(R.string.ble_successor_join_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(stringResource(R.string.ble_successor_join_message, displayName))
        },
        confirmButton = {
            TextButton(
                onClick = onJoin,
                modifier = Modifier.testTag("successor_join_confirm"),
            ) {
                Text(stringResource(R.string.ble_successor_join_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepQueue,
                modifier = Modifier.testTag("successor_join_keep_queue"),
            ) {
                Text(stringResource(R.string.ble_successor_join_keep_queue))
            }
        },
    )
}
