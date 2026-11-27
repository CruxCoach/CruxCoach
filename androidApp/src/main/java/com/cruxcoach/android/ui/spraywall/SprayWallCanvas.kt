package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.spraywall.HoldOutline
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayWallGeometry
import kotlin.math.roundToInt

/** How one hold is drawn on the wall photo. */
/** Drawn in this order, so faint rings sit below and the picked hold on top. */
enum class SprayHoldLook { FAINT, SUGGESTED, PLAIN, GONE, ROLE, PICKED }

data class SprayCanvasHold(
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val look: SprayHoldLook,
    val role: SprayHoldRole? = null,
    /** The hold's shape (see HoldOutline); null draws a circle. */
    val outline: List<Float>? = null,
)

/** Screen colours for the hold roles, in the usual board convention. */
fun SprayHoldRole.color(): Color = when (this) {
    SprayHoldRole.START -> Color(0xFF00D84A)
    SprayHoldRole.HAND -> Color(0xFF00B8FF)
    SprayHoldRole.FOOT -> Color(0xFFFFB300)
    SprayHoldRole.FINISH -> Color(0xFFFF2BD6)
}

/** Touch callbacks get photo coordinates (0..1) and the hold under the finger, if any. */
class SprayCanvasGestures(
    val onTap: ((x: Float, y: Float, holdId: Long?) -> Unit)? = null,
    val onLongPress: ((x: Float, y: Float, holdId: Long?) -> Unit)? = null,
    /** A drag that starts on this hold moves it instead of panning the view. */
    val draggableHoldId: Long? = null,
    val onDragStart: () -> Unit = {},
    val onDrag: (x: Float, y: Float) -> Unit = { _, _ -> },
    val onDragEnd: () -> Unit = {},
    /** After a long press the same touch keeps going as a drag (onDrag without onDragStart). */
    val dragAfterLongPress: Boolean = false,
)

private const val MAX_ZOOM = 6f
private const val TOUCH_SLOP_DP = 18f

/**
 * The wall photo with its holds drawn on top, zoomable with two fingers and
 * pannable once zoomed. Holds are drawn in photo space, so they stay on their
 * spot at any zoom; ring widths stay constant on screen.
 */
@Composable
fun SprayWallCanvas(
    photo: ImageBitmap?,
    photoWidth: Int,
    photoHeight: Int,
    holds: List<SprayCanvasHold>,
    modifier: Modifier = Modifier,
    gestures: SprayCanvasGestures = SprayCanvasGestures(),
    dimPhoto: Boolean = false,
    /** Off for a static preview, e.g. inside a scrolling list. */
    interactive: Boolean = true,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val slopPx = with(density) { TOUCH_SLOP_DP.dp.toPx() }
    val ringPx = with(density) { 2.5.dp.toPx() }
    val currentHolds by rememberUpdatedState(holds)
    val currentGestures by rememberUpdatedState(gestures)
    val description = stringResource(R.string.spray_cd_photo)

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .testTag("spray_wall_canvas"),
    ) {
        val fit = SprayWallGeometry.fit(size.width.toFloat(), size.height.toFloat(), photoWidth, photoHeight)
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (!interactive) Modifier else Modifier.pointerInput(photoWidth, photoHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        val g = currentGestures
                        val f = SprayWallGeometry.fit(size.width.toFloat(), size.height.toFloat(), photoWidth, photoHeight)
                        fun photoAt(p: Offset) = SprayWallGeometry.toPhoto(p.x, p.y, f, zoom, panX, panY)
                        fun holdAt(p: Offset): Long? = photoAt(p)?.let { (nx, ny) ->
                            SprayWallGeometry.holdAt(currentHolds, nx, ny, f, zoom, slopPx, { it.x }, { it.y }, { it.radius })?.id
                        }
                        val startHold = holdAt(down.position)
                        val dragsHold = g.draggableHoldId != null && startHold == g.draggableHoldId

                        // Phase 1: a tap, a long press, a drag or a second finger?
                        var outcome = "longpress"
                        var upAt = down.position
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) { outcome = "multi"; break }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                if (!change.pressed) { outcome = "tap"; upAt = change.position; break }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    outcome = "move"; break
                                }
                            }
                        }
                        when (outcome) {
                            "tap" -> {
                                photoAt(upAt)?.let { (nx, ny) -> g.onTap?.invoke(nx, ny, holdAt(upAt)) }
                                return@awaitEachGesture
                            }
                            "longpress" -> {
                                val p = photoAt(down.position)
                                if (p != null && g.onLongPress != null) {
                                    g.onLongPress.invoke(p.first, p.second, startHold)
                                    if (g.dragAfterLongPress) {
                                        dragLoop(down.id) { pos ->
                                            val (nx, ny) = SprayWallGeometry.toPhotoClamped(pos.x, pos.y, f, zoom, panX, panY)
                                            g.onDrag(nx, ny)
                                        }
                                        g.onDragEnd()
                                        return@awaitEachGesture
                                    }
                                }
                                // Otherwise the touch carries on as a pan / zoom.
                            }
                            "move" -> if (dragsHold) {
                                g.onDragStart()
                                dragLoop(down.id) { pos ->
                                    val (nx, ny) = SprayWallGeometry.toPhotoClamped(pos.x, pos.y, f, zoom, panX, panY)
                                    g.onDrag(nx, ny)
                                }
                                g.onDragEnd()
                                return@awaitEachGesture
                            }
                        }
                        // Pan and pinch-zoom until every finger is up.
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                            val c = if (centroid == Offset.Unspecified) Offset(size.width / 2f, size.height / 2f) else centroid
                            val nx = c.x - (c.x - panX) * (newZoom / zoom) + pan.x
                            val ny = c.y - (c.y - panY) * (newZoom / zoom) + pan.y
                            val (cx, cy) = SprayWallGeometry.clampPan(nx, ny, newZoom, size.width.toFloat(), size.height.toFloat())
                            zoom = newZoom
                            panX = cx
                            panY = cy
                            // At 1x a single finger is left to a scrolling parent.
                            if (newZoom > 1f || event.changes.count { it.pressed } > 1) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
                }),
        ) {
            withTransform({
                translate(panX, panY)
                scale(zoom, zoom, pivot = Offset.Zero)
            }) {
                if (photo != null) {
                    drawImage(
                        image = photo,
                        dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                    )
                    if (dimPhoto) {
                        drawRect(Color.Black.copy(alpha = 0.35f), Offset(fit.left, fit.top), Size(fit.width, fit.height))
                    }
                }
                val stroke = ringPx / zoom
                // Faint and plain rings first, so climb holds sit on top.
                currentHolds.sortedBy { it.look.ordinal }.forEach { drawHold(it, fit, stroke) }
            }
        }
        if (photo == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (zoom > 1.01f) {
            FilledTonalIconButton(
                onClick = { zoom = 1f; panX = 0f; panY = 0f },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("spray_zoom_reset"),
            ) { Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.spray_zoom_reset)) }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragLoop(
    pointer: androidx.compose.ui.input.pointer.PointerId,
    onMove: (Offset) -> Unit,
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointer } ?: break
        if (!change.pressed) break
        onMove(change.position)
        change.consume()
    }
}

