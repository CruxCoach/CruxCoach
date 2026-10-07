package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.android.ui.settings.FoodDataStorageViewModel
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
import com.cruxcoach.athlete.model.Meal
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing a logged entry, and the nutrition files the settings show and remove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodEditAndStorageTest {

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
        context.getDatabasePath(OffRepository.DB_NAME).delete()
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

    /** Scrolls the day list until [tag] is composed. */
    private fun scrollTo(tag: String) = compose.waitUntil(WAIT_MS) {
        runCatching { compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false)
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun render() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")
    }

    @Test
    fun `a logged food gets a new amount and meal, a quick entry new values`() {
        val day = service.today().toString()
        repo.saveFoodItem(FoodItem(id = "oats", name = "Haferflocken", kcalPer100 = 370.0, proteinPer100 = 13.0,
            carbsPer100 = 59.0, fatPer100 = 7.0, updatedAt = 1))
        repo.saveFoodLog(FoodLogEntry(id = "e1", day = day, loggedAt = 1, meal = Meal.BREAKFAST, foodItemId = "oats",
            name = "Haferflocken", amountG = 100.0, kcal = 370.0, proteinG = 13.0, carbsG = 59.0, fatG = 7.0))
        repo.saveFoodLog(FoodLogEntry(id = "e2", day = day, loggedAt = 2, meal = Meal.SNACK, name = "Riegel", portions = 1.0,
            proteinG = 10.0))
        render()

        scrollTo("fuel_entry_e1")
        compose.onNodeWithTag("fuel_entry_e1").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_dialog")
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement("150")
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).first { it.id == "e1" }.amountG == 150.0 }
        val oats = repo.foodLog(day).first { it.id == "e1" }
        assertEquals(19.5, oats.proteinG!!, 0.001)
        assertEquals(Meal.BREAKFAST, oats.meal)

        scrollTo("fuel_entry_e2")
        compose.onNodeWithTag("fuel_entry_e2").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_qa_protein")
        compose.onNodeWithTag("fuel_qa_protein").performTextReplacement("12")
        compose.onNodeWithTag("fuel_qa_save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).first { it.id == "e2" }.proteinG == 12.0 }
        assertEquals("Riegel", repo.foodLog(day).first { it.id == "e2" }.name)
        assertEquals(2, repo.foodLog(day).size)
    }

    @Test
    fun `the product database is prepared ahead and can be removed`() {
        val products = OffRepository(context).apply {
            source = { "4025500000001\tSkyr Natur\t\tMilbona\t63\t11\t4\t0.2\t150\t1\t1 Becher (150 g)\n".reader().buffered() }
            assetVersion = { "t" }
        }
        products.prepareInBackground()
        compose.waitUntil(WAIT_MS) { products.state.value is OffRepository.State.Ready }
        assertEquals("1 Becher (150 g)", runBlocking { products.byBarcode("4025500000001") }?.servingLabel)

        val vm = FoodDataStorageViewModel(products, VisionModelStore(context)).tracked()
        compose.waitUntil(WAIT_MS) { vm.sizes.value.productsBytes > 0 }
        vm.removeProducts()
        compose.waitUntil(WAIT_MS) { vm.sizes.value.productsBytes == 0L }
        assertEquals(OffRepository.State.Missing, products.state.value)
        assertTrue(!context.getDatabasePath(OffRepository.DB_NAME).exists())
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
