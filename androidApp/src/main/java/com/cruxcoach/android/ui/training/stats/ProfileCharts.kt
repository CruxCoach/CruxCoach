package com.cruxcoach.android.ui.training.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.body.epochDay
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.LogbookSummaries
import kotlinx.datetime.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One measurement placed at the grade the athlete climbed when it was taken. */
data class PositionPoint(val difficulty: Double, val pctBw: Double, val day: LocalDate, val estimated: Boolean)

/** Font label of a difficulty, upper-cased like the rest of the app shows boulder grades ("6C+"). */
fun fontLabel(difficulty: Double): String = LogbookSummaries.fontOf(difficulty)?.uppercase() ?: "–"

/**
 * Where the athlete stands among climbers of a grade: x = boulder grade,
 * y = strength in % of body weight. The shaded band is the orientation from
 * [GradeStrengthNorms], the dashed line its middle; the athlete's own values
 * form a path over time, oldest faint, the newest highlighted with a label.
 */
@Composable
fun GradePositionChart(
    metric: GradeStrengthNorms.Metric,
    points: List<PositionPoint>,
    newestLabel: String?,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
) {
    val accent = CruxCoachDesign.colors.brandAccent
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val bandEdge = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    val calloutStyle = TextStyle(fontSize = 12.sp, color = onSurface, fontWeight = FontWeight.SemiBold)

    // Grade window: around the athlete's points, at least two Font grades wide, inside the band's range.
    val dMin = (points.minOfOrNull { it.difficulty } ?: 18.0)
    val dMax = (points.maxOfOrNull { it.difficulty } ?: 22.0)
    val xMin = floor(min(dMin - 2.0, dMax - 6.0)).coerceAtLeast(13.0)
    val xMax = ceil(max(dMax + 2.0, xMin + 6.0)).coerceAtMost(32.0)
    val bandAt = { d: Double -> GradeStrengthNorms.band(metric, d) }
    val bandValues = listOfNotNull(bandAt(xMin)?.low, bandAt(xMax)?.high)
    val yValues = points.map { it.pctBw } + bandValues
    val yMin = floor(((yValues.minOrNull() ?: 90.0) - 8.0) / 10.0) * 10.0
    val yMax = ceil(((yValues.maxOrNull() ?: 160.0) + 8.0) / 10.0) * 10.0

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${yMax.roundToInt()} %", style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text("${((yMax + yMin) / 2).roundToInt()} %", style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text("${yMin.roundToInt()} %", style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                fun x(d: Double) = ((d - xMin) / (xMax - xMin)).toFloat() * w
                fun y(v: Double) = h - ((v - yMin) / (yMax - yMin)).toFloat() * h
                (0..4).forEach { i -> drawLine(gridColor, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1f) }

                // Band: upper edge left → right, lower edge back.
                val steps = 40
                val xs = (0..steps).map { xMin + (xMax - xMin) * it / steps }
                val bands = xs.mapNotNull { d -> bandAt(d)?.let { d to it } }
                if (bands.size >= 2) {
                    val area = Path()
                    bands.forEachIndexed { i, (d, b) -> if (i == 0) area.moveTo(x(d), y(b.high)) else area.lineTo(x(d), y(b.high)) }
                    bands.reversed().forEach { (d, b) -> area.lineTo(x(d), y(b.low)) }
                    area.close()
                    drawPath(area, bandColor)
                    val center = Path()
                    bands.forEachIndexed { i, (d, b) -> if (i == 0) center.moveTo(x(d), y(b.center)) else center.lineTo(x(d), y(b.center)) }
                    drawPath(center, bandEdge, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }

                // The athlete's path, oldest faint.
                val sorted = points.sortedBy { it.day.epochDay() }
                if (sorted.size >= 2) {
                    sorted.zipWithNext().forEachIndexed { i, (a, b) ->
                        val alpha = 0.25f + 0.65f * (i + 1) / sorted.size
                        drawLine(accent.copy(alpha = alpha), Offset(x(a.difficulty), y(a.pctBw)), Offset(x(b.difficulty), y(b.pctBw)),
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                sorted.forEachIndexed { i, p ->
                    val newest = i == sorted.lastIndex
                    val alpha = if (newest) 1f else 0.35f + 0.5f * (i + 1) / sorted.size
                    val c = Offset(x(p.difficulty), y(p.pctBw))
                    if (newest) drawCircle(accent.copy(alpha = 0.25f), radius = 11.dp.toPx(), center = c)
                    if (p.estimated) {
                        drawCircle(surface, radius = (if (newest) 6 else 4).dp.toPx(), center = c)
                        drawCircle(accent.copy(alpha = alpha), radius = (if (newest) 6 else 4).dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                    } else {
                        drawCircle(accent.copy(alpha = alpha), radius = (if (newest) 6 else 4).dp.toPx(), center = c)
                    }
                }
                val newest = sorted.lastOrNull()
                if (newest != null && newestLabel != null) {
                    val layout = measurer.measure(newestLabel, calloutStyle)
                    val c = Offset(x(newest.difficulty), y(newest.pctBw))
                    val pad = 6.dp.toPx()
                    val boxW = layout.size.width + 2 * pad
                    val boxH = layout.size.height + pad
                    val left = (c.x - boxW / 2).coerceIn(0f, w - boxW)
                    val top = if (c.y - boxH - 14.dp.toPx() > 0) c.y - boxH - 14.dp.toPx() else c.y + 14.dp.toPx()
                    drawRoundRect(surface.copy(alpha = 0.92f), topLeft = Offset(left, top), size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(6.dp.toPx()))
                    drawRoundRect(accent, topLeft = Offset(left, top), size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
                    drawText(layout, topLeft = Offset(left + pad, top + pad / 2))
                }
                // Grade ticks along the bottom.
                val stepEvery = if (xMax - xMin > 10) 2 else 1
                var d = xMin
                var tick = 0
                while (d <= xMax + 1e-6) {
                    if (tick % stepEvery == 0) {
                        val layout = measurer.measure(fontLabel(d), labelStyle)
                        val lx = (x(d) - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
                        drawText(layout, topLeft = Offset(lx, h - layout.size.height - 2f))
                    }
                    d += 1.0
                    tick++
                }
            }
        }
    }
}

/** One row of an [AlignedTimeline]. */
data class TimelineRow(
    val label: String,
    val color: Color,
    val points: List<Pair<LocalDate, Double>>,
    val formatter: (Double) -> String,
)

/**
 * Several small line charts on one shared date axis, stacked — so working
 * grade and finger strength can be read week by week against each other.
 */
@Composable
fun AlignedTimeline(rows: List<TimelineRow>, description: String, modifier: Modifier = Modifier, rowHeight: Dp = 96.dp) {
    val all = rows.flatMap { it.points }
    if (all.isEmpty()) return
    val minX = all.minOf { it.first.epochDay() }
    val maxX = all.maxOf { it.first.epochDay() }.let { if (it == minX) minX + 1 else it }
    val first = all.minBy { it.first.epochDay() }.first
    val last = all.maxBy { it.first.epochDay() }.first
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Legend(row.color, row.label)
                    Spacer(Modifier.weight(1f))
                    row.points.maxByOrNull { it.first.epochDay() }?.let { (_, v) ->
                        Text(row.formatter(v), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (row.points.isEmpty()) {
                    Spacer(Modifier.height(rowHeight / 3))
                    return@Column
                }
                val values = row.points.map { it.second }
                val pad = ((values.max() - values.min()) * 0.15).coerceAtLeast(0.5)
                val minY = values.min() - pad
                val maxY = values.max() + pad
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    Column(Modifier.width(44.dp).fillMaxHeight().padding(end = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(row.formatter(maxY), style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
                        Text(row.formatter(minY), style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
                    }
                    Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        val w = size.width
                        val h = size.height
                        fun x(d: LocalDate) = ((d.epochDay() - minX).toFloat() / (maxX - minX).toFloat()) * w
                        fun y(v: Double) = h - ((v - minY) / (maxY - minY)).toFloat() * h
                        (0..2).forEach { i -> drawLine(gridColor, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), strokeWidth = 1f) }
                        val sorted = row.points.sortedBy { it.first.epochDay() }
                        if (sorted.size >= 2) {
                            val path = Path()
                            sorted.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
                            drawPath(path, row.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        sorted.forEach { (d, v) -> drawCircle(row.color, radius = 3.dp.toPx(), center = Offset(x(d), y(v))) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 44.dp)) {
            Text(first.shortLabel(), style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.weight(1f))
            if (last != first) Text(last.shortLabel(), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/**
 * Flash level, working grade and hardest send on one grade scale, with the
 * flash–working gap marked: how far the climber's everyday level sits under
 * what they can project.
 */
@Composable
fun GradeGapGauge(flash: Double?, working: Double?, max: Double?, labels: Triple<String, String, String>, description: String,
                  modifier: Modifier = Modifier) {
    val values = listOfNotNull(flash, working, max)
    if (values.isEmpty()) return
    val lo = floor(values.min() - 1.0)
    val hi = ceil(values.max() + 1.0).let { if (it - lo < 4) lo + 4 else it }
    val accent = CruxCoachDesign.colors.brandAccent
    val positive = CruxCoachDesign.colors.positive
    val maxColor = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val gapColor = accent.copy(alpha = 0.25f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val w = size.width
            val trackY = 18.dp.toPx()
            fun x(d: Double) = ((d - lo) / (hi - lo)).toFloat() * w
            drawRoundRect(track, topLeft = Offset(0f, trackY - 4.dp.toPx()), size = Size(w, 8.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()))
            if (flash != null && working != null && working > flash) {
                drawRect(gapColor, topLeft = Offset(x(flash), trackY - 4.dp.toPx()), size = Size(x(working) - x(flash), 8.dp.toPx()))
            }
            listOfNotNull(flash?.let { it to positive }, working?.let { it to accent }, max?.let { it to maxColor }).forEach { (d, c) ->
                drawCircle(c, radius = 7.dp.toPx(), center = Offset(x(d), trackY))
            }
            var d = lo
            while (d <= hi + 1e-6) {
                val layout = measurer.measure(fontLabel(d), labelStyle)
                drawText(layout, topLeft = Offset((x(d) - layout.size.width / 2f).coerceIn(0f, w - layout.size.width), trackY + 12.dp.toPx()))
                d += if (hi - lo > 8) 2.0 else 1.0
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (flash != null) Legend(positive, labels.first)
            if (working != null) Legend(accent, labels.second)
            if (max != null) Legend(maxColor, labels.third)
        }
    }
}

/**
 * Daily load of one structure as faint bars, with the 7-day mean (solid) and
 * the 28-day mean (dashed) on top: where the two lines part, load is
 * changing faster than the body is used to.
 */
@Composable
fun LoadTrendChart(series: List<Pair<LocalDate, Double>>, description: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    if (series.isEmpty()) return
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val acuteColor = CruxCoachDesign.colors.brandAccent
    val chronicColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val values = series.map { it.second }
    fun rolling(n: Int) = values.indices.map { i -> values.subList(max(0, i - n + 1), i + 1).average() }
    val acute = rolling(7)
    val chronic = rolling(28)
    val maxY = (values + acute + chronic).maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            val slot = w / series.size
            fun y(v: Double) = h - (v / maxY).toFloat() * h
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
            values.forEachIndexed { i, v ->
                if (v > 0) drawRect(barColor, topLeft = Offset(i * slot + slot * 0.15f, y(v)), size = Size(slot * 0.7f, h - y(v)))
            }
            fun line(points: List<Double>, color: Color, dashed: Boolean) {
                val path = Path()
                points.forEachIndexed { i, v ->
                    val px = i * slot + slot / 2
                    if (i == 0) path.moveTo(px, y(v)) else path.lineTo(px, y(v))
                }
                drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
            line(chronic, chronicColor, dashed = true)
            line(acute, acuteColor, dashed = false)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(series.first().first.shortLabel(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(series.last().first.shortLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One area of the bottleneck profile. */
data class BottleneckBar(val label: String, val score: Double?, val reason: String)

/**
 * Diverging bars around "typical for your grade": left (caution colour) is
 * weaker than typical, right (positive colour) stronger. Areas without a norm
 * show their reason instead of a bar.
 */
@Composable
fun BottleneckBars(bars: List<BottleneckBar>, modifier: Modifier = Modifier) {
    val weak = CruxCoachDesign.colors.caution
    val strong = CruxCoachDesign.colors.positive
    val track = MaterialTheme.colorScheme.surfaceVariant
    val mid = MaterialTheme.colorScheme.outline
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        bars.forEach { b ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.width(120.dp))
                    if (b.score != null) {
                        Canvas(Modifier.weight(1f).height(14.dp)) {
                            val w = size.width
                            val h = size.height
                            val c = w / 2
                            drawRoundRect(track, size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                            val len = (b.score.coerceIn(-1.0, 1.0) * c).toFloat()
                            val color = if (len < 0) weak else strong
                            val left = if (len < 0) c + len else c
                            drawRoundRect(color, topLeft = Offset(left, 0f), size = Size(kotlin.math.abs(len).coerceAtLeast(3f), h),
                                cornerRadius = CornerRadius(h / 2))
                            drawLine(mid, Offset(c, -2f), Offset(c, h + 2f), strokeWidth = 2f)
                        }
                    } else {
                        Box(Modifier.weight(1f).height(14.dp).background(track.copy(alpha = 0.5f), RoundedCornerShape(7.dp)))
                    }
                }
                Text(b.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 120.dp, top = 2.dp))
            }
        }
    }
}
