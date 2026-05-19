package com.cruxcoach.android.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.cruxcoach.android.R

private val ChartOrange = Color(0xFFFF6B1A)
private val ChartGrey = Color(0xFF9E9E9E)
private val ChartTeal = Color(0xFF26A69A)
private val ChartBlue = Color(0xFF42A5F5)
private val ChartAmber = Color(0xFFFFA726)
private val ChartPurple = Color(0xFFAB47BC)

@Composable
fun StatsScreen(
    state: MapState,
    modifier: Modifier = Modifier,
) {
    if (state.unfilteredLocations.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.map_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Stats always reflect the full dataset (all boards), independent
    // of the active map filters — the filter-count footer is where
    // "matches your filters" lives, not here.
    val stats = state.unfilteredStats
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeaderCard(stats = stats)
        }

        item {
            DonutCard(
                title = stringResource(R.string.map_stats_layout_distribution),
                slices = listOf(
                    DonutSlice(stringResource(R.string.map_stats_layout_original), stats.originalCount, ChartOrange),
                    DonutSlice(stringResource(R.string.map_stats_layout_homewall), stats.homewallCount, ChartGrey),
                ),
            )
        }

        item {
            DonutCard(
                title = stringResource(R.string.map_stats_access_distribution),
                slices = listOf(
                    DonutSlice(stringResource(R.string.map_access_public), stats.publicCount, ChartTeal),
                    DonutSlice(stringResource(R.string.map_access_private), stats.privateCount, ChartPurple),
                    DonutSlice(stringResource(R.string.map_access_members), stats.membersCount, ChartBlue),
                    DonutSlice(stringResource(R.string.map_marker_field_unknown), stats.accessUnknownCount, ChartGrey),
                ),
            )
        }

        item {
            DonutCard(
                title = stringResource(R.string.map_stats_adjustability_distribution),
                slices = listOf(
                    DonutSlice(stringResource(R.string.map_adjustability_adjustable), stats.adjustableCount, ChartAmber),
                    DonutSlice(stringResource(R.string.map_adjustability_fixed), stats.fixedCount, ChartBlue),
                    DonutSlice(stringResource(R.string.map_marker_field_unknown), stats.adjUnknownCount, ChartGrey),
                ),
            )
        }

        item {
            BarChartCard(
                title = stringResource(R.string.map_stats_top_countries),
                items = stats.byCountry.take(15),
                barColor = ChartOrange,
            )
        }

        item {
            if (stats.bySize.isNotEmpty()) {
                BarChartCard(
                    title = stringResource(R.string.map_stats_size_distribution),
                    items = stats.bySize,
                    barColor = ChartTeal,
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(stats: MapStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${stats.total}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = ChartOrange,
            )
            Text(
                stringResource(R.string.map_stats_total_locations),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat(
                    label = stringResource(R.string.map_stats_label_countries),
                    value = stats.byCountry.size.toString(),
                )
                Stat(
                    label = stringResource(R.string.map_stats_label_public),
                    value = stats.publicCount.toString(),
                )
                Stat(
                    label = stringResource(R.string.map_stats_label_adjustable),
                    value = stats.adjustableCount.toString(),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class DonutSlice(val label: String, val value: Int, val color: Color)

@Composable
private fun DonutCard(
    title: String,
    slices: List<DonutSlice>,
) {
    val total = slices.sumOf { it.value }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (total == 0) {
                Text(
                    stringResource(R.string.map_stats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Donut(
                    slices = slices,
                    total = total,
                    modifier = Modifier.size(120.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    slices.filter { it.value > 0 }.forEach { slice ->
                        DonutLegendRow(slice = slice, total = total)
                    }
                }
            }
        }
    }
}

@Composable
private fun Donut(
    slices: List<DonutSlice>,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height)
        val strokeWidth = side * 0.18f
        val diameter = side - strokeWidth
        val topLeft = Offset(
            x = (size.width - diameter) / 2f,
            y = (size.height - diameter) / 2f,
        )
        val arcSize = Size(diameter, diameter)
        var startAngle = -90f
        for (slice in slices) {
            if (slice.value == 0) continue
            val sweep = (slice.value.toFloat() / total.toFloat()) * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun DonutLegendRow(slice: DonutSlice, total: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(slice.color),
        )
        Text(
            slice.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val percent = (slice.value.toFloat() / total.toFloat() * 100f).toInt()
        Text(
            "${slice.value}  ·  $percent%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BarChartCard(
    title: String,
    items: List<Pair<String, Int>>,
    barColor: Color,
) {
    val maxValue = items.maxOfOrNull { it.second } ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (items.isEmpty() || maxValue == 0) {
                Text(
                    stringResource(R.string.map_stats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }
            items.forEach { (label, count) ->
                BarRow(label = label, count = count, max = maxValue, color = barColor)
            }
        }
    }
}

@Composable
private fun BarRow(label: String, count: Int, max: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // The fraction-based fillMaxWidth on the inner Box gives a
            // simple proportional bar without a Canvas. Single colour
            // — categorical labels carry the meaning, not the bar fill.
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = count.toFloat() / max.toFloat())
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Text(
            count.toString(),
            modifier = Modifier
                .padding(start = 8.dp)
                .width(48.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
