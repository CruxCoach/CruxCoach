package com.cruxcoach.app.ui

import com.cruxcoach.app.map.MapError
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
)

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
class MapScreenModel(private val presenter: MapPresenter) {

    val currentState: MapScreenState get() = map(presenter.state.value)

    fun watch(onState: (MapScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()

    /** "" clears the selection. */
    fun selectVenue(id: String) = presenter.selectVenue(id.ifEmpty { null })

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
        selectedVenue = state.filteredVenues.firstOrNull { it.id == state.selectedVenueId }?.let { venue(it) },
        initialLatitude = INITIAL_LATITUDE,
        initialLongitude = INITIAL_LONGITUDE,
        initialSpanDegrees = INITIAL_SPAN_DEGREES,
    )

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
    )

    private fun venue(venue: MapVenue) = MapVenueUi(
        id = venue.id,
        name = venue.name,
        city = venue.city ?: "",
        countryCode = venue.countryCode.takeIf { it != "??" } ?: "",
        latitude = venue.lat,
        longitude = venue.lng,
        address = venue.boards.firstNotNullOfOrNull { it.address?.takeIf(String::isNotBlank) } ?: "",
        url = venue.boards.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) } ?: "",
        boardCount = venue.boards.size,
        boards = venue.boards.map { board(it) },
    )

    private fun board(location: BoardLocation) = MapBoardUi(
        brandWire = location.boardBrand.wireValue,
        brandTitle = location.boardBrand.displayName,
        layoutLabel = location.layoutName?.takeIf(String::isNotBlank) ?: "",
        sizeLabel = location.sizeLabel?.takeIf(String::isNotBlank) ?: "",
        accessCode = MapCodes.access(location.accessType),
        adjustabilityCode = MapCodes.adjustability(location.adjustability),
        fixedAngle = location.fixedAngle ?: 0,
        acceptsWellpass = location.wellpass == true,
    )

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
    }
}
