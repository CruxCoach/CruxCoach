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
    onNavigateToSync: () -> Unit,
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
            BoardModelSelectionDialog(
                productSizes = state.productSizes,
                selectedId = state.boardProductSizeId,
                onConfirm = { id ->
                    val name = state.productSizes.find { it.id.toInt() == id }?.name ?: ""
                    viewModel.updateBoardProductSize(id, name)
                    showBoardModelDialog = false
                },
                onDismiss = { showBoardModelDialog = false },
                onNavigateToSync = {
                    showBoardModelDialog = false
                    onNavigateToSync()
                }
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
                        bleAutoDisconnectMinutes = state.bleAutoDisconnectMinutes,
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
            CollapsibleHeader(stringResource(R.string.settings_section_data), dataExpanded) { dataExpanded = !dataExpanded }
            AnimatedVisibility(visible = dataExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoardSyncSection(
                        syncInterval = state.syncInterval,
                        lastSyncTimestamp = state.lastSyncTimestamp,
                        onSyncIntervalChange = { viewModel.updateSyncInterval(it) },
                        onNavigateToSync = onNavigateToSync
                    )
                    HorizontalDivider()
                    DataManagementSection(
                        showDeleteBoardDataDialog = state.showDeleteBoardDataDialog,
                        showDeleteUserDataDialog = state.showDeleteUserDataDialog,
                        deleteSuccess = state.deleteSuccess,
                        onNavigateToImport = onNavigateToImport,
                        onNavigateToExport = onNavigateToExport,
                        onShowDeleteBoardDataDialog = { viewModel.showDeleteBoardDataDialog() },
                        onShowDeleteUserDataDialog = { viewModel.showDeleteUserDataDialog() },
                        onDismissDeleteDialog = { viewModel.dismissDeleteDialog() },
                        onDismissDeleteSuccess = { viewModel.dismissDeleteSuccess() },
                        onDeleteBoardData = { viewModel.deleteBoardData() },
                        onDeleteUserBoardData = { viewModel.deleteUserBoardData() }
                    )
                    HorizontalDivider()
                    BackupSettingsSection(
                        state = backupState,
                        onSetBackupEnabled = { backupViewModel.setBackupEnabled(it) },
                        onSetInterval = { backupViewModel.setInterval(it) },
                        onRunBackupNow = { backupViewModel.runBackupNow() },
                        onTriggerRestore = { backupViewModel.triggerManualRestore() },
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
    backupState.snackbar?.let { snackbar ->
        val messageRes = when (snackbar) {
            BackupSettingsState.Snackbar.NoBackupFound -> R.string.settings_backup_no_backup_found
            BackupSettingsState.Snackbar.RestoreFailed -> R.string.settings_backup_restore_failed
            BackupSettingsState.Snackbar.BackupQueued -> R.string.settings_backup_queued
        }
        AlertDialog(
            onDismissRequest = { backupViewModel.consumeSnackbar() },
            confirmButton = {
                TextButton(onClick = { backupViewModel.consumeSnackbar() }) {
                    Text(stringResource(R.string.settings_backup_restore_cancel))
                }
            },
            text = { Text(stringResource(messageRes)) },
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
