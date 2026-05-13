package com.cruxcoach.android.ui.board.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.BleConnectionViewModel
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.QuickSendStatus
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbValidation

/**
 * Climb-creator screen. Composes:
 *   - Board visualization with tap-to-cycle role
 *   - Hold-count indicators + validation status
 *   - Metadata fields (name, description, grade slider, angle dropdown)
 *   - Save-as-draft / Publish actions
 *
 * State lives in [ClimbEditorViewModel] (Hilt-scoped to the
 * NavBackStackEntry). Compose UI is intentionally thin — all state
 * transitions go through the ViewModel for testability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClimbEditorScreen(
    onBack: () -> Unit,
    onPublished: (uuid: String) -> Unit,
    onNavigateToKilterSettings: () -> Unit = {},
    onNavigateToNostrProfile: () -> Unit = {},
    viewModel: ClimbEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // BLE button + send pipeline mirrors BoardClimbDetailScreen 1:1:
    //  * tap behaviour branches on userPrefs.quickBoardSend (the "schnell
    //    senden" setting) into the macro or the manual sheet.
    //  * BoardClimbDetailViewModel has BoardSendController that auto-
    //    fires sendClimb on every CONNECTED transition; the editor
    //    achieves the same via LaunchedEffect(bleConnected) below.
    //  * QuickSendStatus.NeedsManualPick escalates back into the sheet
    //    so the user can pick when 2+ boards are in range.
    //  * Subsequent hold edits on a still-connected board are live-
    //    mirrored by ClimbEditorViewModel.applyEditor -> syncLeds(),
    //    which is a no-op when disconnected.
    val bleConnViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnState by bleConnViewModel.state.collectAsStateWithLifecycle()
    val bleConnected = bleConnState.connectionState.let {
        it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
    }
    var showBleSheet by remember { mutableStateOf(false) }
    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            autoStartScan = true,
        )
    }
    // Editor-side equivalent of BoardSendController.kt's CONNECTED-collector:
    // every transition into CONNECTED pushes the current hold map. Covers
    // both the quick-send macro (scan/connect lands → fires) and the
    // manual sheet path (user picks → connects → fires). Subsequent
    // hold-tap edits keep the board in sync via syncLeds() inside
    // applyEditor — this LaunchedEffect only owns the initial push.
    LaunchedEffect(bleConnected) {
        if (bleConnected) viewModel.pushCurrentHoldsToBoard()
    }
    // NeedsManualPick + Done/Error reset — same observer pattern as
    // BoardClimbDetailScreen.kt:296-308. Multi-board ambiguity escalates
    // into the sheet; terminal Done/Error states reset the macro flow
    // so the next icon tap starts fresh. No snackbars here either —
    // the BLE-icon colour change (grey → green) is signal enough.
    val quickSendStatus by bleConnViewModel.quickSend.collectAsStateWithLifecycle()
    LaunchedEffect(quickSendStatus) {
        if (quickSendStatus is QuickSendStatus.NeedsManualPick) {
            showBleSheet = true
            bleConnViewModel.resetQuickSend()
        }
    }
    LaunchedEffect(quickSendStatus) {
        if (quickSendStatus is QuickSendStatus.Done || quickSendStatus is QuickSendStatus.Error) {
            bleConnViewModel.resetQuickSend()
        }
    }

    val nudgeMessage = stringResource(R.string.climb_creator_kilter_connect_nudge)
    val nudgeAction = stringResource(R.string.climb_creator_kilter_connect_action)
    val draftSavedMessage = stringResource(R.string.climb_creator_draft_saved)
    val autoNoteTemplate = stringResource(R.string.auto_note_default_template)
    val coroutineScope = rememberCoroutineScope()

    val autoNoteFailedMessage = stringResource(R.string.climb_creator_auto_note_zero_relays)
    val publishBothOkMessage = stringResource(R.string.climb_creator_publish_both_ok)
    val publishKilterPendingMessage = stringResource(R.string.climb_creator_publish_kilter_pending)
    LaunchedEffect(
        state.publishedUuid,
        state.showKilterConnectNudge,
        state.kilterPublishOutcome,
        state.autoNotePublished,
    ) {
        val uuid = state.publishedUuid ?: return@LaunchedEffect
        // One outcome snackbar per publish, picked from the dominant
        // state — most-actionable first. Rationale: pre-0.1.4 the editor
        // either showed the connect-Kilter nudge OR a Kilter failure
        // message OR nothing, leaving the happy path completely silent
        // (user couldn't tell whether a "save without confirmation" was
        // the success or a swallowed error). The four cases below mirror
        // FEAT-003's publish-state matrix:
        //   • Kilter not connected → existing nudge with action button
        //   • Kilter sync failed/diverged → "Nostr ok, Kilter retry"
        //   • Both networks green → "Nostr + Kilter"
        //   • User opted Kilter publishing off → silent (no Kilter
        //     expectation to manage)
        // Auto-note failure is surfaced separately afterwards because
        // it's an orthogonal Kind-1 dispatch, not a Kind-30078 outcome.
        val kilterOutcome = state.kilterPublishOutcome
        when {
            state.showKilterConnectNudge -> {
                val result = snackbarHostState.showSnackbar(
                    message = nudgeMessage,
                    actionLabel = nudgeAction,
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                viewModel.clearKilterConnectNudge()
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    viewModel.clearKilterPublishOutcome()
                    viewModel.clearAutoNoteOutcome()
                    onNavigateToKilterSettings()
                    return@LaunchedEffect
                }
            }
            kilterOutcome is com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome.Failed ||
                kilterOutcome is com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome.Diverged ->
                snackbarHostState.showSnackbar(publishKilterPendingMessage)
            kilterOutcome is com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome.Synced ->
                snackbarHostState.showSnackbar(publishBothOkMessage)
            // else: kilterOutcome == null (user disabled Kilter publishing
            // in settings) or Skipped(non-login reason). Stay silent —
            // navigating to the detail screen is the implicit success
            // signal for these "Nostr-only by design" paths.
        }
        viewModel.clearKilterPublishOutcome()
        // Auto-Note Kind-1 reach: surface "0 relays accepted the
        // announcement" as its own snackbar — pre-fix this case looked
        // identical to a successful publish from the user's POV.
        if (state.autoNotePublished == false) {
            snackbarHostState.showSnackbar(autoNoteFailedMessage)
        }
        viewModel.clearAutoNoteOutcome()
        onPublished(uuid)
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            viewModel.clearInfoMessage()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.climb_creator_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // 1:1 with BoardClimbDetailScreen.kt:399-414:
                            // Settings → "Schnell-Senden" routes the tap
                            // through the macro (scan → auto-connect-on-
                            // single → CONNECTED → editor's
                            // LaunchedEffect(bleConnected) auto-fires send
                            // → SENDING → CONNECTED → disconnect). When
                            // off, opens the manual connection sheet.
                            // isRoute is always false for the editor —
                            // the climb is a single-frame draft.
                            if (bleConnState.quickBoardSendEnabled) {
                                bleConnViewModel.startQuickSend(isRoute = false)
                            } else {
                                showBleSheet = true
                            }
                        },
                        modifier = Modifier.testTag("climb_creator_ble_connect_button"),
                    ) {
                        Icon(
                            if (bleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                            contentDescription = stringResource(
                                if (bleConnected) R.string.cd_board_connected else R.string.cd_board_connect,
                            ),
                            tint = if (bleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = viewModel::clearEditor) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.climb_creator_clear),
                        )
                    }
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.climb_creator_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.climb_creator_redo))
                    }
                    // Overflow menu: drafts list + heatmap toggle. Six
                    // primary actions in this TopAppBar squeezed the
                    // title into one or two characters on phone widths;
                    // demoting the two least-frequently-tapped actions
                    // (drafts is opened occasionally, heatmap is a
                    // discoverability aid) restores the title.
                    var overflowExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.action_more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.climb_creator_drafts_open)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.openDraftsSheet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.climb_creator_heatmap_toggle)) },
                                leadingIcon = {
                                    Icon(
                                        if (state.heatmapEnabled) Icons.Filled.Whatshot else Icons.Outlined.Whatshot,
                                        contentDescription = null,
                                        tint = if (state.heatmapEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.toggleHeatmap()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Live board visualization
            val activeHolds = state.editor.selectedHolds.map { (pid, role) -> BoardHold(pid, role) }
            KilterBoardVisualization(
                holds = activeHolds,
                placements = state.placements,
                boardSize = state.boardSize,
                boardImages = state.boardImages,
                heatmapData = if (state.heatmapEnabled) state.heatmap else null,
                selectedHolds = state.editor.selectedHolds.keys,
                onHoldTapped = viewModel::toggleHold,
                onHoldMoved = viewModel::moveHold,
                ledColors = state.ledColors,
                solidHoldFill = true,
                allowZoom = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HoldCountStatus(
                holds = state.editor.selectedHolds,
                activeBrush = state.editor.activeBrush,
                ledColors = state.ledColors,
                onBrushTap = viewModel::toggleBrush,
            )

            HorizontalDivider()

            OutlinedTextField(
                value = state.editor.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.climb_creator_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.editor.description,
                onValueChange = viewModel::setDescription,
                label = { Text(stringResource(R.string.climb_creator_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            GradeSlider(
                gradeId = state.editor.setterGradeId,
                onChange = { viewModel.setSetterGradeId(it) },
            )

            AngleDropdown(
                angle = state.editor.angle,
                onChange = { viewModel.setAngle(it) },
            )

            Spacer(Modifier.height(8.dp))

            // Validation status sits right above the action buttons so the
            // user reads the failing rule and the disabled Publish in one
            // glance — no scrolling up to figure out why it's locked.
            ValidationStatus(state.validationIssues)

            // Auto-Note opt-in for this publish. Vorbelegt aus
            // userPreferences.autoNoteEnabled (siehe VM init); per-publish
            // Override ändert nur den lokalen Editor-State.
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = state.alsoPostNote,
                    onCheckedChange = { viewModel.setAlsoPostNote(it, autoNoteTemplate) },
                )
                Text(
                    stringResource(R.string.climb_creator_also_post_note),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            // Per-publish editor for the Kind-1 announcement text. Only
            // visible while the Auto-Note checkbox is on. The text is
            // pre-seeded with the default template (substituted at
            // publish time — placeholders like {name}, {naddr},
            // {npub_cruxcoach}, {cruxcoach_url} stay literal here so
            // the user can keep them, edit them out, or rewrite the
            // surrounding prose without losing the dynamic parts.
            if (state.alsoPostNote) {
                androidx.compose.material3.OutlinedTextField(
                    value = state.autoNoteText.orEmpty(),
                    onValueChange = { viewModel.setAutoNoteText(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    label = { Text(stringResource(R.string.climb_creator_auto_note_text_label)) },
                    supportingText = { Text(stringResource(R.string.climb_creator_auto_note_placeholders_hint), style = MaterialTheme.typography.bodySmall) },
                    minLines = 4,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isEditingExisting) {
                    OutlinedButton(
                        // Save-as-draft stays in the editor: the user can keep
                        // tweaking and the now-loaded draft gets re-saved in
                        // place via loadedDraftUuid. Same validation gate as
                        // Publish so the user gets one consistent reason the
                        // buttons are disabled — the ValidationStatus list
                        // above already shows what's still missing. Hidden
                        // when editing an already-published climb (a
                        // published climb is not a draft).
                        onClick = {
                            viewModel.saveAsDraft { _ ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(draftSavedMessage)
                                }
                            }
                        },
                        enabled = !state.isPublishing && state.validationIssues.isEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.climb_creator_save_draft)) }
                }
                Button(
                    onClick = { viewModel.publish(sizeLabel = "12x12", autoNoteTemplate = autoNoteTemplate) },
                    enabled = !state.isPublishing && state.validationIssues.isEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.climb_creator_publish)) }
            }
        }
    }

    // Duplicate-warning dialog (shown only when triggered as publish-gate).
    if (state.pendingPublishConfirm) {
        state.duplicateOf?.let { dup ->
            AlertDialog(
                onDismissRequest = viewModel::cancelPublishOnDuplicate,
                title = { Text(stringResource(R.string.climb_creator_dup_title)) },
                text = { Text(stringResource(R.string.climb_creator_dup_message, dup.name)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmPublishWithDuplicate("12x12", autoNoteTemplate) }) {
                        Text(stringResource(R.string.climb_creator_dup_publish_anyway))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelPublishOnDuplicate) {
                        Text(stringResource(R.string.climb_creator_dup_continue))
                    }
                },
            )
        }
    }

    if (state.pendingProfileHint) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissProfileHintAndPublish("12x12", autoNoteTemplate) },
            title = { Text(stringResource(R.string.profile_hint_dialog_title)) },
            text = { Text(stringResource(R.string.profile_hint_dialog_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::acceptProfileHint) {
                    Text(stringResource(R.string.profile_hint_dialog_setup))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissProfileHintAndPublish("12x12", autoNoteTemplate) }) {
                    Text(stringResource(R.string.profile_hint_dialog_later))
                }
            },
        )
    }

    LaunchedEffect(state.profileSetupRequested) {
        if (state.profileSetupRequested) {
            viewModel.acknowledgeProfileSetupNavigated()
            onNavigateToNostrProfile()
        }
    }

    if (state.draftsSheetOpen) {
        DraftsDrawer(
            drafts = state.drafts,
            onSelect = viewModel::loadDraft,
            onDelete = viewModel::deleteDraft,
            onDismiss = viewModel::closeDraftsSheet,
        )
    }
}

/**
 * Inline banner at the top of the editor offering to restore a previous
 * unsaved session. Two paths: accept (load into editor) or discard
 * (clear the autosave from DataStore).
 */
