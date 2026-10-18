package com.cruxcoach.app.nutrition

import com.cruxcoach.app.ui.NutritionScreenModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Device test D41 (port of Android's FuelMidnightTest): the nutrition screen left open over midnight showed
 * "Yesterday" with the old day's food until "Today" was tapped. On today it now follows the clock; a day picked
 * with the arrows stays picked.
 */
class FuelMidnightTest {
    private val zone = TimeZone.getDefault()
    private val kits = mutableListOf<NutritionTestKit>()
    private val screens = mutableListOf<NutritionScreenModel>()

    private fun open(): Pair<NutritionTestKit, NutritionScreenModel> {
        val k = NutritionTestKit().also { kits += it }
        runBlocking { k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true) } } }
        return k to k.screen(dayTickMs = 200).also { screens += it }
    }

    @AfterTest
    fun tearDown() {
        screens.forEach { it.close() }
        kits.forEach { it.close() }
        TimeZone.setDefault(zone)
    }

    /** Moves "today" by switching the default zone from UTC-12 to UTC+14 (always a later date). */
    private fun midnightPasses() = TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-14"))

    @Test
    fun `on today the screen moves to the new day when the date changes`() = runBlocking<Unit> {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        val (k, screen) = open()
        val before = screen.await { !it.isLoading && it.dayIso.isNotEmpty() }
        assertTrue(before.isToday)
        midnightPasses()
        val after = screen.await { it.dayIso != before.dayIso }
        assertEquals(k.service.today().toString(), after.dayIso)
        assertTrue(after.isToday)
        assertTrue(LocalDate.parse(after.dayIso) > LocalDate.parse(before.dayIso))
    }

    @Test
    fun `a day picked with the arrows stays picked and next back to today follows the clock again`() = runBlocking<Unit> {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        val (k, screen) = open()
        val start = LocalDate.parse(screen.await { !it.isLoading && it.dayIso.isNotEmpty() }.dayIso)
        screen.previousDay()
        val picked = start.minus(DatePeriod(days = 1))
        val shown = screen.await { it.dayIso == picked.toString() }
        assertTrue(shown.isYesterday)
        midnightPasses()
        screen.await { screen.presenter.state.value.today?.let { it > start } == true }
        assertEquals(picked.toString(), screen.currentState.dayIso)
        // The clock's day is part of the key, so the picked day is relabelled: no longer "yesterday".
        assertFalse(screen.currentState.isYesterday)
        // Forward to today: from there the clock decides again.
        repeat(5) { screen.nextDay() }
        val back = screen.await { it.isToday }
        assertEquals(k.service.today().toString(), back.dayIso)
    }
}
