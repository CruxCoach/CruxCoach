package com.cruxcoach.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
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
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAppShare: () -> Unit,
    onNavigateToImport: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToAuroraMigration: () -> Unit = {},
    onNavigateToMoonBoardCsvImport: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToAnnouncements: () -> Unit = {},
    onNavigateToBugReports: () -> Unit = {},
    onNavigateToFeatureRequests: () -> Unit = {},
    onNavigateToCrashReports: () -> Unit = {},
    onNavigateToKeyManagement: () -> Unit = {},
    onNavigateToNostrProfile: () -> Unit = {},
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
    var generalExpanded by rememberSaveable { mutableStateOf(false) }
    // The hub is the primary board affordance: supported boards and the active
    // marker must be visible when Settings opens, not hidden behind discovery.
    var boardSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var accountExpanded by rememberSaveable { mutableStateOf(false) }
    var devContactExpanded by rememberSaveable { mutableStateOf(false) }
    var accountsDataExpanded by rememberSaveable { mutableStateOf(false) }
    var updaterExpanded by rememberSaveable { mutableStateOf(false) }
    var showBoardModelDialog by rememberSaveable { mutableStateOf(false) }
    var showGymSearch by rememberSaveable { mutableStateOf(false) }
    var settingsBoardWire by rememberSaveable { mutableStateOf<String?>(null) }

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
    LaunchedEffect(state.isLoading, state.boardBrand) {
        if (!state.isLoading && settingsBoardWire == null) settingsBoardWire = state.boardBrand
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
            // FEAT-031: the one shared board picker — identical state + options
            // (Kilter / MoonBoard / Aurora family) across every call site. The
            // selection persists via the shared VM; this screen's board section
            // updates reactively from the prefs.
            BoardPickerDialog(
                onDismiss = { showBoardModelDialog = false },
                onSelected = { showBoardModelDialog = false },
                onFindViaGym = {
                    showBoardModelDialog = false
                    showGymSearch = true
                },
                prefill = settingsBoardWire
                    ?.let(BoardBrand::fromWire)
                    ?.takeIf { it.wireValue != state.boardBrand }
                    ?.let {
                        BoardPickerPrefill(
                            brand = it,
                            source = BoardPickerPrefillSource.CLIMB,
                        )
                    },
            )
        }
        if (showGymSearch) {
            GymBoardSearchSheet(
                onClose = { showGymSearch = false },
                onFallbackToDirect = {
                    showGymSearch = false
                    showBoardModelDialog = true
                },
                onDismiss = { showGymSearch = false },
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
            val activeBoardBrand = BoardBrand.fromWire(state.boardBrand)
            val settingsBoardBrand = settingsBoardWire?.let(BoardBrand::fromWire) ?: activeBoardBrand

            // General preferences apply regardless of the selected board.
            CollapsibleHeader(stringResource(R.string.settings_section_general), generalExpanded) { generalExpanded = !generalExpanded }
            AnimatedVisibility(visible = generalExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(stringResource(R.string.settings_group_appearance))
                    LanguageSection()
                    HorizontalDivider()
                    DisplaySection(
                        gradeScale = state.gradeScale,
                        darkMode = state.darkMode,
                        keepScreenOn = state.keepScreenOn,
                        onGradeScaleChange = { viewModel.updateGradeScale(it) },
                        onDarkModeChange = { viewModel.updateDarkMode(it) },
                        onKeepScreenOnChange = { viewModel.updateKeepScreenOn(it) },
                    )
                    HorizontalDivider()
                    SettingsGroupHeader(stringResource(R.string.settings_group_training_playback))
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
                    HorizontalDivider()
                    ClimbSharingSection(
                        climbSharing = state.climbSharing,
                        onSharingChange = { viewModel.updateNearbyClimbSharing(it) },
                        relayManualStart = state.relayManualStart,
                        onRelayManualStartChange = { viewModel.updateRelayManualStart(it) },
                    )
                }
            }

            HorizontalDivider()

            // Board hub: selecting a card only changes the settings context.
            // Persisting an active board remains isolated in BoardPickerDialog's
            // explicit confirm callback.
            CollapsibleHeader(
                stringResource(R.string.settings_section_board),
                boardSettingsExpanded,
            ) { boardSettingsExpanded = !boardSettingsExpanded }
            AnimatedVisibility(visible = boardSettingsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.settings_board_hub_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        boardSettingsCards(activeBoardBrand).forEach { card ->
                            BoardHubCard(
                                card = card,
                                selectedForSettings = settingsBoardBrand == card.brand,
                                onSelect = { settingsBoardWire = card.brand.wireValue },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.settings_board_viewing, settingsBoardBrand.displayName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (settingsBoardBrand != activeBoardBrand) {
                        Text(
                            stringResource(R.string.settings_board_inactive_hint, settingsBoardBrand.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showBoardModelDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                        ) {
                            Text(stringResource(R.string.settings_board_make_active))
                        }
                    }
                    SettingsGroupHeader(stringResource(R.string.settings_board_active_connection))
                    // FEAT-027: for a MoonBoard show the variant name; else the
                    // Kilter board-size label. (0.1.5 dropped the standalone
                    // Original/Homewall toggle — the picker resolves layout.)
                    if (settingsBoardBrand == activeBoardBrand) BoardModelSection(
                        // Always show WHICH board it is, not just the size
                        // (FEAT-031): MoonBoard shows its variant; an Aurora board
                        // shows its name/variant + size; Kilter shows the size.
                        boardModelName = run {
                            val brand = activeBoardBrand
                            val detail = when {
                                brand == BoardBrand.MOONBOARD ->
                                    state.moonBoardVariant?.displayName ?: ""
                                brand.usesAuroraProtocol && brand != BoardBrand.KILTER -> {
                                    val boardName = BoardConstants
                                        .auroraVariant(brand, state.boardLayoutId)?.displayName
                                        ?: brand.displayName
                                    if (state.boardProductSizeName.isNotBlank())
                                        "$boardName · ${state.boardProductSizeName}" else boardName
                                }
                                else -> state.boardProductSizeName
                            }
                            boardSelectionLabel(brand, state.boardLayoutId, detail)
                        },
                        onChangeModel = { showBoardModelDialog = true },
                    )
                    // FEAT-049: which of the variant's hold sets are actually
                    // mounted. Renders nothing for any other brand, and none
                    // for MoonBoard 2010 (one set, no choice).
                    if (settingsBoardBrand == activeBoardBrand && settingsBoardBrand == BoardBrand.MOONBOARD) {
                        MoonBoardHoldSetSection()
                    }
                    if (settingsBoardBrand == BoardBrand.MOONBOARD) {
                        MoonBoardLedPositionSection(
                            ledMode = state.moonBoardLedMode,
                            onModeChange = viewModel::updateMoonBoardLedMode,
                        )
                    }
                    if (settingsBoardBrand == activeBoardBrand) {
                        HorizontalDivider()
                        BoardSendModeSection(
                            singleConnectionMode = state.singleConnectionBoardSendMode,
                            multiConnectionMode = state.multiConnectionBoardSendMode,
                            boardBrand = activeBoardBrand,
                            onSingleConnectionModeChange =
                                viewModel::updateSingleConnectionBoardSendMode,
                            onMultiConnectionModeChange =
                                viewModel::updateMultiConnectionBoardSendMode,
                        )
                        HorizontalDivider()
                        BleAutoDisconnectSection(
                            bleAutoDisconnectSeconds = state.bleAutoDisconnectSeconds,
                            boardBrand = activeBoardBrand,
                            onAutoDisconnectChange = { viewModel.updateBleAutoDisconnect(it) },
                        )
                    }
                    if (showsKilterLedColors(settingsBoardBrand)) {
                        HorizontalDivider()
                        LedColorSection(
                            ledColors = state.ledColors,
                            onColorChange = { roleId, colorByte -> viewModel.updateLedColor(roleId, colorByte) },
                            onResetColors = { viewModel.resetLedColors() },
                            onKilterColors = { viewModel.setKilterColors() }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Accounts, board-logbook imports and data lifecycle belong together.
            CollapsibleHeader(stringResource(R.string.settings_section_accounts_data), accountsDataExpanded) { accountsDataExpanded = !accountsDataExpanded }
            AnimatedVisibility(visible = accountsDataExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(stringResource(R.string.settings_group_board_accounts_imports))
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
                        onClimbPublishEnabledChanged = { viewModel.setKilterClimbPublishEnabled(it) },
                        onDisconnect = { viewModel.kilterDisconnect() },
                        onShowDisconnectConfirm = { viewModel.showKilterDisconnectConfirm() },
                        onDismissDisconnectConfirm = { viewModel.dismissKilterDisconnectConfirm() },
                        onDismissResult = { viewModel.dismissKilterResult() },
                        onRetryPublishQueueNow = { viewModel.retryKilterPublishQueueNow() },
                    )
                    HorizontalDivider()
                    BoardLogbookImportSection(
                        onNavigateToAuroraMigration = onNavigateToAuroraMigration,
                        onNavigateToMoonBoardCsvImport = onNavigateToMoonBoardCsvImport,
                    )
                    HorizontalDivider()
                    SettingsGroupHeader(stringResource(R.string.settings_group_board_catalogs))
                    BoardSyncSection(
                        syncInterval = state.syncInterval,
                        onSyncIntervalChange = { viewModel.updateSyncInterval(it) },
                    )
                    BoardSyncInlineCard()
                    HorizontalDivider()
                    SettingsGroupHeader(stringResource(R.string.settings_group_backup_transfer))
                    BackupSettingsSection(
                        state = backupState,
                        onSetBackupEnabled = { backupViewModel.setBackupEnabled(it) },
                        onSetInterval = { backupViewModel.setInterval(it) },
                        onRunBackupNow = { backupViewModel.runBackupNow() },
                        onTriggerRestore = { backupViewModel.triggerManualRestore() },
                        onRequestDeleteRemote = { backupViewModel.requestDeleteRemoteBackups() },
                        onNavigateToKeyManagement = onNavigateToKeyManagement,
                    )
                    HorizontalDivider()
                    AppDataTransferSection(
                        deleteSuccess = state.deleteSuccess,
                        onNavigateToImport = onNavigateToImport,
                        onNavigateToExport = onNavigateToExport,
                        onDismissDeleteSuccess = { viewModel.dismissDeleteSuccess() },
                    )
                    HorizontalDivider()
                    SettingsGroupHeader(stringResource(R.string.settings_group_delete_data))
                    DataDeletionSection(
                        showDeleteBoardDataDialog = state.showDeleteBoardDataDialog,
                        showDeleteUserDataDialog = state.showDeleteUserDataDialog,
                        isDeletingBoardData = state.isDeletingBoardData,
                        selectedBrands = state.deleteDialogSelection,
                        onShowDeleteBoardDataDialog = { viewModel.showDeleteBoardDataDialog() },
                        onShowDeleteUserDataDialog = { viewModel.showDeleteUserDataDialog() },
                        onToggleBrand = { viewModel.toggleDeleteDialogBrand(it) },
                        onToggleSelectAll = { viewModel.toggleDeleteDialogSelectAll() },
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
                    AccountKeysSection(
                        onNavigateToKeyManagement = onNavigateToKeyManagement,
                        onNavigateToNostrProfile = onNavigateToNostrProfile,
                    )
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
            boardImportInProgress = backupState.boardImportInProgress,
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
            is BackupSettingsState.Snackbar.RestoreSucceeded ->
                stringResource(
                    R.string.settings_backup_restored,
                    snackbar.logbookEntries,
                    snackbar.lists,
                )
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

    // FEAT-027: MoonBoard catalogue-sync result, surfaced after the user
    // selects a MoonBoard variant in the board picker.
    state.moonBoardSyncMessage?.let { syncMessage ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoonBoardSyncMessage() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissMoonBoardSyncMessage() }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = { Text(syncMessage) },
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

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = OrangeAccent,
    )
}

@Composable
internal fun BoardHubCard(
    card: BoardSettingsCard,
    selectedForSettings: Boolean,
    onSelect: () -> Unit,
) {
    val activeLabel = stringResource(R.string.settings_board_active_badge)
    OutlinedCard(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 176.dp)
            .testTag("settings_board_card_${card.brand.wireValue}")
            .selectable(
                selected = selectedForSettings,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .semantics {
                selected = selectedForSettings
                if (card.isActive) stateDescription = activeLabel
            },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selectedForSettings) {
                OrangeAccent.copy(alpha = 0.10f)
            } else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (selectedForSettings) 2.dp else 1.dp,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(card.brand.displayName, fontWeight = FontWeight.Bold)
            if (card.isActive) {
                Text(
                    activeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = OrangeAccent,
                )
            }
        }
    }
}

internal fun showsKilterLedColors(boardBrand: BoardBrand): Boolean =
    boardBrand == BoardBrand.KILTER

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
    is DeleteRemoteNote.TombstonePublishFailed ->
        stringResource(
            R.string.delete_remote_note_tombstone_publish_failed,
            note.backupAccepted,
            note.keyAccepted,
            note.attempted,
        )
    is DeleteRemoteNote.UnexpectedError ->
        stringResource(R.string.delete_remote_note_unexpected_error, note.type)
}