/**
 * Hold-count chips that double as brush-selectors. Tapping a chip arms
 * the brush so subsequent board taps paint that role; tapping again
 * disarms (back to cycle-on-tap). Active brush has the chip's selected
 * state visually highlighted.
 *
 * The role-coloured leading icon makes the colour mapping explicit so
 * users don't have to guess which board colour means which role.
 */
@Composable
private fun HoldCountStatus(
    holds: Map<Int, Int>,
    activeBrush: Int?,
    ledColors: com.cruxcoach.android.data.LedHoldColors,
    onBrushTap: (role: Int) -> Unit,
) {
    val starts = holds.values.count { it == HoldRole.START }
    val hands = holds.values.count { it == HoldRole.HAND }
    val feet = holds.values.count { it == HoldRole.FOOT }
    val finishes = holds.values.count { it == HoldRole.FINISH }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (activeBrush == null) {
            Text(
                text = stringResource(R.string.climb_creator_brush_hint_delete_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrushChip(
                label = stringResource(R.string.climb_creator_count_start, starts),
                role = HoldRole.START,
                roleColor = com.cruxcoach.android.ui.theme.rgb332ToComposeColor(ledColors.start),
                isActive = activeBrush == HoldRole.START,
                onClick = onBrushTap,
            )
            BrushChip(
                label = stringResource(R.string.climb_creator_count_hand, hands),
                role = HoldRole.HAND,
                roleColor = com.cruxcoach.android.ui.theme.rgb332ToComposeColor(ledColors.hand),
                isActive = activeBrush == HoldRole.HAND,
                onClick = onBrushTap,
            )
            BrushChip(
                label = stringResource(R.string.climb_creator_count_foot, feet),
                role = HoldRole.FOOT,
                roleColor = com.cruxcoach.android.ui.theme.rgb332ToComposeColor(ledColors.foot),
                isActive = activeBrush == HoldRole.FOOT,
                onClick = onBrushTap,
            )
            BrushChip(
                label = stringResource(R.string.climb_creator_count_finish, finishes),
                role = HoldRole.FINISH,
                roleColor = com.cruxcoach.android.ui.theme.rgb332ToComposeColor(ledColors.finish),
                isActive = activeBrush == HoldRole.FINISH,
                onClick = onBrushTap,
            )
        }
    }
}

@Composable
private fun BrushChip(
    label: String,
    role: Int,
    roleColor: androidx.compose.ui.graphics.Color,
    isActive: Boolean,
    onClick: (Int) -> Unit,
) {
    FilterChip(
        selected = isActive,
        onClick = { onClick(role) },
        label = { Text(label) },
        leadingIcon = {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                drawCircle(color = roleColor)
            }
        },
    )
}

