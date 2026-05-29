package com.cruxcoach.android.ui.map

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Map layer + source manager for board-location markers.
 *
 * Two-stage de-overlap so dense regions stay legible:
 *  1. **Venue grouping** (caller-side, [groupIntoVenues]) collapses boards
 *     that sit on the same coordinate — a gym with both a Kilter and a
 *     MoonBoard becomes one pin, not two stacked dots.
 *  2. **Clustering** (here, via [GeoJsonOptions.withCluster]) collapses
 *     nearby venues into a count bubble until the user zooms in. Tapping a
 *     cluster eases the camera to the zoom where it splits apart.
 *
 * This replaces the earlier no-cluster CircleLayer: with two board
 * catalogues (~2.6k venues) the old flat layer piled into opaque blobs over
 * cities. Clustering is done with the canonical raw-style pattern
 * (`has(point_count)` cluster layer + `!has(point_count)` point layer), which
 * avoids the zoom-boundary dropouts the annotation-plugin clustering hit.
 *
 * Marker colour encodes the venue's board family ([VenueBrandKey]); a mixed
 * venue (Kilter + MoonBoard) gets its own colour. [SOURCE_SELECTED] keeps a
 * single-feature halo so selection never re-pushes the main dataset.
 */
object MapMarkerLayer {
    private const val TAG = "MapMarkerLayer"

    const val SOURCE_ID = "board-locations"
    const val SOURCE_SELECTED = "board-location-selected"

    const val LAYER_CLUSTERS = "board-locations-clusters"
    const val LAYER_CLUSTER_COUNT = "board-locations-cluster-count"
    const val LAYER_POINTS = "board-locations-points"
    const val LAYER_SELECTED_HALO = "board-locations-selected-halo"

    private const val PROP_ID = "id"
    private const val PROP_BRAND = "brand"
    private const val PROP_COUNT = "boardCount"

    private val ORANGE = Color.parseColor("#FF6B1A") // Kilter
    private val MOON_BLUE = Color.parseColor("#2D9CDB") // MoonBoard
    private val MULTI_PURPLE = Color.parseColor("#9B51E0") // mixed-brand venue
    private val GREY = Color.parseColor("#9E9E9E") // other / unknown brand
    private val WHITE = Color.parseColor("#FFFFFF")

    // Cluster bubble palette — neutral, sized + shaded by how many venues
    // it contains (not by brand; a cluster usually mixes brands).
    private val CLUSTER_SMALL = Color.parseColor("#FFB07A")
    private val CLUSTER_MEDIUM = Color.parseColor("#FF6B1A")
    private val CLUSTER_LARGE = Color.parseColor("#E04E00")

