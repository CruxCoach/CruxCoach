package com.cruxcoach.android.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.stats_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("stats_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    com.cruxcoach.android.ui.common.ErrorCard(
                        error = stringResource(R.string.stats_error),
                        onDismiss = { viewModel.clearError() },
                        onReportBug = {
                            onNavigateToBugReport(
                                context.getString(R.string.error_bug_report_stats_title),
                                context.getString(R.string.stats_error)
                            )
                            viewModel.clearError()
                        }
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Summary cards row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryCard(
                            value = state.highestGrade?.let { GradeDisplayHelper.formatGrade(it, state.gradeScale) } ?: "--",
                            label = stringResource(R.string.dashboard_best_grade),
                            color = GradeHard,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCard(
                            value = "${state.totalSends}",
                            label = stringResource(R.string.stats_sends),
                            color = SuccessGreen,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCard(
                            value = state.avgRpe?.let { "%.1f".format(it) } ?: "--",
                            label = stringResource(R.string.dashboard_avg_rpe),
                            color = OrangeAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Grade Pyramid
                    if (state.gradePyramid.isNotEmpty()) {
                        ChartSection(title = stringResource(R.string.stats_grade_pyramid)) {
                            GradePyramidChart(
                                entries = state.gradePyramid,
                                gradeScale = state.gradeScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((state.gradePyramid.size * 36 + 20).dp)
                            )
                        }
                    }

                    // RPE Trend
                    if (state.rpeTrend.size >= 2) {
                        ChartSection(title = stringResource(R.string.stats_rpe_trend)) {
                            RpeTrendChart(
                                entries = state.rpeTrend,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                        }
                    }

                    // Body stat charts (tiles + time filter + chart)
                    if (state.bodyStatTrends.isNotEmpty()) {
                        BodyStatChartsSection(
                            bodyStatTrends = state.bodyStatTrends,
                            selectedBodyStat = state.selectedBodyStat,
                            timeRange = state.bodyStatTimeRange,
                            compareEnabled = state.compareEnabled,
                            compareBodyStat = state.compareBodyStat,
                            onTimeRangeChanged = viewModel::onBodyStatTimeRangeChanged,
                            onBodyStatSelected = viewModel::onBodyStatSelected,
                            onCompareToggled = viewModel::onCompareToggled,
                            onCompareStatSelected = viewModel::onCompareStatSelected
                        )
                    }

                    // Style distribution
                    if (state.styleDistribution.isNotEmpty()) {
                        ChartSection(title = stringResource(R.string.stats_style_dist)) {
                            StyleDistributionChart(
                                data = state.styleDistribution,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            )
                        }
                    }

                    // Empty state
                    if (state.gradePyramid.isEmpty() && state.rpeTrend.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.stats_no_data),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.stats_no_data_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChartSection(
    title: String,
    content: @Composable () -> Unit
) {
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

@Composable
private fun GradePyramidChart(
    entries: List<GradePyramidEntry>,
    gradeScale: com.cruxcoach.android.data.GradeScale = com.cruxcoach.android.data.GradeScale.V_SCALE,
    modifier: Modifier = Modifier
) {
    val maxCount = entries.maxOfOrNull { it.count } ?: 1L
    val textColor = MaterialTheme.colorScheme.onSurface
    val gradeLabels = entries.map { GradeDisplayHelper.formatGrade(it.grade, gradeScale) }

    Canvas(modifier = modifier) {
        val barHeight = 24.dp.toPx()
        val spacing = 12.dp.toPx()
        val labelWidth = 48.dp.toPx()
        val chartWidth = size.width - labelWidth - 8.dp.toPx()

        entries.forEachIndexed { index, entry ->
            val y = index * (barHeight + spacing)
            val barWidth = (entry.count.toFloat() / maxCount) * chartWidth

            val barColor = when {
                entry.numeric <= 2 -> GradeEasy
                entry.numeric <= 5 -> GradeMedium
                entry.numeric <= 9 -> GradeHard
                else -> GradeElite
            }

            // Grade label
            drawContext.canvas.nativeCanvas.drawText(
                gradeLabels[index],
                0f,
                y + barHeight * 0.7f,
                android.graphics.Paint().apply {
                    color = textColor.hashCode()
                    textSize = 12.sp.toPx()
                    isAntiAlias = true
                }
            )

            // Bar
            drawRoundRect(
                color = barColor,
                topLeft = Offset(labelWidth, y),
                size = Size(barWidth.coerceAtLeast(4.dp.toPx()), barHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
            )

            // Count label
            drawContext.canvas.nativeCanvas.drawText(
                "${entry.count}",
                labelWidth + barWidth + 6.dp.toPx(),
                y + barHeight * 0.7f,
                android.graphics.Paint().apply {
                    color = textColor.hashCode()
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }
            )
        }
    }
}

@Composable
private fun RpeTrendChart(
    entries: List<RpeTrendEntry>,
    modifier: Modifier = Modifier
) {
    val lineColor = OrangeAccent
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val leftPad = 32.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad

        val minRpe = 1f
        val maxRpe = 10f

        // Grid lines
        for (rpe in listOf(3f, 5f, 7f, 9f)) {
            val y = chartHeight - ((rpe - minRpe) / (maxRpe - minRpe)) * chartHeight
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${rpe.toInt()}",
                4.dp.toPx(),
                y + 4.dp.toPx(),
                android.graphics.Paint().apply {
                    color = textColor.hashCode()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }
            )
        }

        if (entries.size < 2) return@Canvas

        // Line path
        val path = Path()
        val stepX = chartWidth / (entries.size - 1)

        entries.forEachIndexed { index, entry ->
            val x = leftPad + index * stepX
            val y = chartHeight - ((entry.rpe.toFloat() - minRpe) / (maxRpe - minRpe)) * chartHeight

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }

            // Data point dot
            drawCircle(
                color = rpePointColor(entry.rpe),
                radius = 4.dp.toPx(),
                center = Offset(x, y)
            )
        }

        // Draw line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun StyleDistributionChart(
    data: Map<String, Long>,
    modifier: Modifier = Modifier
) {
    val total = data.values.sum().toFloat().coerceAtLeast(1f)
    val colors = listOf(OrangeAccent, SuccessGreen, InfoBlue, GradeElite, WarningYellow, ErrorRed, SessionDeload)
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier) {
        data.entries.forEachIndexed { index, (style, count) ->
            val fraction = count / total
            val color = colors[index % colors.size]
            val displayName = styleDisplayName(style)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                ) {
                    // Track
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    // Fill
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(
                                color = color,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "$count",
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
private fun styleDisplayName(style: String): String = when (style.uppercase()) {
    "SLAB" -> stringResource(R.string.style_slab)
    "VERT" -> stringResource(R.string.style_vert)
    "OVERHANG" -> stringResource(R.string.style_overhang)
    "ROOF" -> stringResource(R.string.style_roof)
    "DYNO" -> stringResource(R.string.style_dyno)
    "COMP" -> stringResource(R.string.style_comp)
    "UNKNOWN" -> stringResource(R.string.style_unknown)
    else -> style
}

private fun rpePointColor(rpe: Double): Color = when {
    rpe <= 5.0 -> SuccessGreen
    rpe <= 7.0 -> WarningYellow
    rpe <= 8.5 -> OrangeAccent
    else -> ErrorRed
}
