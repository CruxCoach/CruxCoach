package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.abs

/** A hold the board-size check lights: where it sits and its LED on the size under test. */
data class BoardSizeCheckPoint(
    val placementId: Int,
    val x: Long,
    val y: Long,
    val led: Int,
)

/**
 * The holds the board-size check lights, so a user can compare the board with the app.
 *
 * The box reports nothing back, so the app cannot find out the size itself. It lights a few
 * holds that sit far apart for the size under test; on a board of another size the same LED
 * numbers land on other holds, and the user sees it.
 *
 * Six points: the four corners, the top centre and the centre. Corners alone are not enough:
 * on Kilter 12 × 12 with kickboard against 12 × 14 the kickboard LEDs and the start of the
 * first column are identical, so only the top-right corner moves. With the top centre and the
 * centre at least three of the six points move for every pair of sizes of one Kilter product
 * (checked against the bundled Kilter geometry).
 */
object BoardSizeCheckPoints {
    /** White in the RGB332 colour byte the LED encoders take. */
    const val LIGHT_COLOR: Int = 0xFF

    /**
     * Picks the points among [placements] that have an LED in [ledByPlacement] (placement id →
     * LED of the size under test). Ties go to the smallest placement id, so the choice is stable.
     */
    fun pick(placements: List<BoardPlacement>, ledByPlacement: Map<Int, Int>): List<BoardSizeCheckPoint> {
        val candidates = placements.mapNotNull { placement ->
            val id = placement.placementId.toInt()
            ledByPlacement[id]?.let { BoardSizeCheckPoint(id, placement.x, placement.y, it) }
        }.distinctBy { it.placementId }
        if (candidates.isEmpty()) return emptyList()
        // Doubled centre keeps the arithmetic in whole numbers.
        val centreX2 = candidates.minOf { it.x } + candidates.maxOf { it.x }
        val centreY2 = candidates.minOf { it.y } + candidates.maxOf { it.y }
        fun best(score: (BoardSizeCheckPoint) -> Long): BoardSizeCheckPoint =
            candidates.maxWith(compareBy<BoardSizeCheckPoint> { score(it) }.thenByDescending { it.placementId })
        return listOf(
            best { -(it.x + it.y) }, // bottom left
            best { it.x - it.y }, // bottom right
            best { it.y - it.x }, // top left
            best { it.x + it.y }, // top right
            best { 2 * it.y - 4 * abs(2 * it.x - centreX2) }, // top centre
            best { -(abs(2 * it.x - centreX2) + abs(2 * it.y - centreY2)) }, // centre
        ).distinctBy { it.placementId }
    }

    /**
     * Whether the check is offered: boards driven with Aurora LED packets that come in more
     * than one size. MoonBoard, Quantum and Shiny Wall pick their layout differently.
     */
    fun offered(brand: BoardBrand, sizeCount: Int): Boolean = brand.usesAuroraProtocol && sizeCount > 1
}
