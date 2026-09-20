package com.cruxcoach.app.map

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand

/**
 * Map filters persisted on Android's DataStore key names and value spellings
 * (CSV sets, "true"/"false" booleans), so the two apps read the same
 * preferences. Unparseable members are dropped rather than throwing — a corrupt
 * preference must not break the map.
 */
internal class MapFilterStore(private val store: KeyValueStore) {

    fun read(): MapFilters = MapFilters(
        showOriginal = boolean(SHOW_ORIGINAL, true),
        showHomewalls = boolean(SHOW_HOMEWALLS, true),
        matchesMyBoard = boolean(MATCHES_MY_BOARD, false),
        countries = csv(COUNTRIES),
        accessTypes = csv(ACCESS_TYPES).mapNotNullTo(mutableSetOf()) { code ->
            AccessType.entries.firstOrNull { it.name == code }
        },
        adjustabilities = csv(ADJUSTABILITIES).mapNotNullTo(mutableSetOf()) { code ->
            Adjustability.entries.firstOrNull { it.name == code }
        },
        sizeIds = csv(SIZE_IDS).mapNotNullTo(mutableSetOf()) { it.toIntOrNull() },
        // Android's lenient `BoardBrand.fromWire` would silently turn an
        // unknown brand into KILTER and filter the map to Kilter only.
        brands = csv(BRANDS).mapNotNullTo(mutableSetOf()) { BoardBrand.fromWireOrNull(it) },
        wellpassOnly = boolean(WELLPASS_ONLY, false),
        moonLayoutIds = csv(MOON_LAYOUT_IDS).mapNotNullTo(mutableSetOf()) { it.toIntOrNull() },
        moonLedStates = csv(MOON_LED_STATES).mapNotNullTo(mutableSetOf()) { code ->
            MoonLedState.entries.firstOrNull { it.name == code }
        },
    )

    fun write(filters: MapFilters) {
        put(SHOW_ORIGINAL, filters.showOriginal.toString())
        put(SHOW_HOMEWALLS, filters.showHomewalls.toString())
        put(MATCHES_MY_BOARD, filters.matchesMyBoard.toString())
        put(WELLPASS_ONLY, filters.wellpassOnly.toString())
        put(COUNTRIES, filters.countries.joinToString(","))
        put(ACCESS_TYPES, filters.accessTypes.joinToString(",") { it.name })
        put(ADJUSTABILITIES, filters.adjustabilities.joinToString(",") { it.name })
        put(SIZE_IDS, filters.sizeIds.joinToString(","))
        put(BRANDS, filters.brands.joinToString(",") { it.wireValue })
        put(MOON_LAYOUT_IDS, filters.moonLayoutIds.joinToString(","))
        put(MOON_LED_STATES, filters.moonLedStates.joinToString(",") { it.name })
    }

    fun reset() {
        for (key in ALL_KEYS) put(key, null)
    }

    private fun put(key: String, value: String?) {
        try {
            store.putString(key, value)
        } catch (e: Exception) {
            // A preference that cannot be written must not lose the in-memory filter.
        }
    }

    private fun boolean(key: String, fallback: Boolean): Boolean =
        when (store.getString(key)) {
            "true" -> true
            "false" -> false
            else -> fallback
        }

    private fun csv(key: String): Set<String> =
        store.getString(key)?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.toSet()
            ?: emptySet()

    companion object {
        const val SHOW_ORIGINAL = "map_filter_show_original"
        const val SHOW_HOMEWALLS = "map_filter_show_homewalls"
        const val MATCHES_MY_BOARD = "map_filter_matches_my_board"
        const val COUNTRIES = "map_filter_countries"
        const val ACCESS_TYPES = "map_filter_access_types"
        const val ADJUSTABILITIES = "map_filter_adjustabilities"
        const val SIZE_IDS = "map_filter_size_ids"
        const val BRANDS = "map_filter_brands"
        const val MOON_LAYOUT_IDS = "map_filter_moon_layout_ids"
        const val MOON_LED_STATES = "map_filter_moon_led_states"
        const val WELLPASS_ONLY = "map_filter_wellpass_only"

        private val ALL_KEYS = listOf(
            SHOW_ORIGINAL, SHOW_HOMEWALLS, MATCHES_MY_BOARD, COUNTRIES, ACCESS_TYPES,
            ADJUSTABILITIES, SIZE_IDS, BRANDS, MOON_LAYOUT_IDS, MOON_LED_STATES, WELLPASS_ONLY,
        )
    }
}
