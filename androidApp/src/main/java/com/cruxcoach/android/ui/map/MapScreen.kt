package com.cruxcoach.android.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
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

    var mapHandle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    // One-shot offline check on screen entry. Cache may still hold tiles
    // from previous online sessions, but we don't probe — the dialog text
    // calls out the cache benefit so a dismiss-and-pan flow is fine for
    // returning users.
    var showOfflineDialog by remember {
        mutableStateOf(!isNetworkAvailable(context))
    }
    if (showOfflineDialog) {
        OfflineMapDialog(onDismiss = { showOfflineDialog = false })
    }

    // Empty-data snackbar surfaces only when the location table is empty
    // (older client without locations chunk in the manifest, or a sync
    // failure). Action button takes the user to Board Sync.
    LaunchedEffect(state.noLocationData) {
        if (!state.noLocationData) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.map_no_data),
            actionLabel = context.getString(R.string.map_no_data_action),
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            onNavigateToBoardSync()
        }
    }

    LaunchedEffect(mapHandle, state.locations, state.selectedLocationId) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.updateData(style, state.locations, state.selectedLocationId)
    }

    val selectedLocation = state.selectedLocationId?.let { id ->
        state.locations.firstOrNull { it.id == id } ?: viewModel.selectedLocation()
    }
    if (selectedLocation != null) {
        BoardLocationDetailSheet(
            location = selectedLocation,
            onDismiss = { viewModel.selectLocation(null) },
            onBrowseClimbs = { layoutId, sizeId ->
                viewModel.applyBoardConfigForBrowse(layoutId, sizeId)
                viewModel.selectLocation(null)
                onNavigateToBoardBrowser()
            },
        )
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChipRow(
                publicOnly = state.publicOnly,
                matchesMyBoard = state.matchesMyBoard,
                canFilterByMyBoard = state.canFilterByMyBoard,
                onTogglePublicOnly = viewModel::togglePublicOnly,
                onToggleMatchesMyBoard = {
                    if (state.canFilterByMyBoard) {
                        viewModel.toggleMatchesMyBoard()
                    } else {
                        Toast.makeText(
                            context,
                            R.string.map_filter_match_disabled,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                MapView(
                    styleUrl = styleUrl,
                    initialCameraPosition = initialCamera,
                    onMapReady = { map, style ->
                        MapMarkerLayer.install(style)
                        mapHandle = map to style
                    },
                    onMapTap = { map, x, y ->
                        val cluster = MapMarkerLayer.clusterAt(map, x, y)
                        if (cluster != null) {
                            val (point, targetZoom) = cluster
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(point.latitude(), point.longitude()),
                                    targetZoom,
                                )
                            )
                            return@MapView true
                        }
                        val hit = MapMarkerLayer.locationAt(map, x, y, state.locations)
                        if (hit != null) {
                            viewModel.selectLocation(hit.id)
                            return@MapView true
                        }
                        false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    publicOnly: Boolean,
    matchesMyBoard: Boolean,
    canFilterByMyBoard: Boolean,
    onTogglePublicOnly: () -> Unit,
    onToggleMatchesMyBoard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = publicOnly,
            onClick = onTogglePublicOnly,
            label = { Text(stringResource(R.string.map_filter_public_only)) },
        )
        // Always clickable so the toast can fire; visual disabled state via
        // `enabled = false` would also make the row swallow the tap silently.
        FilterChip(
            selected = matchesMyBoard && canFilterByMyBoard,
            enabled = canFilterByMyBoard,
            onClick = onToggleMatchesMyBoard,
            label = { Text(stringResource(R.string.map_filter_matches_my_board)) },
        )
    }
}
