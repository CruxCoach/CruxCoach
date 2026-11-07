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
 * What the check lights for one size, and which other sizes it still cannot tell apart from it.
 *
 * [lookAlikes] are sizes on which every LED of this size lights the very same hold: a smaller
 * kit with the same LED numbers, like Tension Board 1 Half Kickboard inside Full Wall. On such
 * a board these holds light up exactly as on this one, so "yes, these are lit" only means this
 * size once each look-alike has been ruled out.
 */
data class BoardSizeCheckPlan(
    val points: List<BoardSizeCheckPoint>,
    val lookAlikes: Set<Int> = emptySet(),
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
 * (checked against the bundled Kilter geometry). Where the six land on the same holds of
 * another size, [plan] adds a hold that size lacks or lights elsewhere.
 */
object BoardSizeCheckPoints {
    /** White in the RGB332 colour byte the LED encoders take. */
    const val LIGHT_COLOR: Int = 0xFF

    /** The holds among [placements] with an LED in [ledByPlacement] (placement id → LED). */
    fun candidates(placements: List<BoardPlacement>, ledByPlacement: Map<Int, Int>): List<BoardSizeCheckPoint> =
        placements.mapNotNull { placement ->
            val id = placement.placementId.toInt()
            ledByPlacement[id]?.let { BoardSizeCheckPoint(id, placement.x, placement.y, it) }
        }.distinctBy { it.placementId }

    /**
     * Picks the points among [placements] that have an LED in [ledByPlacement] (placement id →
     * LED of the size under test). Ties go to the smallest placement id, so the choice is stable.
     */
    fun pick(placements: List<BoardPlacement>, ledByPlacement: Map<Int, Int>): List<BoardSizeCheckPoint> =
        pick(candidates(placements, ledByPlacement))

    private fun pick(candidates: List<BoardSizeCheckPoint>): List<BoardSizeCheckPoint> {
        if (candidates.isEmpty()) return emptyList()
        val centre = Centre(candidates)
        fun best(score: (BoardSizeCheckPoint) -> Long): BoardSizeCheckPoint =
            candidates.maxWith(compareBy<BoardSizeCheckPoint> { score(it) }.thenByDescending { it.placementId })
        return listOf(
            best { -(it.x + it.y) }, // bottom left
            best { it.x - it.y }, // bottom right
            best { it.y - it.x }, // top left
            best { it.x + it.y }, // top right
            best { 2 * it.y - 4 * abs(2 * it.x - centre.x2) }, // top centre
            best { -centre.distance(it) }, // centre
        ).distinctBy { it.placementId }
    }

    /**
     * The points for the size whose holds are [candidates], checked against every other size
     * of the product ([others]: size id → that size's candidates). Where the six points light
     * the same holds on another size, one more hold is added that this other size lacks or
     * lights elsewhere: the one farthest from the centre, ties to the smallest placement id.
     * A size without any such hold is a look-alike.
     */
    fun plan(candidates: List<BoardSizeCheckPoint>, others: Map<Int, List<BoardSizeCheckPoint>>): BoardSizeCheckPlan {
        val points = pick(candidates).toMutableList()
        if (points.isEmpty()) return BoardSizeCheckPlan(emptyList())
        val centre = Centre(candidates)
        val lookAlikes = mutableSetOf<Int>()
        others.toSortedMap().forEach { (sizeId, otherCandidates) ->
            val holdOfLed = otherCandidates.groupBy { it.led }.mapValues { (_, holds) -> holds.first().x to holds.first().y }
            fun differs(point: BoardSizeCheckPoint) = holdOfLed[point.led] != (point.x to point.y)
            if (points.any(::differs)) return@forEach
            val extra = candidates.filter(::differs)
                .maxWithOrNull(compareBy<BoardSizeCheckPoint> { centre.distance(it) }.thenByDescending { it.placementId })
            if (extra == null) lookAlikes += sizeId else points += extra
        }
        return BoardSizeCheckPlan(points, lookAlikes)
    }

    /** Middle of the holds' bounding box, doubled to keep the arithmetic in whole numbers. */
    private class Centre(candidates: List<BoardSizeCheckPoint>) {
        val x2 = candidates.minOf { it.x } + candidates.maxOf { it.x }
        private val y2 = candidates.minOf { it.y } + candidates.maxOf { it.y }
        fun distance(point: BoardSizeCheckPoint): Long = abs(2 * point.x - x2) + abs(2 * point.y - y2)
    }

    /**
     * Whether the check is offered: boards driven with Aurora LED packets that come in more
     * than one size. MoonBoard, Quantum and Shiny Wall pick their layout differently.
     */
    fun offered(brand: BoardBrand, sizeCount: Int): Boolean = brand.usesAuroraProtocol && sizeCount > 1
}
