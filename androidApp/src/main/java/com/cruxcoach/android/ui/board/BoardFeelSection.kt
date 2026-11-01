package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [grades] rounded to the nearest half grade, as a positive number of halves. */
internal fun halfGrades(grades: Double): Int = (abs(grades) * 2).roundToInt()

/** "about one grade", "about half a grade", "about 1½ grades". */
@Composable
private fun feelAmount(halves: Int): String = when (halves) {
    1 -> stringResource(R.string.board_stats_feel_half)
    2 -> stringResource(R.string.board_stats_feel_one)
    else -> stringResource(
        R.string.board_stats_feel_grades,
        if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}½",
    )
}

/** One sentence for the board that matters most after the main one. */
@Composable
internal fun boardFeelSentence(feel: BoardFeel, order: List<String>): String? {
    val board = order.firstOrNull { it in feel.offsets } ?: feel.offsets.keys.firstOrNull() ?: return null
    val offset = feel.offsets.getValue(board)
    val halves = halfGrades(offset.grades)
    val name = boardBrandLabel(board)
    val reference = boardBrandLabel(feel.reference)
    return when {
        halves == 0 -> stringResource(R.string.board_stats_feel_same, name, reference)
        offset.grades > 0 -> stringResource(R.string.board_stats_feel_harder, name, feelAmount(halves), reference)
        else -> stringResource(R.string.board_stats_feel_easier, name, feelAmount(halves), reference)
    }
}

/**
 * How the boards feel against the main one: a sentence, then a row per board
 * with its dot on a shared scale of grades, the main board on the 0 line. The
 * pale band around a dot spans one standard error either side, so a wide band
 * means a rough estimate.
 */
@Composable
internal fun BoardFeelView(feel: BoardFeel, order: List<String>, colors: Map<String, Color>) {
    val sentence = boardFeelSentence(feel, order) ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The main board first, then the others in the order of the comparison.
    val rows = listOf(feel.reference to BoardOffset(0.0, 0.0)) +
        order.filter { it in feel.offsets }.map { it to feel.offsets.getValue(it) } +
        feel.offsets.filterKeys { it !in order }.toList()
    val reach = rows.maxOf { (_, o) -> abs(o.grades) + o.error }
    val range = min(3.0, max(2.0, ceil(reach)))

    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = sentence }) {
        Text(sentence, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (board, offset) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    boardBrandLabel(board),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(FEEL_NAME_WIDTH),
                )
                FeelLane(offset, colors[board] ?: muted, range, Modifier.weight(1f).height(FEEL_LANE_HEIGHT))
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Spacer(Modifier.width(FEEL_NAME_WIDTH))
            FeelAxis(range, Modifier.weight(1f))
        }
    }
}

/** Shared x position of a grade offset in a lane of [width] px. */
private fun feelX(grades: Double, range: Double, width: Float, inset: Float): Float =
    inset + ((grades.coerceIn(-range, range) + range) / (2 * range)).toFloat() * (width - 2 * inset)

@Composable
private fun FeelLane(offset: BoardOffset, color: Color, range: Double, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f).compositeOver(MaterialTheme.colorScheme.surface)
    Canvas(modifier) {
        val inset = FEEL_INSET.toPx()
        val mid = size.height / 2
        drawLine(ink.copy(alpha = 0.10f), Offset(inset, mid), Offset(size.width - inset, mid), 1.dp.toPx())
        // The main board's line, through every lane.
        val zero = feelX(0.0, range, size.width, inset)
        drawLine(ink.copy(alpha = 0.30f), Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
        if (offset.error > 0) {
            val from = feelX(offset.grades - offset.error, range, size.width, inset)
            val to = feelX(offset.grades + offset.error, range, size.width, inset)
            val band = 12.dp.toPx()
            drawRoundRect(
                color.copy(alpha = 0.25f),
                topLeft = Offset(from, mid - band / 2),
                size = Size(to - from, band),
                cornerRadius = CornerRadius(band / 2),
            )
        }
        val center = Offset(feelX(offset.grades, range, size.width, inset), mid)
        drawCircle(card, 8.dp.toPx(), center)
        drawCircle(color, 6.dp.toPx(), center)
    }
}

@Composable
private fun FeelAxis(range: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val inset = FEEL_INSET.toPx()
            for (g in -range.toInt()..range.toInt()) {
                val x = feelX(g.toDouble(), range, size.width, inset)
                val tick = measurer.measure(if (g > 0) "+$g" else if (g < 0) "−${-g}" else "0", axisStyle)
                drawText(tick, topLeft = Offset(x - tick.size.width / 2f, 2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← " + stringResource(R.string.board_stats_feel_easier_end), style = axisStyle)
            Text(stringResource(R.string.board_stats_feel_harder_end) + " →", style = axisStyle)
        }
    }
}

private val FEEL_NAME_WIDTH = 96.dp
private val FEEL_LANE_HEIGHT = 28.dp
private val FEEL_INSET = 10.dp
