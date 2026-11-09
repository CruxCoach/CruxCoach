package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.*

/**
 * Reusable chart section with a dropdown selector in the title area.
 * The dropdown lets the user pick from an enum of chart views without cluttering the UI.
 * An optional [help] text for the selected view sits behind an info button
 * beside the title, so the chart itself needs no explanation underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChartSectionWithSelector(
    options: Array<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: @Composable (T) -> String,
    help: String? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = labelOf(selected),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
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
                if (help != null) {
                    InfoButton(labelOf(selected), help)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
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
            selectedLabelColor = CruxCoachDesign.colors.onBrandAccent
        ),
        shape = RoundedCornerShape(20.dp)
    )
}
