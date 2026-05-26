package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MapStatsTest {

    private fun loc(
        layoutId: Int? = 1,
        accessType: AccessType = AccessType.PUBLIC,
        adjustability: Adjustability = Adjustability.ADJUSTABLE,
        countryCode: String = "DE",
        sizeLabel: String? = "12x12",
    ) = BoardLocation(
        id = java.util.UUID.randomUUID().toString(),
        name = "x", lat = 0.0, lng = 0.0,
        address = null, city = null, countryCode = countryCode,
        phone = null, email = null, url = null, instagram = null,
        layoutName = null, layoutId = layoutId,
        sizeLabel = sizeLabel, productSizeId = null,
        accessType = accessType, adjustability = adjustability,
        fixedAngle = null, frameMaker = null,
    )

    @Test
    fun `empty list returns Empty singleton`() {
        assertSame(MapStats.Empty, MapStats.from(emptyList()))
    }

    @Test
    fun `layoutId counts split Original vs Homewall, unknown ignored`() {
        val items = listOf(
            loc(layoutId = 1), loc(layoutId = 1),
            loc(layoutId = 8),
            loc(layoutId = null),
        )
        val s = MapStats.from(items)
        assertEquals(4, s.total)
        assertEquals(2, s.originalCount)
        assertEquals(1, s.homewallCount)
    }

    @Test
    fun `access counts cover all four buckets`() {
        val items = listOf(
            loc(accessType = AccessType.PUBLIC),
            loc(accessType = AccessType.PUBLIC),
            loc(accessType = AccessType.PRIVATE),
            loc(accessType = AccessType.MEMBERS),
            loc(accessType = AccessType.UNKNOWN),
        )
        val s = MapStats.from(items)
        assertEquals(2, s.publicCount)
        assertEquals(1, s.privateCount)
        assertEquals(1, s.membersCount)
        assertEquals(1, s.accessUnknownCount)
    }

    @Test
    fun `adjustability bucketing collapses ADJUSTABLE FULL LIMITED into one`() {
        val items = listOf(
            loc(adjustability = Adjustability.ADJUSTABLE),
            loc(adjustability = Adjustability.FULL),
            loc(adjustability = Adjustability.LIMITED),
            loc(adjustability = Adjustability.FIXED),
            loc(adjustability = Adjustability.UNKNOWN),
        )
        val s = MapStats.from(items)
        assertEquals(3, s.adjustableCount)
        assertEquals(1, s.fixedCount)
        assertEquals(1, s.adjUnknownCount)
    }

    @Test
    fun `byCountry sorted desc by count`() {
        val items = listOf(
            loc(countryCode = "DE"), loc(countryCode = "DE"), loc(countryCode = "DE"),
            loc(countryCode = "FR"), loc(countryCode = "FR"),
            loc(countryCode = "AT"),
        )
        val s = MapStats.from(items)
        assertEquals(listOf("DE" to 3, "FR" to 2, "AT" to 1), s.byCountry)
    }

    @Test
    fun `bySize skips null and blank sizeLabel rows`() {
        val items = listOf(
            loc(sizeLabel = "12x12"), loc(sizeLabel = "12x12"),
            loc(sizeLabel = "10x7"),
            loc(sizeLabel = null), loc(sizeLabel = ""),
        )
        val s = MapStats.from(items)
        assertEquals(listOf("12x12" to 2, "10x7" to 1), s.bySize)
    }

    @Test
    fun `total counts every input regardless of skipped aggregates`() {
        val items = listOf(
            loc(layoutId = null, sizeLabel = null),
            loc(layoutId = 1, sizeLabel = "12x12"),
        )
        assertEquals(2, MapStats.from(items).total)
    }

    @Test
    fun `single-item list still produces a non-empty stats object`() {
        val s = MapStats.from(listOf(loc()))
        assertEquals(1, s.total)
        assertEquals(1, s.originalCount)
    }
}
