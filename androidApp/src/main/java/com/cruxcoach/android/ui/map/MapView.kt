package com.cruxcoach.android.ui.map

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView as MapLibreView
import org.maplibre.android.maps.Style

/**
 * Compose wrapper around MapLibre's Android [MapLibreView].
 *
 * Lifecycle: MapLibre's `MapView` needs onCreate/onStart/onResume/onPause/
 * onStop/onDestroy/onSaveInstanceState forwarded from the host activity.
 * We forward via a Compose-side lifecycle observer so the wrapper works
 * inside any screen without the host needing to know.
 *
 * Map initialization is async: `getMapAsync` invokes [onMapReady] once the
 * native view has finished laying out. Caller stores the [MapLibreMap] in
 * its ViewModel state to drive marker / camera updates.
 */
@Composable
fun MapView(
    styleUrl: String,
    initialCameraPosition: CameraPosition,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize MapLibre once per process. Idempotent — MapLibre.getInstance
    // returns the existing singleton on subsequent calls.
    remember(context) {
        MapLibre.getInstance(context)
        Unit
    }

    val mapView = remember(context) {
        MapLibreView(context).apply {
            // MapView's lifecycle requires explicit onCreate before any other
            // call. We pass a null savedInstanceState because we don't restore
            // state across process death — the parent screen restores camera
            // position from preferences instead (see MapViewModel).
            onCreate(null)
            getMapAsync { map ->
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    map.cameraPosition = initialCameraPosition
                    onMapReady(map, style)
                }
            }
        }
    }

    // Wire MapLibre's lifecycle to the host's lifecycle.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = { /* re-renders driven by MapLibreMap callbacks, not Compose */ },
    )
}

/** Helper to centre the camera on a [LatLng] at a given zoom. */
fun cameraAt(lat: Double, lng: Double, zoom: Double): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lng))
        .zoom(zoom)
        .build()
