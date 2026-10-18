package com.cruxcoach.app.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Port of Android `VenueHoursTest`, assertions unchanged. */
class VenueHoursTest {
    private val days = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    private fun rows(hours: VenueHours) = hours.groups().map { g ->
        formatHoursDays(g, days) + " " + formatHoursRanges(g.ranges, "Geschlossen", "24 Stunden", "Folgetag")
    }

    @Test
    fun `identical days collapse into runs`() {
        val week = VenueHours.parse(List(5) { "10:00-22:00" } + List(2) { "08:00-22:00" })!!
        assertEquals(listOf("Mo–Fr 10:00–22:00", "Sa–So 08:00–22:00"), rows(week))
    }

    @Test
    fun `closed days and split days and all day and past midnight read like the website`() {
        val week = VenueHours.parse(
            listOf("", "10:00-14:00,16:00-22:00", "00:00-24:00", "18:00-26:00", "", "", "09:00-17:00")
        )!!
        assertEquals(
            listOf(
                "Mo Geschlossen",
                "Di 10:00–14:00, 16:00–22:00",
                "Mi 24 Stunden",
                "Do 18:00–02:00 (Folgetag)",
                "Fr–Sa Geschlossen",
                "So 09:00–17:00",
            ),
            rows(week),
        )
    }

    @Test
    fun `anything not canonical drops the whole week`() {
        val ok = List(7) { "10:00-22:00" }
        assertNotNull(VenueHours.parse(ok))
        assertNull(VenueHours.parse(null))
        assertNull(VenueHours.parse(ok.take(6)))
        assertNull(VenueHours.parse(List(7) { "" }))                       // nothing stated
        assertNull(VenueHours.parse(ok.toMutableList().also { it[2] = "10-22" }))
        assertNull(VenueHours.parse(ok.toMutableList().also { it[2] = "22:00-10:00" }))  // end before start
        assertNull(VenueHours.parse(ok.toMutableList().also { it[2] = "10:00-29:00" }))  // beyond 28:00
        assertNull(VenueHours.parse(ok.toMutableList().also { it[2] = "10:00-14:00,13:00-18:00" })) // overlap
        assertNull(VenueHours.parse(ok.toMutableList().also { it[2] = "geschlossen" }))
    }

    @Test
    fun `a single day keeps its ranges`() {
        val week = VenueHours.parse(listOf("", "", "", "", "", "", "10:00-12:00"))!!
        assertEquals(emptyList<IntRange>(), week.day(0))
        assertEquals(listOf(600..720), week.day(6))
    }
}
