package com.cruxcoach.app.ui

import com.cruxcoach.app.gym.GymBoardCatalog
import com.cruxcoach.app.map.AngleFact
import com.cruxcoach.app.map.MapBoardInfo
import com.cruxcoach.app.map.MapError
import com.cruxcoach.app.map.MapLinks
import com.cruxcoach.app.map.MapMarkerStyles
import com.cruxcoach.app.map.MapSearchResult
import com.cruxcoach.app.map.MapStartView
import com.cruxcoach.app.map.MapViewport
import com.cruxcoach.app.map.UserPosition
import com.cruxcoach.app.map.VenueHours
import com.cruxcoach.app.map.angleFact
import com.cruxcoach.app.map.formatHoursDays
import com.cruxcoach.app.map.formatHoursRanges
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.math.pow
import com.cruxcoach.app.map.MapFilters
import com.cruxcoach.app.map.MapPresenter
import com.cruxcoach.app.map.MapState
import com.cruxcoach.app.map.MapStats
import com.cruxcoach.app.map.MapVenue
import com.cruxcoach.app.map.MoonLedState
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand

/**
 * One map pin. A pin is a VENUE, not a board row: co-located boards collapse
 * into one pin (see `groupIntoVenues`), so [boardCount] can exceed 1.
 */
class MapPinUi(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    /** City and/or ISO country code — data, never translated text. */
    val subtitle: String,
    /** Marker colour bucket: kilter | moonboard | multi | other. */
    val brandWire: String,
    /** True when a Kilter Homewall is installed at this venue. */
    val isHomewall: Boolean,
    val boardCount: Int,
    /** 0.2.4 marker: one colour + letter per family (a pie for several), declaration order. */
    val markerBrandWires: List<String>,
)

/** One board installed at a venue. */
class MapBoardUi(
    /** BoardBrand wire value, e.g. kilter | moonboard | tension. */
    val brandWire: String,
    /** Product name, not translatable text. */
    val brandTitle: String,
    /** Layout name from the dataset ("Original", "MoonBoard 2016", …); "" when unknown. */
    val layoutLabel: String,
    val sizeLabel: String,
    /** public | private | members | unknown */
    val accessCode: String,
    /** adjustable | fixed | unknown */
    val adjustabilityCode: String,
    /** 0 when the board is adjustable or the angle is unknown. */
    val fixedAngle: Int,
    val acceptsWellpass: Boolean,
    // ── 0.2.4 venue detail (Android `VenueSheet.BoardCard`); rows without data stay empty ──
    val id: String,
    /** "Kilter Board · Original", a MoonBoard variant, a brand; "" = unknown MoonBoard variant. */
    val titleText: String,
    val isMoonBoard: Boolean,
    /** MoonBoard LED kit: led | noLed | "" (unknown). */
    val ledCode: String,
    /** MoonBoard angle, 0 when unknown. */
    val moonAngle: Int,
    /** Size as the official app names it, the layout prefix removed; "" when unknown. */
    val sizeText: String,
    /** range | set | adjustable | fixedAt | fixed | "" (unknown). */
    val angleCode: String,
    val angleMin: Int,
    val angleMax: Int,
    /** 0 when the step is not published. */
    val angleStep: Int,
    val angleValue: Int,
    val wallName: String,
    val frameMaker: String,
    val models: List<String>,
    /** browse (layout known) | choose (gym search with this place) | "" (map-only family). */
    val actionCode: String,
    /** The board to browse when [actionCode] is browse. */
    val browseBoard: BoardOption?,
)

class MapVenueUi(
    val id: String,
    val name: String,
    val city: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val url: String,
    val boardCount: Int,
    val boards: List<MapBoardUi>,
    // ── 0.2.4 ──
    val markerBrandWires: List<String>,
    /** The town was attached by distance: Swift says "near X". */
    val cityIsNearest: Boolean,
    /** German name of that town, "" when unknown. */
    val nearestCityDe: String,
    val hasHours: Boolean,
    /** Validated https link to the hours' source when it differs from the website, else "". */
    val hoursSourceUrl: String,
    /** Validated website (public http/https host only), else "". */
    val websiteUrl: String,
    /** The number as published, and its dialer-safe form; both "" when unusable. */
    val phoneText: String,
    val phoneDial: String,
    val email: String,
    val instagramUrl: String,
)

