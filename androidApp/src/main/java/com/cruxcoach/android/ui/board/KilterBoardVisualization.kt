package com.cruxcoach.android.ui.board

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.LedHoldColors
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.BoardZoneFilter
import kotlinx.coroutines.withTimeoutOrNull

/** Bundled board-size IDs that have a WebP asset in board_images/.
 *  Original Kilter sizes: 7, 8, 10, 14, 27, 28 (product_id=1).
 *  Homewall sizes: 17, 18, 19, 21, 22, 23, 24, 25, 26, 29 (product_id=7). */
private val BUNDLED_BOARD_SIZES = setOf(
    7L, 8L, 10L, 14L, 27L, 28L,
    17L, 18L, 19L, 21L, 22L, 23L, 24L, 25L, 26L, 29L,
)

/**
 * Single-entry bitmap cache keyed by the full asset path. Each board size has
 * one combined image: Kilter uses board_images/board_<id>.webp, while the other
 * Aurora-family boards are namespaced board_images/<brand>/board_<id>.webp.
 * product_size ids collide across brands, so the path — not the id — is the key.
 */
internal object BoardImageCache {
    @Volatile
    private var cachedPath: String = ""
    @Volatile
    private var cachedBitmap: ImageBitmap? = null

    fun get(path: String): ImageBitmap? =
        if (path == cachedPath) cachedBitmap else null

    /**
     * Decode the first [candidates] path that resolves to an asset, keyed in
     * the cache by the first (most-specific) candidate. The renderer passes
     * the layout-specific composite first and the size-only image as a
     * fallback, so a board not yet regenerated still loads its old asset.
     */
    suspend fun getOrDecode(
        candidates: List<String>,
        assetManager: android.content.res.AssetManager
    ): ImageBitmap? {
        val key = candidates.firstOrNull() ?: return null
        if (key == cachedPath) return cachedBitmap
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val bitmap = candidates.firstNotNullOfOrNull { tryDecodeAsset(assetManager, it) }
            cachedPath = key
            cachedBitmap = bitmap
            bitmap
        }
    }

    private fun tryDecodeAsset(
        assetManager: android.content.res.AssetManager,
        path: String
    ): ImageBitmap? = try {
        assetManager.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (_: java.io.FileNotFoundException) {
        null
    }
}


// Mounting-hole grid dot. The official Aurora apps draw a dot at EVERY
// board position (the `holes`/`placements` grid), which is what makes their
// board read as a complete, full surface; drawing only the climb's holds
// leaves big gaps and looks unfinished. Light + semi-transparent so it sits
// quietly under the LED hold rings on the dark board image (FEAT-031).
private val MountingDotColor = Color(0x40FFFFFF)

// Heatmap color gradient: green → yellow → orange → red
private val HEATMAP_COLORS = listOf(
    Color(0xFF1B5E20), // deep green
    Color(0xFF4CAF50), // green
    Color(0xFFCDDC39), // lime
    Color(0xFFFFEB3B), // yellow
    Color(0xFFFFC107), // amber
    Color(0xFFFF9800), // orange
    Color(0xFFFF5722), // deep orange
    Color(0xFFF44336), // red
    Color(0xFFD32F2F), // dark red
    Color(0xFFB71C1C)  // deepest red
)

/**
 * Climbdex-style Kilter Board visualization with optional heatmap and touch interaction.
 *
 * Layers (bottom to top):
 * 1. Board background (dark)
 * 2. Board photo(s) loaded from bundled assets
 * 3. Heatmap overlay (when heatmapData provided)
 * 4. Canvas overlay with hold circles at correct x/y positions
 * 5. Selected hold highlights (when interactiveMode)
 */
