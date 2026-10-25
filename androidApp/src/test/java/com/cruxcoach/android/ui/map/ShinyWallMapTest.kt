package com.cruxcoach.android.ui.map

import com.cruxcoach.domain.board.BoardBrand
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shiny Wall venues reach the in-app board map: the website pipeline emits
 * them as `board: "shinywall"` with model labels, the parser files them under
 * the family, and they draw with the family's own marker.
 */
class ShinyWallMapTest {

    @Test
    fun `parser files a Shiny Wall board with its models`() {
        val boards = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[2.0,41.5]},"properties":{
              "name":"Wall Gym","city":"Terrassa","country":"ES",
              "boards":[
                {"board":"shinywall","address":"Somewhere 1","models":["Shiny Wall Standard Max Full"],"_source":"shinywall"},
                {"board":"kilter","walls":[{"layout":"Original","size_id":10,"size_label":"12x12","adjustable":true}]}
              ]}}
        ]}"""
        val snapshot = PagesBoardMapParser.parse(boards, """{"cities":[]}""")

        val shiny = snapshot.locations.single { it.boardBrand == BoardBrand.SHINYWALL }
        assertEquals("Wall Gym", shiny.name)
        assertEquals(listOf("Shiny Wall Standard Max Full"), snapshot.boardInfo[shiny.id]!!.models)
        val venue = groupIntoVenues(snapshot.locations, snapshot.venueInfo).single()
        assertEquals(listOf(BoardBrand.KILTER, BoardBrand.SHINYWALL), venue.markerBrands)
    }

    @Test
    fun `the bundled map snapshot carries Shiny Wall venues`() {
        val dir = listOf(File("src/main/assets/board_map"), File("androidApp/src/main/assets/board_map"))
            .firstOrNull { it.isDirectory } ?: error("board_map assets not found (cwd=${File(".").absolutePath})")
        val snapshot = PagesBoardMapParser.parse(
            File(dir, "boards.geojson").readText(),
            File(dir, "cities.json").readText(),
        )
        val shiny = snapshot.locations.filter { it.boardBrand == BoardBrand.SHINYWALL }
        assertTrue("no Shiny Wall venue in the bundled map", shiny.isNotEmpty())
        assertTrue(shiny.all { snapshot.boardInfo[it.id]?.models?.isNotEmpty() == true })
        // Every family that was on the map before is still there.
        val brands = snapshot.locations.map { it.boardBrand }.toSet()
        for (brand in listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.TENSION, BoardBrand.QUANTUM)) {
            assertTrue("$brand missing from the bundled map", brand in brands)
        }
    }

    @Test
    fun `Shiny Wall has its own marker`() {
        val style = BoardBrand.SHINYWALL.markerStyle()
        assertEquals("Sw", style.monogram)
        val others = BoardBrand.entries.filter { it != BoardBrand.SHINYWALL }.map { it.markerStyle() }
        assertTrue(others.none { it.monogram == style.monogram || it.color == style.color })
    }
}
