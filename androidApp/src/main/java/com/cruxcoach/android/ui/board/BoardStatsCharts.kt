package com.cruxcoach.android.ui.board

import android.graphics.Paint
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.domain.board.IntensityZones

// Colors for outcome breakdown
private val FlashColor = SuccessGreen
private val RedpointColor = OrangeAccent
private val AttemptColor = Slate80

/**
 * Stacked horizontal bars per grade: flash (green) | redpoint (orange) | attempt (gray).
 * Replaces the simple grade pyramid with a richer outcome view.
 */
@Composable
internal fun BoardGradeOutcomeChart(
    entries: List<GradeOutcomeEntry>,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val maxCount = entries.maxOf { it.total }.coerceAtLeast(1)
    val textColor = MaterialTheme.colorScheme.onSurface

    // Legend
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        LegendDot(FlashColor, "Flash")
        LegendDot(RedpointColor, "Redpoint")
        LegendDot(AttemptColor, stringResource(R.string.board_stats_attempt))
    }

    Canvas(
        modifier = modifier.then(
            Modifier
                .fillMaxWidth()
                .height((entries.size * 36 + 8).dp)
        )
    ) {
        val barHeight = 22.dp.toPx()
        val spacing = 14.dp.toPx()
        val labelWidth = 48.dp.toPx()
        val chartWidth = size.width - labelWidth - 8.dp.toPx()

        entries.forEachIndexed { index, entry ->
            val y = index * (barHeight + spacing)
            val totalWidth = (entry.total.toFloat() / maxCount) * chartWidth

            // Grade label
            drawContext.canvas.nativeCanvas.drawText(
                entry.grade, 0f, y + barHeight * 0.72f,
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 12.sp.toPx()
                    isAntiAlias = true
                }
            )

            // Stacked segments
            var xOffset = labelWidth
            val segments = listOf(
                entry.flashCount to FlashColor,
                entry.redpointCount to RedpointColor,
                entry.attemptCount to AttemptColor
            )
            segments.forEach { (count, color) ->
                if (count > 0) {
                    val segWidth = (count.toFloat() / maxCount) * chartWidth
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(xOffset, y),
                        size = Size(segWidth.coerceAtLeast(2.dp.toPx()), barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                    xOffset += segWidth
                }
            }

            // Total count label
            drawContext.canvas.nativeCanvas.drawText(
                "${entry.total}", xOffset + 6.dp.toPx(), y + barHeight * 0.72f,
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }
            )
        }
    }
}

/**
 * Donut chart showing overall flash / redpoint / attempt distribution.
 * Center text shows total sends.
 */
@Composable
internal fun BoardOutcomeDonutChart(
    distribution: OutcomeDistribution,
    modifier: Modifier = Modifier
) {
    if (distribution.total == 0 && distribution.attempts == 0) return
    val total = (distribution.flashes + distribution.redpoints).toFloat().coerceAtLeast(1f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val sendsLabel = stringResource(R.string.board_sends)

    // Legend
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        LegendDot(FlashColor, "Flash (${distribution.flashes})")
        LegendDot(RedpointColor, "Redpoint (${distribution.redpoints})")
    }

    Box(
        modifier = modifier.then(Modifier.fillMaxWidth().height(180.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val strokeWidth = 28.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            val flashAngle = (distribution.flashes / total) * 360f
            val redpointAngle = (distribution.redpoints / total) * 360f

            // Flash arc
            drawArc(
                color = FlashColor,
                startAngle = -90f,
                sweepAngle = flashAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Redpoint arc
            drawArc(
                color = RedpointColor,
                startAngle = -90f + flashAngle,
                sweepAngle = redpointAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Center text
            val totalSends = distribution.flashes + distribution.redpoints
            drawContext.canvas.nativeCanvas.drawText(
                "$totalSends",
                center.x, center.y + 8.dp.toPx(),
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 24.sp.toPx()
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                    isAntiAlias = true
                }
            )
            drawContext.canvas.nativeCanvas.drawText(
                sendsLabel,
                center.x, center.y + 26.dp.toPx(),
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 11.sp.toPx()
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
            )
        }
    }
}

/**
 * Weekly volume chart — stacked vertical bars by grade band (easy/medium/hard/elite).
 */
@Composable
internal fun BoardWeeklyVolumeChart(
    entries: List<WeeklyVolumeEntry>,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val maxCount = entries.maxOf { it.total }.coerceAtLeast(1)
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Legend
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        LegendDot(GradeEasy, stringResource(R.string.board_stats_grade_easy))
        LegendDot(GradeMedium, stringResource(R.string.board_stats_grade_medium))
        LegendDot(GradeHard, stringResource(R.string.board_stats_grade_hard))
        LegendDot(GradeElite, stringResource(R.string.board_stats_grade_elite))
    }

    Canvas(modifier = modifier.then(Modifier.fillMaxWidth().height(160.dp))) {
        val leftPad = 28.dp.toPx()
        val bottomPad = 32.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad

        // Grid lines
        for (i in 1..3) {
            val y = chartHeight - (i.toFloat() / 3) * chartHeight
            drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), 1.dp.toPx())
            val gridVal = (maxCount.toFloat() / 3 * i).toInt()
            drawContext.canvas.nativeCanvas.drawText(
                "$gridVal", 2.dp.toPx(), y + 4.dp.toPx(),
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                }
            )
        }

        val barSpacing = 2.dp.toPx()
        val totalBars = entries.size
        val availableWidth = chartWidth - barSpacing * (totalBars + 1)
        val barWidth = (availableWidth / totalBars).coerceAtLeast(4.dp.toPx())

        entries.forEachIndexed { index, entry ->
            val x = leftPad + barSpacing + index * (barWidth + barSpacing)
            var yBottom = chartHeight

            val segments = listOf(
                entry.easyCount to GradeEasy,
                entry.mediumCount to GradeMedium,
                entry.hardCount to GradeHard,
                entry.eliteCount to GradeElite
            )
            segments.forEach { (count, color) ->
                if (count > 0) {
                    val segHeight = (count.toFloat() / maxCount) * chartHeight
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, yBottom - segHeight),
                        size = Size(barWidth, segHeight.coerceAtLeast(2.dp.toPx())),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                    yBottom -= segHeight
                }
            }

            // X-axis labels
            val showEvery = when {
                totalBars <= 8 -> 1
                totalBars <= 16 -> 2
                else -> 4
            }
            if (index % showEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    entry.weekLabel, x, size.height - 4.dp.toPx(),
                    Paint().apply {
                        color = textColor.hashCode()
                        textSize = 8.sp.toPx()
                        isAntiAlias = true
                    }
                )
            }
        }
    }
}

