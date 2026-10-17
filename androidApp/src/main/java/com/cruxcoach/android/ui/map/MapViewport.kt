package com.cruxcoach.android.ui.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The part of the map the user can actually see: between the search bar on
 * top and the sheet at the bottom. [west] > [east] means the view crosses the
 * antimeridian.
 */
data class MapViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val centerLat: Double,
    val centerLng: Double,
) {
    fun contains(lat: Double, lng: Double): Boolean {
        if (lat < south || lat > north) return false
        return if (west <= east) lng in west..east else lng >= west || lng <= east
    }
}

/** What the sheet lists while no venue is selected. */
data class VenuesInView(
    /** All filter-passing venues inside the viewport. */
    val total: Int,
    /** The [total] venues nearest the viewport's centre first, capped. */
    val nearest: List<MapVenue>,
) {
    companion object {
        val Empty = VenuesInView(0, emptyList())
    }
}

/** Venues in [viewport], nearest to its centre first, at most [limit] of them. */
fun venuesInView(venues: List<MapVenue>, viewport: MapViewport, limit: Int = 50): VenuesInView {
    val inside = venues.filter { viewport.contains(it.lat, it.lng) }
    val nearest = inside
        .sortedBy { distanceMeters(viewport.centerLat, viewport.centerLng, it.lat, it.lng) }
        .take(limit)
    return VenuesInView(inside.size, nearest)
}

/** Great-circle distance (haversine), good to a few metres for sorting. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * The frame for "show nearest boards": the current centre plus the [count]
 * nearest venues, so the user sees where they are relative to them. Null when
 * there are no venues. Longitudes are taken relative to the centre, so a
 * frame across the antimeridian stays narrow.
 */
fun nearestFrame(venues: List<MapVenue>, viewport: MapViewport, count: Int = 3): MapStartView.Bounds? {
    val nearest = venues
        .sortedBy { distanceMeters(viewport.centerLat, viewport.centerLng, it.lat, it.lng) }
        .take(count)
    if (nearest.isEmpty()) return null
    fun unwrap(lng: Double): Double {
        var d = lng - viewport.centerLng
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return viewport.centerLng + d
    }
    val lats = nearest.map { it.lat } + viewport.centerLat
    val lngs = nearest.map { unwrap(it.lng) } + viewport.centerLng
    return MapStartView.Bounds(lats.min(), lngs.min(), lats.max(), lngs.max())
}

/** Where the map opens. */
sealed interface MapStartView {
    data class Camera(val lat: Double, val lng: Double, val zoom: Double) : MapStartView
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) : MapStartView

    companion object {
        val World = Camera(25.0, 10.0, 1.3)
    }
}

/**
 * A first view near the user without asking for their location (the map
 * deliberately has no location permission): the country of the device's
 * timezone — "Europe/Vienna" → the city Vienna in the bundled place index →
 * AT — else the locale's country, framed around that country's boards. A
 * remembered camera from the last visit wins over all of this (caller-side).
 *
 * The frame drops the outer 5 % of the country's venues on each side, so an
 * overseas territory or a mis-geocoded row does not zoom the map out to the
 * whole planet.
 */
fun guessStartView(
    timeZoneId: String?,
    localeCountry: String?,
    places: List<MapPlace>,
    venues: List<MapVenue>,
): MapStartView {
    val zoneCity = timeZoneId
        ?.substringAfterLast('/', "")
        ?.replace('_', ' ')
        ?.takeIf { it.isNotBlank() && timeZoneId.contains('/') }
    // Compared without accents: "Europe/Zurich" must find "Zürich".
    val zoneCountry = zoneCity?.let(::normalizeMapSearchText)?.let { city ->
        places.firstOrNull { normalizeMapSearchText(it.name) == city }?.countryCode
            ?: places.firstOrNull { p -> p.aliases.any { normalizeMapSearchText(it) == city } }?.countryCode
    }
    val candidates = listOfNotNull(zoneCountry, localeCountry?.uppercase()?.takeIf { it.length == 2 }).distinct()
    for (country in candidates) {
        val inCountry = venues.filter { it.countryCode == country }
        if (inCountry.isEmpty()) continue
        val lats = inCountry.map { it.lat }.sorted()
        val lngs = inCountry.map { it.lng }.sorted()
        fun trimmed(values: List<Double>): Pair<Double, Double> {
            val cut = (values.size * 0.05).toInt()
            return values[cut] to values[values.size - 1 - cut]
        }
        val (south, north) = trimmed(lats)
        val (west, east) = trimmed(lngs)
        // A single gym (or a tiny country) still gets some surroundings.
        val minSpan = 0.6
        val latPad = ((minSpan - (north - south)) / 2).coerceAtLeast(0.0)
        val lngPad = ((minSpan - (east - west)) / 2).coerceAtLeast(0.0)
        return MapStartView.Bounds(south - latPad, west - lngPad, north + latPad, east + lngPad)
    }
    return MapStartView.World
}
