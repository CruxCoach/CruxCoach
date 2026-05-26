package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
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
    )

    @Test
    fun `empty input returns empty output`() {
        val out = MapFilters().apply(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `default filters keep Original layout but drop Homewalls`() {
        val items = listOf(loc("a", layoutId = 1), loc("b", layoutId = 8))
        val out = MapFilters().apply(items)
        assertEquals(listOf("a"), out.map { it.id })
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
    fun `accessType + adjustability filter intersect`() {
        val items = listOf(
            loc("a", accessType = AccessType.PUBLIC, adjustability = Adjustability.ADJUSTABLE),
            loc("b", accessType = AccessType.PRIVATE, adjustability = Adjustability.ADJUSTABLE),
            loc("c", accessType = AccessType.PUBLIC, adjustability = Adjustability.FIXED),
        )
        val out = MapFilters(
            accessTypes = setOf(AccessType.PUBLIC),
            adjustabilities = setOf(Adjustability.ADJUSTABLE),
        ).apply(items)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `isAtDefault is true for fresh instance`() {
        assertTrue(MapFilters().isAtDefault)
    }

    @Test
    fun `isAtDefault is false when any filter applied`() {
        assertEquals(false, MapFilters(matchesMyBoard = true).isAtDefault)
        assertEquals(false, MapFilters(countries = setOf("DE")).isAtDefault)
        assertEquals(false, MapFilters(showHomewalls = true).isAtDefault)
    }
}
