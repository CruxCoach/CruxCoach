package com.cruxcoach.android.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import com.cruxcoach.domain.model.TrendEntry

@Composable
internal fun BodyStatTrendChart(
    entries: List<TrendEntry>,
    unit: String,
    lineColor: Color,
    secondaryEntries: List<TrendEntry> = emptyList(),
    secondaryUnit: String = "",
    secondaryColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillColor = lineColor.copy(alpha = 0.1f)
    val hasSecondary = secondaryEntries.size >= 2

    Canvas(modifier = modifier) {
        if (entries.size < 2) return@Canvas

        val leftPad = 40.dp.toPx()
        val rightPad = if (hasSecondary) 40.dp.toPx() else 0f
        val bottomPad = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val chartWidth = size.width - leftPad - rightPad
        val chartHeight = size.height - bottomPad - topPad

        // Primary Y-axis scaling
        val primaryValues = entries.map { it.value.toFloat() }
        val primaryMin = (primaryValues.min() - 1f).coerceAtLeast(0f)
        val primaryMax = primaryValues.max() + 1f
        val primaryRange = (primaryMax - primaryMin).coerceAtLeast(1f)

        // Secondary Y-axis scaling
        val secondaryMin: Float
        val secondaryMax: Float
        val secondaryRange: Float
        if (hasSecondary) {
            val secondaryValues = secondaryEntries.map { it.value.toFloat() }
            secondaryMin = (secondaryValues.min() - 1f).coerceAtLeast(0f)
            secondaryMax = secondaryValues.max() + 1f
            secondaryRange = (secondaryMax - secondaryMin).coerceAtLeast(1f)
        } else {
            secondaryMin = 0f
            secondaryMax = 1f
            secondaryRange = 1f
        }

        val labelPaint = android.graphics.Paint().apply {
            color = textColor.hashCode()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }

        // Grid lines (4 evenly spaced)
        for (i in 0..3) {
            val fraction = i / 3f
            val primaryValue = primaryMin + primaryRange * fraction
            val y = topPad + chartHeight - fraction * chartHeight

            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1.dp.toPx(),
            )

            // Left Y-axis labels (primary)
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(primaryValue),
                2.dp.toPx(),
                y + 4.dp.toPx(),
                labelPaint,
            )

            // Right Y-axis labels (secondary)
            if (hasSecondary) {
                val secondaryValue = secondaryMin + secondaryRange * fraction
                val rightPaint = android.graphics.Paint().apply {
                    color = textColor.hashCode()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(secondaryValue),
                    size.width - 2.dp.toPx(),
                    y + 4.dp.toPx(),
                    rightPaint,
                )
            }
        }

        // Primary line and fill
        val linePath = Path()
        val fillPath = Path()
        val stepX = chartWidth / (entries.size - 1)

        entries.forEachIndexed { index, entry ->
            val x = leftPad + index * stepX
            val y = topPad + chartHeight -
                ((entry.value.toFloat() - primaryMin) / primaryRange) * chartHeight
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topPad + chartHeight)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = Offset(x, y))
        }

        fillPath.lineTo(leftPad + (entries.size - 1) * stepX, topPad + chartHeight)
        fillPath.close()
        drawPath(path = fillPath, color = fillColor)
        drawPath(path = linePath, color = lineColor, style = Stroke(width = 2.dp.toPx()))

        // Secondary line (dashed, no fill)
        if (hasSecondary) {
            val secondaryPath = Path()
            val secondaryStepX = chartWidth / (secondaryEntries.size - 1)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))

            secondaryEntries.forEachIndexed { index, entry ->
                val x = leftPad + index * secondaryStepX
                val y = topPad + chartHeight -
                    ((entry.value.toFloat() - secondaryMin) / secondaryRange) * chartHeight
                if (index == 0) {
                    secondaryPath.moveTo(x, y)
                } else {
                    secondaryPath.lineTo(x, y)
                }
                drawCircle(
                    color = secondaryColor,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y),
                )
            }

            drawPath(
                path = secondaryPath,
                color = secondaryColor,
                style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect),
            )
        }
    }
}
