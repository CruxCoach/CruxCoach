package com.cruxcoach.android.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.GradeElite
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.domain.model.BodyStatTimeRange
import com.cruxcoach.domain.model.StatCategory
import com.cruxcoach.domain.model.StatRegistry
import com.cruxcoach.domain.model.TrendEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BodyStatChartsSection(
    bodyStatTrends: Map<String, List<TrendEntry>>,
    selectedBodyStat: String,
    timeRange: BodyStatTimeRange,
    compareEnabled: Boolean,
    compareBodyStat: String?,
    onTimeRangeChanged: (BodyStatTimeRange) -> Unit,
    onBodyStatSelected: (String) -> Unit,
    onCompareToggled: (Boolean) -> Unit,
    onCompareStatSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bodyStatTrends.isEmpty()) return

    val statsWithData = remember(bodyStatTrends) {
        bodyStatTrends.filter { it.value.isNotEmpty() }.keys
    }
    val statsWithChart = remember(bodyStatTrends) {
        bodyStatTrends.filter { it.value.size >= 2 }.keys
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Summary Tiles
        SummaryTilesRow(
            bodyStatTrends = bodyStatTrends,
            statsWithData = statsWithData,
            selectedBodyStat = selectedBodyStat,
            onBodyStatSelected = onBodyStatSelected,
        )

        Spacer(Modifier.height(12.dp))

        // 2. Time Range Filter
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BodyStatTimeRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = timeRange == range,
                    onClick = { onTimeRangeChanged(range) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = BodyStatTimeRange.entries.size,
                    ),
                ) {
                    Text(range.labelDe)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3. Metric Card with dropdown, stats, chart, compare
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                MetricDropdown(
                    selectedKey = selectedBodyStat,
                    availableKeys = statsWithChart,
                    onSelected = onBodyStatSelected,
                )

                val entries = bodyStatTrends[selectedBodyStat].orEmpty()
                if (entries.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    StatsLines(entries = entries, statKey = selectedBodyStat)

                    Spacer(Modifier.height(8.dp))
                    val secondaryEntries = if (compareEnabled && compareBodyStat != null) {
                        bodyStatTrends[compareBodyStat].orEmpty()
                    } else {
                        emptyList()
                    }
                    BodyStatTrendChart(
                        entries = entries,
                        unit = StatRegistry.unit(selectedBodyStat),
                        lineColor = categoryColor(selectedBodyStat),
                        secondaryEntries = secondaryEntries,
                        secondaryUnit = compareBodyStat?.let { StatRegistry.unit(it) } ?: "",
                        secondaryColor = GradeElite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                CompareSection(
                    compareEnabled = compareEnabled,
                    compareBodyStat = compareBodyStat,
                    excludeKey = selectedBodyStat,
                    availableKeys = statsWithChart,
                    onCompareToggled = onCompareToggled,
                    onCompareStatSelected = onCompareStatSelected,
                )
            }
        }
    }
}

// ── Summary Tiles ──

