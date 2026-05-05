package com.cruxcoach.android.ui.map

import android.graphics.Color
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.BoardLocation
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

object MapMarkerLayer {
    const val SOURCE_ID = "board-locations"
    const val LAYER_POINTS = "board-locations-points"
    const val LAYER_CLUSTERS = "board-locations-clusters"
    const val LAYER_CLUSTER_COUNT = "board-locations-cluster-count"

    /** Property keys on each Feature for downstream layer styling + tap dispatch. */
    private const val PROP_ID = "id"
    private const val PROP_ACCESS = "access"
    private const val PROP_SELECTED = "selected"

    private const val CLUSTER_MAX_ZOOM = 6
    private const val CLUSTER_RADIUS = 50

    /** CruxCoach orange — keep in sync with theme OrangeAccent for visual harmony. */
    private val ORANGE = Color.parseColor("#FF6B1A")
    private val GREY = Color.parseColor("#9E9E9E")
    private val WHITE = Color.parseColor("#FFFFFF")

    /**
     * Initial layer setup. Called once when the [Style] is first ready.
     * Subsequent data updates go through [updateData].
     */
    fun install(style: Style) {
        if (style.getSource(SOURCE_ID) != null) return

        val source = GeoJsonSource(
            SOURCE_ID,
            FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions()
                .withCluster(true)
                .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
                .withClusterRadius(CLUSTER_RADIUS),
        )
        style.addSource(source)

        // Cluster bubbles — orange circle, size scales with point count.
        val clusterLayer = CircleLayer(LAYER_CLUSTERS, SOURCE_ID).apply {
            setProperties(
                PropertyFactory.circleColor(ORANGE),
                PropertyFactory.circleStrokeColor(WHITE),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.toNumber(Expression.get("point_count")),
                        Expression.literal(14f),
                        Expression.stop(10, 18f),
                        Expression.stop(50, 24f),
                        Expression.stop(200, 32f),
                    )
                ),
            )
            setFilter(Expression.has("point_count"))
        }
        style.addLayer(clusterLayer)

        // Cluster count label (white text inside the bubble).
        val clusterCountLayer = SymbolLayer(LAYER_CLUSTER_COUNT, SOURCE_ID).apply {
            setProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(WHITE),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
            setFilter(Expression.has("point_count"))
        }
        style.addLayer(clusterCountLayer)

        // Individual point markers — orange for PUBLIC, grey for everything else.
        // Selected point gets a larger radius to give the user feedback after tap.
        val pointLayer = CircleLayer(LAYER_POINTS, SOURCE_ID).apply {
            setProperties(
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get(PROP_ACCESS),
                        Expression.color(GREY),
                        Expression.stop("PUBLIC", Expression.color(ORANGE)),
                    )
                ),
                PropertyFactory.circleRadius(
                    Expression.match(
                        Expression.get(PROP_SELECTED),
                        Expression.literal(7f),
                        Expression.stop(true, Expression.literal(11f)),
                    )
                ),
                PropertyFactory.circleStrokeColor(WHITE),
                PropertyFactory.circleStrokeWidth(1.5f),
            )
            setFilter(Expression.not(Expression.has("point_count")))
        }
        style.addLayer(pointLayer)
    }

    /**
     * Replace the GeoJSON source's data with markers for [locations]. Called
     * whenever the filtered location list or the selection changes — MapLibre
     * recomputes clusters and re-renders affected tiles automatically.
     */
    fun updateData(style: Style, locations: List<BoardLocation>, selectedId: Long?) {
        val features = locations.map { it.toFeature(selectedId) }
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /**
     * Find the [BoardLocation] under a screen-space tap by querying rendered
     * features in the points layer. Returns null on cluster tap (caller
     * handles cluster expansion separately by zooming in).
     */
    fun locationAt(map: MapLibreMap, screenX: Float, screenY: Float, all: List<BoardLocation>): BoardLocation? {
        val pixel = android.graphics.PointF(screenX, screenY)
        val features = map.queryRenderedFeatures(pixel, LAYER_POINTS)
        val id = features.firstOrNull()?.getNumberProperty(PROP_ID)?.toLong() ?: return null
        return all.firstOrNull { it.id == id }
    }

    /**
     * If a tap hit a cluster, return its centre [LatLng] + a target zoom one
     * level deeper so the cluster expands. Caller animates the camera there.
     * Returns null when the tap missed any cluster.
     */
    fun clusterAt(map: MapLibreMap, screenX: Float, screenY: Float): Pair<Point, Double>? {
        val pixel = android.graphics.PointF(screenX, screenY)
        val features = map.queryRenderedFeatures(pixel, LAYER_CLUSTERS)
        val feature = features.firstOrNull() ?: return null
        val geom = feature.geometry() as? Point ?: return null
        val targetZoom = (map.cameraPosition.zoom + 2.0).coerceAtMost(15.0)
        return geom to targetZoom
    }

    private fun BoardLocation.toFeature(selectedId: Long?): Feature {
        val point = Point.fromLngLat(lng, lat)
        return Feature.fromGeometry(point).apply {
            addNumberProperty(PROP_ID, id)
            addStringProperty(PROP_ACCESS, accessTypeKey(accessType))
            addBooleanProperty(PROP_SELECTED, selectedId == id)
        }
    }

    private fun accessTypeKey(t: AccessType): String = when (t) {
        AccessType.PUBLIC -> "PUBLIC"
        AccessType.PRIVATE -> "PRIVATE"
        AccessType.MEMBERS -> "MEMBERS"
        AccessType.UNKNOWN -> "UNKNOWN"
    }
}
