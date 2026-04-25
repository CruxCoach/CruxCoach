package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.nostr.backup.BackupErrorReason
import com.cruxcoach.android.nostr.backup.DeleteRemoteNote
import com.cruxcoach.android.ui.board.sync.BoardSyncInlineCard
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.devcontact.DevContactSection
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAppShare: () -> Unit,
    onNavigateToImport: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToAnnouncements: () -> Unit = {},
    onNavigateToBugReports: () -> Unit = {},
    onNavigateToFeatureRequests: () -> Unit = {},
    onNavigateToCrashReports: () -> Unit = {},
    onNavigateToKeyManagement: () -> Unit = {},
    onDonateClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()

    // Hoisted to top level (not inside Scaffold content) so they survive the
    // isLoading guard and are restored by the NavBackStackEntry SavedStateHolder
    // on back-navigation from sub-screens.
    val scrollState = rememberScrollState()
    var displayExpanded by rememberSaveable { mutableStateOf(false) }
    var boardSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var accountExpanded by rememberSaveable { mutableStateOf(false) }
    var kilterExpanded by rememberSaveable { mutableStateOf(false) }
    var devContactExpanded by rememberSaveable { mutableStateOf(false) }
    var dataExpanded by rememberSaveable { mutableStateOf(false) }
    var updaterExpanded by rememberSaveable { mutableStateOf(false) }
    var showBoardModelDialog by rememberSaveable { mutableStateOf(false) }

    // Notification-tap deep-link auto-expand: opens the updater section so
    // the inline confirmation dialog inside [UpdaterSettingsSection] can
    // actually compose. Without this expansion the section stays collapsed
    // and the dialog's LaunchedEffect never runs, so the user is dropped on
    // an empty Settings screen and has to find the section themselves.
    val updaterVm: UpdaterSettingsViewModel = hiltViewModel()
    val updaterDialogRequested by updaterVm.downloadDialogRequested.collectAsStateWithLifecycle()
    LaunchedEffect(updaterDialogRequested) {
        if (updaterDialogRequested) updaterExpanded = true
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        // Profile button hidden — training features not ready for first release
                        // IconButton(onClick = onNavigateToProfile) {
                        //     Icon(Icons.Outlined.Person, contentDescription = stringResource(R.string.cd_profile_assessment))
                        // }
                        IconButton(onClick = onNavigateToAppShare) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.cd_app_share))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = OrangeAccent)
            }
            return@Scaffold
        }

        // Eager-load so "Ändern" doesn't open onto an empty list while the
        // background load races the dialog visibility flag.
        LaunchedEffect(Unit) { viewModel.loadProductSizes() }

        if (showBoardModelDialog) {
            // No onNavigateToSync — the sync card lives in the Data section
            // on this very screen, so the dialog's DB-empty branch falls
            // back to "Schließen"; the user scrolls to the sync card.
            BoardModelSelectionDialog(
                productSizes = state.productSizes,
                selectedId = state.boardProductSizeId,
                onConfirm = { id ->
                    val name = state.productSizes.find { it.id.toInt() == id }?.name ?: ""
                    viewModel.updateBoardProductSize(id, name)
                    showBoardModelDialog = false
                },
                onDismiss = { showBoardModelDialog = false },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Anzeige (Sprache + Darstellung)
            CollapsibleHeader(stringResource(R.string.settings_section_display), displayExpanded) { displayExpanded = !displayExpanded }
            AnimatedVisibility(visible = displayExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LanguageSection()
                    HorizontalDivider()
                    DisplaySection(
                        gradeScale = state.gradeScale,
                        darkMode = state.darkMode,
                        onGradeScaleChange = { viewModel.updateGradeScale(it) },
                        onDarkModeChange = { viewModel.updateDarkMode(it) }
                    )
                }
            }

            HorizontalDivider()

            // Section 2: Board-Einstellungen
            CollapsibleHeader(stringResource(R.string.settings_section_board), boardSettingsExpanded) { boardSettingsExpanded = !boardSettingsExpanded }
            AnimatedVisibility(visible = boardSettingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoardModelSection(
                        boardModelName = state.boardProductSizeName,
                        onChangeModel = { showBoardModelDialog = true }
                    )
                    HorizontalDivider()
                    BleAutoDisconnectSection(
                        bleAutoDisconnectSeconds = state.bleAutoDisconnectSeconds,
                        keepScreenOn = state.keepScreenOn,
                        onAutoDisconnectChange = { viewModel.updateBleAutoDisconnect(it) },
                        onKeepScreenOnChange = { viewModel.updateKeepScreenOn(it) }
                    )
                    HorizontalDivider()
                    ClimbSharingSection(
                        climbSharing = state.climbSharing,
                        onSharingChange = { viewModel.updateNearbyClimbSharing(it) }
                    )
                    HorizontalDivider()
                    LedColorSection(
                        ledColors = state.ledColors,
                        onColorChange = { roleId, colorByte -> viewModel.updateLedColor(roleId, colorByte) },
                        onResetColors = { viewModel.resetLedColors() },
                        onKilterColors = { viewModel.setKilterColors() }
                    )
                    HorizontalDivider()
                    RestTimerSection(
                        restTimer = state.restTimer,
                        onDurationChange = { viewModel.updateRestTimerDuration(it) },
                        onAutoStartChange = { viewModel.updateRestTimerAutoStart(it) }
                    )
                    HorizontalDivider()
                    RoutePlaybackSection(
                        routePlayback = state.routePlayback,
                        onFrameSpeedChange = { viewModel.updateRouteFrameSpeed(it) },
                        onUseSetterSpeedChange = { viewModel.updateRouteUseSetterSpeed(it) },
                        onCountdownChange = { viewModel.updateRouteCountdown(it) },
                        onCountdownSecondsChange = { viewModel.updateRouteCountdownSeconds(it) },
                        onAutoLoopChange = { viewModel.updateRouteAutoLoop(it) }
                    )
                }
            }

            HorizontalDivider()

            // Section: Kilter Board Account
            CollapsibleHeader(stringResource(R.string.kilter_section_title), kilterExpanded) { kilterExpanded = !kilterExpanded }
            AnimatedVisibility(visible = kilterExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    KilterAccountSection(
                        state = state.kilterAccount,
                        onShowLogin = { viewModel.showKilterLogin() },
                        onDismissLogin = { viewModel.dismissKilterLogin() },
                        onEmailChanged = { viewModel.updateKilterEmail(it) },
                        onPasswordChanged = { viewModel.updateKilterPassword(it) },
                        onLogin = { viewModel.kilterLogin() },
                        onImportOneTime = { viewModel.kilterImportOneTime() },
                        onImportPersistent = { viewModel.kilterImportPersistent() },
                        onDismissPreview = { viewModel.dismissKilterPreview() },
                        onSyncNow = { viewModel.kilterSyncNow() },
                        onPushEnabledChanged = { viewModel.setKilterPushEnabled(it) },
                        onDisconnect = { viewModel.kilterDisconnect() },
                        onShowDisconnectConfirm = { viewModel.showKilterDisconnectConfirm() },
                        onDismissDisconnectConfirm = { viewModel.dismissKilterDisconnectConfirm() },
                        onDismissResult = { viewModel.dismissKilterResult() }
                    )
                }
            }

            HorizontalDivider()

            // Section 3: Datenverwaltung
            //
            // Ordering reflects how the user thinks about their data, not
            // our internal implementation split:
            //   1. Board sync  — public community data (unrelated concern).
            //   2. Nostr backup — primary backup flow; opt-in, ongoing.
            //   3. Import / Export — manual backup variant (file-based).
            //   4. Delete Board / User data — destructive, parked at the
            //      bottom so the user scrolls PAST both backup variants
            //      before encountering it.
            CollapsibleHeader(stringResource(R.string.settings_section_data), dataExpanded) { dataExpanded = !dataExpanded }
            AnimatedVisibility(visible = dataExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoardSyncSection(
                        syncInterval = state.syncInterval,
                        onSyncIntervalChange = { viewModel.updateSyncInterval(it) },
                    )
                    // Inline sync card: download, progress, re-sync, model
                    // selection — no navigation hop to a dedicated screen.
                    BoardSyncInlineCard()
                    HorizontalDivider()
                    BackupSettingsSection(
                        state = backupState,
                        onSetBackupEnabled = { backupViewModel.setBackupEnabled(it) },
                        onSetInterval = { backupViewModel.setInterval(it) },
                        onRunBackupNow = { backupViewModel.runBackupNow() },
                        onTriggerRestore = { backupViewModel.triggerManualRestore() },
                        onRequestDeleteRemote = { backupViewModel.requestDeleteRemoteBackups() },
                    )
                    HorizontalDivider()
                    DataImportExportSection(
                        deleteSuccess = state.deleteSuccess,
                        onNavigateToImport = onNavigateToImport,
                        onNavigateToExport = onNavigateToExport,
                        onDismissDeleteSuccess = { viewModel.dismissDeleteSuccess() },
                    )
                    HorizontalDivider()
                    DataDeletionSection(
                        showDeleteBoardDataDialog = state.showDeleteBoardDataDialog,
                        showDeleteUserDataDialog = state.showDeleteUserDataDialog,
                        onShowDeleteBoardDataDialog = { viewModel.showDeleteBoardDataDialog() },
                        onShowDeleteUserDataDialog = { viewModel.showDeleteUserDataDialog() },
                        onDismissDeleteDialog = { viewModel.dismissDeleteDialog() },
                        onDeleteBoardData = { viewModel.deleteBoardData() },
                        onDeleteUserBoardData = { viewModel.deleteUserBoardData() },
                    )
                }
            }

            HorizontalDivider()

            // Section 4: Entwickler-Kontakt
            CollapsibleHeader(stringResource(R.string.settings_section_dev_contact), devContactExpanded) { devContactExpanded = !devContactExpanded }
            AnimatedVisibility(visible = devContactExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DevContactSection(
                        unreadChat = 0,
                        unreadBugs = 0,
                        unreadFeatures = 0,
                        unreadAnnouncements = state.unreadAnnouncements,
                        crashReportOptIn = state.crashReportOptIn,
                        announcementsEnabled = state.announcementsEnabled,
                        categoryRelease = state.announcementCatRelease,
                        categoryIssue = state.announcementCatIssue,
                        categoryTip = state.announcementCatTip,
                        categoryGeneral = state.announcementCatGeneral,
                        queuedCount = state.queuedCount,
                        onNavigateToChat = onNavigateToChat,
                        onNavigateToAnnouncements = onNavigateToAnnouncements,
                        onNavigateToBugReports = onNavigateToBugReports,
                        onNavigateToFeatureRequests = onNavigateToFeatureRequests,
                        onNavigateToCrashReports = onNavigateToCrashReports,
                        onDonateClick = onDonateClick,
                        onCrashReportOptInChange = { viewModel.updateCrashReportOptIn(it) },
                        onAnnouncementsEnabledChange = { viewModel.updateAnnouncementsEnabled(it) },
                        onCategoryChange = { cat, enabled -> viewModel.updateAnnouncementCategory(cat, enabled) },
                        onDrainQueue = { viewModel.drainQueue() }
                    )
                }
            }

            HorizontalDivider()

            // Section: App updates (FEAT-004)
            CollapsibleHeader(stringResource(R.string.updater_settings_title), updaterExpanded) { updaterExpanded = !updaterExpanded }
            AnimatedVisibility(visible = updaterExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UpdaterSettingsSection()
                }
            }

            HorizontalDivider()

            // Section: Nostr-Schlüssel (Fortgeschritten)
            CollapsibleHeader(stringResource(R.string.key_section_account_keys), accountExpanded) { accountExpanded = !accountExpanded }
            AnimatedVisibility(visible = accountExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccountKeysSection(onNavigateToKeyManagement = onNavigateToKeyManagement)
                }
            }

            HorizontalDivider()

            // Section: App Info
            AppInfoSection(
                easterAnimationsUnlocked = state.easterAnimationsUnlocked,
                isAnimating = state.isAnimating,
                isBleConnected = viewModel.isBleConnected(),
                onUnlockEasterAnimations = { viewModel.unlockEasterAnimations() },
                onPlayEasterAnimation = { viewModel.playEasterAnimation() },
                onStopAnimation = { viewModel.stopAnimation() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // FEAT-002: Restore confirmation + negative-outcome dialogs rendered
    // outside the Scaffold content so they overlay the full screen.
    backupState.pendingRestore?.let { info ->
        BackupRestoreDialog(
            info = info,
            onConfirm = { backupViewModel.confirmRestore() },
            onDismiss = { backupViewModel.dismissRestoreDialog() },
        )
    }
    if (backupState.showDeleteRemoteConfirm) {
        DeleteRemoteBackupsDialog(
            onConfirm = { backupViewModel.confirmDeleteRemoteBackups() },
            onDismiss = { backupViewModel.dismissDeleteRemoteConfirm() },
        )
    }
    backupState.snackbar?.let { snackbar ->
        val message: String = when (snackbar) {
            BackupSettingsState.Snackbar.NoBackupFound ->
                stringResource(R.string.settings_backup_no_backup_found)
            BackupSettingsState.Snackbar.CheckDecryptFailed ->
                stringResource(R.string.settings_backup_check_decrypt_failed)
            BackupSettingsState.Snackbar.BlobUnreachable ->
                stringResource(R.string.settings_backup_blob_unreachable)
            is BackupSettingsState.Snackbar.CheckError ->
                stringResource(R.string.settings_backup_check_error, snackbar.detail)
            BackupSettingsState.Snackbar.RestoreFailed ->
                stringResource(R.string.settings_backup_restore_failed)
            BackupSettingsState.Snackbar.BackupSucceeded ->
                stringResource(R.string.settings_backup_succeeded)
            is BackupSettingsState.Snackbar.BackupFailed ->
                snackbar.reason?.let { localizedBackupErrorReason(it) }
                    ?: stringResource(R.string.settings_backup_failed)
            is BackupSettingsState.Snackbar.RemoteBackupsDeleted -> {
                // Compose a multi-line report: how many relays /
                // Blossom servers ack'd the deletion, plus the honest
                // Nostr-deletion caveat that third-party mirrors may
                // still retain copies.
                val ok = snackbar.notes.isEmpty() &&
                    snackbar.relaysAttempted > 0 &&
                    snackbar.relaysAccepted == snackbar.relaysAttempted &&
                    (snackbar.blossomAttempted == 0 ||
                        snackbar.blossomAccepted == snackbar.blossomAttempted)
                val header = stringResource(
                    if (ok) R.string.settings_backup_delete_remote_done_header
                    else R.string.settings_backup_delete_remote_partial_header,
                )
                val relayLine = stringResource(
                    R.string.settings_backup_delete_remote_relays_line,
                    snackbar.relaysAccepted,
                    snackbar.relaysAttempted,
                )
                val blossomLine = if (snackbar.blossomAttempted == 0) {
                    stringResource(R.string.settings_backup_delete_remote_no_blob)
                } else {
                    stringResource(
                        R.string.settings_backup_delete_remote_blossom_line,
                        snackbar.blossomAccepted,
                        snackbar.blossomAttempted,
                    )
                }
                val caveat = stringResource(R.string.settings_backup_delete_remote_caveat)
                // Resolve each note's localized text BEFORE joinToString —
                // joinToString's transform lambda is not a @Composable
                // scope, so stringResource(...) calls inside it would fail
                // to compile.
                val localizedNotes = snackbar.notes.map { localizedDeleteRemoteNote(it) }
                val notesBlock = if (localizedNotes.isEmpty()) "" else
                    "\n\n" + localizedNotes.joinToString("\n") { "• $it" }
                "$header\n\n$relayLine\n$blossomLine\n\n$caveat$notesBlock"
            }
        }
        AlertDialog(
            onDismissRequest = { backupViewModel.consumeSnackbar() },
            confirmButton = {
                TextButton(onClick = { backupViewModel.consumeSnackbar() }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = { Text(message) },
        )
    }
}

@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
            tint = OrangeAccent
        )
    }
}

