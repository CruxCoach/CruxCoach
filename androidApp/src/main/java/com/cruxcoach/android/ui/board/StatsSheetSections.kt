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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.GradeEasy
import com.cruxcoach.android.ui.theme.GradeElite
import com.cruxcoach.android.ui.theme.GradeHard
import com.cruxcoach.android.ui.theme.GradeMedium
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.zoneColorForDifficulty
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.android.util.GradeDisplayHelper
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- the period in numbers

/**
 * The six numbers of the chosen period and boards, in two rows of three:
 * sends, tries and climbing days; distinct problems, the hardest grade and
 * the flash rate. For a period with a length, each shows its change against
 * the period of the same length before (the hardest grade in grade steps).
 * Below them the period's bests. Everything further down explains these numbers.
 */
@Composable
internal fun PeriodNumbers(stats: BoardLogbookStats, gradeScale: GradeScale) {
    if (stats.totalSends == 0 && stats.totalAttempts == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PeriodTotalsCard(stats, gradeScale)
        PeriodBests(stats.personalRecords)
    }
}

/**
 * A period without a single entry: says so, and offers the whole logbook
 * when a shorter period is chosen ([onShowAll]).
 */
@Composable
internal fun EmptyPeriod(onShowAll: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.board_stats_empty_period),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onShowAll != null) {
            TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.board_stats_show_all_time), color = OrangeAccent)
            }
        }
    }
}

