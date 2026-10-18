package com.cruxcoach.app.map

/**
 * The part of the map the user can actually see. [west] > [east] means the
 * view crosses the antimeridian. Port of Android `MapViewport.kt`.
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

/** Where "My location" found the user. Used on the device only, never stored. */
data class UserPosition(val lat: Double, val lng: Double, val accuracyMeters: Float? = null) {
    fun distanceTo(venue: MapVenue): Double = distanceMeters(lat, lng, venue.lat, venue.lng)
}

/** What the sheet lists while no venue is selected. */
data class VenuesInView(
    /** All filter-passing venues inside the viewport. */
    val total: Int,
    /** The [total] venues nearest the origin first, capped. */
    val nearest: List<MapVenue>,
) {
    companion object {
        val Empty = VenuesInView(0, emptyList())
    }
}

/** Venues in [viewport], nearest to [origin] (the user, once located) or else to the centre first. */
fun venuesInView(
    venues: List<MapVenue>,
    viewport: MapViewport,
    limit: Int = 50,
    origin: UserPosition? = null,
): VenuesInView {
    val inside = venues.filter { viewport.contains(it.lat, it.lng) }
    val lat = origin?.lat ?: viewport.centerLat
    val lng = origin?.lng ?: viewport.centerLng
    val nearest = inside
        .sortedBy { distanceMeters(lat, lng, it.lat, it.lng) }
        .take(limit)
    return VenuesInView(inside.size, nearest)
}

/** The frame for "show nearest boards": the current centre plus the [count] nearest venues. */
fun nearestFrame(venues: List<MapVenue>, viewport: MapViewport, count: Int = 3): MapStartView.Bounds? =
    nearestFrame(venues, viewport.centerLat, viewport.centerLng, count)

/**
 * The point at ([lat], [lng]) and the [count] venues nearest to it, as one
 * frame. Longitudes are taken relative to the point, so a frame across the
 * antimeridian stays narrow. Null when there are no venues.
 */
fun nearestFrame(venues: List<MapVenue>, lat: Double, lng: Double, count: Int = 3): MapStartView.Bounds? {
    val nearest = venues
        .sortedBy { distanceMeters(lat, lng, it.lat, it.lng) }
        .take(count)
    if (nearest.isEmpty()) return null
    fun unwrap(other: Double): Double {
        var d = other - lng
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return lng + d
    }
    val lats = nearest.map { it.lat } + lat
    val lngs = nearest.map { unwrap(it.lng) } + lng
    return MapStartView.Bounds(lats.min(), lngs.min(), lats.max(), lngs.max())
}

/** Where the map opens. */
sealed interface MapStartView {
    data class Camera(val lat: Double, val lng: Double, val zoom: Double) : MapStartView
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) : MapStartView
    /** A camera exactly as the iOS map reported it (the remembered last view). */
    data class Region(val lat: Double, val lng: Double, val latSpan: Double, val lngSpan: Double) : MapStartView

    companion object {
        val World = Camera(25.0, 10.0, 1.3)
    }
}

/**
 * A first view near the user without asking for their location: the country
 * of the device's timezone ("Europe/Vienna" → Vienna in the bundled place
 * index → AT), else the locale's country, framed around that country's boards
 * with the outer 5 % trimmed on each side so an outlier does not zoom out to
 * the planet. A remembered camera from the last visit wins (caller-side).
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