@Composable
private fun ValidationStatus(issues: List<ClimbValidation.Issue>) {
    if (issues.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.climb_creator_valid), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Column {
        for (issue in issues) {
            val msg = when (issue) {
                ClimbValidation.Issue.NoStartHold -> stringResource(R.string.climb_creator_issue_no_start)
                ClimbValidation.Issue.NoFinishHold -> stringResource(R.string.climb_creator_issue_no_finish)
                ClimbValidation.Issue.TooFewHolds -> stringResource(R.string.climb_creator_issue_too_few_holds, ClimbValidation.MIN_HOLDS_TOTAL)
                is ClimbValidation.Issue.TooManyHolds -> stringResource(R.string.climb_creator_issue_too_many_holds, ClimbValidation.MAX_HOLDS_TOTAL)
                is ClimbValidation.Issue.TooManyStarts -> stringResource(R.string.climb_creator_issue_too_many_starts, issue.count)
                is ClimbValidation.Issue.TooManyFinishes -> stringResource(R.string.climb_creator_issue_too_many_finishes, issue.count)
                ClimbValidation.Issue.NameMissing -> stringResource(R.string.climb_creator_issue_name_missing)
                is ClimbValidation.Issue.NameTooLong -> stringResource(R.string.climb_creator_issue_name_too_long, ClimbValidation.NAME_MAX_LENGTH)
                is ClimbValidation.Issue.DescriptionTooLong -> stringResource(R.string.climb_creator_issue_description_too_long, ClimbValidation.DESCRIPTION_MAX_LENGTH)
                ClimbValidation.Issue.AngleMissing -> stringResource(R.string.climb_creator_issue_angle_missing)
            }
            Text(
                "• $msg",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GradeSlider(gradeId: Int?, onChange: (Int?) -> Unit) {
    val effective = gradeId ?: com.cruxcoach.domain.board.KilterGradeMapper.DEFAULT_SETTER_GRADE_ID
    // Default-seeding now lives in ClimbCreatorRepository.saveDraft /
    // updateDraft (single defensive ?: at write time). Earlier this
    // composable also seeded editor state via LaunchedEffect(Unit), but
    // the seed lost a race against _state.update calls from loadDraft /
    // seedFromEdit and drafts could still persist with NULL grade.
    val vGrade = com.cruxcoach.domain.board.KilterGradeMapper.difficultyToVScale(effective)
    val font = com.cruxcoach.domain.board.KilterGradeMapper.difficultyToFont(effective.toDouble())
    Column {
        Text(
            stringResource(R.string.climb_creator_grade_label, vGrade, font),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = effective.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 10f..33f,
            steps = 22,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AngleDropdown(angle: Int?, onChange: (Int?) -> Unit) {
    val angles = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = angle?.let { "${it}°" } ?: stringResource(R.string.climb_creator_angle_pick),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.climb_creator_angle_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (a in angles) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("${a}°") },
                    onClick = {
                        onChange(a)
                        expanded = false
                    },
                )
            }
        }
    }
}
