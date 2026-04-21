package com.cruxcoach.android.ui.settings

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.updater.PipelineStage
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
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationNudge()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkingNow by viewModel.checkingNow.collectAsStateWithLifecycle()
    val nudgeVisible by viewModel.notificationNudgeVisible.collectAsStateWithLifecycle()

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

        ToggleRow(
            title = stringResource(R.string.updater_settings_auto_check),
            description = stringResource(R.string.updater_settings_auto_check_desc),
            checked = state.autoCheckEnabled,
            onCheckedChange = viewModel::setAutoCheck,
        )

        ToggleRow(
            title = stringResource(R.string.updater_settings_auto_wifi),
            description = stringResource(R.string.updater_settings_auto_wifi_desc),
            checked = state.autoDownloadOnWifi,
            onCheckedChange = viewModel::setAutoDownloadOnWifi,
        )

        ToggleRow(
            title = stringResource(R.string.updater_settings_auto_mobile),
            description = stringResource(R.string.updater_settings_auto_mobile_desc),
            checked = state.autoDownloadOnMobile,
            onCheckedChange = viewModel::setAutoDownloadOnMobile,
        )

        val info = state.pendingUpdate()
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
                    onDownload = viewModel::downloadNow,
                    onInstall = viewModel::installPending,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PendingUpdateRow(
    version: String,
    sizeBytes: Long,
    stage: PipelineStage,
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
                    Text(
                        text = stringResource(R.string.updater_notif_downloading_body, 0),
                        style = MaterialTheme.typography.bodySmall,
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
                    stringResource(R.string.updater_notif_action_open_codeberg),
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
