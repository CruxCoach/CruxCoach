package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.math.min
import kotlin.math.roundToInt

// Role codes carried in a MoonBoard climb's `frames` string
// (p{holdId}r{roleCode} pairs) — Aurora-aligned, see MoonBoardFrameEncoder.
private const val MB_ROLE_START = 42
private const val MB_ROLE_HAND = 43
private const val MB_ROLE_FINISH = 44

// MoonBoard problem-marking convention: green start, blue hand, red finish.
private val MoonBoardStartColor = Color(0xFF2FB84A)
private val MoonBoardHandColor = Color(0xFF2F6BE0)
private val MoonBoardFinishColor = Color(0xFFE23B36)

// Generic (no-photo) raster palette.
private val MoonBoardPanelColor = Color(0xFFEAE1CC)
private val MoonBoardGridDotColor = Color(0x40202020)

/** Board card aspect (width / height) for the generic (no-photo) raster. */
private const val BOARD_ASPECT_RATIO = 0.65f

/** Ring radius for a climb hold on a real board image, as a fraction of
 *  the image width — sized to encircle a physical MoonBoard hold. */
private const val IMAGE_HOLD_RADIUS_FRACTION = 0.028f

/**
 * MoonBoard climb visualization (FEAT-027).
 *
 * [MoonBoardAssetState.Ready] draws the real board image full-bleed and
 * highlights each climb hold at its measured position from the asset's
 * coordinate map. [MoonBoardAssetState.Unavailable] — variants without a
 * bundled image (Masters 2017 / 2019) — draws a generic, procedurally-
 * rendered 11x18 grid instead. [MoonBoardAssetState.Loading] draws a
 * blank card, so the procedural grid never flashes before the real
 * image decodes on first open.
 *
 * When [onHoldTapped] is supplied the board becomes INTERACTIVE (the climb
 * editor): a tap maps to the nearest lattice hold and is reported back so the
 * caller can cycle its role; [editable] also draws a faint tappable lattice
 * over the photo. Without [onHoldTapped] it is display-only (detail screen).
 */
@Composable
internal fun MoonBoardVisualization(
    frames: String,
    assetState: MoonBoardAssetState,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onHoldTapped: ((holdId: Int) -> Unit)? = null,
) {
    val climbHolds = remember(frames) { MoonBoardFrameEncoder.parseHolds(frames) }
    val aspect = when (assetState) {
        is MoonBoardAssetState.Ready -> assetState.asset.imageAspect
        else -> BOARD_ASPECT_RATIO
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val showLattice = editable || onHoldTapped != null

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onSizeChanged { boxSize = it }
                .let { base ->
                    if (onHoldTapped == null) base
                    else base.pointerInput(assetState) {
                        detectTapGestures { offset ->
                            holdIdAt(offset, boxSize, assetState)?.let(onHoldTapped)
                        }
                    }
                },
        ) {
            when (assetState) {
                is MoonBoardAssetState.Ready -> {
                    val asset = assetState.asset
                    Image(
                        bitmap = asset.image,
                        contentDescription = stringResource(R.string.cd_moonboard_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (showLattice) drawTappableLatticeMapped(asset)
                        drawClimbHoldsMapped(asset, climbHolds)
                    }
                }

                MoonBoardAssetState.Unavailable -> {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val grid = gridRect(size)
                        drawGenericRaster(grid)
                        drawClimbHolds(grid, climbHolds)
                    }
                }

                // Loading: blank card — the real image follows within a
                // frame or two; no procedural-grid flash.
                MoonBoardAssetState.Loading -> Unit
            }
        }
    }
}

/**
 * Map a tap (in [boxSize] pixel space) to the nearest MoonBoard hold id, or
 * null if the tap is too far from any lattice point. Ready → nearest measured
 * coordinate from the asset map; Unavailable → nearest 11x18 lattice point.
 */
private fun holdIdAt(offset: Offset, boxSize: IntSize, assetState: MoonBoardAssetState): Int? {
    if (boxSize.width <= 0 || boxSize.height <= 0) return null
    val nx = offset.x / boxSize.width
    val ny = offset.y / boxSize.height
    if (nx !in 0f..1f || ny !in 0f..1f) return null
    return when (assetState) {
        is MoonBoardAssetState.Ready -> {
            // Nearest measured hold within ~1.6× the marker radius.
            val threshold = IMAGE_HOLD_RADIUS_FRACTION * 1.6f
            assetState.asset.holdXy.minByOrNull { (_, p) ->
                val dx = p.x - nx; val dy = p.y - ny; dx * dx + dy * dy
            }?.takeIf { (_, p) ->
                val dx = p.x - nx; val dy = p.y - ny
                kotlin.math.sqrt(dx * dx + dy * dy) <= threshold
            }?.key
        }
        else -> {
            // Invert the linear lattice (generic raster). holdId uses the
            // universal (row-1)*11 + col + 1 numbering.
            val grid = gridRect(Size(boxSize.width.toFloat(), boxSize.height.toFloat()))
            val colStep = grid.width / (MoonBoardVariant.GRID_COLUMNS - 1)
            val rowStep = grid.height / (MoonBoardVariant.GRID_ROWS - 1)
            val px = offset.x; val py = offset.y
            val col = ((px - grid.left) / colStep).roundToInt()
            val rowIndex = (MoonBoardVariant.GRID_ROWS - 1) - ((py - grid.top) / rowStep).roundToInt()
            if (col !in 0 until MoonBoardVariant.GRID_COLUMNS ||
                rowIndex !in 0 until MoonBoardVariant.GRID_ROWS
            ) return null
            // Reject taps that land between lattice points (> half a cell away).
            val centre = holdCentre(grid, col, rowIndex)
            if (kotlin.math.abs(px - centre.x) > colStep / 2 ||
                kotlin.math.abs(py - centre.y) > rowStep / 2
            ) return null
            rowIndex * MoonBoardVariant.GRID_COLUMNS + col + 1
        }
    }
}

