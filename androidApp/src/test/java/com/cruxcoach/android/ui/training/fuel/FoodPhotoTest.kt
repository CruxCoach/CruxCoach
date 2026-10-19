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
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.runBlocking
import com.cruxcoach.android.foodvision.OffRepository
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
        // Every way of adding sits in the add sheet.
        click("fuel_my_foods")
        waitForTag("fuel_photo")
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
        // Every way of adding sits in the add sheet.
        click("fuel_my_foods")
        waitForTag("fuel_photo")
        compose.onNodeWithTag("fuel_photo").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_photo_setup")
        waitForText("Qwen3.5 2B")
        waitForTag("fuel_photo_download")
    }

    @Test
    fun `one tap corrects an estimate - a share of the amount, or whole pieces for a counted food`() {
        val photo = photoViewModel(pixel6a)
        runBlocking { photo.showReview(null, listOf(DetectedFood("Reis gekocht", "Boiled rice", 200.0), DetectedFood("Banane", "Banana", 100.0)), 9) }
        val (rice, banana) = (photo.state.value.phase as PhotoPhase.Review).items
        fun grams(key: Int) = FoodPhotoViewModel.amountOf((photo.state.value.phase as PhotoPhase.Review).items.single { it.key == key })
        assertNull(FoodPhotoViewModel.pieceOf(rice))
        photo.scaleAmount(rice.key, 1.25)
        assertEquals(250.0, grams(rice.key)!!, 0.0)
        photo.scaleAmount(rice.key, 0.5)
        assertEquals(125.0, grams(rice.key)!!, 0.0)
        // A banana is counted: 100 g rounds to one piece, one more makes two whole ones.
        assertEquals(120.0, FoodPhotoViewModel.pieceOf(banana)!!, 0.0)
        photo.stepPieces(banana.key, +1)
        assertEquals(240.0, grams(banana.key)!!, 0.0)
        photo.stepPieces(banana.key, -1)
        photo.stepPieces(banana.key, -1)
        assertEquals(120.0, grams(banana.key)!!, 0.0)
    }

    @Test
    fun `a food the table lacks can be chosen from the products and is logged as that product`() {
        val products = OffRepository(context).apply {
            source = { "# version: t\n4000000000017\tWhey Protein Vanille\t\tTestmarke\t380\t75\t6\t5\t30\t1\n".reader().buffered() }
            assetVersion = { "t" }
        }
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), facts(pixel6a), products).tracked()
        runBlocking { photo.showReview(null, listOf(DetectedFood("Whey", "Whey", 30.0)), 0, source = ReviewSource.TEXT_RULES) }
        val whey = (photo.state.value.phase as PhotoPhase.Review).items.single()
        // No counterpart in the food table: no wrong match.
        assertNull(whey.choice)
        var found: com.cruxcoach.athlete.logic.BlsFood? = null
        compose.waitUntil(WAIT_MS) {
            found = runBlocking { photo.searchWithProducts("whey") }.firstOrNull { FoodPhotoViewModel.isProduct(it) }
            found != null
        }
        assertEquals("off:4000000000017", found!!.code)
        assertTrue(found!!.nameDe.contains("Testmarke"))
        photo.choose(whey.key, found)
        val day = service.today().toString()
        photo.save(day, Meal.SNACK)
        compose.waitUntil(WAIT_MS) { photo.state.value.phase is PhotoPhase.Saved }
        val entry = repo.foodLog(day).single()
        assertEquals("off:4000000000017", entry.foodItemId)
        assertEquals(22.5, entry.proteinG!!, 0.001)
        assertEquals(30.0, entry.amountG!!, 0.0)
        val item = repo.foodItem("off:4000000000017")!!
        assertEquals("off", item.source)
        assertEquals("4000000000017", item.barcode)
        // The one-product test database must not stay for the next test (it would rebuild the real one from the APK).
        runBlocking { products.remove() }
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
    fun `choosing a drink for a line switches its amount to ml and back, keeping the quantity`() {
        val photo = photoViewModel(pixel6a)
        runBlocking { photo.showReview(null, listOf(DetectedFood("Knäckebrot", "Crispbread", 200.0)), 5) }
        fun item() = (photo.state.value.phase as PhotoPhase.Review).items.single()
        val bread = item().choice
        assertEquals(FuelUnits.Amount.G, item().unit)
        val milk = BlsFood("M111300", "Vollmilch frisch, 3,5 % Fett, pasteurisiert", "Whole milk, fresh, 3.5 % fat, pasteurised", 64.0, 3.3, 3.5, 4.8)
        photo.choose(item().key, milk)
        assertEquals(FuelUnits.Amount.ML, item().unit)
        assertEquals("200", item().amountText)
        photo.choose(item().key, bread)
        assertEquals(FuelUnits.Amount.G, item().unit)
        assertEquals("200", item().amountText)
        // In US units the same glass is 6.8 fl oz.
        photo.units = UnitSystem.IMPERIAL
        photo.choose(item().key, milk)
        assertEquals(FuelUnits.Amount.FL_OZ, item().unit)
        assertEquals(200.0, FoodPhotoViewModel.amountOf(item())!!, 2.0)
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
