package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Writing a recipe from searched ingredients, then changing it (FEAT-069). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecipeFlowTest {

    @get:Rule val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var athleteDriver: AndroidSqliteDriver
    private lateinit var secureDriver: AndroidSqliteDriver
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService
    private lateinit var sessionManager: BoardSessionManager
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
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveFoodItem(FoodItem(id = "milk", name = "Milch", kcalPer100 = 64.0, proteinPer100 = 3.3,
            carbsPer100 = 4.8, fatPer100 = 3.5, updatedAt = 1))
    }

    @After
    fun tearDown() {
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

    private fun click(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun addIngredient(search: String?, tag: String, grams: String) {
        click("fuel_recipe_add")
        waitForTag("fuel_foods_search")
        search?.let { compose.onNodeWithTag("fuel_foods_search").performTextInput(it) }
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false)
        }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_grams")
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement(grams)
        click("fuel_amount_confirm")
    }

    @Test
    fun `a recipe from a BLS food and an own food is saved and can be changed`() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        click("fuel_my_foods")
        click("fuel_recipe_new")
        waitForTag("fuel_recipe_name")
        compose.onNodeWithTag("fuel_recipe_name").performTextInput("Porridge")
        addIngredient("haferflocken", "fuel_bls_C133000", "100")
        waitForTag("fuel_recipe_ingredient_0")
        addIngredient(null, "fuel_food_milk", "300")
        waitForTag("fuel_recipe_ingredient_1")
        compose.onNodeWithTag("fuel_recipe_portions").performTextReplacement("2")
        waitForTag("fuel_recipe_summary")
        click("fuel_recipe_save")

        compose.waitUntil(WAIT_MS) { repo.foodItems().any { it.source == Recipe.SOURCE } }
        val item = repo.foodItems().single { it.source == Recipe.SOURCE }
        assertEquals("Porridge", item.name)
        assertEquals(200.0, item.servingG!!, 1e-9)
        // 13.22 g (BLS oat flakes) + 9.9 g (milk) protein in 400 g.
        assertEquals((13.22 + 9.9) / 4, item.proteinPer100!!, 1e-6)
        assertEquals(listOf("bls:C133000", "milk"), repo.recipe(item.id)!!.ingredients.map { it.foodItemId })

        // Changing it keeps the id: one ingredient less, one portion.
        waitForTag("fuel_foods_sheet")
        click("fuel_recipe_edit_${item.id}")
        waitForTag("fuel_recipe_ingredient_1")
        click("fuel_recipe_remove_1")
        compose.onNodeWithTag("fuel_recipe_portions").performTextReplacement("1")
        click("fuel_recipe_save")
        compose.waitUntil(WAIT_MS) { repo.recipe(item.id)?.ingredients?.size == 1 }
        assertEquals(100.0, repo.foodItem(item.id)!!.servingG!!, 1e-9)
        assertEquals(1, repo.foodItems().count { it.source == Recipe.SOURCE })
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
