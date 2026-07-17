package com.cruxcoach.android.ui.board

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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.BoardProjectionPolicy
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.SessionRole
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // collectAsState (NOT ...WithLifecycle): the detail's nav entry can stay
    // in a non-STARTED state behind the editor and not re-deliver on return,
    // leaving the climb stale after an edit even though the VM reloaded it.
    // The setter list (which refreshes correctly) uses plain collectAsState too.
    val state by viewModel.state.collectAsState()
    val isRestTimerRunning by viewModel.isRestTimerRunning.collectAsStateWithLifecycle()
    val isSharingEnabled by viewModel.isSharingEnabled.collectAsStateWithLifecycle()
    val pageCache by viewModel.pageCache.collectAsStateWithLifecycle()
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
            autoStartScan = true,
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
    if (bleConnState.showDisconnectRequestDialog) {
        AlertDialog(
            onDismissRequest = { bleConnViewModel.dismissDisconnectRequest() },
            title = { Text(stringResource(R.string.board_ble_disconnect_request_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_ble_disconnect_request_message)) },
            confirmButton = {
                Button(
                    onClick = { bleConnViewModel.acceptRemoteDisconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.board_ble_disconnect_request_confirm), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { bleConnViewModel.dismissDisconnectRequest() }) {
                    Text(stringResource(R.string.board_ble_disconnect_request_deny))
                }
            }
        )
    }

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
                context.getString(template, feedback.accepted, feedback.attempted)
            }
            is CommunityDeleteFeedback.LocalTombstoneFailed ->
                context.getString(
                    R.string.community_climb_delete_local_failed,
                    feedback.accepted, feedback.attempted,
                )
            CommunityDeleteFeedback.NotOwner -> context.getString(R.string.community_climb_delete_not_owner)
            // Defensive: NotOurClimb / NotFound shouldn't reach the user
            // because the menu item is gated on origin=cruxcoach + owner.
            // If they ever do, fall back to the generic failure message.
            CommunityDeleteFeedback.NotOurClimb,
            CommunityDeleteFeedback.NotFound,
            CommunityDeleteFeedback.Failed -> context.getString(R.string.community_climb_delete_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeCommunityDeleteFeedback()
    }

    // Surface own-Kilter-climb publish outcomes (same snackbar pattern as
    // the editor's publish result handling).
    LaunchedEffect(state.ownPublishFeedback) {
        val feedback = state.ownPublishFeedback ?: return@LaunchedEffect
        val msg = when (feedback) {
            OwnPublishFeedback.Published -> context.getString(R.string.own_climb_publish_done)
            OwnPublishFeedback.NoNostrIdentity -> context.getString(R.string.own_climb_publish_no_nostr)
            OwnPublishFeedback.NotAuthor -> context.getString(R.string.own_climb_publish_not_author)
            OwnPublishFeedback.AlreadyPublished -> context.getString(R.string.own_climb_publish_already)
            OwnPublishFeedback.Failed -> context.getString(R.string.climb_creator_publish_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeOwnPublishFeedback()
    }

    // Single Scaffold — shared across all pager pages
    val bleConnected = state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // Five primary actions stay direct: Favorite, Add-to-
                        // list, Rest timer, BLE, Log-ascent (the orange
                        // Check). Creator-side actions (Fork, Edit, Delete)
                        // live in a single ⋮ overflow at the end so the
                        // action row stops growing past six items + back-
                        // arrow on narrow phones.
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
                        IconButton(
                            onClick = { viewModel.showAddToListDialog() },
                            modifier = Modifier.testTag("boarddetail_add_to_list_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.cd_add_to_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        IconButton(
                            onClick = {
                                // Always open the sheet — it handles permission +
                                // BT-disabled flows and auto-connects to a single
                                // board (the existing CONNECTED-collector auto-
                                // fires the send). The idle-disconnect timer
                                // (Settings → BLE) tears the connection down
                                // afterwards, replacing the old Quick-Send macro.
                                showBleSheet = true
                            },
                            modifier = Modifier.testTag("boarddetail_ble_connect_button")
                        ) {
                            Icon(
                                if (bleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = stringResource(if (bleConnected) R.string.cd_board_connected else R.string.cd_board_connect),
                                tint = if (bleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { viewModel.showAscentDialog() },
                            modifier = Modifier.testTag("boarddetail_log_button")
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_log_ascent),
                                tint = OrangeAccent
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
                    pageCache[pageUuid] ?: ClimbDetailState(isLoading = true)
                }

                ClimbDetailPageContent(
                    state = pageState,
                    isSharingEnabled = isSharingEnabled,
                    sessionRole = detailQueueState.role,
                    sessionConnecting = detailQueueState.isConnecting,
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
                sessionRole = detailQueueState.role,
                sessionConnecting = detailQueueState.isConnecting,
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onNavigateToBugReport = onNavigateToBugReport,
                onNavigateToSetter = onNavigateToSetter,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/** Lightweight per-page content — no Scaffold, no TopAppBar, no banners, no BleVM. */
@Composable
private fun ClimbDetailPageContent(
    state: ClimbDetailState,
    isSharingEnabled: Boolean,
    sessionRole: SessionRole,
    sessionConnecting: Boolean,
    viewModel: BoardClimbDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    onNavigateToSetter: (pubkey: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                            context.getString(R.string.error_bug_report_climb_title),
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Grade + Stats card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(climb.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                // Setter line. Click behaviour:
                                //  - cruxcoach-origin + has pubkey → navigate
                                //    to SetterDetailScreen (Plan 8)
                                //  - else (Kilter-origin or pubkey missing) →
                                //    no click (use the search bar to filter
                                //    by setter name)
                                val setterDisplay = state.setterProfile?.displayName
                                    ?: climb.setterUsername
                                val setterPubkey = climb.createdByPubkey?.takeIf { it.isNotBlank() }
                                setterDisplay?.takeIf { it.isNotBlank() }?.let { setter ->
                                    val isClickable = climb.origin == "cruxcoach" && setterPubkey != null
                                    Text(
                                        stringResource(R.string.board_detail_by_setter, setter),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None
                                        ),
                                        color = if (isClickable) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = if (isClickable) {
                                            Modifier.clickable {
                                                onNavigateToSetter(setterPubkey!!)
                                            }
                                        } else Modifier
                                    )
                                }
                                // Provenance + Kilter-mirror badge — only shown for
                                // CruxCoach-authored climbs that actually have a
                                // live Nostr publication (`nostr_event_id` is
                                // set iff at least one publish has reached at
                                // least one relay, either from this device via
                                // markClimbPublishedNostr, or via the live-sub
                                // upsert echoing back the user's own event).
                                // For drafts and failed-publish rows the
                                // previous "Nur CruxCoach-Community" copy was
                                // misleading: an Aurora-imported draft
                                // (origin='cruxcoach' + kilterStatus=NULL +
                                // sync_status='draft') is *not* on the
                                // CruxCoach community, just sitting locally,
                                // and showing the same chip as a genuinely
                                // community-published climb conflated the two.
                                // sync_status alone wasn't a reliable
                                // discriminator — a successful prior publish
                                // can drift to 'failed' on a later attempt
                                // and still have a live event on relays;
                                // nostr_event_id is the deterministic signal.
                                val hasLivePublication = !climb.nostrEventId.isNullOrBlank()
                                if (climb.origin == "cruxcoach" && hasLivePublication) {
                                    Spacer(Modifier.size(4.dp))
                                    // Three states: synced = both Nostr +
                                    // Kilter; diverged = local edit Kilter
                                    // refused (older version still on
                                    // Kilter); else (NULL/pending/failed)
                                    // = community-only.
                                    val badgeText = when (climb.kilterStatus) {
                                        "synced" -> stringResource(R.string.climb_detail_badge_on_kilter)
                                        "diverged" -> stringResource(R.string.climb_detail_badge_kilter_diverged)
                                        else -> stringResource(R.string.climb_detail_badge_cruxcoach_only)
                                    }
                                    val badgeColor = when (climb.kilterStatus) {
                                        "synced" -> OrangeAccent
                                        "diverged" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = badgeColor.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            badgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                } else if (climb.origin == "boardsesh") {
                                    // BoardSesh-imported climb: attribute the
                                    // source. No Kilter/Nostr state applies
                                    // (it was never published to either), so a
                                    // single static provenance badge.
                                    Spacer(Modifier.size(4.dp))
                                    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = badgeColor.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            stringResource(R.string.climb_detail_badge_boardsesh),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                climb.difficultyAverage?.let { diff ->
                                    val fbGrade = GradeDisplayHelper.formatDifficulty(diff, GradeScale.FRENCH)
                                    val vGrade = GradeDisplayHelper.formatDifficulty(diff, GradeScale.V_SCALE)
                                    Surface(
                                        color = zoneColorForDifficulty(diff, state.zones),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            "$fbGrade / $vGrade",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = DarkBackground
                                        )
                                    }
                                }
                                MatchBadge(isNomatch = climb.isNomatch)
                                if (climb.benchmarkDifficulty > 0.0) {
                                    BenchmarkBadge()
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AngleDropdownStatItem(
                                currentAngle = state.angle,
                                availableAngles = state.availableAngles,
                                gradeScale = state.gradeScale,
                                zones = state.zones,
                                onAngleSelected = viewModel::onAngleSelected
                            )
                            if (state.playback.isRoute) {
                                StatItem(stringResource(R.string.board_detail_frames), "${state.playback.totalFrames}")
                            } else {
                                StatItem(stringResource(R.string.board_moves), "${climb.moveCount}")
                            }
                            StatItem(stringResource(R.string.board_quality), climb.qualityAverage?.let { "%.1f".format(it) } ?: "--")
                            StatItem(stringResource(R.string.board_sends), "${climb.ascensionistCount ?: 0}")
                        }

                        // First ascent info
                        if (climb.faUsername != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val faDate = climb.faAt?.let { formatAscentDate(it) }
                            Text(
                                "FA: ${climb.faUsername}" + if (faDate != null) " ($faDate)" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Description
                        if (climb.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                climb.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Board visualization (Climbdex-style) with countdown overlay.
                // FEAT-027: MoonBoard climbs render the climb's `frames` over
                // the real board image when one is bundled for the variant,
                // falling back to a procedural 11x18 grid otherwise; Kilter
                // climbs keep the photo-backed Aurora renderer.
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (climb.brand == BoardBrand.MOONBOARD) {
                        MoonBoardVisualization(
                            frames = climb.frames,
                            assetState = rememberMoonBoardAsset(climb.layoutId),
                            variant = MoonBoardVariant.fromLayoutId(climb.layoutId),
                            modifier = Modifier
                                .fillMaxWidth()
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
                            modifier = Modifier
                                .fillMaxWidth()
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
                    val boardConnected = state.ble.connectionState == ConnectionState.CONNECTED ||
                        state.ble.connectionState == ConnectionState.SENDING
                    val deliveryDecision = BoardDeliveryPolicy.resolve(
                        sendMode = state.boardSendMode,
                        sessionRole = sessionRole,
                        sessionConnecting = sessionConnecting,
                        boardConnected = boardConnected,
                        hasDirectPayload = BoardProjectionPolicy.hasSendablePayload(
                            brand = climb.brand,
                            holdCount = state.holds.size,
                            frames = climb.frames,
                        ),
                        connectedViaRelay = state.ble.connectedViaRelay,
                        hostedRelayClientCount = state.ble.hostedRelayClientCount,
                    )
                    if (deliveryDecision.showAction && state.playback.countdownSeconds == 0) {
                        FilledTonalIconButton(
                            onClick = viewModel::deliverClimb,
                            enabled = deliveryDecision.target == BoardDeliveryTarget.SHARED_QUEUE ||
                                !state.ble.isSending,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(40.dp)
                                .testTag(
                                    if (deliveryDecision.target == BoardDeliveryTarget.SHARED_QUEUE) {
                                        "boarddetail_add_to_shared_queue_button"
                                    } else {
                                        "boarddetail_light_climb_button"
                                    },
                                ),
                        ) {
                            if (deliveryDecision.target == BoardDeliveryTarget.DIRECT_BOARD &&
                                state.ble.isSending
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (
                                        deliveryDecision.target == BoardDeliveryTarget.SHARED_QUEUE
                                    ) {
                                        Icons.AutoMirrored.Filled.PlaylistAdd
                                    } else {
                                        Icons.Default.Lightbulb
                                    },
                                    contentDescription = stringResource(
                                        if (deliveryDecision.target == BoardDeliveryTarget.SHARED_QUEUE) {
                                            R.string.cd_add_climb_to_shared_queue
                                        } else {
                                            R.string.cd_light_climb_on_board
                                        },
                                    ),
                                    tint = OrangeAccent,
                                )
                            }
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
                                            context.getString(R.string.error_bug_report_ble_title),
                                            bleErrorText
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Climb sharing debug + indicator (only while connected)
                if (state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }) {
                    val sharingDebug = buildString {
                        append("S:")
                        append(if (isSharingEnabled) "ON" else "OFF")
                        append(" A:")
                        append(if (state.nearby.isAdvertising) "ON" else "OFF")
                        if (state.nearby.debugInfo.isNotEmpty()) {
                            append(" | ")
                            append(state.nearby.debugInfo)
                        }
                    }
                    Text(
                        sharingDebug,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (state.nearby.isAdvertising && state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CellTower,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = OrangeAccent
                        )
                        Text(
                            stringResource(R.string.board_detail_climb_shared),
                            style = MaterialTheme.typography.bodySmall,
                            color = OrangeAccent
                        )
                    }
                }

                // User ascent history
                if (state.userAscents.isNotEmpty()) {
                    UserAscentHistory(
                        ascents = state.userAscents,
                        gradeScale = state.gradeScale,
                        onEdit = { viewModel.editAscent(it) },
                        onDelete = { viewModel.requestDeleteAscent(it.uuid) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
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
            contentDescription = null,
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
