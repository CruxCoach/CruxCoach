package com.cruxcoach.android.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
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
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.domain.board.BoardBrand

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BoardClimbHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    viewModel: BoardClimbHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Verlauf löschen", fontWeight = FontWeight.Bold) },
            text = { Text("Den gesamten Verlauf wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Löschen", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Verlauf") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    },
                    actions = {
                        if (state.entries.isNotEmpty()) {
                            IconButton(onClick = { showClearConfirm = true }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Verlauf löschen",
                                    tint = ErrorRed
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
                            onClick = { onNavigateToClimb(entry.climbUuid, entry.angle) }
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

@Composable
private fun HistoryEntryCard(
    entry: ClimbHistoryEntry,
    gradeScale: GradeScale,
    onClick: () -> Unit
) {
    // App-standard grade formatting (same call AscentCard uses for a
    // difficulty_average Double), in the user's chosen scale; "?" when the
    // grade is unknown.
    val grade = entry.difficultyAverage?.let {
        GradeDisplayHelper.formatDifficulty(it, gradeScale)
    } ?: "?"
    val brandLabel = BoardBrand.fromWire(entry.boardBrand).displayName

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
