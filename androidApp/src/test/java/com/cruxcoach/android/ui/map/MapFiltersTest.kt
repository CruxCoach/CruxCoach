package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFiltersTest {

    private fun loc(
        id: String,
        layoutId: Int? = 1,
        productSizeId: Int? = 10,
        countryCode: String = "DE",
        accessType: AccessType = AccessType.PUBLIC,
        adjustability: Adjustability = Adjustability.ADJUSTABLE,
        sizeLabel: String? = "12x12",
        boardBrand: BoardBrand = BoardBrand.KILTER,
        wellpass: Boolean? = null,
    ) = BoardLocation(
        id = id,
        name = "Gym $id",
        lat = 0.0, lng = 0.0,
        address = null, city = null, countryCode = countryCode,
        phone = null, email = null, url = null, instagram = null,
        layoutName = null, layoutId = layoutId,
        sizeLabel = sizeLabel, productSizeId = productSizeId,
        accessType = accessType,
        adjustability = adjustability,
        fixedAngle = null, frameMaker = null,
        boardBrand = boardBrand,
        wellpass = wellpass,
    )

    @Test
    fun `empty input returns empty output`() {
        val out = MapFilters().apply(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `default filters show Original and Homewalls like the web map`() {
        val items = listOf(loc("a", layoutId = 1), loc("b", layoutId = 8))
        val out = MapFilters().apply(items)
        assertEquals(setOf("a", "b"), out.map { it.id }.toSet())
    }

    @Test
    fun `showHomewalls toggle includes layoutId=8`() {
        val items = listOf(loc("a", layoutId = 1), loc("b", layoutId = 8))
        val out = MapFilters(showHomewalls = true).apply(items)
        assertEquals(setOf("a", "b"), out.map { it.id }.toSet())
    }

    @Test
    fun `null layoutId passes when at least one layout gate enabled`() {
        val items = listOf(loc("a", layoutId = null))
        val out = MapFilters().apply(items)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `null layoutId dropped when both layout gates disabled`() {
        val items = listOf(loc("a", layoutId = null))
        val out = MapFilters(showOriginal = false, showHomewalls = false).apply(items)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `matchesMyBoard with null user layout is a no-op`() {
        val items = listOf(loc("a", layoutId = 1), loc("b", layoutId = 1))
        val out = MapFilters(matchesMyBoard = true).apply(items, userBoardLayoutId = null)
        assertEquals(2, out.size)
    }

    @Test
    fun `matchesMyBoard requires layout match`() {
        val items = listOf(loc("a", layoutId = 1), loc("b", layoutId = 8))
        val out = MapFilters(matchesMyBoard = true, showHomewalls = true)
            .apply(items, userBoardLayoutId = 8)
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun `matchesMyBoard with NULL productSizeId is wildcard`() {
        // FEAT-015 spec: gym row with NULL product_size_id still surfaces
        // as a layout-match — UI flags it as "layout match, size unknown".
        val items = listOf(
            loc("known", layoutId = 1, productSizeId = 10),
            loc("wildcard", layoutId = 1, productSizeId = null),
            loc("wrongSize", layoutId = 1, productSizeId = 99),
        )
        val out = MapFilters(matchesMyBoard = true)
            .apply(items, userBoardLayoutId = 1, userBoardSizeId = 10)
        assertEquals(setOf("known", "wildcard"), out.map { it.id }.toSet())
    }

    @Test
    fun `matchesMyBoard is brand-scoped - Aurora location at a colliding Kilter layout id is excluded`() {
        // FEAT-031 fix #5: Aurora layout ids overlap Kilter's (Tension also uses
        // layout 1), so without the brand gate a Kilter user on layout 1 would
        // wrongly match a Tension venue sharing that id.
        val items = listOf(
            loc("kilter1", layoutId = 1, boardBrand = BoardBrand.KILTER),
            loc("tension1", layoutId = 1, boardBrand = BoardBrand.TENSION),
        )
        val out = MapFilters(matchesMyBoard = true).apply(
            items, userBoardLayoutId = 1, userBoardBrand = BoardBrand.KILTER,
        )
        assertEquals(listOf("kilter1"), out.map { it.id })
    }

    @Test
    fun `matchesMyBoard with null userBoardBrand keeps legacy layout-only behavior`() {
        // Back-compat: a caller that supplies no brand skips the brand gate, so
        // both the Kilter and the colliding-layout Tension venue still match.
        val items = listOf(
            loc("kilter1", layoutId = 1, boardBrand = BoardBrand.KILTER),
            loc("tension1", layoutId = 1, boardBrand = BoardBrand.TENSION),
        )
        val out = MapFilters(matchesMyBoard = true).apply(items, userBoardLayoutId = 1)
        assertEquals(setOf("kilter1", "tension1"), out.map { it.id }.toSet())
    }

    @Test
    fun `country filter set excludes non-members`() {
        val items = listOf(loc("a", countryCode = "DE"), loc("b", countryCode = "FR"))
        val out = MapFilters(countries = setOf("DE")).apply(items)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `empty country set is wildcard`() {
        val items = listOf(loc("a", countryCode = "DE"), loc("b", countryCode = "FR"))
        val out = MapFilters(countries = emptySet()).apply(items)
        assertEquals(2, out.size)
    }

    @Test
    fun `sizeIds set drops locations whose productSizeId is null`() {
        val items = listOf(
            loc("a", productSizeId = 10),
            loc("b", productSizeId = null),
        )
        val out = MapFilters(sizeIds = setOf(10)).apply(items)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `Kilter wall and MoonBoard setup filters stay board-specific`() {
        val items = listOf(
            loc("kilter-adjustable", accessType = AccessType.UNKNOWN, adjustability = Adjustability.ADJUSTABLE),
            loc("kilter-fixed", accessType = AccessType.UNKNOWN, adjustability = Adjustability.FIXED),
            loc("moon-commercial", accessType = AccessType.PUBLIC, adjustability = Adjustability.UNKNOWN, boardBrand = BoardBrand.MOONBOARD),
            loc("moon-home", accessType = AccessType.PRIVATE, adjustability = Adjustability.UNKNOWN, boardBrand = BoardBrand.MOONBOARD),
        )
        val out = MapFilters(
            accessTypes = setOf(AccessType.PUBLIC),
            adjustabilities = setOf(Adjustability.ADJUSTABLE),
        ).apply(items)
        assertEquals(setOf("kilter-adjustable", "moon-commercial"), out.map { it.id }.toSet())
    }

    @Test
    fun `MoonBoard gym passes layout gate regardless of Original Homewall toggles`() {
        // The Original/Homewall toggles are a Kilter concept; a MoonBoard gym
        // (layout 5 = Masters 2019) must not be hidden by them — only the
        // brand filter governs it. Both layout toggles off → MoonBoard stays.
        val items = listOf(
            loc("kilter", layoutId = 1, boardBrand = BoardBrand.KILTER),
            loc("moon", layoutId = 5, boardBrand = BoardBrand.MOONBOARD),
        )
        val out = MapFilters(showOriginal = false, showHomewalls = false).apply(items)
        assertEquals(listOf("moon"), out.map { it.id })
    }

    @Test
    fun `brand filter excludes other brands`() {
        val items = listOf(
            loc("kilter", layoutId = 1, boardBrand = BoardBrand.KILTER),
            loc("moon", layoutId = 5, boardBrand = BoardBrand.MOONBOARD),
        )
        assertEquals(
            listOf("moon"),
            MapFilters(brands = setOf(BoardBrand.MOONBOARD)).apply(items).map { it.id },
        )
        assertEquals(
            listOf("kilter"),
            MapFilters(brands = setOf(BoardBrand.KILTER)).apply(items).map { it.id },
        )
    }

    @Test
    fun `empty brand set is wildcard across brands`() {
        val items = listOf(
            loc("kilter", layoutId = 1, boardBrand = BoardBrand.KILTER),
            loc("moon", layoutId = 5, boardBrand = BoardBrand.MOONBOARD),
        )
        assertEquals(2, MapFilters().apply(items).size)
    }

    @Test
    fun `matchesMyBoard matches a MoonBoard variant by layout`() {
        val items = listOf(
            loc("moon2019", layoutId = 5, productSizeId = null, boardBrand = BoardBrand.MOONBOARD),
            loc("moon2017", layoutId = 4, productSizeId = null, boardBrand = BoardBrand.MOONBOARD),
        )
        val out = MapFilters(matchesMyBoard = true).apply(items, userBoardLayoutId = 5)
        assertEquals(listOf("moon2019"), out.map { it.id })
    }

    @Test
    fun `wellpassOnly keeps only wellpass-true locations`() {
        val items = listOf(
            loc("yes", wellpass = true),
            loc("no", wellpass = false),
            loc("unknown", wellpass = null),
        )
        val out = MapFilters(wellpassOnly = true).apply(items)
        assertEquals(listOf("yes"), out.map { it.id })
    }

    @Test
    fun `wellpassOnly off is a wildcard across wellpass states`() {
        val items = listOf(
            loc("yes", wellpass = true),
            loc("no", wellpass = false),
            loc("unknown", wellpass = null),
        )
        assertEquals(3, MapFilters().apply(items).size)
    }

    @Test
    fun `isAtDefault is true for fresh instance`() {
        assertTrue(MapFilters().isAtDefault)
    }

    @Test
    fun `isAtDefault is false when any filter applied`() {
        assertEquals(false, MapFilters(matchesMyBoard = true).isAtDefault)
        assertEquals(false, MapFilters(countries = setOf("DE")).isAtDefault)
        assertEquals(false, MapFilters(showHomewalls = false).isAtDefault)
        assertEquals(false, MapFilters(brands = setOf(BoardBrand.MOONBOARD)).isAtDefault)
    }

    @Test
    fun `Kilter size and adjustability filters do not hide other board families`() {
        val items = listOf(
            loc("kilter-match", productSizeId = 10, adjustability = Adjustability.ADJUSTABLE),
            loc("kilter-miss", productSizeId = 12, adjustability = Adjustability.FIXED),
            loc("moon", productSizeId = null, adjustability = Adjustability.UNKNOWN, boardBrand = BoardBrand.MOONBOARD),
        )
        val out = MapFilters(
            sizeIds = setOf(10),
            adjustabilities = setOf(Adjustability.ADJUSTABLE),
        ).apply(items)
        assertEquals(setOf("kilter-match", "moon"), out.map { it.id }.toSet())
    }

    @Test
    fun `MoonBoard variant and LED filters are board-specific`() {
        val moon2019 = loc("moon2019", layoutId = 5, boardBrand = BoardBrand.MOONBOARD)
            .copy(hasLed = true)
        val moon2016 = loc("moon2016", layoutId = 2, boardBrand = BoardBrand.MOONBOARD)
            .copy(hasLed = false)
        val kilter = loc("kilter")
        val out = MapFilters(
            moonLayoutIds = setOf(5),
            moonLedStates = setOf(MoonLedState.LED),
        ).apply(listOf(moon2019, moon2016, kilter))
        assertEquals(setOf("moon2019", "kilter"), out.map { it.id }.toSet())
    }
}
