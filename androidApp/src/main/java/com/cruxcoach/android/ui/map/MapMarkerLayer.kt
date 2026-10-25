package com.cruxcoach.android.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import android.util.Log
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * Map layer + source manager for board-location markers.
 *
 * Two-stage de-overlap so dense regions stay legible:
 *  1. **Venue grouping** (caller-side, [groupIntoVenues]) collapses boards
 *     that sit on the same coordinate — a gym with both a Kilter and a
 *     MoonBoard becomes one marker, not two stacked dots.
 *  2. **Clustering** (here, via [GeoJsonOptions.withCluster]) collapses
 *     nearby venues into a count bubble until the user zooms in. Tapping a
 *     cluster eases the camera to the zoom where it splits apart.
 *
 * Venues are symbols, not circles: each shows its family's colour and letter
 * ([MapMarkerIcons]), mixed venues a pie of their families, and from city zoom
 * on the gym's name underneath. Clusters are symbols too, so the base map's
 * place labels make room for them instead of being painted over. Clusters are
 * neutral (they mix brands) unless the brand filter leaves exactly one family,
 * then they take its colour. [SOURCE_SELECTED] keeps a single-feature
 * highlight so selection never re-pushes the main dataset.
 */
object MapMarkerLayer {
    private const val TAG = "MapMarkerLayer"

    const val SOURCE_ID = "board-locations"
    const val SOURCE_SELECTED = "board-location-selected"
    const val SOURCE_USER = "board-map-user"

    const val LAYER_CLUSTERS = "board-locations-clusters"
    const val LAYER_POINTS = "board-locations-points"
    const val LAYER_SELECTED_HALO = "board-locations-selected-halo"
    const val LAYER_SELECTED = "board-locations-selected"
    const val LAYER_USER_ACCURACY = "board-map-user-accuracy"
    const val LAYER_USER_DOT = "board-map-user-dot"

    private val USER_BLUE = android.graphics.Color.parseColor("#1A73E8")

    private const val PROP_ID = "id"
    private const val PROP_ICON = "icon"
    private const val PROP_NAME = "name"
    private const val PROP_HALO = "halo"

    /** From this zoom on, venue names are drawn under their markers. */
    const val LABEL_MIN_ZOOM = 11f

    private const val FONT_REGULAR = "Noto Sans Regular"
    private const val FONT_BOLD = "Noto Sans Bold"

    /** Cluster sizes by venue count: diameters in dp for <10, <50, <200, more. */
    private val CLUSTER_BUCKETS = listOf("s" to 30f, "m" to 36f, "l" to 44f, "xl" to 52f)

    /** Colours that depend on the map style (light tiles vs. dark tiles). */
    data class Theme(
        val isDark: Boolean,
        val labelColor: Int,
        val labelHalo: Int,
        val clusterColor: Int,
        val onClusterColor: Int,
    ) {
        companion object {
            val Light = Theme(false, 0xFF1F2328.toInt(), 0xFFFFFFFF.toInt(), 0xFF263238.toInt(), 0xFFFFFFFF.toInt())
            val Dark = Theme(true, 0xFFE8EAED.toInt(), 0xFF101214.toInt(), 0xFFE3E7EA.toInt(), 0xFF1B2328.toInt())
            fun of(isDark: Boolean) = if (isDark) Dark else Light
        }
    }

    /** Initial layer + source setup. Idempotent — safe after a style reload. */
    fun install(style: Style, theme: Theme, density: Float, clusterBrand: BoardBrand? = null) {
        if (style.getSource(SOURCE_ID) == null) {
            // Cluster the venue points. radius 50 px groups city-dense
            // venues; maxZoom 13 lets clusters split before street level so
            // individual gyms are tappable. Generous buffer + maxZoom 22 keep
            // points served to the edge at full pinch-zoom.
            style.addSource(
                GeoJsonSource(
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
            )
        }
        if (style.getSource(SOURCE_SELECTED) == null) {
            style.addSource(
                GeoJsonSource(SOURCE_SELECTED, FeatureCollection.fromFeatures(emptyList()), GeoJsonOptions().withMaxZoom(22))
            )
        }

        // "You are here" sits below the venue markers, so a gym at the
        // user's own position stays visible and tappable.
        if (style.getSource(SOURCE_USER) == null) {
            style.addSource(GeoJsonSource(SOURCE_USER, FeatureCollection.fromFeatures(emptyList())))
        }
        if (style.getLayer(LAYER_USER_ACCURACY) == null) {
            style.addLayer(
                CircleLayer(LAYER_USER_ACCURACY, SOURCE_USER).apply {
                    setProperties(
                        PropertyFactory.circleColor(USER_BLUE),
                        PropertyFactory.circleOpacity(0.12f),
                        PropertyFactory.circleStrokeColor(USER_BLUE),
                        PropertyFactory.circleStrokeOpacity(0.35f),
                        PropertyFactory.circleStrokeWidth(1f),
                        PropertyFactory.circleRadius(0f),
                    )
                }
            )
        }
        if (style.getLayer(LAYER_USER_DOT) == null) {
            style.addLayer(
                CircleLayer(LAYER_USER_DOT, SOURCE_USER).apply {
                    setProperties(
                        PropertyFactory.circleColor(USER_BLUE),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                        PropertyFactory.circleStrokeWidth(3f),
                    )
                }
            )
        }

        if (style.getLayer(LAYER_CLUSTERS) == null) {
            style.addLayer(
                SymbolLayer(LAYER_CLUSTERS, SOURCE_ID).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textField(Expression.get("point_count_abbreviated")),
                        PropertyFactory.textFont(arrayOf(FONT_BOLD)),
                        PropertyFactory.textSize(
                            Expression.step(Expression.get("point_count"), Expression.literal(13f), Expression.stop(50, 14f))
                        ),
                    )
                }
            )
        }
        setClusterStyle(style, theme, density, clusterBrand)

