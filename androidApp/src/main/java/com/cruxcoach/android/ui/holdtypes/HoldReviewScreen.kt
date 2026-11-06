package com.cruxcoach.android.ui.holdtypes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.BoardImageCache
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.boardImageCandidatePaths
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.holdtypes.CANT_TELL
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_NO
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_YES
import com.cruxcoach.domain.holdtypes.FieldSummary
import com.cruxcoach.domain.holdtypes.HoldDirection
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.ReviewField
import com.cruxcoach.domain.holdtypes.Verdict
import com.cruxcoach.domain.holdtypes.computedValue
import com.cruxcoach.domain.holdtypes.reviewerValue
import kotlin.math.roundToInt

/** Inches of board around the hold in the close-up. */
private const val CLOSE_UP_INCHES = 10f
private val MarkerColor = Color(0xFFFFC107)

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.missingDataset -> Text(stringResource(R.string.hold_review_missing))
                else -> {
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
private fun HoldViews(state: HoldReviewUiState) {
    val entry = state.entry ?: return
    val board = state.board
    if (board == null) {
        Text(stringResource(R.string.hold_review_no_board))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        HoldCloseUp(entry, board, Modifier.weight(0.6f))
        val placement = board.placements[entry.placement]
        val role = if (entry.layoutId == KILTER_HOMEWALL_LAYOUT) HoldRole.ROUTE_START else HoldRole.START
        Box(Modifier.weight(0.4f)) {
            KilterBoardVisualization(
                holds = listOf(BoardHold(entry.placement, role)),
                placements = board.placements,
                boardSize = board.boardSize,
                boardImages = board.boardImages,
                zone = placement?.let { BoardZone(it.x - 6, it.x + 6, it.y - 6, it.y + 6) },
            )
        }
    }
}

/** The board image around one hold, cut from the same asset the board view draws, with a ring on the hold. */
@Composable
private fun HoldCloseUp(entry: HoldTypeEntry, board: HoldBoardView, modifier: Modifier) {
    val context = LocalContext.current
    val layoutId = board.boardImages.firstOrNull()?.layoutId ?: entry.layoutId.toLong()
    val paths = remember(board.boardSize.id, layoutId) {
        boardImageCandidatePaths(BoardBrand.KILTER, board.boardSize.id, layoutId)
    }
    var image by remember(paths) { mutableStateOf<ImageBitmap?>(BoardImageCache.get(paths.first())) }
    LaunchedEffect(paths) { image = BoardImageCache.getOrDecode(paths, context.assets) }
    val placement = board.placements[entry.placement]
    val size = board.boardSize
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        val img = image ?: return@Canvas
        val p = placement ?: return@Canvas
        val boardWidth = (size.edgeRight - size.edgeLeft).toFloat()
        val boardHeight = (size.edgeTop - size.edgeBottom).toFloat()
        val px = (p.x - size.edgeLeft) / boardWidth * img.width
        val py = img.height - (p.y - size.edgeBottom) / boardHeight * img.height
        val window = (CLOSE_UP_INCHES / boardWidth * img.width).roundToInt()
            .coerceAtMost(minOf(img.width, img.height))
        val left = (px - window / 2f).roundToInt().coerceIn(0, img.width - window)
        val top = (py - window / 2f).roundToInt().coerceIn(0, img.height - window)
        drawImage(
            image = img,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(window, window),
            dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
        )
        val scale = this.size.width / window
        val ringRadius = 2.6f / boardWidth * img.width * scale
        drawCircle(
            color = MarkerColor,
            radius = ringRadius,
            center = Offset((px - left) * scale, (py - top) * scale),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun BlindRound(state: HoldReviewUiState, viewModel: HoldReviewViewModel) {
    val entry = state.entry ?: return
    Text(
        stringResource(R.string.hold_review_progress, state.index + 1, state.referenceSize, entry.placement),
        fontWeight = FontWeight.SemiBold,
    )
    HoldViews(state)
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
        stringResource(R.string.hold_review_progress, state.index + 1, state.disagreementHolds.size, entry.placement),
        fontWeight = FontWeight.SemiBold,
    )
    HoldViews(state)
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
        onClick = { saveLauncher.launch("hold-types-${HoldReviewViewModel.BOARD}-review.json") },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChooser(selected: String?, enabled: Boolean, withCantTell: Boolean = true, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_type), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HoldType.entries.forEach { type ->
            FilterChip(
                selected = selected == type.wire,
                enabled = enabled,
                onClick = { onSelect(type.wire) },
                label = { Text(typeName(type)) },
            )
        }
        if (withCantTell) {
            FilterChip(
                selected = selected == CANT_TELL,
                enabled = enabled,
                onClick = { onSelect(CANT_TELL) },
                label = { Text(stringResource(R.string.hold_review_cant_tell)) },
            )
        }
    }
}

/** Eight arrows around an "all round" circle, laid out like the directions they stand for. */
@Composable
private fun DirectionChooser(selected: String?, enabled: Boolean, withCantTell: Boolean = true, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_direction), style = MaterialTheme.typography.labelLarge)
    val grid = listOf(
        listOf(HoldDirection.UP_LEFT, HoldDirection.UP, HoldDirection.UP_RIGHT),
        listOf(HoldDirection.LEFT, HoldDirection.ALL_ROUND, HoldDirection.RIGHT),
        listOf(HoldDirection.DOWN_LEFT, HoldDirection.DOWN, HoldDirection.DOWN_RIGHT),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            grid.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { direction ->
                        DirectionCell(direction, selected == direction.wire, enabled) { onSelect(direction.wire) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = HoldDirection.fromWire(selected)
            if (current == HoldDirection.ALL_ROUND) Text(stringResource(R.string.hold_direction_all_round))
            if (withCantTell) {
                FilterChip(
                    selected = selected == CANT_TELL,
                    enabled = enabled,
                    onClick = { onSelect(CANT_TELL) },
                    label = { Text(stringResource(R.string.hold_review_cant_tell)) },
                )
            }
        }
    }
}

