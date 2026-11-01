package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import java.time.Month
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val GRADE_COLUMN_WIDTH = 44.dp
private val BAR_THICKNESS = 8.dp
private val LANE_HEIGHT = 12.dp
// Room after the longest bar for its value ("12,5").
private val BAR_LABEL_RESERVE = 36.dp
// Shared by both month charts so their months line up.
private val Y_AXIS_WIDTH = 36.dp
private val MONTH_AXIS_HEIGHT = 20.dp

@Composable
private fun crossBoardViewLabel(view: CrossBoardView): String = when (view) {
    CrossBoardView.TRIES_PER_GRADE -> stringResource(R.string.board_stats_cross_board_tries)
    CrossBoardView.PER_MONTH -> stringResource(R.string.board_stats_cross_board_months)
}

private fun TriesToSend.isSmallSample() = problems < CrossBoardStatsComputer.SMALL_SAMPLE

/**
 * Stats across boards (#44), one colour per board. "Tries per grade" puts a
 * bar per board under each grade, "Progress by month" draws the hardest new
 * send and the tries per send as two small line charts over the same months.
 * Averages over fewer than [CrossBoardStatsComputer.SMALL_SAMPLE] problems are
 * drawn hollow. The explanation sits behind the info button; tapping
 * a grade or a month shows its numbers.
 *
 * [allBoards] are all boards the user has logged on: they fix the colours, so
 * a board keeps its colour when the interval hides another one.
 */
