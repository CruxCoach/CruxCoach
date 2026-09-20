package com.cruxcoach.app.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MapStatsTest {

    @Test
    fun `empty input yields the shared empty value`() {
        assertSame(MapStats.Empty, MapStats.from(emptyList()))
    }

    @Test
    fun `every board is counted, including several at one venue`() {
        // Two boards on the exact same coordinate: one venue, two boards.
        val stats = MapStats.from(
            listOf(
                location("a", lat = 52.5, lng = 13.4, layoutId = KILTER_ORIGINAL_LAYOUT),
                location("b", lat = 52.5, lng = 13.4, brand = BoardBrand.MOONBOARD, layoutId = 2),
            ),
        )
        assertEquals(2, stats.total)
        assertEquals(1, groupIntoVenues(
            listOf(
                location("a", lat = 52.5, lng = 13.4),
                location("b", lat = 52.5, lng = 13.4),
            ),
        ).size)
    }

    @Test
    fun `original and homewall are counted for Kilter only`() {
        val stats = MapStats.from(
            listOf(
                location("k-orig", layoutId = KILTER_ORIGINAL_LAYOUT),
                location("k-home", layoutId = KILTER_HOMEWALL_LAYOUT),
                location("k-other", layoutId = 99),
                // Aurora family sharing Kilter's layout ids must not be counted.
                location("t-1", brand = BoardBrand.TENSION, layoutId = KILTER_ORIGINAL_LAYOUT),
                location("t-8", brand = BoardBrand.TENSION, layoutId = KILTER_HOMEWALL_LAYOUT),
            ),
        )
        assertEquals(1, stats.originalCount)
        assertEquals(1, stats.homewallCount)
        assertEquals(5, stats.total)
    }

    @Test
    fun `access and adjustability buckets cover every value`() {
        val stats = MapStats.from(
            listOf(
                location("1", access = AccessType.PUBLIC, adjustability = Adjustability.ADJUSTABLE),
                location("2", access = AccessType.PRIVATE, adjustability = Adjustability.FULL),
                location("3", access = AccessType.MEMBERS, adjustability = Adjustability.LIMITED),
                location("4", access = AccessType.UNKNOWN, adjustability = Adjustability.FIXED),
                location("5", access = AccessType.PUBLIC, adjustability = Adjustability.UNKNOWN),
            ),
        )
        assertEquals(2, stats.publicCount)
        assertEquals(1, stats.privateCount)
        assertEquals(1, stats.membersCount)
        assertEquals(1, stats.accessUnknownCount)
        // FULL and LIMITED fold into "adjustable", exactly as Android does.
        assertEquals(3, stats.adjustableCount)
        assertEquals(1, stats.fixedCount)
        assertEquals(1, stats.adjUnknownCount)
    }

    @Test
    fun `byCountry and bySize are sorted by count then label`() {
        val stats = MapStats.from(
            listOf(
                location("1", country = "DE", sizeLabel = "12x12"),
                location("2", country = "DE", sizeLabel = "12x12"),
                location("3", country = "US", sizeLabel = "10x10"),
                location("4", country = "AT", sizeLabel = "8x12"),
                // Blank size labels are not a size bucket.
                location("5", country = "AT", sizeLabel = "  "),
            ),
        )
        assertEquals(listOf("AT" to 2, "DE" to 2, "US" to 1), stats.byCountry)
        assertEquals(listOf("12x12" to 2, "10x10" to 1, "8x12" to 1), stats.bySize)
        assertEquals(4, stats.bySize.sumOf { it.second })
    }

    @Test
    fun `byBrand and byLayout carry the brand split`() {
        val stats = MapStats.from(
            listOf(
                location("k1", layoutId = KILTER_ORIGINAL_LAYOUT),
                location("k2", layoutId = KILTER_ORIGINAL_LAYOUT),
                location("k3", layoutId = KILTER_HOMEWALL_LAYOUT),
                location("m1", brand = BoardBrand.MOONBOARD, layoutId = 2, layoutName = "MoonBoard 2016"),
                location("x1", brand = BoardBrand.TENSION, layoutId = 9),
            ),
        )
        assertEquals(
            listOf(BoardBrand.KILTER to 3, BoardBrand.MOONBOARD to 1, BoardBrand.TENSION to 1),
            stats.byBrand,
        )
        // Kilter layout names are derived from the layout id when absent.
        assertEquals(
            MapLayoutCount(BoardBrand.KILTER, "Original", 2),
            stats.byLayout.first(),
        )
        assertEquals(
            listOf("Homewall", "MoonBoard 2016", null),
            stats.byLayout.drop(1).map { it.layout },
        )
    }

    @Test
    fun `an explicit layout name wins over the derived Kilter name`() {
        val stats = MapStats.from(
            listOf(location("k", layoutId = KILTER_ORIGINAL_LAYOUT, layoutName = " Kilter Original ")),
        )
        assertEquals("Kilter Original", stats.byLayout.single().layout)
    }
}
