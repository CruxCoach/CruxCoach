package com.cruxcoach.android.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.util.isNetworkAvailable
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale

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
    val loadingLocationsMessage = stringResource(R.string.map_loading_locations)
    val noLocationDataMessage = stringResource(R.string.map_no_data)
    val initErrorMessage = stringResource(R.string.map_init_error_generic)
    val tileProviderUnavailableMessage = stringResource(R.string.map_tile_provider_unreachable)
    val isDark = isSystemInDarkTheme()
    val styleUrl = remember(isDark) { MapStyleProvider.forDarkMode(isDark) }
    val initialCamera = remember {
        cameraAt(state.initialLat, state.initialLng, state.initialZoom)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var requestedPlace by remember { mutableStateOf<MapPlace?>(null) }

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
                message = loadingLocationsMessage,
                duration = SnackbarDuration.Indefinite,
            )
            state.noLocationData -> snackbarHostState.showSnackbar(
                message = noLocationDataMessage,
                duration = SnackbarDuration.Long,
            )
        }
    }

    // Init-failure surface — no more silent infinite spinner.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage == null) return@LaunchedEffect
        // Generic localized message — errorMessage is an internal flag, not
        // user-facing text (avoids leaking raw exception text into the UI).
        snackbarHostState.showSnackbar(
            message = initErrorMessage,
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
                message = tileProviderUnavailableMessage,
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

    LaunchedEffect(mapHandle, state.filteredVenues, state.selectedVenueId) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        val selected = state.unfilteredVenues.firstOrNull { it.id == state.selectedVenueId }
        val displayed = if (selected != null && state.filteredVenues.none { it.id == selected.id }) {
            state.filteredVenues + selected
        } else state.filteredVenues
        MapMarkerLayer.setData(style, displayed)
    }

    val selectedVenue = remember(state.selectedVenueId, state.unfilteredVenues) {
        val id = state.selectedVenueId ?: return@remember null
        state.unfilteredVenues.firstOrNull { it.id == id }
    }

    LaunchedEffect(mapHandle, requestedPlace) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        val place = requestedPlace ?: return@LaunchedEffect
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(place.lat, place.lng), 11.0),
            500,
        )
        requestedPlace = null
    }

    val searchResults = remember(searchQuery, state.unfilteredVenues, state.places) {
        searchBoardMap(searchQuery, state.unfilteredVenues, state.places)
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
            onBrowseClimbs = { brand, layoutId, sizeId ->
                viewModel.applyBoardConfigForBrowse(brand, layoutId, sizeId)
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
            onToggleWellpassOnly = viewModel::toggleWellpassOnly,
            onToggleMoonLayoutId = viewModel::toggleMoonLayoutId,
            onToggleMoonLedState = viewModel::toggleMoonLedState,
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
                title = {
                    if (searchOpen) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.map_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.map_screen_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) searchQuery = ""
                    }) {
                        Icon(
                            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(
                                if (searchOpen) R.string.map_search_close else R.string.map_search_open
                            ),
                        )
                    }
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
            if (searchOpen && searchQuery.trim().length >= 2) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    if (searchResults.isEmpty()) {
                        Text(
                            stringResource(R.string.map_search_no_results),
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            searchResults.forEach { result ->
                                MapSearchResultRow(
                                    result = result,
                                    onClick = {
                                        when (result) {
                                            is MapSearchResult.Venue -> viewModel.selectVenue(result.venue.id)
                                            is MapSearchResult.Place -> requestedPlace = result.place
                                        }
                                        searchQuery = ""
                                        searchOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
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

@Composable
private fun MapSearchResultRow(result: MapSearchResult, onClick: () -> Unit) {
    val locale = Locale.getDefault()
    val title: String
    val subtitle: String
    val icon = when (result) {
        is MapSearchResult.Venue -> {
            title = result.venue.name
            subtitle = listOfNotNull(
                result.venue.city,
                Locale("", result.venue.countryCode).getDisplayCountry(locale)
                    .takeIf(String::isNotBlank),
                result.venue.brands.joinToString { it.displayName },
            ).joinToString(" · ")
            Icons.Filled.Place
        }
        is MapSearchResult.Place -> {
            title = if (locale.language == "de") result.place.germanName ?: result.place.name
                else result.place.name
            subtitle = listOfNotNull(
                result.place.region,
                Locale("", result.place.countryCode).getDisplayCountry(locale)
                    .takeIf(String::isNotBlank),
            ).joinToString(" · ")
            Icons.Filled.LocationCity
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
