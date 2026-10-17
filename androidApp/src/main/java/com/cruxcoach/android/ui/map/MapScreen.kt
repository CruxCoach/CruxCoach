package com.cruxcoach.android.ui.map

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.LocateResult
import com.cruxcoach.android.ui.common.locateFailureText
import com.cruxcoach.android.ui.common.locateFixLabel
import com.cruxcoach.android.ui.common.openLocateFix
import com.cruxcoach.android.ui.common.rememberLocateMe
import com.cruxcoach.android.util.isNetworkAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale
import java.util.TimeZone

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
    val density = LocalDensity.current
    val pixelDensity = density.density
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val loadingLocationsMessage = stringResource(R.string.map_loading_locations)
    val noLocationDataMessage = stringResource(R.string.map_no_data)
    val initErrorMessage = stringResource(R.string.map_init_error_generic)
    val tileProviderUnavailableMessage = stringResource(R.string.map_tile_provider_unreachable)

    // Follow the app's theme (it can differ from the system's), not the system flag.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val styleUrl = remember(isDark) { MapStyleProvider.forDarkMode(isDark) }
    val markerTheme = MapMarkerLayer.Theme.of(isDark)
    val clusterBrand = state.filters.brands.singleOrNull()

    val mapState = remember { context.getSharedPreferences(MAP_STATE_PREFS, Context.MODE_PRIVATE) }
    val savedCamera = remember { readCamera(mapState) }
    val initialCamera = remember {
        val start = savedCamera ?: MapStartView.World
        cameraAt(start.lat, start.lng, start.zoom)
    }
    // Without a remembered camera the map opens on the user's country once the data is in.
    var startViewApplied by remember { mutableStateOf(savedCamera != null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        ),
    )
    var showFilterSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var requestedPlace by remember { mutableStateOf<MapPlace?>(null) }
    var mapHandle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var overlayPx by remember { mutableIntStateOf(0) }
    var peekPx by remember { mutableIntStateOf(0) }
    var peekVenueId by remember { mutableStateOf<String?>(null) }
    // Where "My location" found the user: kept in memory for this screen only.
    var userPosition by remember { mutableStateOf<UserPosition?>(null) }
    var locateFailure by remember { mutableStateOf<LocateResult?>(null) }
    val locateMe = rememberLocateMe { result ->
        when (result) {
            is LocateResult.Found -> {
                val position = UserPosition(result.lat, result.lng, result.accuracyMeters)
                userPosition = position
                val (map, _) = mapHandle ?: return@rememberLocateMe
                // Frame the user together with the closest boards that pass the filters.
                val frame = nearestFrame(state.filteredVenues, position.lat, position.lng)
                val margin = with(density) { 56.dp.roundToPx() }
                runCatching {
                    if (frame == null) {
                        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.lat, position.lng), 12.0), 700)
                    } else {
                        map.easeCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                LatLngBounds.from(frame.north, frame.east, frame.south, frame.west),
                                margin, overlayPx + margin, margin, peekPx + margin,
                            ),
                            700,
                        )
                    }
                }
            }
            else -> locateFailure = result
        }
    }
    val failure = locateFailure
    val failureText = failure?.let { locateFailureText(it) }
    val failureFix = failure?.let { locateFixLabel(it) }
    LaunchedEffect(failure) {
        if (failure == null || failureText == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(failureText, failureFix, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) openLocateFix(context, failure)
        locateFailure = null
    }
    LaunchedEffect(mapHandle, userPosition) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.setUserPosition(style, userPosition)
    }

    // Once a style has loaded, the areas viewed stay in MapLibre's cache.
    // The dialog says the map needs internet the first time, so it only
    // belongs before that; afterwards it blocked a map that worked offline.
    var showOfflineDialog by remember {
        mutableStateOf(!isNetworkAvailable(context) && !mapState.getBoolean(KEY_STYLE_LOADED, false))
    }
    if (showOfflineDialog) {
        OfflineMapDialog(onDismiss = { showOfflineDialog = false })
    }

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
            else -> snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    // Init-failure surface — no more silent infinite spinner. errorMessage is
    // an internal flag, not user-facing text.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message = initErrorMessage, duration = SnackbarDuration.Long)
        viewModel.clearError()
    }

    // One-shot tile-provider reachability probe: a down provider would
    // otherwise show only a grey canvas with markers and no explanation.
    LaunchedEffect(styleUrl) {
        if (!MapStyleProvider.isReachable(styleUrl)) {
            snackbarHostState.showSnackbar(message = tileProviderUnavailableMessage, duration = SnackbarDuration.Long)
        }
    }

    LaunchedEffect(styleUrl) {
        val (map, style) = mapHandle ?: return@LaunchedEffect
        if (runCatching { style.uri }.getOrNull() == styleUrl) return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(styleUrl)) { newStyle ->
            MapMarkerLayer.install(newStyle, markerTheme, pixelDensity, clusterBrand)
            mapHandle = map to newStyle
        }
    }

    LaunchedEffect(mapHandle, clusterBrand, isDark) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        if (style.isFullyLoaded) MapMarkerLayer.setClusterStyle(style, markerTheme, pixelDensity, clusterBrand)
    }

    // A selected place the filters hide is added to the data; any other
    // selection only moves the highlight and never re-pushes all venues.
    val selectedOutsideFilter = state.selectedVenueId
        ?.takeIf { id -> state.filteredVenues.none { it.id == id } }
    LaunchedEffect(mapHandle, state.filteredVenues, selectedOutsideFilter) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        val extra = selectedOutsideFilter?.let { id -> state.unfilteredVenues.firstOrNull { it.id == id } }
        MapMarkerLayer.setData(style, if (extra != null) state.filteredVenues + extra else state.filteredVenues, pixelDensity)
    }

    val selectedVenue = remember(state.selectedVenueId, state.unfilteredVenues) {
        val id = state.selectedVenueId ?: return@remember null
        state.unfilteredVenues.firstOrNull { it.id == id }
    }

    fun refreshViewport(map: MapLibreMap) {
        viewport = visibleViewport(map, overlayPx, peekPx)
    }

    // First visit: frame the boards of the user's country (timezone, then
    // locale) instead of the whole planet. Needs the data and the measured
    // overlays, so it waits for both.
    LaunchedEffect(mapHandle, state.isLoading, startViewApplied) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        if (startViewApplied || state.isLoading || state.unfilteredVenues.isEmpty()) return@LaunchedEffect
        withTimeoutOrNull(1_000) { snapshotFlow { peekPx > 0 && overlayPx > 0 }.first { it } }
        val guess = withContext(Dispatchers.Default) {
            guessStartView(TimeZone.getDefault().id, Locale.getDefault().country, state.places, state.unfilteredVenues)
        }
        val margin = with(density) { 24.dp.roundToPx() }
        runCatching {
            when (guess) {
                is MapStartView.Bounds -> map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.from(guess.north, guess.east, guess.south, guess.west),
                        margin, overlayPx + margin, margin, peekPx + margin,
                    )
                )
                is MapStartView.Camera -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(guess.lat, guess.lng), guess.zoom))
            }
        }
        startViewApplied = true
        refreshViewport(map)
    }

    // The list follows the visible area: refresh when the sheet or the
    // search bar change size, not only when the camera stops.
    LaunchedEffect(mapHandle, overlayPx, peekPx) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        refreshViewport(map)
    }

    LaunchedEffect(mapHandle, requestedPlace) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        val place = requestedPlace ?: return@LaunchedEffect
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(place.lat, place.lng))
                    .zoom(11.0)
                    .padding(0.0, overlayPx.toDouble(), 0.0, peekPx.toDouble())
                    .build()
            ),
            600,
        )
        requestedPlace = null
    }

    // Selecting a venue: highlight it and bring it into the free area above
    // the sheet, close enough to see the gym's surroundings.
    LaunchedEffect(mapHandle, selectedVenue?.id) {
        val (map, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.setSelected(style, selectedVenue, pixelDensity)
        val venue = selectedVenue ?: return@LaunchedEffect
        // Wait for the sheet to measure the venue summary it now shows.
        withTimeoutOrNull(500) { snapshotFlow { peekVenueId }.first { it == venue.id } }
        val zoom = map.cameraPosition.zoom.coerceAtLeast(13.5)
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(venue.lat, venue.lng))
                    .zoom(zoom)
                    .padding(0.0, overlayPx.toDouble(), 0.0, peekPx.toDouble())
                    .build()
            ),
            500,
        )
    }

    // Keep the OSM/OpenFreeMap attribution and the MapLibre logo above the sheet.
    LaunchedEffect(mapHandle, peekPx) {
        val (map, _) = mapHandle ?: return@LaunchedEffect
        val bottom = peekPx + with(density) { 6.dp.roundToPx() }
        map.uiSettings.apply {
            setLogoMargins(logoMarginLeft, logoMarginTop, logoMarginRight, bottom)
            setAttributionMargins(attributionMarginLeft, attributionMarginTop, attributionMarginRight, bottom)
        }
    }

    // Search never runs on the main thread: the corpus (venues plus the bundled city index)
    // is normalised once in the background, and typing only triggers a debounced lookup.
    var searchIndex by remember { mutableStateOf<BoardMapSearchIndex?>(null) }
    LaunchedEffect(state.unfilteredVenues, state.places) {
        searchIndex = null
        searchIndex = withContext(Dispatchers.Default) {
            BoardMapSearchIndex(state.unfilteredVenues, state.places)
        }
    }
    var searchResults by remember { mutableStateOf<List<MapSearchResult>>(emptyList()) }
    // The query the current results belong to. "No results" may only be claimed for a
    // finished lookup of exactly the typed text, never while the index or a lookup is pending.
    var searchedQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchQuery, searchIndex) {
        val index = searchIndex
        if (searchQuery.trim().length < 2) {
            searchResults = emptyList()
            searchedQuery = searchQuery
            return@LaunchedEffect
        }
        if (index == null) return@LaunchedEffect
        delay(200)
        val query = searchQuery
        searchResults = withContext(Dispatchers.Default) { index.search(query) }
        searchedQuery = query
    }
    val search = MapSearchUi(
        query = searchQuery,
        results = searchResults,
        pending = searchQuery.trim().length >= 2 && searchedQuery != searchQuery,
    )

    val sheetExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded
    BackHandler(enabled = searchQuery.isNotEmpty()) {
        searchQuery = ""
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchQuery.isEmpty() && selectedVenue != null) { viewModel.selectVenue(null) }
    BackHandler(enabled = searchQuery.isEmpty() && selectedVenue == null && sheetExpanded) {
        scope.launch { scaffoldState.bottomSheetState.partialExpand() }
    }

    fun showVenue(venue: MapVenue) {
        viewModel.selectVenue(venue.id)
        scope.launch { scaffoldState.bottomSheetState.partialExpand() }
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

    // "Choose this board": the gym search of the board picker with the tapped
    // place already open, then on to the board browser with the chosen board.
    var chooseAt by remember { mutableStateOf<MapVenue?>(null) }
    var showDirectPicker by remember { mutableStateOf(false) }
    var boardChosen by remember { mutableStateOf(false) }
    fun openBrowserIfChosen() {
        if (!boardChosen) return
        boardChosen = false
        viewModel.selectVenue(null)
        onNavigateToBoardBrowser()
    }
    chooseAt?.let { venue ->
        com.cruxcoach.android.ui.settings.GymBoardSearchSheet(
            startGym = venue.boards,
            onBoardChosen = { boardChosen = true },
            onClose = {
                chooseAt = null
                openBrowserIfChosen()
            },
            onFallbackToDirect = {
                chooseAt = null
                showDirectPicker = true
            },
            onDismiss = { chooseAt = null },
        )
    }
    if (showDirectPicker) {
        com.cruxcoach.android.ui.settings.BoardPickerDialog(
            onDismiss = { showDirectPicker = false },
            onSelected = {
                showDirectPicker = false
                boardChosen = true
                openBrowserIfChosen()
            },
        )
    }

    MapScreenLayout(
        state = state,
        inView = remember(state.filteredVenues, viewport, userPosition) {
            viewport?.let { venuesInView(state.filteredVenues, it, origin = userPosition) } ?: VenuesInView.Empty
        },
        selectedVenue = selectedVenue,
        search = search,
        scaffoldState = scaffoldState,
        snackbarHostState = snackbarHostState,
        onBack = onNavigateBack,
        onQueryChange = { searchQuery = it },
        onSearchResult = { result ->
            when (result) {
                is MapSearchResult.Venue -> showVenue(result.venue)
                is MapSearchResult.Place -> {
                    viewModel.selectVenue(null)
                    requestedPlace = result.place
                }
            }
            searchQuery = ""
            focusManager.clearFocus()
        },
        onOpenStats = { showStatsSheet = true },
        onOpenFilters = { showFilterSheet = true },
        onSelectAllBrands = viewModel::selectAllBrands,
        onToggleBrand = viewModel::toggleBrand,
        onSelectVenue = ::showVenue,
        onCloseVenue = { viewModel.selectVenue(null) },
        onBrowseClimbs = { brand, layoutId, sizeId ->
            viewModel.applyBoardConfigForBrowse(brand, layoutId, sizeId)
            viewModel.selectVenue(null)
            onNavigateToBoardBrowser()
        },
        onResetFilters = viewModel::resetFilters,
        onShowNearest = {
            val (map, _) = mapHandle ?: return@MapScreenLayout
            val view = viewport ?: return@MapScreenLayout
            val bounds = nearestFrame(state.filteredVenues, view) ?: return@MapScreenLayout
            val margin = with(density) { 48.dp.roundToPx() }
            // A frame across the antimeridian is rejected by some MapLibre
            // versions; then the map simply stays put.
            runCatching {
                map.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
                        margin, overlayPx + margin, margin, peekPx + margin,
                    ),
                    700,
                )
            }
        },
        onOverlayHeight = { overlayPx = it },
        onPeekHeight = { px, venueId ->
            peekPx = px
            peekVenueId = venueId
        },
        userPosition = userPosition,
        locating = locateMe.locating,
        onLocateMe = locateMe::locate,
        onChooseBoardAt = { chooseAt = it },
    ) {
        MapView(
            styleUrl = styleUrl,
            initialCameraPosition = initialCamera,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map, style ->
                MapMarkerLayer.install(style, markerTheme, pixelDensity, clusterBrand)
                mapHandle = map to style
                mapState.edit().putBoolean(KEY_STYLE_LOADED, true).apply()
            },
            onMapTap = { map, x, y ->
                focusManager.clearFocus()
                // Cluster first: tapping a count bubble eases the camera to
                // the zoom where it splits apart. Then individual venues.
                val style = mapHandle?.second
                if (style != null) {
                    val cluster = MapMarkerLayer.clusterExpansionAt(map, style, x, y)
                    if (cluster != null) {
                        map.easeCamera(CameraUpdateFactory.newLatLngZoom(cluster.center, cluster.zoom), 450)
                        return@MapView true
                    }
                }
                val id = MapMarkerLayer.venueIdAt(map, x, y, pixelDensity)
                if (id != null) {
                    viewModel.selectVenue(id)
                    scope.launch { scaffoldState.bottomSheetState.partialExpand() }
                    return@MapView true
                }
                // A tap on empty map closes the selected place, like other map apps.
                if (state.selectedVenueId != null) {
                    viewModel.selectVenue(null)
                    return@MapView true
                }
                false
            },
            onCameraIdle = { map ->
                refreshViewport(map)
                if (startViewApplied) writeCamera(mapState, map)
            },
        )
    }
}