/** One opening-hours row: a run of identical days. */
class MapHoursRowUi(val days: String, val ranges: String, val closed: Boolean)

/** One place in the sheet's "in view" list. */
class MapVenueRowUi(
    val id: String,
    val name: String,
    val city: String,
    val cityIsNearest: Boolean,
    val nearestCityDe: String,
    val countryCode: String,
    val markerBrandWires: List<String>,
    /** Family names ("Kilter Board, MoonBoard"): product names. */
    val boardsText: String,
    val hasDistance: Boolean,
    val distance: GymDistanceUi,
)

/** One search hit: a venue (opens it) or a place (moves the camera). */
class MapSearchResultUi(
    val index: Int,
    /** venue | place */
    val kind: String,
    val title: String,
    /** City or region; Swift appends the localized country. */
    val subtitle: String,
    val countryCode: String,
    val markerBrandWires: List<String>,
)

/** Marker colour (0xAARRGGBB), letter colour and monogram of one family. */
class MapMarkerStyleUi(val brandWire: String, val colorArgb: Long, val onColorArgb: Long, val monogram: String)

/** A camera as MapKit takes it. */
class MapCameraUi(val latitude: Double, val longitude: Double, val latitudeDelta: Double, val longitudeDelta: Double)

/** A labelled count; [code] is what a toggle call takes back. */
class MapCountUi(
    val code: String,
    /** Proper noun or ISO code — Swift localizes country codes itself. */
    val label: String,
    val count: Int,
    val isSelected: Boolean,
)

/**
 * Map statistics. Every number counts BOARDS across the WHOLE dataset,
 * independent of the active map filters, and a venue may hold several boards —
 * the Android rule behind the `map_stats_scope` note.
 */
class MapStatsUi(
    val total: Int,
    val originalCount: Int,
    val homewallCount: Int,
    val publicCount: Int,
    val privateCount: Int,
    val membersCount: Int,
    val accessUnknownCount: Int,
    val adjustableCount: Int,
    val fixedCount: Int,
    val adjUnknownCount: Int,
    val countryCount: Int,
    val byCountry: List<MapCountUi>,
    val bySize: List<MapCountUi>,
    val byBrand: List<MapCountUi>,
    val byLayout: List<MapCountUi>,
)

class MapFiltersUi(
    val showOriginal: Boolean,
    val showHomewalls: Boolean,
    val matchesMyBoard: Boolean,
    val wellpassOnly: Boolean,
    val countries: List<String>,
    /** public | private | members | unknown */
    val accessTypes: List<String>,
    /** adjustable | fixed | unknown (Android's three chips). */
    val adjustabilities: List<String>,
    val sizeIds: List<Int>,
    val brands: List<String>,
    val moonLayoutIds: List<Int>,
    /** led | noLed | unknown */
    val moonLedStates: List<String>,
    val isAtDefault: Boolean,
)

class MapScreenState(
    val isLoading: Boolean,
    /** none | loadFailed */
    val errorCode: String,
    val hasData: Boolean,
    /** One entry per venue that passes the filters. */
    val pins: List<MapPinUi>,
    val venueCount: Int,
    val totalVenueCount: Int,
    val filters: MapFiltersUi,
    val canFilterByMyBoard: Boolean,
    /** Chips derived from the full dataset, in Android's order. */
    val brandOptions: List<MapCountUi>,
    val moonVariantOptions: List<MapCountUi>,
    val sizeOptions: List<MapCountUi>,
    val countryOptions: List<MapCountUi>,
    val stats: MapStatsUi,
    val selectedVenue: MapVenueUi?,
    val initialLatitude: Double,
    val initialLongitude: Double,
    /** Latitude/longitude span of the initial camera — Android's zoom 1.0 world view. */
    val initialSpanDegrees: Double,
    // ── 0.2.4 ──
    /** Where the map opens (remembered camera, else the timezone's country, else the world). */
    val hasStartCamera: Boolean,
    val startCamera: MapCameraUi,
    /** Increments when Kotlin asks for a camera move ("My location", a search pick). */
    val cameraRequestId: Int,
    val cameraRequest: MapCameraUi,
    val hasUserPosition: Boolean,
    val userLatitude: Double,
    val userLongitude: Double,
    /** Filter dimensions besides the brand choice: the number on the "Filters" chip. */
    val filterCount: Int,
    val markerStyles: List<MapMarkerStyleUi>,
    val hasViewport: Boolean,
    val inViewTotal: Int,
    /** In-view venues, nearest to the user (once located) or the centre first, at most 50. */
    val inView: List<MapVenueRowUi>,
    val inViewFromUser: Boolean,
    val searchQuery: String,
    val searchResults: List<MapSearchResultUi>,
)

