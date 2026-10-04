package com.cruxcoach.android.ui.training.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.body.epochDay
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.athlete.logic.ConsistencyStreak
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One line of a [DateLineChart]. */
data class ChartSeries(val label: String, val color: Color, val points: List<Pair<LocalDate, Double>>)

/** One week of a [WeeklyBarChart]: stacked segments, bottom first. */
data class WeekBar(val start: LocalDate, val stacks: List<Pair<Color, Double>>)

/** Two clearly distinguishable series colours (left/right, climbing/off-board) that work in both themes. */
@Composable
fun seriesColors(): List<Color> = listOf(CruxCoachDesign.colors.brandAccent, MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.primary, CruxCoachDesign.colors.positive)

/**
 * Line chart over a real date axis (a month without training is a gap, not
 * two neighbouring points). Optional [level] draws a dashed reference line,
 * e.g. the current performance value.
 */
@Composable
fun DateLineChart(
    series: List<ChartSeries>,
    valueFormatter: @Composable (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    level: Double? = null,
    showAxis: Boolean = true,
) {
    val all = series.flatMap { it.points }
    if (all.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val levelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = all.map { it.second } + listOfNotNull(level)
    val spread = ((values.max() - values.min()) * 0.1).coerceAtLeast(0.5)
    val minY = values.min() - spread
    val maxY = values.max() + spread
    val minX = all.minOf { it.first.epochDay() }
    val maxX = all.maxOf { it.first.epochDay() }.let { if (it == minX) minX + 1 else it }
    val firstDay = all.minBy { it.first.epochDay() }.first
    val lastDay = all.maxBy { it.first.epochDay() }.first

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(height)) {
            if (showAxis) {
                Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(valueFormatter(maxY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(valueFormatter(minY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                fun x(day: LocalDate) = if (all.size == 1) w / 2f else ((day.epochDay() - minX).toFloat() / (maxX - minX).toFloat()) * w
                fun y(v: Double) = h - ((v - minY) / (maxY - minY)).toFloat() * h
                if (showAxis) (0..3).forEach { i -> drawLine(gridColor, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1f) }
                level?.let { l ->
                    drawLine(levelColor, Offset(0f, y(l)), Offset(w, y(l)), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
                series.forEach { s ->
                    val sorted = s.points.sortedBy { it.first.epochDay() }
                    if (sorted.size >= 2) {
                        val path = Path()
                        sorted.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
                        drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    sorted.forEach { (d, v) -> drawCircle(s.color, radius = 3.5.dp.toPx(), center = Offset(x(d), y(v))) }
                }
            }
        }
        if (showAxis) {
            Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp)) {
                Text(firstDay.shortLabel(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (lastDay != firstDay) {
                    Text(lastDay.shortLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (series.size > 1) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                series.forEach { s -> Legend(s.color, s.label) }
            }
        }
    }
}

@Composable
fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Small line without axes for list rows. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max().let { if (it == min) min + 1 else it }
    Canvas(modifier.size(width = 72.dp, height = 28.dp)) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * step
            val py = size.height - ((v - min) / (max - min)).toFloat() * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Stacked weekly bars, oldest left. */
@Composable
fun WeeklyBarChart(
    weeks: List<WeekBar>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    valueFormatter: @Composable (Double) -> String = { it.toInt().toString() },
) {
    if (weeks.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxValue = weeks.maxOf { w -> w.stacks.sumOf { it.second } }.coerceAtLeast(1.0)
    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(valueFormatter(maxValue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
                val slot = w / weeks.size
                val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
                weeks.forEachIndexed { i, week ->
                    var top = h
                    val left = i * slot + (slot - barWidth) / 2f
                    week.stacks.forEach { (color, value) ->
                        if (value <= 0.0) return@forEach
                        val barHeight = (value / maxValue).toFloat() * h
                        drawRoundRect(color, topLeft = Offset(left, top - barHeight), size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                        top -= barHeight
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp)) {
            Text(weeks.first().start.shortLabel(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(weeks.last().start.shortLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Training calendar: one column per week (Monday on top), four shades for
 * rest / light / training / hard day, today outlined.
 */
@Composable
fun CalendarHeatmap(
    days: Map<LocalDate, Int>,
    today: LocalDate,
    description: String,
    modifier: Modifier = Modifier,
    weeks: Int = 16,
) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val accent = CruxCoachDesign.colors.brandAccent
    val shades = listOf(empty, accent.copy(alpha = 0.35f), accent.copy(alpha = 0.65f), accent)
    val outline = MaterialTheme.colorScheme.onSurface
    val firstWeek = ConsistencyStreak.weekStart(today).minus(DatePeriod(days = (weeks - 1) * 7))
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        val cell = minOf(size.width / weeks, size.height / 7f)
        val gap = cell * 0.16f
        for (week in 0 until weeks) {
            for (dow in 0 until 7) {
                val day = firstWeek.plus(DatePeriod(days = week * 7 + dow))
                if (day > today) continue
                val level = (days[day] ?: 0).coerceIn(0, 3)
                val topLeft = Offset(week * cell + gap / 2, dow * cell + gap / 2)
                val s = Size(cell - gap, cell - gap)
                drawRoundRect(shades[level], topLeft = topLeft, size = s, cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                if (day == today) {
                    drawRoundRect(outline, topLeft = topLeft, size = s, cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
    }
}
