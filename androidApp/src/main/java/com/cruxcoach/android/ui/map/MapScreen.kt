package com.cruxcoach.android.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.util.isNetworkAvailable
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBoardBrowser: () -> Unit = {},
    onNavigateToBoardSync: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val styleUrl = remember(isDark) { MapStyleProvider.forDarkMode(isDark) }
    val initialCamera = remember {
        cameraAt(state.initialLat, state.initialLng, state.initialZoom)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }

    var mapHandle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    var showOfflineDialog by remember {
        mutableStateOf(!isNetworkAvailable(context))
    }
    if (showOfflineDialog) {
        OfflineMapDialog(onDismiss = { showOfflineDialog = false })
    }

    // While the one-time locations backfill runs, show a real progress
    // hint instead of the old "sync the board DB / Sync now" prompt (a
    // full board sync never fixed this — the backfill does, automatically).
    LaunchedEffect(state.locationsLoading, state.noLocationData) {
        when {
            state.locationsLoading -> snackbarHostState.showSnackbar(
                message = context.getString(R.string.map_loading_locations),
                duration = SnackbarDuration.Indefinite,
            )
            state.noLocationData -> snackbarHostState.showSnackbar(
                message = context.getString(R.string.map_no_data),
                duration = SnackbarDuration.Long,
            )
        }
    }

    // Init-failure surface — no more silent infinite spinner.
    LaunchedEffect(state.errorMessage) {
        val err = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.map_init_error, err),
            duration = SnackbarDuration.Long,
        )
        viewModel.clearError()
    }

    // One-shot tile-provider reachability probe. If OpenFreeMap is down /
    // rate-limited the user would otherwise see only a grey canvas with
    // markers and no explanation — surface a snackbar instead.
    LaunchedEffect(styleUrl) {
        val ok = MapStyleProvider.isReachable(styleUrl)
        if (!ok) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.map_tile_provider_unreachable),
                duration = SnackbarDuration.Long,
            )
        }
    }

    LaunchedEffect(styleUrl) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(styleUrl)) { newStyle ->
            MapMarkerLayer.install(newStyle)
            mapHandle = map to newStyle
        }
    }

    LaunchedEffect(mapHandle, state.filteredVenues) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.setData(style, state.filteredVenues)
    }

    val selectedVenue = remember(state.selectedVenueId, state.filteredVenues) {
        val id = state.selectedVenueId ?: return@remember null
        state.filteredVenues.firstOrNull { it.id == id }
    }

    LaunchedEffect(mapHandle, selectedVenue) {
        val (map, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.setSelected(style, selectedVenue)
        if (selectedVenue != null) {
            val target = LatLng(selectedVenue.lat, selectedVenue.lng)
            val update = if (map.cameraPosition.zoom < 8.0) {
                CameraUpdateFactory.newLatLngZoom(target, 9.0)
            } else {
                CameraUpdateFactory.newLatLng(target)
            }
            map.easeCamera(update, 450)
        }
    }

    if (selectedVenue != null) {
        BoardLocationDetailSheet(
            venue = selectedVenue,
            onDismiss = { viewModel.selectVenue(null) },
            onBrowseClimbs = { layoutId, sizeId ->
                viewModel.applyBoardConfigForBrowse(layoutId, sizeId)
                viewModel.selectVenue(null)
                onNavigateToBoardBrowser()
            },
        )
    }

    if (showFilterSheet) {
        MapFilterSheet(
            state = state,
            onDismiss = { showFilterSheet = false },
            onSelectAllBrands = viewModel::selectAllBrands,
            onToggleBrand = viewModel::toggleBrand,
            onSelectAllLayouts = viewModel::selectAllLayouts,
            onToggleShowOriginal = viewModel::toggleShowOriginal,
            onToggleShowHomewalls = viewModel::toggleShowHomewalls,
            onToggleMatchesMyBoard = viewModel::toggleMatchesMyBoard,
            onToggleCountry = viewModel::toggleCountry,
            onToggleAccessType = viewModel::toggleAccessType,
            onToggleAdjustability = viewModel::toggleAdjustability,
            onToggleSizeId = viewModel::toggleSizeId,
            onResetAll = viewModel::resetFilters,
        )
    }

    if (showStatsSheet) {
        StatsSheet(state = state, onDismiss = { showStatsSheet = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showStatsSheet = true }) {
                        Icon(
                            Icons.Filled.BarChart,
                            contentDescription = stringResource(R.string.map_open_stats),
                        )
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            // Same icon as Board Browser uses for its filter
                            // entry-point — keeps the affordance consistent.
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.map_open_filters),
                            tint = if (state.filters.isAtDefault) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MapView(
                styleUrl = styleUrl,
                initialCameraPosition = initialCamera,
                onMapReady = { map, style ->
                    MapMarkerLayer.install(style)
                    mapHandle = map to style
                },
                onMapTap = { map, x, y ->
                    // Cluster first: tapping a count bubble eases the camera to
                    // the zoom where it splits apart. Then individual venues.
                    val style = mapHandle?.second
                    if (style != null) {
                        val cluster = MapMarkerLayer.clusterExpansionAt(map, style, x, y)
                        if (cluster != null) {
                            map.easeCamera(
                                CameraUpdateFactory.newLatLngZoom(cluster.center, cluster.zoom),
                                450,
                            )
                            return@MapView true
                        }
                    }
                    val hit = MapMarkerLayer.venueAt(map, x, y, state.filteredVenues)
                    if (hit != null) {
                        viewModel.selectVenue(hit.id)
                        return@MapView true
                    }
                    false
                },
            )
            if (state.isLoading || state.locationsLoading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .padding(16.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsSheet(state: MapState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The sheet's own padding looks tight against chart cards; let
        // StatsScreen control padding internally via its LazyColumn.
        StatsScreen(state = state, modifier = Modifier.fillMaxSize())
    }
}