/** Codes for the map enums. */
internal object MapCodes {
    fun access(value: AccessType): String = when (value) {
        AccessType.PUBLIC -> "public"
        AccessType.PRIVATE -> "private"
        AccessType.MEMBERS -> "members"
        AccessType.UNKNOWN -> "unknown"
    }

    fun access(code: String): AccessType? = when (code) {
        "public" -> AccessType.PUBLIC
        "private" -> AccessType.PRIVATE
        "members" -> AccessType.MEMBERS
        "unknown" -> AccessType.UNKNOWN
        else -> null
    }

    /** FULL/LIMITED collapse into "adjustable", exactly as Android's chips do. */
    fun adjustability(value: Adjustability): String = when (value) {
        Adjustability.ADJUSTABLE, Adjustability.FULL, Adjustability.LIMITED -> "adjustable"
        Adjustability.FIXED -> "fixed"
        Adjustability.UNKNOWN -> "unknown"
    }

    fun adjustability(code: String): Adjustability? = when (code) {
        "adjustable" -> Adjustability.ADJUSTABLE
        "fixed" -> Adjustability.FIXED
        "unknown" -> Adjustability.UNKNOWN
        else -> null
    }

    fun led(value: MoonLedState): String = when (value) {
        MoonLedState.LED -> "led"
        MoonLedState.NO_LED -> "noLed"
        MoonLedState.UNKNOWN -> "unknown"
    }

    fun led(code: String): MoonLedState? = when (code) {
        "led" -> MoonLedState.LED
        "noLed" -> MoonLedState.NO_LED
        "unknown" -> MoonLedState.UNKNOWN
        else -> null
    }
}

/**
 * Board map screen: the offline board-location dataset as pins, the filter
 * state and the statistics.
 *
 * The statistics always describe every recorded board, never just the pins the
 * filters leave visible — that is the Android behaviour; the "showing N of M"
 * venue counters are where the filtered view is reported.
 */
class MapScreenModel(private val presenter: MapPresenter, private val germanUi: Boolean = false) {

    val currentState: MapScreenState get() = map(presenter.state.value)

