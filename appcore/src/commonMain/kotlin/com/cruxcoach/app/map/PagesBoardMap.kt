package com.cruxcoach.app.map

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One place of the bundled GeoNames index (`cities.json`): map search and the first view. */
data class MapPlace(
    val name: String,
    val countryCode: String,
    val lat: Double,
    val lng: Double,
    val region: String?,
    val aliases: List<String>,
    val germanName: String?,
)

/** The bundled cruxcoach-pages snapshot, already expanded into per-board rows. */
class PagesBoardMapSnapshot(
    val locations: List<BoardLocation>,
    val places: List<MapPlace> = emptyList(),
    /** Venue-level extras keyed by [venueKey]. */
    val venueInfo: Map<String, MapVenueInfo> = emptyMap(),
    /** Board-level extras keyed by `BoardLocation.id`. */
    val boardInfo: Map<String, MapBoardInfo> = emptyMap(),
)

/** Where the canonical offline snapshot comes from. Tests substitute their own. */
fun interface PagesBoardMapSource {
    /** Never throws: an unreadable or malformed snapshot yields an empty result. */
    fun load(): PagesBoardMapSnapshot
}

/**
 * Reads `board_map/boards.geojson` from the app bundle. [directory] is the
 * absolute path of the bundled `board_map` folder, which Swift resolves from
 * `Bundle.main.resourceURL`; Kotlin never guesses a bundle layout.
 */
class BundledPagesBoardMapSource(
    private val files: FileSystem,
    private val directory: String,
    /** The place index serves map search and the first view; the gym search needs none. */
    private val includePlaces: Boolean = true,
) : PagesBoardMapSource {

    override fun load(): PagesBoardMapSnapshot = try {
        val base = directory.trimEnd('/') + "/"
        val bytes = files.readBytes(base + BOARDS_FILE, MAX_SNAPSHOT_BYTES)
        if (bytes == null) {
            PagesBoardMapSnapshot(emptyList())
        } else {
            val boards = PagesBoardMapParser.parse(bytes.decodeToString())
            // The place index only serves a map that has boards; a broken snapshot reads nothing more.
            val cities = if (includePlaces && boards.locations.isNotEmpty()) {
                files.readBytes(base + CITIES_FILE, MAX_SNAPSHOT_BYTES)
            } else {
                null
            }
            if (cities == null) boards else PagesBoardMapSnapshot(
                boards.locations, PagesBoardMapParser.places(cities.decodeToString()), boards.venueInfo, boards.boardInfo,
            )
        }
    } catch (e: Exception) {
        // The map still works from the synced BoardDB rows alone.
        PagesBoardMapSnapshot(emptyList())
    }

    companion object {
        const val BOARDS_FILE = "boards.geojson"
        const val CITIES_FILE = "cities.json"

        /** The bundled snapshot is ~1.5 MB; this only stops a corrupt build from exhausting memory. */
        const val MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024
    }
}

/**
 * Port of Android `PagesBoardMapParser`: expands the cruxcoach-pages venue
 * GeoJSON into one [BoardLocation] per installed board, with the same ids,
 * layout-id mapping and access/adjustability derivation, plus the 0.2.4
 * map-only extras (hours, wall angles, models) and the `cities.json` place index.
 */
object PagesBoardMapParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(boardsJson: String, citiesJson: String? = null): PagesBoardMapSnapshot {
        val root = json.parseToJsonElement(boardsJson) as? JsonObject
            ?: return PagesBoardMapSnapshot(emptyList())
        val venueInfo = HashMap<String, MapVenueInfo>()
        val boardInfo = HashMap<String, MapBoardInfo>()
        val locations = root["features"].asArray().flatMap { parseFeature(it, venueInfo, boardInfo) }
        return PagesBoardMapSnapshot(locations, citiesJson?.let(::places).orEmpty(), venueInfo, boardInfo)
    }

    /** The `cities.json` place index; malformed rows are skipped. */
    fun places(citiesJson: String): List<MapPlace> =
        (json.parseToJsonElement(citiesJson) as? JsonObject)?.get("cities").asArray().mapNotNull(::parsePlace)

    private fun parseFeature(
        element: JsonElement,
        venueInfo: MutableMap<String, MapVenueInfo>,
        boardInfo: MutableMap<String, MapBoardInfo>,
    ): List<BoardLocation> {
        val feature = element as? JsonObject ?: return emptyList()
        val coordinates = feature["geometry"].asObject()["coordinates"].asArray()
        val lng = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val props = feature["properties"].asObject()
        // A few upstream names arrive wrapped in literal quotes ("\"Gym\"").
        val name = props.string("name")?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        val country = props.string("country")?.uppercase()?.takeIf { it.length == 2 } ?: "??"
        val city = props.string("city") ?: props.string("city_nearest")
        val website = props.string("website")
        val wellpass = props["wellpass"]?.jsonPrimitive?.booleanOrNull
        val aliases = listOfNotNull(
            props.string("city"),
            props.string("city_nearest"),
            props.string("city_nearest_de"),
        ).distinct()
        val key = venueKey(lat, lng)
        val info = MapVenueInfo(
            hours = VenueHours.parse(props.stringList("hours")),
            hoursSource = props.string("hours_src"),
            cityIsNearest = props.string("city") == null && props.string("city_nearest") != null,
            nearestCityDe = props.string("city_nearest_de").takeIf { props.string("city") == null },
        )
        if (info != MapVenueInfo()) venueInfo[key] = info

        return props["boards"].asArray().flatMapIndexed { boardIndex, boardElement ->
            val board = boardElement as? JsonObject ?: return@flatMapIndexed emptyList()
            val brand = BoardBrand.fromWireOrNull(board.string("board"))
                ?: return@flatMapIndexed emptyList()
            val common = CommonVenue(
                key = key,
                boardIndex = boardIndex,
                name = name,
                lat = lat,
                lng = lng,
                city = city,
                country = country,
                website = website,
                wellpass = wellpass,
                aliases = aliases,
                brand = brand,
                address = board.string("address"),
                instagram = board.string("instagram"),
            )
            when (brand) {
                BoardBrand.KILTER -> parseKilter(common, board, boardInfo)
                BoardBrand.MOONBOARD -> listOf(parseMoonBoard(common, board, boardInfo))
                else -> listOf(common.location()).onEach { location ->
                    val models = board["models"].asArray()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                    if (models.isNotEmpty()) boardInfo[location.id] = MapBoardInfo(models = models)
                }
            }
        }
    }

    private fun parseKilter(
        common: CommonVenue,
        board: JsonObject,
        boardInfo: MutableMap<String, MapBoardInfo>,
    ): List<BoardLocation> {
        val walls = board["walls"].asArray()
        if (walls.isEmpty()) return listOf(common.location())
        return walls.mapIndexedNotNull { index, element ->
            val wall = element as? JsonObject ?: return@mapIndexedNotNull null
            val adjustable = wall["adjustable"]?.jsonPrimitive?.booleanOrNull
            common.location(
                suffix = index.toString(),
                layoutName = wall.string("layout"),
                layoutId = when (wall.string("layout")?.lowercase()) {
                    "original" -> KILTER_ORIGINAL_LAYOUT
                    "homewall" -> KILTER_HOMEWALL_LAYOUT
                    else -> null
                },
                sizeLabel = wall.string("size_label"),
                productSizeId = wall.int("size_id"),
                adjustability = when (adjustable) {
                    true -> Adjustability.ADJUSTABLE
                    false -> Adjustability.FIXED
                    null -> Adjustability.UNKNOWN
                },
                fixedAngle = if (adjustable == false) wall.int("angle") else null,
                extraTerms = listOfNotNull(wall.string("wall_name"), wall.string("layout")),
            ).also { location ->
                val extra = MapBoardInfo(
                    wallName = wall.string("wall_name"),
                    minAngle = wall.int("min_angle"),
                    maxAngle = wall.int("max_angle"),
                    angleStep = wall.int("angle_increments"),
                    angle = wall.int("angle"),
                )
                if (extra != MapBoardInfo()) boardInfo[location.id] = extra
            }
        }
    }

    private fun parseMoonBoard(
        common: CommonVenue,
        board: JsonObject,
        boardInfo: MutableMap<String, MapBoardInfo>,
    ): BoardLocation {
        val variant = board.string("variant")
        return common.location(
            layoutName = moonVariantLabel(variant),
            layoutId = when (variant) {
                "mb2016" -> 2
                "mb2024" -> 3
                "mb2017-masters" -> 4
                "mb2019-masters" -> 5
                "mini-2020" -> 6
                else -> null
            },
            accessType = when (board["commercial"]?.jsonPrimitive?.booleanOrNull) {
                true -> AccessType.PUBLIC
                false -> AccessType.PRIVATE
                null -> AccessType.UNKNOWN
            },
            adjustability = when (variant) {
                "mb2016", "mb2024", "mini-2020" -> Adjustability.FIXED
                "mb2017-masters", "mb2019-masters" -> Adjustability.ADJUSTABLE
                else -> Adjustability.UNKNOWN
            },
            fixedAngle = board.int("angle"),
            hasLed = board["led"]?.jsonPrimitive?.booleanOrNull,
            extraTerms = listOfNotNull(variant, moonVariantLabel(variant), board.string("username")),
        ).also { location ->
            board.int("angle")?.let { boardInfo[location.id] = MapBoardInfo(angle = it) }
        }
    }

    private fun parsePlace(element: JsonElement): MapPlace? {
        val row = element as? JsonArray ?: return null
        if (row.size < 4) return null
        val name = (row[0] as? JsonPrimitive)?.contentOrNull ?: return null
        val country = (row[1] as? JsonPrimitive)?.contentOrNull ?: return null
        val lat = (row[2] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (row[3] as? JsonPrimitive)?.doubleOrNull ?: return null
        return MapPlace(
            name = name,
            countryCode = country,
            lat = lat,
            lng = lng,
            region = (row.getOrNull(4) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
            aliases = row.getOrNull(5).asArray().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            germanName = (row.getOrNull(6) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        )
    }

    private fun moonVariantLabel(variant: String?): String? = when (variant) {
        "mb2016" -> "MoonBoard 2016"
        "mb2024" -> "MoonBoard 2024"
        "mb2017-masters" -> "MoonBoard Masters 2017"
        "mb2019-masters" -> "MoonBoard Masters 2019"
        "mini-2020" -> "Mini MoonBoard 2020"
        "school-room" -> "School Room"
        else -> null
    }

    private data class CommonVenue(
        val key: String,
        val boardIndex: Int,
        val name: String,
        val lat: Double,
        val lng: Double,
        val city: String?,
        val country: String,
        val website: String?,
        val wellpass: Boolean?,
        val aliases: List<String>,
        val brand: BoardBrand,
        val address: String?,
        val instagram: String?,
    ) {
        fun location(
            suffix: String = "0",
            layoutName: String? = null,
            layoutId: Int? = null,
            sizeLabel: String? = null,
            productSizeId: Int? = null,
            accessType: AccessType = AccessType.UNKNOWN,
            adjustability: Adjustability = Adjustability.UNKNOWN,
            fixedAngle: Int? = null,
            hasLed: Boolean? = null,
            extraTerms: List<String> = emptyList(),
        ) = BoardLocation(
            id = "pages-$key-${brand.wireValue}-$boardIndex-$suffix",
            name = name,
            lat = lat,
            lng = lng,
            address = address,
            city = city,
            countryCode = country,
            phone = null,
            email = null,
            url = website,
            instagram = instagram,
            layoutName = layoutName,
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            productSizeId = productSizeId,
            accessType = accessType,
            adjustability = adjustability,
            fixedAngle = fixedAngle,
            frameMaker = null,
            boardBrand = brand,
            wellpass = wellpass,
            hasLed = hasLed,
            alternateSearchTerms = (aliases + extraTerms).filter(String::isNotBlank).distinct(),
        )
    }

    private fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** The array's strings, or null as soon as one entry is not a string. */
    private fun JsonObject.stringList(key: String): List<String>? =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
}
