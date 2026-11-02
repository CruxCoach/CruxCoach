package com.cruxcoach.android.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSizeCheckPoint
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
        Text(stringResource(R.string.board_size_check_title), style = MaterialTheme.typography.titleLarge)
        val size = state.size
        size?.let {
            Text(
                boardSizeCheckLabel(brand, it),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("board_size_check_size"),
            )
        }
        Text(stringResource(R.string.board_size_check_intro), style = MaterialTheme.typography.bodyMedium)
        if (size != null && state.points.isNotEmpty()) {
            BoardSizeCheckImage(brand, size, state.layoutId, state.points)
        }
        when (state.step) {
            BoardSizeCheckStep.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            BoardSizeCheckStep.NO_DATA -> Text(stringResource(R.string.board_size_check_no_data))
            BoardSizeCheckStep.ASK -> when {
                otherBoardConnected -> ConnectPrompt(
                    stringResource(R.string.board_size_check_other_board, brand.displayName), onConnect,
                )
                !connected -> ConnectPrompt(stringResource(R.string.board_size_check_connect_hint), onConnect)
                else -> {
                    Text(
                        stringResource(R.string.board_size_check_question),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onYes,
                            enabled = ready && state.lit && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                            modifier = Modifier.weight(1f).testTag("board_size_check_yes"),
                        ) { Text(stringResource(R.string.board_size_check_yes)) }
                        OutlinedButton(
                            onClick = onNo,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).testTag("board_size_check_no"),
                        ) { Text(stringResource(R.string.board_size_check_no)) }
                    }
                    TextButton(
                        onClick = onLight,
                        enabled = ready && !state.busy,
                        modifier = Modifier.testTag("board_size_check_relight"),
                    ) { Text(stringResource(R.string.board_size_check_relight)) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.sendFailed) {
                        Text(stringResource(R.string.board_size_check_send_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            BoardSizeCheckStep.PICK_OTHER -> {
                Text(stringResource(R.string.board_size_check_pick_other))
                state.otherSizes.forEach { other ->
                    SizeOption(
                        label = boardSizeCheckLabel(brand, other),
                        tried = other.id.toInt() in state.tried,
                        enabled = !state.busy,
                        onClick = { onTest(other.id.toInt()) },
                        modifier = Modifier.testTag("board_size_check_option_${other.id}"),
                    )
                }
                if (state.allOthersTried) Text(stringResource(R.string.board_size_check_none_fit))
                if (!connected) ConnectPrompt(stringResource(R.string.board_size_check_connect_hint), onConnect)
            }
            BoardSizeCheckStep.CONFIRMED -> {
                val label = size?.let { boardSizeCheckLabel(brand, it) }.orEmpty()
                Text(
                    when {
                        state.confirmedChange && applyOnConfirm -> stringResource(R.string.board_size_check_saved, label)
                        state.confirmedChange -> stringResource(R.string.board_size_check_selected, label)
                        else -> stringResource(R.string.board_size_check_fits, label)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("board_size_check_result"),
                )
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("board_size_check_close")) {
            Text(stringResource(if (state.step == BoardSizeCheckStep.CONFIRMED) R.string.board_size_check_done else R.string.action_close))
        }
    }
}

@Composable
private fun ConnectPrompt(text: String, onConnect: () -> Unit) {
    Text(text)
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
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.board_size_check_image_cd, boardSizeCheckLabel(brand, boardSize))
    Box(
        Modifier.fillMaxWidth().aspectRatio(width / height).clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .testTag("board_size_check_image"),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
            if (layers.isNullOrEmpty()) drawRect(placeholder) else layers.forEach { drawImage(it, dstSize = target) }
            val scaleX = size.width / width
            val scaleY = size.height / height
            val radius = maxOf(minOf(width, height) / 36f * minOf(scaleX, scaleY), 6.dp.toPx())
            points.forEach { point ->
                val centre = Offset(
                    (point.x - boardSize.edgeLeft) * scaleX,
                    size.height - (point.y - boardSize.edgeBottom) * scaleY,
                )
                drawCircle(Color.White.copy(alpha = 0.3f), radius, centre)
                drawCircle(OrangeAccent, radius, centre, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

/** Quiet entry right above a size list: "Not sure which size? Check on your board". */
@Composable
internal fun BoardSizeCheckLink(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.testTag("board_size_check_link"),
    ) {
        Icon(Icons.Outlined.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.board_size_check_link), style = MaterialTheme.typography.labelLarge)
    }
}

internal fun boardSizeCheckLabel(brand: BoardBrand, size: BoardSize): String =
    if (brand == BoardBrand.KILTER) BoardConstants.sizeLabel(size.id, size.name)
    else BoardConstants.auroraSizeLabel(brand, size)
