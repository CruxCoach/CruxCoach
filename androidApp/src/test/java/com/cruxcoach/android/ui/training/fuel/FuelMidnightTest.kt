package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import com.cruxcoach.android.ui.training.AthleteScreenTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Device test D41: the nutrition screen left open over midnight showed "Yesterday" with the old day's food until
 * "Today" was tapped. On today it now follows the clock; a day picked with the arrows stays picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FuelMidnightTest : AthleteScreenTest() {

    private val zone = TimeZone.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(zone)
        FuelViewModel.dayTickMs = 60_000L
    }

    /** Moves "today" by switching the default zone from UTC-12 to UTC+14 (always a later date). */
    private fun midnightPasses() = TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-14"))

    @Test
    fun `on today the screen moves to the new day when the date changes`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        FuelViewModel.dayTickMs = 200L
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.day != null }
        val before = vm.state.value.day!!
        assertTrue(vm.state.value.isToday)
        midnightPasses()
        compose.waitUntil(WAIT_MS) { vm.state.value.day != before }
        assertEquals(service.today(), vm.state.value.day)
        assertTrue(vm.state.value.isToday)
    }

    @Test
    fun `a day picked with the arrows stays picked, and next back to today follows the clock again`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        FuelViewModel.dayTickMs = 200L
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.day != null }
        val start = vm.state.value.day!!
        vm.previousDay()
        val picked = start.minus(DatePeriod(days = 1))
        compose.waitUntil(WAIT_MS) { vm.state.value.day == picked }
        midnightPasses()
        compose.waitUntil(WAIT_MS) { vm.state.value.today != null && vm.state.value.today!! > start }
        assertEquals(picked, vm.state.value.day)
        // Forward to today: from there the clock decides again.
        repeat(5) { vm.nextDay() }
        compose.waitUntil(WAIT_MS) { vm.state.value.isToday }
        assertEquals(service.today(), vm.state.value.day)
    }
}
