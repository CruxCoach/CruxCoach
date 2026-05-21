package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.math.min

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

/** Board card aspect (width / height) — the 11x18 lattice plus margins. */
private const val BOARD_ASPECT_RATIO = 0.65f

/**
 * MoonBoard climb visualization (FEAT-027).
 *
 * v0.2.0 draws a generic, procedurally-rendered 11x18 grid — no board
 * photo, no third-party imagery. [boardImage] is the swap-in seam for
 * the planned per-variant real-board photos: when non-null it becomes
 * the background and the hold-marker overlay is unchanged. A supplied
 * photo must be perspective-corrected and cropped to the grid bounding
 * box (centre of A1 to centre of K18).
 *
 * Display-only — the MoonBoard climb-creator is out of v0.2.0 scope, so
 * there is no touch interaction (cf. KilterBoardVisualization's editor).
 */
@Composable
internal fun MoonBoardVisualization(
    frames: String,
    boardImage: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    val climbHolds = remember(frames) { MoonBoardFrameEncoder.parseHolds(frames) }

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
                .aspectRatio(BOARD_ASPECT_RATIO),
        ) {
            boardImage?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val grid = gridRect(size, hasPhoto = boardImage != null)
                if (boardImage == null) {
                    drawGenericRaster(grid)
                }
                drawClimbHolds(grid, climbHolds)
            }
        }
    }
}

/**
 * Bounding box of the 11x18 hold lattice (centre of A1 to centre of
 * K18). For the generic raster the lattice is inset from the card
 * edges; a supplied photo is assumed pre-cropped to exactly this box.
 */
private fun gridRect(size: Size, hasPhoto: Boolean): Rect {
    if (hasPhoto) return Rect(Offset.Zero, size)
    return Rect(
        left = size.width * 0.09f,
        top = size.height * 0.055f,
        right = size.width * 0.94f,
        bottom = size.height * 0.955f,
    )
}

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

/** Role-coloured ring at each of the climb's start / hand / finish holds. */
private fun DrawScope.drawClimbHolds(grid: Rect, holds: List<Pair<Int, Int>>) {
    val maxHoldId = MoonBoardVariant.GRID_COLUMNS * MoonBoardVariant.GRID_ROWS
    val radius = cellSpacing(grid) * 0.34f
    holds.forEach { (holdId, roleCode) ->
        val color = when (roleCode) {
            MB_ROLE_START -> MoonBoardStartColor
            MB_ROLE_HAND -> MoonBoardHandColor
            MB_ROLE_FINISH -> MoonBoardFinishColor
            else -> return@forEach
        }
        if (holdId !in 1..maxHoldId) return@forEach
        val column = (holdId - 1) % MoonBoardVariant.GRID_COLUMNS
        val rowIndex = (holdId - 1) / MoonBoardVariant.GRID_COLUMNS
        val centre = holdCentre(grid, column, rowIndex)
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = radius,
            center = centre,
            style = Fill,
        )
        drawCircle(
            color = color,
            radius = radius,
            center = centre,
            style = Stroke(width = radius * 0.32f),
        )
    }
}
