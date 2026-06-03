package com.cruxcoach.android.ui.board.sync

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.settings.BoardPickerDialog
import com.cruxcoach.android.ui.theme.*

/**
 * Screen-independent board-database sync UI — the card variant of
 * [BoardSyncScreen]. Drops inline into any parent Column so the user
 * can kick off and watch the sync without a navigation hop (Onboarding
 * step 1 and Settings → Datenverwaltung both embed this). The hosting
 * [BoardSyncViewModel] proxies the application-scoped sync manager, so
 * multiple instances across screens share a single run.
 */
@Composable
fun BoardSyncInlineCard(
    modifier: Modifier = Modifier,
    viewModel: BoardSyncViewModel = hiltViewModel(),
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    /** When true, fires the API sync automatically on first composition
     *  if no board data is present yet. Used by the onboarding's
     *  BOARD_SETUP step so the user doesn't have to scroll past the
     *  intro and tap "Jetzt laden" before the download begins. Default
     *  false keeps every other call site (BoardBrowser, Settings)
     *  manual-trigger as before. */
    autoStartIfNeeded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.checkNetwork() }
    if (autoStartIfNeeded) {
        // One-shot on first composition. The VM's startApiSyncIfNeeded
        // guards on alreadyImported + isSyncing so a re-entry to the
        // onboarding (or returning user) doesn't kick off a redundant
        // re-download.
        LaunchedEffect(Unit) { viewModel.startApiSyncIfNeeded() }
    }

    if (state.showNetworkDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNetworkDialog() },
            icon = {
                Icon(
                    Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_no_network_title)) },
            text = { Text(stringResource(R.string.board_sync_no_network_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissNetworkDialog()
                        context.startActivity(
                            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) { Text(stringResource(R.string.board_sync_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNetworkDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Local-share import consent. The tap on the hotspot's landing page
    // happens in an attacker-controllable browser, so this in-app dialog
    // is the real consent moment — it shows the source host and runs in
    // CruxCoach's own UI.
    state.pendingLocalImportUrl?.let { url ->
        val host = remember(url) {
            runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissLocalImport() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_local_import_title)) },
            text = { Text(stringResource(R.string.board_sync_local_import_message, host)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmLocalImport() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.testTag("board_sync_local_import_confirm"),
                ) { Text(stringResource(R.string.board_sync_local_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLocalImport() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.showWifiDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWifiDialog() },
            icon = {
                Icon(
                    Icons.Default.NetworkWifi,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_wifi_required_title)) },
            text = { Text(stringResource(R.string.board_sync_wifi_required_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissWifiDialog()
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) { Text(stringResource(R.string.board_sync_wifi_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissWifiDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    LaunchedEffect(state.syncComplete) {
        if (state.syncComplete) {
            viewModel.checkFirstSyncModelSelection()
        }
    }
    if (modelState.showDialog) {
        // FEAT-031: the one shared board picker (same as Settings / Filter /
        // Onboarding) — identical state + the full board list incl. Aurora.
        BoardPickerDialog(
            onDismiss = { viewModel.dismissModelDialog() },
            onSelected = { viewModel.dismissModelDialog() },
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            !state.networkAvailable -> NetworkWarningBanner()
            !state.wifiConnected -> NoWifiWarningBanner()
        }
        DatabaseImportSection(
            state = state,
            onStartSync = { viewModel.startApiSync() },
            onDismissError = { viewModel.clearError() },
            onReportBug = { error ->
                onNavigateToBugReport(
                    context.getString(R.string.error_bug_report_sync_title),
                    error,
                )
                viewModel.clearError()
            },
        )
    }
}

@Composable
private fun NetworkWarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.SignalWifiOff, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.board_sync_no_network_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorRed,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NoWifiWarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.NetworkWifi, null, tint = OrangeAccent, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.board_sync_no_wifi_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = OrangeAccent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DatabaseImportSection(
    state: BoardSyncState,
    onStartSync: () -> Unit,
    onDismissError: () -> Unit,
    onReportBug: (error: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.board_sync_db_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                stringResource(R.string.board_sync_db_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.alreadyImported && !state.isSyncing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("board_sync_complete"),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(R.string.board_sync_db_synced),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold,
                        )
                        state.lastSyncTimestamp?.let { ts ->
                            Text(
                                stringResource(R.string.board_sync_last_sync, formatTimestamp(ts)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onStartSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_update"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_sync_redownload), fontWeight = FontWeight.Bold)
                }
            }

            if (!state.alreadyImported && !state.isSyncing) {
                Button(
                    onClick = onStartSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_start"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_sync_update_online), fontWeight = FontWeight.Bold)
                }
            }

            if (state.isSyncing) {
                // FEAT-031: one progress section per board with an active sync
                // stream (Kilter, MoonBoard, and each Aurora board), driven by
                // the per-board state map instead of two hardcoded sections.
                state.boardSteps.forEach { (brand, step) ->
                    Text(
                        brand.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SyncProgressChecklist(
                        step = step,
                        // Only placement boards (Kilter + Aurora) have a layout
                        // step; MoonBoard ships no geometry.
                        showLayoutStep = brand.usesAuroraProtocol,
                        modifier = Modifier.testTag("board_sync_progress_${brand.wireValue}"),
                    )
                    state.boardErrors[brand]?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.board_sync_section_error),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (!state.isSyncing) {
                state.errorMessage?.let { error ->
                    com.cruxcoach.android.ui.common.ErrorCard(
                        error = error,
                        onDismiss = { onDismissError() },
                        onReportBug = { onReportBug(error) },
                    )
                }
            }
        }
    }
}

private enum class StepStatus { PENDING, ACTIVE, DONE }

@Composable
private fun SyncProgressChecklist(
    step: ImportStep?,
    modifier: Modifier = Modifier,
    /** The MoonBoard snapshot has no separate placement-import phase, so
     *  its checklist omits the layout row. */
    showLayoutStep: Boolean = true,
) {
    val stepIndex = when (step) {
        is ImportStep.FetchingManifest, is ImportStep.CheckingUpdate -> 0
        is ImportStep.DownloadChunk, is ImportStep.Download -> 1
        is ImportStep.Extract -> 1
        is ImportStep.ImportClimbs -> 2
        is ImportStep.ImportStats -> 3
        is ImportStep.ImportLayout -> 4
        is ImportStep.Finalizing -> 5
        is ImportStep.Done -> 6
        else -> -1
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val manifestStatus = when {
            stepIndex > 0 -> StepStatus.DONE
            stepIndex == 0 -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        SyncStepRow(stringResource(R.string.board_sync_step_fetch_manifest), manifestStatus)

        val dlStatus = when {
            stepIndex > 1 -> StepStatus.DONE
            stepIndex == 1 -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val dlDetail = if (step is ImportStep.DownloadChunk && step.cumulativeTotalBytes > 0) {
            val readMb = step.cumulativeBytesRead / 1_048_576.0
            val totalMb = step.cumulativeTotalBytes / 1_048_576.0
            val pct = (step.cumulativeBytesRead * 100 / step.cumulativeTotalBytes).toInt()
            "%.1f / %.1f MB (%d%%)".format(readMb, totalMb, pct)
        } else if (step is ImportStep.DownloadChunk) {
            "${step.chunkIndex + 1}/${step.totalChunks}"
        } else null
        val dlProgress = if (step is ImportStep.DownloadChunk && step.cumulativeTotalBytes > 0) {
            step.cumulativeBytesRead.toFloat() / step.cumulativeTotalBytes.toFloat()
        } else null
        SyncStepRow(stringResource(R.string.board_sync_step_download_db), dlStatus, dlDetail, dlProgress)

        val climbIdx = 2
        val climbStatus = when {
            stepIndex > climbIdx -> StepStatus.DONE
            stepIndex == climbIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val climbDetail = if (step is ImportStep.ImportClimbs && step.total > 0) {
            if (step.scanned == 0) {
                stringResource(R.string.board_sync_detail_climbs_count, step.total)
            } else {
                val isDelta = step.scanned != step.inserted
                if (isDelta) stringResource(
                    R.string.board_sync_detail_progress_with_new,
                    step.scanned, step.total, step.inserted,
                )
                else stringResource(
                    R.string.board_sync_detail_progress, step.scanned, step.total,
                )
            }
        } else if (step is ImportStep.Done && step.climbs > 0) {
            "%,d".format(step.climbs)
        } else null
        val climbProgress = if (step is ImportStep.ImportClimbs && step.total > 0 && step.scanned > 0) {
            step.scanned.toFloat() / step.total.toFloat()
        } else null
        SyncStepRow(stringResource(R.string.board_sync_step_import_climbs), climbStatus, climbDetail, climbProgress)

        val statIdx = 3
        val statStatus = when {
            stepIndex > statIdx -> StepStatus.DONE
            stepIndex == statIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val statDetail = if (step is ImportStep.ImportStats && step.total > 0) {
            if (step.scanned == 0) {
                stringResource(R.string.board_sync_detail_stats_count, step.total)
            } else {
                val isDelta = step.scanned != step.inserted
                if (isDelta) stringResource(
                    R.string.board_sync_detail_progress_with_new,
                    step.scanned, step.total, step.inserted,
                )
                else stringResource(
                    R.string.board_sync_detail_progress, step.scanned, step.total,
                )
            }
        } else if (step is ImportStep.Done && step.stats > 0) {
            "%,d".format(step.stats)
        } else null
        val statProgress = if (step is ImportStep.ImportStats && step.total > 0 && step.scanned > 0) {
            step.scanned.toFloat() / step.total.toFloat()
        } else null
        SyncStepRow(stringResource(R.string.board_sync_step_import_stats), statStatus, statDetail, statProgress)

        if (showLayoutStep) {
            val layoutIdx = 4
            val layoutStatus = when {
                stepIndex > layoutIdx -> StepStatus.DONE
                stepIndex == layoutIdx -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }
            val layoutDetail = if (step is ImportStep.ImportLayout && step.count > 0) {
                "%,d".format(step.count)
            } else if (step is ImportStep.Done && step.placements > 0) {
                stringResource(R.string.board_sync_detail_placements_count, step.placements)
            } else null
            SyncStepRow(stringResource(R.string.board_sync_step_import_layout), layoutStatus, layoutDetail)
        }

        val finalizeIdx = 5
        val finalizeStatus = when {
            stepIndex > finalizeIdx -> StepStatus.DONE
            stepIndex == finalizeIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        SyncStepRow(stringResource(R.string.board_sync_step_finalize), finalizeStatus)

        if (step is ImportStep.Done && step.nomatchCount > 0) {
            Text(
                stringResource(R.string.board_sync_detail_nomatch, step.nomatchCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncStepRow(
    label: String,
    status: StepStatus,
    detail: String? = null,
    progress: Float? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (status) {
            StepStatus.DONE -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(18.dp),
            )
            StepStatus.ACTIVE -> CircularProgressIndicator(
                color = OrangeAccent,
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            StepStatus.PENDING -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (status == StepStatus.ACTIVE) FontWeight.Bold else FontWeight.Normal,
                color = when (status) {
                    StepStatus.DONE -> SuccessGreen
                    StepStatus.ACTIVE -> MaterialTheme.colorScheme.onSurface
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
            if (status == StepStatus.ACTIVE && progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.2f),
                )
            }
        }

        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = when (status) {
                    StepStatus.DONE -> SuccessGreen
                    StepStatus.ACTIVE -> OrangeAccent
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
    }
}

/**
 * Render an ISO-8601 timestamp string as a short, locale-aware date+time.
 * The previous implementation hand-concatenated `dd.MM.yyyy, HH:mm`
 * unconditionally, which is the German format but was also shown to
 * English-locale users. SHORT-style formatting gives `25.04.26, 14:32`
 * for `de`, `4/25/26, 2:32 PM` for `en-US`, etc.
 */
private fun formatTimestamp(iso: String): String {
    return try {
        java.time.Instant.parse(iso)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter
                    .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                    .withLocale(java.util.Locale.getDefault()),
            )
    } catch (_: Exception) {
        iso
    }
}