    fun watch(onState: (MapScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()

    /** "" clears the selection. */
    fun selectVenue(id: String) = presenter.selectVenue(id.ifEmpty { null })

    /** The visible region after a camera move; [west] > [east] crosses the antimeridian. */
    fun setViewport(south: Double, west: Double, north: Double, east: Double, centerLatitude: Double, centerLongitude: Double) {
        if (listOf(south, west, north, east, centerLatitude, centerLongitude).any { !it.isFinite() }) return
        presenter.setViewport(MapViewport(south, west, north, east, centerLatitude, centerLongitude))
    }

    fun rememberCamera(latitude: Double, longitude: Double, latitudeDelta: Double, longitudeDelta: Double) =
        presenter.rememberCamera(latitude, longitude, latitudeDelta, longitudeDelta)

    /** "My location" found the user; out-of-range values are ignored. The position is never stored. */
    fun showMyLocation(latitude: Double, longitude: Double) {
        if (latitude.isNaN() || longitude.isNaN() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        presenter.showUserPosition(UserPosition(latitude, longitude))
    }

    fun showNearestBoards() = presenter.showNearestToCentre()
    fun search(query: String) = presenter.search(query)
    fun pickSearchResult(index: Int) = presenter.pickSearchResult(index)

    /** The rows of one venue for the gym search ("Choose this board"). */
    internal fun venueRows(venueId: String): List<BoardLocation> = presenter.venueRows(venueId)

    /**
     * Opening-hours rows of the selected venue, Monday first. Swift passes the
     * seven short day names and the words; empty when the venue published none.
     */
    fun hoursRows(venueId: String, dayNames: List<String>, closed: String, allDay: String, nextDay: String): List<MapHoursRowUi> {
        if (dayNames.size != 7) return emptyList()
        val s = presenter.state.value
        val hours: VenueHours = (s.filteredVenues + s.unfilteredVenues).firstOrNull { it.id == venueId }?.info?.hours
            ?: return emptyList()
        return hours.groups().map { g ->
            MapHoursRowUi(formatHoursDays(g, dayNames), formatHoursRanges(g.ranges, closed, allDay, nextDay), g.ranges.isEmpty())
        }
    }

    fun toggleShowOriginal() = presenter.toggleShowOriginal()
    fun toggleShowHomewalls() = presenter.toggleShowHomewalls()
    fun selectAllLayouts() = presenter.selectAllLayouts()
    fun toggleMatchesMyBoard() = presenter.toggleMatchesMyBoard()
    fun toggleWellpassOnly() = presenter.toggleWellpassOnly()
    fun toggleCountry(code: String) = presenter.toggleCountry(code)
    fun toggleSizeId(sizeId: Int) = presenter.toggleSizeId(sizeId)
    fun toggleMoonLayoutId(layoutId: Int) = presenter.toggleMoonLayoutId(layoutId)
    fun selectAllBrands() = presenter.selectAllBrands()
    fun toggleOtherBrands() = presenter.toggleOtherBrands()
    fun resetFilters() = presenter.resetFilters()

    /** Unknown codes are ignored rather than throwing across the bridge. */
    fun toggleBrand(brandWire: String) {
        BoardBrand.fromWireOrNull(brandWire)?.let { presenter.toggleBrand(it) }
    }

    fun toggleAccessType(code: String) {
        MapCodes.access(code)?.let { presenter.toggleAccessType(it) }
    }

    fun toggleAdjustability(code: String) {
        MapCodes.adjustability(code)?.let { presenter.toggleAdjustability(it) }
    }

    fun toggleMoonLedState(code: String) {
        MapCodes.led(code)?.let { presenter.toggleMoonLedState(it) }
    }

    private fun map(state: MapState): MapScreenState = MapScreenState(
        isLoading = state.isLoading,
        errorCode = if (state.error == MapError.LOAD_FAILED) "loadFailed" else "none",
        hasData = !state.noLocationData,
        pins = state.filteredVenues.map { pin(it) },
        venueCount = state.filteredVenues.size,
        totalVenueCount = state.unfilteredVenues.size,
        filters = filters(state.filters),
        canFilterByMyBoard = state.canFilterByMyBoard,
        brandOptions = brandOptions(state),
        moonVariantOptions = moonVariantOptions(state),
        sizeOptions = sizeOptions(state),
        countryOptions = countryOptions(state),
        stats = stats(state.unfilteredStats),
        selectedVenue = state.selectedVenueId?.let { id ->
            (state.filteredVenues.firstOrNull { it.id == id } ?: state.unfilteredVenues.firstOrNull { it.id == id })
                ?.let { venue(it, state.boardInfo) }
        },
        initialLatitude = INITIAL_LATITUDE,
        initialLongitude = INITIAL_LONGITUDE,
        initialSpanDegrees = INITIAL_SPAN_DEGREES,
        hasStartCamera = state.startView != null,
        startCamera = camera(state.startView ?: MapStartView.World),
        cameraRequestId = state.cameraRequestId,
        cameraRequest = camera(state.cameraRequest ?: MapStartView.World),
        hasUserPosition = state.userPosition != null,
        userLatitude = state.userPosition?.lat ?: 0.0,
        userLongitude = state.userPosition?.lng ?: 0.0,
        filterCount = state.filters.activeCountExceptBrands,
        markerStyles = MARKER_STYLES,
        hasViewport = state.viewport != null,
        inViewTotal = state.inView.total,
        inView = state.inView.nearest.map { row(it, state.userPosition) },
        inViewFromUser = state.userPosition != null,
        searchQuery = state.searchQuery,
        searchResults = state.searchResults.mapIndexed { i, r -> searchResult(i, r) },
    )

    private fun row(venue: MapVenue, user: UserPosition?) = MapVenueRowUi(
        id = venue.id,
        name = venue.name,
        city = venue.city ?: "",
        cityIsNearest = venue.info.cityIsNearest,
        nearestCityDe = venue.info.nearestCityDe ?: "",
        countryCode = venue.countryCode.takeIf { it != "??" } ?: "",
        markerBrandWires = venue.markerBrands.map { it.wireValue },
        boardsText = venue.markerBrands.joinToString(", ") { it.displayName },
        hasDistance = user != null,
        distance = GymSearchScreenModel.distance(user?.distanceTo(venue) ?: 0.0),
    )

    private fun searchResult(index: Int, result: MapSearchResult) = when (result) {
        is MapSearchResult.Venue -> MapSearchResultUi(
            index, "venue", result.venue.name, result.venue.city ?: "",
            result.venue.countryCode.takeIf { it != "??" } ?: "",
            result.venue.markerBrands.map { it.wireValue },
        )
        is MapSearchResult.Place -> MapSearchResultUi(
            index, "place", if (germanUi) result.place.germanName ?: result.place.name else result.place.name,
            result.place.region ?: "", result.place.countryCode, emptyList(),
        )
    }

    private fun pin(venue: MapVenue) = MapPinUi(
        id = venue.id,
        latitude = venue.lat,
        longitude = venue.lng,
        title = venue.name,
        subtitle = listOfNotNull(
            venue.city?.takeIf { it.isNotBlank() },
            venue.countryCode.takeIf { it.isNotBlank() && it != "??" },
        ).joinToString(" · "),
        brandWire = venue.brandKey.wire,
        isHomewall = venue.hasHomewall,
        boardCount = venue.boards.size,
        markerBrandWires = venue.markerBrands.map { it.wireValue },
    )

    private fun venue(venue: MapVenue, boardInfo: Map<String, MapBoardInfo>): MapVenueUi {
        // The board with the most contact detail speaks for the venue (Android `contactBoard`).
        val contact = venue.boards.maxByOrNull { b -> listOf(b.phone, b.email, b.url, b.address).count { !it.isNullOrBlank() } }
            ?: venue.boards.first()
        val website = MapLinks.validatedHttpUrlOrNull(venue.boards.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }) ?: ""
        val source = MapLinks.validatedHttpUrlOrNull(venue.info.hoursSource)
        val phoneDial = MapLinks.sanitisedPhoneOrNull(contact.phone) ?: ""
        return MapVenueUi(
            id = venue.id,
            name = venue.name,
            city = venue.city ?: "",
            countryCode = venue.countryCode.takeIf { it != "??" } ?: "",
            latitude = venue.lat,
            longitude = venue.lng,
            address = venue.boards.firstNotNullOfOrNull { it.address?.takeIf(String::isNotBlank) } ?: "",
            url = venue.boards.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) } ?: "",
            boardCount = venue.boards.size,
            boards = venue.boards.map { board(it, boardInfo[it.id]) },
            markerBrandWires = venue.markerBrands.map { it.wireValue },
            cityIsNearest = venue.info.cityIsNearest,
            nearestCityDe = venue.info.nearestCityDe ?: "",
            hasHours = venue.info.hours != null,
            hoursSourceUrl = source?.takeIf { venue.info.hoursSource != contact.url && it != website } ?: "",
            websiteUrl = website,
            phoneText = if (phoneDial.isEmpty()) "" else contact.phone!!.trim(),
            phoneDial = phoneDial,
            email = MapLinks.validatedEmailOrNull(contact.email) ?: "",
            instagramUrl = MapLinks.instagramUrlOrNull(venue.boards.firstNotNullOfOrNull { it.instagram?.takeIf(String::isNotBlank) }) ?: "",
        )
    }

