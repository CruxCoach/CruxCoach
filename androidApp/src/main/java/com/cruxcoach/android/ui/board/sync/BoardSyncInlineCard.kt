package com.cruxcoach.android.ui.board.sync

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cruxcoach.android.util.LocalShareProtocol
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
    /** Review the download selection on first onboarding entry before starting. */
    autoStartIfNeeded: Boolean = false,
    /** Compact background-preparation status for the board-first onboarding. */
    compact: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val boardCounts by viewModel.boardCounts.collectAsStateWithLifecycle()
    val downloadBrands by viewModel.downloadBrands.collectAsStateWithLifecycle()
    var selectionDraft by rememberSaveable { mutableStateOf<Set<BoardBrand>?>(null) }
    var initialReviewShown by rememberSaveable { mutableStateOf(false) }
    var startAfterSelection by rememberSaveable { mutableStateOf(false) }
    val activeBrand by viewModel.activeBrand.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val syncBugReportTitle = stringResource(R.string.error_bug_report_sync_title)
    var permissionApprovedInvitation by remember {
        mutableStateOf<LocalShareProtocol.Invitation?>(null)
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val approved = permissionApprovedInvitation
        permissionApprovedInvitation = null
        if (approved != null && grants.isNotEmpty() && grants.values.all { it }) {
            viewModel.confirmOfflineShare(approved)
        }
    }

    LaunchedEffect(Unit) { viewModel.checkNetwork() }
    // Automatic syncing failing has no other way to reach the user.
    LaunchedEffect(state.lastSyncTimestamp) { viewModel.refreshAutoSyncHealth() }
    // Recompute on first show, after a sync/deletion, and whenever one board
    // finishes mid-sync. Keep this as ONE effect: two independent effects
    // both fire on first composition and used to run the same full grouped
    // catalogue count concurrently.
    val doneBrands = state.boardSteps.filterValues { it is ImportStep.Done }.keys
    LaunchedEffect(state.lastSyncCompletedAtMillis, state.catalogueRevision, state.alreadyImported, doneBrands) {
        viewModel.refreshBoardCounts()
    }
    if (autoStartIfNeeded) {
        // First download waits for a reviewable board choice. Nothing is fetched
        // before confirmation, and the preference is persisted before enqueueing.
        LaunchedEffect(Unit) {
            if (!initialReviewShown && !state.alreadyImported && !state.isSyncing) {
                initialReviewShown = true
                selectionDraft = viewModel.initialDownloadSelection()
                startAfterSelection = true
            }
        }
    }
    selectionDraft?.let { draft ->
        // Mid-share, the dialog is split like the offer was: the sender's boards and the rest.
        val offered = if (state.localShareInProgress) offeredCatalogues(state.localShareOffered) else emptyList()
        CatalogueSelectionDialog(
            selectedBrands = draft,
            isSyncing = state.isSyncing,
            onToggleBrand = { brand ->
                selectionDraft = if (brand in draft) draft - brand else draft + brand
            },
            onToggleSelectAll = {
                val all = BoardBrand.entries.filter { it.isInteractive }.toSet()
                selectionDraft = if (draft.containsAll(all)) emptySet() else all
            },
            onConfirm = {
                if (offered.isNotEmpty()) viewModel.updateShareSelection(draft)
                viewModel.saveDownloadSelection(draft, startInitial = startAfterSelection)
                selectionDraft = null
            },
            onDismiss = { selectionDraft = null },
            offered = offered,
            offeredEditable = state.localShareSelectionEditable,
        )
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

    // An inline offer belongs to onboarding's first screen; no dialog on top of it.
    state.pendingDiscoveredShare?.takeIf { !state.discoveredShareInline }?.let { found ->
        val host = remember(found.baseUrl) {
            runCatching { Uri.parse(found.baseUrl).host }.getOrNull() ?: found.baseUrl
        }
        val offered = remember(found.manifest) { offeredCatalogues(found.manifest) }
        val savedSelection by viewModel.downloadBrands.collectAsStateWithLifecycle()
        if (offered.isEmpty()) {
            // An old sender that declares no catalogues: nothing to choose from, so the
            // question stays the plain yes/no it always was and the share is taken whole.
            AlertDialog(
                onDismissRequest = { viewModel.dismissDiscoveredShare() },
                title = { Text(stringResource(R.string.board_sync_discovered_share_title)) },
                text = { Text(stringResource(R.string.board_sync_discovered_share_message, host)) },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmDiscoveredShare() },
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                        modifier = Modifier.testTag("board_sync_discovered_share_confirm"),
                    ) { Text(stringResource(R.string.board_sync_discovered_share_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissDiscoveredShare() },
                        modifier = Modifier.testTag("board_sync_discovered_share_internet"),
                    ) {
                        Text(stringResource(
                            if (state.pendingDiscoveredShareFallsBackOnline) R.string.board_sync_discovered_share_internet
                            else R.string.action_cancel,
                        ))
                    }
                },
            )
        } else {
            DiscoveredShareDialog(
                host = host,
                offered = offered,
                savedSelection = savedSelection,
                fallsBackOnline = state.pendingDiscoveredShareFallsBackOnline,
                onConfirm = { share, online -> viewModel.confirmDiscoveredShare(share, online) },
                onDismiss = { viewModel.dismissDiscoveredShare() },
            )
        }
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
            onDismissRequest = {
                permissionApprovedInvitation = null
                viewModel.dismissOfflineShare()
            },
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
                            viewModel.confirmOfflineShare(invitation)
                        } else {
                            permissionApprovedInvitation = invitation
                            wifiPermissionLauncher.launch(permissions)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.testTag("board_sync_offline_share_confirm"),
                ) { Text(stringResource(R.string.board_sync_offline_share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    permissionApprovedInvitation = null
                    viewModel.dismissOfflineShare()
                }) {
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
        if (!compact && !state.localShareInProgress) {
            when {
                !state.networkAvailable -> NetworkWarningBanner()
                !state.wifiConnected -> NoWifiWarningBanner()
            }
        }
        state.autoSyncOverdueDays?.takeIf { !compact && !downloadBrands.isNullOrEmpty() }
            ?.let { days -> AutoSyncOverdueBanner(days) }
        val startSync: () -> Unit = {
            if (autoStartIfNeeded && !state.alreadyImported) {
                selectionDraft = downloadBrands ?: setOf(activeBrand)
                startAfterSelection = true
            } else viewModel.startApiSync()
        }
        if (!compact) OutlinedButton(
            onClick = {
                selectionDraft = downloadBrands
                startAfterSelection = false
            },
            enabled = downloadBrands != null,
            modifier = Modifier.fillMaxWidth().testTag("board_download_selection"),
        ) {
            Text(stringResource(R.string.catalogue_choose_action))
        }
        if (!compact && downloadBrands?.isEmpty() == true) {
            Text(stringResource(R.string.board_download_selection_empty),
                style = MaterialTheme.typography.bodySmall)
        }
        if (compact) {
            CompactDatabasePreparation(
                state = state,
                boardCounts = boardCounts,
                activeBrand = activeBrand,
                selectedBrands = downloadBrands.orEmpty(),
                onRetry = startSync,
                onLoadBoard = { viewModel.loadBoard(it) },
                onChangeSelection = { selectionDraft = downloadBrands; startAfterSelection = false },
                canChangeSelection = downloadBrands != null,
            )
        } else {
            DatabaseImportSection(
                state = state,
                boardCounts = boardCounts,
                activeBrand = activeBrand,
                selectedBrands = downloadBrands.orEmpty(),
                onStartSync = startSync,
                onChangeSelection = { selectionDraft = downloadBrands; startAfterSelection = false },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactDatabasePreparation(
    state: BoardSyncState,
    boardCounts: Map<String, Long>,
    activeBrand: BoardBrand,
    selectedBrands: Set<BoardBrand>,
    onRetry: () -> Unit,
    onLoadBoard: (BoardBrand) -> Unit,
    onChangeSelection: (() -> Unit)? = null,
    canChangeSelection: Boolean = true,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // The selection, plus whatever is still loading or failed: a board ticked off
    // mid-download keeps downloading (the change applies to the next run), and the
    // card lost its count and details while it did.
    val supportedBoards = BoardBrand.entries.filter { brand ->
        brand in selectedBrands || brand in state.boardErrors ||
            state.boardSteps[brand].let { it != null && it !is ImportStep.Done }
    }
    val readyBoards = supportedBoards.count { brand ->
        val step = state.boardSteps[brand]
        (step == null || step is ImportStep.Done) && brand !in state.boardErrors &&
            catalogueDisplayCount(boardCounts[brand.wireValue] ?: 0L, step) > 0L
    }
    val hasErrors = state.errorMessage != null || supportedBoards.any { it in state.boardErrors }
    val allReady = supportedBoards.isNotEmpty() && readyBoards == supportedBoards.size && !hasErrors && !state.isSyncing
    val status = when {
        state.isSyncing -> R.string.onboarding_offline_status_loading
        supportedBoards.isEmpty() -> R.string.setup_download_none
        allReady -> R.string.onboarding_offline_status_ready
        !state.networkAvailable -> R.string.setup_download_no_network
        state.waitingForUnmeteredNetwork -> R.string.board_sync_compact_waiting_wifi
        hasErrors -> R.string.onboarding_offline_status_waiting
        else -> R.string.setup_download_pending
    }
    Surface(Modifier.fillMaxWidth().testTag("onboarding_offline_preparation"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(when {
                    allReady -> Icons.Default.CheckCircle
                    hasErrors && !state.isSyncing -> Icons.Default.Warning
                    !state.networkAvailable && !state.localShareInProgress -> Icons.Default.SignalWifiOff
                    else -> Icons.Default.CloudDownload
                }, null, tint = if (allReady) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(status), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            if (supportedBoards.isNotEmpty()) {
                Text(stringResource(R.string.board_sync_compact_progress, readyBoards, supportedBoards.size),
                    style = MaterialTheme.typography.bodyMedium)
            }
            // A share is one transfer and one import for all of its boards, so the count above
            // stays at 0 until the very end. Name the step and its progress as the full card
            // does: an unlabelled bar for the ten minutes of a share read as a hang, and the
            // sender was stopped by hand in the middle of a running import.
            val shareStep = state.importStep.takeIf { state.localShareInProgress }
            if (shareStep != null) {
                LocalShareProgressSummary(step = shareStep)
            } else if (state.isSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (hasErrors && !state.isSyncing) {
                val failed = supportedBoards.filter { it in state.boardErrors }.joinToString { it.displayName }
                if (failed.isNotEmpty()) Text(failed, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            if (!allReady && !state.isSyncing && supportedBoards.isNotEmpty()) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("onboarding_offline_retry")) {
                    Text(stringResource(if (hasErrors) R.string.action_retry else R.string.setup_download_start))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (supportedBoards.isNotEmpty()) {
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("board_sync_compact_details")) {
                        Text(stringResource(if (showDetails) R.string.board_sync_compact_hide_details else R.string.board_sync_compact_show_details))
                        Icon(
                        if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    }
                }
                onChangeSelection?.let { change ->
                    TextButton(onClick = change, enabled = canChangeSelection,
                        modifier = Modifier.testTag("board_download_selection")) {
                        Text(stringResource(R.string.setup_download_change))
                    }
                }
            }
            AnimatedVisibility(showDetails && supportedBoards.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardCatalogueStatusList(boardCounts, activeBrand, selectedBrands, state.boardSteps, state.boardErrors,
                        state.isSyncing, state.localShareInProgress, onLoadBoard, onlySelected = true)
                    state.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error) }
                }
            }

        }
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
    selectedBrands: Set<BoardBrand>,
    onStartSync: () -> Unit,
    onChangeSelection: () -> Unit,
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

            // "Wi-Fi recommended, the data can be large" is advice about the internet; while
            // a nearby device is sending, the transfer summary says what is happening instead.
            if (!state.localShareInProgress) {
                Text(
                    stringResource(R.string.board_sync_db_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.localShareInProgress && state.importStep != null) {
                LocalShareProgressSummary(step = state.importStep)
            }

            if (!state.alreadyImported && !state.isSyncing) {
                // Fresh install — nothing imported yet: the primary download CTA.
                Button(
                    onClick = onStartSync,
                    enabled = selectedBrands.isNotEmpty(),
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
                    selectedBrands = selectedBrands,
                    boardSteps = state.boardSteps,
                    boardErrors = state.boardErrors,
                    syncing = state.isSyncing,
                    localShareInProgress = state.localShareInProgress,
                    onLoadBoard = onLoadBoard,
                    onChangeSelection = onChangeSelection,
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
                        enabled = selectedBrands.isNotEmpty(),
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
private fun LocalShareProgressSummary(step: ImportStep) {
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
    selectedBrands: Set<BoardBrand>,
    boardSteps: Map<BoardBrand, ImportStep>,
    boardErrors: Map<BoardBrand, String>,
    syncing: Boolean,
    localShareInProgress: Boolean,
    onLoadBoard: (BoardBrand) -> Unit,
    onlySelected: Boolean = false,
    onChangeSelection: (() -> Unit)? = null,
) {
    val boards = remember {
        listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD) +
            BoardBrand.entries.filter {
                it.isInteractive && it != BoardBrand.KILTER && it != BoardBrand.MOONBOARD
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Only what is, or is being, loaded. The overview used to list every family there is,
        // most of them as "deselected —": eight rows to find the one that was downloading.
        // Adding another board is what the selection button above the list is for.
        boards.filter { brand ->
            val loading = boardSteps[brand].let { it != null && it !is ImportStep.Done }
            brand in selectedBrands || loading || boardErrors.containsKey(brand) ||
                (!onlySelected && ((boardCounts[brand.wireValue] ?: 0L) > 0L || boardSteps.containsKey(brand)))
        }.forEach { brand ->
            // A nearby share is ONE transfer and one import for all of its boards. The
            // summary above the list carries its step and progress; the boards it brings
            // wait in a neutral state until it is done, so the same sentence is not repeated
            // per row — and a board that is not part of it (loaded earlier) keeps its count.
            val step = boardSteps[brand]
            val inShare = localShareInProgress && step != null && step !is ImportStep.Done
            BoardStatusRow(
                brand = brand,
                count = boardCounts[brand.wireValue] ?: 0L,
                isActive = brand == activeBrand,
                step = if (inShare) null else step,
                sharedPhasePending = inShare,
                hasError = !inShare && boardErrors.containsKey(brand),
                anySyncing = syncing,
                downloadSelected = brand in selectedBrands,
                onLoad = { onLoadBoard(brand) },
                onChangeSelection = onChangeSelection,
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
    downloadSelected: Boolean,
    onLoad: () -> Unit,
    onChangeSelection: (() -> Unit)?,
) {
    // This board is mid-sync when it has a non-terminal step in the map.
    val boardSyncing = step != null && step !is ImportStep.Done
    // A completed import carries a fresh, brand-scoped total. Zero means
    // AlreadyCurrent and falls back to the stored catalogue count.
    val displayCount = catalogueDisplayCount(count, step)
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
            .testTag("board_status_${brand.wireValue}")
            .then(if (!downloadSelected && onChangeSelection != null)
                Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onChangeSelection) else Modifier),
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
                hasError -> Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = ErrorRed, modifier = Modifier.size(20.dp),
                )
                loaded -> Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    tint = SuccessGreen, modifier = Modifier.size(20.dp),
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

            Column(Modifier.weight(1f)) {
                Text(
                    brand.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!downloadSelected) {
                    Text(
                        stringResource(R.string.board_download_not_selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                hasError -> Text(
                    stringResource(R.string.setup_catalogue_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.widthIn(max = 140.dp),
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
            if (!anySyncing && downloadSelected) {
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

internal fun formatTimestamp(iso: String): String {
    val formatter = java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
        .withLocale(java.util.Locale.getDefault())
    return try {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).format(formatter)
    } catch (_: Exception) {
        // The sync state is stored as a zone-less local timestamp; without this branch the
        // raw ISO string with microseconds was shown to the user.
        try {
            java.time.LocalDateTime.parse(iso).format(formatter)
        } catch (_: Exception) {
            iso
        }
    }
}

/** Done(0) represents an unchanged catalogue, not a newly loaded empty one. */
internal fun catalogueDisplayCount(count: Long, step: ImportStep?): Long =
    (step as? ImportStep.Done)?.climbs?.toLong()?.takeIf { it > 0L } ?: count