        if (style.getLayer(LAYER_POINTS) == null) {
            style.addLayer(
                SymbolLayer(LAYER_POINTS, SOURCE_ID).apply {
                    setFilter(Expression.not(Expression.has("point_count")))
                    setProperties(
                        PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                        PropertyFactory.iconSize(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(3, 0.62f),
                                Expression.stop(7, 0.78f),
                                Expression.stop(11, 0.95f),
                                Expression.stop(15, 1.05f),
                            )
                        ),
                        // Markers never disappear behind each other or behind
                        // the base map's labels; only our own names may drop out.
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.textField(
                            Expression.step(
                                Expression.zoom(), Expression.literal(""),
                                Expression.stop(LABEL_MIN_ZOOM, Expression.get(PROP_NAME)),
                            )
                        ),
                        PropertyFactory.textFont(arrayOf(FONT_REGULAR)),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 1.15f)),
                        PropertyFactory.textMaxWidth(9f),
                        PropertyFactory.textOptional(true),
                    )
                }
            )
        }

        if (style.getLayer(LAYER_SELECTED_HALO) == null) {
            style.addLayer(
                CircleLayer(LAYER_SELECTED_HALO, SOURCE_SELECTED).apply {
                    setProperties(
                        PropertyFactory.circleColor(Expression.toColor(Expression.get(PROP_HALO))),
                        PropertyFactory.circleOpacity(0.22f),
                        PropertyFactory.circleRadius(25f),
                        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get(PROP_HALO))),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeOpacity(0.85f),
                    )
                }
            )
        }
        if (style.getLayer(LAYER_SELECTED) == null) {
            style.addLayer(
                SymbolLayer(LAYER_SELECTED, SOURCE_SELECTED).apply {
                    setProperties(
                        PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                        PropertyFactory.iconSize(1.3f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.textField(Expression.get(PROP_NAME)),
                        PropertyFactory.textFont(arrayOf(FONT_BOLD)),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 1.45f)),
                        PropertyFactory.textMaxWidth(10f),
                        PropertyFactory.textAllowOverlap(true),
                    )
                }
            )
        }

        listOf(LAYER_POINTS, LAYER_SELECTED).forEach { id ->
            style.getLayer(id)?.setProperties(
                PropertyFactory.textColor(theme.labelColor),
                PropertyFactory.textHaloColor(theme.labelHalo),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloBlur(0.3f),
            )
        }
    }

    /**
     * Cluster colour: neutral when the visible venues mix families, the
     * family's colour when the brand filter leaves exactly one. Cluster
     * images are drawn per colour on demand.
     */
    fun setClusterStyle(style: Style, theme: Theme, density: Float, clusterBrand: BoardBrand?) {
        val markerStyle = clusterBrand?.markerStyle()
        val fill = markerStyle?.color ?: theme.clusterColor
        val onFill = markerStyle?.onColor ?: theme.onClusterColor
        val suffix = Integer.toHexString(fill)
        CLUSTER_BUCKETS.forEach { (bucket, diameter) ->
            val id = "cluster-$bucket-$suffix"
            if (style.getImage(id) == null) style.addImage(id, renderCluster(fill, diameter, density))
        }
        style.getLayer(LAYER_CLUSTERS)?.setProperties(
            PropertyFactory.iconImage(
                Expression.step(
                    Expression.get("point_count"),
                    Expression.literal("cluster-s-$suffix"),
                    Expression.stop(10, "cluster-m-$suffix"),
                    Expression.stop(50, "cluster-l-$suffix"),
                    Expression.stop(200, "cluster-xl-$suffix"),
                )
            ),
            PropertyFactory.textColor(onFill),
        )
    }

    /** A filled disc with a white ring inside a translucent halo of the same colour. */
    internal fun renderCluster(fill: Int, diameterDp: Float, density: Float): Bitmap {
        val halo = 5f * density
        val radius = diameterDp / 2f * density
        val size = Math.round(2 * (radius + halo))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = Math.round(density * DisplayMetrics.DENSITY_DEFAULT)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = (fill and 0x00FFFFFF) or 0x40000000
        canvas.drawCircle(c, c, radius + halo, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(c, c, radius, paint)
        paint.color = fill
        canvas.drawCircle(c, c, radius - 2f * density, paint)
        return bitmap
    }

    /**
     * Replace the data source with the venue list. Feature assembly (and the
     * marker images it needs) runs on [Dispatchers.Default]; registering images
     * and pushing the GeoJSON land on Main where MapLibre expects them.
     */
    suspend fun setData(style: Style, venues: List<MapVenue>, density: Float) {
        val missing = withContext(Dispatchers.Main) {
            if (!style.isFullyLoaded) return@withContext emptyList()
            venues.mapTo(linkedSetOf()) { it.markerBrands }
                .filter { style.getImage(MapMarkerIcons.iconId(it)) == null }
        }
        val (collection, icons) = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(venues.map { it.toFeature() }) to
                missing.associate { MapMarkerIcons.iconId(it) to MapMarkerIcons.render(it, density) }
        }
        withContext(Dispatchers.Main) {
            if (!style.isFullyLoaded) return@withContext
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            if (source == null) {
                Log.w(TAG, "setData: source $SOURCE_ID missing — install() not called yet?")
                return@withContext
            }
            if (icons.isNotEmpty()) style.addImages(HashMap(icons))
            source.setGeoJson(collection)
            Log.d(TAG, "setData: pushed ${venues.size} venues to $SOURCE_ID")
        }
    }

    /**
     * Show (or clear) the user's position: a blue dot and, around it, the
     * accuracy as a circle of the right size in metres at every zoom.
     */
    fun setUserPosition(style: Style, position: UserPosition?) {
        if (!style.isFullyLoaded) return
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_USER) ?: return
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.lng, position.lat)))
        // Metres per pixel at zoom 0 for MapLibre's 512-px world, at this latitude.
        val metersPerPixelZ0 = 40_075_016.686 * kotlin.math.cos(Math.toRadians(position.lat)) / 512.0
        val radiusZ0 = ((position.accuracyMeters ?: 0f) / metersPerPixelZ0).toFloat()
        style.getLayer(LAYER_USER_ACCURACY)?.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2f), Expression.zoom(),
                    Expression.stop(0, radiusZ0),
                    Expression.stop(22, radiusZ0 * (1 shl 22)),
                )
            )
        )
    }

    /** Update the selection highlight to a single venue, or clear it. */
    fun setSelected(style: Style, venue: MapVenue?, density: Float) {
        if (!style.isFullyLoaded) return
        val collection = if (venue == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            val brands = venue.markerBrands
            val icon = MapMarkerIcons.iconId(brands)
            if (style.getImage(icon) == null) style.addImage(icon, MapMarkerIcons.render(brands, density))
            FeatureCollection.fromFeatures(listOf(venue.toFeature()))
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED)?.setGeoJson(collection)
    }

    /** Hit-test the venue markers (selected one first); returns the venue id or null. */
    fun venueIdAt(map: MapLibreMap, screenX: Float, screenY: Float, density: Float): String? {
        val pixel = android.graphics.PointF(screenX, screenY)
        // A finger is wider than the marker: probe a small box, not a point.
        val slop = 10f * density
        val box = android.graphics.RectF(pixel.x - slop, pixel.y - slop, pixel.x + slop, pixel.y + slop)
        val features = map.queryRenderedFeatures(box, LAYER_SELECTED, LAYER_POINTS)
        return features.minByOrNull { f ->
            val p = f.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
            val s = map.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
            (s.x - pixel.x) * (s.x - pixel.x) + (s.y - pixel.y) * (s.y - pixel.y)
        }?.getStringProperty(PROP_ID)
    }

    /**
     * Hit-test the cluster layer. Returns the camera target that expands the
     * tapped cluster (its centroid + the zoom at which it splits), or null if
     * no cluster was tapped. Caller eases the camera there.
     */
    fun clusterExpansionAt(map: MapLibreMap, style: Style, screenX: Float, screenY: Float): ClusterTarget? {
        if (!style.isFullyLoaded) return null
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
            addStringProperty(PROP_ICON, MapMarkerIcons.iconId(markerBrands))
            addStringProperty(PROP_NAME, name)
            addStringProperty(PROP_HALO, String.format(Locale.ROOT, "#%06X", markerBrands.first().markerStyle().color and 0xFFFFFF))
        }
}
