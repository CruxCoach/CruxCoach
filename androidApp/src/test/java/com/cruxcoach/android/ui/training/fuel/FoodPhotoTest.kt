package com.cruxcoach.android.ui.training.fuel

import com.cruxcoach.android.ui.training.AthleteScreenTest
import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FEAT-069 without the model: the hardware gate in the fueling screen, the
 * setup sheet, and review → food log with BLS nutrients and water → drinking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodPhotoTest : AthleteScreenTest() {

    private val modernCpu = setOf("fp", "asimd", "fphp", "asimdhp", "asimddp")
    /** Nokia 6.1: Snapdragon 630, 2.8 GB. */
    private val nokia = DeviceFacts(2_861_000_000L, false, true, setOf("fp", "asimd", "aes", "crc32"))
    /** Pixel 6a: 6 GB nominal. */
    private val pixel6a = DeviceFacts(5_600_000_000L, false, true, modernCpu)

    private fun facts(f: DeviceFacts) = object : DeviceFactsReader(context) {
        override fun read() = f
    }

    private fun photoViewModel(f: DeviceFacts) =
        FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), facts(f)).tracked()

    @Before
    fun setUp() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
    }

    @Test
    fun `weak phone sees a greyed photo button that explains itself`() {
        val photo = photoViewModel(nokia)
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.CPU_FEATURES), photo.state.value.support)
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_photo"))
        compose.onNodeWithTag("fuel_photo").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_photo_unavailable")
        waitForText("too old")
        compose.onAllNodesWithTag("fuel_photo_sheet").assertCountEquals(0)
    }

    @Test
    fun `low memory names the needed and the actual size`() {
        val photo = photoViewModel(DeviceFacts(3_700_000_000L, false, true, modernCpu))
        render { FoodPhotoUnavailableDialog(photo.state.value, onRemoveModel = {}, onDismiss = {}) }
        waitForText("at least 6 GB")
        waitForText("3.7 GB")
    }

    @Test
    fun `capable phone opens the setup with the 2B download`() {
        val photo = photoViewModel(pixel6a)
        assertEquals(VisionSupport.Supported(VisionTier.SMALL), photo.state.value.support)
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_photo"))
        compose.onNodeWithTag("fuel_photo").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_photo_setup")
        waitForText("Qwen3.5 2B")
        waitForTag("fuel_photo_download")
    }

    @Test
    fun `review logs BLS nutrients and sends water to drinking`() {
        val photo = photoViewModel(pixel6a)
        runBlocking {
            photo.showReview(null, listOf(
                DetectedFood("Reis gekocht", "Boiled rice", 200.0),
                DetectedFood("Brühlchen", "Brussels sprouts", 150.0),
                DetectedFood("Wasser", "Water", 330.0),
                DetectedFood("Fantasiegericht", "Imaginary dish", 80.0),
            ), seconds = 31)
        }
        val review = photo.state.value.phase as PhotoPhase.Review
        val (rice, sprouts, water, unknown) = review.items
        assertTrue(rice.choice!!.nameDe.startsWith("Reis"))
        assertTrue(sprouts.choice!!.nameDe.startsWith("Rosenkohl"))
        assertTrue(water.water)
        assertNull(unknown.choice?.takeIf { !unknown.uncertain })

        photo.setIncluded(unknown.key, false)
        photo.setAmount(sprouts.key, "120")
        val day = service.today().toString()
        photo.save(day, Meal.LUNCH)
        compose.waitUntil(WAIT_MS) { photo.state.value.phase is PhotoPhase.Saved }

        val log = repo.foodLog(day)
        assertEquals(2, log.size)
        val riceEntry = log.single { it.foodItemId == "bls:${rice.choice!!.code}" }
        assertEquals(200.0, riceEntry.amountG!!, 0.0)
        assertEquals(rice.choice!!.carbs * 2, riceEntry.carbsG!!, 0.001)
        assertEquals(Meal.LUNCH, riceEntry.meal)
        val sproutsEntry = log.single { it.foodItemId == "bls:${sprouts.choice!!.code}" }
        assertEquals(sprouts.choice!!.protein * 1.2, sproutsEntry.proteinG!!, 0.001)
        assertEquals(listOf(330), repo.hydration(day).map { it.ml })
        // BLS foods become reusable "my foods" (per 100 g).
        assertEquals("bls", repo.foodItem("bls:${rice.choice!!.code}")!!.source)
    }

    @Test
    fun `review screen shows matches, water and the attribution`() {
        val photo = photoViewModel(pixel6a)
        runBlocking {
            photo.showReview(null, listOf(DetectedFood("Banane", "Banana", 120.0), DetectedFood("Wasser", "Water", 250.0)), 12)
        }
        render { FoodPhotoSheet(day = service.today().toString(), initialMeal = Meal.SNACK, onDismiss = {}, onSaved = {}, viewModel = photo) }
        waitForTag("fuel_photo_review")
        waitForText("Banana raw")
        waitForText("counts as drinking")
        waitForText("BLS 4.0")
        waitForText("Log 2 entries")
    }

}
