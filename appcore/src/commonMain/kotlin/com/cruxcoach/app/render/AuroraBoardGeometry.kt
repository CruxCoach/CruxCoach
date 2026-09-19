package com.cruxcoach.app.render

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.BoardZoneFilter
import kotlin.math.sqrt

data class BoardPoint(val x: Float, val y: Float)

/** Marker radii / stroke widths as multiples of [AuroraBoardGeometry.markerScale] (KilterBoardVisualization.kt). */
object BoardMarkerMultipliers {
    const val MOUNTING_DOT_RADIUS = 1.25f
    const val HEAT_HALO_RADIUS = 4.5f
    const val HEAT_CORE_RADIUS = 1.8f
    const val ACTIVE_HOLD_RADIUS = 4f
    const val ACTIVE_HOLD_STROKE = 1.2f
    const val SELECTED_RING_RADIUS = 5f
    const val SELECTED_RING_STROKE = 1.2f
    const val SELECTED_DOT_RADIUS = 3f
    const val DRAG_RING_RADIUS = 6f
    const val DRAG_RING_STROKE = 1.5f
    const val DRAG_DOT_RADIUS = 4f
    const val ZONE_PADDING = 5f
    const val TAP_RADIUS = 6f
    /** Frame holds outside the current route frame in preview mode. */
    const val PREVIEW_DIMMED_ALPHA = 0.3f
}

/** Heatmap rendering rules: placements below the threshold are not drawn at all. */
object BoardHeatmap {
    const val MIN_INTENSITY = 0.15f
    const val MOUNTING_DOT_ARGB: Long = 0x40FFFFFF

    /** green -> yellow -> orange -> red */
    val COLORS_ARGB: List<Long> = listOf(
        0xFF1B5E20, 0xFF4CAF50, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFFF44336,
    )

    fun colorArgb(intensity: Float): Long {
        val idx = (intensity * (COLORS_ARGB.size - 1)).toInt().coerceIn(0, COLORS_ARGB.size - 1)
        return COLORS_ARGB[idx]
    }

    fun haloAlpha(intensity: Float): Float = 0.18f + 0.20f * intensity
    fun coreAlpha(intensity: Float): Float = 0.55f + 0.30f * intensity
}

/**
 * Coordinate mapping of an Aurora-family board (Kilter, Tension, ...) onto a canvas,
 * identical to Android's KilterBoardVisualization. Placement coordinates live in the
 * product size's edge box; y grows upwards on the board and downwards on the canvas.
 * Quantum uses [quantumBoardPoint] instead.
 */
class AuroraBoardGeometry(
    val brand: BoardBrand,
    val edgeLeft: Float,
    val edgeRight: Float,
    val edgeBottom: Float,
    val edgeTop: Float,
) {
    val boardWidth: Float get() = edgeRight - edgeLeft
    val boardHeight: Float get() = edgeTop - edgeBottom

    /** width / height the board view must keep so the image and the markers stay aligned. */
    val aspectRatio: Float get() = boardWidth / boardHeight

    fun xScale(canvasWidth: Float): Float = canvasWidth / boardWidth
    fun yScale(canvasHeight: Float): Float = canvasHeight / boardHeight
    fun markerScale(canvasWidth: Float): Float = boardMarkerScale(brand, xScale(canvasWidth), boardWidth)

    fun point(x: Long, y: Long, canvasWidth: Float, canvasHeight: Float): BoardPoint = BoardPoint(
        x = (x.toFloat() - edgeLeft) * xScale(canvasWidth),
        y = canvasHeight - (y.toFloat() - edgeBottom) * yScale(canvasHeight),
    )

    /** Android skips markers whose centre lies outside the canvas. */
    fun isVisible(p: BoardPoint, canvasWidth: Float, canvasHeight: Float): Boolean =
        p.x in 0f..canvasWidth && p.y in 0f..canvasHeight

    fun normalized(x: Long, y: Long): BoardPoint = point(x, y, 1f, 1f)

    /** Nearest placement within the tap radius (6 x marker scale), or null. */
    fun nearestPlacement(
        tapX: Float,
        tapY: Float,
        placements: Collection<BoardPlacement>,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Int? {
        val tapRadius = markerScale(canvasWidth) * BoardMarkerMultipliers.TAP_RADIUS
        var nearest: Int? = null
        var nearestDist = Float.MAX_VALUE
        for (placement in placements) {
            val p = point(placement.x, placement.y, canvasWidth, canvasHeight)
            val dist = sqrt((tapX - p.x) * (tapX - p.x) + (tapY - p.y) * (tapY - p.y))
            if (dist < tapRadius && dist < nearestDist) {
                nearestDist = dist
                nearest = placement.placementId.toInt()
            }
        }
        return nearest
    }

    /** Two canvas corners of a drag -> zone in placement coordinates. */
    fun zoneFromCanvas(ax: Float, ay: Float, bx: Float, by: Float, canvasWidth: Float, canvasHeight: Float): BoardZone {
        val xS = xScale(canvasWidth)
        val yS = yScale(canvasHeight)
        return BoardZoneFilter.zoneFromCorners(
            (ax / xS + edgeLeft).toLong(),
            ((canvasHeight - ay) / yS + edgeBottom).toLong(),
            (bx / xS + edgeLeft).toLong(),
            ((canvasHeight - by) / yS + edgeBottom).toLong(),
        )
    }

    companion object {
        // Defaults when no product size is known: the Kilter Original 12x12 edge box.
        const val DEFAULT_EDGE_LEFT = 0f
        const val DEFAULT_EDGE_RIGHT = 144f
        const val DEFAULT_EDGE_BOTTOM = 0f
        const val DEFAULT_EDGE_TOP = 156f

        fun of(boardSize: BoardSize?): AuroraBoardGeometry = AuroraBoardGeometry(
            brand = boardSize?.boardBrand ?: BoardBrand.KILTER,
            edgeLeft = boardSize?.edgeLeft?.toFloat() ?: DEFAULT_EDGE_LEFT,
            edgeRight = boardSize?.edgeRight?.toFloat() ?: DEFAULT_EDGE_RIGHT,
            edgeBottom = boardSize?.edgeBottom?.toFloat() ?: DEFAULT_EDGE_BOTTOM,
            edgeTop = boardSize?.edgeTop?.toFloat() ?: DEFAULT_EDGE_TOP,
        )
    }
}
