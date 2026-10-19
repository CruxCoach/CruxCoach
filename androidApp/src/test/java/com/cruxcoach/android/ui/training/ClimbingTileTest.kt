package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.training.today.ClimbingDaySheet
import com.cruxcoach.android.ui.training.today.ClimbingDayViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device finding D30: a tap on the filled climbing tile opens today's entry, or the list when today has several,
 * instead of a new entry that logs the day twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClimbingTileTest : AthleteScreenTest() {

    private fun entry(id: String, minutes: Int) = ClimbingDayEntry(id, service.today().toString(), ClimbingDayKind.GYM_BOULDER, minutes,
        ClimbIntensity.HARD)

    @Test
    fun `the climbing tile logs a new day, opens today's entry or the list of several`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        val vm = loaded(TodayViewModel(service))
        val taps = mutableListOf<String>()
        render {
            TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm,
                onLogClimbing = { taps += "new" }, onEditClimbing = { taps += "edit:$it" }, onOpenClimbingDays = { taps += "list" })
        }
        scrollTo("today_list", hasTestTag("today_log_climbing"))
        click("today_log_climbing")
        compose.waitUntil(WAIT_MS) { taps.size == 1 }
        assertEquals("new", taps.last())

        service.saveClimbingDay(entry("gym", 60))
        compose.waitUntil(WAIT_MS) { vm.state.value.climbingEntriesToday == listOf("gym") }
        scrollTo("today_list", hasTestTag("today_log_climbing"))
        click("today_log_climbing")
        compose.waitUntil(WAIT_MS) { taps.size == 2 }
        assertEquals("edit:gym", taps.last())

        service.saveClimbingDay(entry("evening", 45))
        compose.waitUntil(WAIT_MS) { vm.state.value.climbingEntriesToday.size == 2 }
        scrollTo("today_list", hasTestTag("today_log_climbing"))
        click("today_log_climbing")
        compose.waitUntil(WAIT_MS) { taps.size == 3 }
        assertEquals("list", taps.last())
    }

    @Test
    fun `a deleted climbing day comes back with Undo, its board overlap choice too (device test D45)`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        service.saveClimbingDay(entry("gym", 90))
        val overlapKey = com.cruxcoach.athlete.logic.BoardOverlap.key("gym")
        val overlap = com.cruxcoach.athlete.logic.BoardOverlap.Stored(40, countAnyway = true).encode()
        repo.putSetting(overlapKey, overlap)
        val today = loaded(TodayViewModel(service))
        val sheet = ClimbingDayViewModel(service).tracked()
        var open by androidx.compose.runtime.mutableStateOf(true)
        render {
            TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = today)
            if (open) ClimbingDaySheet(onDismiss = { open = false }, editId = "gym", viewModel = sheet, onDeleted = today::climbingDeleted)
        }
        compose.waitUntil(WAIT_MS) { today.state.value.climbingEntriesToday == listOf("gym") }
        click("climbing_day_delete")
        compose.waitUntil(WAIT_MS) { repo.climbingDay("gym") == null }
        waitForText("Climbing day deleted")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { repo.climbingDay("gym") != null && repo.setting(overlapKey) == overlap }
        assertEquals(90, repo.climbingDay("gym")!!.minutes)
        assertEquals(overlap, repo.setting(overlapKey))
        compose.waitUntil(WAIT_MS) { today.state.value.climbingEntriesToday == listOf("gym") }
    }

    @Test
    fun `today's entry offers another session in one tap`() {
        service.saveClimbingDay(entry("gym", 60))
        var another = false
        render { ClimbingDaySheet(onDismiss = {}, editId = "gym", viewModel = ClimbingDayViewModel(service).tracked(), onAddAnother = { another = true }) }
        click("climbing_day_add_another")
        compose.waitUntil(WAIT_MS) { another }
    }
}
