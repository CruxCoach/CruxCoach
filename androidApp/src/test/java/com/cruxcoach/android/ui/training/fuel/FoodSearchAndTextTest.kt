package com.cruxcoach.android.ui.training.fuel

import com.cruxcoach.android.ui.training.AthleteScreenTest
import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.athlete.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Logging without a photo (FEAT-069): the BLS search in "find food" and a
 * typed meal through the rule parser, both on any phone and offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodSearchAndTextTest : AthleteScreenTest() {

    @Before
    fun setUp() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        context.getDatabasePath(OffRepository.DB_NAME).delete()
    }

    private fun render(products: OffRepository = OffRepository(context)) {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context), products).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
    }

    @Test
    fun `search finds a BLS food and logs it by grams`() {
        render()
        waitForTag("fuel_my_foods")
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_foods_search")
        compose.onNodeWithTag("fuel_foods_search").performTextInput("haferflocken")
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag("fuel_bls_C133000")); true }
                .getOrDefault(false)
        }
        compose.onNodeWithTag("fuel_bls_C133000").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_dialog")
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement("80")
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)

        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).isNotEmpty() }
        val entry = repo.foodLog(day).single()
        assertEquals("bls:C133000", entry.foodItemId)
        assertEquals(80.0, entry.amountG!!, 0.0)
        // BLS 4.0 oat flakes: 13.22 g protein per 100 g.
        assertEquals(13.22 * 0.8, entry.proteinG!!, 0.01)
        // The picked food is now one of "my foods".
        assertEquals("bls", repo.foodItem("bls:C133000")!!.source)
    }

    @Test
    fun `product search finds a packaged product and logs one serving`() {
        val products = OffRepository(context).apply {
            source = { "# version: t\n4025500000001\tSkyr Natur\t\tMilbona\t63\t11\t4\t0.2\t150\t1\n".reader().buffered() }
            assetVersion = { "t" }
        }
        render(products)
        waitForTag("fuel_my_foods")
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_foods_search")
        compose.onNodeWithTag("fuel_foods_search").performTextInput("skyr")
        // BLS, USDA and products answer in any order and can re-sort the list after the scroll:
        // scroll and tap in one attempt until the tap lands (failed on a loaded CI runner, 2026-10-09).
        compose.waitUntil(WAIT_MS) {
            runCatching {
                compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag("fuel_off_4025500000001"))
                compose.onNodeWithTag("fuel_off_4025500000001").performSemanticsAction(SemanticsActions.OnClick)
                true
            }.getOrDefault(false)
        }
        waitForTag("fuel_amount_dialog")
        // A product with a serving size starts on "1 portion".
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)

        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).isNotEmpty() }
        val entry = repo.foodLog(day).single()
        assertEquals("off:4025500000001", entry.foodItemId)
        assertEquals(150.0, entry.amountG!!, 0.0)
        assertEquals(16.5, entry.proteinG!!, 0.01)
        assertEquals("4025500000001", repo.foodItem("off:4025500000001")!!.barcode)
    }

    @Test
    fun `a USDA food is logged by the cup and feeds the weekly micronutrients`() {
        repo.updateProfile { com.cruxcoach.athlete.logic.Units.withUnits(it, com.cruxcoach.athlete.model.UnitSystem.IMPERIAL) }
        render()
        waitForTag("fuel_my_foods")
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_foods_search")
        compose.onNodeWithTag("fuel_foods_search").performTextInput("oats regular quick")
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag("fuel_usda_173904")); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("fuel_usda_173904").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_dialog")
        // USDA weighed a cup of oats: one portion is that cup, shown with its weight in oz.
        compose.onNodeWithText("1 cup · 2.9 oz", substring = true).assertExists()
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)

        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).isNotEmpty() }
        val entry = repo.foodLog(day).single()
        assertEquals("usda:173904", entry.foodItemId)
        assertEquals(81.0, entry.amountG!!, 0.001)
        assertEquals("1 cup", repo.foodItem("usda:173904")!!.servingLabel)
        // 81 g oats carry 3.4 mg iron (4.25 mg per 100 g).
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_micros")); true }.getOrDefault(false)
        }
        compose.onNodeWithText("3.4 / 11 mg", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `typed meal becomes a review list and is logged to the named meal`() {
        render()
        // Every way of adding sits in the add sheet.
        click("fuel_my_foods")
        waitForTag("fuel_describe")
        compose.onNodeWithTag("fuel_describe").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_text_input")
        compose.onNodeWithTag("fuel_text_input").performTextInput("Zum Frühstück 80 g Haferflocken und eine Banane")
        compose.onNodeWithTag("fuel_text_go").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_photo_review")
        compose.onNodeWithTag("fuel_photo_save").performSemanticsAction(SemanticsActions.OnClick)

        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).size == 2 }
        val log = repo.foodLog(day)
        assertTrue(log.all { it.meal == Meal.BREAKFAST })
        assertEquals(listOf(80.0, 120.0), log.map { it.amountG })
        assertTrue(log.all { it.foodItemId?.startsWith("bls:") == true && it.kcal != null })
    }

}