/** Faint tappable dot at each measured hold position — editor affordance so
 *  the user sees where holds can be toggled on the photo. */
private fun DrawScope.drawTappableLatticeMapped(asset: MoonBoardRenderAsset) {
    val radius = size.width * IMAGE_HOLD_RADIUS_FRACTION * 0.4f
    asset.holdXy.values.forEach { p ->
        drawCircle(
            color = MoonBoardGridDotColor,
            radius = radius,
            center = Offset(p.x * size.width, p.y * size.height),
            style = Fill,
        )
    }
}

/**
 * Bounding box of the 11x18 hold lattice (centre of A1 to centre of
 * K18) for the generic raster — inset from the card edges.
 */
private fun gridRect(size: Size): Rect = Rect(
    left = size.width * 0.09f,
    top = size.height * 0.055f,
    right = size.width * 0.94f,
    bottom = size.height * 0.955f,
)

/**
 * Pixel centre of the hold at [column] (0 = A .. 10 = K) and [rowIndex]
 * (0 = row 1 at the bottom .. 17 = row 18 at the top).
 */
private fun holdCentre(grid: Rect, column: Int, rowIndex: Int): Offset {
    val x = grid.left + column * grid.width / (MoonBoardVariant.GRID_COLUMNS - 1)
    val y = grid.top + (MoonBoardVariant.GRID_ROWS - 1 - rowIndex) *
        grid.height / (MoonBoardVariant.GRID_ROWS - 1)
    return Offset(x, y)
}

/** Spacing between adjacent lattice points (smaller of the two axes). */
private fun cellSpacing(grid: Rect): Float = min(
    grid.width / (MoonBoardVariant.GRID_COLUMNS - 1),
    grid.height / (MoonBoardVariant.GRID_ROWS - 1),
)

/** Light board panel + a faint dot at each of the 11x18 lattice points. */
private fun DrawScope.drawGenericRaster(grid: Rect) {
    drawRect(color = MoonBoardPanelColor)
    val dotRadius = cellSpacing(grid) * 0.13f
    for (column in 0 until MoonBoardVariant.GRID_COLUMNS) {
        for (rowIndex in 0 until MoonBoardVariant.GRID_ROWS) {
            drawCircle(
                color = MoonBoardGridDotColor,
                radius = dotRadius,
                center = holdCentre(grid, column, rowIndex),
                style = Fill,
            )
        }
    }
}

/** Role colour for a climb hold's role code, or null to skip. */
private fun roleColor(roleCode: Int): Color? = when (roleCode) {
    MB_ROLE_START -> MoonBoardStartColor
    MB_ROLE_HAND -> MoonBoardHandColor
    MB_ROLE_FINISH -> MoonBoardFinishColor
    else -> null
}

/** Filled-alpha disc + solid ring at [centre], in the role colour. */
private fun DrawScope.drawHoldMarker(centre: Offset, color: Color, radius: Float) {
    drawCircle(color = color.copy(alpha = 0.22f), radius = radius, center = centre, style = Fill)
    drawCircle(
        color = color,
        radius = radius,
        center = centre,
        style = Stroke(width = radius * 0.32f),
    )
}

/** Role-coloured ring at each climb hold — generic raster (linear grid). */
private fun DrawScope.drawClimbHolds(grid: Rect, holds: List<Pair<Int, Int>>) {
    val maxHoldId = MoonBoardVariant.GRID_COLUMNS * MoonBoardVariant.GRID_ROWS
    val radius = cellSpacing(grid) * 0.34f
    holds.forEach { (holdId, roleCode) ->
        val color = roleColor(roleCode) ?: return@forEach
        if (holdId !in 1..maxHoldId) return@forEach
        val column = (holdId - 1) % MoonBoardVariant.GRID_COLUMNS
        val rowIndex = (holdId - 1) / MoonBoardVariant.GRID_COLUMNS
        drawHoldMarker(holdCentre(grid, column, rowIndex), color, radius)
    }
}

/** Role-coloured ring at each climb hold — real board image: positions
 *  come from the asset's measured per-hold coordinate map. */
private fun DrawScope.drawClimbHoldsMapped(
    asset: MoonBoardRenderAsset,
    holds: List<Pair<Int, Int>>,
) {
    val radius = size.width * IMAGE_HOLD_RADIUS_FRACTION
    holds.forEach { (holdId, roleCode) ->
        val color = roleColor(roleCode) ?: return@forEach
        val norm = asset.holdXy[holdId] ?: return@forEach
        drawHoldMarker(Offset(norm.x * size.width, norm.y * size.height), color, radius)
    }
}
