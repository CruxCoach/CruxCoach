package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.GradeEasy
import com.cruxcoach.android.ui.theme.GradeElite
import com.cruxcoach.android.ui.theme.GradeHard
import com.cruxcoach.android.ui.theme.GradeMedium
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.GradeDisplayHelper
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- the period in numbers

/**
 * Sends, tries, climbing days and distinct problems of the chosen period and
 * boards, as plain numbers under the filters: everything below explains them.
 */
@Composable
internal fun PeriodNumbers(stats: BoardLogbookStats) {
    if (stats.totalSends == 0 && stats.totalAttempts == 0) return
    val sends = if (stats.routeSends > 0) "${stats.boulderSends}B / ${stats.routeSends}R" else "${stats.totalSends}"
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
    ) {
        val column = Modifier.weight(1f).fillMaxHeight()
        PeriodNumber(sends, stringResource(R.string.board_sends), column)
        PeriodNumber("${stats.totalAttempts}", stringResource(R.string.board_session_summary_attempts), column)
        PeriodNumber("${stats.sessionCount}", stringResource(R.string.board_logbook_sessions), column)
        PeriodNumber("${stats.uniqueClimbs}", stringResource(R.string.board_stats_problems), column)
    }
}

@Composable
private fun PeriodNumber(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- grades

/**
 * Problems per grade, each counted once by its best result: flashed, sent
 * later, or not sent yet. The same light blue means a flash as in "Tries
 * until the send", a darker blue a send after more tries. Tap a grade for its
 * numbers.
 */
@Composable
internal fun GradesCard(entries: List<GradeOutcomeEntry>) {
    if (entries.isEmpty()) return
    val ramp = triesRamp()
    val shades = listOf(ramp[0], ramp[2], MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    val labels = listOf(
        stringResource(R.string.board_logbook_flash),
        stringResource(R.string.ux_later_send),
        stringResource(R.string.board_stats_grades_open),
    )
    val most = entries.maxOf { it.total }.coerceAtLeast(1)
    var selected by remember(entries) { mutableStateOf<String?>(null) }
    ChartSection(
        stringResource(R.string.board_stats_grades_title),
        help = stringResource(R.string.board_stats_grades_help),
    ) {
        SwatchLegend(labels.zip(shades))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Hardest grade on top, like a pyramid.
            entries.asReversed().forEach { entry ->
                val counts = listOf(entry.flashCount, entry.redpointCount, entry.attemptCount)
                val detail = counts.indices.filter { counts[it] > 0 }.joinToString(" · ") { "${labels[it]} ${counts[it]}" }
                val expanded = selected == entry.grade
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (expanded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                        .clickable { selected = if (expanded) null else entry.grade }
                        .semantics { contentDescription = "${entry.grade}: $detail" }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.grade,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.width(44.dp),
                        )
                        Canvas(Modifier.weight(1f).height(16.dp)) {
                            val gap = 2.dp.toPx()
                            val full = size.width * entry.total / most
                            val shown = counts.withIndex().filter { it.value > 0 }
                            val width = (full - gap * (shown.size - 1)).coerceAtLeast(2f)
                            var x = 0f
                            shown.forEach { (i, n) ->
                                val w = (width * n / entry.total).coerceAtLeast(2.dp.toPx())
                                drawRoundRect(shades[i], Offset(x, 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
                                x += w + gap
                            }
                        }
                        Text(
                            "${entry.total}",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(36.dp),
                        )
                    }
                    if (expanded) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 44.dp, top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchLegend(items: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- progress

/**
 * The rolling performance level over time: one line per board when the
 * statistics show several boards together ([byBoard]), otherwise one line in
 * the board's colour. Each line ends in its current grade.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgressCard(
    single: List<GradeProgressionPoint>,
    byBoard: Map<String, List<GradeProgressionPoint>>,
    boardFilter: String?,
    allBoards: List<String>,
    gradeScale: GradeScale,
) {
    val colors = boardSeriesColors(allBoards)
    val series: List<Pair<String?, List<GradeProgressionPoint>>> = if (byBoard.size > 1) {
        allBoards.filter { it in byBoard }.map { it to byBoard.getValue(it) }
    } else {
        // One line: the filtered board, the only board with sends in the period, or the only board at all.
        listOf((boardFilter ?: byBoard.keys.singleOrNull() ?: allBoards.singleOrNull()) to single)
    }
    if (series.none { it.second.size >= 2 }) return
    val lineColors = series.map { (board, _) -> board?.let { colors[it] } ?: OrangeAccent }

    ChartSection(
        stringResource(R.string.board_stats_grade_progression),
        help = stringResource(R.string.ux_grade_progression_help),
    ) {
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                series.forEachIndexed { i, (board, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spacer(Modifier.size(10.dp).background(lineColors[i], CircleShape))
                        Text(
                            boardBrandLabel(board.orEmpty()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        ProgressChart(series.map { it.second }, lineColors, gradeScale, series.map { (board, _) -> board?.let { boardBrandLabel(it) } ?: "" })
    }
}

private val MONTH_LABELS = intArrayOf(
    R.string.month_jan, R.string.month_feb, R.string.month_mar,
    R.string.month_apr, R.string.month_may, R.string.month_jun,
    R.string.month_jul, R.string.month_aug, R.string.month_sep,
    R.string.month_oct, R.string.month_nov, R.string.month_dec,
)

@Composable
private fun ProgressChart(
    series: List<List<GradeProgressionPoint>>,
    colors: List<Color>,
    gradeScale: GradeScale,
    names: List<String>,
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val endStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val ink = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f).compositeOver(MaterialTheme.colorScheme.surface)
    val months = MONTH_LABELS.map { stringResource(it) }

    val all = series.flatten()
    val first = all.minOf { it.weekStart }
    val last = all.maxOf { it.weekStart }
    val span = (last.toEpochDay() - first.toEpochDay()).coerceAtLeast(1L).toFloat()
    val low = floor(all.minOf { it.performanceDifficulty }) - 0.5
    val high = max(ceil(all.maxOf { it.performanceDifficulty }) + 0.5, low + 2.5)
    // The first difficulty of every displayed grade in range, at most four of them.
    val gradeTicks = remember(low, high, gradeScale) {
        val starts = (ceil(low).toInt()..floor(high).toInt())
            .filter { d ->
                GradeDisplayHelper.formatDifficulty(d.toDouble(), gradeScale) !=
                    GradeDisplayHelper.formatDifficulty(d - 1.0, gradeScale)
            }
        val step = ceil(starts.size / 4.0).toInt().coerceAtLeast(1)
        starts.filterIndexed { i, _ -> i % step == 0 }
    }
    val description = series.mapIndexed { i, points ->
        val from = GradeDisplayHelper.formatDifficulty(points.first().performanceDifficulty, gradeScale)
        val to = GradeDisplayHelper.formatDifficulty(points.last().performanceDifficulty, gradeScale)
        if (names[i].isNotEmpty()) "${names[i]}: $from → $to" else "$from → $to"
    }.joinToString("; ")

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(PROGRESS_PLOT_HEIGHT + PROGRESS_AXIS_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        val left = PROGRESS_Y_AXIS.toPx()
        val right = size.width - PROGRESS_END_LABELS.toPx()
        val top = 6.dp.toPx()
        val bottom = PROGRESS_PLOT_HEIGHT.toPx() - 6.dp.toPx()
        fun xOf(day: LocalDate) = left + (day.toEpochDay() - first.toEpochDay()) / span * (right - left)
        fun yOf(d: Double) = bottom - ((d - low) / (high - low)).toFloat() * (bottom - top)

        gradeTicks.forEach { d ->
            val y = yOf(d.toDouble())
            drawLine(ink.copy(alpha = 0.10f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            val text = measurer.measure(GradeDisplayHelper.formatDifficulty(d.toDouble(), gradeScale), axisStyle)
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
        }

        drawMonthAxis(
            measurer, axisStyle, months, first, last, ::xOf,
            gridTop = top, gridBottom = bottom, labelTop = PROGRESS_PLOT_HEIGHT.toPx() + 2.dp.toPx(),
            right = right, grid = ink.copy(alpha = 0.10f),
        )

        series.forEachIndexed { i, points ->
            val color = colors[i]
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { j, p ->
                    val o = Offset(xOf(p.weekStart), yOf(p.performanceDifficulty))
                    if (j == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val end = points.last()
            val center = Offset(xOf(end.weekStart), yOf(end.performanceDifficulty))
            drawCircle(card, 6.dp.toPx(), center)
            drawCircle(color, 4.dp.toPx(), center)
        }

        // The current grade of each line at its end, pushed apart where they meet.
        val ends = series.mapIndexed { i, points ->
            val p = points.last()
            Triple(i, yOf(p.performanceDifficulty), measurer.measure(GradeDisplayHelper.formatDifficulty(p.performanceDifficulty, gradeScale), endStyle))
        }.sortedBy { it.second }
        var lastBottom = Float.NEGATIVE_INFINITY
        ends.forEach { (i, y, text) ->
            val natural = y - text.size.height / 2f
            val labelTop = max(natural, lastBottom + 1.dp.toPx())
                .coerceAtMost(PROGRESS_PLOT_HEIGHT.toPx() - text.size.height)
            val x = right + 8.dp.toPx()
            // A label pushed off its line gets its board's dot, so it is still clear whose it is.
            if (abs(labelTop - natural) > 2.dp.toPx()) {
                drawCircle(colors[i], 3.dp.toPx(), Offset(x, labelTop + text.size.height / 2f))
            }
            drawText(text, topLeft = Offset(x + 6.dp.toPx(), labelTop))
            lastBottom = labelTop + text.size.height
        }
    }
}

private fun LocalDate.coerceAtLeast(other: LocalDate) = if (isBefore(other)) other else this

/**
 * Month names under a time axis from [first] to [last], where each month
 * starts, as many as fit, with the year on the first one and on January; a
 * faint line marks each labelled month.
 */
private fun DrawScope.drawMonthAxis(
    measurer: TextMeasurer,
    style: TextStyle,
    months: List<String>,
    first: LocalDate,
    last: LocalDate,
    xOf: (LocalDate) -> Float,
    gridTop: Float,
    gridBottom: Float,
    labelTop: Float,
    right: Float,
    grid: Color,
) {
    val starts = buildList {
        if (first.dayOfMonth <= 7) add(first.withDayOfMonth(1))
        var month = first.withDayOfMonth(1).plusMonths(1)
        while (!month.isAfter(last)) { add(month); month = month.plusMonths(1) }
    }
    var lastEnd = Float.NEGATIVE_INFINITY
    starts.forEachIndexed { i, start ->
        val name = months[start.monthValue - 1] + if (i == 0 || start.monthValue == 1) " '${start.year % 100}" else ""
        val text = measurer.measure(name, style)
        val x = xOf(start.coerceAtLeast(first)).coerceAtMost(right - text.size.width)
        if (x > lastEnd + 8.dp.toPx()) {
            drawLine(grid, Offset(x, gridTop), Offset(x, gridBottom), 1.dp.toPx())
            drawText(text, topLeft = Offset(x, labelTop))
            lastEnd = x + text.size.width
        }
    }
}

private val PROGRESS_PLOT_HEIGHT = 140.dp
private val PROGRESS_AXIS_HEIGHT = 20.dp
private val PROGRESS_Y_AXIS = 36.dp
private val PROGRESS_END_LABELS = 44.dp

// ---------------------------------------------------------------- activity

@Composable
private fun activityViewLabel(view: ActivityView): String = when (view) {
    ActivityView.CALENDAR -> stringResource(R.string.board_stats_activity)
    ActivityView.WEEKLY -> stringResource(R.string.board_stats_weekly_volume)
}

/** When you climbed: the days as a calendar, or the entries per week by grade band. */
@Composable
internal fun ActivityCard(
    view: ActivityView,
    onSelect: (ActivityView) -> Unit,
    activityMap: Map<LocalDate, Int>,
    weeklyVolume: List<WeeklyVolumeEntry>,
    interval: StatsTimeInterval,
) {
    if (activityMap.isEmpty() && weeklyVolume.isEmpty()) return
    ChartSectionWithSelector(
        options = ActivityView.entries.toTypedArray(),
        selected = view,
        onSelect = onSelect,
        labelOf = { activityViewLabel(it) },
    ) {
        when (view) {
            ActivityView.CALENDAR -> {
                BoardActivityHeatmap(activityMap = activityMap, interval = interval)
                Spacer(Modifier.height(8.dp))
                HeatmapLegend()
            }
            ActivityView.WEEKLY -> WeeklyChart(weeklyVolume)
        }
    }
}

/**
 * Log entries per week as columns on a time axis, so weeks off stay visible
 * as gaps, stacked by grade band in the grade colours.
 */
@Composable
private fun WeeklyChart(entries: List<WeeklyVolumeEntry>) {
    if (entries.isEmpty()) return
    val bands = listOf(GradeEasy, GradeMedium, GradeHard, GradeElite)
    val used = listOf(
        entries.any { it.easyCount > 0 }, entries.any { it.mediumCount > 0 },
        entries.any { it.hardCount > 0 }, entries.any { it.eliteCount > 0 },
    )
    // Only the bands that occur.
    SwatchLegend(
        listOf(
            stringResource(R.string.board_stats_grade_easy),
            stringResource(R.string.board_stats_grade_medium),
            stringResource(R.string.board_stats_grade_hard),
            stringResource(R.string.board_stats_grade_elite),
        ).zip(bands).filterIndexed { i, _ -> used[i] },
    )
    Spacer(Modifier.height(10.dp))
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ink = MaterialTheme.colorScheme.onSurface
    val months = MONTH_LABELS.map { stringResource(it) }
    val most = entries.maxOf { it.total }.coerceAtLeast(1)
    val first = entries.first().weekStart
    val last = entries.last().weekStart.plusDays(6)
    val days = (last.toEpochDay() - first.toEpochDay() + 1).toFloat()
    val description = stringResource(R.string.board_stats_weekly_volume) + ": " +
        entries.joinToString("; ") { "${it.weekStart}: ${it.total}" }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(WEEKLY_PLOT_HEIGHT + PROGRESS_AXIS_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        val left = PROGRESS_Y_AXIS.toPx()
        val right = size.width
        val top = 6.dp.toPx()
        val bottom = WEEKLY_PLOT_HEIGHT.toPx()
        fun xOf(day: LocalDate) = left + (day.toEpochDay() - first.toEpochDay()) / days * (right - left)
        val week = (right - left) * 7 / days
        val gap = min(2.dp.toPx(), week * 0.25f)
        val barWidth = (week - gap).coerceAtLeast(1.dp.toPx())

        listOf(most / 2, most).distinct().filter { it > 0 }.forEach { value ->
            val y = bottom - value.toFloat() / most * (bottom - top)
            drawLine(ink.copy(alpha = 0.10f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            val text = measurer.measure("$value", axisStyle)
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
        }
        drawLine(ink.copy(alpha = 0.20f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        drawMonthAxis(
            measurer, axisStyle, months, first, last, ::xOf,
            gridTop = top, gridBottom = bottom, labelTop = bottom + 2.dp.toPx(),
            right = right, grid = ink.copy(alpha = 0.06f),
        )

        entries.forEach { entry ->
            val x = xOf(entry.weekStart) + gap / 2
            var y = bottom
            val counts = listOf(entry.easyCount, entry.mediumCount, entry.hardCount, entry.eliteCount)
            val topBand = counts.indexOfLast { it > 0 }
            counts.forEachIndexed { i, n ->
                if (n == 0) return@forEachIndexed
                val h = n.toFloat() / most * (bottom - top)
                // Rounded only at the top of the column.
                if (i == topBand) {
                    val r = min(2.dp.toPx(), barWidth / 2)
                    drawRoundRect(bands[i], Offset(x, y - h), Size(barWidth, h), CornerRadius(r))
                    if (h > r) drawRect(bands[i], Offset(x, y - h + r), Size(barWidth, h - r))
                } else {
                    drawRect(bands[i], Offset(x, y - h), Size(barWidth, h))
                }
                y -= h
            }
        }

    }
}

private val WEEKLY_PLOT_HEIGHT = 112.dp