/**
 * Map a [BackupErrorReason] to its locale-aware user-facing string.
 * The dev-facing English form is in `BackupException.message` (logcat /
 * crash reports); this composable owns the user-visible text.
 */
@Composable
private fun localizedBackupErrorReason(reason: BackupErrorReason): String = when (reason) {
    is BackupErrorReason.BlobUploadFailed -> if (reason.authDetail.isNullOrBlank()) {
        stringResource(R.string.backup_error_blob_upload_failed, reason.total)
    } else {
        stringResource(
            R.string.backup_error_blob_upload_failed_with_detail,
            reason.total,
            reason.authDetail,
        )
    }
    BackupErrorReason.AmberNeedsAutoApprove ->
        stringResource(R.string.backup_error_amber_needs_auto_approve)
    is BackupErrorReason.BlobNotVisibleAfterUpload ->
        stringResource(R.string.backup_error_blob_not_visible, reason.total)
    is BackupErrorReason.PointerEventNotDurable ->
        stringResource(R.string.backup_error_pointer_not_durable, reason.attempted)
    is BackupErrorReason.KeyEventNotDurable ->
        stringResource(R.string.backup_error_key_event_not_durable, reason.attempted)
    BackupErrorReason.DataKeyUnwrapFailed ->
        stringResource(R.string.backup_error_data_key_unwrap_failed)
    BackupErrorReason.PointerListsNoUsableServers ->
        stringResource(R.string.backup_error_pointer_lists_no_servers)
    is BackupErrorReason.PlaintextSizeCap ->
        stringResource(R.string.backup_error_plaintext_size_cap)
    BackupErrorReason.KeyFetchAmbiguous ->
        stringResource(R.string.backup_error_key_fetch_ambiguous)
    // Fall-through `Other` carries an unstructured string that is dev-
    // facing only; route it through the existing generic-detail string
    // so the user sees a localized prefix instead of just the raw text.
    is BackupErrorReason.Other ->
        stringResource(R.string.settings_backup_failed_detail, reason.message)
}

