package com.cruxcoach.android.ui.board.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.settings.BoardPickerDialog
import com.cruxcoach.android.ui.settings.GymBoardSearchSheet
import com.cruxcoach.android.ui.theme.*
import java.io.File

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
    val boardCounts by viewModel.boardCounts.collectAsStateWithLifecycle()
    val activeBrand by viewModel.activeBrand.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val syncBugReportTitle = stringResource(R.string.error_bug_report_sync_title)
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.confirmOfflineShare()
    }

    LaunchedEffect(Unit) { viewModel.checkNetwork() }
    // Automatic syncing failing has no other way to reach the user.
    LaunchedEffect(state.lastSyncTimestamp) { viewModel.refreshAutoSyncHealth() }
    // Recompute per-board catalogue sizes on first show, after each sync
    // completes, and after a board-data deletion (alreadyImported flips
    // false), so the status list never shows pre-deletion counts.
    LaunchedEffect(state.lastSyncCompletedAtMillis, state.alreadyImported) { viewModel.refreshBoardCounts() }
    // Also recompute whenever any single board's step flips to Done mid-sync:
    // during the all-boards sync each row must turn green with its count as
    // soon as ITS import finishes, not only when the whole sync ends.
    val doneBrands = state.boardSteps.filterValues { it is ImportStep.Done }.keys
    LaunchedEffect(doneBrands) { viewModel.refreshBoardCounts() }
    if (autoStartIfNeeded) {
        // One-shot on first composition. The VM's startInitialSyncIfNeeded
        // guards on alreadyImported + isSyncing so a re-entry to the
        // onboarding (or returning user) doesn't kick off a redundant
        // re-download.
        LaunchedEffect(Unit) { viewModel.startInitialSyncIfNeeded() }
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

    state.pendingOfflineShare?.let { invitation ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissOfflineShare() },
            icon = {
                Icon(
                    Icons.Default.NetworkWifi,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_offline_share_title)) },
            text = {
                Text(stringResource(R.string.board_sync_offline_share_message, invitation.ssid))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                        } else {
                            // Android 12 ignores a fine-only runtime request
                            // on some releases. Wi-Fi APIs still require fine
                            // location through API 32, so request the pair.
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            )
                        }
                        if (permissions.all { permission ->
                                ContextCompat.checkSelfPermission(context, permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            viewModel.confirmOfflineShare()
                        } else {
                            wifiPermissionLauncher.launch(permissions)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.testTag("board_sync_offline_share_confirm"),
                ) { Text(stringResource(R.string.board_sync_offline_share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOfflineShare() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.localShareUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissLocalShareUpdate() },
            icon = {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_local_update_title)) },
            text = { Text(stringResource(R.string.board_sync_local_update_message, update.versionName)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            !context.packageManager.canRequestPackageInstalls()
                        ) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } else {
                            val apk = File(update.apkPath)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                apk,
                            )
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                            )
                            viewModel.dismissLocalShareUpdate()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) { Text(stringResource(R.string.board_sync_local_update_install)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLocalShareUpdate() }) {
                    Text(stringResource(R.string.action_not_now))
                }
            },
        )
    }

    // Metered-download consent: not on WiFi is no longer a hard block — the
    // user can explicitly opt in to pulling the full catalogue over mobile
    // data after seeing the size warning (user-triggered downloads only;
    // background auto-sync stays WiFi-gated).
    if (state.showMeteredConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMeteredConfirm() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text(stringResource(R.string.board_sync_metered_confirm_title)) },
            text = { Text(stringResource(R.string.board_sync_metered_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmMeteredSync() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.testTag("board_sync_metered_confirm"),
                ) { Text(stringResource(R.string.board_sync_metered_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMeteredConfirm() }) {
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
    // FEAT-007 gym-search escape hatch — wired on all four picker call sites
    // (Settings, Filter, Onboarding, sync card) so the "Weiß nicht?" link is
    // consistently present. Falls back to the direct picker on demand.
    var showGymSearch by remember { mutableStateOf(false) }
    if (modelState.showDialog) {
        // FEAT-031: the one shared board picker (same as Settings / Filter /
        // Onboarding) — identical state + the full board list incl. Aurora.
        BoardPickerDialog(
            onDismiss = { viewModel.dismissModelDialog() },
            onSelected = { viewModel.dismissModelDialog() },
            onFindViaGym = {
                viewModel.dismissModelDialog()
                showGymSearch = true
            },
        )
    }
    if (showGymSearch) {
        GymBoardSearchSheet(
            onClose = { showGymSearch = false },
            onFallbackToDirect = {
                showGymSearch = false
                viewModel.showModelDialog()
            },
            onDismiss = { showGymSearch = false },
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A CruxCoach share hotspot is intentionally local-only and therefore
        // has no VALIDATED internet capability. Showing "no network" while a
        // nearby DB is visibly downloading is both false and alarming.
        if (!state.localShareInProgress) {
            when {
                !state.networkAvailable -> NetworkWarningBanner()
                !state.wifiConnected -> NoWifiWarningBanner()
            }
        }
        state.autoSyncOverdueDays?.let { days -> AutoSyncOverdueBanner(days) }
        DatabaseImportSection(
            state = state,
            boardCounts = boardCounts,
            activeBrand = activeBrand,
            onStartSync = {
                if (autoStartIfNeeded) viewModel.startInitialSync()
                else viewModel.startApiSync()
            },
            onLoadBoard = { viewModel.loadBoard(it) },
            onDismissError = { viewModel.clearError() },
            onReportBug = { error ->
                onNavigateToBugReport(
                    syncBugReportTitle,
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
    boardCounts: Map<String, Long>,
    activeBrand: BoardBrand,
    onStartSync: () -> Unit,
    onLoadBoard: (BoardBrand) -> Unit,
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

            if (state.localShareInProgress && state.importStep != null) {
                LocalShareProgressSummary(
                    step = state.importStep,
                    boardCount = state.localShareBoardSteps.size,
                )
            }

            if (!state.alreadyImported && !state.isSyncing) {
                // Fresh install — nothing imported yet: the primary download CTA.
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
            } else {
                // FEAT-031: the board overview is ALWAYS present — one row per
                // board (Kilter, MoonBoard + the Aurora family). A board that is
                // currently syncing shows its progress inline within its own row,
                // so the overview is never replaced by a full-screen checklist.
                BoardCatalogueStatusList(
                    boardCounts = boardCounts,
                    activeBrand = activeBrand,
                    boardSteps = state.boardSteps,
                    boardErrors = state.boardErrors,
                    syncing = state.isSyncing,
                    localShareInProgress = state.localShareInProgress,
                    globalStep = state.importStep,
                    onLoadBoard = onLoadBoard,
                )

                if (!state.isSyncing) {
                    state.lastSyncTimestamp?.let { ts ->
                        Text(
                            stringResource(R.string.board_sync_last_sync, formatTimestamp(ts)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

@Composable
private fun LocalShareProgressSummary(
    step: ImportStep,
    boardCount: Int,
) {
    val label = when (step) {
        is ImportStep.DiscoveringLocalShare ->
            stringResource(R.string.board_sync_step_discover_local_share)
        is ImportStep.PreparingSnapshot ->
            stringResource(R.string.board_sync_step_sender_preparing)
        is ImportStep.CheckingUpdate -> stringResource(R.string.board_sync_step_check_update)
        is ImportStep.FetchingManifest -> stringResource(R.string.board_sync_step_fetch_manifest)
        is ImportStep.Download, is ImportStep.DownloadChunk ->
            stringResource(R.string.board_sync_step_download_db)
        is ImportStep.DownloadApk -> stringResource(R.string.board_sync_step_download_apk)
        is ImportStep.VerifyingSnapshot -> stringResource(R.string.board_sync_step_verify_db)
        is ImportStep.VerifyingApk -> stringResource(R.string.board_sync_step_verify_apk)
        is ImportStep.Extract, is ImportStep.Decompress ->
            stringResource(R.string.board_sync_step_extract_db)
        is ImportStep.ImportClimbs -> stringResource(R.string.board_sync_step_import_climbs)
        is ImportStep.ImportStats -> stringResource(R.string.board_sync_step_import_stats)
        is ImportStep.ImportLayout -> stringResource(R.string.board_sync_step_import_layout)
        is ImportStep.Finalizing -> stringResource(R.string.board_sync_step_finalize)
        is ImportStep.Done -> stringResource(R.string.board_sync_local_complete)
    }
    val fraction = when (step) {
        is ImportStep.Download -> ratio(step.bytesRead, step.totalBytes)
        is ImportStep.DownloadApk -> ratio(step.bytesRead, step.totalBytes)
        is ImportStep.DownloadChunk -> ratio(
            step.cumulativeBytesRead,
            step.cumulativeTotalBytes,
        )
        is ImportStep.Decompress -> ratio(step.bytesRead, step.totalBytes)
        is ImportStep.ImportClimbs -> ratio(step.scanned.toLong(), step.total.toLong())
        is ImportStep.ImportStats -> ratio(step.scanned.toLong(), step.total.toLong())
        else -> null
    }
    val detail = when (step) {
        is ImportStep.Download -> if (step.totalBytes > 0) {
            "${formatShareBytes(step.bytesRead)} / ${formatShareBytes(step.totalBytes)}"
        } else null
        is ImportStep.DownloadApk -> if (step.totalBytes > 0) {
            "${formatShareBytes(step.bytesRead)} / ${formatShareBytes(step.totalBytes)}"
        } else null
        is ImportStep.DownloadChunk -> if (step.cumulativeTotalBytes > 0) {
            "${formatShareBytes(step.cumulativeBytesRead)} / " +
                formatShareBytes(step.cumulativeTotalBytes)
        } else null
        is ImportStep.Decompress -> if (step.totalBytes > 0) {
            "${formatShareBytes(step.bytesRead)} / ${formatShareBytes(step.totalBytes)}"
        } else null
        is ImportStep.ImportClimbs -> if (step.total > 0) {
            "%,d / %,d".format(step.scanned, step.total)
        } else null
        is ImportStep.ImportStats -> if (step.total > 0) {
            "%,d / %,d".format(step.scanned, step.total)
        } else null
        is ImportStep.ImportLayout -> if (step.count > 0) {
            "%,d".format(step.count)
        } else null
        else -> null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_share_progress"),
        shape = RoundedCornerShape(14.dp),
        color = OrangeAccent.copy(alpha = 0.09f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            OrangeAccent.copy(alpha = 0.22f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.NetworkWifi,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.board_sync_local_progress_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.18f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.18f),
                )
            }
            if (step !is ImportStep.DiscoveringLocalShare &&
                step !is ImportStep.PreparingSnapshot && boardCount > 0
            ) {
                Text(
                    stringResource(R.string.board_sync_local_catalogue_count, boardCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ratio(value: Long, total: Long): Float? =
    if (total > 0L) (value.toFloat() / total).coerceIn(0f, 1f) else null

private fun formatShareBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/**
 * FEAT-031: per-board catalogue status list — Kilter, MoonBoard, then the
 * Aurora family, in that order. Loaded boards show their climb count; the
 * active board's catalogue auto-loads when missing, and any not-yet-loaded
 * board can be loaded/retried inline. Replaces the old single Kilter-centric
 * "synced" line so all three board categories are visible + actionable.
 */
@Composable
private fun BoardCatalogueStatusList(
    boardCounts: Map<String, Long>,
    activeBrand: BoardBrand,
    boardSteps: Map<BoardBrand, ImportStep>,
    boardErrors: Map<BoardBrand, String>,
    syncing: Boolean,
    localShareInProgress: Boolean,
    globalStep: ImportStep?,
    onLoadBoard: (BoardBrand) -> Unit,
) {
    val boards = remember {
        listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD) +
            BoardBrand.entries.filter {
                it.isInteractive && it != BoardBrand.KILTER && it != BoardBrand.MOONBOARD
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val sharedPhasePending = localShareInProgress &&
            globalStep?.isSharedLocalSharePhase() == true
        boards.forEach { brand ->
            // Discovery, snapshot creation, download and verification are
            // global share phases. LocalShareProgressSummary already renders
            // them once above the list; repeating the same (potentially long)
            // sentence in every board row made the onboarding layout explode.
            val rowStep = boardSteps[brand]?.takeUnless {
                localShareInProgress && it.isSharedLocalSharePhase()
            }
            BoardStatusRow(
                brand = brand,
                count = boardCounts[brand.wireValue] ?: 0L,
                isActive = brand == activeBrand,
                step = rowStep,
                // Discovery/snapshot/download are one global operation. A
                // missing per-board step during that window is not a Kilter
                // failure (and stale errors must not leak into the waiting
                // UI): every catalogue gets the same neutral pending state.
                sharedPhasePending = sharedPhasePending,
                hasError = !sharedPhasePending && boardErrors.containsKey(brand),
                anySyncing = syncing,
                onLoad = { onLoadBoard(brand) },
            )
        }
    }
}

@Composable
private fun BoardStatusRow(
    brand: BoardBrand,
    count: Long,
    isActive: Boolean,
    step: ImportStep?,
    sharedPhasePending: Boolean,
    hasError: Boolean,
    anySyncing: Boolean,
    onLoad: () -> Unit,
) {
    // This board is mid-sync when it has a non-terminal step in the map.
    val boardSyncing = step != null && step !is ImportStep.Done
    // A Done step carries that board's post-import catalogue total — use it
    // while [count] (refreshed asynchronously) is still stale, so the row
    // flips to done+count the moment its own import completes. AlreadyCurrent
    // reports Done(0,0,0); the count-first preference keeps the previously
    // loaded total for that case.
    val doneClimbs = (step as? ImportStep.Done)?.climbs?.toLong() ?: 0L
    val displayCount = if (count > 0L) count else doneClimbs
    val loaded = displayCount > 0L

    // Inline progress label (reuses the step strings) + bar fraction.
    val progressLabel: String? = when (step) {
        is ImportStep.DiscoveringLocalShare ->
            stringResource(R.string.board_sync_step_discover_local_share)
        is ImportStep.PreparingSnapshot ->
            stringResource(R.string.board_sync_step_sender_preparing)
        is ImportStep.FetchingManifest ->
            stringResource(R.string.board_sync_step_fetch_manifest)
        is ImportStep.CheckingUpdate -> stringResource(R.string.board_sync_step_check_update)
        is ImportStep.Download, is ImportStep.DownloadChunk ->
            stringResource(R.string.board_sync_step_download_db)
        is ImportStep.DownloadApk -> stringResource(R.string.board_sync_step_download_apk)
        is ImportStep.VerifyingSnapshot -> stringResource(R.string.board_sync_step_verify_db)
        is ImportStep.VerifyingApk -> stringResource(R.string.board_sync_step_verify_apk)
        is ImportStep.Extract, is ImportStep.Decompress ->
            stringResource(R.string.board_sync_step_extract_db)
        is ImportStep.ImportClimbs -> stringResource(R.string.board_sync_step_import_climbs)
        is ImportStep.ImportStats -> stringResource(R.string.board_sync_step_import_stats)
        is ImportStep.ImportLayout -> stringResource(R.string.board_sync_step_import_layout)
        is ImportStep.Finalizing -> stringResource(R.string.board_sync_step_finalize)
        else -> null
    }
    val progressFraction: Float? = when (step) {
        is ImportStep.Download -> if (step.totalBytes > 0)
            (step.bytesRead.toFloat() / step.totalBytes).coerceIn(0f, 1f) else null
        is ImportStep.DownloadApk -> if (step.totalBytes > 0)
            (step.bytesRead.toFloat() / step.totalBytes).coerceIn(0f, 1f) else null
        is ImportStep.DownloadChunk -> if (step.cumulativeTotalBytes > 0)
            (step.cumulativeBytesRead.toFloat() / step.cumulativeTotalBytes).coerceIn(0f, 1f) else null
        is ImportStep.Decompress -> if (step.totalBytes > 0)
            (step.bytesRead.toFloat() / step.totalBytes).coerceIn(0f, 1f) else null
        is ImportStep.ImportClimbs -> if (step.total > 0 && step.scanned > 0)
            (step.scanned.toFloat() / step.total).coerceIn(0f, 1f) else null
        is ImportStep.ImportStats -> if (step.total > 0 && step.scanned > 0)
            (step.scanned.toFloat() / step.total).coerceIn(0f, 1f) else null
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("board_status_${brand.wireValue}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                boardSyncing -> CircularProgressIndicator(
                    color = OrangeAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                )
                sharedPhasePending -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                loaded -> Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    tint = SuccessGreen, modifier = Modifier.size(20.dp),
                )
                hasError -> Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = ErrorRed, modifier = Modifier.size(20.dp),
                )
                isActive -> Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = OrangeAccent, modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Default.RadioButtonUnchecked, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))

            Text(
                brand.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (loaded || isActive || boardSyncing) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )

            when {
                boardSyncing -> Text(
                    progressLabel ?: stringResource(R.string.board_sync_step_fetch_manifest),
                    style = MaterialTheme.typography.labelSmall,
                    color = OrangeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                sharedPhasePending -> Text(
                    stringResource(R.string.board_sync_status_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loaded -> Text(
                    "%,d".format(displayCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    if (isActive || hasError) stringResource(R.string.board_sync_status_not_loaded)
                    else stringResource(R.string.board_sync_status_dash),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive || hasError) OrangeAccent
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            // Per-board reload (loaded) / download (empty). Hidden while any
            // sync runs so the row shows progress, not a dead button.
            if (!anySyncing) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onLoad,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("board_status_load_${brand.wireValue}"),
                ) {
                    Icon(
                        if (loaded) Icons.Default.Refresh else Icons.Default.CloudDownload,
                        contentDescription = stringResource(
                            if (loaded) R.string.board_sync_reload_board else R.string.board_sync_load_board,
                            brand.displayName,
                        ),
                        tint = OrangeAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (boardSyncing) {
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.2f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.2f),
                )
            }
        }
    }
}

private fun ImportStep.isSharedLocalSharePhase(): Boolean = when (this) {
    is ImportStep.DiscoveringLocalShare,
    is ImportStep.PreparingSnapshot,
    is ImportStep.CheckingUpdate,
    is ImportStep.FetchingManifest,
    is ImportStep.Download,
    is ImportStep.DownloadApk,
    is ImportStep.VerifyingSnapshot,
    is ImportStep.VerifyingApk,
    is ImportStep.Extract,
    is ImportStep.Decompress -> true
    is ImportStep.ImportClimbs,
    is ImportStep.ImportStats,
    is ImportStep.ImportLayout,
    is ImportStep.Finalizing,
    is ImportStep.DownloadChunk,
    is ImportStep.Done -> false
}

/**
 * Render an ISO-8601 timestamp string as a short, locale-aware date+time.
 * The previous implementation hand-concatenated `dd.MM.yyyy, HH:mm`
 * unconditionally, which is the German format but was also shown to
 * English-locale users. SHORT-style formatting gives `25.04.26, 14:32`
 * for `de`, `4/25/26, 2:32 PM` for `en-US`, etc.
 */
/**
 * Automatic syncing has not produced anything for several cycles.
 *
 * The background worker cannot report to anyone — it fails, retries, and the
 * app looks unchanged. Without this the catalogue can go a month out of date
 * with "daily" configured and nothing on screen ever says so.
 */
@Composable
private fun AutoSyncOverdueBanner(days: Int) {
    Surface(
        color = WarningYellow.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("board_sync_overdue"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = WarningYellow,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.board_sync_overdue, days),
                style = MaterialTheme.typography.bodySmall,
                color = WarningYellow,
            )
        }
    }
}

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
