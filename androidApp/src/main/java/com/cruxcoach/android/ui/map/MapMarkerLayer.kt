package com.cruxcoach.android.ui.map

import android.graphics.Color
import android.util.Log
import com.cruxcoach.data.repository.BoardLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Map layer + source manager for Kilter Board location markers.
 *
 * **No clustering** — 1080 worldwide points are well within MapLibre's
 * render budget for `CircleLayer`, and disabling Supercluster eliminates
 * the cluster→individual zoom-boundary glitches that were previously
 * leaving certain regions empty at certain zooms. The visual cost is
 * dot-pile-up at world-view zoom, which is itself meaningful information
 * ("many boards here").
 *
 * Two GeoJsonSources stay around so that selection updates never
 * re-push the main dataset:
 *  - [SOURCE_ID]: full unfiltered location set
 *  - [SOURCE_SELECTED]: single-feature source for the selected halo
 */
object MapMarkerLayer {
    private const val TAG = "MapMarkerLayer"

    const val SOURCE_ID = "board-locations"
    const val SOURCE_SELECTED = "board-location-selected"

    const val LAYER_POINTS = "board-locations-points"
    const val LAYER_SELECTED_HALO = "board-locations-selected-halo"

    private const val PROP_ID = "id"
    private const val PROP_LAYOUT_ID = "layoutId"

    private val ORANGE = Color.parseColor("#FF6B1A")
    private val GREY = Color.parseColor("#9E9E9E")
    private val WHITE = Color.parseColor("#FFFFFF")

    /** Initial layer + source setup. Idempotent — safe after a style reload. */
    fun install(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            // No clustering. Generous buffer so points near tile edges
            // don't dropout during pans. maxZoom=22 (MapLibre's hard cap)
            // so the source keeps serving data at maximum pinch-zoom.
            val source = GeoJsonSource(
                SOURCE_ID,
                FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions()
                    .withBuffer(128)
                    .withTolerance(0.5f)
                    .withMaxZoom(22),
            )
            style.addSource(source)
        }
        if (style.getSource(SOURCE_SELECTED) == null) {
            style.addSource(
                GeoJsonSource(
                    SOURCE_SELECTED,
                    FeatureCollection.fromFeatures(emptyList()),
                    GeoJsonOptions().withMaxZoom(22),
                )
            )
        }

        if (style.getLayer(LAYER_POINTS) == null) {
            style.addLayer(
                CircleLayer(LAYER_POINTS, SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.circleColor(
                            // Color carries the *board family* signal — that's
                            // the most actionable information per dot. Original
                            // installations are the typical "find a place to
                            // climb" target; homewalls are private and appear
                            // grey when the user opts to show them.
                            Expression.match(
                                Expression.get(PROP_LAYOUT_ID),
                                Expression.color(GREY),
                                Expression.stop(1L, Expression.color(ORANGE)),
                                Expression.stop(8L, Expression.color(GREY)),
                            )
                        ),
                        // Smaller dots when zoomed out (many overlap in
                        // dense regions), bigger when zoomed in.
                        PropertyFactory.circleRadius(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 3.5f),
                                Expression.stop(6, 5f),
                                Expression.stop(10, 7f),
                                Expression.stop(14, 9f),
                            )
                        ),
                        PropertyFactory.circleStrokeColor(WHITE),
                        PropertyFactory.circleStrokeWidth(1.5f),
                        // Slight opacity at low zoom keeps dense overlaps
                        // from forming opaque blobs in regions like NYC.
                        PropertyFactory.circleOpacity(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 0.75f),
                                Expression.stop(8, 0.95f),
                            )
                        ),
                    )
                }
            )
        }

        if (style.getLayer(LAYER_SELECTED_HALO) == null) {
            style.addLayer(
                CircleLayer(LAYER_SELECTED_HALO, SOURCE_SELECTED).apply {
                    setProperties(
                        PropertyFactory.circleColor(ORANGE),
                        PropertyFactory.circleOpacity(0.25f),
                        PropertyFactory.circleRadius(20f),
                        PropertyFactory.circleStrokeColor(ORANGE),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    )
                }
            )
        }
    }

    /**
     * Replace the data source with the full unfiltered location list.
     * Feature assembly runs on [Dispatchers.Default]; the GeoJSON push
     * lands on Main where MapLibre expects it.
     */
    suspend fun setData(style: Style, locations: List<BoardLocation>) {
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(locations.map { it.toFeature() })
        }
        withContext(Dispatchers.Main) {
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            if (source == null) {
                Log.w(TAG, "setData: source $SOURCE_ID missing — install() not called yet?")
                return@withContext
            }
            source.setGeoJson(collection)
            Log.d(TAG, "setData: pushed ${locations.size} locations to $SOURCE_ID")
        }
    }

    /** Update the selection halo to a single feature, or clear it. */
    fun setSelected(style: Style, location: BoardLocation?) {
        val collection = if (location == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            FeatureCollection.fromFeatures(listOf(location.toSelectionFeature()))
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)?.setGeoJson(collection)
    }

    fun locationAt(map: MapLibreMap, screenX: Float, screenY: Float, all: List<BoardLocation>): BoardLocation? {
        val pixel = android.graphics.PointF(screenX, screenY)
        val features = map.queryRenderedFeatures(pixel, LAYER_POINTS)
        val id = features.firstOrNull()?.getStringProperty(PROP_ID) ?: return null
        return all.firstOrNull { it.id == id }
    }

    /**
     * Cluster taps no longer happen because clustering is disabled, but
     * the function stays for API stability — always returns null so the
     * caller's tap dispatch falls through to point hit-testing.
     */
    fun clusterAt(map: MapLibreMap, screenX: Float, screenY: Float): Pair<Point, Double>? = null

    private fun BoardLocation.toFeature(): Feature {
        val point = Point.fromLngLat(lng, lat)
        return Feature.fromGeometry(point).apply {
            addStringProperty(PROP_ID, id)
            addNumberProperty(PROP_LAYOUT_ID, (layoutId ?: -1).toLong())
        }
    }

    private fun BoardLocation.toSelectionFeature(): Feature =
        Feature.fromGeometry(Point.fromLngLat(lng, lat))
}