/** Map a [DeleteRemoteNote] to its locale-aware bullet text in the opt-out report dialog. */
@Composable
private fun localizedDeleteRemoteNote(note: DeleteRemoteNote): String = when (note) {
    DeleteRemoteNote.DTagDerivationFailed ->
        stringResource(R.string.delete_remote_note_dtag_derivation_failed)
    DeleteRemoteNote.NoWriteRelays ->
        stringResource(R.string.delete_remote_note_no_write_relays)
    DeleteRemoteNote.NoRelayAcceptedDeletion ->
        stringResource(R.string.delete_remote_note_no_relay_accepted)
    is DeleteRemoteNote.PartialRelayAccept ->
        stringResource(R.string.delete_remote_note_partial_relay_accept, note.accepted, note.attempted)
    DeleteRemoteNote.RelayPublishThrew ->
        stringResource(R.string.delete_remote_note_relay_publish_threw)
    DeleteRemoteNote.BlossomAuthFailed ->
        stringResource(R.string.delete_remote_note_blossom_auth_failed)
    DeleteRemoteNote.BlossomFullyRejected ->
        stringResource(R.string.delete_remote_note_blossom_fully_rejected)
    is DeleteRemoteNote.BlossomPartial ->
        stringResource(R.string.delete_remote_note_blossom_partial, note.accepted, note.attempted)
    is DeleteRemoteNote.UnexpectedError ->
        stringResource(R.string.delete_remote_note_unexpected_error, note.type)
}
