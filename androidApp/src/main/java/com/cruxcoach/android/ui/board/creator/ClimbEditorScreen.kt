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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.KilterBoardVisualization
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
    viewModel: ClimbEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.publishedUuid) {
        state.publishedUuid?.let { onPublished(it) }
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
                    IconButton(onClick = viewModel::openDraftsSheet) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.climb_creator_drafts_open),
                        )
                    }
                    IconButton(onClick = viewModel::toggleHeatmap) {
                        Icon(
                            if (state.heatmapEnabled) Icons.Filled.Whatshot else Icons.Outlined.Whatshot,
                            contentDescription = stringResource(R.string.climb_creator_heatmap_toggle),
                            // Bright tint when active, muted when off — gives
                            // the outline-only icon a clearly "disabled" read
                            // and avoids the look-alike between the two states.
                            tint = if (state.heatmapEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.climb_creator_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.climb_creator_redo))
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
            // Autosave restore offer — appears once on editor open if a
            // previous unsaved session is still in DataStore.
            state.autosaveOffer?.let { offer ->
                AutosaveRestoreBanner(
                    savedAtEpochMs = offer.savedAtEpochMs,
                    onAccept = viewModel::acceptAutosave,
                    onDismiss = viewModel::dismissAutosave,
                )
            }

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
            ValidationStatus(state.validationIssues)

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.saveAsDraft(onPublished) },
                    enabled = !state.isPublishing,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.climb_creator_save_draft)) }
                Button(
                    onClick = { viewModel.publish(sizeLabel = "12x12") },
                    enabled = !state.isPublishing,
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
                    TextButton(onClick = { viewModel.confirmPublishWithDuplicate("12x12") }) {
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
@Composable
private fun AutosaveRestoreBanner(
    savedAtEpochMs: Long,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.climb_creator_autosave_offer_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                val rel = remember(savedAtEpochMs) {
                    val seconds = (System.currentTimeMillis() - savedAtEpochMs) / 1000L
                    when {
                        seconds < 60 -> "gerade eben"
                        seconds < 3600 -> "vor ${seconds / 60} min"
                        seconds < 86400 -> "vor ${seconds / 3600} h"
                        else -> "vor ${seconds / 86400} t"
                    }
                }
                Text(
                    stringResource(R.string.climb_creator_autosave_offer_message, rel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.climb_creator_autosave_offer_discard))
            }
            Button(onClick = onAccept) {
                Text(stringResource(R.string.climb_creator_autosave_offer_restore))
            }
        }
    }
}

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
                is ClimbValidation.Issue.TooManyStarts -> stringResource(R.string.climb_creator_issue_too_many_starts, issue.count)
                is ClimbValidation.Issue.TooManyFinishes -> stringResource(R.string.climb_creator_issue_too_many_finishes, issue.count)
                ClimbValidation.Issue.NameMissing -> stringResource(R.string.climb_creator_issue_name_missing)
                is ClimbValidation.Issue.NameTooLong -> stringResource(R.string.climb_creator_issue_name_too_long, ClimbValidation.NAME_MAX_LENGTH)
                is ClimbValidation.Issue.DescriptionTooLong -> stringResource(R.string.climb_creator_issue_description_too_long, ClimbValidation.DESCRIPTION_MAX_LENGTH)
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
    val effective = gradeId ?: 20
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
