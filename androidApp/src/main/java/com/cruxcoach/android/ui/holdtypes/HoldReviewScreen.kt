package com.cruxcoach.android.ui.holdtypes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.holdtypes.CANT_TELL
import com.cruxcoach.domain.holdtypes.FieldSummary
import com.cruxcoach.domain.holdtypes.ReviewField
import com.cruxcoach.domain.holdtypes.Verdict
import com.cruxcoach.domain.holdtypes.computedValue
import com.cruxcoach.domain.holdtypes.reviewerValue
import kotlin.math.roundToInt

/**
 * Hold-type review (#63), test builds only. Round one is blind: the reviewer answers type, direction and
 * foot only before the computed label is shown. Round two shows only the disagreements, and the reviewer
 * decides who was right. The results stay on the phone until they are saved or shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HoldReviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: HoldReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // A new hold starts at the top, so its picture is in view before anything is answered.
    LaunchedEffect(state.selectedBoard, state.stage, state.index) { scroll.scrollTo(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hold_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.missingDataset -> Text(stringResource(R.string.hold_review_missing))
                else -> {
                    if (state.boards.size > 1) BoardTabs(state, viewModel::selectBoard)
                    StageTabs(state, viewModel::openStage)
                    when (state.stage) {
                        ReviewStage.BLIND -> BlindRound(state, viewModel)
                        ReviewStage.DECIDE -> DecideRound(state, viewModel)
                        ReviewStage.RESULTS -> Results(state, viewModel)
                    }
                    Text(
                        stringResource(R.string.hold_review_dataset, state.datasetVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardTabs(state: HoldReviewUiState, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.boards.forEach { board ->
            FilterChip(
                selected = state.selectedBoard == board,
                onClick = { onSelect(board) },
                label = { Text(BoardBrand.fromWireOrNull(board)?.displayName ?: board) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageTabs(state: HoldReviewUiState, onOpen: (ReviewStage) -> Unit) {
    val blindDone = state.answered == state.referenceSize
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.stage == ReviewStage.BLIND,
            onClick = { onOpen(ReviewStage.BLIND) },
            label = { Text(stringResource(R.string.hold_review_stage_blind, state.answered, state.referenceSize)) },
        )
        FilterChip(
            selected = state.stage == ReviewStage.DECIDE,
            enabled = blindDone,
            onClick = { onOpen(ReviewStage.DECIDE) },
            label = { Text(stringResource(R.string.hold_review_stage_decide, state.disagreementHolds.size)) },
        )
        FilterChip(
            selected = state.stage == ReviewStage.RESULTS,
            onClick = { onOpen(ReviewStage.RESULTS) },
            label = { Text(stringResource(R.string.hold_review_stage_results)) },
        )
    }
}

@Composable
private fun BlindRound(state: HoldReviewUiState, viewModel: HoldReviewViewModel) {
    val entry = state.entry ?: return
    Text(
        stringResource(R.string.hold_review_progress, state.index + 1, state.referenceSize, holdName(state.selectedBoard, entry)),
        fontWeight = FontWeight.SemiBold,
    )
    HoldViews(state.entry, state.board)
    Text(
        stringResource(R.string.hold_review_blind_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val locked = state.answer != null
    TypeChooser(selected = state.draft.type, enabled = !locked, onSelect = viewModel::selectType)
    DirectionChooser(selected = state.draft.direction, enabled = !locked, onSelect = viewModel::selectDirection)
    FootOnlyChooser(selected = state.draft.footOnly, enabled = !locked, onSelect = viewModel::selectFootOnly)
    val answer = state.answer
    if (answer == null) {
        Button(onClick = viewModel::submit, enabled = state.draft.complete, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hold_review_show_label))
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReviewField.entries.forEach { field ->
                    val computed = answer.computedValue(field)
                    val yours = answer.reviewerValue(field)
                    val mark = when {
                        yours == CANT_TELL || computed == null -> "·"
                        yours == computed -> "✓"
                        else -> "✗"
                    }
                    Text("$mark ${fieldName(field)}: " + stringResource(R.string.hold_review_computed, valueName(field, computed)))
                }
            }
        }
    }
    NavRow(onPrevious = viewModel::previous, onNext = viewModel::next, nextEnabled = answer != null, previousEnabled = state.index > 0)
}

@Composable
private fun DecideRound(state: HoldReviewUiState, viewModel: HoldReviewViewModel) {
    if (state.answered < state.referenceSize) {
        Text(stringResource(R.string.hold_review_decide_locked))
        return
    }
    if (state.disagreementHolds.isEmpty()) {
        Text(stringResource(R.string.hold_review_none_disagree))
        return
    }
    val entry = state.entry ?: return
    Text(
        stringResource(R.string.hold_review_progress, state.index + 1, state.disagreementHolds.size, holdName(state.selectedBoard, entry)),
        fontWeight = FontWeight.SemiBold,
    )
    HoldViews(state.entry, state.board)
    Text(
        stringResource(R.string.hold_review_decide_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val session = state.session
    state.disagreementsHere.forEach { d ->
        val decision = session?.decisionFor(d.placement, d.field)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fieldName(d.field), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.hold_review_yours, valueName(d.field, d.reviewer)))
                Text(stringResource(R.string.hold_review_computed, valueName(d.field, d.computed)))
                VerdictChooser(
                    field = d.field,
                    selected = Verdict.fromWire(decision?.verdict),
                    corrected = decision?.corrected,
                    onDecide = { verdict, corrected -> viewModel.decide(d.field, verdict.wire, corrected) },
                )
            }
        }
    }
    NavRow(onPrevious = viewModel::previous, onNext = viewModel::next, nextEnabled = true, previousEnabled = state.index > 0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictChooser(
    field: ReviewField,
    selected: Verdict?,
    corrected: String?,
    onDecide: (Verdict, String?) -> Unit,
) {
    val verdicts = buildList {
        add(Verdict.REVIEWER_RIGHT to R.string.hold_review_verdict_reviewer)
        add(Verdict.COMPUTED_RIGHT to R.string.hold_review_verdict_computed)
        // Foot only is yes or no: one of the two is right.
        if (field != ReviewField.FOOT_ONLY) add(Verdict.BOTH_WRONG to R.string.hold_review_verdict_both)
        add(Verdict.CANT_TELL to R.string.hold_review_cant_tell)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        verdicts.forEach { (verdict, label) ->
            FilterChip(
                selected = selected == verdict,
                onClick = { onDecide(verdict, null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (selected == Verdict.BOTH_WRONG) {
        when (field) {
            ReviewField.TYPE -> TypeChooser(corrected, enabled = true, withCantTell = false) {
                onDecide(Verdict.BOTH_WRONG, it)
            }
            ReviewField.DIRECTION -> DirectionChooser(corrected, enabled = true, withCantTell = false) {
                onDecide(Verdict.BOTH_WRONG, it)
            }
            ReviewField.FOOT_ONLY -> Unit
        }
    }
}

@Composable
private fun Results(state: HoldReviewUiState, viewModel: HoldReviewViewModel) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.resultsJson().toByteArray()) }
            saved = true
        }
    }
    Text(stringResource(R.string.hold_review_results_blind), fontWeight = FontWeight.SemiBold)
    state.summary.forEach { s ->
        Text(
            stringResource(
                R.string.hold_review_results_blind_row,
                fieldName(s.field), s.agreed, s.compared, percent(s), s.cantTell,
            ),
        )
        s.agreedWithinOneStep?.let {
            Text(
                stringResource(R.string.hold_review_results_near, it, s.compared),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider()
    Text(stringResource(R.string.hold_review_results_decided), fontWeight = FontWeight.SemiBold)
    state.summary.forEach { s ->
        Text(
            stringResource(
                R.string.hold_review_results_decided_row,
                fieldName(s.field), s.computedWrong, s.reviewerWrong, s.bothWrong, s.undecided,
            ),
        )
    }
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { saveLauncher.launch(viewModel.resultsFileName()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.hold_review_save_file)) }
    OutlinedButton(
        onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, viewModel.resultsJson())
            }
            context.startActivity(Intent.createChooser(send, null))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.hold_review_share)) }
    if (saved) Text(stringResource(R.string.hold_review_saved), color = MaterialTheme.colorScheme.primary)
    TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.hold_review_start_over)) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text(stringResource(R.string.hold_review_start_over_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.startOver() }) {
                    Text(stringResource(R.string.hold_review_start_over))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun percent(s: FieldSummary): Int = ((s.blindAgreement ?: 0.0) * 100).roundToInt()
