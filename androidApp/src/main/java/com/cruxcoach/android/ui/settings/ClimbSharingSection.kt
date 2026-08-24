package com.cruxcoach.android.ui.settings

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.WarningYellow

@Composable
internal fun ClimbSharingSection(
    climbSharing: ClimbSharingSettings,
    onSharingChange: (Boolean) -> Unit,
    relayManualStart: Boolean = false,
    onRelayManualStartChange: (Boolean) -> Unit = {},
) {
    Text(
        stringResource(R.string.settings_sharing_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    // Hardware support warning
    if (climbSharing.advertisingSupported == false) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = WarningYellow.copy(alpha = 0.15f)
            )
        ) {
            Text(
                stringResource(R.string.settings_sharing_no_ble_advertising),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = WarningYellow
            )
        }
        return
    }

    // Bluetooth off hint
    if (climbSharing.advertisingSupported == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                stringResource(R.string.settings_sharing_bluetooth_off),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Permission launcher for BLE sharing (advertising + scanning permissions)
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onSharingChange(true)
        }
    }

    // Sharing toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_sharing_enable), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_sharing_enable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = climbSharing.enabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    // Collect all required permissions: advertising + base BLE/location
                    val advPerms = BlePermissionHelper.getAdvertisingPermissions()
                    val basePerms = BlePermissionHelper.getRequiredPermissions()
                    val allPerms = (advPerms.toSet() + basePerms.toSet()).toTypedArray()
                    val missingPerms = allPerms.filter {
                        ContextCompat.checkSelfPermission(context, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }.toTypedArray()
                    if (missingPerms.isEmpty()) {
                        onSharingChange(true)
                    } else {
                        permissionLauncher.launch(missingPerms)
                    }
                } else {
                    onSharingChange(false)
                }
            },
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }

    // The normal mode follows the physical board connection. This opt-in is
    // only for users who deliberately want the old manual start button.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_relay_manual_start),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_relay_manual_start_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = relayManualStart,
            onCheckedChange = onRelayManualStartChange,
            modifier = Modifier.testTag("settings_relay_manual_start"),
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent),
        )
    }

}
