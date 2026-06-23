package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.Climb_list_entries
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardListDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    viewModel: BoardListDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Refresh entries on return so an edit/delete/publish done on a climb's
    // detail reflects instantly (the ViewModel is retained across back-nav).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.listName.ifEmpty { stringResource(R.string.board_list_default_name) }) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.board_list_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.board_list_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                val listState = rememberLazyListState()

                Column(modifier = Modifier.padding(padding)) {
                    Text(
                        stringResource(R.string.board_list_climb_count, state.totalCount),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // FEAT-023: a list is the user's selection, shown in FULL
                    // (every board) — each card's BoardBrandBadge labels its own
                    // board. When the list spans more than one board, offer an
                    // explicit per-board filter (down to MoonBoard variant /
                    // Kilter Original vs Homewall).
                    if (state.boardFilters.isNotEmpty()) {
                        BoardFilterRow(
                            options = state.boardFilters,
                            selected = state.selectedFilter,
                            onSelect = { viewModel.setBoardFilter(it) }
                        )
                    }

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.entries, key = { it.climb.uuid }) { entry ->
                            ListEntryCard(
                                entry = entry,
                                gradeScale = state.gradeScale,
                                zones = state.zones,
                                onClick = {
                                    // Pager follows the currently-FILTERED set so
                                    // swiping in detail matches what's on screen.
                                    viewModel.climbNavState.climbUuids = state.entries.map { it.climb.uuid }
                                    viewModel.climbNavState.angle = state.angle
                                    viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.LIST
                                    onNavigateToClimb(entry.climb.uuid, state.angle)
                                },
                                onRemove = { viewModel.removeFromList(entry.climb.uuid) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** FEAT-023: per-list board filter. One chip per distinct board present in the
 *  list (variant-granular — MoonBoard 2019, Kilter Homewall, …) plus an "Alle"
 *  chip. Tapping the active chip again clears back to "Alle". */
@Composable
private fun BoardFilterRow(
    options: List<BoardFilterOption>,
    selected: BoardFilterOption?,
    onSelect: (BoardFilterOption?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.board_list_filter_all)) }
            )
        }
        items(options, key = { "${it.brandWire}:${it.layoutId}" }) { opt ->
            val isActive = selected?.brandWire == opt.brandWire && selected?.layoutId == opt.layoutId
            FilterChip(
                selected = isActive,
                onClick = { onSelect(if (isActive) null else opt) },
                label = { Text("${boardFilterLabel(opt.brandWire, opt.layoutId)} · ${opt.count}") }
            )
        }
    }
}

/** Human label for a (brand, layout) filter key: Kilter Original / Homewall,
 *  the MoonBoard variant name, or the Aurora brand display name. */
@Composable
private fun boardFilterLabel(brandWire: String, layoutId: Long): String {
    val brand = BoardBrand.fromWire(brandWire)
    return when {
        brand == BoardBrand.KILTER &&
            layoutId == com.cruxcoach.android.data.BoardConstants.KILTER_HOMEWALL_LAYOUT.toLong() ->
            stringResource(R.string.board_category_kilter_homewall)
        brand == BoardBrand.KILTER ->
            stringResource(R.string.board_category_kilter_original)
        brand == BoardBrand.MOONBOARD ->
            com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(layoutId)?.displayName
                ?: brand.displayName
        else -> brand.displayName
    }
}

@Composable
private fun ListEntryCard(
    entry: Climb_list_entries,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    zones: IntensityZones? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val climb = entry.climb
    val grade = climb.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) } ?: "?"
    val moveCount = climb.moveCount

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("board_list_entry_card"),
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
                color = zoneColorForDifficulty(climb.difficultyAverage ?: 0.0, zones),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        grade,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkBackground
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Per-entry board type, analogous to the logbook badge.
                    BoardBrandBadge(BoardBrand.fromWire(climb.boardBrand), climb.layoutId)
                    climb.setterUsername?.let {
                        Text(
                            stringResource(R.string.board_climb_by_setter, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (climb.isRoute) {
                        Text(
                            stringResource(R.string.board_climb_frames, climb.framesCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.board_climb_moves, moveCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                climb.qualityAverage?.let {
                    Text(
                        "${"%.1f".format(it)}★",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningYellow,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_remove),
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

