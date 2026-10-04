package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
class FoodPhotoTest {

    @get:Rule val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val athleteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
    private val secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.IO) { System.currentTimeMillis() }
    private lateinit var service: AthleteService
    private lateinit var sessionManager: BoardSessionManager

    private val modernCpu = setOf("fp", "asimd", "fphp", "asimdhp", "asimddp")
    /** Nokia 6.1: Snapdragon 630, 2.8 GB. */
    private val nokia = DeviceFacts(2_861_000_000L, false, true, setOf("fp", "asimd", "aes", "crc32"))
    /** Pixel 6a: 6 GB nominal. */
    private val pixel6a = DeviceFacts(5_600_000_000L, false, true, modernCpu)

    private fun facts(f: DeviceFacts) = object : DeviceFactsReader(context) {
        override fun read() = f
    }

    private fun photoViewModel(f: DeviceFacts) =
        FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), facts(f))

    @Before
    fun setUp() {
        val boardRepo = mockk<com.cruxcoach.data.repository.PersonalBoardRepository>(relaxed = true)
        every { boardRepo.getActiveSession() } returns null
        sessionManager = BoardSessionManager(boardRepo, mockk(relaxed = true), mockk(relaxed = true))
        service = AthleteService(
            repoLazy = { repo },
            catalogStore = ExerciseCatalogStore(context) { repo },
            climbingDays = ClimbingDaysReader(SecureDatabase(secureDriver)),
            bodyStatRepository = mockk(relaxed = true),
            sessionManager = sessionManager,
        )
        runBlocking { service.ensureReady() }
        repo.updateProfile { it.copy(fuelEnabled = true, fuelIntroAccepted = true) }
    }

    @After
    fun tearDown() {
        athleteDriver.close(); secureDriver.close()
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) { MaterialTheme { content() } }
        }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `weak phone sees a greyed photo button that explains itself`() {
        val photo = photoViewModel(nokia)
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.CPU_FEATURES), photo.state.value.support)
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_photo"))
        compose.onNodeWithTag("fuel_photo").assertIsEnabled().performClick()
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
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_photo"))
        compose.onNodeWithTag("fuel_photo").performClick()
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
        compose.waitUntil(10_000) { photo.state.value.phase is PhotoPhase.Saved }

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
