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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The bundled cruxcoach-pages snapshot, already expanded into per-board rows. */
class PagesBoardMapSnapshot(val locations: List<BoardLocation>)

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
) : PagesBoardMapSource {

    override fun load(): PagesBoardMapSnapshot = try {
        val path = directory.trimEnd('/') + "/" + BOARDS_FILE
        val bytes = files.readBytes(path, MAX_SNAPSHOT_BYTES)
        if (bytes == null) {
            PagesBoardMapSnapshot(emptyList())
        } else {
            PagesBoardMapParser.parse(bytes.decodeToString())
        }
    } catch (e: Exception) {
        // The map still works from the synced BoardDB rows alone.
        PagesBoardMapSnapshot(emptyList())
    }

    companion object {
        const val BOARDS_FILE = "boards.geojson"

        /** The bundled snapshot is ~1.5 MB; this only stops a corrupt build from exhausting memory. */
        const val MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024
    }
}

/**
 * Port of Android `PagesBoardMapParser`: expands the cruxcoach-pages venue
 * GeoJSON into one [BoardLocation] per installed board, with the same ids,
 * layout-id mapping and access/adjustability derivation.
 *
 * `cities.json` (the offline place-search index) is intentionally not parsed
 * here: map search is not part of this port.
 */
object PagesBoardMapParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(boardsJson: String): PagesBoardMapSnapshot {
        val root = json.parseToJsonElement(boardsJson) as? JsonObject
            ?: return PagesBoardMapSnapshot(emptyList())
        return PagesBoardMapSnapshot(root["features"].asArray().flatMap(::parseFeature))
    }

    private fun parseFeature(element: JsonElement): List<BoardLocation> {
        val feature = element as? JsonObject ?: return emptyList()
        val coordinates = feature["geometry"].asObject()["coordinates"].asArray()
        val lng = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val props = feature["properties"].asObject()
        val name = props.string("name")?.trim().orEmpty()
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
                BoardBrand.KILTER -> parseKilter(common, board)
                BoardBrand.MOONBOARD -> listOf(parseMoonBoard(common, board))
                else -> listOf(common.location())
            }
        }
    }

    private fun parseKilter(common: CommonVenue, board: JsonObject): List<BoardLocation> {
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
            )
        }
    }

    private fun parseMoonBoard(common: CommonVenue, board: JsonObject): BoardLocation {
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
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}
