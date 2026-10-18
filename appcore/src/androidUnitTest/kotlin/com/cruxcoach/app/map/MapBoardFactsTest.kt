package com.cruxcoach.app.map

import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of Android `MapBoardFactsTest`, assertions unchanged (plus the link checks Android could not run on the JVM). */
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
        assertEquals("venue-kilter", MapMarkerStyles.iconId(listOf(BoardBrand.KILTER)))
        assertEquals("venue-kilter+moonboard", MapMarkerStyles.iconId(listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)))
        // Every family has its own colour and a letter.
        val styles = BoardBrand.entries.map { MapMarkerStyles.of(it) }
        assertEquals(styles.size, styles.map { it.color }.toSet().size)
        assertTrue(styles.all { it.monogram.isNotBlank() })
    }

    @Test
    fun `instagram handles and profile links become one safe https link`() {
        assertNull(MapLinks.instagramUrlOrNull(null))
        assertNull(MapLinks.instagramUrlOrNull("  "))
        assertNull(MapLinks.instagramUrlOrNull("not a handle!"))
        // Android's Uri is a JVM stub; the string port can check the positive cases too.
        assertEquals("https://www.instagram.com/gym.x/", MapLinks.instagramUrlOrNull("@gym.x"))
        assertEquals("https://www.instagram.com/gym_x/", MapLinks.instagramUrlOrNull("https://instagram.com/gym_x/?hl=de"))
    }

    @Test
    fun `website links open only public http hosts`() {
        assertEquals("https://example.com", MapLinks.validatedHttpUrlOrNull("example.com"))
        assertEquals("http://example.com/x", MapLinks.validatedHttpUrlOrNull(" http://example.com/x "))
        assertNull(MapLinks.validatedHttpUrlOrNull("javascript://alert(1)"))
        assertNull(MapLinks.validatedHttpUrlOrNull("ftp://example.com"))
        assertNull(MapLinks.validatedHttpUrlOrNull("http://192.168.1.1/admin"))
        assertNull(MapLinks.validatedHttpUrlOrNull("http://user@127.0.0.1:8080/"))
        assertNull(MapLinks.validatedHttpUrlOrNull("http://[::1]/"))
        assertNull(MapLinks.validatedHttpUrlOrNull("http://exa mple.com"))
    }

    @Test
    fun `phone numbers keep only dialer characters`() {
        // Like Android: letters go, the dialer-safe space before them stays.
        assertEquals("+49 (89) 123-45 ", MapLinks.sanitisedPhoneOrNull(" +49 (89) 123-45 ext."))
        assertNull(MapLinks.sanitisedPhoneOrNull("call us"))
        assertNull(MapLinks.sanitisedPhoneOrNull(null))
    }
}