@Composable
private fun SummaryTilesRow(
    bodyStatTrends: Map<String, List<TrendEntry>>,
    statsWithData: Set<String>,
    selectedBodyStat: String,
    onBodyStatSelected: (String) -> Unit,
) {
    val orderedKeys = remember(statsWithData) {
        StatRegistry.ALL.map { it.key }.filter { it in statsWithData }
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(orderedKeys, key = { it }) { key ->
            val entries = bodyStatTrends[key].orEmpty()
            val current = entries.lastOrNull()?.value ?: return@items
            val delta = if (entries.size >= 2) current - entries.first().value else null
            val def = StatRegistry.get(key)
            val isSelected = key == selectedBodyStat

            OutlinedCard(
                modifier = Modifier
                    .width(100.dp)
                    .clickable { onBodyStatSelected(key) },
                border = if (isSelected) {
                    BorderStroke(2.dp, categoryColor(key))
                } else {
                    CardDefaults.outlinedCardBorder()
                },
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = formatValue(current) + " " + (def?.unit ?: ""),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (delta != null) {
                        val arrow = if (delta >= 0) "\u2191" else "\u2193"
                        Text(
                            text = "$arrow${formatValue(kotlin.math.abs(delta))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = deltaColor(delta, def?.higherIsBetter ?: true),
                        )
                    }
                    Text(
                        text = def?.labelDe ?: key,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Metric Dropdown ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricDropdown(
    selectedKey: String,
    availableKeys: Set<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = "${StatRegistry.labelDe(selectedKey)} (${StatRegistry.unit(selectedKey)})"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StatRegistry.byCategory.forEach { (category, definitions) ->
                val visible = definitions.filter { it.key in availableKeys }
                if (visible.isEmpty()) return@forEach
                DropdownMenuItem(
                    text = {
                        Text(
                            text = category.labelDe,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                visible.forEach { def ->
                    DropdownMenuItem(
                        text = { Text("${def.labelDe} (${def.unit})") },
                        onClick = {
                            onSelected(def.key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ── Stats Lines ──

@Composable
private fun StatsLines(entries: List<TrendEntry>, statKey: String) {
    val values = entries.map { it.value }
    val current = values.last()
    val delta = current - values.first()
    val avg = values.average()
    val min = values.min()
    val max = values.max()
    val count = values.size
    val def = StatRegistry.get(statKey)
    val unit = def?.unit ?: ""
    val higherIsBetter = def?.higherIsBetter ?: true
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val arrow = if (delta >= 0) "\u2191" else "\u2193"
    val deltaText = "$arrow${formatValue(kotlin.math.abs(delta))} $unit"

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatValue(current)} $unit",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(" \u00B7 ", style = MaterialTheme.typography.bodySmall, color = color)
            Text(
                text = deltaText,
                style = MaterialTheme.typography.bodySmall,
                color = deltaColor(delta, higherIsBetter),
            )
            Text(" \u00B7 ", style = MaterialTheme.typography.bodySmall, color = color)
            Text(
                text = "\u00D8 ${formatValue(avg)}",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
        Row {
            Text(
                text = pluralStringResource(
                    R.plurals.stats_body_range,
                    count,
                    formatValue(min),
                    formatValue(max),
                    count,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

// ── Compare Section ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareSection(
    compareEnabled: Boolean,
    compareBodyStat: String?,
    excludeKey: String,
    availableKeys: Set<String>,
    onCompareToggled: (Boolean) -> Unit,
    onCompareStatSelected: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = compareEnabled, onCheckedChange = onCompareToggled)
        Text(stringResource(R.string.stats_compare_with), style = MaterialTheme.typography.bodyMedium)
    }

    if (compareEnabled) {
        var expanded by remember { mutableStateOf(false) }
        val label = compareBodyStat?.let {
            "${StatRegistry.labelDe(it)} (${StatRegistry.unit(it)})"
        } ?: stringResource(R.string.dropdown_selection_placeholder)

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                StatRegistry.ALL
                    .filter { it.key in availableKeys && it.key != excludeKey }
                    .forEach { def ->
                        DropdownMenuItem(
                            text = { Text("${def.labelDe} (${def.unit})") },
                            onClick = {
                                onCompareStatSelected(def.key)
                                expanded = false
                            },
                        )
                    }
            }
        }
    }
}

// ── Helpers ──

private fun categoryColor(statKey: String): Color {
    val def = StatRegistry.get(statKey) ?: return InfoBlue
    return when (def.category) {
        StatCategory.BODY_COMPOSITION -> InfoBlue
        StatCategory.CLIMBING_SPECIFIC -> OrangeAccent
        StatCategory.MOBILITY -> SuccessGreen
    }
}

private fun deltaColor(delta: Double, higherIsBetter: Boolean): Color {
    if (delta == 0.0) return Color.Gray
    val positive = delta > 0
    return if (positive == higherIsBetter) SuccessGreen else ErrorRed
}

private fun formatValue(value: Double): String {
    return if (value == value.toLong().toDouble()) "%.0f".format(value) else "%.1f".format(value)
}
