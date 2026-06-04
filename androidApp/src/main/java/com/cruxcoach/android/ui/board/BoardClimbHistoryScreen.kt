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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

    if (showDeleteSelectedConfirm) {
        val count = state.selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text("Einträge löschen", fontWeight = FontWeight.Bold) },
            text = { Text("$count ${if (count == 1) "Eintrag" else "Einträge"} aus dem Verlauf löschen?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelected()
                        showDeleteSelectedConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Löschen", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (state.hasSelection) Text("${state.selectedIds.size} ausgewählt")
                        else Text("Verlauf")
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    },
                    actions = {
                        if (state.entries.isNotEmpty()) {
                            IconButton(onClick = { viewModel.toggleSelectAll() }) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = if (state.allSelected) "Auswahl aufheben" else "Alle auswählen",
                                    tint = if (state.allSelected) OrangeAccent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { showDeleteSelectedConfirm = true },
                                enabled = state.hasSelection
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Auswahl löschen",
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
    HistoryRetention.OFF to "Aus",
    HistoryRetention.DAYS_30 to "30 Tage",
    HistoryRetention.DAYS_90 to "90 Tage",
    HistoryRetention.DAYS_365 to "365 Tage",
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
        retentionOptions.forEach { (retention, label) ->
            FilterChip(
                selected = selected == retention,
                onClick = { onSelect(retention) },
                label = { Text(label) },
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
                "Noch kein Verlauf",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Geschickte Boulder erscheinen hier, sobald du sie kletterst.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
