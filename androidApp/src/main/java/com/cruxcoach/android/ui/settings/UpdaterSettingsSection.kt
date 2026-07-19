package com.cruxcoach.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.updater.PipelineStage
import com.cruxcoach.android.updater.UpdateAutomationMode
import com.cruxcoach.android.updater.UpdateNotificationReliabilityHelper

/**
 * Settings section for the in-app updater (§6.15). Layout matches the
 * spec's ASCII mock: permission nudge → status + Check now → toggles →
 * pending-update inline row (only when relevant). When the install
 * source is gated (Zapstore), the whole section collapses to a single
 * info row (§6.6).
 */
@Composable
internal fun UpdaterSettingsSection(
    viewModel: UpdaterSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (viewModel.storeGated) {
        Text(
            text = stringResource(R.string.updater_settings_gated_info),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNotificationNudge()
                viewModel.refreshInstallPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkingNow by viewModel.checkingNow.collectAsStateWithLifecycle()
    val nudgeVisible by viewModel.notificationNudgeVisible.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val dialogRequested by viewModel.downloadDialogRequested.collectAsStateWithLifecycle()
    val installPermissionGranted by viewModel.installPermissionGranted.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (nudgeVisible) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.updater_settings_nudge_title),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = stringResource(R.string.updater_settings_nudge_body))
                    OutlinedButton(onClick = {
                        val intent = UpdateNotificationReliabilityHelper.nudgeIntent(context)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }) {
                        Text(stringResource(R.string.updater_settings_nudge_enable))
                    }
                }
            }
        }

        // Status row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.updater_settings_status_title),
                    fontWeight = FontWeight.Bold,
                )
                val lastCheckText = state.lastCheckAtEpochMs?.let {
                    val relative = DateUtils.getRelativeTimeSpanString(
                        it,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    stringResource(R.string.updater_settings_status_last_check, relative)
                } ?: stringResource(R.string.updater_settings_status_never)
                Text(
                    text = lastCheckText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { viewModel.checkNow() }, enabled = !checkingNow) {
                Text(stringResource(R.string.updater_settings_check_now))
            }
        }

        ToggleSettingRow(
            title = stringResource(R.string.updater_settings_auto_check),
            description = stringResource(R.string.updater_settings_auto_check_desc),
            checked = state.autoCheckEnabled,
            onCheckedChange = viewModel::setAutoCheck,
        )

        if (state.autoCheckEnabled) {
            Text(
                text = stringResource(R.string.updater_settings_automation_title),
                fontWeight = FontWeight.Bold,
            )
            val modes = UpdateAutomationMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    val label = when (mode) {
                        UpdateAutomationMode.NOTIFY -> R.string.updater_mode_notify
                        UpdateAutomationMode.AUTO_DOWNLOAD -> R.string.updater_mode_download
                        UpdateAutomationMode.AUTO_INSTALL -> R.string.updater_mode_install
                    }
                    SegmentedButton(
                        selected = state.automationMode == mode,
                        onClick = { viewModel.setAutomationMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Text(
                text = stringResource(
                    when (state.automationMode) {
                        UpdateAutomationMode.NOTIFY -> R.string.updater_mode_notify_desc
                        UpdateAutomationMode.AUTO_DOWNLOAD -> R.string.updater_mode_download_desc
                        UpdateAutomationMode.AUTO_INSTALL -> R.string.updater_mode_install_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.automationMode != UpdateAutomationMode.NOTIFY) {
                ToggleSettingRow(
                    title = stringResource(R.string.updater_settings_auto_mobile),
                    description = stringResource(R.string.updater_settings_auto_mobile_desc),
                    checked = state.autoDownloadOnMobile,
                    onCheckedChange = viewModel::setAutoDownloadOnMobile,
                )
            }

            if (state.automationMode == UpdateAutomationMode.AUTO_INSTALL) {
                AutomaticInstallInfo(
                    permissionGranted = installPermissionGranted,
                    onGrantPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }
                    },
                )
            }
        }

        var downloadConfirmFor by remember { mutableStateOf<com.cruxcoach.android.updater.UpdateInfo?>(null) }

        val info = state.pendingUpdate()

        // Notification tap → open-dialog request. Auto-surface the dialog
        // here so the user lands directly on the confirmation prompt.
        // Consume the request ONLY after we've actually opened the dialog;
        // otherwise a first composition with info==null (state still
        // loading) would silently swallow the trigger.
        LaunchedEffect(dialogRequested, info, state.pipelineStage) {
            if (dialogRequested && info != null &&
                state.pipelineStage != PipelineStage.READY_TO_INSTALL &&
                state.pipelineStage != PipelineStage.DOWNLOADING
            ) {
                downloadConfirmFor = info
                viewModel.consumeDownloadDialogRequest()
            }
        }

        when {
            info != null && state.pipelineStage == PipelineStage.BLOCKED_CERT_MISMATCH -> {
                CertMismatchRow(onOpen = viewModel::openReleasePage)
            }
            info != null -> {
                Spacer(Modifier.height(4.dp))
                PendingUpdateRow(
                    version = info.versionName,
                    sizeBytes = info.apkSizeBytes,
                    stage = state.pipelineStage,
                    downloadProgress = downloadProgress,
                    onDownload = { downloadConfirmFor = info },
                    onInstall = viewModel::installPending,
                )
            }
        }

        downloadConfirmFor?.let { pending ->
            AlertDialog(
                onDismissRequest = { downloadConfirmFor = null },
                title = { Text(stringResource(R.string.updater_download_confirm_title, pending.versionName)) },
                text = {
                    Text(
                        stringResource(
                            R.string.updater_download_confirm_body,
                            humanSize(pending.apkSizeBytes),
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        downloadConfirmFor = null
                        viewModel.downloadNow()
                    }) {
                        Text(stringResource(R.string.updater_download_confirm_start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadConfirmFor = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AutomaticInstallInfo(
    permissionGranted: Boolean,
    onGrantPermission: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.updater_auto_install_info_title),
                fontWeight = FontWeight.Bold,
            )
            val body = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                    R.string.updater_auto_install_legacy_desc
                permissionGranted -> R.string.updater_auto_install_ready_desc
                else -> R.string.updater_auto_install_permission_desc
            }
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodySmall,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !permissionGranted) {
                OutlinedButton(onClick = onGrantPermission) {
                    Text(stringResource(R.string.updater_auto_install_permission_action))
                }
            }
        }
    }
}

@Composable
private fun PendingUpdateRow(
    version: String,
    sizeBytes: Long,
    stage: PipelineStage,
    downloadProgress: Int?,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.updater_settings_pending_title, version),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.updater_settings_pending_size,
                    humanSize(sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (stage) {
                PipelineStage.READY_TO_INSTALL -> {
                    OutlinedButton(onClick = onInstall) {
                        Text(stringResource(R.string.updater_notif_action_install))
                    }
                }
                PipelineStage.DOWNLOADING -> {
                    val pct = downloadProgress ?: 0
                    Text(
                        text = stringResource(R.string.updater_notif_downloading_body, pct),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PipelineStage.INSTALLING -> {
                    Text(
                        text = stringResource(R.string.updater_settings_installing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    OutlinedButton(onClick = onDownload) {
                        Text(stringResource(R.string.updater_notif_action_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun CertMismatchRow(onOpen: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.updater_settings_cert_title),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.updater_settings_cert_body),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpen) {
                Text(
                    stringResource(R.string.updater_notif_action_open_release),
                    color = Color.Unspecified,
                )
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}
