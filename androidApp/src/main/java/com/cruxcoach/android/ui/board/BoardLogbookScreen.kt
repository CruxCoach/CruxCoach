package com.cruxcoach.android.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BoardLogbookScreen(
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: BoardLogbookViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasSelection = state.selectedUuids.isNotEmpty()

    // Edit dialog
    if (state.showEditDialog) {
        AscentLoggingDialog(
            isEditing = true,
            isSend = true,
            bidCount = state.editBidCount,
            quality = state.editQuality,
            comment = state.editComment,
            onIsSendChanged = {},
            onBidCountChanged = { viewModel.updateEditBidCount(it) },
            onQualityChanged = { viewModel.updateEditQuality(it) },
            onCommentChanged = { viewModel.updateEditComment(it) },
            onSave = { viewModel.saveEdit() },
            onDismiss = { viewModel.dismissEditDialog() }
        )
    }

    // Batch delete confirm
    if (state.showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBatchDeleteConfirm() },
            title = { Text(stringResource(R.string.board_logbook_delete_title, state.selectedUuids.size), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_logbook_delete_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmBatchDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBatchDeleteConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Stats bottom sheet
    if (state.showStatsSheet) {
        BoardStatsSheet(
            stats = state.stats,
            statsInterval = state.statsInterval,
            gradeChartView = state.gradeChartView,
            timeChartView = state.timeChartView,
            distributionChartView = state.distributionChartView,
            gradeScale = state.gradeScale,
            zones = state.zones,
            customDateFrom = state.customDateFrom,
            customDateTo = state.customDateTo,
            heatmapMode = state.heatmapMode,
            heatmapData = state.heatmapData,
            placements = state.placements,
            boardSize = state.boardSize,
            boardImages = state.boardImages,
            boardFilter = state.boardFilter,
            availableBoardBrands = state.availableBoardBrands,
            onBoardFilterSelect = { viewModel.setBoardFilter(it) },
            onIntervalSelect = { viewModel.setStatsInterval(it) },
            onGradeChartViewSelect = { viewModel.setGradeChartView(it) },
            onTimeChartViewSelect = { viewModel.setTimeChartView(it) },
            onDistributionChartViewSelect = { viewModel.setDistributionChartView(it) },
            onCustomDateRange = { from, to -> viewModel.setCustomDateRange(from, to) },
            onHeatmapModeSelect = { viewModel.setHeatmapMode(it) },
            onDismiss = { viewModel.toggleStatsSheet() }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (hasSelection) Text(stringResource(R.string.board_logbook_selected, state.selectedUuids.size))
                        else Text(stringResource(R.string.board_logbook_title))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("logbook_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (state.hasData) {
                            val allSelected = state.ascents.isNotEmpty() &&
                                state.selectedUuids.size == state.ascents.size
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = stringResource(if (allSelected) R.string.cd_deselect_all else R.string.cd_select_all),
                                    tint = if (allSelected) OrangeAccent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { viewModel.requestBatchDelete() },
                                enabled = hasSelection
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_selected),
                                    tint = if (hasSelection) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.toggleStatsSheet() },
                                modifier = Modifier.testTag("logbook_stats_button")
                            ) {
                                Icon(
                                    Icons.Default.BarChart,
                                    contentDescription = stringResource(R.string.board_stats_title),
                                    tint = OrangeAccent
                                )
                            }
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
            !state.hasData -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    EmptyLogbookMessage()
                }
            }
            else -> {
                val listState = rememberLazyListState()

                val shouldLoadMore by remember {
                    derivedStateOf {
                        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val totalItems = state.ascents.size + 5 // account for header items
                        lastVisibleItem >= totalItems - 5 && state.canLoadMore && !state.isLoadingMore
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) viewModel.loadMore()
                }

                val grouped = remember(state.ascents) {
                    state.ascents.groupBy { it.climbedAt.take(10) }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    // Stats interval selector
                    item(key = "interval") {
                        StatsIntervalChips(
                            selected = state.statsInterval,
                            onSelect = { viewModel.setStatsInterval(it) }
                        )
                    }

                    // Summary cards only (no charts)
                    item(key = "stats") {
                        StatsSummaryRow(state.stats)
                    }

                    // Ascent cards grouped by day
                    grouped.forEach { (dateKey, dayAscents) ->
                        item(key = "day_$dateKey") {
                            DayHeader(dateKey, dayAscents.size)
                        }
                        items(dayAscents, key = { it.uuid }) { ascent ->
                            val isSelected = ascent.uuid in state.selectedUuids
                            AscentCard(
                                ascent = ascent,
                                gradeScale = state.gradeScale,
                                zones = state.zones,
                                isSelected = isSelected,
                                onClick = {
                                    viewModel.climbNavState.climbUuids = state.ascents.map { it.climbUuid }.distinct()
                                    viewModel.climbNavState.angle = ascent.angle.toInt()
                                    viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.LOGBOOK
                                    onNavigateToClimb(ascent.climbUuid, ascent.angle.toInt())
                                },
                                onToggleSelect = { viewModel.toggleSelection(ascent.uuid) },
                                onEdit = { viewModel.editAscent(ascent) }
                            )
                        }
                    }

                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = OrangeAccent,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
