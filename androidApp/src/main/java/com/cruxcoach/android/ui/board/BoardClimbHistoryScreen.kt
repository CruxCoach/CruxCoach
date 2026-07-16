package com.cruxcoach.android.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BoardClimbHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    viewModel: BoardClimbHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.userMessage) {
        val message = state.userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(message))
        viewModel.consumeUserMessage()
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                    contentDescription = stringResource(R.string.cd_clear_selection),
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RetentionSelectorRow(
                selected = state.retention,
                onSelect = { viewModel.setRetention(it) }
            )

            // The Verlauf table is device-local by design and is NOT part of
            // the JSON/Nostr backup — surface that so the loss on reinstall
            // is never silent (backup-compat audit, 0.2.0).
            Text(
                stringResource(R.string.history_backup_local_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (state.entries.isEmpty()) {
                EmptyHistoryMessage()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Repo emits newest-recorded first, so the natural order is
                    // already most-recent first.
                    items(state.entries, key = { it.id }) { entry ->
                        HistoryEntryCard(
                            entry = entry,
                            gradeScale = state.gradeScale,
                            isSelected = entry.id in state.selectedIds,
                            // While a selection is active, tapping a card extends
                            // the selection instead of navigating away — the
                            // standard contextual multi-select gesture.
                            onClick = {
                                if (state.hasSelection) viewModel.toggleSelection(entry.id)
                                else onNavigateToClimb(entry.climbUuid, entry.angle)
                            },
                            onToggleSelect = { viewModel.toggleSelection(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

private val retentionOptions = listOf(
    HistoryRetention.OFF to R.string.history_retention_off,
    HistoryRetention.DAYS_30 to R.string.history_retention_30,
    HistoryRetention.DAYS_90 to R.string.history_retention_90,
    HistoryRetention.DAYS_365 to R.string.history_retention_365,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionSelectorRow(
    selected: HistoryRetention,
    onSelect: (HistoryRetention) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        retentionOptions.forEach { (retention, labelRes) ->
            FilterChip(
                selected = selected == retention,
                onClick = { onSelect(retention) },
                label = { Text(stringResource(labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }
}

/** The specific board a history row was logged on — the MoonBoard / Aurora
 *  variant or the Kilter layout where (brand, layoutId) resolves one (e.g.
 *  "MoonBoard Masters 2017", "Tension Board 2 (Mirror)", "Kilter Homewall"),
 *  falling back to the plain brand name when the layout is unknown. */
private fun boardLabel(boardBrand: String, layoutId: Long?): String {
    val brand = BoardBrand.fromWire(boardBrand)
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

@Composable
private fun HistoryEntryCard(
    entry: ClimbHistoryEntry,
    gradeScale: GradeScale,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    // App-standard grade formatting (same call AscentCard uses for a
    // difficulty_average Double), in the user's chosen scale; "?" when the
    // grade is unknown.
    val grade = entry.difficultyAverage?.let {
        GradeDisplayHelper.formatDifficulty(it, gradeScale)
    } ?: "?"
    val brandLabel = boardLabel(entry.boardBrand, entry.layoutId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) OrangeAccent.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = OrangeAccent)
            )

            Surface(
                color = OrangeAccent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        grade,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.climbName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${entry.angle}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        brandLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text(
                formatDate(entry.recordedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyHistoryMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