@Composable
internal fun CrossBoardSection(
    stats: CrossBoardStats,
    view: CrossBoardView,
    onViewSelect: (CrossBoardView) -> Unit,
    gradeScale: GradeScale,
    allBoards: List<String> = emptyList(),
) {
    val colors = boardSeriesColors(allBoards + stats.boards)
    ChartSectionWithSelector(
        options = CrossBoardView.entries.toTypedArray(),
        selected = view,
        onSelect = onViewSelect,
        labelOf = { crossBoardViewLabel(it) },
        help = stringResource(
            when (view) {
                CrossBoardView.TRIES_PER_GRADE -> R.string.board_stats_cross_board_tries_help
                CrossBoardView.PER_MONTH -> R.string.board_stats_cross_board_months_help
            },
            CrossBoardStatsComputer.SMALL_SAMPLE,
        ),
    ) {
        val smallSamples = when (view) {
            CrossBoardView.TRIES_PER_GRADE -> stats.grades.any { row -> row.byBoard.values.any { it.isSmallSample() } }
            CrossBoardView.PER_MONTH -> stats.months.any { row -> row.byBoard.values.any { it.tries.isSmallSample() } }
        }
        BoardLegend(stats.boards, colors, smallSamples)
        Spacer(Modifier.height(12.dp))
        when (view) {
            CrossBoardView.TRIES_PER_GRADE -> TriesPerGradeChart(stats, colors)
            CrossBoardView.PER_MONTH -> MonthCharts(stats, colors, gradeScale)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardLegend(
    boards: List<String>,
    colors: Map<String, Color>,
    smallSamples: Boolean,
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        boards.forEach { board ->
            LegendItem(boardBrandLabel(board)) {
                Spacer(Modifier.size(10.dp).background(colors.getValue(board), CircleShape))
            }
        }
        if (smallSamples) {
            LegendItem(stringResource(R.string.board_stats_cross_board_few, CrossBoardStatsComputer.SMALL_SAMPLE)) {
                Spacer(Modifier.size(10.dp).border(2.dp, ink, CircleShape))
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, marker: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        marker()
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "2,4": an average number of tries, in the language of the app. */
@Composable
private fun triesNumber(tries: TriesToSend): String =
    stringResource(R.string.board_stats_cross_board_tries_number, tries.averageTries)

/** "2,0 Versuche · 6 Probleme": the spelled-out value for details and screen readers. */
@Composable
private fun triesDetail(tries: TriesToSend): String =
    stringResource(R.string.board_stats_cross_board_tries_value, tries.averageTries) + " · " +
        pluralStringResource(R.plurals.board_stats_cross_board_problems, tries.problems, tries.problems)

/** The top of a tries scale: a whole number of tries, at least two. */
internal fun triesScaleMax(maxAverage: Double): Double = max(2.0, ceil(maxAverage))

// ---------------------------------------------------------------- tries per grade

@Composable
private fun TriesPerGradeChart(stats: CrossBoardStats, colors: Map<String, Color>) {
    if (stats.grades.isEmpty()) return
    val scaleMax = triesScaleMax(stats.grades.maxOf { row -> row.byBoard.values.maxOf { it.averageTries } })
    var selected by remember(stats.grades) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stats.grades.forEach { row ->
            GradeRow(
                row = row,
                boards = stats.boards,
                colors = colors,
                scaleMax = scaleMax,
                expanded = selected == row.grade,
                onClick = { selected = if (selected == row.grade) null else row.grade },
            )
        }
    }
}

@Composable
private fun GradeRow(
    row: CrossBoardGradeRow,
    boards: List<String>,
    colors: Map<String, Color>,
    scaleMax: Double,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val present = boards.filter { it in row.byBoard }
    val description = row.grade + ": " + present.map { board ->
        boardBrandLabel(board) + " " + triesDetail(row.byBoard.getValue(board))
    }.joinToString("; ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (expanded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.grade,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.width(GRADE_COLUMN_WIDTH),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Only boards with sends at this grade; the colour says which board it is.
                present.forEach { board ->
                    TriesBar(row.byBoard.getValue(board), colors.getValue(board), scaleMax)
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = GRADE_COLUMN_WIDTH, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                present.forEach { board ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spacer(Modifier.size(8.dp).background(colors.getValue(board), CircleShape))
                        Text(
                            boardBrandLabel(board) + "  " + triesDetail(row.byBoard.getValue(board)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TriesBar(tries: TriesToSend, color: Color, scaleMax: Double) {
    val label = triesNumber(tries)
    BoxWithConstraints(Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
        val small = tries.isSmallSample()
        // Rounded at the value end, square at the baseline.
        val shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
        val track = maxWidth - BAR_LABEL_RESERVE
        val length = (track * (tries.averageTries / scaleMax).toFloat()).coerceAtLeast(BAR_THICKNESS)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .width(length)
                    .height(BAR_THICKNESS)
                    // Hollow like the dots of the month view when few problems back it.
                    .then(if (small) Modifier.border(1.5.dp, color, shape) else Modifier.background(color, shape)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (small) FontWeight.Normal else FontWeight.SemiBold,
                color = if (small) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                // Taller than the lane: centred on the bar instead of cut off at the bottom.
                modifier = Modifier.wrapContentHeight(unbounded = true),
            )
        }
    }
}

// ---------------------------------------------------------------- progress by month

private val MONTH_RES_IDS = intArrayOf(
    R.string.month_jan, R.string.month_feb, R.string.month_mar,
    R.string.month_apr, R.string.month_may, R.string.month_jun,
    R.string.month_jul, R.string.month_aug, R.string.month_sep,
    R.string.month_oct, R.string.month_nov, R.string.month_dec,
)

/**
 * The grades between the easiest and the hardest of [difficulties], easiest
 * first, each once: the rows of a grade axis. Several difficulties can share
 * a displayed grade, so the axis counts grades, not difficulty steps.
 */
internal fun gradeLevels(difficulties: List<Double>, scale: GradeScale): List<String> {
    if (difficulties.isEmpty()) return emptyList()
    val low = floor(difficulties.min()).toInt()
    val high = ceil(difficulties.max()).toInt()
    return (low..high).map { GradeDisplayHelper.formatDifficulty(it.toDouble(), scale) }.distinct()
}

/** One series point: a board's value in one month. */
private class MonthPoint(val month: Int, val value: Double, val label: String, val hollow: Boolean)

@Composable
private fun MonthCharts(stats: CrossBoardStats, colors: Map<String, Color>, gradeScale: GradeScale) {
    // The model lists the newest month first; a chart reads left to right.
    val months = remember(stats.months) { stats.months.reversed() }
    if (months.isEmpty()) return
    var selected by remember(months) { mutableIntStateOf(months.lastIndex) }

    val levels = remember(months, gradeScale) {
        gradeLevels(months.flatMap { row -> row.byBoard.values.mapNotNull { it.hardestDifficulty } }, gradeScale)
    }
    val gradeSeries = stats.boards.associateWith { board ->
        months.mapIndexedNotNull { index, row ->
            val grade = row.byBoard[board]?.hardestGrade ?: return@mapIndexedNotNull null
            val level = levels.indexOf(grade).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
            MonthPoint(index, level.toDouble(), grade, hollow = false)
        }
    }
    val triesSeries = stats.boards.associateWith { board ->
        months.mapIndexedNotNull { index, row ->
            val tries = row.byBoard[board]?.tries ?: return@mapIndexedNotNull null
            MonthPoint(index, tries.averageTries, triesNumber(tries), tries.isSmallSample())
        }
    }
    val monthLabels = months.mapIndexed { index, row ->
        val name = stringResource(MONTH_RES_IDS[row.month.monthValue - 1])
        if (index == 0 || row.month.month == Month.JANUARY) "$name '${row.month.year % 100}" else name
    }
    val description = months.mapIndexed { index, row ->
        monthLabels[index] + ": " + stats.boards.filter { it in row.byBoard }.map { board ->
            val cell = row.byBoard.getValue(board)
            boardBrandLabel(board) + " " + (cell.hardestGrade?.let { "$it, " } ?: "") + triesDetail(cell.tries)
        }.joinToString("; ")
    }.joinToString(". ")

    Column(Modifier.semantics { contentDescription = description }) {
        ChartCaption(stringResource(R.string.board_stats_cross_board_hardest))
        if (levels.isNotEmpty()) {
            val step = ceil(levels.size / 5.0).toInt()
            val pad = if (levels.size == 1) 1.0 else 0.5
            MonthLineChart(
                monthCount = months.size,
                boards = stats.boards,
                colors = colors,
                series = gradeSeries,
                yMin = -pad,
                yMax = levels.lastIndex + pad,
                ticks = levels.indices.filter { it % step == 0 || it == levels.lastIndex }
                    .map { it.toDouble() to levels[it] },
                monthLabels = null,
                plotHeight = 128.dp,
                selected = selected,
                onSelect = { selected = it },
            )
        }
        Spacer(Modifier.height(16.dp))
        ChartCaption(stringResource(R.string.board_stats_cross_board_tries_per_send))
        val top = triesScaleMax(months.maxOf { row -> row.byBoard.values.maxOf { it.tries.averageTries } })
        val middle = ((1 + top) / 2).let { if (it == floor(it)) it else null }
        MonthLineChart(
            monthCount = months.size,
            boards = stats.boards,
            colors = colors,
            series = triesSeries,
            yMin = 1.0,
            yMax = top,
            ticks = listOfNotNull(1.0, middle?.takeIf { top >= 4 }, top).map { it to "${it.toInt()}" },
            monthLabels = monthLabels,
            plotHeight = 88.dp,
            selected = selected,
            onSelect = { selected = it },
        )
    }
}

@Composable
private fun ChartCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * A line per board over evenly spaced months, with dots on the months that
 * have a value. Boards sit a few dp apart inside a month, so equal values do
 * not hide each other. The selected month is shaded and its values are
 * written beside the dots; equal values share one label.
 */
@Composable
private fun MonthLineChart(
    monthCount: Int,
    boards: List<String>,
    colors: Map<String, Color>,
    series: Map<String, List<MonthPoint>>,
    yMin: Double,
    yMax: Double,
    ticks: List<Pair<Double, String>>,
    monthLabels: List<String>?,
    plotHeight: Dp,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedAxisStyle = axisStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val ink = MaterialTheme.colorScheme.onSurface
    // The card's own colour: rings around dots and backdrops behind labels.
    val card = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        .compositeOver(MaterialTheme.colorScheme.surface)
    val axisHeight = if (monthLabels != null) MONTH_AXIS_HEIGHT else 0.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(plotHeight + axisHeight)
            .pointerInput(monthCount) {
                detectTapGestures { tap ->
                    val left = Y_AXIS_WIDTH.toPx()
                    val band = (size.width - left) / monthCount
                    if (tap.x >= left) onSelect(((tap.x - left) / band).toInt().coerceIn(0, monthCount - 1))
                }
            },
    ) {
        val left = Y_AXIS_WIDTH.toPx()
        val inset = 8.dp.toPx()
        val plotTop = inset
        val plotBottom = plotHeight.toPx() - inset
        val band = (size.width - left) / monthCount
        val dodgeStep = min(6.dp.toPx(), band * 0.2f)
        fun xOf(month: Int, lane: Int) =
            left + band * (month + 0.5f) + (lane - (boards.size - 1) / 2f) * dodgeStep
        fun yOf(value: Double) =
            plotBottom - ((value - yMin) / (yMax - yMin)).toFloat() * (plotBottom - plotTop)
        val hairline = 1.dp.toPx()

        // Grid and value axis.
        ticks.forEach { (value, label) ->
            val y = yOf(value)
            drawLine(ink.copy(alpha = 0.10f), Offset(left, y), Offset(size.width, y), hairline)
            val text = measurer.measure(label, axisStyle)
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
        }

        // The selected month.
        drawRoundRect(
            ink.copy(alpha = 0.06f),
            topLeft = Offset(left + band * selected + 2.dp.toPx(), 0f),
            size = Size(band - 4.dp.toPx(), plotHeight.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )

        boards.forEachIndexed { lane, board ->
            val points = series[board].orEmpty()
            if (points.size < 2) return@forEachIndexed
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(xOf(p.month, lane), yOf(p.value)) else path.lineTo(xOf(p.month, lane), yOf(p.value))
            }
            drawPath(
                path,
                colors.getValue(board),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        boards.forEachIndexed { lane, board ->
            val color = colors.getValue(board)
            series[board].orEmpty().forEach { p ->
                val center = Offset(xOf(p.month, lane), yOf(p.value))
                drawCircle(card, radius = 6.dp.toPx(), center = center)
                if (p.hollow) {
                    drawCircle(color, radius = 3.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(color, radius = 4.dp.toPx(), center = center)
                }
            }
        }

        // Values of the selected month, beside its dots; equal values share one label.
        val labels = boards.withIndex()
            .mapNotNull { (lane, board) ->
                series[board].orEmpty().firstOrNull { it.month == selected }?.let { p ->
                    Triple(p.label, xOf(p.month, lane), yOf(p.value))
                }
            }
            .groupBy { it.first }
            .map { (label, at) -> Triple(measurer.measure(label, valueStyle), at.maxOf { it.second }, at.first().third) }
            .sortedBy { it.third }
        val gap = 8.dp.toPx()
        val pad = 3.dp.toPx()
        val leftmostDot = xOf(selected, 0)
        var lastBottom = Float.NEGATIVE_INFINITY
        labels.forEach { (text, dotX, dotY) ->
            val width = text.size.width.toFloat()
            val height = text.size.height.toFloat()
            val rightX = dotX + gap
            val x = if (rightX + width + pad <= size.width) rightX else max(0f, leftmostDot - gap - width)
            // Push a label down when it would overlap the one above it.
            val top = max(dotY - height / 2f, lastBottom + pad)
                .coerceAtMost(plotHeight.toPx() - height)
            lastBottom = top + height
            drawRoundRect(
                card,
                topLeft = Offset(x - pad, top - pad / 2f),
                size = Size(width + 2 * pad, height + pad),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(text, topLeft = Offset(x, top))
        }

        // Month axis: as many labels as fit, always the selected month.
        if (monthLabels != null) {
            val axisTop = plotHeight.toPx() + 4.dp.toPx()
            val measured = monthLabels.mapIndexed { i, label ->
                measurer.measure(label, if (i == selected) selectedAxisStyle else axisStyle)
            }
            val widest = measured.maxOf { it.size.width } + 8.dp.toPx()
            val every = max(1, ceil(widest / band).toInt())
            val selectedLayout = measured[selected]
            fun drawMonth(i: Int, layout: TextLayoutResult) {
                val x = (left + band * (i + 0.5f) - layout.size.width / 2f)
                    .coerceIn(left, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, axisTop))
            }
            measured.forEachIndexed { i, layout ->
                if (i != selected && i % every == 0 && abs(i - selected) * band >= widest) drawMonth(i, layout)
            }
            drawMonth(selected, selectedLayout)
        }
    }
}
