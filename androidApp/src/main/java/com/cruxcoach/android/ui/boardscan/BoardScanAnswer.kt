package com.cruxcoach.android.ui.boardscan

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.boardscan.ScanTestSummary

/** The scan's answer. Outside test mode a tap opens the problem; in test mode it records which problem was lit. */
@Composable
internal fun AnswerPanel(state: BoardScanState, viewModel: BoardScanViewModel, onOpenClimb: (String, Int) -> Unit) {
    val title = when {
        state.candidates.isEmpty() -> R.string.board_scan_nothing
        state.sure -> R.string.board_scan_sure
        else -> R.string.board_scan_not_sure
    }
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("board_scan_answer"))
    val shown = if (state.sure && !state.choosing) state.candidates.take(1) else state.candidates
    shown.forEachIndexed { i, c ->
        CandidateRow(c, enabled = true, highlighted = i == 0 && state.sure, modifier = Modifier.testTag("board_scan_candidate_$i")) {
            if (state.testMode) viewModel.confirm(i) else state.board?.let { onOpenClimb(c.uuid, it.angle) }
        }
    }
    Text(stringResource(R.string.board_scan_details, state.leds, seconds(state.msToAnswer)),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.testMode && state.sure && !state.choosing) {
        state.onBoard?.let { lit ->
            Text(stringResource(R.string.board_scan_test_on_board, lit.name ?: stringResource(R.string.board_scan_unnamed)),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("board_scan_test_on_board"))
        }
    }
    if (!state.testMode) {
        Button(onClick = viewModel::scanAgain, modifier = Modifier.fillMaxWidth().testTag("board_scan_again")) {
            Text(stringResource(R.string.board_scan_again))
        }
        return
    }
    if (state.sure && !state.choosing) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.confirm(0) }, modifier = Modifier.weight(1f).testTag("board_scan_test_right")) {
                Text(stringResource(R.string.board_scan_test_right))
            }
            OutlinedButton(onClick = viewModel::startChoosing, modifier = Modifier.weight(1f).testTag("board_scan_test_wrong")) {
                Text(stringResource(R.string.board_scan_test_wrong))
            }
        }
        return
    }
    PickLitProblem(state, viewModel)
}

/** Test mode: the lit problem was not the sure answer (or there was none); find it, or say it is not on the phone. */
@Composable
private fun PickLitProblem(state: BoardScanState, viewModel: BoardScanViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.board_scan_test_tap_lit), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = query, onValueChange = { query = it; viewModel.search(it) },
        label = { Text(stringResource(R.string.board_scan_test_search)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("board_scan_test_search"),
    )
    if (state.mirrorable) {
        FilterChip(
            selected = state.truthMirrored, onClick = { viewModel.setTruthMirrored(!state.truthMirrored) },
            label = { Text(stringResource(R.string.board_scan_test_lit_mirrored)) },
            modifier = Modifier.testTag("board_scan_test_mirrored"),
        )
    }
    // the problem CruxCoach lit, unless it is offered above already
    state.onBoard?.takeIf { lit -> state.candidates.none { it.uuid.equals(lit.uuid, ignoreCase = true) && !it.mirrored } }?.let { lit ->
        CandidateRow(lit, enabled = true, note = stringResource(R.string.board_scan_test_lit_by_app),
            modifier = Modifier.testTag("board_scan_test_pick_on_board")) { viewModel.pick(lit, mirrored = false) }
    }
    state.searchResults.forEachIndexed { i, c ->
        CandidateRow(c, enabled = true, modifier = Modifier.testTag("board_scan_search_$i")) { viewModel.pick(c) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::notInCatalogue, modifier = Modifier.weight(1f).testTag("board_scan_test_not_in_catalogue")) {
            Text(stringResource(R.string.board_scan_test_not_in_catalogue), maxLines = 2)
        }
        TextButton(onClick = viewModel::skip, modifier = Modifier.weight(1f).testTag("board_scan_test_skip")) {
            Text(stringResource(R.string.board_scan_test_skip))
        }
    }
}

@Composable
internal fun CandidateRow(
    c: ScanCandidate,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    /** Shown instead of the holds seen. */
    note: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth()
            .border(if (highlighted) 2.dp else 1.dp,
                if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name ?: stringResource(R.string.board_scan_unnamed), fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = note ?: listOfNotNull(
                c.mirrored.takeIf { it }?.let { stringResource(R.string.board_scan_mirrored) },
                c.holds.takeIf { it > 0 }?.let { stringResource(R.string.board_scan_holds_seen, c.matched, it) },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        c.grade?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** Test results per board, with the gate: free-view scans on a real board and no sure-and-wrong answer. */
@Composable
internal fun ScanResultsDialog(state: BoardScanState, onExport: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.board_scan_test_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }, modifier = Modifier.testTag("board_scan_clear_confirm")) {
                    Text(stringResource(R.string.board_scan_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.board_scan_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_scan_test_results)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("board_scan_results_dialog")) {
                if (state.summaries.isEmpty()) Text(stringResource(R.string.board_scan_test_empty))
                state.summaries.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    SummaryBlock(s, if (s.board.copy(angle = 0) == state.board?.copy(angle = 0)) state.boardLabel else state.summaryLabels[s.board])
                }
                Text(stringResource(R.string.board_scan_test_note), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onExport, enabled = state.summaries.isNotEmpty(), modifier = Modifier.testTag("board_scan_export")) {
                Text(stringResource(R.string.board_scan_test_export))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmClear = true }, enabled = state.summaries.isNotEmpty()) {
                    Text(stringResource(R.string.board_scan_test_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.board_scan_close)) }
            }
        },
    )
}

@Composable
private fun SummaryBlock(s: ScanTestSummary, label: String?) {
    Column {
        val board = label ?: s.board.moonBoardVariant?.displayName ?: "${s.board.brand} ${s.board.layoutId}/${s.board.sizeId}"
        Text(board, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.board_scan_test_free, s.freeView, GATE_SCANS))
        Text(stringResource(R.string.board_scan_test_top, s.freeTop1, s.freeTop3))
        Text(stringResource(R.string.board_scan_test_sure, s.freeSure, s.sureWrong),
            color = if (s.sureWrong > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (s.freeView > 0) Text(stringResource(R.string.board_scan_test_time, seconds(s.medianMs)))
        if (s.scans > s.freeView) {
            Text(stringResource(R.string.board_scan_test_all, s.scans), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Free-view scans per board on a real board, with no sure-and-wrong answer, before the rest of the scan UI (#65). */
private const val GATE_SCANS = 50
