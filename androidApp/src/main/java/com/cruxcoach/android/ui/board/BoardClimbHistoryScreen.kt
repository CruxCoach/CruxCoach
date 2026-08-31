package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HistoryRetentionPeriod
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.ProgressHistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardClimbHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    viewModel: BoardClimbHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }

    if (showDeleteSelectedConfirm) {
        val count = state.selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text(stringResource(R.string.history_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(pluralStringResource(R.plurals.history_delete_body, count, count)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelected()
                        showDeleteSelectedConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (state.hasSelection) Text(stringResource(R.string.history_selected_count, state.selectedIds.size))
                        else Text(stringResource(R.string.history_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
                        }
                    },
                    actions = {
                        if (state.entries.isNotEmpty()) {
                            IconButton(onClick = { viewModel.toggleSelectAll() }) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = if (state.allSelected) stringResource(R.string.cd_deselect_all) else stringResource(R.string.cd_select_all),
                                    tint = if (state.allSelected) OrangeAccent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { showDeleteSelectedConfirm = true },
                                enabled = state.hasSelection
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_selected),
                                    tint = if (state.hasSelection) ErrorRed
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
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
        ProgressHistoryContent(
            state = state.toPortableState(isLoading = state.isLoading, issue = state.issue),
            labelsFor = { entry -> entry.labels(state.gradeScale) },
            onChooseRetention = { viewModel.setRetention(it.toAndroidRetention()) },
            onOpenEntry = { onNavigateToClimb(it.climbUuid, it.angle) },
            onToggleSelection = viewModel::toggleSelection,
            onRetry = viewModel::retryHistory,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The specific board a history row was logged on — the MoonBoard / Aurora
 *  variant or the Kilter layout where (brand, layoutId) resolves one (e.g.
 *  "MoonBoard Masters 2017", "Tension Board 2 (Mirror)", "Kilter Homewall"),
 *  falling back to the plain brand name when the layout is unknown. */
private fun boardLabel(brand: BoardBrand, layoutId: Long?): String {
    return when {
        brand == BoardBrand.MOONBOARD ->
            layoutId?.let { MoonBoardVariant.fromLayoutId(it)?.displayName } ?: brand.displayName
        brand == BoardBrand.KILTER -> when (layoutId?.toInt()) {
            BoardConstants.KILTER_HOMEWALL_LAYOUT -> "Kilter Homewall"
            BoardConstants.KILTER_ORIGINAL_LAYOUT -> "Kilter Original"
            else -> brand.displayName
        }
        else -> layoutId?.let { BoardConstants.auroraVariant(brand, it.toInt())?.displayName } ?: brand.displayName
    }
}

private fun ProgressHistoryEntry.labels(gradeScale: GradeScale) = ProgressHistoryEntryLabels(
    grade = difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) } ?: "?",
    board = boardLabel(boardBrand, layoutId),
    date = formatDate(recordedAt),
)

private fun HistoryRetentionPeriod.toAndroidRetention(): HistoryRetention = when (this) {
    HistoryRetentionPeriod.OFF -> HistoryRetention.OFF
    HistoryRetentionPeriod.DAYS_30 -> HistoryRetention.DAYS_30
    HistoryRetentionPeriod.DAYS_90 -> HistoryRetention.DAYS_90
    HistoryRetentionPeriod.DAYS_365 -> HistoryRetention.DAYS_365
}
