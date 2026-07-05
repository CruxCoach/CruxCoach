package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

/**
 * Reusable chart section with a dropdown selector in the title area.
 * The dropdown lets the user pick from an enum of chart views without cluttering the UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChartSectionWithSelector(
    options: Array<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: @Composable (T) -> String,
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
            // Dropdown selector as title
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                Row(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labelOf(selected),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.cd_select_view),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    labelOf(option),
                                    fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSelect(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** Period comparison card with delta indicators */
@Composable
internal fun BoardPeriodComparisonCard(comparison: PeriodComparison) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${comparison.currentLabel} vs. ${comparison.previousLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DeltaChip(stringResource(R.string.board_sends), comparison.totalSendsDelta, isPercentage = false)
            DeltaChip(stringResource(R.string.board_stats_flash_rate), comparison.flashRateDelta.toInt(), isPercentage = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DeltaChip(stringResource(R.string.board_stats_hardest), comparison.hardestGradeDelta, isPercentage = false)
            DeltaChip(stringResource(R.string.board_stats_unique), comparison.uniqueClimbsDelta, isPercentage = false)
        }
    }
}

@Composable
private fun DeltaChip(label: String, delta: Int, isPercentage: Boolean) {
    val positive = delta > 0
    val neutral = delta == 0
    val color = when {
        positive -> SuccessGreen
        neutral -> Slate80
        else -> GradeHard
    }
    val icon = when {
        positive -> Icons.AutoMirrored.Filled.TrendingUp
        neutral -> Icons.AutoMirrored.Filled.TrendingFlat
        else -> Icons.AutoMirrored.Filled.TrendingDown
    }
    val suffix = if (isPercentage) "%" else ""
    val prefix = if (positive) "+" else ""

    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
            Column {
                Text(
                    "$prefix$delta$suffix",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/** Personal records row — horizontal scrolling highlights */
@Composable
internal fun BoardPersonalRecordsRow(records: PersonalRecords) {
    val hardestFlashLabel = stringResource(R.string.board_stats_hardest_flash)
    val mostSendsLabel = stringResource(R.string.board_stats_most_sends_day)
    val avgSessionsLabel = stringResource(R.string.board_stats_avg_sessions_week)
    val weekStreakLabel = stringResource(R.string.board_stats_week_streak)
    val items = buildList {
        records.hardestFlashGrade?.let {
            add(hardestFlashLabel to it)
        }
        if (records.mostSendsInDay > 0) {
            add(mostSendsLabel to "${records.mostSendsInDay}")
        }
        if (records.avgSessionsPerWeek > 0.0) {
            add(avgSessionsLabel to String.format("%.1f", records.avgSessionsPerWeek))
        }
        if (records.weekStreak > 0) {
            add(weekStreakLabel to "${records.weekStreak}")
        }
    }
    if (items.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, value) ->
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = OrangeAccent.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Custom date range trigger chip */
@Composable
internal fun CustomDateChip(
    isActive: Boolean,
    customFrom: java.time.LocalDate?,
    customTo: java.time.LocalDate?,
    onClick: () -> Unit
) {
    val label = if (isActive && customFrom != null && customTo != null) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM")
        "${customFrom.format(fmt)} - ${customTo.format(fmt)}"
    } else {
        stringResource(R.string.board_stats_date_range)
    }

    FilterChip(
        selected = isActive,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OrangeAccent,
            selectedLabelColor = DarkBackground
        ),
        shape = RoundedCornerShape(20.dp)
    )
}
