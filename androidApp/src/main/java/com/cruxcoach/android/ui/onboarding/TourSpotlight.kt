package com.cruxcoach.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import kotlin.math.roundToInt

internal enum class TourTarget { BOARD, BLUETOOTH, ANGLE, FILTER, OVERFLOW, CLIMB, PROJECT, LOG, QUICK_ATTEMPT, QUICK_SEND, MENU, BACK, LOGBOOK, EDIT, DELETE }

@Stable
internal class TourTargets {
    val bounds = mutableStateMapOf<TourTarget, Rect>()
    var active by mutableStateOf<TourTarget?>(null)
    var menuOpen by mutableStateOf(false)
    var onEnd: (() -> Unit)? = null
}
internal val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/** Measure the real control; never replace its click handler with a tutorial action. */
internal fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed this
    DisposableEffect(targets, target) { onDispose { targets.bounds.remove(target) } }
    onGloballyPositioned { targets.bounds[target] = it.boundsInRoot() }
}

/** Popup menus occupy their own window, so highlight their actual menu item there. */
internal fun Modifier.tourMenuTarget(target: TourTarget): Modifier = composed {
    if (LocalTourTargets.current?.active == target) {
        background(OrangeAccent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .border(2.dp, OrangeAccent, RoundedCornerShape(8.dp))
    } else this
}

@Composable
internal fun TourHost(
    targets: TourTargets,
    target: TourTarget?,
    message: Int,
    onEnd: () -> Unit,
    visible: Boolean = true,
    secondaryTarget: TourTarget? = null,
    content: @Composable () -> Unit,
) {
    SideEffect { targets.active = target; targets.onEnd = onEnd }
    CompositionLocalProvider(LocalTourTargets provides targets) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (visible && !targets.menuOpen && target != null) {
                val bounds = targets.bounds[target]
                if (bounds != null) TourSpotlight(
                    listOfNotNull(bounds, secondaryTarget?.let { targets.bounds[it] }), message, onEnd)

            }
        }
    }
}

/** Four input shields leave only the real highlighted control and skip action touchable. */
@Composable
private fun TourSpotlight(targetsInRoot: List<Rect>, message: Int, onEnd: () -> Unit) {
    BackHandler(onBack = onEnd)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var hintHeight by remember(message) { mutableIntStateOf(0) }
    var skipHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .testTag("tour_spotlight")) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val gap = with(density) { 24.dp.toPx() }
        val holes = targetsInRoot.map { it.translate(-origin).intersect(Rect(0f, 0f, width, height)) }
            .filter { it.width > 0 && it.height > 0 }
        if (holes.isEmpty()) return@BoxWithConstraints
        val target = holes.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
        val below = target.center.y < height / 2
        val skipSpace = maxOf(skipHeight.toFloat(), with(density) { 64.dp.toPx() }) + margin * 2
        val available = (if (below) height - target.bottom - gap - skipSpace
            else target.top - gap - skipSpace).coerceAtLeast(1f)
        val hintY = if (below) target.bottom + gap else (target.top - gap - hintHeight).coerceAtLeast(skipSpace)
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                holes.forEach { addRoundRect(RoundRect(it, CornerRadius(12.dp.toPx()))) }
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.78f))
            holes.forEach { drawRoundRect(OrangeAccent, it.topLeft, it.size, CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx())) }
            holes.forEach { hole ->
            val arrowX = hole.center.x.coerceIn(margin, width - margin)
            val start = Offset(arrowX, if (below) hole.bottom + 4.dp.toPx() else hole.top - 4.dp.toPx())
            val end = start + Offset(0f, if (below) 16.dp.toPx() else -16.dp.toPx())
            drawLine(OrangeAccent, start, end, 2.dp.toPx())
            val direction = if (below) 1 else -1
            drawLine(OrangeAccent, start, start + Offset(-4.dp.toPx(), direction * 5.dp.toPx()), 2.dp.toPx())
            drawLine(OrangeAccent, start, start + Offset(4.dp.toPx(), direction * 5.dp.toPx()), 2.dp.toPx())
            }
        }
        // Separate rectangles are intentional: a full-screen pointer handler would
        // win hit testing inside the hole, preventing the real control from firing.
        spotlightShields(width, height, holes).forEach { shield ->
            Box(Modifier.offset { IntOffset(shield.left.roundToInt(), shield.top.roundToInt()) }
                .size(with(density) { shield.width.toDp() }, with(density) { shield.height.toDp() })
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
        }
        Text(stringResource(message), color = Color.White, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.offset { IntOffset(margin.roundToInt(), hintY.roundToInt()) }
                .width(maxWidth - 32.dp).heightIn(max = with(density) { available.toDp() })
                .onSizeChanged { hintHeight = it.height }
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) { detectTapGestures { } })
        Button(onClick = onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            modifier = Modifier.align(if (below) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(16.dp).widthIn(max = 360.dp).fillMaxWidth()
                .heightIn(min = 48.dp).onSizeChanged { skipHeight = it.height }.testTag("tour_skip")) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tour_skip))
        }
    }
}

/** Partition the screen around every hole; the space between Quicklog buttons stays blocked. */
internal fun spotlightShields(width: Float, height: Float, holes: List<Rect>): List<Rect> {
    val rows = (listOf(0f, height) + holes.flatMap { listOf(it.top, it.bottom) }).distinct().sorted()
    return buildList {
        rows.zipWithNext().forEach { (top, bottom) ->
            var left = 0f
            holes.filter { it.top < bottom && it.bottom > top }.sortedBy { it.left }.forEach { hole ->
                if (hole.left > left) add(Rect(left, top, hole.left, bottom))
                left = maxOf(left, hole.right)
            }
            if (left < width) add(Rect(left, top, width, bottom))
        }
    }
}
