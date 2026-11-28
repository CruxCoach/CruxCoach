package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.abs

/**
 * The angle a climb opens at when navigation asks for one the climb cannot be climbed at. A pager, list or queue
 * hands every climb the same angle, so a MoonBoard problem that only exists at 25° opened at 40° with grade "?"
 * and could be logged at 40°.
 *
 * - MoonBoard: a problem's angle is part of its identity (MoonBoardUuid: one uuid per problem and angle), so it
 *   exists only at the angles it has stats for.
 * - Every other board: any angle its board is set up at is climbable (the detail picker offers them all); only an
 *   angle outside the climb's and the board's angles is impossible. While the board's angles are unknown (empty,
 *   e.g. mid-import, or a board without them) nothing is changed.
 *
 * An impossible angle becomes the setter's angle when there is one, else the nearest possible angle (the lower
 * one on a tie).
 */
internal object ClimbAngleFit {
    fun fit(
        requested: Int,
        brand: BoardBrand,
        climbAngles: List<Int>,
        boardAngles: Set<Int>,
        setterAngle: Int?,
    ): Int {
        val possible = when {
            brand == BoardBrand.MOONBOARD -> climbAngles
            boardAngles.isEmpty() -> emptyList<Int>()
            else -> climbAngles + boardAngles
        }.distinct().sorted()
        if (possible.isEmpty() || requested in possible) return requested
        if (setterAngle != null && setterAngle in possible) return setterAngle
        return possible.minBy { abs(it - requested) }
    }
}
