package com.cruxcoach.android.ui.common

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.boardcell.BoardCellPlatformPolicy
import com.cruxcoach.android.data.SessionVisibility

/** Per-run privacy choice shown before a host session starts. */
@Composable
fun SessionVisibilityDialog(
    onDismiss: () -> Unit,
    onSelect: (SessionVisibility) -> Unit,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.isNotEmpty() && results.values.all { it }) {
            currentOnSelect(SessionVisibility.JOINABLE)
        } else {
            permissionDenied = true
        }
    }

    fun selectJoinable() {
        permissionDenied = false
        val permissions = BlePermissionHelper.getSessionHostingPermissions().distinct()
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            currentOnSelect(SessionVisibility.JOINABLE)
        } else {
            permissionLauncher.launch(missing)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.ble_session_visibility_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ble_session_visibility_message))
                if (BoardCellPlatformPolicy.sharedPlaylistAvailable(Build.VERSION.SDK_INT)) {
                    VisibilityOption(
                        title = stringResource(R.string.ble_session_visibility_joinable),
                        description = stringResource(R.string.ble_session_visibility_joinable_desc),
                        testTag = "session_visibility_joinable",
                        onClick = ::selectJoinable,
                    )
                }
                VisibilityOption(
                    title = stringResource(R.string.ble_session_visibility_local),
                    description = stringResource(R.string.ble_session_visibility_local_desc),
                    testTag = "session_visibility_local",
                    onClick = { currentOnSelect(SessionVisibility.LOCAL_ONLY) },
                )
                if (permissionDenied) {
                    Text(
                        stringResource(R.string.ble_session_visibility_permission_denied),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun VisibilityOption(
    title: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