@Composable
private fun DirectionCell(direction: HoldDirection, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(if (selected) OrangeAccent else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            arrow(direction),
            fontSize = 20.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FootOnlyChooser(selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_foot_only), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            FOOT_ONLY_YES to R.string.hold_review_yes,
            FOOT_ONLY_NO to R.string.hold_review_no,
            CANT_TELL to R.string.hold_review_cant_tell,
        ).forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun NavRow(onPrevious: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean, previousEnabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onPrevious, enabled = previousEnabled) { Text("‹ " + stringResource(R.string.action_back)) }
        Button(onClick = onNext, enabled = nextEnabled) { Text(stringResource(R.string.action_next) + " ›") }
    }
}

private fun arrow(direction: HoldDirection): String = when (direction) {
    HoldDirection.UP -> "↑"
    HoldDirection.UP_RIGHT -> "↗"
    HoldDirection.RIGHT -> "→"
    HoldDirection.DOWN_RIGHT -> "↘"
    HoldDirection.DOWN -> "↓"
    HoldDirection.DOWN_LEFT -> "↙"
    HoldDirection.LEFT -> "←"
    HoldDirection.UP_LEFT -> "↖"
    HoldDirection.ALL_ROUND -> "◯"
}

@Composable
private fun typeName(type: HoldType): String = stringResource(
    when (type) {
        HoldType.JUG -> R.string.hold_type_jug
        HoldType.EDGE -> R.string.hold_type_edge
        HoldType.SLOPER -> R.string.hold_type_sloper
        HoldType.PINCH -> R.string.hold_type_pinch
        HoldType.POCKET -> R.string.hold_type_pocket
        HoldType.VOLUME -> R.string.hold_type_volume
        HoldType.FOOT_CHIP -> R.string.hold_type_foot_chip
    },
)

@Composable
private fun fieldName(field: ReviewField): String = stringResource(
    when (field) {
        ReviewField.TYPE -> R.string.hold_review_q_type
        ReviewField.DIRECTION -> R.string.hold_review_field_direction
        ReviewField.FOOT_ONLY -> R.string.hold_review_q_foot_only
    },
)

@Composable
private fun valueName(field: ReviewField, value: String?): String = when {
    value == null -> "–"
    value == CANT_TELL -> stringResource(R.string.hold_review_cant_tell)
    field == ReviewField.TYPE -> HoldType.fromWire(value)?.let { typeName(it) } ?: value
    field == ReviewField.DIRECTION -> HoldDirection.fromWire(value)?.let {
        if (it == HoldDirection.ALL_ROUND) stringResource(R.string.hold_direction_all_round) else arrow(it)
    } ?: value
    value == FOOT_ONLY_YES -> stringResource(R.string.hold_review_yes)
    value == FOOT_ONLY_NO -> stringResource(R.string.hold_review_no)
    else -> value
}

private const val KILTER_HOMEWALL_LAYOUT = 8
