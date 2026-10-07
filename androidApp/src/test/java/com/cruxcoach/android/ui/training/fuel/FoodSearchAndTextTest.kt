package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.cruxcoach.athlete.model.Meal
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
class FoodSearchAndTextTest {

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
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        context.getDatabasePath(OffRepository.DB_NAME).delete()
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

    private fun render(products: OffRepository = OffRepository(context)) {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context), products).tracked()
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `search finds a BLS food and logs it by grams`() {
        render()
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_foods_search")
        compose.onNodeWithTag("fuel_foods_search").performTextInput("haferflocken")
        waitForTag("fuel_bls_C133000")
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
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_foods_search")
        compose.onNodeWithTag("fuel_foods_search").performTextInput("skyr")
        waitForTag("fuel_off_4025500000001")
        compose.onNodeWithTag("fuel_off_4025500000001").performSemanticsAction(SemanticsActions.OnClick)
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
    fun `typed meal becomes a review list and is logged to the named meal`() {
        render()
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_describe"))
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

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
