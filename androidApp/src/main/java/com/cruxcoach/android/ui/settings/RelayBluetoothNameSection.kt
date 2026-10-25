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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BluetoothNameInput
import com.cruxcoach.android.data.RelayAdapterNameGuard
import com.cruxcoach.android.data.RelayNameIssue
import com.cruxcoach.android.data.RelayNameStatus
import com.cruxcoach.android.ui.theme.WarningYellow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RelayNameViewModel @Inject constructor(
    private val guard: RelayAdapterNameGuard,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val status: StateFlow<RelayNameStatus> = guard.status

    private val _renameFailed = MutableStateFlow(false)
    val renameFailed: StateFlow<Boolean> = _renameFailed.asStateFlow()

    fun refresh() {
        guard.refreshStatus()
    }

    fun restoreNow() {
        guard.restoreIfStale()
    }

    /** The device name from the phone settings, offered for editing; empty when it cannot be one. */
    fun suggestedName(): String = BluetoothNameInput.suggestion(
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull(),
    )

    /** Runs only after the user confirmed [name] in the dialog. */
    fun rename(name: String) {
        viewModelScope.launch { _renameFailed.value = !guard.renameConfirmed(name) }
    }
}

/**
 * Says plainly that sharing renames the whole phone over Bluetooth, and while
 * a name is not back offers to give it back. When CruxCoach does not know the
 * earlier name it suggests the device name from the phone settings in an
 * editable field and renames only after the user confirms; or it opens the
 * phone's own settings. It never renames on a guess.
 */
@Composable
internal fun RelayBluetoothNameSection(viewModel: RelayNameViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val renameFailed by viewModel.renameFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Null while closed; otherwise the name the dialog opens with.
    var dialogName by rememberSaveable { mutableStateOf<String?>(null) }
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
                action = stringResource(R.string.relay_bt_name_choose),
                tag = "relay_bt_name_choose",
                onAction = { dialogName = viewModel.suggestedName() },
                secondAction = stringResource(R.string.relay_bt_name_open_settings),
                secondTag = "relay_bt_name_open_settings",
                onSecondAction = { openDeviceNameSettings(context) },
            )
        }
        if (renameFailed && status.issue == RelayNameIssue.ORIGINAL_UNKNOWN) {
            Text(
                stringResource(R.string.relay_bt_name_rename_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("relay_bt_name_rename_failed"),
            )
        }
    }

    dialogName?.let { initial ->
        ChooseNameDialog(
            initial = initial,
            onConfirm = { name ->
                dialogName = null
                viewModel.rename(name)
            },
            onDismiss = { dialogName = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameIssueCard(
    text: String,
    action: String,
    tag: String,
    onAction: () -> Unit,
    secondAction: String? = null,
    secondTag: String? = null,
    onSecondAction: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = WarningYellow.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAction, modifier = Modifier.testTag(tag)) { Text(action) }
                if (secondAction != null) {
                    TextButton(
                        onClick = onSecondAction,
                        modifier = Modifier.testTag(secondTag.orEmpty()),
                    ) { Text(secondAction) }
                }
            }
        }
    }
}

/**
 * The suggested name is only a starting point: the user may type any name.
 * "Restore" stays off until the name can be used, and nothing changes on
 * "Cancel".
 */
@Composable
private fun ChooseNameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var edited by rememberSaveable { mutableStateOf(false) }
    val problem = BluetoothNameInput.problem(text)
    // An empty field is only an error once the user has typed in it.
    val shownProblem = problem?.takeIf { it != BluetoothNameInput.Problem.BLANK || edited }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relay_bt_name_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (initial.isNotEmpty()) R.string.relay_bt_name_dialog_text
                        else R.string.relay_bt_name_dialog_text_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        edited = true
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.relay_bt_name_field_label)) },
                    isError = shownProblem != null,
                    supportingText = shownProblem?.let { p -> { Text(nameProblemText(p)) } },
                    modifier = Modifier.fillMaxWidth().testTag("relay_bt_name_field"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = problem == null,
                modifier = Modifier.testTag("relay_bt_name_confirm"),
            ) { Text(stringResource(R.string.relay_bt_name_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("relay_bt_name_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun nameProblemText(problem: BluetoothNameInput.Problem): String = when (problem) {
    BluetoothNameInput.Problem.BLANK -> stringResource(R.string.relay_bt_name_error_blank)
    BluetoothNameInput.Problem.SHARING_NAME -> stringResource(R.string.relay_bt_name_error_sharing)
    BluetoothNameInput.Problem.TOO_LONG -> stringResource(R.string.relay_bt_name_error_too_long)
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