/** The part of the map between the search overlay and the sheet, as lat/lng. */
private fun visibleViewport(map: MapLibreMap, overlayPx: Int, peekPx: Int): MapViewport? {
    val w = map.width
    val h = map.height
    if (w <= 0f || h <= 0f) return null
    val top = overlayPx.toFloat().coerceIn(0f, h / 2f)
    val bottom = (h - peekPx).coerceIn(h / 2f, h)
    val projection = map.projection
    val northWest = projection.fromScreenLocation(PointF(0f, top))
    val southEast = projection.fromScreenLocation(PointF(w, bottom))
    val center = projection.fromScreenLocation(PointF(w / 2f, (top + bottom) / 2f)).wrap()
    var span = southEast.longitude - northWest.longitude
    if (span < 0) span += 360.0
    val west = if (span >= 360.0) -180.0 else northWest.wrap().longitude
    val east = if (span >= 360.0) 180.0 else southEast.wrap().longitude
    return MapViewport(
        south = southEast.latitude,
        west = west,
        north = northWest.latitude,
        east = east,
        centerLat = center.latitude,
        centerLng = center.longitude,
    )
}

private fun readCamera(prefs: SharedPreferences): MapStartView.Camera? {
    if (!prefs.contains(KEY_CAMERA_LAT)) return null
    val lat = prefs.getFloat(KEY_CAMERA_LAT, 0f).toDouble()
    val lng = prefs.getFloat(KEY_CAMERA_LNG, 0f).toDouble()
    val zoom = prefs.getFloat(KEY_CAMERA_ZOOM, 0f).toDouble()
    if (lat !in -85.0..85.0 || lng !in -180.0..180.0 || zoom !in 0.0..22.0) return null
    return MapStartView.Camera(lat, lng, zoom)
}

/** Saves the centre of the whole map view (not of the padded area a
 *  selection leaves behind), so the map reopens exactly where it was. */
private fun writeCamera(prefs: SharedPreferences, map: MapLibreMap) {
    if (map.width <= 0f || map.height <= 0f) return
    val center = map.projection.fromScreenLocation(PointF(map.width / 2f, map.height / 2f)).wrap()
    prefs.edit()
        .putFloat(KEY_CAMERA_LAT, center.latitude.toFloat())
        .putFloat(KEY_CAMERA_LNG, center.longitude.toFloat())
        .putFloat(KEY_CAMERA_ZOOM, map.cameraPosition.zoom.toFloat())
        .apply()
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

/** Kept out of backups: a restored flag could hide the dialog on a phone whose map never loaded. */
private const val MAP_STATE_PREFS = "map_state"
private const val KEY_STYLE_LOADED = "style_loaded_once"
/** The last camera, so the map reopens where the user left it. */
private const val KEY_CAMERA_LAT = "camera_lat"
private const val KEY_CAMERA_LNG = "camera_lng"
private const val KEY_CAMERA_ZOOM = "camera_zoom"
