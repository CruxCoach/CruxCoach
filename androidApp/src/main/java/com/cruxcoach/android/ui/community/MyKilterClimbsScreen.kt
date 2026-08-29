package com.cruxcoach.android.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.BoardBrandBadge
import com.cruxcoach.android.ui.board.OwnPublishFeedback
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.domain.board.KilterGradeMapper

/**
 * "Meine Climbs" — the hub for every climb the user authored, grouped into
 * clearly-labelled sections: CruxCoach drafts, CruxCoach-published climbs,
 * and Kilter imports not yet on CruxCoach (with a native-publish "claim"
 * action). Spans all boards; each card carries its board badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyKilterClimbsScreen(
    onNavigateBack: () -> Unit,
    onClimbClick: (uuid: String, angle: Int) -> Unit,
    viewModel: MyKilterClimbsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-query when returning from the climb detail / editor so a publish,
    // edit, delete or un-claim done there reflects instantly instead of only
    // after a full re-open (the ViewModel is retained across the back-nav).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.feedback) {
        val feedback = state.feedback ?: return@LaunchedEffect
        val msg = when (feedback) {
            OwnPublishFeedback.Published -> resources.getString(R.string.own_climb_publish_done)
            OwnPublishFeedback.NoNostrIdentity -> resources.getString(R.string.own_climb_publish_no_nostr)
            OwnPublishFeedback.NotAuthor -> resources.getString(R.string.own_climb_publish_not_author)
            OwnPublishFeedback.AlreadyPublished -> resources.getString(R.string.own_climb_publish_already)
            OwnPublishFeedback.Failed -> resources.getString(R.string.climb_creator_publish_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_climbs_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = OrangeAccent) }
            }
            state.climbs.isEmpty() && !state.hasKilterConnection && !state.hasNostrIdentity -> {
                EmptyHint(
                    padding = padding,
                    title = stringResource(R.string.my_climbs_no_kilter_title),
                    message = stringResource(R.string.my_climbs_no_kilter_message),
                )
            }
            state.climbs.isEmpty() -> {
                EmptyHint(
                    padding = padding,
                    title = stringResource(R.string.my_climbs_empty_title),
                    message = stringResource(R.string.my_climbs_empty_message),
                )
            }
            else -> {
                // Drafts (incl. an in-flight retry) → Published → Kilter
                // imports awaiting a claim. Each section renders only when
                // it has rows, so the screen never shows an empty header.
                val drafts = state.climbs.filter {
                    it.status == MyClimbStatus.DRAFT || it.status == MyClimbStatus.PUBLISH_PENDING
                }
                val published = state.climbs.filter { it.status == MyClimbStatus.PUBLISHED }
                val unclaimed = state.climbs.filter { it.status == MyClimbStatus.KILTER_UNCLAIMED }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    climbSection(
                        title = R.string.my_climbs_section_drafts,
                        climbs = drafts,
                        state = state,
                        onClimbClick = onClimbClick,
                        onClaim = viewModel::claim,
                    )
                    climbSection(
                        title = R.string.my_climbs_section_published,
                        climbs = published,
                        state = state,
                        onClimbClick = onClimbClick,
                        onClaim = viewModel::claim,
                    )
                    climbSection(
                        title = R.string.my_climbs_section_kilter_unclaimed,
                        climbs = unclaimed,
                        state = state,
                        onClimbClick = onClimbClick,
                        onClaim = viewModel::claim,
                    )
                }
            }
        }
    }

    // Grade picker for claiming an ungraded Kilter climb — difficulty is a
    // must-have, so an ungraded claim never publishes with a silent default.
    state.gradeDialogUuid?.let { uuid ->
        ClaimGradeDialog(
            onConfirm = { gradeId -> viewModel.confirmGrade(uuid, gradeId) },
            onDismiss = viewModel::dismissGradeDialog,
        )
    }
}

/** A titled group of climb cards; emits nothing when [climbs] is empty. */
private fun androidx.compose.foundation.lazy.LazyListScope.climbSection(
    title: Int,
    climbs: List<MyClimbItem>,
    state: MyKilterClimbsState,
    onClimbClick: (uuid: String, angle: Int) -> Unit,
    onClaim: (MyClimbItem) -> Unit,
) {
    if (climbs.isEmpty()) return
    item(key = "header_$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
    items(climbs, key = { it.uuid }) { climb ->
        MyClimbCard(
            climb = climb,
            publishEnabled = state.publishingUuid == null,
            isPublishing = state.publishingUuid == climb.uuid,
            onClick = { onClimbClick(climb.uuid, climb.angle) },
            onClaim = { onClaim(climb) },
        )
    }
}

@Composable
private fun EmptyHint(padding: PaddingValues, title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MyClimbCard(
    climb: MyClimbItem,
    publishEnabled: Boolean,
    isPublishing: Boolean,
    onClick: () -> Unit,
    onClaim: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("my_climb_card_${climb.uuid}"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb.name.ifBlank { stringResource(R.string.my_climbs_unnamed) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoardBrandBadge(climb.boardBrand)
                    if (climb.status == MyClimbStatus.PUBLISH_PENDING) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.my_climbs_publish_retrying),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (climb.status) {
                MyClimbStatus.PUBLISHED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.my_climbs_published_badge),
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.my_climbs_published_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = SuccessGreen,
                    )
                }
                MyClimbStatus.DRAFT -> {
                    Text(
                        stringResource(R.string.my_climbs_draft_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MyClimbStatus.PUBLISH_PENDING -> {
                    // Status is conveyed by the inline "retrying" note next to
                    // the board badge; no trailing affordance.
                }
                MyClimbStatus.KILTER_UNCLAIMED -> {
                    TextButton(
                        onClick = onClaim,
                        enabled = publishEnabled && !isPublishing,
                        modifier = Modifier.testTag("my_climb_publish_${climb.uuid}"),
                    ) {
                        if (isPublishing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = OrangeAccent,
                            )
                        } else {
                            Text(
                                stringResource(R.string.own_climb_publish_action_short),
                                color = OrangeAccent,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Difficulty picker shown when claiming an ungraded Kilter climb. Mirrors the
 * editor's grade slider (Kilter difficulty ids 10–33 → V-scale + font) so the
 * claim publishes a real grade rather than a silent default.
 */
@Composable
private fun ClaimGradeDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var gradeId by remember { mutableIntStateOf(KilterGradeMapper.DEFAULT_SETTER_GRADE_ID) }
    val vGrade = KilterGradeMapper.difficultyToVScale(gradeId)
    val font = KilterGradeMapper.difficultyToFont(gradeId.toDouble())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.my_climbs_grade_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.my_climbs_grade_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    stringResource(R.string.climb_creator_grade_label, vGrade, font),
                    style = MaterialTheme.typography.titleMedium,
                    color = OrangeAccent,
                )
                Slider(
                    value = gradeId.toFloat(),
                    onValueChange = { gradeId = it.toInt() },
                    valueRange = 10f..33f,
                    steps = 22,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(gradeId) }) {
                Text(stringResource(R.string.own_climb_publish_action_short), color = OrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
