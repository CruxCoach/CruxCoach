package com.cruxcoach.android.ui.board

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.cruxcoach.domain.board.BoardBrand

/**
 * One colour per board in charts that put boards side by side. Eight
 * categorical hues with a light and a dark step each, checked against the
 * stats card in both themes for colour-vision deficiencies and contrast; the
 * first three stay apart in every pairing, not only next to each other.
 *
 * Boards take the slots in [BoardBrand] order over all boards the user has
 * logged on, so the usual one to three boards get the three safest colours,
 * and a board keeps its colour when the interval hides another one. A ninth
 * board gets no hue of its own and is drawn in the neutral ink; the legend
 * still names it.
 */
internal object BoardSeriesColors {

    private val LIGHT = listOf(
        Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948),
    )
    private val DARK = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
    )

    /** Slot per board wire value; null past the eighth board. */
    fun slots(boards: Collection<String>): Map<String, Int?> = boards.distinct()
        .sortedWith(
            compareBy<String> { BoardBrand.fromWireOrNull(it)?.ordinal ?: Int.MAX_VALUE }.thenBy { it },
        )
        .withIndex()
        .associate { (index, board) -> board to index.takeIf { it < LIGHT.size } }

    fun color(slot: Int?, dark: Boolean, fallback: Color): Color =
        slot?.let { (if (dark) DARK else LIGHT)[it] } ?: fallback
}

/** The chart colour of every board in [boards], resolved for the current theme. */
@Composable
internal fun boardSeriesColors(boards: Collection<String>): Map<String, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fallback = MaterialTheme.colorScheme.onSurfaceVariant
    return BoardSeriesColors.slots(boards).mapValues { (_, slot) -> BoardSeriesColors.color(slot, dark, fallback) }
}
