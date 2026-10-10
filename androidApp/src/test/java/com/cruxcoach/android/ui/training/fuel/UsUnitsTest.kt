package com.cruxcoach.android.ui.training.fuel

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import com.cruxcoach.android.ui.training.AthleteScreenTest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.athlete.SystemRegion
import com.cruxcoach.android.ui.settings.UnitsSection
import com.cruxcoach.android.ui.settings.UnitsSettingsViewModel
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * US units (owner request 2026-10-07): set next to the language, picked from
 * the phone's region on a first start, and used for water, food amounts and
 * drinks in oz, fl oz and cups – while storage stays grams and ml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UsUnitsTest : AthleteScreenTest() {

    /** A phone set to the US: a new athlete starts in US units. */
    override fun beforeReady() {
        service.systemRegion = object : SystemRegion(context) { override fun country() = "US" }
    }

    @Test
    fun `a first start in the US picks US units, an existing profile keeps its own`() {
        assertEquals(UnitSystem.IMPERIAL, repo.profile().units)
        assertEquals(2.5, repo.profile().smallestIncrementKg * com.cruxcoach.athlete.logic.Units.LB_PER_KG, 1e-9)

        repo.updateProfile { com.cruxcoach.athlete.logic.Units.withUnits(it, UnitSystem.METRIC) }
        val again = AthleteService({ repo }, ExerciseCatalogStore(context) { repo }, ClimbingDaysReader(SecureDatabase(secureDriver)),
            mockk(relaxed = true), sessionManager).apply { systemRegion = service.systemRegion }
        runBlocking { again.ensureReady() }
        assertEquals(UnitSystem.METRIC, repo.profile().units)
    }

    @Test
    fun `the units setting switches the whole profile`() {
        runBlocking { service.ensureReady() }
        repo.updateProfile { com.cruxcoach.athlete.logic.Units.withUnits(it, UnitSystem.METRIC) }
        val vm = UnitsSettingsViewModel(service).tracked()
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column { UnitsSection(vm) } } }
        waitForTag("settings_units")
        compose.onNodeWithText("US (oz, fl oz, cups, lb, in)").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().units == UnitSystem.IMPERIAL }
        assertEquals(2.5, repo.profile().smallestIncrementKg * com.cruxcoach.athlete.logic.Units.LB_PER_KG, 1e-9)
    }

    @Test
    fun `US units show water in fl oz and log a drink by cups`() {
        runBlocking { service.ensureReady() }
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveFoodItem(FoodItem(id = "juice", name = "Orange juice", kcalPer100 = 45.0, proteinPer100 = 0.7,
            carbsPer100 = 10.0, fatPer100 = 0.2, updatedAt = 1))
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")

        // A US cup of water: 8 fl oz, stored as 237 ml.
        // The next empty glass of the water card (below the day's rings).
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_water_237")); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("fuel_water_237").performSemanticsAction(SemanticsActions.OnClick)
        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.hydration(day).isNotEmpty() }
        assertEquals(237, repo.hydration(day).single().ml)

        waitForTag("fuel_my_foods")
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_food_juice")
        compose.onNodeWithTag("fuel_food_juice").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_dialog")
        // A drink offers fl oz and cups; one and a half cups are 355 ml.
        compose.onNodeWithTag("fuel_amount_unit_cup").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement("1.5")
        // The dialog shows what that amount brings before it is logged.
        compose.onNodeWithTag("fuel_amount_preview").assertTextContains("160 kcal", substring = true)
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntil(WAIT_MS) { repo.foodLog(day).isNotEmpty() }
        val entry = repo.foodLog(day).single()
        assertEquals(354.9, entry.amountG!!, 0.1)
        assertEquals(45.0 * 3.549, entry.kcal!!, 0.1)
        // The entry sits above the "my foods" button the list was scrolled to: scroll back to it,
        // a lazy list composes only what is on screen (timed out on CI otherwise).
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_list").performScrollToNode(hasText("12 fl oz", substring = true)); true }
                .getOrDefault(false)
        }
    }

}
