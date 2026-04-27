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
import androidx.compose.material.icons.filled.Check
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
            // Live board visualization
            val activeHolds = state.editor.selectedHolds.map { (pid, role) -> BoardHold(pid, role) }
            KilterBoardVisualization(
                holds = activeHolds,
                placements = state.placements,
                boardSize = state.boardSize,
                boardImages = state.boardImages,
                selectedHolds = state.editor.selectedHolds.keys,
                onHoldTapped = viewModel::toggleHold,
                onHoldMoved = viewModel::moveHold,
                solidHoldFill = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HoldCountStatus(state.editor.selectedHolds)
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

    state.duplicateOf?.let { dup ->
        AlertDialog(
            onDismissRequest = { /* dismiss via TextButton */ },
            title = { Text(stringResource(R.string.climb_creator_dup_title)) },
            text = { Text(stringResource(R.string.climb_creator_dup_message, dup.name)) },
            confirmButton = {
                TextButton(onClick = { /* user keeps editing */ }) {
                    Text(stringResource(R.string.climb_creator_dup_continue))
                }
            },
        )
    }
}

@Composable
private fun HoldCountStatus(holds: Map<Int, Int>) {
    val starts = holds.values.count { it == HoldRole.START }
    val hands = holds.values.count { it == HoldRole.HAND }
    val finishes = holds.values.count { it == HoldRole.FINISH }
    val feet = holds.values.count { it == HoldRole.FOOT }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = starts > 0,
            onClick = {},
            label = { Text(stringResource(R.string.climb_creator_count_start, starts)) },
        )
        FilterChip(
            selected = hands > 0,
            onClick = {},
            label = { Text(stringResource(R.string.climb_creator_count_hand, hands)) },
        )
        FilterChip(
            selected = finishes > 0,
            onClick = {},
            label = { Text(stringResource(R.string.climb_creator_count_finish, finishes)) },
        )
        FilterChip(
            selected = feet > 0,
            onClick = {},
            label = { Text(stringResource(R.string.climb_creator_count_foot, feet)) },
        )
    }
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
                ClimbValidation.Issue.TooFewHolds -> stringResource(R.string.climb_creator_issue_too_few_holds)
                is ClimbValidation.Issue.TooManyStarts -> stringResource(R.string.climb_creator_issue_too_many_starts, issue.count)
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
    Column {
        Text(
            stringResource(R.string.climb_creator_grade_label, gradeId ?: 20),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = (gradeId ?: 20).toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 10f..34f,
            steps = 23,
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
