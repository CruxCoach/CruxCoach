package com.cruxcoach.android.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.DarkSurface
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.roundToInt

/** What the board picker hands to the size check. */
internal data class BoardSizeCheckRequest(val brand: BoardBrand, val sizeId: Int, val layoutId: Int?)

/**
 * Optional help for users who are not sure which size their board is, or who see other holds
 * light up than the app shows: the board lights a few marked holds of the size under test and
 * the user compares. The box reports nothing back, so the user's eyes are the check.
 *
 * With [applyOnConfirm] a confirmed different size becomes the user's board (climb detail);
 * without it [onConfirmed] receives the size and the host applies it (board picker, where
 * Confirm still saves). Closing the sheet clears what it lit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSizeCheckSheet(
    brand: BoardBrand,
    sizeId: Int,
    layoutId: Int?,
    applyOnConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirmed: (Int) -> Unit = {},
    viewModel: BoardSizeCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedBrand by viewModel.connectedBrand.collectAsStateWithLifecycle()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    // Open once per sheet: a size confirmed here may change the saved board the host passes in.
    val start = remember { BoardSizeCheckRequest(brand, sizeId, layoutId) }
    LaunchedEffect(start) { viewModel.open(start.brand, start.sizeId, start.layoutId) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val connected = connection == ConnectionState.CONNECTED || connection == ConnectionState.SENDING
    val ready = connection == ConnectionState.CONNECTED && connectedBrand == brand
    // Coming back from connecting lights the marked holds without another tap.
    LaunchedEffect(ready) { if (ready) viewModel.lightIfDark() }
    if (showConnection) {
        BleConnectionSheet(onDismiss = { showConnection = false })
        return
    }
    // Fully open, like the cleaning sheet: the picture and the answers do not fit half a screen.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BoardSizeCheckContent(
            state = state,
            brand = brand,
            connected = connected,
            ready = ready,
            otherBoardConnected = connected && connectedBrand != null && connectedBrand != brand,
            applyOnConfirm = applyOnConfirm,
            onConnect = { showConnection = true },
            onLight = viewModel::light,
            onYes = { viewModel.confirm(applyOnConfirm, onConfirmed) },
            onNo = viewModel::reject,
            onTest = viewModel::test,
            onClose = onDismiss,
        )
    }
}

@Composable
internal fun BoardSizeCheckContent(
    state: BoardSizeCheckState,
    brand: BoardBrand,
    connected: Boolean,
    ready: Boolean,
    otherBoardConnected: Boolean,
    applyOnConfirm: Boolean,
    onConnect: () -> Unit,
    onLight: () -> Unit,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onTest: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("board_size_check_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // How the check works sits at the title's info icon (design.md): the sheet itself only
        // shows the size, its holds and the question.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.board_size_check_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            InfoButton(stringResource(R.string.board_size_check_title), stringResource(R.string.board_size_check_info))
        }
        val size = state.size
        // After "No" the other sizes are the task: the rejected size would push them off the screen.
        val picking = state.step == BoardSizeCheckStep.PICK_OTHER
        if (!picking) {
            size?.let {
                Text(
                    boardSizeCheckLabel(brand, it),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("board_size_check_size"),
                )
            }
        }
        val lookAlikeOf = state.startsWithLookAlike
        if (state.step == BoardSizeCheckStep.ASK && lookAlikeOf != null && size != null) {
            Note(
                stringResource(
                    R.string.board_size_check_look_alike_first,
                    boardSizeCheckLabel(brand, lookAlikeOf),
                    boardSizeCheckLabel(brand, size),
                ),
                Modifier.testTag("board_size_check_look_alike"),
            )
        }
        if (size != null && state.points.isNotEmpty() && !picking) {
            BoardSizeCheckImage(brand, size, state.layoutId, state.points)
        }
        when (state.step) {
            BoardSizeCheckStep.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            BoardSizeCheckStep.NO_DATA -> Supporting(stringResource(R.string.board_size_check_no_data))
            BoardSizeCheckStep.ASK -> {
                when {
                    otherBoardConnected -> ConnectPrompt(
                        stringResource(R.string.board_size_check_other_board, brand.displayName), onConnect,
                    )
                    !connected -> ConnectPrompt(stringResource(R.string.board_size_check_connect_hint), onConnect)
                    // Only lit holds can be answered: until then the sheet says what keeps them dark.
                    state.lit -> {
                        Text(
                            stringResource(R.string.board_size_check_question),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SideBySideOrStacked(spacing = 12.dp) {
                            Button(
                                onClick = onYes,
                                enabled = ready && !state.busy && state.canConfirm,
                                modifier = Modifier.testTag("board_size_check_yes"),
                            ) { Text(stringResource(R.string.board_size_check_yes)) }
                            OutlinedButton(
                                onClick = onNo,
                                enabled = ready && !state.busy,
                                modifier = Modifier.testTag("board_size_check_no"),
                            ) { Text(stringResource(R.string.board_size_check_no)) }
                        }
                        TextButton(
                            onClick = onLight,
                            enabled = ready && !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("board_size_check_relight"),
                        ) { Text(stringResource(R.string.board_size_check_relight), textAlign = TextAlign.Center) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.sendFailed || state.replaced -> {
                        if (state.sendFailed) {
                            Supporting(stringResource(R.string.board_size_check_send_failed), color = MaterialTheme.colorScheme.error)
                        } else {
                            Supporting(
                                stringResource(R.string.board_size_check_replaced),
                                Modifier.testTag("board_size_check_replaced"),
                            )
                        }
                        Button(
                            onClick = onLight,
                            enabled = ready && !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("board_size_check_relight"),
                        ) { Text(stringResource(R.string.board_size_check_relight), textAlign = TextAlign.Center) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    // Sending, or about to: the sheet lights them as soon as the board is free.
                    else -> {
                        Supporting(
                            stringResource(R.string.board_size_check_lighting),
                            Modifier.testTag("board_size_check_lighting"),
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (ready && !state.busy) {
                            TextButton(
                                onClick = onLight,
                                modifier = Modifier.fillMaxWidth().testTag("board_size_check_relight"),
                            ) { Text(stringResource(R.string.board_size_check_relight), textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
            BoardSizeCheckStep.PICK_OTHER -> {
                // Without a board a tapped size only shows its holds here: connecting comes first.
                if (!connected) ConnectPrompt(null, onConnect)
                Supporting(stringResource(if (connected) R.string.board_size_check_pick_other else R.string.board_size_check_pick_other_offline))
                // Most common first: the likeliest other size is the one at the top.
                state.otherSizes.sortedByDescending { BoardConstants.DEFAULT_SIZE_FREQUENCY[it.id.toInt()] ?: 0L }.forEach { other ->
                    SizeOption(
                        label = boardSizeCheckLabel(brand, other),
                        tried = other.id.toInt() in state.tried,
                        enabled = !state.busy,
                        onClick = { onTest(other.id.toInt()) },
                        modifier = Modifier.testTag("board_size_check_option_${other.id}"),
                    )
                }
                if (state.otherSizes.isEmpty() || state.allOthersTried) Supporting(stringResource(R.string.board_size_check_none_fit))
            }
            BoardSizeCheckStep.CONFIRMED -> {
                // The size is on the line above: the result names only what happened to it.
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OrangeAccent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            when {
                                // In the picker nothing is saved before Confirm, also not the size it had selected.
                                !applyOnConfirm -> R.string.board_size_check_selected
                                state.confirmedChange -> R.string.board_size_check_saved
                                else -> R.string.board_size_check_fits
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("board_size_check_result"),
                    )
                }
            }
        }
        if (state.step == BoardSizeCheckStep.CONFIRMED) {
            // The answer is in: closing is the one thing left to do.
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().testTag("board_size_check_close"),
            ) { Text(stringResource(if (applyOnConfirm) R.string.board_size_check_done else R.string.board_size_check_back_to_picker)) }
        } else {
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("board_size_check_close")) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** What the sheet is waiting for or what a tap does: quieter than the question and its answers. */
@Composable
private fun Supporting(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** A line that explains what the check shows, marked like the picker's hints. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Its children side by side in equal widths while each label fits on one line there, else
 * stacked at full width: "Ja, passt" must not wrap into a blob at the largest font.
 */
@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val gaps = gap * (measurables.size - 1)
        val each = (width - gaps) / measurables.size.coerceAtLeast(1)
        val sideBySide = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= each }
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(if (sideBySide) each else width)) }
        if (sideBySide) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                placeables.forEachIndexed { i, placeable -> placeable.placeRelative(i * (each + gap), (height - placeable.height) / 2) }
            }
        } else {
            val height = placeables.sumOf { it.height } + gaps
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

@Composable
private fun ConnectPrompt(text: String?, onConnect: () -> Unit) {
    text?.let { Supporting(it) }
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().testTag("board_size_check_connect")) {
        Icon(Icons.Default.Bluetooth, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.board_size_check_connect))
    }
}

