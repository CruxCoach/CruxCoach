package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.MicronutrientWeek
import com.cruxcoach.android.foodvision.UsdaRepository
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.athlete.WeeklyReviewScreen
import com.cruxcoach.android.ui.training.athlete.WeeklyReviewViewModel
import com.cruxcoach.android.ui.training.common.energyTarget
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Holistic audit P2-18 (b): the weekly review measures the logged days against the targets the nutrition screen set. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeeklyReviewTargetsTest : AthleteScreenTest() {

    @Test
    fun `the review uses the nutrition screen's protein carbohydrate and calorie targets`() {
        val today = service.today().toString()
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        repo.updateProfile {
            it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = service.today().year - 30, proteinPerKg = 2.2,
                goal = AthleteGoal.LOSE_WEIGHT, targetWeightKg = 66.0, weeklyLossKg = 0.5, ownCarbsG = 300)
        }
        repo.saveFoodLog(FoodLogEntry("f1", today, 1, Meal.BREAKFAST, name = "Oats", kcal = 600.0, proteinG = 25.0, carbsG = 90.0))
        repo.saveFoodLog(FoodLogEntry("f2", today, 2, Meal.LUNCH, name = "Rice bowl", kcal = 800.0, proteinG = 40.0, carbsG = 110.0))

        val vm = loaded(WeeklyReviewViewModel(service, MicronutrientWeek(service, BlsRepository(context), UsdaRepository(context))))
        val s = vm.state.value
        val profile = repo.profile()
        val activity = service.activities(days = 1)[today]
        val targets = service.fuelTargets(activity, profile)!!
        val need = service.energyNeed(profile, activity)!!
        val calorieTarget = energyTarget(need, service.weightPlan(profile, activity), profile)!!
        // The same numbers as the rings: protein from the chosen 2.2 g/kg, the own carbohydrate target, and energy
        // against the calorie target while losing weight – not against the need.
        assertEquals(targets.proteinG, s.proteinTarget)
        assertEquals(154, s.proteinTarget)
        assertEquals(300, s.carbsTargetAvg)
        assertEquals(calorieTarget, s.kcalTargetAvg)
        assertEquals(need.totalKcal, s.kcalNeedAvg)
        assertNotEquals(s.kcalNeedAvg, s.kcalTargetAvg)

        render { WeeklyReviewScreen({}, viewModel = vm) }
        waitForText("Energy on logged days: 1400 kcal (target about $calorieTarget kcal, estimated need about ${need.totalKcal} kcal)")
    }
}
