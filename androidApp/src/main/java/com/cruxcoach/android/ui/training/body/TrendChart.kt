package com.cruxcoach.android.ui.training.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.athlete.logic.TrendWeight
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.datetime.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun LocalDate.epochDay(): Long = toEpochDays().toLong()

internal fun LocalDate.shortLabel(): String =
    java.time.LocalDate.parse(toString()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * Body-weight chart with a real time axis: a week without weighing is a gap,
 * not two neighbouring points. Raw readings are faint dots, the trend is the
 * bold line. With [hideNumbers] neither the dots nor the axis values are
 * drawn — only the shape of the trend remains.
 */
@Composable
fun TrendChart(
    points: List<TrendWeight.Point>,
    hideNumbers: Boolean,
    units: UnitSystem,
    description: String,
    modifier: Modifier = Modifier,
) {
    val accent = CruxCoachDesign.colors.brandAccent
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val values = points.flatMap { if (hideNumbers) listOf(it.trend) else listOf(it.raw, it.trend) }
    val minY = (values.minOrNull() ?: 0.0) - 0.5
    val maxY = (values.maxOrNull() ?: 1.0) + 0.5
    val minX = points.firstOrNull()?.day?.epochDay() ?: 0L
    val maxX = (points.lastOrNull()?.day?.epochDay() ?: 1L).let { if (it == minX) minX + 1 else it }

    Column(modifier.semantics { contentDescription = description }.testTag("body_chart")) {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            if (!hideNumbers) {
                Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMass(maxY, units), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMass(minY, units), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                fun x(day: LocalDate) = ((day.epochDay() - minX).toFloat() / (maxX - minX).toFloat()) * w
                fun y(v: Double) = h - ((v - minY) / (maxY - minY)).toFloat() * h
                (0..3).forEach { i ->
                    val gy = h * i / 3f
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }
                if (!hideNumbers) {
                    points.forEach { p -> drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(x(p.day), y(p.raw))) }
                }
                if (points.size >= 2) {
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(x(p.day), y(p.trend)) else path.lineTo(x(p.day), y(p.trend))
                    }
                    drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else if (points.size == 1) {
                    drawCircle(accent, radius = 5.dp.toPx(), center = Offset(w / 2f, h / 2f))
                }
            }
        }
        if (points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = if (hideNumbers) 0.dp else 48.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(points.first().day.shortLabel(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(points.last().day.shortLabel(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One line per exercise of % body weight over time (strength-to-weight). */
@Composable
fun StrengthChart(
    series: Map<String, List<StrengthPoint>>,
    colors: Map<String, Color>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val all = series.values.flatten()
    if (all.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val minY = all.minOf { it.percentBodyweight } - 5.0
    val maxY = all.maxOf { it.percentBodyweight } + 5.0
    val minX = all.minOf { it.day.epochDay() }
    val maxX = all.maxOf { it.day.epochDay() }.let { if (it == minX) minX + 1 else it }
    Row(modifier.height(140.dp).semantics { contentDescription = description }.testTag("body_strength_chart")) {
        Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${maxY.toInt()} %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${minY.toInt()} %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width
            val h = size.height
            fun x(day: LocalDate) = ((day.epochDay() - minX).toFloat() / (maxX - minX).toFloat()) * w
            fun y(v: Double) = h - ((v - minY) / (maxY - minY)).toFloat() * h
            (0..2).forEach { i -> drawLine(gridColor, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), strokeWidth = 1f) }
            series.forEach { (slug, points) ->
                val color = colors[slug] ?: Color.Gray
                val sorted = points.sortedBy { it.day.epochDay() }
                if (sorted.size >= 2) {
                    val path = Path()
                    sorted.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(x(p.day), y(p.percentBodyweight)) else path.lineTo(x(p.day), y(p.percentBodyweight))
                    }
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                sorted.forEach { p -> drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(x(p.day), y(p.percentBodyweight))) }
            }
        }
    }
}
