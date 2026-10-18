package com.cruxcoach.app.render

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.math.sqrt

/**
 * Quantum on iOS screens. [quantumBoardPoint] scales linearly with the canvas
 * width, so a point computed for a 1-pixel square is the normalized position
 * over the square board image. The iOS app is iPhone-only and every iOS 17
 * iPhone is at least 375 pt wide, so Android's phone branch applies: never the
 * compact (< 360 dp) padding, never the tablet one.
 */
object QuantumRender {
    /** Android renders Quantum on a square canvas over the square model image. */
    const val ASPECT = 1f

    /** Same resolution order as Android's KilterBoardVisualization: product size first, then layout. */
    fun model(boardSize: BoardSize?, layoutId: Long?): QuantumBoardModel? {
        if (boardSize != null && boardSize.boardBrand != BoardBrand.QUANTUM) return null
        return boardSize?.id?.let(QuantumBoardModel::fromProductSizeId)
            ?: layoutId?.let(QuantumBoardModel::fromLayoutId)
    }

    fun normalized(model: QuantumBoardModel, boardX: Long, boardY: Long): BoardPoint =
        quantumBoardPoint(model, boardX, boardY, boardPixels = 1f, compactPhone = false, tablet = false)

    /** Android skips markers whose centre lies outside the canvas. */
    fun isVisible(point: BoardPoint): Boolean = point.x in 0f..1f && point.y in 0f..1f

    /**
     * Android's per-model touch radius is in dp over the board canvas, which on a
     * phone spans about the screen width; 360 pt stands in for that width here.
     */
    const val REFERENCE_CANVAS_POINTS = 360f

    /** Nearest placement to a tap in unit space, within the model's touch radius (Android findNearest). */
    fun nearestPlacement(model: QuantumBoardModel, x: Float, y: Float, placements: Collection<BoardPlacement>): Int? {
        val radius = quantumHitRadiusDp(model) / REFERENCE_CANVAS_POINTS
        var nearest: Int? = null
        var nearestDistance = Float.MAX_VALUE
        for (placement in placements) {
            val p = normalized(model, placement.x, placement.y)
            val distance = sqrt((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y))
            if (distance < radius && distance < nearestDistance) {
                nearestDistance = distance
                nearest = placement.placementId.toInt()
            }
        }
        return nearest
    }

    /**
     * left, top, right, bottom of a hold-search area over the image: Android
     * draws the bounding box of the four mapped corners, because the Quantum
     * mapping bends.
     */
    fun zoneRect(model: QuantumBoardModel, zone: BoardZone): List<Float> {
        val corners = listOf(
            normalized(model, zone.minX, zone.minY),
            normalized(model, zone.minX, zone.maxY),
            normalized(model, zone.maxX, zone.minY),
            normalized(model, zone.maxX, zone.maxY),
        )
        return listOf(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}
