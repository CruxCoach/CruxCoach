package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

/** Heatmap display modes */
enum class HeatmapMode {
    OFF,
    GLOBAL,
    PERSONAL,
    START,
    HAND,
    FOOT,
    FINISH
}

@Composable
private fun heatmapModeLabel(mode: HeatmapMode): String = when (mode) {
    HeatmapMode.OFF -> stringResource(R.string.board_heatmap_off)
    HeatmapMode.GLOBAL -> stringResource(R.string.board_heatmap_global)
    HeatmapMode.PERSONAL -> stringResource(R.string.board_heatmap_personal)
    HeatmapMode.START -> stringResource(R.string.board_heatmap_start)
    HeatmapMode.HAND -> stringResource(R.string.board_heatmap_hand)
    HeatmapMode.FOOT -> stringResource(R.string.board_heatmap_foot)
    HeatmapMode.FINISH -> stringResource(R.string.board_heatmap_finish)
}

/**
 * Full-screen bottom sheet for hold-based search and heatmap.
 * Contains interactive board + heatmap mode selector + hold search controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HoldSearchSheet(
    selectedHolds: Set<Int>,
    heatmapMode: HeatmapMode,
    heatmapData: Map<Int, Float>,
    matchCount: Int,
    isSearching: Boolean,
    placements: Map<Int, com.cruxcoach.data.repository.AuroraPlacement>,
    boardSize: com.cruxcoach.data.repository.BoardSize?,
    boardImages: List<com.cruxcoach.data.repository.BoardImage> = emptyList(),
    onHoldTapped: (Int) -> Unit,
    onHeatmapModeSelect: (HeatmapMode) -> Unit,
    onClearSelection: () -> Unit,
    onSearchByHolds: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.board_holdsearch_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Interactive board visualization
            if (placements.isNotEmpty()) {
                KilterBoardVisualization(
                    holds = emptyList(),
                    placements = placements,
                    boardSize = boardSize,
                    boardImages = boardImages,
                    heatmapData = heatmapData.ifEmpty { null },
                    selectedHolds = selectedHolds,
                    onHoldTapped = onHoldTapped,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Heatmap mode chips
            Text(stringResource(R.string.board_holdsearch_heatmap), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HeatmapMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == heatmapMode,
                        onClick = { onHeatmapModeSelect(mode) },
                        label = { Text(heatmapModeLabel(mode), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent,
                            selectedLabelColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            HorizontalDivider()

            // Hold selection section
            Text(stringResource(R.string.board_holdsearch_hold_search), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.board_holdsearch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedHolds.isNotEmpty()) {
                // Clear button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.board_holdsearch_holds_selected, selectedHolds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.board_holdsearch_reset))
                    }
                }

                // Match count + filter button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = OrangeAccent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.board_holdsearch_searching), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            stringResource(R.string.board_holdsearch_climbs_found, matchCount),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (matchCount > 0) SuccessGreen else GradeHard
                        )
                    }
                    Button(
                        onClick = onSearchByHolds,
                        enabled = matchCount > 0 && !isSearching,
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.board_holdsearch_filter), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Active hold-filter banner shown below the action bar when a hold filter is active.
 * Displays hold count, match count, and clear/edit buttons.
 */
@Composable
internal fun HoldSearchActionBar(
    holdFilterActive: Boolean,
    heatmapActive: Boolean,
    selectedCount: Int,
    matchCount: Int,
    onOpenSheet: () -> Unit,
    onClearFilter: () -> Unit
) {
    if (!holdFilterActive) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp), tint = OrangeAccent)
                Text(
                    stringResource(R.string.board_holdsearch_filter_summary, selectedCount, matchCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row {
                TextButton(
                    onClick = onOpenSheet,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.board_holdsearch_change), style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = onClearFilter,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.board_holdsearch_clear), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
