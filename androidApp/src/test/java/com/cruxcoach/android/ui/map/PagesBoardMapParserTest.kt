package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesBoardMapParserTest {
    @Test
    fun `parser preserves curated venue boards and place aliases`() {
        val boards = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[11.5,48.1]},"properties":{"name":"Test Gym","city_nearest":"Munich","city_nearest_de":"München","country":"DE","website":"https://example.com","wellpass":true,"boards":[{"board":"kilter","walls":[{"layout":"Original","size_id":10,"size_label":"12x12","adjustable":true}]},{"board":"moonboard","variant":"mb2019-masters","commercial":true,"led":true}]}}]}"""
        val cities = """{"cities":[["Munich","DE",48.14,11.58,"Bavaria",["München"],"München"]]}"""

        val snapshot = PagesBoardMapParser.parse(boards, cities)

        assertEquals(2, snapshot.locations.size)
        assertEquals(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), snapshot.locations.map { it.boardBrand }.toSet())
        val moon = snapshot.locations.first { it.boardBrand == BoardBrand.MOONBOARD }
        assertEquals(5, moon.layoutId)
        assertEquals(AccessType.PUBLIC, moon.accessType)
        assertEquals(true, moon.hasLed)
        assertTrue("München" in moon.alternateSearchTerms)
        assertEquals("München", snapshot.places.single().germanName)
    }
}
