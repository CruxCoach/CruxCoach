package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Holistic audit P2-15: within two hours after a finished training, adding food starts in "after training". */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostTrainingMealTest : AthleteScreenTest() {

    @Test
    fun `the meal follows the time of day except within two hours after a training`() {
        val now = 1_800_000_000_000L
        val hour = 60 * 60 * 1000L
        assertEquals(Meal.POST_TRAINING, FuelViewModel.mealFor(now, 19, now - hour / 2))
        assertEquals(Meal.POST_TRAINING, FuelViewModel.mealFor(now, 19, now - 2 * hour))
        assertEquals(Meal.DINNER, FuelViewModel.mealFor(now, 19, now - 2 * hour - 1))
        assertEquals(Meal.BREAKFAST, FuelViewModel.mealFor(now, 7, null))
        // A clock that jumped back is no "after training".
        assertEquals(Meal.LUNCH, FuelViewModel.mealFor(now, 12, now + hour))
    }

    @Test
    fun `the add button starts in after training right after a finished training`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER), null)
        repo.setsFor(id).take(1).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 6, null)
        val vm = loaded(FuelViewModel(service))
        assertTrue(vm.state.value.lastTrainingEndedAt != null)
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = vm, photoViewModel = photo) }
        click("fuel_my_foods")
        waitForTag("fuel_meal_choice")
        compose.waitUntil(WAIT_MS) {
            compose.onNodeWithTag("fuel_meal_choice", useUnmergedTree = false).fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "After training" }
        }
    }
}