/**
 * Rolling performance level from the best distinct sends in each four-week window.
 */
@Composable
internal fun BoardGradeProgressionChart(
    entries: List<GradeProgressionPoint>,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    modifier: Modifier = Modifier
) {
    if (entries.size < 2) return
    val minDiff = entries.minOf { it.performanceDifficulty }
    val maxDiff = entries.maxOf { it.performanceDifficulty }
    val range = (maxDiff - minDiff).coerceAtLeast(2.0)
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = OrangeAccent
    val dotCenterColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = modifier.then(Modifier.fillMaxWidth().height(160.dp))) {
        val leftPad = 40.dp.toPx()
        val bottomPad = 32.dp.toPx()
        val topPad = 8.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad - topPad

        // Y-axis grade labels (3 tick marks)
        for (i in 0..2) {
            val diff = minDiff + range * (i.toDouble() / 2)
            val y = topPad + chartHeight - (i.toFloat() / 2) * chartHeight
            drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), 1.dp.toPx())
            val gradeLabel = com.cruxcoach.android.util.GradeDisplayHelper.formatDifficulty(diff, gradeScale)
            drawContext.canvas.nativeCanvas.drawText(
                gradeLabel, 2.dp.toPx(), y + 4.dp.toPx(),
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }
            )
        }

        // Plot points and lines
        val points = entries.mapIndexed { index, entry ->
            val x = leftPad + progressionXFraction(entries, index) * chartWidth
            val y = topPad + chartHeight - ((entry.performanceDifficulty - minDiff) / range).toFloat() * chartHeight
            Offset(x, y)
        }

        // Lines
        for (i in 0 until points.size - 1) {
            drawLine(lineColor, points[i], points[i + 1], 2.dp.toPx(), StrokeCap.Round)
        }

        // Dots
        points.forEach { point ->
            drawCircle(lineColor, 4.dp.toPx(), point)
            drawCircle(dotCenterColor, 2.dp.toPx(), point)
        }

        // X-axis labels
        val showEvery = when {
            entries.size <= 8 -> 1
            entries.size <= 16 -> 2
            else -> 4
        }
        entries.forEachIndexed { index, entry ->
            if (index % showEvery == 0) {
                val x = leftPad + progressionXFraction(entries, index) * chartWidth
                drawContext.canvas.nativeCanvas.drawText(
                    entry.label, x, size.height - 4.dp.toPx(),
                    Paint().apply {
                        color = textColor.hashCode()
                        textSize = 8.sp.toPx()
                        isAntiAlias = true
                    }
                )
            }
        }
    }
}

/** Maps real week-start dates to the chart axis so inactive gaps remain visible. */
internal fun progressionXFraction(entries: List<GradeProgressionPoint>, index: Int): Float {
    if (entries.size < 2) return 0f
    val firstDay = entries.first().weekStart.toEpochDay()
    val span = (entries.last().weekStart.toEpochDay() - firstDay).coerceAtLeast(1L)
    return ((entries[index].weekStart.toEpochDay() - firstDay).toDouble() / span).toFloat()
}

/**
 * Unique climbs chart — grouped horizontal bars: unique (accent) vs total sends (muted).
 */
@Composable
internal fun BoardUniqueClimbsChart(
    entries: List<UniqueClimbEntry>,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val maxCount = entries.maxOf { it.totalSends }.coerceAtLeast(1)
    val textColor = MaterialTheme.colorScheme.onSurface

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        LegendDot(OrangeAccent, stringResource(R.string.board_stats_unique))
        LegendDot(Slate80.copy(alpha = 0.5f), stringResource(R.string.board_stats_total))
    }

    Column(modifier = modifier) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.grade,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp),
                    fontSize = 12.sp
                )
                Box(modifier = Modifier.weight(1f).height(20.dp)) {
                    // Total bar (background)
                    val totalFraction = entry.totalSends.toFloat() / maxCount
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(totalFraction.coerceAtLeast(0.02f))
                            .align(Alignment.CenterStart)
                            .background(Slate80.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    )
                    // Unique bar (foreground, narrower)
                    val uniqueFraction = entry.uniqueCount.toFloat() / maxCount
                    Box(
                        modifier = Modifier
                            .height(14.dp)
                            .fillMaxWidth(uniqueFraction.coerceAtLeast(0.02f))
                            .align(Alignment.CenterStart)
                            .background(OrangeAccent, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "${entry.uniqueCount}/${entry.totalSends}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** Small colored dot + label for chart legends */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}