@Composable
private fun PeriodTotalsCard(stats: BoardLogbookStats, gradeScale: GradeScale) {
    val sends = if (stats.routeSends > 0) "${stats.boulderSends}B / ${stats.routeSends}R" else "${stats.totalSends}"
    val before = stats.previous
    val flash = Math.round(stats.flashRate)
    val hardestNow = stats.hardestDifficulty
    val hardestBefore = before?.hardestDifficulty
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val sendsLabel = stringResource(R.string.board_sends)
        val triesLabel = stringResource(R.string.board_session_summary_attempts)
        val daysLabel = stringResource(R.string.board_logbook_sessions)
        val problemsLabel = stringResource(R.string.board_stats_problems)
        val hardestLabel = stringResource(R.string.board_stats_glance_hardest)
        val flashLabel = stringResource(R.string.board_logbook_flash)
        NumberGrid(
            perRow = 3,
            labels = listOf(sendsLabel, triesLabel, daysLabel, problemsLabel, hardestLabel, flashLabel),
            cells = listOf<@Composable (Modifier) -> Unit>(
                { column -> PeriodNumber(sends, sendsLabel, before?.let { stats.totalSends - it.sends }, column) },
                { column -> PeriodNumber("${stats.totalAttempts}", triesLabel, before?.let { stats.totalAttempts - it.attempts }, column) },
                { column -> PeriodNumber("${stats.sessionCount}", daysLabel, before?.let { stats.sessionCount - it.sessions }, column) },
                { column -> PeriodNumber("${stats.uniqueClimbs}", problemsLabel, before?.let { stats.uniqueClimbs - it.problems }, column) },
                { column ->
                    PeriodNumber(
                        stats.hardestGrade ?: "–", hardestLabel,
                        if (hardestNow != null && hardestBefore != null) {
                            StatsHighlightsComputer.gradeSteps(hardestBefore, hardestNow, gradeScale)
                        } else {
                            null
                        },
                        column,
                        reserveChange = before != null,
                    )
                },
                { column -> PeriodNumber("$flash %", flashLabel, before?.let { flash - Math.round(it.flashRate) }, column, unit = " %") },
            ),
        )
        if (before != null) {
            Text(
                stringResource(R.string.board_stats_period_versus),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * The bests of the chosen period and boards, as 0.2.4's records row: the
 * hardest flash, the most sends in one day, climbing days a week over the
 * whole period and the most weeks in a row with a climbing day.
 */
@Composable
private fun PeriodBests(records: PersonalRecords) {
    val items = buildList<Triple<String, String, String?>> {
        records.hardestFlashGrade?.let { add(Triple(it, stringResource(R.string.board_stats_glance_hardest_flash), null)) }
        // A best of one is no best yet.
        if (records.mostSendsInDay > 1) {
            val date = records.mostSendsDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            add(Triple("${records.mostSendsInDay}", stringResource(R.string.board_stats_milestone_biggest_day), date?.let { milestoneDate(it) }))
        }
        if (records.daysPerWeek > 0.0) {
            add(Triple(stringResource(R.string.board_stats_glance_per_week_value, records.daysPerWeek), stringResource(R.string.board_stats_best_per_week), null))
        }
        if (records.longestWeekStreak > 1) {
            add(Triple("${records.longestWeekStreak}", stringResource(R.string.board_stats_best_streak), null))
        }
    }
    if (items.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.board_stats_bests),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
        )
        NumberGrid(
            perRow = 4,
            labels = items.map { it.second },
            cells = items.map<Triple<String, String, String?>, @Composable (Modifier) -> Unit> { (value, label, date) ->
                { column ->
                    Column(
                        column
                            .padding(horizontal = 4.dp)
                            .clearAndSetSemantics { contentDescription = listOfNotNull(label, value, date).joinToString(", ") },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (date != null) {
                            Text(
                                date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PeriodNumber(
    value: String,
    label: String,
    change: Int?,
    modifier: Modifier,
    unit: String = "",
    reserveChange: Boolean = false,
) {
    val changeText = change?.let { if (it > 0) "+$it$unit" else if (it < 0) "−${-it}$unit" else "±0$unit" }
    Column(
        modifier.clearAndSetSemantics { contentDescription = listOfNotNull(label, value, changeText).joinToString(", ") },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (change == null && reserveChange) {
            // Keeps the label on the line of its neighbours' labels.
            Text("", style = MaterialTheme.typography.labelSmall, modifier = Modifier.heightIn(min = 16.dp))
        }
        if (change != null && changeText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (change != 0) {
                    Icon(
                        if (change > 0) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = if (change > 0) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(changeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- grades

@Composable
private fun gradesViewLabel(view: GradesView): String = when (view) {
    GradesView.PROBLEMS -> stringResource(R.string.board_stats_grades_title)
    GradesView.SENDS -> stringResource(R.string.board_stats_grade_sends_title)
}

/**
 * The grades in two views. "Problems per grade" counts every problem once by
 * its best result: flashed, sent later, or not sent yet; the same light blue
 * means a flash as in "Tries until the send". "Sends per grade" counts every
 * send with its repeats, coloured by the climber's intensity zones. Tap a
 * grade for its numbers.
 */
@Composable
internal fun GradesCard(
    outcomes: List<GradeOutcomeEntry>,
    sends: List<BoardGradePyramidEntry>,
    zones: IntensityZones?,
    view: GradesView,
    onSelect: (GradesView) -> Unit,
) {
    if (outcomes.isEmpty() && sends.isEmpty()) return
    ChartSectionWithSelector(
        options = GradesView.entries.toTypedArray(),
        selected = view,
        onSelect = onSelect,
        labelOf = { gradesViewLabel(it) },
        help = stringResource(
            when (view) {
                GradesView.PROBLEMS -> R.string.board_stats_grades_help
                GradesView.SENDS -> R.string.board_stats_grade_sends_help
            },
        ),
    ) {
        when (view) {
            GradesView.PROBLEMS -> ProblemsPerGrade(outcomes, sends)
            GradesView.SENDS -> SendsPerGrade(sends, zones)
        }
    }
}

/** "7 sends · 3 problems": a grade's sends with repeats and the problems behind them, so the repeats show. */
@Composable
private fun sendsAndProblems(entry: BoardGradePyramidEntry): String =
    pluralStringResource(R.plurals.board_stats_grade_sends_count, entry.count, entry.count) + " · " +
        pluralStringResource(R.plurals.board_stats_cross_board_problems, entry.problems, entry.problems)

@Composable
private fun ProblemsPerGrade(entries: List<GradeOutcomeEntry>, sends: List<BoardGradePyramidEntry>) {
    if (entries.isEmpty()) return
    val ramp = triesRamp()
    val shades = listOf(ramp[0], ramp[2], MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    val labels = listOf(
        stringResource(R.string.board_logbook_flash),
        stringResource(R.string.ux_later_send),
        stringResource(R.string.board_stats_grades_open),
    )
    val sendsByGrade = sends.associateBy { it.grade }
    SwatchLegend(labels.zip(shades))
    Spacer(Modifier.height(10.dp))
    // Hardest grade on top, like a pyramid.
    GradeRows(
        rows = entries.asReversed().map { entry ->
            val counts = listOf(entry.flashCount, entry.redpointCount, entry.attemptCount)
            val outcomes = counts.indices.filter { counts[it] > 0 }.joinToString(" · ") { "${labels[it]} ${counts[it]}" }
            GradeRow(entry.grade, counts.zip(shades), listOfNotNull(sendsByGrade[entry.grade]?.let { sendsAndProblems(it) }, outcomes))
        },
    )
}

@Composable
private fun SendsPerGrade(entries: List<BoardGradePyramidEntry>, zones: IntensityZones?) {
    if (entries.isEmpty()) return
    if (zones != null) {
        // The colours of zoneColorForDifficulty, as in the end-of-session summary.
        SwatchLegend(
            listOf(
                stringResource(R.string.board_zone_warmup) to GradeEasy,
                stringResource(R.string.board_zone_optimal) to GradeMedium,
                stringResource(R.string.board_zone_limit) to GradeHard,
            ),
        )
        Spacer(Modifier.height(10.dp))
    }
    GradeRows(
        rows = entries.asReversed().map { entry ->
            val color = zoneColorForDifficulty(entry.difficultyInt.toDouble(), zones)
            GradeRow(entry.grade, listOf(entry.count to color), listOf(sendsAndProblems(entry)))
        },
    )
}

/** One grade row: its segments, each a count and a colour, and the detail lines shown on tap. */
private class GradeRow(val grade: String, val segments: List<Pair<Int, Color>>, val details: List<String>) {
    val total: Int get() = segments.sumOf { it.first }
}

@Composable
private fun GradeRows(rows: List<GradeRow>) {
    val most = rows.maxOf { it.total }.coerceAtLeast(1)
    var selected by remember(rows.map { it.grade }) { mutableStateOf<String?>(null) }
    // Wide enough for the longest grade and count at any font size: "6b+" must not lose its "+".
    val gradeStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val gradeWidth = maxOf(44.dp, widestText(rows.map { it.grade }, gradeStyle) + 8.dp)
    val countWidth = maxOf(36.dp, widestText(rows.map { "${it.total}" }, MaterialTheme.typography.labelMedium) + 6.dp)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            val expanded = selected == row.grade
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (expanded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                    .clickable { selected = if (expanded) null else row.grade }
                    .semantics { contentDescription = "${row.grade}: ${row.details.joinToString("; ")}" }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.grade,
                        style = gradeStyle,
                        maxLines = 1,
                        modifier = Modifier.width(gradeWidth),
                    )
                    Canvas(Modifier.weight(1f).height(16.dp)) {
                        val gap = 2.dp.toPx()
                        val full = size.width * row.total / most
                        val shown = row.segments.filter { it.first > 0 }
                        val width = (full - gap * (shown.size - 1)).coerceAtLeast(2f)
                        var x = 0f
                        shown.forEach { (n, color) ->
                            val w = (width * n / row.total).coerceAtLeast(2.dp.toPx())
                            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
                            x += w + gap
                        }
                    }
                    Text(
                        "${row.total}",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(countWidth),
                    )
                }
                if (expanded) {
                    row.details.forEachIndexed { i, detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = gradeWidth, top = if (i == 0) 4.dp else 2.dp),
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
    // The axes and the end labels as wide and tall as their text at the current font size.
    val yAxis = valueAxisWidth(gradeTicks.map { GradeDisplayHelper.formatDifficulty(it.toDouble(), gradeScale) }, axisStyle)
    val timeAxis = timeAxisHeight(axisStyle)
    val endLabels = maxOf(
        PROGRESS_END_LABELS,
        widestText(series.map { GradeDisplayHelper.formatDifficulty(it.last().performanceDifficulty, gradeScale) }, endStyle) + 16.dp,
    )
    val description = series.mapIndexed { i, points ->
        val from = GradeDisplayHelper.formatDifficulty(points.first().performanceDifficulty, gradeScale)
        val to = GradeDisplayHelper.formatDifficulty(points.last().performanceDifficulty, gradeScale)
        if (names[i].isNotEmpty()) "${names[i]}: $from → $to" else "$from → $to"
    }.joinToString("; ")

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(PROGRESS_PLOT_HEIGHT + timeAxis)
            .semantics { contentDescription = description },
    ) {
        val left = yAxis.toPx()
        val right = size.width - endLabels.toPx()
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
            // Inside the chart: a line ending at its top must not push its label off the card.
            val labelTop = max(max(natural, lastBottom + 1.dp.toPx()), 0f)
                .coerceAtMost(PROGRESS_PLOT_HEIGHT.toPx() - text.size.height)
            val x = right + 8.dp.toPx()
            // With several lines each label leads with its board's dot, so it is clear whose it is.
            if (series.size > 1 || abs(labelTop - natural) > 2.dp.toPx()) {
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
    var lastYear: Int? = null
    starts.forEach { start ->
        // The year on the first label shown of every year, also when January has no room.
        val name = months[start.monthValue - 1] + if (start.year != lastYear) " '${start.year % 100}" else ""
        val text = measurer.measure(name, style)
        val x = xOf(start.coerceAtLeast(first)).coerceAtMost(right - text.size.width)
        if (x > lastEnd + 8.dp.toPx()) {
            drawLine(grid, Offset(x, gridTop), Offset(x, gridBottom), 1.dp.toPx())
            drawText(text, topLeft = Offset(x, labelTop))
            lastEnd = x + text.size.width
            lastYear = start.year
        }
    }
}

private val PROGRESS_PLOT_HEIGHT = 140.dp
private val PROGRESS_AXIS_HEIGHT = 20.dp
private val PROGRESS_Y_AXIS = 36.dp
private val PROGRESS_END_LABELS = 44.dp

/** A value axis wide enough for its [labels], at least the usual 36 dp. */
@Composable
private fun valueAxisWidth(labels: List<String>, style: TextStyle): Dp = maxOf(PROGRESS_Y_AXIS, widestText(labels, style) + 6.dp)

/** A time axis tall enough for its month names at the current font size. */
@Composable
private fun timeAxisHeight(style: TextStyle): Dp = maxOf(PROGRESS_AXIS_HEIGHT, textLineHeight(style) + 4.dp)

/** "3" or "3.5": a tick of the climbing days per week. */
private fun dayTick(value: Double): String = if (value == floor(value)) "${value.toInt()}" else "%.1f".format(value)

// ---------------------------------------------------------------- activity

@Composable
private fun activityViewLabel(view: ActivityView): String = when (view) {
    ActivityView.CALENDAR -> stringResource(R.string.board_stats_activity)
    ActivityView.DAYS_PER_WEEK -> stringResource(R.string.board_stats_days_per_week)
    ActivityView.ENTRIES -> stringResource(R.string.board_stats_entries_over_time)
    ActivityView.SENDS -> stringResource(R.string.board_stats_sends_over_time)
}

/**
 * When you climbed: the days as a calendar, climbing days per week, or all
 * entries or only the sends per week (per month over long periods) by grade
 * band. The charts over time span the whole [period].
 */
@Composable
internal fun ActivityCard(
    view: ActivityView,
    onSelect: (ActivityView) -> Unit,
    activityMap: Map<LocalDate, Int>,
    daysPerWeek: List<DaysPerWeek>,
    entries: List<VolumeBucket>,
    sends: List<VolumeBucket>,
    interval: StatsTimeInterval,
    period: StatsPeriod? = null,
    averageDaysPerWeek: Double = 0.0,
) {
    if (activityMap.isEmpty() && entries.isEmpty()) return
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
            ActivityView.DAYS_PER_WEEK -> DaysPerWeekChart(daysPerWeek, averageDaysPerWeek)
            ActivityView.ENTRIES -> VolumeChart(entries, stringResource(R.string.board_stats_entries_over_time), period)
            ActivityView.SENDS -> VolumeChart(sends, stringResource(R.string.board_stats_sends_over_time), period)
        }
    }
}

/**
 * Columns per week or month on a time axis over the whole [period], so time
 * off stays visible as gaps, stacked by grade band in the grade colours and
 * topped by the entries without a grade in grey.
 */
@Composable
private fun VolumeChart(entries: List<VolumeBucket>, title: String, period: StatsPeriod?) {
    if (entries.isEmpty()) return
    val bands = listOf(GradeEasy, GradeMedium, GradeHard, GradeElite, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f))
    val used = listOf(
        entries.any { it.easyCount > 0 }, entries.any { it.mediumCount > 0 },
        entries.any { it.hardCount > 0 }, entries.any { it.eliteCount > 0 },
        entries.any { it.ungradedCount > 0 },
    )
    // Only the bands that occur.
    SwatchLegend(
        listOf(
            stringResource(R.string.board_stats_grade_easy),
            stringResource(R.string.board_stats_grade_medium),
            stringResource(R.string.board_stats_grade_hard),
            stringResource(R.string.board_stats_grade_elite),
            stringResource(R.string.board_stats_grade_ungraded),
        ).zip(bands).filterIndexed { i, _ -> used[i] },
    )
    Spacer(Modifier.height(10.dp))
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ink = MaterialTheme.colorScheme.onSurface
    val months = MONTH_LABELS.map { stringResource(it) }
    val most = entries.maxOf { it.total }.coerceAtLeast(1)
    val first = minOf(period?.axisStart ?: entries.first().start, entries.first().start)
    val last = maxOf(period?.axisEnd ?: entries.last().start, entries.last().let { it.start.plusDays(it.days - 1L) })
    val days = (last.toEpochDay() - first.toEpochDay() + 1).toFloat()
    val description = "$title: " + entries.joinToString("; ") { "${it.start}: ${it.total}" }
    val yAxis = valueAxisWidth(listOf("$most"), axisStyle)
    val timeAxis = timeAxisHeight(axisStyle)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(WEEKLY_PLOT_HEIGHT + timeAxis)
            .semantics { contentDescription = description },
    ) {
        val left = yAxis.toPx()
        val right = size.width
        val top = 6.dp.toPx()
        val bottom = WEEKLY_PLOT_HEIGHT.toPx()
        fun xOf(day: LocalDate) = left + (day.toEpochDay() - first.toEpochDay()) / days * (right - left)
        val dayWidth = (right - left) / days

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
            val span = dayWidth * entry.days
            val gap = min(2.dp.toPx(), span * 0.25f)
            val barWidth = (span - gap).coerceAtLeast(1.dp.toPx())
            val x = xOf(entry.start) + gap / 2
            var y = bottom
            val counts = listOf(entry.easyCount, entry.mediumCount, entry.hardCount, entry.eliteCount, entry.ungradedCount)
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

/**
 * Climbing days per week as columns on a time axis over the whole period,
 * per week or, over long periods, as the weekly average of each month. A
 * faint line marks the [average] over the whole period.
 */
@Composable
private fun DaysPerWeekChart(entries: List<DaysPerWeek>, average: Double) {
    if (entries.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val averageStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val ink = MaterialTheme.colorScheme.onSurface
    val months = MONTH_LABELS.map { stringResource(it) }
    val first = entries.first().start
    val last = entries.last().let { it.start.plusDays(it.days - 1L) }
    val days = (last.toEpochDay() - first.toEpochDay() + 1).toFloat()
    val top = max(3.0, ceil(max(entries.maxOf { it.perWeek }, average))).coerceAtMost(7.0)
    val averageLabel = stringResource(R.string.board_stats_days_per_week_average, average)
    val description = stringResource(R.string.board_stats_days_per_week) + ": " + averageLabel + ". " +
        entries.joinToString("; ") { "${it.start}: ${"%.1f".format(it.perWeek)}" }

    // The average above the chart, so it never covers a column; none over a short period.
    if (average > 0.0) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.width(16.dp).height(2.dp)) {
            drawLine(ink.copy(alpha = 0.55f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        Spacer(Modifier.width(6.dp))
        Text(averageLabel, style = averageStyle)
    }
    val yAxis = valueAxisWidth(listOf(top / 2, top).map { dayTick(it) }, axisStyle)
    val timeAxis = timeAxisHeight(axisStyle)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(WEEKLY_PLOT_HEIGHT + timeAxis)
            .semantics { contentDescription = description },
    ) {
        val left = yAxis.toPx()
        val right = size.width
        val plotTop = 6.dp.toPx()
        val bottom = WEEKLY_PLOT_HEIGHT.toPx()
        fun xOf(day: LocalDate) = left + (day.toEpochDay() - first.toEpochDay()) / days * (right - left)
        fun yOf(v: Double) = bottom - (v / top).toFloat() * (bottom - plotTop)
        val dayWidth = (right - left) / days

        listOf(top / 2, top).forEach { value ->
            val y = yOf(value)
            drawLine(ink.copy(alpha = 0.10f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            val text = measurer.measure(dayTick(value), axisStyle)
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
        }
        drawLine(ink.copy(alpha = 0.20f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        drawMonthAxis(
            measurer, axisStyle, months, first, last, ::xOf,
            gridTop = plotTop, gridBottom = bottom, labelTop = bottom + 2.dp.toPx(),
            right = right, grid = ink.copy(alpha = 0.06f),
        )
        entries.forEach { entry ->
            if (entry.perWeek <= 0.0) return@forEach
            val span = dayWidth * entry.days
            val gap = min(2.dp.toPx(), span * 0.25f)
            val width = (span - gap).coerceAtLeast(1.dp.toPx())
            val y = yOf(entry.perWeek)
            val r = min(2.dp.toPx(), width / 2)
            drawRoundRect(OrangeAccent, Offset(xOf(entry.start) + gap / 2, y), Size(width, bottom - y), CornerRadius(r))
            if (bottom - y > r) drawRect(OrangeAccent, Offset(xOf(entry.start) + gap / 2, y + r), Size(width, bottom - y - r))
        }
        // The average over the period; its value stands above the chart.
        val y = yOf(average)
        if (average > 0.0) drawLine(ink.copy(alpha = 0.55f), Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
}
