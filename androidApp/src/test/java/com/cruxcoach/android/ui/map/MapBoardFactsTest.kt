package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBoardFactsTest {
    @Test
    fun `angles read like the website`() {
        assertEquals(
            AngleFact.Range(0, 70, 5),
            angleFact(Adjustability.ADJUSTABLE, MapBoardInfo(minAngle = 0, maxAngle = 70, angleStep = 5, angle = 40), null),
        )
        // Without both ends of the range the set angle is all we can say.
        assertEquals(AngleFact.SetTo(40), angleFact(Adjustability.ADJUSTABLE, MapBoardInfo(maxAngle = 70, angle = 40), null))
        assertEquals(AngleFact.Adjustable, angleFact(Adjustability.FULL, null, null))
        assertEquals(AngleFact.FixedAt(40), angleFact(Adjustability.FIXED, null, 40))
        assertEquals(AngleFact.FixedAt(45), angleFact(Adjustability.FIXED, MapBoardInfo(angle = 45), null))
        assertEquals(AngleFact.Fixed, angleFact(Adjustability.FIXED, null, null))
        assertNull(angleFact(Adjustability.UNKNOWN, MapBoardInfo(angle = 40), null))
    }

    @Test
    fun `filter chip counts everything but the brand choice`() {
        assertEquals(0, MapFilters().activeCountExceptBrands)
        assertEquals(0, MapFilters(brands = setOf(BoardBrand.KILTER)).activeCountExceptBrands)
        assertEquals(
            3,
            MapFilters(showHomewalls = false, wellpassOnly = true, countries = setOf("DE", "AT")).activeCountExceptBrands,
        )
    }

    @Test
    fun `marker ids are stable per family combination`() {
        assertEquals("venue-kilter", MapMarkerIcons.iconId(listOf(BoardBrand.KILTER)))
        assertEquals("venue-kilter+moonboard", MapMarkerIcons.iconId(listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)))
        // Every family has its own colour and a letter.
        val styles = BoardBrand.entries.map { it.markerStyle() }
        assertEquals(styles.size, styles.map { it.color }.toSet().size)
        assertTrue(styles.all { it.monogram.isNotBlank() })
    }

    @Test
    fun `instagram handles and profile links become one safe https link`() {
        // android.net.Uri is a stub on the JVM; check only what can be decided without it.
        assertNull(instagramUrlOrNull(null))
        assertNull(instagramUrlOrNull("  "))
        assertNull(instagramUrlOrNull("not a handle!"))
    }
}
