package com.cruxcoach.android.ui.paintbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.board.*
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.domain.board.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun PaintImageEditor(state: PaintBoxState, vm: PaintBoxViewModel) {
    val session = state.imageImport ?: return
    val board = state.document?.board ?: return
    val brand = BoardBrand.fromWire(board.brand)
    val moon = if (brand == BoardBrand.MOONBOARD) rememberMoonBoardAsset(board.layoutId.toLong()) else null
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 360
    val tablet = configuration.smallestScreenWidthDp >= 600
    val quantum = if (brand == BoardBrand.QUANTUM)
        state.boardSize?.id?.let(QuantumBoardModel::fromProductSizeId)
            ?: QuantumBoardModel.fromLayoutId(board.layoutId.toLong()) else null
    val b = state.boardSize
    val left = b?.edgeLeft?.toFloat() ?: 0f
    val bottom = b?.edgeBottom?.toFloat() ?: 0f
    val width = (b?.edgeRight?.toFloat() ?: 144f) - left
    val height = (b?.edgeTop?.toFloat() ?: 156f) - bottom
    val aspect = when {
        moon is MoonBoardAssetState.Ready -> moon.asset.imageAspect
        brand == BoardBrand.MOONBOARD -> BOARD_ASPECT_RATIO
        brand == BoardBrand.QUANTUM -> 1f
        else -> width / height
    }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val points = remember(viewport, moon, board, state.placements, compact, tablet) {
        if (viewport == Size.Zero) emptyMap() else if (brand == BoardBrand.MOONBOARD) {
            val rows = MoonBoardVariant.fromLayoutId(board.layoutId.toLong())?.gridRows ?: MoonBoardVariant.GRID_ROWS
            board.moonHoldIds().mapNotNull { id ->
                val point = when (moon) {
                    is MoonBoardAssetState.Ready -> moon.asset.holdXy[id]?.let { Offset(it.x * viewport.width, it.y * viewport.height) }
                    MoonBoardAssetState.Unavailable -> holdCentre(gridRect(viewport), (id - 1) % 11, (id - 1) / 11, rows)
                    else -> null
                }
                point?.let { id to it }
            }.toMap()
        } else state.placements.mapValues { (_, placement) ->
            paintBoardPoint(placement, viewport, left, bottom, width, height, quantum, compact, tablet)
        }.filterValues { it.x in 0f..viewport.width && it.y in 0f..viewport.height }
    }
    val density = LocalDensity.current
    val marker = when {
        moon is MoonBoardAssetState.Ready -> viewport.width * IMAGE_HOLD_RADIUS_FRACTION / 4f
        brand == BoardBrand.MOONBOARD -> cellSpacing(gridRect(viewport),
            MoonBoardVariant.fromLayoutId(board.layoutId.toLong())?.gridRows ?: 18) * .34f / 4f
        quantum != null -> with(density) { (QUANTUM_DIODE_DIAMETER_DP / 8f).dp.toPx() }
        else -> boardMarkerScale(brand, viewport.width / width, width)
    }
    // The same immutable transform is used for rendering and inverse sampling.
    // A mapping can take longer than a frame. They run one after the other, each for the newest
    // placement, and the holds show the last one that finished: the preview trails the finger a
    // little instead of standing still until the finger rests. Only the mapping of the current
    // placement is handed on (the view model checks), so Apply never takes an older one.
    val request by rememberUpdatedState(Triple(session, viewport, points))
    var trailing by remember(session.bitmap) { mutableStateOf<Map<Int, Int>?>(null) }
    LaunchedEffect(session.bitmap, brand) {
        snapshotFlow { request }.conflate().collect { (placed, size, holds) ->
            if (!placed.ready && size.width > 0 && holds.isNotEmpty()) {
                val colors = withContext(Dispatchers.Default) { PaintImageSampling.sample(placed, size, holds, brand) }
                trailing = colors
                vm.imagePreview(placed, colors)
            }
        }
    }
    val shown = if (session.ready) session.colors else trailing ?: session.colors
    val current by rememberUpdatedState(session)
    val bitmap = remember(session.bitmap) { session.bitmap.asImageBitmap() }
    Canvas(Modifier.aspectRatio(aspect).clipToBounds().testTag("paintbox_image_canvas")
        .onSizeChanged {
            val next = Size(it.width.toFloat(), it.height.toFloat())
            if (next != viewport) { viewport = next; vm.imageTransform(current.transform) }
        }
        .pointerInput(session.bitmap, session.pickingBackground) {
            if (current.pickingBackground) detectTapGestures { point ->
                val imageSize = Size(current.bitmap.width.toFloat(), current.bitmap.height.toFloat())
                PaintImageSampling.pixel(current.bitmap, current.transform.imagePoint(point, imageSize, viewport))
                    ?.let(vm::imageBackground)
            } else detectTransformGestures { centroid, pan, zoom, rotation ->
                vm.imageTransform(current.transform.gesture(centroid, pan, zoom, rotation, viewport))
            }
        }) {
        drawRect(Color(0xff242424))
        val transform = session.transform
        val imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        withTransform({
            translate(transform.center.x * size.width, transform.center.y * size.height)
            rotate(transform.degrees, Offset.Zero)
            val factor = transform.scale(imageSize, size)
            scale(factor, factor, Offset.Zero)
            translate(-imageSize.width / 2, -imageSize.height / 2)
        }) { drawImage(bitmap, filterQuality = FilterQuality.None) }
        points.forEach { (id, point) ->
            drawCircle(Color.White.copy(alpha = .4f), marker, point)
            shown[id]?.let { drawBoardColorOverlay(point, marker, rgb332ToComposeColor(it)) }
        }
    }
}