private fun DrawScope.drawHold(hold: SprayCanvasHold, fit: SprayWallGeometry.Fit, stroke: Float) {
    val center = Offset(fit.left + hold.x * fit.width, fit.top + hold.y * fit.height)
    val r = hold.radius * fit.width
    val halo = Color.Black.copy(alpha = 0.55f)
    // A hold with a known shape is drawn as that shape, else as a circle.
    val shape = hold.outline?.takeIf { HoldOutline.isValid(it) }?.let { ring ->
        Path().apply {
            moveTo(center.x + ring[0] * r, center.y + ring[1] * r)
            for (i in 2 until ring.size step 2) lineTo(center.x + ring[i] * r, center.y + ring[i + 1] * r)
            close()
        }
    }
    fun fill(color: Color) = if (shape != null) drawPath(shape, color) else drawCircle(color, r, center)
    fun line(color: Color, width: Float, dash: PathEffect? = null) =
        if (shape != null) drawPath(shape, color, style = Stroke(width, pathEffect = dash, join = StrokeJoin.Round))
        else drawCircle(color, r, center, style = Stroke(width, pathEffect = dash))
    when (hold.look) {
        SprayHoldLook.FAINT -> line(Color.White.copy(alpha = 0.45f), stroke * 0.6f)
        SprayHoldLook.SUGGESTED -> {
            fill(Color(0xFFFFA000).copy(alpha = 0.18f))
            if (shape != null) {
                // Dashes on many small shapes turn into noise: a thin solid line reads better.
                line(Color(0xFFFFB300), stroke * 0.8f)
            } else {
                val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 2.2f, stroke * 1.6f))
                line(halo, stroke * 1.8f, dash)
                line(Color(0xFFFFB300), stroke, dash)
            }
        }
        SprayHoldLook.PLAIN -> {
            line(halo, stroke * 2f)
            line(Color.White, stroke)
        }
        SprayHoldLook.PICKED -> {
            fill(Color(0xFFFFEB3B).copy(alpha = 0.25f))
            line(halo, stroke * 2.6f)
            line(Color(0xFFFFEB3B), stroke * 1.6f)
        }
        SprayHoldLook.ROLE -> {
            val color = hold.role?.color() ?: Color.White
            // A traced hold is coloured in, so the climb reads at a glance.
            if (shape != null) fill(color.copy(alpha = 0.3f))
            line(halo, stroke * 2.4f)
            line(color, stroke * 1.4f)
        }
        SprayHoldLook.GONE -> {
            val color = (hold.role?.color() ?: Color.White).copy(alpha = 0.8f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 3f, stroke * 2.5f))
            line(halo, stroke * 2f, dash)
            line(color, stroke * 1.2f, dash)
        }
    }
}
