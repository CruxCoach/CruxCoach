package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.ui.training.body.BodyHistoryScreen
import com.cruxcoach.android.ui.training.body.BodyHistoryViewModel
import com.cruxcoach.android.ui.training.body.HistoryFilter
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-11-28: "ich kann mir nirgendwo alle historischen Gewichtslogs anschauen, nur letzte Einträge." The
 * history lists every reading by month, filtered by group, to correct or delete (with undo).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BodyHistoryTest : AthleteScreenTest() {

    private fun seed() {
        repo.saveMeasurement(BodyMeasurement("2026-09-14", "weight", 71.0, "kg", 1, WaistlineImport.SOURCE))
        repo.saveMeasurement(BodyMeasurement("2026-10-02", "weight", 70.5, "kg", 2))
        repo.saveMeasurement(BodyMeasurement("2026-10-02", "waist", 80.0, "cm", 3))
    }

    private fun gone(tag: String) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun `every reading by month, filtered by group, with the Waistline badge`() {
        seed()
        val vm = loaded(BodyHistoryViewModel(service, SavedStateHandle()))
        assertEquals(3, vm.state.value.entries.size)
        assertEquals(listOf("2026-10-02", "2026-10-02", "2026-09-14"), vm.state.value.entries.map { it.day })
        render { BodyHistoryScreen({}, viewModel = vm) }
        waitForTag("body_history_month_2026-10")
        scrollTo("body_history_list", "body_history_weight_2026-09-14")
        compose.onAllNodesWithTag("body_history_month_2026-09", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertTrue(compose.onAllNodesWithText("Waistline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        click("body_history_filter_weight")
        gone("body_history_waist_2026-10-02")
        assertEquals(HistoryFilter.WEIGHT, vm.state.value.filter)
        click("body_history_filter_circumference")
        waitForTag("body_history_waist_2026-10-02")
        click("body_history_filter_composition")
        waitForTag("body_history_empty")
    }

    @Test
    fun `a reading opens the edit dialog and is deleted with undo`() {
        seed()
        val vm = loaded(BodyHistoryViewModel(service, SavedStateHandle(mapOf(BodyHistoryViewModel.ARG_GROUP to "weight"))))
        assertEquals(HistoryFilter.WEIGHT, vm.state.value.filter)
        render { BodyHistoryScreen({}, viewModel = vm) }
        click("body_history_weight_2026-10-02")
        click("body_entry_delete")
        compose.waitUntil(WAIT_MS) { repo.allMeasurements().none { it.day == "2026-10-02" && it.metric == "weight" } }
        gone("body_history_weight_2026-10-02")
        clickText("Undo")
        compose.waitUntil(WAIT_MS) { repo.allMeasurements().any { it.day == "2026-10-02" && it.metric == "weight" } }
        waitForTag("body_history_weight_2026-10-02")
    }

    @Test
    fun `hidden body numbers stay hidden and no readings show the empty state`() {
        val empty = loaded(BodyHistoryViewModel(service, SavedStateHandle()))
        assertTrue(empty.state.value.entries.isEmpty())
        seed()
        repo.updateProfile { it.copy(hideBodyNumbers = true) }
        val vm = loaded(BodyHistoryViewModel(service, SavedStateHandle()))
        render { BodyHistoryScreen({}, viewModel = vm) }
        waitForTag("body_history_weight_2026-10-02")
        assertTrue(compose.onAllNodesWithText("hidden", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("70.5", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `without readings the history says so`() {
        val vm = loaded(BodyHistoryViewModel(service, SavedStateHandle()))
        render { BodyHistoryScreen({}, viewModel = vm) }
        waitForTag("body_history_empty")
    }
}
