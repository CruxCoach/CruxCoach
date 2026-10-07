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
import com.cruxcoach.android.athlete.SystemRegion
import com.cruxcoach.android.ui.settings.UnitsSection
import com.cruxcoach.android.ui.settings.UnitsSettingsViewModel
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.UnitSystem
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
 * US units (owner request 2026-10-07): set next to the language, picked from
 * the phone's region on a first start, and used for water, food amounts and
 * drinks in oz, fl oz and cups – while storage stays grams and ml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UsUnitsTest {

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
        service.systemRegion = object : SystemRegion(context) { override fun country() = "US" }
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

    @Test
    fun `a first start in the US picks US units, an existing profile keeps its own`() {
        runBlocking { service.ensureReady() }
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
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                MaterialTheme { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
            }
        }
        waitForTag("fuel_list")

        // A US cup of water: 8 fl oz, stored as 237 ml.
        waitForTag("fuel_water_237")
        compose.onNodeWithTag("fuel_water_237").performSemanticsAction(SemanticsActions.OnClick)
        val day = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.hydration(day).isNotEmpty() }
        assertEquals(237, repo.hydration(day).single().ml)

        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        compose.onNodeWithTag("fuel_my_foods").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_food_juice")
        compose.onNodeWithTag("fuel_food_juice").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_dialog")
        // A drink offers fl oz and cups; one and a half cups are 355 ml.
        compose.onNodeWithTag("fuel_amount_unit_cup").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement("1.5")
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntil(WAIT_MS) { repo.foodLog(day).isNotEmpty() }
        val entry = repo.foodLog(day).single()
        assertEquals(354.9, entry.amountG!!, 0.1)
        assertEquals(45.0 * 3.549, entry.kcal!!, 0.1)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("12 fl oz", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
