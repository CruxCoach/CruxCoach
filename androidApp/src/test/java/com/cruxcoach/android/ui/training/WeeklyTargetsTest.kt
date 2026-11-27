package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.workouts.WeeklyVolumeViewModel
import com.cruxcoach.athlete.logic.VolumeArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Own weekly targets next to the recommended range (owner 2026-10-10). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeeklyTargetsTest : AthleteScreenTest() {

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!condition()) { check(System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    @Test
    fun `an own pull target replaces the range and goes back to the recommendation`() {
        val vm = loaded(WeeklyVolumeViewModel(service))
        waitFor { !vm.state.value.loading }
        val recommended = vm.state.value.progress.first { it.area == VolumeArea.PULL }.target
        vm.setOwn(VolumeArea.PULL, 10)
        waitFor { vm.state.value.progress.first { it.area == VolumeArea.PULL }.target.own }
        val own = vm.state.value.progress.first { it.area == VolumeArea.PULL }.target
        assertEquals(10, own.minSets)
        assertEquals(10, own.maxSets)
        assertEquals(recommended, own.recommended)
        assertEquals(mapOf(VolumeArea.PULL.name to 10), repo.profile().ownWeeklyTargets)
        vm.setOwn(VolumeArea.PULL, null)
        waitFor { !vm.state.value.progress.first { it.area == VolumeArea.PULL }.target.own }
        assertTrue(repo.profile().ownWeeklyTargets.isEmpty())
    }
}