    /** Initial layer + source setup. Idempotent — safe after a style reload. */
    fun install(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            // Cluster the venue points. radius 50 px groups city-dense
            // venues; maxZoom 13 lets clusters split before street level so
            // individual gyms are tappable. Generous buffer + maxZoom 22 keep
            // points served to the edge at full pinch-zoom.
            val source = GeoJsonSource(
                SOURCE_ID,
                FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions()
                    .withBuffer(128)
                    .withTolerance(0.5f)
                    .withMaxZoom(22)
                    .withCluster(true)
                    .withClusterRadius(50)
                    .withClusterMaxZoom(13),
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

        // Selected halo sits at the bottom so the point draws on top of it.
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

        // Unclustered venue points. Colour = board family; mixed venues get
        // their own colour so "this place has both" reads at a glance.
        if (style.getLayer(LAYER_POINTS) == null) {
            style.addLayer(
                CircleLayer(LAYER_POINTS, SOURCE_ID).apply {
                    setFilter(Expression.not(Expression.has("point_count")))
                    setProperties(
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(PROP_BRAND),
                                Expression.color(GREY),
                                Expression.stop(VenueBrandKey.KILTER.wire, Expression.color(ORANGE)),
                                Expression.stop(VenueBrandKey.MOONBOARD.wire, Expression.color(MOON_BLUE)),
                                Expression.stop(VenueBrandKey.MULTI.wire, Expression.color(MULTI_PURPLE)),
                                Expression.stop(VenueBrandKey.OTHER.wire, Expression.color(GREY)),
                            )
                        ),
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
                        PropertyFactory.circleOpacity(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 0.8f),
                                Expression.stop(8, 0.95f),
                            )
                        ),
                    )
                }
            )
        }

        // Cluster bubbles — sized + shaded by point_count (10 / 50 breaks).
        if (style.getLayer(LAYER_CLUSTERS) == null) {
            style.addLayer(
                CircleLayer(LAYER_CLUSTERS, SOURCE_ID).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.circleColor(
                            Expression.step(
                                Expression.get("point_count"),
                                Expression.color(CLUSTER_SMALL),
                                Expression.stop(10, Expression.color(CLUSTER_MEDIUM)),
                                Expression.stop(50, Expression.color(CLUSTER_LARGE)),
                            )
                        ),
                        PropertyFactory.circleRadius(
                            Expression.step(
                                Expression.get("point_count"),
                                14f,
                                Expression.stop(10, 18f),
                                Expression.stop(50, 24f),
                            )
                        ),
                        PropertyFactory.circleStrokeColor(WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleOpacity(0.9f),
                    )
                }
            )
        }

        // Cluster count label. Relies on the style's glyphs (OpenFreeMap ships
        // Noto Sans); if a glyph set is missing the number simply doesn't draw
        // and the sized bubble still conveys density.
        if (style.getLayer(LAYER_CLUSTER_COUNT) == null) {
            style.addLayer(
                SymbolLayer(LAYER_CLUSTER_COUNT, SOURCE_ID).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.textField(Expression.get("point_count_abbreviated")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(WHITE),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    )
                }
            )
        }
    }

    /**
     * Replace the data source with the venue list. Feature assembly runs on
     * [Dispatchers.Default]; the GeoJSON push lands on Main where MapLibre
     * expects it.
     */
    suspend fun setData(style: Style, venues: List<MapVenue>) {
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(venues.map { it.toFeature() })
        }
        withContext(Dispatchers.Main) {
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            if (source == null) {
                Log.w(TAG, "setData: source $SOURCE_ID missing — install() not called yet?")
                return@withContext
            }
            source.setGeoJson(collection)
            Log.d(TAG, "setData: pushed ${venues.size} venues to $SOURCE_ID")
        }
    }

    /** Update the selection halo to a single venue, or clear it. */
    fun setSelected(style: Style, venue: MapVenue?) {
        val collection = if (venue == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(venue.lng, venue.lat))))
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)?.setGeoJson(collection)
    }

    /** Hit-test the unclustered venue layer; returns the tapped venue or null. */
    fun venueAt(map: MapLibreMap, screenX: Float, screenY: Float, venues: List<MapVenue>): MapVenue? {
        val pixel = android.graphics.PointF(screenX, screenY)
        val features = map.queryRenderedFeatures(pixel, LAYER_POINTS)
        val id = features.firstOrNull()?.getStringProperty(PROP_ID) ?: return null
        return venues.firstOrNull { it.id == id }
    }

    /**
     * Hit-test the cluster layer. Returns the camera target that expands the
     * tapped cluster (its centroid + the zoom at which it splits), or null if
     * no cluster was tapped. Caller eases the camera there.
     */
    fun clusterExpansionAt(map: MapLibreMap, style: Style, screenX: Float, screenY: Float): ClusterTarget? {
        val pixel = android.graphics.PointF(screenX, screenY)
        val feature = map.queryRenderedFeatures(pixel, LAYER_CLUSTERS).firstOrNull() ?: return null
        val geometry = feature.geometry()
        val centroid = if (geometry is Point) LatLng(geometry.latitude(), geometry.longitude()) else return null
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return null
        val expansionZoom = runCatching { source.getClusterExpansionZoom(feature) }
            .getOrNull()
            ?.toDouble()
            ?.coerceAtLeast(map.cameraPosition.zoom + 1.0)
            ?: (map.cameraPosition.zoom + 2.0)
        return ClusterTarget(centroid, expansionZoom)
    }

    data class ClusterTarget(val center: LatLng, val zoom: Double)

    private fun MapVenue.toFeature(): Feature =
        Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
            addStringProperty(PROP_ID, id)
            addStringProperty(PROP_BRAND, brandKey.wire)
            addNumberProperty(PROP_COUNT, boards.size)
        }
}
