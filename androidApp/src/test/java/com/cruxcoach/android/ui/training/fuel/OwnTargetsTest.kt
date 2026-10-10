package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel
import com.cruxcoach.android.ui.training.athlete.SettingsSection
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Own targets next to the recommendation (owner 2026-10-10): every
 * nutrition target can be set by hand and taken back; a low own calorie
 * target is warned about with its numbers, never refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnTargetsTest : AthleteScreenTest() {

    private fun body() {
        val today = service.today().toString()
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = service.today().year - 30) }
    }

    @Test
    fun `the settings set an own fat target and take it back`() {
        body()
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.NUTRITION) }
        waitForTag("own_fat_own")
        compose.onNodeWithTag("own_fat_own").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        // "Own" starts from the recommendation, then takes a typed value.
        compose.waitUntil(WAIT_MS) { repo.profile().ownFatG != null }
        waitForTag("own_fat_input")
        compose.onNodeWithTag("own_fat_input").performScrollTo().performTextReplacement("60")
        compose.onNodeWithTag("own_fat_input").performImeAction()
        compose.waitUntil(WAIT_MS) { repo.profile().ownFatG == 60 }
        assertEquals(60, service.fuelTargets(null, repo.profile())!!.fatG)
        compose.onNodeWithTag("own_fat_auto").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().ownFatG == null }
    }

    @Test
    fun `a ring offers its own target and a low calorie target is named`() {
        body()
        repo.updateProfile { it.copy(ownKcal = 1400) }
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_targets")
        // The energy ring counts against the own target …
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("of 1400", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // … and the energy card names how far it lies under the need, without the weight-goal action.
        waitForTag("fuel_reds_large_deficit")
        assertTrue(compose.onAllNodesWithTag("fuel_reds_adjust").fetchSemanticsNodes().isEmpty())
        // The carbohydrate ring explains itself and offers an own target.
        compose.onNodeWithTag("fuel_carbs").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("own_carbs_own")
        compose.onNodeWithTag("own_carbs_own").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().ownCarbsG != null }
        assertNull(repo.profile().ownProteinG)
        compose.onNodeWithText("Close").performSemanticsAction(SemanticsActions.OnClick)
    }
}
