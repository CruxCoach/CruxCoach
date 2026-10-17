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
        // The venue's town was attached by distance: hedged, with its German name kept.
        val info = snapshot.venueInfo.getValue(venueKey(48.1, 11.5))
        assertTrue(info.cityIsNearest)
        assertEquals("München", info.nearestCityDe)
    }

    @Test
    fun `parser keeps hours, wall angles, models and hedges a nearest city`() {
        val boards = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.5,48.1]},"properties":{
              "name":"\"Quoted Gym\"","city_nearest":"Munich","country":"DE",
              "hours":["10:00-22:00","10:00-22:00","10:00-22:00","10:00-22:00","10:00-22:00","",""],
              "hours_src":"https://example.com/hours",
              "boards":[{"board":"kilter","walls":[{"wall_name":"Big wall","layout":"Original","size_id":10,"size_label":"12x12","adjustable":true,"angle":40,"min_angle":0,"max_angle":70,"angle_increments":5}]}]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[23.3,42.7]},"properties":{
              "name":"Quantum Gym","city":"Sofia","country":"BG","hours":["bad"],
              "boards":[{"board":"quantum","models":["Quantum Board XL 15×15"]},{"board":"moonboard","variant":"mb2016","led":false,"angle":40,"commercial":false}]}}
        ]}"""
        val snapshot = PagesBoardMapParser.parse(boards, """{"cities":[]}""")

        val kilter = snapshot.locations.first { it.boardBrand == BoardBrand.KILTER }
        assertEquals("Quoted Gym", kilter.name)
        assertEquals(MapBoardInfo(wallName = "Big wall", minAngle = 0, maxAngle = 70, angleStep = 5, angle = 40), snapshot.boardInfo[kilter.id])

        val munich = snapshot.venueInfo[venueKey(48.1, 11.5)]!!
        assertTrue(munich.cityIsNearest)
        assertEquals("https://example.com/hours", munich.hoursSource)
        assertEquals(2, munich.hours!!.groups().size)

        // Malformed hours are dropped; an upstream city is not hedged.
        val sofia = snapshot.venueInfo[venueKey(42.7, 23.3)]
        assertEquals(null, sofia?.hours)
        assertEquals(false, sofia?.cityIsNearest ?: false)

        val quantum = snapshot.locations.first { it.boardBrand == BoardBrand.QUANTUM }
        assertEquals(listOf("Quantum Board XL 15×15"), snapshot.boardInfo[quantum.id]!!.models)
        val moon = snapshot.locations.first { it.boardBrand == BoardBrand.MOONBOARD }
        assertEquals(40, moon.fixedAngle)
        assertEquals(false, moon.hasLed)
        assertEquals(AccessType.PRIVATE, moon.accessType)

        val venues = groupIntoVenues(snapshot.locations, snapshot.venueInfo)
        assertTrue(venues.first { it.city == "Munich" }.info.cityIsNearest)
        assertEquals(listOf(BoardBrand.MOONBOARD, BoardBrand.QUANTUM), venues.first { it.city == "Sofia" }.markerBrands)
    }
}