@Composable
internal fun KilterBoardVisualization(
    holds: List<BoardHold>,
    placements: Map<Int, BoardPlacement>,
    boardSize: BoardSize?,
    boardImages: List<BoardImage> = emptyList(),
    ledColors: LedHoldColors = LedHoldColors(),
    previewMode: Boolean = false,
    currentFrameHolds: List<BoardHold>? = null,
    // Heatmap support
    heatmapData: Map<Int, Float>? = null,
    // Interactive hold selection
    selectedHolds: Set<Int> = emptySet(),
    onHoldTapped: ((Int) -> Unit)? = null,
    /**
     * Editor-specific drag callback. When provided, long-press + drag MOVES
     * the role from `fromId` to `toId` (atomic). Without this, the
     * fallback two-tap-cycle behavior runs.
     */
    onHoldMoved: ((fromId: Int, toId: Int) -> Unit)? = null,
    /**
     * Editor mode: draw active holds as solid role-colored discs instead of
     * rings, and suppress the orange selection overlay. The role color IS
     * the selection indicator.
     */
    solidHoldFill: Boolean = false,
    /**
     * Two-finger pinch-to-zoom + pan. Single-finger taps and long-press
     * drags continue to work; tap positions are inverse-transformed so the
     * editor can hit the visually-tapped hold even when zoomed in.
     */
    allowZoom: Boolean = false,
    /** Zone-box overlay (hold search): translucent rectangle in placement
     *  coordinate space. */
    zone: BoardZone? = null,
    /**
     * Zone selection: while true, a one-finger drag frames a rectangle with
     * live preview and [onZoneSelected] fires on release. Replaces hold
     * tapping for the duration of the mode.
     */
    zoneSelectMode: Boolean = false,
    onZoneSelected: ((BoardZone) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val activeHoldMap = remember(holds) {
        holds.associateBy { it.placementId }
    }

    val edgeLeft = boardSize?.edgeLeft?.toFloat() ?: 0f
    val edgeRight = boardSize?.edgeRight?.toFloat() ?: 144f
    val edgeBottom = boardSize?.edgeBottom?.toFloat() ?: 0f
    val edgeTop = boardSize?.edgeTop?.toFloat() ?: 156f
    val boardWidth = edgeRight - edgeLeft
    val boardHeight = edgeTop - edgeBottom
    val aspectRatio = boardWidth / boardHeight

    val brand = boardSize?.boardBrand ?: BoardBrand.KILTER
    val sizeId = boardSize?.id ?: 10L
    // Kilter's bundled set is enumerated; the other Aurora-family boards
    // attempt-and-fallback — the asset is present for listed sizes, and a miss
    // decodes to null (placements-only), never a crash or a blank lock-up.
    val hasBundledImage = when {
        brand == BoardBrand.KILTER -> sizeId in BUNDLED_BOARD_SIZES
        brand.usesAuroraProtocol -> true
        else -> false
    }
    // The active layout (from the size's set images) picks the layout-specific
    // composite — Tension TB2 Mirror vs Spray share a size but not their holds.
    val layoutId = boardImages.firstOrNull()?.layoutId
    val boardImageCandidates = boardImageCandidatePaths(brand, sizeId, layoutId)
    val boardImagePath = boardImageCandidates.first()

    // Two-finger zoom/pan state. Kept at composable scope so taps in the
    // child pointerInput can inverse-transform their positions to canvas
    // space — the touch grid stays accurate at any zoom level.
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    val zoomState by rememberUpdatedState(zoomScale to zoomOffset)

    val zoomModifier: Modifier = if (allowZoom) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f) {
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                        }
                        if (pan != Offset.Zero) {
                            zoomOffset += pan
                        }
                        // Reset translation when fully zoomed out so the
                        // board snaps back to its centered home position.
                        if (zoomScale <= 1.001f) zoomOffset = Offset.Zero
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    } else Modifier

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .then(zoomModifier)
        ) {
            // Layer 1: Board image — loaded from bundled WebP asset
            val assetManager = context.assets
            var boardBitmap by remember(boardImagePath) {
                mutableStateOf(if (hasBundledImage) BoardImageCache.get(boardImagePath) else null)
            }
            if (hasBundledImage) {
                LaunchedEffect(boardImagePath) {
                    if (boardBitmap == null) {
                        boardBitmap = BoardImageCache.getOrDecode(boardImageCandidates, assetManager)
                    }
                }
            }

            // graphicsLayer applies the zoom/pan transform to both the
            // board photo and the holds canvas so they stay aligned.
            val zoomLayer: Modifier = if (allowZoom) {
                Modifier.graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale,
                    translationX = zoomOffset.x,
                    translationY = zoomOffset.y,
                )
            } else Modifier

            boardBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.cd_board_image),
                    modifier = Modifier.fillMaxSize().then(zoomLayer),
                    contentScale = ContentScale.Fit
                )
            }

            // Layer 3+4+5: Heatmap + holds + selection overlay
            val currentFrameSet = remember(currentFrameHolds) {
                currentFrameHolds?.map { it.placementId }?.toSet()
            }
            val selectedColor = OrangeAccent

            // Drag-preview state: hold ID highlighted during long-press drag
            var dragPreviewHoldId by remember { mutableStateOf<Int?>(null) }
            // Origin hold being moved (hide its selection circle during drag)
            var dragOriginHoldId by remember { mutableStateOf<Int?>(null) }
            // Keep fresh reference to selectedHolds for gesture coroutine
            val currentSelectedHolds by rememberUpdatedState(selectedHolds)

            // Live zone-drag preview (board coordinate space)
            var dragZone by remember { mutableStateOf<BoardZone?>(null) }

            // Touch handler (only when interactive)
            val touchModifier = if (zoneSelectMode && onZoneSelected != null) {
                Modifier.pointerInput(zoneSelectMode, boardSize) {
                    val toCanvasSpace: (Offset) -> Offset = { screen ->
                        val (s, off) = zoomState
                        if (!allowZoom || s == 1f) {
                            screen
                        } else {
                            val pivotX = size.width / 2f
                            val pivotY = size.height / 2f
                            Offset(
                                x = (screen.x - pivotX - off.x) / s + pivotX,
                                y = (screen.y - pivotY - off.y) / s + pivotY,
                            )
                        }
                    }
                    // Canvas position → board placement coordinates
                    val toZone: (Offset, Offset) -> BoardZone = { a, b ->
                        val xS = size.width.toFloat() / boardWidth
                        val yS = size.height.toFloat() / boardHeight
                        val ax = (a.x / xS + edgeLeft).toLong()
                        val ay = ((size.height - a.y) / yS + edgeBottom).toLong()
                        val bx = (b.x / xS + edgeLeft).toLong()
                        val by = ((size.height - b.y) / yS + edgeBottom).toLong()
                        BoardZoneFilter.zoneFromCorners(ax, ay, bx, by)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = toCanvasSpace(down.position)
                        var current = start
                        do {
                            val event = awaitPointerEvent()
                            // Hand off to zoom/pan on a second finger.
                            if (event.changes.count { it.pressed } >= 2) {
                                dragZone = null
                                return@awaitEachGesture
                            }
                            event.changes.forEach { it.consume() }
                            event.changes.firstOrNull()?.let { current = toCanvasSpace(it.position) }
                            dragZone = toZone(start, current)
                        } while (event.changes.any { it.pressed })
                        val moved = (current - start).getDistance() > viewConfiguration.touchSlop * 2
                        val result = toZone(start, current)
                        dragZone = null
                        if (moved) onZoneSelected(result)
                    }
                }
            } else if (onHoldTapped != null) {
                Modifier.pointerInput(placements, boardSize) {
                    // Inverse the screen-space tap by the active zoom/pan
                    // transform so findNearest works in canvas-space (which
                    // is where the placements live). Pivot defaults to the
                    // center of the composable for graphicsLayer.
                    val toCanvasSpace: (Offset) -> Offset = { screen ->
                        val (s, off) = zoomState
                        if (!allowZoom || s == 1f) {
                            screen
                        } else {
                            val pivotX = size.width / 2f
                            val pivotY = size.height / 2f
                            Offset(
                                x = (screen.x - pivotX - off.x) / s + pivotX,
                                y = (screen.y - pivotY - off.y) / s + pivotY,
                            )
                        }
                    }
                    val findNearest: (Offset) -> Int? = { offset ->
                        val pos = toCanvasSpace(offset)
                        val xS = size.width.toFloat() / boardWidth
                        val yS = size.height.toFloat() / boardHeight
                        val tapRadius = xS * 6f
                        var nearest: Int? = null
                        var nearestDist = Float.MAX_VALUE
                        placements.values.forEach { placement ->
                            val px = (placement.x.toFloat() - edgeLeft) * xS
                            val py = size.height - (placement.y.toFloat() - edgeBottom) * yS
                            val dist = kotlin.math.sqrt(
                                (pos.x - px) * (pos.x - px) +
                                (pos.y - py) * (pos.y - py)
                            )
                            if (dist < tapRadius && dist < nearestDist) {
                                nearestDist = dist
                                nearest = placement.placementId.toInt()
                            }
                        }
                        nearest
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val upOrNull = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }

                        if (upOrNull != null) {
                            // Short tap → toggle hold.
                            findNearest(down.position)?.let { onHoldTapped(it) }
                        } else {
                            // Long press → show preview, allow drag
                            val originHold = findNearest(down.position)
                            val isMoving = originHold != null && originHold in currentSelectedHolds
                            var currentHold = originHold
                            dragPreviewHoldId = currentHold
                            if (isMoving) dragOriginHoldId = originHold

                            do {
                                val event = awaitPointerEvent()
                                // Hand off to the zoom/pan handler if a
                                // second finger lands during the drag.
                                if (event.changes.count { it.pressed } >= 2) {
                                    dragPreviewHoldId = null
                                    dragOriginHoldId = null
                                    return@awaitEachGesture
                                }
                                event.changes.forEach { it.consume() }
                                val pos = event.changes.firstOrNull()?.position ?: break
                                val newHold = findNearest(pos)
                                if (newHold != null) currentHold = newHold
                                dragPreviewHoldId = currentHold
                            } while (event.changes.any { it.pressed })

                            if (isMoving && currentHold != null && currentHold != originHold) {
                                // Move: prefer the atomic onHoldMoved callback when
                                // the host supplies one (editor mode). Without it,
                                // fall back to two-tap-cycle which only works
                                // sensibly for the legacy non-editor browse case.
                                if (onHoldMoved != null) {
                                    onHoldMoved(originHold!!, currentHold)
                                } else {
                                    onHoldTapped(originHold!!)
                                    onHoldTapped(currentHold)
                                }
                            } else if (!isMoving && currentHold != null) {
                                // New selection
                                onHoldTapped(currentHold)
                            }
                            // If moving but released on same hold → no change
                            dragPreviewHoldId = null
                            dragOriginHoldId = null
                        }
                    }
                }
            } else Modifier

            Canvas(modifier = Modifier.fillMaxSize().then(touchModifier).then(zoomLayer)) {
                if (placements.isEmpty()) return@Canvas

                val xScale = size.width / boardWidth
                val yScale = size.height / boardHeight

                // Zone-box overlay: below the hold circles so selections stay
                // readable on top. Live drag preview wins over the committed
                // zone. Padded by one hold radius so the boundary holds sit
                // visually inside the rectangle.
                val zoneToDraw = dragZone ?: zone
                if (zoneToDraw != null) {
                    val pad = xScale * 5f
                    val left = ((zoneToDraw.minX.toFloat() - edgeLeft) * xScale - pad).coerceAtLeast(0f)
                    val right = ((zoneToDraw.maxX.toFloat() - edgeLeft) * xScale + pad).coerceAtMost(size.width)
                    val top = (size.height - (zoneToDraw.maxY.toFloat() - edgeBottom) * yScale - pad).coerceAtLeast(0f)
                    val bottom = (size.height - (zoneToDraw.minY.toFloat() - edgeBottom) * yScale + pad).coerceAtMost(size.height)
                    drawRect(
                        color = OrangeAccent.copy(alpha = 0.15f),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                    )
                    drawRect(
                        color = OrangeAccent,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                placements.values.forEach { placement ->
                    val px = (placement.x.toFloat() - edgeLeft) * xScale
                    val py = size.height - (placement.y.toFloat() - edgeBottom) * yScale

                    if (px !in 0f..size.width || py !in 0f..size.height) return@forEach

                    val pid = placement.placementId.toInt()

                    // Layer 2.5: Mounting-hole grid — a faint dot at every
                    // placement so a board WITHOUT a bundled composite still
                    // reads as a full surface instead of a few floating holds.
                    // When the composite is present it already shows every
                    // hold, so the dots would just be noise — skip them.
                    if (boardBitmap == null) {
                        drawCircle(
                            color = MountingDotColor,
                            radius = xScale * 1.25f,
                            center = Offset(px, py),
                            style = Fill,
                        )
                    }

                    // Layer 3: Heatmap markers — small tinted dots that
                    // hint at popularity without obscuring the hold image.
                    // Skip very faint placements so the board doesn't get
                    // washed out by every climb that ever used the layout.
                    if (heatmapData != null) {
                        val intensity = heatmapData[pid]
                        if (intensity != null && intensity >= 0.15f) {
                            val heatColor = heatmapColor(intensity)
                            // Soft halo — barely visible at low intensity,
                            // brighter for hot holds. Stays smaller than
                            // the hold so the photo still reads through.
                            drawCircle(
                                color = heatColor.copy(alpha = 0.18f + 0.20f * intensity),
                                radius = xScale * 4.5f,
                                center = Offset(px, py),
                                style = Fill,
                            )
                            // Tiny core dot — the actual "this is hot" marker.
                            drawCircle(
                                color = heatColor.copy(alpha = 0.55f + 0.30f * intensity),
                                radius = xScale * 1.8f,
                                center = Offset(px, py),
                                style = Fill,
                            )
                        }
                    }

                    // Layer 4: Active hold circles (existing behavior — colored ring).
                    if (holds.isNotEmpty()) {
                        val activeHold = activeHoldMap[pid]
                        if (activeHold != null) {
                            val alpha = if (previewMode && currentFrameSet != null) {
                                if (activeHold.placementId in currentFrameSet) 1.0f else 0.3f
                            } else 1.0f
                            // Drag origin hold is hidden during a long-press move.
                            val skip = solidHoldFill && pid == dragOriginHoldId
                            if (!skip) drawActiveHold(px, py, activeHold.roleId, xScale, ledColors, alpha)
                        }
                    }

                    // Layer 5: Selected hold highlight (thick orange ring + filled dot).
                    // Suppressed in `solidHoldFill` (editor) mode — the role-color disc
                    // already conveys "this hold is selected" without an extra overlay.
                    // Hide origin hold during move-drag regardless (it's being relocated).
                    if (!solidHoldFill && pid in selectedHolds && pid != dragOriginHoldId) {
                        drawCircle(
                            color = selectedColor,
                            radius = xScale * 5f,
                            center = Offset(px, py),
                            style = Stroke(width = xScale * 1.2f)
                        )
                        drawCircle(
                            color = selectedColor.copy(alpha = 0.4f),
                            radius = xScale * 3f,
                            center = Offset(px, py),
                            style = Fill
                        )
                    }

                    // Layer 6: Long-press drag preview. In editor mode, show
                    // the role-color of the hold being moved as a translucent
                    // disc at the drop target — gives the user a "where will
                    // it land + with which role" cue without orange noise.
                    // In legacy mode (browse), keep the orange indicator.
                    if (dragPreviewHoldId == pid) {
                        if (solidHoldFill && dragOriginHoldId != null) {
                            val movingRole = activeHoldMap[dragOriginHoldId!!]?.roleId
                            val previewColor = movingRole
                                ?.let { holdColorForRole(it, ledColors) }
                                ?: selectedColor
                            drawCircle(
                                color = previewColor.copy(alpha = 0.55f),
                                radius = xScale * 5f,
                                center = Offset(px, py),
                                style = Fill,
                            )
                        } else {
                            drawCircle(
                                color = selectedColor,
                                radius = xScale * 6f,
                                center = Offset(px, py),
                                style = Stroke(width = xScale * 1.5f)
                            )
                            drawCircle(
                                color = selectedColor.copy(alpha = 0.3f),
                                radius = xScale * 4f,
                                center = Offset(px, py),
                                style = Fill
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draw an active hold — climbdex-style colored ring with a transparent
 * fill so the board image shows through. The role colour itself is the
 * "selected" affordance; in editor mode the orange selection ring overlay
 * (Layer 5) is suppressed via `solidHoldFill = true` on the parent.
 */
private fun DrawScope.drawActiveHold(
    x: Float,
    y: Float,
    roleId: Int,
    xScale: Float,
    ledColors: LedHoldColors,
    alpha: Float = 1.0f,
) {
    val color = holdColorForRole(roleId, ledColors).copy(alpha = alpha)
    val radius = xScale * 4f
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(x, y),
        style = Stroke(width = xScale * 1.2f),
    )
}

/** Resolve hold color from user's LED settings via RGB332 palette. */
private fun holdColorForRole(roleId: Int, ledColors: LedHoldColors): Color {
    return rgb332ToComposeColor(ledColors.colorForRole(roleId))
}

/** Map normalized intensity (0..1) to heatmap color gradient. */
private fun heatmapColor(intensity: Float): Color {
    val idx = (intensity * (HEATMAP_COLORS.size - 1)).toInt().coerceIn(0, HEATMAP_COLORS.size - 1)
    return HEATMAP_COLORS[idx]
}
