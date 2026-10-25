package com.cruxcoach.android.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.RelayAdapterNameGuard
import com.cruxcoach.android.data.RelayNameIssue
import com.cruxcoach.android.data.RelayNameStatus
import com.cruxcoach.android.ui.theme.WarningYellow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class RelayNameViewModel @Inject constructor(
    private val guard: RelayAdapterNameGuard,
) : ViewModel() {
    val status: StateFlow<RelayNameStatus> = guard.status

    fun refresh() {
        guard.refreshStatus()
    }

    fun restoreNow() {
        guard.restoreIfStale()
    }
}

/**
 * Says plainly that sharing renames the whole phone over Bluetooth, and while
 * a name is not back offers to give it back, or shows the way to the phone's
 * own settings when CruxCoach does not know the earlier name. It never guesses
 * a name.
 */
@Composable
internal fun RelayBluetoothNameSection(viewModel: RelayNameViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Back from the system settings: the phone may have been renamed there.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.restoreNow() else openAppDetails(context)
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("relay_bt_name"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.relay_bt_name_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        val original = status.originalName
        Text(
            if (status.issue == RelayNameIssue.LENT && original != null) {
                stringResource(R.string.relay_bt_name_lent, original)
            } else {
                stringResource(R.string.relay_bt_name_notice)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status.issue) {
            RelayNameIssue.NONE, RelayNameIssue.LENT -> Unit
            RelayNameIssue.RESTORE_PENDING -> NameIssueCard(
                text = stringResource(R.string.relay_bt_name_pending, original.orEmpty()),
                action = stringResource(R.string.relay_bt_name_restore),
                tag = "relay_bt_name_restore",
                onAction = viewModel::restoreNow,
            )
            RelayNameIssue.NEEDS_PERMISSION -> NameIssueCard(
                text = stringResource(R.string.relay_bt_name_permission, original.orEmpty()),
                action = stringResource(R.string.relay_bt_name_permission_action),
                tag = "relay_bt_name_permission",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        viewModel.restoreNow()
                    }
                },
            )
            RelayNameIssue.ORIGINAL_UNKNOWN -> NameIssueCard(
                text = stringResource(R.string.relay_bt_name_unknown, status.currentName.orEmpty()),
                action = stringResource(R.string.relay_bt_name_open_settings),
                tag = "relay_bt_name_open_settings",
                onAction = { openDeviceNameSettings(context) },
            )
        }
    }
}

@Composable
private fun NameIssueCard(text: String, action: String, tag: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = WarningYellow.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction, modifier = Modifier.testTag(tag)) { Text(action) }
        }
    }
}

/** "About phone" holds the device name on most phones; fall back to the settings home. */
private fun openDeviceNameSettings(context: Context) {
    val primary = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    try {
        context.startActivity(primary)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
    }
}
