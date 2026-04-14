package com.cruxcoach.android.ui.board

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.domain.board.IntensityZones
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun intensityAlpha(count: Int): Float = when {
    count == 0 -> 0f
    count == 1 -> 0.30f
    count <= 3 -> 0.50f
    count <= 6 -> 0.75f
    else -> 1.0f
}

@Composable
internal fun BoardActivityHeatmap(
    activityMap: Map<LocalDate, Int>,
    interval: StatsTimeInterval
) {
    val today = remember { LocalDate.now() }
    val totalDays = when (interval) {
        StatsTimeInterval.DAYS_30 -> 30
        StatsTimeInterval.DAYS_90 -> 90
        StatsTimeInterval.YEAR_1 -> 365
        StatsTimeInterval.ALL -> 730 // 2yr cap
    }
    val startDate = today.minusDays(totalDays.toLong() - 1)

    // Align start to Monday
    val alignedStart = startDate.with(DayOfWeek.MONDAY)
    val totalWeeks = (ChronoUnit.DAYS.between(alignedStart, today) / 7 + 1).toInt()

    val cellSize = 12.dp
    val spacing = 3.dp
    val dayLabelWidth = 24.dp
    val monthLabelHeight = 16.dp
    val cellWithSpacing = cellSize + spacing
    val gridWidth = dayLabelWidth + cellWithSpacing * totalWeeks

    val accentColor = OrangeAccent
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val dayLabels = listOf("Mo", "", "Mi", "", "Fr", "", "So")
    val monthNames = arrayOf(
        stringResource(R.string.month_jan), stringResource(R.string.month_feb),
        stringResource(R.string.month_mar), stringResource(R.string.month_apr),
        stringResource(R.string.month_may), stringResource(R.string.month_jun),
        stringResource(R.string.month_jul), stringResource(R.string.month_aug),
        stringResource(R.string.month_sep), stringResource(R.string.month_oct),
        stringResource(R.string.month_nov), stringResource(R.string.month_dec)
    )

    val scrollState = rememberScrollState()

    // Auto-scroll to end (most recent)
    LaunchedEffect(totalWeeks) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Day labels column (fixed)
            Column(modifier = Modifier.width(dayLabelWidth)) {
                Spacer(modifier = Modifier.height(monthLabelHeight))
                dayLabels.forEachIndexed { _, label ->
                    Box(
                        modifier = Modifier.height(cellWithSpacing),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = labelColor
                            )
                        }
                    }
                }
            }

            // Scrollable grid
            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
            ) {
                // Precompute per-week: month (from Monday) and boundaries
                val weekMonths = IntArray(totalWeeks)
                val monthBoundaryWeeks = mutableSetOf<Int>()
                val yearBoundaryWeeks = mutableSetOf<Int>()
                for (week in 0 until totalWeeks) {
                    val ws = alignedStart.plusWeeks(week.toLong())
                    weekMonths[week] = ws.monthValue
                    if (week > 0) {
                        if (ws.monthValue != weekMonths[week - 1]) monthBoundaryWeeks.add(week)
                        if (ws.year != alignedStart.plusWeeks((week - 1).toLong()).year) yearBoundaryWeeks.add(week)
                    }
                }

                val monthTintA = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                val monthTintB = androidx.compose.ui.graphics.Color.Transparent
                val monthLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
                val yearLineColor = OrangeAccent.copy(alpha = 0.6f)

                // Month labels row
                Row(modifier = Modifier.height(monthLabelHeight)) {
                    var lastMonth = -1
                    for (week in 0 until totalWeeks) {
                        val month = weekMonths[week]
                        Box(modifier = Modifier.width(cellWithSpacing)) {
                            if (month != lastMonth) {
                                Text(
                                    text = monthNames[month - 1],
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = labelColor,
                                    softWrap = false,
                                    modifier = Modifier.wrapContentWidth(
                                        align = Alignment.Start,
                                        unbounded = true
                                    )
                                )
                                lastMonth = month
                            }
                        }
                    }
                }

                // Grid cells — all columns exactly cellWithSpacing wide,
                // boundary lines drawn with drawBehind (no extra layout width)
                Row {
                    for (week in 0 until totalWeeks) {
                        val month = weekMonths[week]
                        val bgTint = if (month % 2 == 0) monthTintA else monthTintB
                        val isYearBoundary = week in yearBoundaryWeeks
                        val isMonthBoundary = week in monthBoundaryWeeks

                        Box(
                            modifier = Modifier
                                .width(cellWithSpacing)
                                .background(bgTint)
                                .drawBehind {
                                    if (isYearBoundary) {
                                        drawLine(
                                            color = yearLineColor,
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, size.height),
                                            strokeWidth = 2.dp.toPx()
                                        )
                                    } else if (isMonthBoundary) {
                                        drawLine(
                                            color = monthLineColor,
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, size.height),
                                            strokeWidth = 1.dp.toPx()
                                        )
                                    }
                                }
                        ) {
                            Column {
                                for (dayOfWeek in 0 until 7) {
                                    val date = alignedStart.plusWeeks(week.toLong()).plusDays(dayOfWeek.toLong())
                                    val count = if (date.isAfter(today) || date.isBefore(startDate)) {
                                        -1
                                    } else {
                                        activityMap[date] ?: 0
                                    }

                                    Box(
                                        modifier = Modifier
                                            .padding(bottom = spacing, end = spacing)
                                            .size(cellSize)
                                            .background(
                                                color = when {
                                                    count < 0 -> emptyColor.copy(alpha = 0.05f)
                                                    count == 0 -> emptyColor
                                                    else -> accentColor.copy(alpha = intensityAlpha(count))
                                                },
                                                shape = RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HeatmapLegend() {
    val accentColor = OrangeAccent
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    val levels = listOf(0f, 0.30f, 0.50f, 0.75f, 1.0f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.board_heatmap_less),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        levels.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (alpha == 0f) emptyColor else accentColor.copy(alpha = alpha),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(
            stringResource(R.string.board_heatmap_more),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun BoardGradePyramidChart(
    entries: List<BoardGradePyramidEntry>,
    zones: IntensityZones? = null,
    modifier: Modifier = Modifier
) {
    val maxCount = entries.maxOfOrNull { it.count } ?: 1
    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val barHeight = 24.dp.toPx()
        val spacing = 12.dp.toPx()
        val labelWidth = 48.dp.toPx()
        val chartWidth = size.width - labelWidth - 8.dp.toPx()

        entries.forEachIndexed { index, entry ->
            val y = index * (barHeight + spacing)
            val barWidth = (entry.count.toFloat() / maxCount) * chartWidth

            val barColor = zoneColorForDifficulty(entry.difficultyInt.toDouble(), zones)

            drawContext.canvas.nativeCanvas.drawText(
                entry.grade,
                0f,
                y + barHeight * 0.7f,
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 12.sp.toPx()
                    isAntiAlias = true
                }
            )

            drawRoundRect(
                color = barColor,
                topLeft = Offset(labelWidth, y),
                size = Size(barWidth.coerceAtLeast(4.dp.toPx()), barHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
            )

            drawContext.canvas.nativeCanvas.drawText(
                "${entry.count}",
                labelWidth + barWidth + 6.dp.toPx(),
                y + barHeight * 0.7f,
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }
            )
        }
    }
}

@Composable
internal fun BoardAngleDistChart(
    entries: List<AngleDistEntry>,
    modifier: Modifier = Modifier
) {
    val total = entries.sumOf { it.count }.toFloat().coerceAtLeast(1f)

    Column(modifier = modifier) {
        entries.forEach { entry ->
            val fraction = entry.count / total

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${entry.angle}°",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(
                                color = OrangeAccent,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${entry.count}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
internal fun BoardSendsOverTimeChart(
    entries: List<TimeBucketEntry>,
    modifier: Modifier = Modifier
) {
    val maxCount = entries.maxOfOrNull { it.count } ?: 1
    val barColor = OrangeAccent
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val leftPad = 28.dp.toPx()
        val bottomPad = 32.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad

        // Grid lines
        val gridSteps = 3
        for (i in 1..gridSteps) {
            val y = chartHeight - (i.toFloat() / gridSteps) * chartHeight
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            val gridValue = (maxCount.toFloat() / gridSteps * i).toInt()
            drawContext.canvas.nativeCanvas.drawText(
                "$gridValue",
                2.dp.toPx(),
                y + 4.dp.toPx(),
                Paint().apply {
                    color = textColor.hashCode()
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                }
            )
        }

        if (entries.isEmpty()) return@Canvas

        val barSpacing = 2.dp.toPx()
        val totalBars = entries.size
        val availableWidth = chartWidth - barSpacing * (totalBars + 1)
        val barWidth = (availableWidth / totalBars).coerceAtLeast(4.dp.toPx())

        entries.forEachIndexed { index, entry ->
            val x = leftPad + barSpacing + index * (barWidth + barSpacing)
            val barHeight = (entry.count.toFloat() / maxCount) * chartHeight

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, chartHeight - barHeight),
                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx())
            )

            // X-axis labels (show every Nth to avoid overlap)
            val showEvery = when {
                totalBars <= 8 -> 1
                totalBars <= 16 -> 2
                totalBars <= 30 -> 4
                else -> 6
            }
            if (index % showEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    entry.label,
                    x,
                    size.height - 4.dp.toPx(),
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

@Composable
internal fun ChartSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
