package com.cruxcoach.android.ui.board

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.LedHoldColors
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.data.repository.AuroraPlacement
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.withTimeoutOrNull

/** Bundled board-size IDs that have a WebP asset in board_images/. */
private val BUNDLED_BOARD_SIZES = setOf(7L, 8L, 10L, 14L, 27L, 28L)

/**
 * Single-entry bitmap cache keyed by board-size ID.
 * Each board type has one combined image: board_images/board_{sizeId}.webp
 */
internal object BoardImageCache {
    @Volatile
    private var cachedSizeId: Long = -1L
    @Volatile
    private var cachedBitmap: ImageBitmap? = null

    fun get(sizeId: Long): ImageBitmap? =
        if (sizeId == cachedSizeId) cachedBitmap else null

    suspend fun getOrDecode(
        sizeId: Long,
        assetManager: android.content.res.AssetManager
    ): ImageBitmap? {
        if (sizeId == cachedSizeId) return cachedBitmap
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val bitmap = tryDecodeAsset(assetManager, "board_images/board_${sizeId}.webp")
            cachedSizeId = sizeId
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
    placements: Map<Int, AuroraPlacement>,
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

    val sizeId = boardSize?.id ?: 10L
    val hasBundledImage = sizeId in BUNDLED_BOARD_SIZES

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
        ) {
            // Layer 1: Board image — loaded from bundled WebP asset
            val assetManager = context.assets
            var boardBitmap by remember(sizeId) {
                mutableStateOf(if (hasBundledImage) BoardImageCache.get(sizeId) else null)
            }
            if (hasBundledImage) {
                LaunchedEffect(sizeId) {
                    if (boardBitmap == null) {
                        boardBitmap = BoardImageCache.getOrDecode(sizeId, assetManager)
                    }
                }
            }

            boardBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.cd_board_image),
                    modifier = Modifier.fillMaxSize(),
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

            // Touch handler (only when interactive)
            val touchModifier = if (onHoldTapped != null) {
                Modifier.pointerInput(placements, boardSize) {
                    val findNearest: (Offset) -> Int? = { offset ->
                        val xS = size.width.toFloat() / boardWidth
                        val yS = size.height.toFloat() / boardHeight
                        val tapRadius = xS * 6f
                        var nearest: Int? = null
                        var nearestDist = Float.MAX_VALUE
                        placements.values.forEach { placement ->
                            val px = (placement.x.toFloat() - edgeLeft) * xS
                            val py = size.height - (placement.y.toFloat() - edgeBottom) * yS
                            val dist = kotlin.math.sqrt(
                                (offset.x - px) * (offset.x - px) +
                                (offset.y - py) * (offset.y - py)
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
                            // Short tap → toggle hold
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
                                event.changes.forEach { it.consume() }
                                val pos = event.changes.firstOrNull()?.position ?: break
                                val newHold = findNearest(pos)
                                if (newHold != null) currentHold = newHold
                                dragPreviewHoldId = currentHold
                            } while (event.changes.any { it.pressed })

                            if (isMoving && currentHold != null && currentHold != originHold) {
                                // Move: deselect origin, select destination
                                onHoldTapped(originHold!!)
                                onHoldTapped(currentHold)
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

            Canvas(modifier = Modifier.fillMaxSize().then(touchModifier)) {
                if (placements.isEmpty()) return@Canvas

                val xScale = size.width / boardWidth
                val yScale = size.height / boardHeight

                placements.values.forEach { placement ->
                    val px = (placement.x.toFloat() - edgeLeft) * xScale
                    val py = size.height - (placement.y.toFloat() - edgeBottom) * yScale

                    if (px !in 0f..size.width || py !in 0f..size.height) return@forEach

                    val pid = placement.placementId.toInt()

                    // Layer 3: Heatmap circles (filled, semi-transparent)
                    if (heatmapData != null) {
                        val intensity = heatmapData[pid]
                        if (intensity != null && intensity > 0f) {
                            val heatColor = heatmapColor(intensity)
                            // Larger blurred circle for glow effect
                            drawCircle(
                                color = heatColor.copy(alpha = 0.45f),
                                radius = xScale * 7f,
                                center = Offset(px, py),
                                style = Fill
                            )
                            // Inner solid circle
                            drawCircle(
                                color = heatColor.copy(alpha = 0.85f),
                                radius = xScale * 4.5f,
                                center = Offset(px, py),
                                style = Fill
                            )
                        }
                    }

                    // Layer 4: Active hold circles (existing behavior)
                    if (holds.isNotEmpty()) {
                        val activeHold = activeHoldMap[pid]
                        if (activeHold != null) {
                            val alpha = if (previewMode && currentFrameSet != null) {
                                if (activeHold.placementId in currentFrameSet) 1.0f else 0.3f
                            } else 1.0f
                            drawActiveHold(px, py, activeHold.roleId, xScale, ledColors, alpha)
                        }
                    }

                    // Layer 5: Selected hold highlight (thick ring + filled dot)
                    // Hide origin hold during move-drag (it's being relocated)
                    if (pid in selectedHolds && pid != dragOriginHoldId) {
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

                    // Layer 6: Long-press drag preview (larger orange indicator)
                    if (dragPreviewHoldId == pid) {
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

/**
 * Draw an active hold with Climbdex-style colored ring.
 * Transparent fill so the board image shows through.
 */
private fun DrawScope.drawActiveHold(x: Float, y: Float, roleId: Int, xScale: Float, ledColors: LedHoldColors, alpha: Float = 1.0f) {
    val color = holdColorForRole(roleId, ledColors).copy(alpha = alpha)
    val radius = xScale * 4f
    val strokeWidth = xScale * 0.8f

    drawCircle(
        color = color,
        radius = radius,
        center = Offset(x, y),
        style = Stroke(width = strokeWidth)
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