    private fun board(location: BoardLocation, info: MapBoardInfo?): MapBoardUi {
        val brand = location.boardBrand
        val size = (location.productSizeId?.let { id ->
            if (brand == BoardBrand.KILTER) GymBoardCatalog.kilterSizeLabel(id.toLong(), location.sizeLabel.orEmpty()) else location.sizeLabel
        } ?: location.sizeLabel)?.takeIf(String::isNotBlank)?.let { s ->
            val layout = location.layoutName
            if (layout != null && s.startsWith("$layout ", ignoreCase = true)) s.substring(layout.length + 1) else s
        } ?: ""
        val fact = if (brand == BoardBrand.MOONBOARD) null else angleFact(location.adjustability, info, location.fixedAngle)
        val layoutId = location.layoutId
        val action = when {
            !brand.isInteractive -> ""
            layoutId != null -> "browse"
            else -> "choose"
        }
        return MapBoardUi(
            brandWire = location.boardBrand.wireValue,
            brandTitle = location.boardBrand.displayName,
            layoutLabel = location.layoutName?.takeIf(String::isNotBlank) ?: "",
            sizeLabel = location.sizeLabel?.takeIf(String::isNotBlank) ?: "",
            accessCode = MapCodes.access(location.accessType),
            adjustabilityCode = MapCodes.adjustability(location.adjustability),
            fixedAngle = location.fixedAngle ?: 0,
            acceptsWellpass = location.wellpass == true,
            id = location.id,
            titleText = boardTitle(location),
            isMoonBoard = brand == BoardBrand.MOONBOARD,
            ledCode = when (location.hasLed) { true -> "led"; false -> "noLed"; null -> "" },
            moonAngle = if (brand == BoardBrand.MOONBOARD) (location.fixedAngle ?: info?.angle ?: 0) else 0,
            sizeText = if (brand == BoardBrand.MOONBOARD) "" else size,
            angleCode = when (fact) {
                is AngleFact.Range -> "range"
                is AngleFact.SetTo -> "set"
                AngleFact.Adjustable -> "adjustable"
                is AngleFact.FixedAt -> "fixedAt"
                AngleFact.Fixed -> "fixed"
                null -> ""
            },
            angleMin = (fact as? AngleFact.Range)?.min ?: 0,
            angleMax = (fact as? AngleFact.Range)?.max ?: 0,
            angleStep = (fact as? AngleFact.Range)?.step ?: 0,
            angleValue = when (fact) {
                is AngleFact.SetTo -> fact.angle
                is AngleFact.FixedAt -> fact.angle
                else -> 0
            },
            wallName = info?.wallName?.takeIf { it.isNotBlank() && !it.equals(location.layoutName, ignoreCase = true) } ?: "",
            frameMaker = location.frameMaker?.takeIf(String::isNotBlank) ?: "",
            models = info?.models.orEmpty(),
            actionCode = action,
            browseBoard = if (action == "browse" && layoutId != null) BoardOption(
                brand.wireValue, UiCodes.brandTitle(brand), layoutId, location.productSizeId ?: 0,
                location.layoutName ?: UiCodes.brandTitle(brand), size,
            ) else null,
        )
    }

