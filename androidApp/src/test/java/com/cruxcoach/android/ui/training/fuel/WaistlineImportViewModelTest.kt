package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.common.WaistlineImportOutcome
import com.cruxcoach.android.ui.training.common.WaistlineImportViewModel
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-11-28: the Waistline import read body values only and was hard to find. One import now brings the
 * food diary and the body values; importing the same backup again adds nothing twice, and a day that already has
 * food entries here stays as it is unless it is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WaistlineImportViewModelTest : AthleteScreenTest() {

    private val source = "content://test/waistline_export.json"

    private val backup = """
        {"diary":[
          {"id":1,"dateTime":"2026-10-01T00:00:00.000Z","stats":{"weight":70.2},"items":[
            {"id":3,"portion":"150","quantity":"1","type":"food","dateTime":"2026-10-01T06:40:12.345Z","category":0},
            {"id":5,"portion":"1","quantity":"2","type":"food","dateTime":"2026-10-01T10:02:00.000Z","category":1}]},
          {"id":2,"dateTime":"2026-10-02T00:00:00.000Z","stats":{},"items":[
            {"id":3,"portion":"50","quantity":"2","type":"food","dateTime":"2026-10-02T07:00:00.000Z","category":0}]}],
         "foodList":[
          {"id":3,"name":"Haferflocken","portion":"100","unit":"g","nutrition":{"calories":372,"fat":7,"carbohydrates":58.7,"proteins":13.5}},
          {"id":5,"name":"Toast","portion":"1","unit":"Scheibe","nutrition":{"calories":67}}],
         "recipes":[],"meals":[],"version":34}
    """.trimIndent()

    private fun WaistlineImportViewModel.previewed() {
        preview(source, backup)
        compose.waitUntil(WAIT_MS) { state.value.preview != null }
    }

    private fun WaistlineImportViewModel.imported(replaceFoodDays: Boolean = false): WaistlineImportOutcome.Imported {
        confirm(overwriteBody = false, replaceFoodDays = replaceFoodDays)
        compose.waitUntil(WAIT_MS) { state.value.outcome != null }
        val outcome = state.value.outcome as WaistlineImportOutcome.Imported
        outcomeShown()
        return outcome
    }

    @Test
    fun `food diary, its foods and body values are imported, and a second import adds nothing`() {
        val vm = WaistlineImportViewModel(service).tracked()
        vm.previewed()
        assertEquals(0, vm.state.value.preview!!.foodConflictDays)
        val first = vm.imported()
        assertEquals(3, first.foodWritten)
        assertEquals(1, first.bodyWritten)
        val day = repo.foodLog("2026-10-01")
        assertEquals(2, day.size)
        val oats = day.single { it.id == "waistline:2026-10-01:0" }
        assertEquals(558.0, oats.kcal!!, 0.001)
        assertEquals(150.0, oats.amountG!!, 0.001)
        assertEquals(Meal.BREAKFAST, oats.meal)
        assertEquals(Meal.LUNCH, day.single { it.id == "waistline:2026-10-01:1" }.meal)
        assertNotNull(repo.foodItem("waistline:3"))
        assertNotNull(repo.foodItem("waistline:5"))
        assertEquals(70.2, repo.latest("weight")!!.value, 0.001)

        vm.previewed()
        assertEquals(2, vm.state.value.preview!!.foodConflictDays)
        val again = vm.imported()
        assertEquals(0, again.foodWritten)
        assertEquals(2, again.foodDaysKept)
        assertEquals(1, again.bodyKept)
        assertEquals(2, repo.foodLog("2026-10-01").size)
        assertEquals(1, repo.foodLog("2026-10-02").size)
        assertEquals(2, repo.foodItems().count { it.id.startsWith("waistline:") })
    }

    @Test
    fun `a day with own entries stays as it is unless it is replaced`() {
        repo.saveFoodLog(FoodLogEntry(id = "own", day = "2026-10-01", loggedAt = 1L, meal = Meal.LUNCH, name = "Brot", kcal = 200.0))
        val vm = WaistlineImportViewModel(service).tracked()
        vm.previewed()
        assertEquals(1, vm.state.value.preview!!.foodConflictDays)
        val kept = vm.imported()
        assertEquals(1, kept.foodWritten)
        assertEquals(1, kept.foodDaysKept)
        assertEquals(listOf("own"), repo.foodLog("2026-10-01").map { it.id })
        assertEquals(1, repo.foodLog("2026-10-02").size)

        vm.previewed()
        val replaced = vm.imported(replaceFoodDays = true)
        assertEquals(3, replaced.foodWritten)
        val ids = repo.foodLog("2026-10-01").map { it.id }.toSet()
        assertEquals(setOf("waistline:2026-10-01:0", "waistline:2026-10-01:1"), ids)
        assertTrue(repo.foodLog("2026-10-02").all { it.id.startsWith("waistline:") })
    }

    @Test
    fun `a diary CSV with food only as day totals says so`() {
        val vm = WaistlineImportViewModel(service).tracked()
        vm.preview("content://test/diary_export.csv", "Datum;Kalorien (kcal);Proteine (g)\n01.10.2026;2100;120\n")
        compose.waitUntil(WAIT_MS) { vm.state.value.outcome != null }
        assertEquals(WaistlineImportOutcome.Empty(csvTotalsOnly = true), vm.state.value.outcome)
        assertTrue(repo.foodLog("2026-10-01").isEmpty())
    }
}
