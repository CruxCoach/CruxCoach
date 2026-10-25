package com.cruxcoach.domain.board.games

/**
 * A hold a wall game can light: its placement id (the id the board's frames
 * and send paths use) and its centre in the board's own coordinates, x to the
 * right and y upwards.
 */
data class WallHold(val placementId: Int, val x: Double, val y: Double)

/**
 * The holds of a wall in horizontal rows, bottom row first, each row from
 * left to right.
 *
 * Every board CruxCoach can light keeps its holds on a fixed grid (Aurora
 * boards on 4-unit rows, Shiny Wall on its big, small and kicker rows,
 * MoonBoard on 11 columns), so holds at the same height form a row. Heights
 * within [DEFAULT_TOLERANCE] of a row's lowest hold count as that row, which
 * absorbs rounding without ever merging two grid rows.
 */
class WallRows private constructor(val rows: List<List<WallHold>>) {

    /** All holds, bottom row first. */
    val holds: List<WallHold> = rows.flatten()

    private val rowByPlacement: Map<Int, Int> = buildMap {
        rows.forEachIndexed { index, row -> row.forEach { put(it.placementId, index) } }
    }

    private val holdByPlacement: Map<Int, WallHold> = holds.associateBy { it.placementId }

    val size: Int get() = rows.size

    fun isEmpty(): Boolean = rows.isEmpty()

    /** Row of [placementId] (0 = bottom), or null when the hold is not on this wall. */
    fun rowOf(placementId: Int): Int? = rowByPlacement[placementId]

    /** Position of [placementId], or null when the hold is not on this wall. */
    fun holdOf(placementId: Int): WallHold? = holdByPlacement[placementId]

    companion object {
        const val DEFAULT_TOLERANCE = 0.5

        fun of(holds: Collection<WallHold>, tolerance: Double = DEFAULT_TOLERANCE): WallRows {
            val sorted = holds.distinctBy { it.placementId }
                .sortedWith(compareBy<WallHold> { it.y }.thenBy { it.x }.thenBy { it.placementId })
            val rows = mutableListOf<MutableList<WallHold>>()
            var rowBottom = Double.NaN
            for (hold in sorted) {
                if (rows.isEmpty() || hold.y - rowBottom > tolerance) {
                    rows += mutableListOf(hold)
                    rowBottom = hold.y
                } else {
                    rows.last() += hold
                }
            }
            return WallRows(rows.map { row -> row.sortedWith(compareBy<WallHold> { it.x }.thenBy { it.placementId }) })
        }
    }
}