    /** Android `boardTitle`: MoonBoard → variant; Kilter → "Kilter Board · <layout>"; others → brand. */
    private fun boardTitle(board: BoardLocation): String = when (board.boardBrand) {
        BoardBrand.MOONBOARD ->
            board.layoutId?.toLong()?.let { MoonBoardVariant.fromLayoutId(it)?.displayName } ?: board.layoutName ?: ""
        BoardBrand.KILTER -> {
            val name = board.layoutName ?: when (board.layoutId) {
                1 -> "Original"
                8 -> "Homewall"
                else -> null
            }
            if (name == null) BoardBrand.KILTER.displayName else "${BoardBrand.KILTER.displayName} · $name"
        }
        else -> board.boardBrand.displayName
    }

    /** Android zoom → MapKit span: ~1.6 tiles across a phone at 256 px per tile. */
    private fun camera(view: MapStartView): MapCameraUi = when (view) {
        is MapStartView.Camera -> {
            val span = (360.0 * 1.6 / 2.0.pow(view.zoom)).coerceIn(0.005, INITIAL_SPAN_DEGREES)
            MapCameraUi(view.lat, view.lng, span, span)
        }
        is MapStartView.Region -> MapCameraUi(view.lat, view.lng, view.latSpan, view.lngSpan)
        is MapStartView.Bounds -> {
            val latSpan = ((view.north - view.south) * 1.2).coerceAtLeast(0.01)
            val lngSpan = ((view.east - view.west) * 1.2).coerceAtLeast(0.01)
            var centerLng = (view.west + view.east) / 2
            if (centerLng > 180) centerLng -= 360
            if (centerLng < -180) centerLng += 360
            MapCameraUi((view.south + view.north) / 2, centerLng, latSpan.coerceAtMost(170.0), lngSpan.coerceAtMost(359.0))
        }
    }

