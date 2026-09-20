package com.cruxcoach.app.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapFiltersTest {

    private val kilterOriginal = location("k1", layoutId = KILTER_ORIGINAL_LAYOUT, productSizeId = 10,
        sizeLabel = "12x12", adjustability = Adjustability.ADJUSTABLE)
    private val kilterHomewall = location("k2", layoutId = KILTER_HOMEWALL_LAYOUT, productSizeId = 21,
        sizeLabel = "10x10", adjustability = Adjustability.FIXED, fixedAngle = 40)
    private val kilterUnknownLayout = location("k3", layoutId = null)
    private val moon = location("m1", brand = BoardBrand.MOONBOARD, layoutId = 2,
        access = AccessType.PUBLIC, adjustability = Adjustability.FIXED, hasLed = true)
    private val moonMasters = location("m2", brand = BoardBrand.MOONBOARD, layoutId = 4,
        access = AccessType.PRIVATE, adjustability = Adjustability.ADJUSTABLE, hasLed = null)
    private val tension = location("t1", brand = BoardBrand.TENSION, layoutId = 1)
    private val all = listOf(kilterOriginal, kilterHomewall, kilterUnknownLayout, moon, moonMasters, tension)

    private fun ids(filters: MapFilters, layout: Int? = null, size: Int? = null, brand: BoardBrand? = null) =
        filters.apply(all, layout, size, brand).map { it.id }

    @Test
    fun `default filters keep every row`() {
        assertTrue(MapFilters().isAtDefault)
        assertEquals(all.map { it.id }, ids(MapFilters()))
    }

    @Test
    fun `brand filter is a wildcard when empty and a gate when set`() {
        assertEquals(listOf("m1", "m2"), ids(MapFilters(brands = setOf(BoardBrand.MOONBOARD))))
        assertEquals(listOf("t1"), ids(MapFilters(brands = setOf(BoardBrand.TENSION))))
    }

    @Test
    fun `wellpass filter excludes unknown and explicit no`() {
        val rows = listOf(
            location("yes", wellpass = true),
            location("no", wellpass = false),
            location("unknown", wellpass = null),
        )
        assertEquals(listOf("yes"), MapFilters(wellpassOnly = true).apply(rows).map { it.id })
    }

    @Test
    fun `original and homewall toggles gate only Kilter rows`() {
        // Homewall off: the Kilter homewall goes, every other family stays.
        assertEquals(
            listOf("k1", "k3", "m1", "m2", "t1"),
            ids(MapFilters(showHomewalls = false)),
        )
        assertEquals(
            listOf("k2", "k3", "m1", "m2", "t1"),
            ids(MapFilters(showOriginal = false)),
        )
        // Both off: only the Kilter row of unknown layout is dropped as well.
        assertEquals(listOf("m1", "m2", "t1"), ids(MapFilters(showOriginal = false, showHomewalls = false)))
    }

    @Test
    fun `size and adjustability filters do not remove non-Kilter rows`() {
        assertEquals(listOf("k1", "m1", "m2", "t1"), ids(MapFilters(sizeIds = setOf(10))))
        assertEquals(
            listOf("k1", "m1", "m2", "t1"),
            ids(MapFilters(adjustabilities = setOf(Adjustability.ADJUSTABLE))),
        )
    }

    @Test
    fun `a Kilter row without a size id fails an active size filter`() {
        val rows = listOf(location("no-size", layoutId = KILTER_ORIGINAL_LAYOUT, productSizeId = null))
        assertTrue(MapFilters(sizeIds = setOf(10)).apply(rows).isEmpty())
    }

    @Test
    fun `MoonBoard gates apply to MoonBoard rows only`() {
        assertEquals(listOf("k1", "k2", "k3", "m1", "t1"), ids(MapFilters(accessTypes = setOf(AccessType.PUBLIC))))
        assertEquals(listOf("k1", "k2", "k3", "m2", "t1"), ids(MapFilters(moonLayoutIds = setOf(4))))
        assertEquals(
            listOf("k1", "k2", "k3", "m1", "t1"),
            ids(MapFilters(moonLedStates = setOf(MoonLedState.LED))),
        )
        assertEquals(
            listOf("k1", "k2", "k3", "m2", "t1"),
            ids(MapFilters(moonLedStates = setOf(MoonLedState.UNKNOWN))),
        )
    }

    @Test
    fun `matches my board is brand scoped so colliding layout ids do not match`() {
        // Layout 1 exists on both Kilter and Tension; only the user's family matches.
        assertEquals(
            listOf("k1"),
            ids(MapFilters(matchesMyBoard = true), layout = 1, size = 10, brand = BoardBrand.KILTER),
        )
        assertEquals(
            listOf("t1"),
            ids(MapFilters(matchesMyBoard = true), layout = 1, size = 10, brand = BoardBrand.TENSION),
        )
        // Legacy callers without a brand keep the unscoped behaviour.
        assertEquals(
            listOf("k1", "t1"),
            ids(MapFilters(matchesMyBoard = true), layout = 1, size = 10, brand = null),
        )
    }

    @Test
    fun `matches my board keeps rows whose size is unknown`() {
        val rows = listOf(
            location("sized", layoutId = 1, productSizeId = 10),
            location("unsized", layoutId = 1, productSizeId = null),
            location("other-size", layoutId = 1, productSizeId = 21),
        )
        assertEquals(
            listOf("sized", "unsized"),
            MapFilters(matchesMyBoard = true).apply(rows, 1, 10, BoardBrand.KILTER).map { it.id },
        )
    }

    @Test
    fun `country filter is applied to every family`() {
        val rows = listOf(location("de", country = "DE"), location("us", country = "US"))
        assertEquals(listOf("us"), MapFilters(countries = setOf("US")).apply(rows).map { it.id })
    }

    @Test
    fun `isAtDefault turns false for every dimension`() {
        assertFalse(MapFilters(showOriginal = false).isAtDefault)
        assertFalse(MapFilters(showHomewalls = false).isAtDefault)
        assertFalse(MapFilters(matchesMyBoard = true).isAtDefault)
        assertFalse(MapFilters(countries = setOf("DE")).isAtDefault)
        assertFalse(MapFilters(accessTypes = setOf(AccessType.PUBLIC)).isAtDefault)
        assertFalse(MapFilters(adjustabilities = setOf(Adjustability.FIXED)).isAtDefault)
        assertFalse(MapFilters(sizeIds = setOf(10)).isAtDefault)
        assertFalse(MapFilters(brands = setOf(BoardBrand.KILTER)).isAtDefault)
        assertFalse(MapFilters(wellpassOnly = true).isAtDefault)
        assertFalse(MapFilters(moonLayoutIds = setOf(2)).isAtDefault)
        assertFalse(MapFilters(moonLedStates = setOf(MoonLedState.LED)).isAtDefault)
    }

    @Test
    fun `led state mapping covers the tri-state`() {
        assertEquals(MoonLedState.LED, MoonLedState.from(true))
        assertEquals(MoonLedState.NO_LED, MoonLedState.from(false))
        assertEquals(MoonLedState.UNKNOWN, MoonLedState.from(null))
    }
}
