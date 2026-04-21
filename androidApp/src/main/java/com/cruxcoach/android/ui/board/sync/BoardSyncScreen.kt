package com.cruxcoach.android.ui.board.sync

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSyncScreen(
    onSyncComplete: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: BoardSyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check network when screen resumes (e.g. returning from system settings)
    LaunchedEffect(Unit) {
        viewModel.checkNetwork()
    }

    // Network unavailable dialog
    if (state.showNetworkDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNetworkDialog() },
            icon = {
                Icon(
                    Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = { Text(stringResource(R.string.board_sync_no_network_title)) },
            text = {
                Text(stringResource(R.string.board_sync_no_network_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissNetworkDialog()
                        context.startActivity(
                            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                ) {
                    Text(stringResource(R.string.board_sync_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNetworkDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Data is up to date — offer force download
    if (state.showUpToDateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpToDateDialog() },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = { Text(stringResource(R.string.board_sync_up_to_date_title)) },
            text = {
                Text(stringResource(R.string.board_sync_up_to_date_message))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.forceSync() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                ) {
                    Text(stringResource(R.string.board_sync_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpToDateDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
                    modifier = Modifier.size(40.dp)
                )
            },
            title = { Text(stringResource(R.string.board_sync_local_import_title)) },
            text = {
                Text(stringResource(R.string.board_sync_local_import_message, host))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmLocalImport() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.testTag("board_sync_local_import_confirm")
                ) {
                    Text(stringResource(R.string.board_sync_local_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLocalImport() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // WiFi required dialog (online sync is ~30 MB)
    if (state.showWifiDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWifiDialog() },
            icon = {
                Icon(
                    Icons.Default.NetworkWifi,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = { Text(stringResource(R.string.board_sync_wifi_required_title)) },
            text = {
                Text(stringResource(R.string.board_sync_wifi_required_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissWifiDialog()
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                ) {
                    Text(stringResource(R.string.board_sync_wifi_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissWifiDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.board_sync_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Network warning
            if (!state.networkAvailable) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = ErrorRed.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SignalWifiOff,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.board_sync_no_network_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // WiFi warning (online but no WiFi)
            if (state.networkAvailable && !state.wifiConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = OrangeAccent.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.NetworkWifi,
                            contentDescription = null,
                            tint = OrangeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.board_sync_no_wifi_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrangeAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Board database sync
            DatabaseImportSection(
                state = state,
                onStartSync = { viewModel.startApiSync() },
                onDismissError = { viewModel.clearError() },
                onReportBug = { error ->
                    onNavigateToBugReport(
                        context.getString(R.string.error_bug_report_sync_title),
                        error
                    )
                    viewModel.clearError()
                }
            )

            // Show board model selection after first sync completes
            LaunchedEffect(state.syncComplete) {
                if (state.syncComplete) {
                    viewModel.checkFirstSyncModelSelection()
                }
            }

            if (modelState.showDialog && modelState.productSizes.isNotEmpty()) {
                com.cruxcoach.android.ui.settings.BoardModelSelectionDialog(
                    productSizes = modelState.productSizes,
                    selectedId = modelState.selectedId,
                    onConfirm = { viewModel.confirmBoardModel(it) },
                    onDismiss = { viewModel.dismissModelDialog() }
                )
            }

            // "Go to browser" button after sync completes
            if (state.syncComplete) {
                Button(
                    onClick = onSyncComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_to_browser"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.board_sync_to_browser), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DatabaseImportSection(
    state: BoardSyncState,
    onStartSync: () -> Unit,
    onDismissError: () -> Unit = {},
    onReportBug: (error: String) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.board_sync_db_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                stringResource(R.string.board_sync_db_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Already synced: show status + re-sync button
            if (state.alreadyImported && !state.isSyncing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("board_sync_complete")
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(R.string.board_sync_db_synced),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                        state.lastSyncTimestamp?.let { ts ->
                            Text(
                                stringResource(R.string.board_sync_last_sync, formatTimestamp(ts)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Re-download button (always full download, no delta)
                OutlinedButton(
                    onClick = onStartSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_update"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_sync_redownload), fontWeight = FontWeight.Bold)
                }
            }

            // First-time sync: download from Blossom
            if (!state.alreadyImported && !state.isSyncing) {
                Button(
                    onClick = onStartSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_start"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_sync_update_online), fontWeight = FontWeight.Bold)
                }
            }

            // Sync in progress — step-by-step checklist
            if (state.isSyncing) {
                SyncProgressChecklist(
                    step = state.importStep,
                    modifier = Modifier.testTag("board_sync_progress")
                )
            }

            // Error message (when not syncing)
            if (!state.isSyncing) {
                state.errorMessage?.let { error ->
                    com.cruxcoach.android.ui.common.ErrorCard(
                        error = error,
                        onDismiss = { onDismissError() },
                        onReportBug = {
                            onReportBug(error)
                        }
                    )
                }
            }
        }
    }
}

/** Step status for the progress checklist. */
private enum class StepStatus { PENDING, ACTIVE, DONE }

@Composable
private fun SyncProgressChecklist(
    step: ImportStep?,
    modifier: Modifier = Modifier
) {
    // Blossom: FetchingManifest(0), DownloadChunk(1), ImportClimbs(2),
    // ImportStats(3), ImportLayout(4), Finalizing(5), Done(6)
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Step 0: Fetching manifest
        val manifestStatus = when {
            stepIndex > 0 -> StepStatus.DONE
            stepIndex == 0 -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        SyncStepRow(stringResource(R.string.board_sync_step_fetch_manifest), manifestStatus)

        // Step 1: Download chunks
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
        SyncStepRow(stringResource(R.string.board_sync_step_download_apk), dlStatus, dlDetail, dlProgress)

        val climbIdx = 2
        val climbStatus = when {
            stepIndex > climbIdx -> StepStatus.DONE
            stepIndex == climbIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val climbDetail = if (step is ImportStep.ImportClimbs && step.total > 0) {
            if (step.scanned == 0) {
                // Bulk import (atomic SQL) — no per-row progress available
                "%,d Climbs…".format(step.total)
            } else {
                val isDelta = step.scanned != step.inserted
                if (isDelta) {
                    "%,d / %,d  (%,d neu)".format(step.scanned, step.total, step.inserted)
                } else {
                    "%,d / %,d".format(step.scanned, step.total)
                }
            }
        } else if (step is ImportStep.Done) {
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
                // Bulk import (atomic SQL) — no per-row progress available
                "%,d Stats…".format(step.total)
            } else {
                val isDelta = step.scanned != step.inserted
                if (isDelta) {
                    "%,d / %,d  (%,d neu)".format(step.scanned, step.total, step.inserted)
                } else {
                    "%,d / %,d".format(step.scanned, step.total)
                }
            }
        } else if (step is ImportStep.Done) {
            "%,d".format(step.stats)
        } else null
        val statProgress = if (step is ImportStep.ImportStats && step.total > 0 && step.scanned > 0) {
            step.scanned.toFloat() / step.total.toFloat()
        } else null
        SyncStepRow(stringResource(R.string.board_sync_step_import_stats), statStatus, statDetail, statProgress)

        val layoutIdx = 4
        val layoutStatus = when {
            stepIndex > layoutIdx -> StepStatus.DONE
            stepIndex == layoutIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val layoutDetail = if (step is ImportStep.ImportLayout && step.count > 0) {
            "%,d".format(step.count)
        } else if (step is ImportStep.Done) {
            "%,d Placements".format(step.placements)
        } else null
        SyncStepRow(stringResource(R.string.board_sync_step_import_layout), layoutStatus, layoutDetail)

        // Step 5: Finalisieren — index rebuild, move-count backfill,
        // denormalized refresh. Without this row the user just sees
        // "Statistiken importieren 100%" frozen for up to 2min.
        val finalizeIdx = 5
        val finalizeStatus = when {
            stepIndex > finalizeIdx -> StepStatus.DONE
            stepIndex == finalizeIdx -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val finalizeDetail = if (step is ImportStep.Finalizing) step.phase else null
        SyncStepRow(stringResource(R.string.board_sync_step_finalize), finalizeStatus, finalizeDetail)

        // Debug: show metadata counters after import
        if (step is ImportStep.Done && step.nomatchCount > 0) {
            Text(
                "NM: %,d".format(step.nomatchCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncStepRow(
    label: String,
    status: StepStatus,
    detail: String? = null,
    progress: Float? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Status icon
        when (status) {
            StepStatus.DONE -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            StepStatus.ACTIVE -> CircularProgressIndicator(
                color = OrangeAccent,
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            StepStatus.PENDING -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp)
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
                }
            )
            // Progress bar for active steps with known total
            if (status == StepStatus.ACTIVE && progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.2f)
                )
            }
        }

        // Detail text on the right
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = when (status) {
                    StepStatus.DONE -> SuccessGreen
                    StepStatus.ACTIVE -> OrangeAccent
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
    }
}

private fun formatTimestamp(iso: String): String {
    // Format "2026-03-03T14:30:00" -> "03.03.2026, 14:30"
    return try {
        val parts = iso.split("T")
        val dateParts = parts[0].split("-")
        val time = parts.getOrElse(1) { "00:00" }.take(5)
        "${dateParts[2]}.${dateParts[1]}.${dateParts[0]}, $time"
    } catch (_: Exception) {
        iso
    }
}