    private fun filters(filters: MapFilters) = MapFiltersUi(
        showOriginal = filters.showOriginal,
        showHomewalls = filters.showHomewalls,
        matchesMyBoard = filters.matchesMyBoard,
        wellpassOnly = filters.wellpassOnly,
        countries = filters.countries.sorted(),
        accessTypes = filters.accessTypes.map { MapCodes.access(it) }.sorted(),
        adjustabilities = filters.adjustabilities.map { MapCodes.adjustability(it) }.distinct().sorted(),
        sizeIds = filters.sizeIds.sorted(),
        brands = filters.brands.map { it.wireValue }.sorted(),
        moonLayoutIds = filters.moonLayoutIds.sorted(),
        moonLedStates = filters.moonLedStates.map { MapCodes.led(it) }.sorted(),
        isAtDefault = filters.isAtDefault,
    )

    private fun stats(stats: MapStats) = MapStatsUi(
        total = stats.total,
        originalCount = stats.originalCount,
        homewallCount = stats.homewallCount,
        publicCount = stats.publicCount,
        privateCount = stats.privateCount,
        membersCount = stats.membersCount,
        accessUnknownCount = stats.accessUnknownCount,
        adjustableCount = stats.adjustableCount,
        fixedCount = stats.fixedCount,
        adjUnknownCount = stats.adjUnknownCount,
        countryCount = stats.byCountry.size,
        byCountry = stats.byCountry.map { (code, count) -> MapCountUi(code, code, count, false) },
        bySize = stats.bySize.map { (label, count) -> MapCountUi(label, label, count, false) },
        byBrand = stats.byBrand.map { (brand, count) ->
            MapCountUi(brand.wireValue, brand.displayName, count, false)
        },
        // The layout label is left empty for "unknown layout"; Swift names it.
        byLayout = stats.byLayout.map { entry ->
            MapCountUi(
                code = entry.brand.wireValue,
                label = listOfNotNull(entry.brand.displayName, entry.layout).joinToString(" · "),
                count = entry.count,
                isSelected = false,
            )
        },
    )

    /** Brand chips count VENUES holding that family, as Android's sheet does. */
    private fun brandOptions(state: MapState): List<MapCountUi> {
        val available = state.unfilteredLocations.mapTo(linkedSetOf()) { it.boardBrand }
        return BoardBrand.entries.filter { it in available }.map { brand ->
            MapCountUi(
                code = brand.wireValue,
                label = brand.displayName,
                count = state.unfilteredVenues.count { brand in it.brands },
                isSelected = brand in state.filters.brands,
            )
        }
    }

    private fun moonVariantOptions(state: MapState): List<MapCountUi> =
        state.unfilteredLocations
            .filter { it.boardBrand == BoardBrand.MOONBOARD }
            .mapNotNull { location -> location.layoutId?.let { it to location.layoutName } }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (layoutId, label) ->
                MapCountUi(
                    code = layoutId.toString(),
                    label = label ?: layoutId.toString(),
                    count = state.unfilteredLocations.count {
                        it.boardBrand == BoardBrand.MOONBOARD && it.layoutId == layoutId
                    },
                    isSelected = layoutId in state.filters.moonLayoutIds,
                )
            }

    /** Only sizes whose label resolves to a product-size id can be filtered on. */
    private fun sizeOptions(state: MapState): List<MapCountUi> =
        state.unfilteredStats.bySize.mapNotNull { (label, count) ->
            val sizeId = state.unfilteredLocations.firstOrNull { it.sizeLabel == label }?.productSizeId
                ?: return@mapNotNull null
            MapCountUi(sizeId.toString(), label, count, sizeId in state.filters.sizeIds)
        }

    private fun countryOptions(state: MapState): List<MapCountUi> =
        state.unfilteredStats.byCountry.map { (code, count) ->
            MapCountUi(code, code, count, code in state.filters.countries)
        }

    companion object {
        /** Android's initial camera (lat 20, lng 0, zoom 1.0): the whole world. */
        const val INITIAL_LATITUDE = 20.0
        const val INITIAL_LONGITUDE = 0.0
        const val INITIAL_SPAN_DEGREES = 140.0

        private val MARKER_STYLES: List<MapMarkerStyleUi> = BoardBrand.entries.map {
            val style = MapMarkerStyles.of(it)
            MapMarkerStyleUi(it.wireValue, style.color, style.onColor, style.monogram)
        }
    }
}
