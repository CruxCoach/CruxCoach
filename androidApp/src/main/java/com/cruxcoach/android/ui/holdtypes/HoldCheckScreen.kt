package com.cruxcoach.android.ui.holdtypes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.cruxcoach.domain.holdtypes.CheckVerdict
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_NO
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_YES
import com.cruxcoach.domain.holdtypes.HoldCheck
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.ReviewField

/**
 * Checking the computed hold types of every board that has them (#63), test builds only: the proposed label is
 * shown, and the reviewer confirms it, corrects it or says they can't tell. Holds on the review list come first.
 * The results stay on the phone until they are saved or shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HoldCheckScreen(
    onNavigateBack: () -> Unit,
    viewModel: HoldCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // A new hold starts at the top, so its picture is in view before it is checked.
    LaunchedEffect(state.board, state.reviewListOnly, state.index) { scroll.scrollTo(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hold_check_title)) },
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
                state.boards.isEmpty() && !state.loading -> Text(stringResource(R.string.hold_review_missing))
                else -> {
                    BoardChooser(state, viewModel::selectBoard)
                    QueueChooser(state, viewModel::setReviewListOnly)
                    when {
                        state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.entry == null -> Text(stringResource(R.string.hold_check_none))
                        else -> CheckHold(state, viewModel)
                    }
                    HorizontalDivider()
                    CheckResults(state, viewModel)
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
private fun BoardChooser(state: HoldCheckUiState, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.boards.forEach { board ->
            FilterChip(
                selected = state.board == board,
                onClick = { onSelect(board) },
                label = { Text(BoardBrand.fromWireOrNull(board)?.displayName ?: board) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueChooser(state: HoldCheckUiState, onSelect: (Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.reviewListOnly,
            onClick = { onSelect(true) },
            label = { Text(stringResource(R.string.hold_check_review_list, state.reviewListSize)) },
        )
        FilterChip(
            selected = !state.reviewListOnly,
            onClick = { onSelect(false) },
            label = { Text(stringResource(R.string.hold_check_all, state.labelledSize)) },
        )
    }
}

@Composable
private fun CheckHold(state: HoldCheckUiState, viewModel: HoldCheckViewModel) {
    val entry = state.entry ?: return
    Text(
        stringResource(R.string.hold_check_progress, state.index + 1, state.queueSize, holdName(state.board.orEmpty(), entry)),
        fontWeight = FontWeight.SemiBold,
    )
    HoldViews(entry, state.view)
    ProposedLabel(entry)
    state.check?.let { CheckLine(it) }
    val correction = state.correction
    if (correction == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::confirm, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hold_check_looks_right))
            }
            OutlinedButton(onClick = viewModel::startCorrection, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hold_check_correct))
            }
        }
        TextButton(onClick = viewModel::cantTell) { Text(stringResource(R.string.hold_review_cant_tell)) }
    } else {
        TypeChooser(correction.type, enabled = true, withCantTell = false) { v -> viewModel.editCorrection { it.copy(type = v) } }
        DirectionChooser(correction.direction, enabled = true, withCantTell = false) { v ->
            viewModel.editCorrection { it.copy(direction = v) }
        }
        FootOnlyChooser(correction.footOnly, enabled = true, withCantTell = false) { v ->
            viewModel.editCorrection { it.copy(footOnly = v) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveCorrection, enabled = correction.complete, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hold_check_save_correction))
            }
            OutlinedButton(onClick = viewModel::cancelCorrection, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
    NavRow(
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        nextEnabled = state.index + 1 < state.queueSize,
        previousEnabled = state.index > 0,
    )
}

@Composable
private fun ProposedLabel(entry: HoldTypeEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.hold_check_proposed), fontWeight = FontWeight.SemiBold)
            val type = entry.holdType?.let { typeName(it) } ?: "–"
            Text("${fieldName(ReviewField.TYPE)}: $type")
            entry.secondType?.let { Text(stringResource(R.string.hold_check_second, typeName(it))) }
            entry.size?.let { Text(stringResource(R.string.hold_check_size, sizeName(it))) }
            Text("${fieldName(ReviewField.DIRECTION)}: ${valueName(ReviewField.DIRECTION, entry.direction)}")
            Text("${fieldName(ReviewField.FOOT_ONLY)}: ${valueName(ReviewField.FOOT_ONLY, if (entry.footOnly) FOOT_ONLY_YES else FOOT_ONLY_NO)}")
            if (entry.source["type"] == SOURCE_MIRRORED) {
                Text(stringResource(R.string.hold_check_mirrored), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(
                    R.string.hold_check_confidence,
                    confidenceName(entry.confidence.type),
                    confidenceName(entry.confidence.direction),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.review.isNotEmpty()) {
                Text(
                    stringResource(R.string.hold_check_why, entry.review.map { reasonName(it) }.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CheckLine(check: HoldCheck) {
    val text = when (CheckVerdict.fromWire(check.verdict)) {
        CheckVerdict.CONFIRMED -> stringResource(R.string.hold_check_verdict_confirmed)
        CheckVerdict.CANT_TELL -> stringResource(R.string.hold_review_cant_tell)
        CheckVerdict.CORRECTED, null -> stringResource(R.string.hold_check_verdict_corrected) + ": " +
            listOf(
                valueName(ReviewField.TYPE, check.type),
                valueName(ReviewField.DIRECTION, check.direction),
                fieldName(ReviewField.FOOT_ONLY) + " " + valueName(ReviewField.FOOT_ONLY, check.footOnly),
            ).joinToString(" · ")
    }
    Text(stringResource(R.string.hold_check_yours, text), color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CheckResults(state: HoldCheckUiState, viewModel: HoldCheckViewModel) {
    val context = LocalContext.current
    val board = state.board ?: return
    var saved by remember(board) { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.resultsJson().toByteArray()) }
            saved = true
        }
    }
    val s = state.summary ?: return
    Text(
        stringResource(
            R.string.hold_check_summary,
            s.checked, s.confirmed, s.corrected, s.typeChanged, s.directionChanged, s.footOnlyChanged, s.cantTell,
        ),
    )
    if (s.checked == 0) return
    Button(onClick = { saveLauncher.launch("hold-types-$board-check.json") }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hold_review_save_file))
    }
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
            text = { Text(stringResource(R.string.hold_check_start_over_confirm)) },
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

/** Source of a label copied from its mirror partner (left and right swapped). */
private const val SOURCE_MIRRORED = "mirrored"

@Composable
private fun sizeName(size: String): String = when (size) {
    "small" -> stringResource(R.string.hold_size_small)
    "medium" -> stringResource(R.string.hold_size_medium)
    "large" -> stringResource(R.string.hold_size_large)
    else -> size
}

/** The same three levels the reports use: high from 0.6, medium from 0.4, low below. */
@Composable
private fun confidenceName(value: Double): String = stringResource(
    when {
        value >= 0.6 -> R.string.hold_conf_high
        value >= 0.4 -> R.string.hold_conf_medium
        else -> R.string.hold_conf_low
    },
)

@Composable
private fun reasonName(reason: String): String = when (reason) {
    "type uncertain" -> stringResource(R.string.hold_reason_type)
    "direction uncertain" -> stringResource(R.string.hold_reason_direction)
    "low-resolution image" -> stringResource(R.string.hold_reason_low_res)
    "touches another hold in the image" -> stringResource(R.string.hold_reason_touching)
    "app image shows a placeholder" -> stringResource(R.string.hold_reason_placeholder)
    else -> reason
}
