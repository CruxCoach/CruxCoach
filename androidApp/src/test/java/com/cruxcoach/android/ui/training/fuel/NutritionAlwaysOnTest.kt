package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.android.ui.navigation.BrowserMainDrawer
import com.cruxcoach.android.ui.training.TrainingRoutes
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nutrition is its own main-menu entry and always on (owner 2026-10-05): a
 * fresh profile gets the food log at once, with the guard rails explained
 * once in a card instead of an opt-in screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NutritionAlwaysOnTest {

    @get:Rule val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var athleteDriver: AndroidSqliteDriver
    private lateinit var secureDriver: AndroidSqliteDriver
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService
    private lateinit var sessionManager: BoardSessionManager
    /** Created view models; their scopes are cancelled before the databases close. */
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private fun <T : androidx.lifecycle.ViewModel> T.tracked(): T = also { viewModels += it }

    @Before
    fun setUp() {
        athleteDriver = AndroidSqliteDriver(AthleteDatabase.Schema, context, null)
        secureDriver = AndroidSqliteDriver(SecureDatabase.Schema, context, null)
        repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.IO) { System.currentTimeMillis() }
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
        // A brand-new profile: nothing switched on, nothing accepted.
        assertFalse(repo.profile().fuelEnabled)
        assertFalse(repo.profile().fuelIntroAccepted)
    }

    @After
    fun tearDown() {
        // Let in-flight database work finish before the drivers close underneath it.
        viewModels.forEach { it.viewModelScope.cancel() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                viewModels.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join() }
            }
        }
        athleteDriver.close(); secureDriver.close()
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `fresh profile gets the food log with a one-time intro card`() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")
        waitForTag("fuel_intro")
        // The intro card pushes the buttons below the small test screen.
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_quick_add"))
        compose.onNodeWithTag("fuel_quick_add").assertExists()
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_intro_ok"))
        compose.onNodeWithTag("fuel_intro_ok").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().fuelIntroAccepted }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("fuel_intro").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("fuel_intro").assertCountEquals(0)
    }

    @Test
    fun `logged day shows meal subtotals and the day total against targets`() {
        val today = service.today().toString()
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(today, com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveFoodLog(com.cruxcoach.athlete.model.FoodLogEntry("a", today, 1, com.cruxcoach.athlete.model.Meal.LUNCH,
            name = "Pasta", amountG = 250.0, proteinG = 12.0, carbsG = 70.0, fatG = 2.0))
        repo.saveFoodLog(com.cruxcoach.athlete.model.FoodLogEntry("b", today, 2, com.cruxcoach.athlete.model.Meal.LUNCH,
            name = "Tomato sauce", amountG = 150.0, proteinG = 3.0, carbsG = 10.0, fatG = 8.0))
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")
        waitForTag("fuel_targets")
        // 70 kg × 1.6 g/kg protein = 112 g; 15 g logged.
        compose.onNodeWithText("15 / 112 g").assertExists()
        compose.onNodeWithText("97 g to go", substring = true).assertExists()
        compose.onNodeWithText("of energy", substring = true).assertExists()
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_meal_lunch"))
        // No kcal logged: calories are on by default and estimated from the macros (4/4/9).
        compose.onNodeWithTag("fuel_meal_total_lunch", useUnmergedTree = true).assertTextEquals("P 15 · C 80 · F 10 g · ≈ 470 kcal")
        compose.onNodeWithTag("fuel_kcal", useUnmergedTree = true).assertTextEquals("≈ 470 kcal")
    }

    @Test
    fun `main menu has nutrition next to training`() {
        var selected: String? = null
        compose.setContent { MaterialTheme { BrowserMainDrawer { selected = it } } }
        compose.onNodeWithTag("menu_today").assertExists()
        compose.onNodeWithText("Nutrition").assertExists()
        // The drawer sheet sits outside the small Robolectric window, so a
        // synthetic touch misses it; trigger the item's own click action.
        compose.onNodeWithTag("menu_nutrition").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(TrainingRoutes.FUEL, selected)
    }

    private companion object {
        /** CI runners and the shared dev server render slowly; same limit as TrainingScreensSmokeTest. */
        const val WAIT_MS = 60_000L
    }
}
