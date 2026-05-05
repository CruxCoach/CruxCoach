package com.cruxcoach.android.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onNavigateBack: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDark = isSystemInDarkTheme()
    val styleUrl = remember(isDark) { MapStyleProvider.forDarkMode(isDark) }
    val initialCamera = remember {
        cameraAt(state.initialLat, state.initialLng, state.initialZoom)
    }

    // Map + Style become available asynchronously; locations may already be
    // loaded or arrive later. We capture both and re-run the marker update
    // whenever either changes.
    var mapHandle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    LaunchedEffect(mapHandle, state.locations, state.selectedLocationId) {
        val (_, style) = mapHandle ?: return@LaunchedEffect
        MapMarkerLayer.updateData(style, state.locations, state.selectedLocationId)
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
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapView(
                    styleUrl = styleUrl,
                    initialCameraPosition = initialCamera,
                    onMapReady = { map, style ->
                        MapMarkerLayer.install(style)
                        mapHandle = map to style
                    },
                    onMapTap = { map, x, y ->
                        // Cluster tap → zoom in. Marker tap → ViewModel selection.
                        // Empty space → noop, return false to let MapLibre process default behavior.
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