@Composable
private fun SizeOption(label: String, tried: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        // "Tried" under the name, so it never squeezes a long size name at the largest font.
        Column(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (tried) {
                Text(
                    stringResource(R.string.board_size_check_tried),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The board picture of [boardSize] with the holds that should light up ringed. */
@Composable
private fun BoardSizeCheckImage(
    brand: BoardBrand,
    boardSize: BoardSize,
    layoutId: Int?,
    points: List<BoardSizeCheckPoint>,
) {
    val layers = rememberBoardImageLoadState(brand, boardSize.id, layoutId?.toLong(), maxDimension = 1024)
    val width = (boardSize.edgeRight - boardSize.edgeLeft).toFloat().coerceAtLeast(1f)
    val height = (boardSize.edgeTop - boardSize.edgeBottom).toFloat().coerceAtLeast(1f)
    val description = stringResource(R.string.board_size_check_image_cd, boardSizeCheckLabel(brand, boardSize))
    // At most a little under half the screen: on a small phone a tall board would push the
    // question and its answers below the fold.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Box(
        Modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = minOf((w * height / width).roundToInt(), maxHeight.roundToPx())
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .semantics { contentDescription = description }
            .testTag("board_size_check_image"),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 3.dp.toPx()
            // Holds sit right at the edge of the picture: inset it by a ring so the corner
            // rings show whole instead of being cut by the frame.
            val fullScale = minOf(size.width / width, size.height / height)
            val inset = ringRadius(width, height, fullScale) + stroke
            val scale = minOf((size.width - 2 * inset) / width, (size.height - 2 * inset) / height)
                .coerceAtLeast(0.01f)
            val picture = Size(width * scale, height * scale)
            val origin = Offset((size.width - picture.width) / 2f, (size.height - picture.height) / 2f)
            val frame = Path().apply { addRoundRect(RoundRect(Rect(origin, picture), CornerRadius(12.dp.toPx()))) }
            clipPath(frame) {
                // The holds are white: on a light sheet they need the dark board behind them.
                drawRect(DarkSurface, origin, picture)
                if (!layers.isNullOrEmpty()) {
                    val target = IntSize(picture.width.roundToInt(), picture.height.roundToInt())
                    val at = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                    layers.forEach { drawImage(it, dstOffset = at, dstSize = target) }
                }
            }
            val centres = points.map { point ->
                Offset(
                    origin.x + (point.x - boardSize.edgeLeft) * scale,
                    origin.y + picture.height - (point.y - boardSize.edgeBottom) * scale,
                )
            }
            // Neighbouring marked holds keep apart rings, so each one can be counted.
            val closest = centres.withIndex().minOfOrNull { (i, a) ->
                centres.drop(i + 1).minOfOrNull { (a - it).getDistance() } ?: Float.MAX_VALUE
            } ?: Float.MAX_VALUE
            val radius = minOf(ringRadius(width, height, scale), (closest - stroke) / 2f).coerceAtLeast(3.dp.toPx())
            centres.forEach { centre ->
                drawCircle(Color.White.copy(alpha = 0.3f), radius, centre)
                drawCircle(OrangeAccent, radius, centre, style = Stroke(width = stroke))
            }
        }
    }
}

/** Ring around a marked hold: about one hold spacing wide, never smaller than 6 dp (closer holds shrink it). */
private fun DrawScope.ringRadius(width: Float, height: Float, scale: Float): Float =
    maxOf(minOf(width, height) / 36f * scale, 6.dp.toPx())

/** Quiet entry right above a size list: "Check the size on your board". */
@Composable
internal fun BoardSizeCheckLink(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.testTag("board_size_check_link"),
    ) {
        Icon(Icons.Outlined.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        // Weighted, so a two-line label at the largest font wraps inside the dialog instead of past it.
        Text(
            stringResource(R.string.board_size_check_link),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

internal fun boardSizeCheckLabel(brand: BoardBrand, size: BoardSize): String =
    if (brand == BoardBrand.KILTER) BoardConstants.sizeLabel(size.id, size.name)
    else BoardConstants.auroraSizeLabel(brand, size)
