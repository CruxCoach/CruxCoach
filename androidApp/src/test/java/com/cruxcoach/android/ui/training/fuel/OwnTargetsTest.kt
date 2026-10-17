package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.cruxcoach.athlete.model.AthleteGoal
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
    fun `an own calorie target outside the plausible range is not kept and the field says why`() {
        body()
        repo.updateProfile { it.copy(ownKcal = 2000) }
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.NUTRITION) }
        waitForTag("own_energy_input")
        // 700 kcal is most likely a typo: not kept, and the range is named at the field – nothing else is locked.
        compose.onNodeWithTag("own_energy_input").performScrollTo().performTextReplacement("700")
        compose.onNodeWithTag("own_energy_input").performImeAction()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("Kept between 800 and 6000 kcal", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2000, repo.profile().ownKcal)
        // A low but plausible target is kept.
        compose.onNodeWithTag("own_energy_input").performTextReplacement("1200")
        compose.onNodeWithTag("own_energy_input").performImeAction()
        compose.waitUntil(WAIT_MS) { repo.profile().ownKcal == 1200 }
        assertTrue(compose.onAllNodesWithText("Kept between", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
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

    @Test
    fun `an own carbohydrate target says it does not follow the training day and names today's recommendation`() {
        body()
        repo.updateProfile { it.copy(ownCarbsG = 300) }
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        // A rest day recommends 3 g per kg: 70 kg → 210 g, while the own 300 g stay on the ring.
        waitForTag("fuel_own_note_carbs")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("does not follow the training day (recommended today 210 g)", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The recommended carbohydrates and calories carry no such line.
        assertTrue(compose.onAllNodesWithTag("fuel_own_note_energy", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // The ring's own field says the same in a word.
        compose.onNodeWithTag("fuel_carbs").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("own_carbs_note")
    }

    @Test
    fun `an own calorie target can be taken back while calories are hidden`() {
        body()
        repo.updateProfile { it.copy(ownKcal = 1800, showCalories = false) }
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.NUTRITION) }
        // No calorie field while they are hidden, but the target is named as still active and can be reset.
        waitForTag("own_energy_hidden")
        assertTrue(compose.onAllNodesWithTag("own_energy_input").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("own_energy_reset").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().ownKcal == null }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("own_energy_hidden").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `while losing weight the protein choice names the higher recommendation and goes up to 2_4 g per kg`() {
        body()
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.NUTRITION) }
        waitForTag("protein_per_kg")
        val range = compose.onNodeWithTag("protein_per_kg").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
        assertEquals(1.4f, range.start, 1e-4f)
        assertEquals(2.4f, range.endInclusive, 1e-4f)
        // Without a weight-loss goal no deficit recommendation.
        assertTrue(compose.onAllNodesWithTag("protein_losing_hint").fetchSemanticsNodes().isEmpty())
        repo.updateProfile { it.copy(goal = AthleteGoal.LOSE_WEIGHT, targetWeightKg = 66.0, weeklyLossKg = 0.3) }
        waitForTag("protein_losing_hint")
        waitForText("While losing weight 1.8–2.2 g per kg is recommended")
        // The athlete's choice above the usual range is kept and counts.
        compose.onNodeWithTag("protein_per_kg").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2.3f) }
        compose.waitUntil(WAIT_MS) { repo.profile().proteinPerKg == 2.3 }
        assertEquals(161, service.fuelTargets(null, repo.profile())!!.proteinG)
    }
}
