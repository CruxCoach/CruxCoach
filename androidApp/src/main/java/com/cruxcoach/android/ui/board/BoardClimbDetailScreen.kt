package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.MoonBoardVariant
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.util.ClimbShareLink
import com.cruxcoach.android.util.PerfLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardClimbDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: ((climbUuid: String, angle: Int) -> Unit)? = null,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    onNavigateToFork: (climbUuid: String) -> Unit = {},
    onNavigateToEdit: (climbUuid: String) -> Unit = {},
    onNavigateToSetter: (pubkey: String) -> Unit = {},
    viewModel: BoardClimbDetailViewModel = hiltViewModel()
) {
    PerfLogger.navMilestone("BoardClimbDetailScreen composing")
    val resources = LocalResources.current
    // collectAsState (NOT ...WithLifecycle): the detail's nav entry can stay
    // in a non-STARTED state behind the editor and not re-deliver on return,
    // leaving the climb stale after an edit even though the VM reloaded it.
    // The setter list (which refreshes correctly) uses plain collectAsState too.
    val state by viewModel.state.collectAsState()
    val isRestTimerRunning by viewModel.isRestTimerRunning.collectAsStateWithLifecycle()
    val isSharingEnabled by viewModel.isSharingEnabled.collectAsStateWithLifecycle()
    val pageCache by viewModel.pageCache.collectAsStateWithLifecycle()
    val addToListViewModel: AddToListViewModel = hiltViewModel()
    var showBleSheet by remember { mutableStateOf(false) }
    var showRestTimerDialog by remember { mutableStateOf(false) }

    // BLE sheet lives here (once), not inside per-page content
    val detailQueueManager = com.cruxcoach.android.ui.common.LocalSessionQueueManager.current
    val detailQueueState by detailQueueManager.state.collectAsStateWithLifecycle()

    // Swipe navigation: only use queue items when the user navigated FROM the queue sheet.
    // When navigating from the browser, always use the full browser climb list —
    // regardless of whether a session queue is active.
    val navigatedFromQueue = viewModel.climbNavState.source == com.cruxcoach.android.ui.navigation.ClimbNavigationSource.QUEUE
    val rawNavUuids = if (navigatedFromQueue && detailQueueState.isActive && detailQueueState.queue.isNotEmpty()) {
        detailQueueState.queue.map { it.climbUuid }
    } else {
        remember { viewModel.climbNavState.climbUuids }
    }
    // Defense against stale climbNavState. Some navigation paths
    // (SetterDetailScreen, push notifications, deep-links) navigate
    // straight to boardClimbDetail without first refreshing
    // climbNavState.climbUuids — leaving it pointing at the previous
    // browser/logbook session. Without this guard the pager would
    // open at index 0 of that stale list, which means tapping a
    // climb in the new screen lands the user on a *completely
    // different* climb (the first entry of whatever they were
    // browsing before). When the route's UUID isn't in the cached
    // list, drop to a single-page render of just that UUID — the
    // user loses left/right swipe-paging for this screen instance,
    // but at least sees the climb they actually tapped.
    val navUuids = remember(rawNavUuids, viewModel.initialClimbUuid) {
        // distinct(): the pager keys pages by uuid, and queue-sourced lists
        // may repeat climbs (playlist attempt structure) — a duplicate key
        // crashes the pager. Writers dedup too; this is the backstop.
        val unique = rawNavUuids.distinct()
        if (viewModel.initialClimbUuid in unique) unique
        else listOf(viewModel.initialClimbUuid)
    }
    val navAngle = if (navigatedFromQueue && detailQueueState.isActive && detailQueueState.queue.isNotEmpty()) {
        detailQueueState.queue.firstOrNull()?.angle ?: remember { viewModel.climbNavState.angle }
    } else {
        remember { viewModel.climbNavState.angle }
    }
    val initialIndex = remember(navUuids) {
        val idx = navUuids.indexOf(viewModel.initialClimbUuid)
        if (idx >= 0) idx else 0
    }
    val hasPager = navUuids.size > 1

    // Navigation perf: mark when first meaningful content is visible
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && state.climb != null) {
            PerfLogger.navEnd("BoardClimbDetail(${state.climb?.uuid})")
        }
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
        )
    }

    // Dialogs — driven by active-page state, only need one instance
    if (state.ascent.showDialog) {
        AscentLoggingDialog(
            isEditing = state.ascent.editingUuid != null,
            isSend = state.ascent.isSend,
            bidCount = state.ascent.bidCount,
            quality = state.ascent.quality,
            comment = state.ascent.comment,
            isBenchmark = state.ascent.isBenchmark,
            onIsBenchmarkChanged = { viewModel.updateAscentIsBenchmark(it) },
            onIsSendChanged = { viewModel.updateAscentIsSend(it) },
            onBidCountChanged = { viewModel.updateAscentBidCount(it) },
            onQualityChanged = { viewModel.updateAscentQuality(it) },
            onCommentChanged = { viewModel.updateAscentComment(it) },
            onSave = { viewModel.saveAscent() },
            onDismiss = { viewModel.dismissAscentDialog() }
        )
    }

    state.ascent.deleteConfirmUuid?.let {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text(stringResource(R.string.board_detail_delete_ascent_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_detail_delete_ascent_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteAscent() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Community-publication delete confirmation. The text differs based
    // on whether the climb was also pushed to Kilter — Kilter has no
    // delete API so the user has to clean up there manually.
    // Draft (local-only) delete confirmation. Reuses the
    // climb_creator_drafts_delete_* strings so the wording matches
    // the editor's drafts-drawer flow — same action, different entry
    // point.
    state.draftDeleteDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = {
                if (!dialog.isInProgress) viewModel.dismissDraftDeleteDialog()
            },
            title = {
                Text(
                    stringResource(R.string.climb_creator_drafts_delete_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.climb_creator_drafts_delete_message, dialog.name))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDraftDelete(onDeleted = onNavigateBack) },
                    enabled = !dialog.isInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("boarddetail_delete_draft_confirm"),
                ) {
                    Text(
                        stringResource(R.string.climb_creator_drafts_delete_confirm),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissDraftDeleteDialog() },
                    enabled = !dialog.isInProgress,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.communityDeleteDialog?.let { dialog ->
        val bodyRes = if (dialog.kilterAlsoPublished) {
            R.string.community_climb_delete_confirm_kilter_warning
        } else {
            R.string.community_climb_delete_confirm_nostr_only
        }
        AlertDialog(
            onDismissRequest = {
                if (!dialog.isInProgress) viewModel.dismissCommunityDeleteDialog()
            },
            title = {
                Text(
                    stringResource(R.string.community_climb_delete_confirm_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(bodyRes)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmCommunityDelete(onDeleted = onNavigateBack) },
                    enabled = !dialog.isInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("boarddetail_delete_publication_confirm"),
                ) {
                    Text(
                        stringResource(R.string.community_climb_delete_confirm_button),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissCommunityDeleteDialog() },
                    enabled = !dialog.isInProgress,
                ) {
                    Text(stringResource(R.string.community_climb_delete_cancel_button))
                }
            },
        )
    }

    if (state.listDialog.show) {
        // Self-contained host (same as the browser long-press): includes
        // the "add to running playlist" shortcut and playlist-aware adds.
        state.climb?.let { climb ->
            AddToListDialogHost(
                climbUuid = climb.uuid,
                angle = state.angle,
                onDismiss = { viewModel.dismissAddToListDialog() },
                viewModel = addToListViewModel,
            )
        }
    }

    // Per-use custom rest-timer duration (settings value stays the
    // default + the post-logging auto-start).
    if (showRestTimerDialog) {
        RestTimerStartDialog(
            initialSeconds = state.restTimerTotalSeconds,
            onStart = {
                viewModel.startRestTimer(it)
                showRestTimerDialog = false
            },
            onDismiss = { showRestTimerDialog = false },
        )
    }

    // Remote disconnect request dialog (single instance)
    val bleConnViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnState by bleConnViewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // Share-link: clipboard + a coroutine scope to surface the "copied"
    // snackbar from the (non-composable) menu onClick.
    val clipboardManager = LocalClipboardManager.current
    val shareScope = rememberCoroutineScope()
    val linkCopiedMessage = stringResource(R.string.board_detail_link_copied)

    // Surface community-delete outcomes — the deleter returns success
    // even when no relay accepted (local-row tombstoned regardless),
    // so the snackbar text mentions the relay-accept count.
    LaunchedEffect(state.communityDeleteFeedback) {
        val feedback = state.communityDeleteFeedback ?: return@LaunchedEffect
        val msg = when (feedback) {
            is CommunityDeleteFeedback.Done -> {
                val template = if (feedback.kilterAlsoPublished) {
                    R.string.community_climb_delete_done_with_kilter
                } else {
                    R.string.community_climb_delete_done_nostr_only
                }
                resources.getString(template, feedback.accepted, feedback.attempted)
            }
            is CommunityDeleteFeedback.LocalTombstoneFailed ->
                resources.getString(
                    R.string.community_climb_delete_local_failed,
                    feedback.accepted, feedback.attempted,
                )
            CommunityDeleteFeedback.NotOwner -> resources.getString(R.string.community_climb_delete_not_owner)
            // Defensive: NotOurClimb / NotFound shouldn't reach the user
            // because the menu item is gated on origin=cruxcoach + owner.
            // If they ever do, fall back to the generic failure message.
            CommunityDeleteFeedback.NotOurClimb,
            CommunityDeleteFeedback.NotFound,
            CommunityDeleteFeedback.Failed -> resources.getString(R.string.community_climb_delete_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeCommunityDeleteFeedback()
    }

    // Surface own-Kilter-climb publish outcomes (same snackbar pattern as
    // the editor's publish result handling).
    LaunchedEffect(state.ownPublishFeedback) {
        val feedback = state.ownPublishFeedback ?: return@LaunchedEffect
        val msg = when (feedback) {
            OwnPublishFeedback.Published -> resources.getString(R.string.own_climb_publish_done)
            OwnPublishFeedback.NoNostrIdentity -> resources.getString(R.string.own_climb_publish_no_nostr)
            OwnPublishFeedback.NotAuthor -> resources.getString(R.string.own_climb_publish_not_author)
            OwnPublishFeedback.AlreadyPublished -> resources.getString(R.string.own_climb_publish_already)
            OwnPublishFeedback.Failed -> resources.getString(R.string.climb_creator_publish_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeOwnPublishFeedback()
    }

    LaunchedEffect(state.quickLogFeedback?.eventId) {
        val feedback = state.quickLogFeedback ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            resources.getString(
                if (feedback.isSend) R.string.board_detail_quick_send_logged
                else R.string.board_detail_quick_attempt_logged,
            ),
            actionLabel = resources.getString(R.string.climb_creator_undo),
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoQuickLog()
        else viewModel.consumeQuickLogFeedback()
    }

    LaunchedEffect(state.quickLogFailed) {
        if (!state.quickLogFailed) return@LaunchedEffect
        snackbarHostState.showSnackbar(resources.getString(R.string.board_detail_quick_log_failed))
        viewModel.consumeQuickLogFailure()
    }

    LaunchedEffect(state.personalNoteSaveStatus, state.climb?.uuid) {
        if (state.personalNoteSaveStatus != PersonalNoteSaveStatus.FAILED) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            resources.getString(R.string.board_detail_note_save_failed),
            actionLabel = resources.getString(R.string.action_retry),
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.savePersonalNote(state.personalNoteDraft)
        }
    }

    // Single Scaffold — shared across all pager pages
    val bleConnected = state.ble.connectionState.let {
        it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
    }

    // One delivery decision for the whole screen, taken by the one thing
    // allowed to take it. The dock renders it; it does not re-derive it from
    // a connection flag, which is how a lamp ends up on a board a group is
    // already using.
    val hasDirectPayload = state.climb?.let { climb ->
        BoardProjectionPolicy.hasSendablePayload(
            brand = climb.brand,
            holdCount = state.holds.size,
            frames = climb.frames,
        )
    } == true
    val deliveryDecision = BoardDeliveryPolicy.resolve(
        sendMode = state.boardSendMode,
        sessionRole = detailQueueState.role,
        sessionConnecting = detailQueueState.isConnecting,
        boardConnected = state.ble.connectionState == ConnectionState.CONNECTED ||
            state.ble.connectionState == ConnectionState.SENDING,
        hasDirectPayload = hasDirectPayload,
        connectedViaRelay = state.ble.connectedViaRelay,
    )
    // A lamp that is not there is only diagnosable from the inputs — the
    // decision itself says nothing about WHY it came out NONE.
    LaunchedEffect(deliveryDecision, state.playback.countdownSeconds) {
        Log.d(
            "CruxBLE/Delivery",
            "target=${deliveryDecision.target} show=${deliveryDecision.showAction} " +
                "auto=${deliveryDecision.dispatchAutomatically} mode=${state.boardSendMode} " +
                "role=${detailQueueState.role} connecting=${detailQueueState.isConnecting} " +
                "conn=${state.ble.connectionState} " +
                "holds=${state.holds.size} brand=${state.climb?.brand} " +
                "relay=${state.ble.connectedViaRelay}/${state.ble.hostedRelayClientCount} " +
                "countdown=${state.playback.countdownSeconds}"
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Same gate as the page body below: over an error card or a
            // logbook-only stub there is no climb to act on, and a dock
            // offering to light one would be offering nothing.
            if (state.climb != null && state.error == null && state.logbookOnly == null) {
                BoardDetailBottomActions(
                    state = state,
                    decision = deliveryDecision,
                    hasDirectPayload = hasDirectPayload,
                    boardOwnedByOthers = detailQueueState.isConnecting,
                    onAttempt = { viewModel.quickLogAscent(isSend = false) },
                    onLight = viewModel::deliverClimb,
                    onConnectBoard = { showBleSheet = true },
                    onSend = { viewModel.quickLogAscent(isSend = true) },
                )
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("boarddetail_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier.testTag("boarddetail_favorite_button")
                        ) {
                            Icon(
                                if (state.isFavorited) Icons.Default.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(if (state.isFavorited) R.string.cd_remove_favorite else R.string.cd_add_favorite),
                                tint = if (state.isFavorited) WarningYellow else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Personal lists, always. Putting the climb on the
                        // board's shared list is the pair of buttons under the
                        // board render — this icon used to be hijacked for it,
                        // which left the user's own lists unreachable exactly
                        // when a group was on the board.
                        IconButton(
                            onClick = { viewModel.showAddToListDialog() },
                            modifier = Modifier.testTag("boarddetail_add_to_list_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.cd_add_to_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { showBleSheet = true },
                            modifier = Modifier.testTag("boarddetail_ble_connect_button"),
                        ) {
                            Icon(
                                if (bleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = stringResource(
                                    if (bleConnected) R.string.cd_board_connected else R.string.cd_board_connect,
                                ),
                                tint = if (bleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { showRestTimerDialog = true },
                            modifier = Modifier.testTag("boarddetail_rest_timer_button")
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = stringResource(R.string.cd_rest_timer),
                                tint = if (isRestTimerRunning) OrangeAccent
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Owner gate for Edit/Delete inside the overflow.
                        // origin must be 'cruxcoach' (we can re-publish those
                        // via Replaceable Kind 30078) AND the climb's
                        // created_by_pubkey must match our local key.
                        val canEdit = state.climb?.origin == "cruxcoach" &&
                            state.climb?.createdByPubkey != null &&
                            state.climb?.createdByPubkey == state.currentUserPubkey
                        // Kilter's API treats published climbs as immutable
                        // (no PATCH, no DELETE — see KilterApiClient docstrings).
                        // For climbs we already mirrored to Kilter the editor
                        // would create divergent state — local + Nostr would
                        // hold the new version, Kilter the old, and the cron
                        // pipeline would see two truths for the same uuid.
                        // Hiding Edit (Delete still works, with warning) is
                        // the simplest invariant: "synced and diverged are
                        // frozen on Kilter, period."
                        val kilterImmutable = state.climb?.kilterStatus == "synced" ||
                            state.climb?.kilterStatus == "diverged"
                        var moreExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { moreExpanded = true },
                                modifier = Modifier.testTag("boarddetail_more_button"),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.action_more_options),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = moreExpanded,
                                onDismissRequest = { moreExpanded = false },
                            ) {
                                // The dock logs the two answers somebody has
                                // while standing under the wall. Quality, a
                                // grade opinion, a benchmark flag and a comment
                                // are a different, slower act — but they are
                                // still the same log, so they stay one tap away
                                // instead of only being reachable by logging
                                // first and then editing what you just logged.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_detail_log_ascent_detailed)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.EditNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    // AscentLogger.save() returns on a null
                                    // climb: the dialog would take the form and
                                    // then eat Save without closing.
                                    enabled = state.climb != null,
                                    onClick = {
                                        moreExpanded = false
                                        viewModel.showAscentDialog()
                                    },
                                    modifier = Modifier.testTag("boarddetail_log_button"),
                                )
                                HorizontalDivider()
                                // Mirror toggle — a display-only left/right flip
                                // of the climb. Only shown for layouts that are
                                // actually mirrorable (Aurora `is_mirrored`):
                                // Tension TB1 / TB2 Mirror, Grasshopper, Decoy,
                                // So iLL. Hidden for non-mirrorable layouts
                                // (Tension TB2 Spray, Touchstone, Kilter,
                                // MoonBoard) where a flip is meaningless or would
                                // light unpaired holds. Sits at the top of the
                                // overflow, above the owner-gated Edit/Delete.
                                if (state.isMirrorable) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (state.isMirrored) R.string.cd_mirror_off
                                                    else R.string.cd_mirror_on
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.SwapHoriz,
                                                contentDescription = null,
                                                tint = if (state.isMirrored) OrangeAccent
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        onClick = {
                                            moreExpanded = false
                                            viewModel.toggleMirror()
                                        },
                                        modifier = Modifier.testTag("boarddetail_mirror_toggle"),
                                    )
                                }
                                // Ignore / un-ignore: keep "Quatsch" climbs (e.g.
                                // the Weihnachtsbaum) out of every browse
                                // suggestion. Applies to any climb, so it sits
                                // with the mirror toggle above the owner-gated
                                // Edit/Delete actions.
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.isIgnored) R.string.cd_unignore_climb
                                                else R.string.cd_ignore_climb
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isIgnored) Icons.Default.Visibility
                                            else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = if (state.isIgnored) OrangeAccent
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = {
                                        moreExpanded = false
                                        viewModel.toggleIgnored()
                                    },
                                    modifier = Modifier.testTag("boarddetail_ignore_toggle"),
                                )
                                // Share: copy the cruxcoach.org/c/… App-Link.
                                // Community climbs get the naddr form (the same
                                // link the climb-creator Kind-1 note uses),
                                // catalogue climbs the raw-uuid form — both are
                                // parsed by MainActivity's App-Link handler.
                                val shareClimb = state.climb
                                val sharePubkey = shareClimb?.createdByPubkey
                                val shareUuid = shareClimb?.uuid
                                if (shareUuid != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_detail_share_link)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        onClick = {
                                            moreExpanded = false
                                            val link = ClimbShareLink.build(sharePubkey, shareUuid)
                                            clipboardManager.setText(AnnotatedString(link))
                                            shareScope.launch { snackbarHostState.showSnackbar(linkCopiedMessage) }
                                        },
                                        modifier = Modifier.testTag("boarddetail_share_link"),
                                    )
                                }
                                // Publish OWN Kilter climb to the CruxCoach
                                // community. Authorship-gated (canPublishAsMine:
                                // connected Kilter account == recorded climb
                                // author, identity match) and hidden once
                                // published. The publisher re-checks the gate,
                                // so this visibility flag is UX, not security.
                                if (state.canPublishAsMine) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.own_climb_publish_action)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Groups,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        enabled = !state.isOwnPublishInProgress,
                                        onClick = {
                                            moreExpanded = false
                                            viewModel.publishOwnClimb()
                                        },
                                        modifier = Modifier.testTag("boarddetail_publish_own_climb"),
                                    )
                                }
                                HorizontalDivider()
                                // Remix forks into the climb editor, which is
                                // now brand-aware (Kilter + MoonBoard). The
                                // editor opens in the active board's mode; the
                                // always-on board-fit filter means the climb on
                                // screen always matches the active board, so a
                                // MoonBoard climb is only ever remixed on a
                                // MoonBoard board.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.climb_creator_remix_action)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.CallSplit,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    enabled = state.climb != null,
                                    onClick = {
                                        moreExpanded = false
                                        state.climb?.uuid?.let(onNavigateToFork)
                                    },
                                    modifier = Modifier.testTag("boarddetail_fork_button"),
                                )
                                if (canEdit) {
                                    if (!kilterImmutable) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.climb_creator_edit_action)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            onClick = {
                                                moreExpanded = false
                                                state.climb?.uuid?.let(onNavigateToEdit)
                                            },
                                            modifier = Modifier.testTag("boarddetail_edit_button"),
                                        )
                                    } else {
                                        // Greyed-out Edit row + tooltip-style sub-text
                                        // so the user understands WHY Edit isn't here
                                        // (instead of just silently missing it).
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        stringResource(R.string.climb_creator_edit_action),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                    Text(
                                                        stringResource(R.string.climb_detail_edit_locked_kilter),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                )
                                            },
                                            enabled = false,
                                            onClick = {},
                                            modifier = Modifier.testTag("boarddetail_edit_button_locked"),
                                        )
                                    }
                                    HorizontalDivider()
                                    // Discriminate "own published" from "own
                                    // never-published draft" by `source`, NOT
                                    // by `nostr_event_id`.
                                    //
                                    // An own published climb is ADDRESSABLE: its
                                    // Kind-30078 d-tag is deterministically
                                    // `communityClimbDTag(ourPubkey, uuid)`, so
                                    // the deleter can NIP-09 tombstone it by
                                    // address ("a"-tag) even when this device
                                    // never stored a `nostr_event_id`. On a
                                    // FRESH INSTALL the community-synced chunk
                                    // for the user's own climb carries no
                                    // nostr_event_id, so the old
                                    // `nostrEventId.isNullOrBlank()` test
                                    // mis-classified it as a draft and routed it
                                    // to the local-only delete — which leaves
                                    // the event live on relays forever. Such a
                                    // synced row has `source='nostr'`, so it is
                                    // correctly treated as published here.
                                    //
                                    // The local-only draft path is reserved for
                                    // climbs the user never published — a local
                                    // creation that never reached a relay:
                                    // `source='local'` AND no event id. This
                                    // mirrors the deleteLocalClimb SQL gate
                                    // (`source='local' AND nostr_event_id IS
                                    // NULL`) exactly, so the UI label and the
                                    // server-side guard never disagree.
                                    val isUnpublishedDraft = state.climb?.let {
                                        it.source == "local" && it.nostrEventId.isNullOrBlank()
                                    } ?: false
                                    if (isUnpublishedDraft) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.climb_creator_drafts_delete_action)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                moreExpanded = false
                                                viewModel.requestDraftDelete()
                                            },
                                            modifier = Modifier.testTag("boarddetail_delete_draft"),
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.community_climb_delete_action)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                moreExpanded = false
                                                viewModel.requestCommunityDelete()
                                            },
                                            modifier = Modifier.testTag("boarddetail_delete_publication"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                run {
                    val qm = LocalSessionQueueManager.current
                    BleStatusArea(
                        currentClimbUuid = state.climb?.uuid,
                        onClimbTapped = { uuid, angle -> viewModel.switchClimb(uuid, angle) },
                        onAddToQueue = if (state.climb != null) {
                            { qm.addClimb(state.climb!!.uuid, state.angle) }
                        } else null
                    )
                }
            }
        }
    ) { padding ->
        if (hasPager) {
            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount = { navUuids.size }
            )

            // Switch active climb when user settles on a new page
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    val uuid = navUuids.getOrNull(page) ?: return@collect
                    viewModel.switchClimb(uuid, navAngle)
                }
            }

            // Preload adjacent pages for smooth swiping (wait until current page is loaded)
            LaunchedEffect(pagerState.settledPage, state.isLoading) {
                if (state.isLoading) return@LaunchedEffect
                val settled = pagerState.settledPage
                listOf(settled - 1, settled + 1)
                    .filter { it in navUuids.indices }
                    .forEach { viewModel.preloadClimb(navUuids[it], navAngle) }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                beyondViewportPageCount = 1,
                key = { navUuids[it] }
            ) { pageIndex ->
                val pageUuid = navUuids[pageIndex]
                val isActivePage = pagerState.settledPage == pageIndex
                val pageState = if (isActivePage) {
                    state
                } else {
                    // Same rollback as in switchClimb: a cached page carries a
                    // frozen copy of the connection and send mode, which is
                    // visible on the half-swiped neighbour.
                    pageCache[pageUuid]?.withLiveDeviceState(state)
                        ?: ClimbDetailState(isLoading = true)
                }

                ClimbDetailPageContent(
                    state = pageState,
                    isSharingEnabled = isSharingEnabled,
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    onNavigateToBugReport = onNavigateToBugReport,
                    onNavigateToSetter = onNavigateToSetter,
                )
            }
        } else {
            ClimbDetailPageContent(
                state = state,
                isSharingEnabled = isSharingEnabled,
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onNavigateToBugReport = onNavigateToBugReport,
                onNavigateToSetter = onNavigateToSetter,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/**
 * The controller palette, named.
 *
 * Layer numbers are the redundant cue everywhere else on this screen, but
 * inside the colour picker itself there are no numbers — only four circles.
 * These are what a screen reader has to work with, and they are also why the
 * palette is exactly four: the six eWalls swatches collapse to four protocol
 * colours, so two of them would otherwise be unnameable duplicates.
 */
@StringRes
internal fun boardLayerColorName(@ColorInt color: Int): Int = when (color) {
    BoardLayerManager.LAYER_COLORS[0] -> R.string.board_layer_color_green
    BoardLayerManager.LAYER_COLORS[1] -> R.string.board_layer_color_cyan
    BoardLayerManager.LAYER_COLORS[2] -> R.string.board_layer_color_magenta
    BoardLayerManager.LAYER_COLORS[3] -> R.string.board_layer_color_yellow
    else -> R.string.board_layer_custom_color
}

/**
 * What is on a multi-layer wall right now, in one line, above the wall.
 *
 * A rack big enough to operate four projections does not fit on a screen whose
 * whole point is that the climb gets the space, and it does not belong there
 * either: most of the time the question is "which of these is mine", not
 * "reassign a colour". So the answer stays visible and the controls open on
 * demand, the same way the climb's own details do.
 */
@Composable
private fun BoardLayerStrip(
    state: ClimbDetailState,
    onOpen: () -> Unit,
) {
    val maxLayers = state.climb?.brand?.maxSimultaneousClimbs ?: return
    if (maxLayers <= 1) return
    val bySlot = state.boardLayers.layers.associateBy { it.slot }
    val currentUuid = state.climb?.uuid
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("board_layer_strip"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.board_layers_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            for (slot in 0 until maxLayers) {
                val layer = bySlot[slot]
                val onWall = layer?.confirmedRouteUuid != null
                Surface(
                    modifier = Modifier
                        .size(width = 26.dp, height = 20.dp)
                        .testTag("board_layer_strip_slot_${slot + 1}"),
                    shape = RoundedCornerShape(6.dp),
                    color = layer?.let {
                        Color(it.color).copy(alpha = if (onWall) 1f else 0.35f)
                    } ?: Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(
                        if (layer?.climbUuid != null && layer.climbUuid == currentUuid) 2.dp else 1.dp,
                        if (layer?.climbUuid != null && layer.climbUuid == currentUuid) OrangeAccent
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // The number, not just the colour: two of the four
                        // controller colours are a hard pair to tell apart.
                        Text(
                            "${slot + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (layer != null) DarkBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(
                    R.string.board_layers_occupied,
                    state.boardLayers.occupiedCount,
                    maxLayers,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.board_layers_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardLayerSheet(
    state: ClimbDetailState,
    onDismiss: () -> Unit,
    onSelectSlot: (Int) -> Unit,
    onSelectColor: (Int) -> Unit,
    onAssignCurrent: () -> Unit,
    onSendSlot: (Int) -> Unit,
    onSendAll: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoardLayerRack(
                state = state,
                onSelectSlot = onSelectSlot,
                onSelectColor = onSelectColor,
                onAssignCurrent = onAssignCurrent,
                onSendSlot = onSendSlot,
                onSendAll = onSendAll,
                onRemove = onRemove,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The four controller colours, as a choice somebody can actually make.
 *
 * A swatch is a colour and nothing else: without semantics it reaches a screen
 * reader as four identical unlabelled buttons, and the layer numbers that carry
 * the redundancy everywhere else on this screen are not in here. So each one
 * carries its name, whether it is the current choice, and — when it is already
 * on the wall under somebody else's layer — why it cannot be picked, rather
 * than just going quiet on tap.
 */
@Composable
internal fun BoardLayerColorPicker(
    selectedColor: Int?,
    unavailableColors: Set<Int>,
    onSelectColor: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("board_layer_color_picker"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoardLayerManager.LAYER_COLORS.forEachIndexed { index, color ->
            val available = color !in unavailableColors
            val name = stringResource(boardLayerColorName(color))
            val takenName = stringResource(R.string.board_layer_color_unavailable, name)
            Surface(
                onClick = { if (available) onSelectColor(color) },
                enabled = available,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("board_layer_color_$index")
                    .semantics {
                        role = Role.RadioButton
                        selected = selectedColor == color
                        contentDescription = if (available) name else takenName
                    },
                shape = CircleShape,
                color = Color(color).copy(alpha = if (available) 1f else 0.22f),
                border = if (selectedColor == color) {
                    androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                } else null,
            ) {}
        }
    }
}

@Composable
private fun BoardLayerRack(
    state: ClimbDetailState,
    /**
     * A BoardCell group owns this wall. Layers are a direct-controller
     * feature: the mesh carries exactly one canonical projection, so a layer
     * sent through it would arrive stripped of its identity and colour and
     * still report success. Staging stays open; only the lamps close.
     */
    onSelectSlot: (Int) -> Unit,
    onSelectColor: (Int) -> Unit,
    onAssignCurrent: () -> Unit,
    onSendSlot: (Int) -> Unit,
    onSendAll: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    val maxLayers = BoardBrand.QUANTUM.maxSimultaneousClimbs
    val ownBySlot = state.boardLayers.layers.associateBy { it.slot }
    val currentLayer = state.climb?.uuid?.let { uuid ->
        state.boardLayers.layers.firstOrNull { it.climbUuid == uuid }
    }
    val explicitSlot = state.selectedBoardLayerSlot?.takeIf { it in 0 until maxLayers }
    val selectedSlot = explicitSlot
        ?: currentLayer?.slot
    val selectedLayer = selectedSlot?.let(ownBySlot::get)
    val selectedColor = state.selectedBoardLayerColor
        ?: selectedLayer?.color
        ?: selectedSlot?.let { BoardLayerManager.LAYER_COLORS[it] }
    val occupiedColors = state.boardLayers.layers.mapTo(mutableSetOf()) { it.color } +
        state.boardLayers.externalLayers.map { it.color }
    val sharedHoldCount = BoardLayerConflictPolicy.sharedHoldCount(
        state.holds,
        state.boardLayers.layers,
        selectedSlot,
    )
    val colorsOnOtherLayers = state.boardLayers.layers
        .filterNot { it.slot == selectedSlot }.mapTo(mutableSetOf()) { it.color } +
        state.boardLayers.externalLayers.map { it.color }
    val selectedColorConflict = selectedColor != null && selectedColor in colorsOnOtherLayers
    val connected = state.ble.connectionState == ConnectionState.CONNECTED
    val duplicateHoldCount = state.boardLayers.layers
        .flatMap { it.holds.map { hold -> hold.placementId } }
        .groupingBy { it }.eachCount().count { it.value > 1 }
    val newControllerIdentities = state.boardLayers.layers.count { it.confirmedRouteUuid == null }
    val canSendAll = state.boardLayers.occupiedCount + newControllerIdentities <= maxLayers

    Card(
        modifier = Modifier.fillMaxWidth().testTag("board_layer_rack"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.board_layers_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.board_layers_occupied,
                            state.boardLayers.occupiedCount,
                            maxLayers,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.boardLayers.externalLayers.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(stringResource(
                                R.string.board_layers_external,
                                state.boardLayers.externalLayers.size,
                            ))
                        },
                    )
                }
            }

            if (state.boardLayers.externalLayers.isNotEmpty()) {
                Text(
                    stringResource(R.string.board_layers_external_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.boardLayers.externalLayers.forEachIndexed { index, external ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(16.dp).background(Color(external.color), CircleShape))
                        Text(
                            stringResource(R.string.board_layer_external_player, index + 1),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            external.routeUuid.take(8),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rowStart in 0 until maxLayers step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (slot in rowStart until minOf(rowStart + 2, maxLayers)) {
                            val layer = ownBySlot[slot]
                            val selected = slot == selectedSlot
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("board_layer_slot_${slot + 1}")
                                    .clickable { onSelectSlot(slot) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) {
                                    OrangeAccent.copy(alpha = 0.16f)
                                } else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) OrangeAccent else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(16.dp).background(
                                            color = layer?.let { Color(it.color) }
                                                ?: MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape,
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.board_layer_number, slot + 1),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            layer?.climbName ?: stringResource(R.string.board_layer_empty),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                        if (layer != null) {
                                            Text(
                                                when (layer.status) {
                                                    BoardLayerStatus.PREVIEW -> stringResource(R.string.board_layer_preview)
                                                    BoardLayerStatus.SENDING -> stringResource(R.string.board_layer_sending)
                                                    BoardLayerStatus.CONFIRMED -> stringResource(R.string.board_layer_confirmed)
                                                    BoardLayerStatus.FAILED -> stringResource(R.string.board_layer_failed)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (layer.status) {
                                                    BoardLayerStatus.CONFIRMED -> SuccessGreen
                                                    BoardLayerStatus.FAILED -> ErrorRed
                                                    else -> OrangeAccent
                                                },
                                            )
                                        }
                                    }
                                    if (layer != null) {
                                        IconButton(
                                            onClick = { onSendSlot(slot) },
                                            enabled = connected && !state.ble.isSending &&
                                                (layer.confirmedRouteUuid != null ||
                                                    state.boardLayers.occupiedCount < maxLayers),
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("board_layer_send_${slot + 1}"),
                                        ) {
                                            Icon(
                                                Icons.Default.Lightbulb,
                                                contentDescription = stringResource(
                                                    R.string.board_layer_send_one,
                                                    slot + 1,
                                                ),
                                                tint = if (layer.status == BoardLayerStatus.CONFIRMED) {
                                                    SuccessGreen
                                                } else OrangeAccent,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRemove(slot) },
                                            // Dropping a preview is local.
                                            // Dropping a live layer is a
                                            // TURN_OFF_USER on the wall.
                                            enabled = !state.ble.isSending,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("board_layer_remove_${slot + 1}"),
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.board_layer_remove),
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedSlot == null) {
                Text(
                    stringResource(R.string.board_layer_select_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selectedSlot?.let { slot ->
                Text(
                    stringResource(R.string.board_layer_color),
                    style = MaterialTheme.typography.labelMedium,
                )
                BoardLayerColorPicker(
                    selectedColor = selectedColor,
                    unavailableColors = occupiedColors - setOfNotNull(selectedLayer?.color),
                    onSelectColor = onSelectColor,
                )
                OutlinedButton(
                    onClick = onAssignCurrent,
                    enabled = !state.ble.isSending && !selectedColorConflict,
                    modifier = Modifier.fillMaxWidth().testTag("board_layer_assign_current"),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (selectedLayer?.climbUuid == state.climb?.uuid) {
                                R.string.board_layer_update_current
                            } else if (selectedLayer != null) {
                                R.string.board_layer_replace_current
                            } else {
                                R.string.board_layer_add_current
                            },
                            slot + 1,
                        ),
                    )
                }
                if (sharedHoldCount > 0) {
                    Text(
                        stringResource(R.string.board_layer_preview_overlap_warning, sharedHoldCount),
                        color = WarningYellow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selectedColorConflict) {
                    Text(
                        stringResource(R.string.board_layer_error_color_taken),
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = onSendAll,
                enabled = connected && !state.ble.isSending &&
                    state.boardLayers.layers.isNotEmpty() && canSendAll && duplicateHoldCount == 0,
                modifier = Modifier.fillMaxWidth().testTag("board_layer_send_all"),
            ) {
                Icon(Icons.Default.Lightbulb, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.board_layer_send_all))
            }
            if (!canSendAll) {
                Text(
                    stringResource(R.string.board_layer_send_all_capacity),
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (duplicateHoldCount > 0) {
                Text(
                    stringResource(R.string.board_layer_send_all_overlap, duplicateHoldCount),
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Lightweight per-page content — no Scaffold, no TopAppBar, no banners, no BleVM. */
@Composable
private fun ClimbDetailPageContent(
    state: ClimbDetailState,
    isSharingEnabled: Boolean,
    /** A group is on this board, so its shared list owns the wall. */
    viewModel: BoardClimbDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    onNavigateToSetter: (pubkey: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val climbBugReportTitle = stringResource(R.string.error_bug_report_climb_title)
    val bleBugReportTitle = stringResource(R.string.error_bug_report_ble_title)
    var showDetails by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    LaunchedEffect(state.personalNoteDraft, showDetails, state.climb?.uuid) {
        if (showDetails && state.personalNoteDraft.trim() != state.personalNote) {
            delay(700)
            viewModel.savePersonalNote(state.personalNoteDraft)
        }
    }
    val closeDetails = {
        if (state.personalNoteDraft.trim() != state.personalNote) {
            viewModel.savePersonalNote(state.personalNoteDraft)
        }
        showDetails = false
    }
    state.climb?.takeIf { showDetails }?.let {
        ClimbDetailInfoSheet(
            state = state,
            isSharingEnabled = isSharingEnabled,
            onDismiss = closeDetails,
            onAngleSelected = {
                closeDetails()
                viewModel.onAngleSelected(it)
            },
            onEditAscent = {
                closeDetails()
                viewModel.editAscent(it)
            },
            onDeleteAscent = {
                closeDetails()
                viewModel.requestDeleteAscent(it.uuid)
            },
            noteDraft = state.personalNoteDraft,
            onNoteChanged = viewModel::updatePersonalNoteDraft,
            onRetryNote = { viewModel.savePersonalNote(state.personalNoteDraft) },
        )
    }
    // A board that holds four climbs at once needs four climbs' worth of
    // controls, and the screen's whole point is that the wall gets the space.
    // So the rack is a sheet, like the other two board surfaces on this screen.
    if (showLayers && state.climb?.brand?.supportsIndependentClimbLayers == true) {
        BoardLayerSheet(
            state = state,
            onDismiss = { showLayers = false },
            onSelectSlot = viewModel::selectBoardLayer,
            onSelectColor = viewModel::selectBoardLayerColor,
            onAssignCurrent = viewModel::assignCurrentToBoardLayer,
            onSendSlot = viewModel::sendBoardLayer,
            onSendAll = viewModel::sendAllBoardLayers,
            onRemove = viewModel::removeBoardLayer,
        )
    }
    when {
        state.isLoading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
        }
        state.logbookOnly != null -> {
            LogbookOnlyClimbContent(
                logbookOnly = state.logbookOnly!!,
                gradeScale = state.gradeScale,
                modifier = modifier
            )
        }
        state.error != null -> {
            Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                com.cruxcoach.android.ui.common.ErrorCard(
                    error = state.error ?: stringResource(R.string.board_detail_error),
                    onDismiss = { viewModel.clearError() },
                    onReportBug = {
                        onNavigateToBugReport(
                            climbBugReportTitle,
                            state.error ?: ""
                        )
                        viewModel.clearError()
                    }
                )
            }
        }
        state.climb != null -> {
            val climb = state.climb!!
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactClimbOverview(
                    state = state,
                    onShowDetails = { showDetails = true },
                    onAngleSelected = viewModel::onAngleSelected,
                    onNavigateToSetter = onNavigateToSetter,
                    isSharingEnabled = isSharingEnabled,
                )

                // Boards that hold several climbs at once get a legend for
                // what is on the wall right above the wall itself. It is the
                // rack in one line; the rack proper opens from it.
                if (climb.brand.supportsIndependentClimbLayers) {
                    BoardLayerStrip(
                        state = state,
                        onOpen = { showLayers = true },
                    )
                }

                // Board visualization (Climbdex-style) with countdown overlay.
                // FEAT-027: MoonBoard climbs render the climb's `frames` over
                // the real board image when one is bundled for the variant,
                // falling back to a procedural 11x18 grid otherwise; Kilter
                // climbs keep the photo-backed Aurora renderer.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (climb.brand == BoardBrand.MOONBOARD) {
                        MoonBoardVisualization(
                            frames = climb.frames,
                            assetState = rememberMoonBoardAsset(climb.layoutId),
                            variant = MoonBoardVariant.fromLayoutId(climb.layoutId),
                            modifier = Modifier
                                .testTag("boarddetail_visualization")
                        )
                    } else {
                        KilterBoardVisualization(
                            holds = state.holds,
                            placements = state.placements,
                            boardSize = state.boardSize,
                            boardImages = state.boardImages,
                            // FEAT-031: Aurora boards draw their own per-board
                            // colours; Kilter keeps the user's configured palette.
                            ledColors = if (climb.brand == BoardBrand.KILTER) state.ledColors
                                        else LedHoldColors.standardFor(climb.brand),
                            previewMode = state.playback.showPreview,
                            currentFrameHolds = if (state.playback.showPreview && state.playback.isRoute) {
                                state.playback.allFrames.getOrElse(state.playback.currentFrameIndex) { emptyList() }
                            } else null,
                            projectionLayers = if (climb.brand == BoardBrand.QUANTUM) {
                                state.boardLayers.layers
                            } else emptyList(),
                            modifier = Modifier
                                .testTag("boarddetail_visualization")
                        )
                    }
                    // Countdown overlay
                    if (state.playback.countdownSeconds > 0) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${state.playback.countdownSeconds}",
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent
                            )
                        }
                    }
                }

                // Route playback controls
                if (state.playback.isRoute) {
                    RoutePlaybackControls(state = state, viewModel = viewModel)
                }

                // Compact BLE send status
                if (state.ble.isSending || state.ble.success || state.ble.error != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            state.ble.isSending -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = OrangeAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.board_detail_sending), style = MaterialTheme.typography.bodySmall, color = OrangeAccent)
                            }
                            state.ble.success -> {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(14.dp), tint = SuccessGreen)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.board_detail_sent), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = SuccessGreen)
                                // Non-blocking warning: send went through, but some
                                // holds had no LED on the configured board size.
                                state.ble.warning?.let { warningRes ->
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        stringResource(warningRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WarningYellow
                                    )
                                }
                            }
                            state.ble.error != null -> {
                                val bleErrorText = stringResource(state.ble.error)
                                Text(
                                    bleErrorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ErrorRed,
                                    modifier = Modifier.clickable {
                                        onNavigateToBugReport(
                                            bleBugReportTitle,
                                            bleErrorText
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

/**
 * The whole bottom of a climb page, in the order somebody needs it.
 *
 * Two rows at most: the group's list first when there is one, then the three
 * things this climber does with the climb in front of them. They are stacked
 * rather than merged because they answer different questions — "put this in
 * front of everybody" and "that burn just happened" — and a row that
 * sometimes means one and sometimes the other is a row nobody can use fast.
 */
@Composable
private fun BoardDetailBottomActions(
    state: ClimbDetailState,
    decision: BoardDeliveryDecision,
    hasDirectPayload: Boolean,
    /** A board group, or a shared session mid-join, owns delivery. */
    boardOwnedByOthers: Boolean,
    onAttempt: () -> Unit,
    onLight: () -> Unit,
    onConnectBoard: () -> Unit,
    onSend: () -> Unit,
) {
    val climb = state.climb ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoardDetailActionDock(
                loggingEnabled = !state.isLoading && !state.isQuickLogging,
                lamp = BoardDeliveryPolicy.lampMode(
                    decision = decision,
                    hasDirectPayload = hasDirectPayload,
                    boardConnected = state.ble.connectionState == ConnectionState.CONNECTED ||
                        state.ble.connectionState == ConnectionState.SENDING,
                    boardOwnedByOthers = boardOwnedByOthers,
                    countdownRunning = state.playback.countdownSeconds > 0,
                ),
                lightEnabled = !state.isLoading,
                lightInProgress = state.ble.isSending,
                onAttempt = onAttempt,
                onLight = onLight,
                onConnectBoard = onConnectBoard,
                onSend = onSend,
            )
        }
    }
}

/**
 * One dock button's contents: the icon, and under it the word for what it does.
 *
 * The icons alone were a red cross, a lamp and a green tick, which is legible
 * once somebody has been told and ambiguous until then — a cross reads as
 * "cancel" at least as readily as "attempt". The label is the cheapest way to
 * say it, and it keeps all three buttons the same height whichever state the
 * middle one is in.
 */
@Composable
private fun DockButtonContent(icon: ImageVector, contentDescription: String, label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(27.dp))
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun BoardDetailActionDock(
    loggingEnabled: Boolean,
    lamp: BoardDetailLampMode,
    lightEnabled: Boolean,
    lightInProgress: Boolean,
    onAttempt: () -> Unit,
    onLight: () -> Unit,
    onConnectBoard: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onAttempt,
            enabled = loggingEnabled,
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .testTag("boarddetail_quick_attempt"),
            shape = RoundedCornerShape(18.dp),
            color = ErrorRed.copy(alpha = 0.13f),
            contentColor = ErrorRed,
            border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.42f)),
        ) {
            DockButtonContent(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.board_ascent_attempt),
                label = stringResource(R.string.board_dock_attempt),
            )
        }
        when (lamp) {
            BoardDetailLampMode.HIDDEN -> Unit
            BoardDetailLampMode.CONNECT -> Surface(
                onClick = onConnectBoard,
                enabled = lightEnabled,
                modifier = Modifier
                    .weight(1.12f)
                    .height(64.dp)
                    .testTag("boarddetail_connect_board_button"),
                shape = RoundedCornerShape(18.dp),
                // Same treatment as the lamp: this is the primary action of
                // the dock whenever there is no board yet, and a grey control
                // between a red and an orange one read as the disabled one.
                // The icon carries the difference.
                color = OrangeAccent,
                contentColor = DarkBackground,
                shadowElevation = 4.dp,
            ) {
                DockButtonContent(
                    icon = Icons.Default.Bluetooth,
                    contentDescription = stringResource(R.string.cd_board_connect),
                    label = stringResource(R.string.board_dock_connect),
                )
            }
            BoardDetailLampMode.LIGHT,
            BoardDetailLampMode.SHARED_QUEUE -> {
                val sharedQueue = lamp == BoardDetailLampMode.SHARED_QUEUE
                Surface(
                    onClick = onLight,
                    enabled = lightEnabled && !lightInProgress,
                    modifier = Modifier
                        .weight(1.12f)
                        .height(64.dp)
                        .testTag(
                            if (sharedQueue) "boarddetail_add_to_shared_queue_button"
                            else "boarddetail_light_climb_button",
                        ),
                    shape = RoundedCornerShape(18.dp),
                    color = OrangeAccent,
                    contentColor = DarkBackground,
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (lightInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(25.dp),
                                strokeWidth = 2.5.dp,
                                color = DarkBackground,
                            )
                        } else {
                            DockButtonContent(
                                icon = if (sharedQueue) Icons.AutoMirrored.Filled.PlaylistAdd
                                else Icons.Default.Lightbulb,
                                contentDescription = stringResource(
                                    if (sharedQueue) R.string.cd_add_climb_to_shared_queue
                                    else R.string.cd_light_climb_on_board,
                                ),
                                label = stringResource(
                                    if (sharedQueue) R.string.board_dock_queue
                                    else R.string.board_dock_light,
                                ),
                            )
                        }
                    }
                }
            }
        }
        Surface(
            onClick = onSend,
            enabled = loggingEnabled,
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .testTag("boarddetail_quick_send"),
            shape = RoundedCornerShape(18.dp),
            color = SuccessGreen.copy(alpha = 0.16f),
            contentColor = SuccessGreen,
            border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.46f)),
        ) {
            DockButtonContent(
                icon = Icons.Default.Check,
                contentDescription = stringResource(R.string.board_ascent_send),
                label = stringResource(R.string.board_dock_top),
            )
        }
    }
}

@Composable
private fun CompactClimbOverview(
    state: ClimbDetailState,
    onShowDetails: () -> Unit,
    onAngleSelected: (Int) -> Unit,
    onNavigateToSetter: (String) -> Unit,
    isSharingEnabled: Boolean,
) {
    val climb = state.climb ?: return
    val setter = state.setterProfile?.displayName ?: climb.setterUsername
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("boarddetail_compact_overview")
            .clickable(onClick = onShowDetails),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = climb.name,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    setter?.takeIf { it.isNotBlank() }?.let {
                        val pubkey = climb.createdByPubkey?.takeIf(String::isNotBlank)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .widthIn(max = 112.dp)
                                .then(
                                    if (climb.origin == "cruxcoach" && pubkey != null) {
                                        Modifier.clickable { onNavigateToSetter(pubkey) }
                                    }
                                    else Modifier,
                                ),
                        )
                    }
                }
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.board_detail_more_information),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                climb.difficultyAverage?.let { difficulty ->
                    val french = GradeDisplayHelper.formatDifficulty(difficulty, GradeScale.FRENCH)
                    val vScale = GradeDisplayHelper.formatDifficulty(difficulty, GradeScale.V_SCALE)
                    Surface(
                        color = zoneColorForDifficulty(difficulty, state.zones),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "$french / $vScale",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkBackground,
                        )
                    }
                }
                CompactAngleMenu(
                    currentAngle = state.angle,
                    availableAngles = state.availableAngles,
                    onAngleSelected = onAngleSelected,
                )
                Text(
                    text = if (state.playback.isRoute) "${state.playback.totalFrames}F" else "${climb.moveCount}M",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${climb.qualityAverage?.let { "%.1f".format(it) } ?: "–"}★",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (climb.benchmarkDifficulty > 0.0) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(R.string.board_detail_benchmark),
                        tint = OrangeAccent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (climb.isMatchStateKnown) {
                    MatchIcon(
                        crossed = climb.isNomatch,
                        tint = if (climb.isNomatch) ErrorRed else SuccessGreen,
                        size = 15,
                    )
                }
                // "This climb is going out over nearby-sharing right now" is
                // ambient state somebody needs to be able to see without
                // opening anything, so it sits with the other status icons.
                val advertisingLive = isSharingEnabled && state.nearby.isAdvertising &&
                    state.ble.connectionState.let {
                        it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
                    }
                if (advertisingLive) {
                    Icon(
                        Icons.Default.CellTower,
                        contentDescription = stringResource(R.string.board_detail_climb_shared),
                        tint = OrangeAccent,
                        modifier = Modifier
                            .size(15.dp)
                            .testTag("boarddetail_climb_shared_icon"),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = stringResource(R.string.board_sends),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "${climb.ascensionistCount ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactAngleMenu(
    currentAngle: Int,
    availableAngles: List<AngleOption>,
    onAngleSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable(enabled = availableAngles.size > 1) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$currentAngle°",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent,
            )
            if (availableAngles.size > 1) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.cd_change_angle),
                    tint = OrangeAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableAngles.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.angle}°") },
                    onClick = {
                        expanded = false
                        onAngleSelected(option.angle)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimbDetailInfoSheet(
    state: ClimbDetailState,
    isSharingEnabled: Boolean,
    onDismiss: () -> Unit,
    onAngleSelected: (Int) -> Unit,
    onEditAscent: (com.cruxcoach.data.repository.AscentWithClimb) -> Unit,
    onDeleteAscent: (com.cruxcoach.data.repository.AscentWithClimb) -> Unit,
    noteDraft: String,
    onNoteChanged: (String) -> Unit,
    onRetryNote: () -> Unit,
) {
    val climb = state.climb ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(climb.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    R.string.board_detail_by_setter,
                    state.setterProfile?.displayName ?: climb.setterUsername.orEmpty(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                AngleDropdownStatItem(
                    currentAngle = state.angle,
                    availableAngles = state.availableAngles,
                    gradeScale = state.gradeScale,
                    zones = state.zones,
                    onAngleSelected = onAngleSelected,
                )
                StatItem(
                    stringResource(if (state.playback.isRoute) R.string.board_detail_frames else R.string.board_moves),
                    "${if (state.playback.isRoute) state.playback.totalFrames else climb.moveCount}",
                )
                StatItem(
                    stringResource(R.string.board_quality),
                    climb.qualityAverage?.let { "%.1f".format(it) } ?: "–",
                )
                StatItem(stringResource(R.string.board_sends), "${climb.ascensionistCount ?: 0}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (climb.isMatchStateKnown) MatchBadge(climb.isNomatch)
                climb.method?.let { MethodBadge(it) }
                if (climb.benchmarkDifficulty > 0.0) BenchmarkBadge()
            }
            climb.faUsername?.let { username ->
                val date = climb.faAt?.let(::formatAscentDate)
                Text(
                    "FA: $username" + if (date != null) " ($date)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            climb.description.takeIf(String::isNotBlank)?.let {
                HorizontalDivider()
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.userAscents.isNotEmpty()) {
                HorizontalDivider()
                UserAscentHistory(
                    ascents = state.userAscents,
                    gradeScale = state.gradeScale,
                    onEdit = onEditAscent,
                    onDelete = onDeleteAscent,
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.board_detail_personal_note),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = noteDraft,
                onValueChange = onNoteChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("boarddetail_note_field"),
                placeholder = { Text(stringResource(R.string.board_detail_personal_note_hint)) },
                minLines = 2,
                maxLines = 5,
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${noteDraft.length}/1000")
                        val noteStatus = when {
                            state.personalNoteSaveStatus == PersonalNoteSaveStatus.FAILED ->
                                R.string.board_detail_note_not_saved
                            noteDraft.trim() != state.personalNote ||
                                state.personalNoteSaveStatus == PersonalNoteSaveStatus.SAVING ->
                                R.string.board_detail_note_saving
                            else -> R.string.board_detail_note_saved
                        }
                        Text(
                            stringResource(noteStatus),
                            color = if (state.personalNoteSaveStatus == PersonalNoteSaveStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
            if (state.personalNoteSaveStatus == PersonalNoteSaveStatus.FAILED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onRetryNote,
                        modifier = Modifier.testTag("boarddetail_note_retry"),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            // Nearby-sharing diagnostics. Only while a board is connected,
            // which is the only situation they describe, and down here rather
            // than on the climb page, which now belongs to the climb.
            if (state.ble.connectionState.let {
                    it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
                }
            ) {
                HorizontalDivider()
                Text(
                    buildString {
                        append("S:")
                        append(if (isSharingEnabled) "ON" else "OFF")
                        append(" A:")
                        append(if (state.nearby.isAdvertising) "ON" else "OFF")
                        if (state.nearby.debugInfo.isNotEmpty()) {
                            append(" | ")
                            append(state.nearby.debugInfo)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.testTag("boarddetail_sharing_debug"),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AngleDropdownStatItem(
    currentAngle: Int,
    availableAngles: List<AngleOption>,
    gradeScale: GradeScale,
    zones: IntensityZones?,
    onAngleSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleAngles = availableAngles.size > 1

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .then(
                    if (hasMultipleAngles) Modifier.clickable { expanded = true }
                    else Modifier
                )
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${currentAngle}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (hasMultipleAngles) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.cd_change_angle),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.board_angle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp)
        ) {
            availableAngles.forEach { option ->
                val isSelected = option.angle == currentAngle
                val gradeText = option.difficultyAverage?.let { diff ->
                    GradeDisplayHelper.formatDifficulty(diff, gradeScale)
                } ?: "–"
                val zoneColor = option.difficultyAverage?.let { diff ->
                    zoneColorForDifficulty(diff, zones)
                }

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${option.angle}°",
                                modifier = Modifier.width(40.dp),
                                fontWeight = if (isSelected) FontWeight.Bold
                                    else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                            )
                            if (zoneColor != null) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(zoneColor, CircleShape)
                                )
                            }
                            Text(
                                text = gradeText,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (isSelected) FontWeight.Bold
                                    else FontWeight.Normal
                            )
                            // The angle the setter created the climb at, kept
                            // visible as info while every board angle stays
                            // pickable.
                            if (option.isSetterAngle) {
                                Text(
                                    text = stringResource(R.string.board_angle_setter_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "${option.ascensionistCount ?: 0}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onAngleSelected(option.angle)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * MoonBoard problem method. NULL — the overwhelming majority — means "feet
 * follow hands" and gets no badge: a marker on the normal case is noise.
 */
@Composable
private fun MethodBadge(method: String) {
    val label = when (method) {
        "method_footless" -> R.string.board_detail_method_footless
        "method_footless_kickboard" -> R.string.board_detail_method_footless_kickboard
        "method_no_kickboard" -> R.string.board_detail_method_no_kickboard
        else -> return  // unknown token from a newer catalogue: say nothing
    }
    Surface(
        color = OrangeAccent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = OrangeAccent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Match / No-match badge with Handshake icon (crossed out when no-match). */
@Composable
private fun MatchBadge(isNomatch: Boolean) {
    val color = if (isNomatch) ErrorRed else SuccessGreen
    val label = stringResource(if (isNomatch) R.string.board_detail_no_matching else R.string.board_detail_matching)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MatchIcon(crossed = isNomatch, tint = color, size = 16)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun BenchmarkBadge() {
    Surface(
        color = OrangeAccent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Verified,
                contentDescription = stringResource(R.string.board_detail_benchmark),
                tint = OrangeAccent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                stringResource(R.string.board_detail_benchmark),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent
            )
        }
    }
}

/** Raised-hand icon with optional diagonal strike-through for no-match. */
@Composable
private fun MatchIcon(crossed: Boolean, tint: Color, size: Int = 16) {
    Box(modifier = Modifier.size(size.dp)) {
        Icon(
            Icons.Default.PanTool,
            contentDescription = stringResource(
                if (crossed) R.string.board_detail_no_matching
                else R.string.board_detail_matching,
            ),
            tint = tint,
            modifier = Modifier.size(size.dp)
        )
        if (crossed) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = tint,
                    start = Offset(0f, this.size.height),
                    end = Offset(this.size.width, 0f),
                    strokeWidth = stroke
                )
            }
        }
    }
}
