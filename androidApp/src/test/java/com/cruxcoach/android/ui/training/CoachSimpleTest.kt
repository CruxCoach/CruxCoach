package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.fuel.FoodPhotoViewModel
import com.cruxcoach.android.ui.training.fuel.FuelScreen
import com.cruxcoach.android.ui.training.fuel.FuelViewModel
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryScreen
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryViewModel
import com.cruxcoach.athlete.logic.NextTargets
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.ReadinessEvaluator
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEvent
import com.cruxcoach.athlete.model.SuggestionEventKind
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-11-21: simple by default, depth one tap away; the fitting progression is the main feature. The summary
 * opens with what the next training plans, the next step along a chain takes effect, and nutrition starts as one number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachSimpleTest : AthleteScreenTest() {

    private fun fuel(): FuelViewModel {
        val vm = FuelViewModel(service).tracked()
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = vm, photoViewModel = photo) }
        waitForTag("fuel_list")
        return vm
    }

    private fun absent(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    @Test
    fun `nutrition starts simple for someone new and exact is one tap away`() {
        val today = service.today().toString()
        repo.updateProfile { it.copy(fuelExact = null, fuelIntroAccepted = true) }
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        val vm = fuel()
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading && !vm.state.value.exact }
        waitForTag("fuel_simple_protein")
        waitForTag("fuel_simple_eaten")
        // One number: no rings for energy, carbohydrates and fat, no water glasses, no meal cards.
        assertTrue(absent("fuel_targets") && absent("fuel_water") && absent("fuel_meal_lunch"))
        // The choice is kept from the first look on.
        compose.waitUntil(WAIT_MS) { repo.profile().fuelExact == false }
        click("fuel_view_exact")
        compose.waitUntil(WAIT_MS) { repo.profile().fuelExact == true }
        waitForTag("fuel_targets")
        assertTrue(absent("fuel_simple_protein"))
    }

    @Test
    fun `whoever logged food before the choice existed keeps the exact view`() {
        val yesterday = service.today().minus(DatePeriod(days = 1)).toString()
        repo.updateProfile { it.copy(fuelExact = null, fuelIntroAccepted = true) }
        repo.saveFoodLog(FoodLogEntry("a", yesterday, 1, Meal.LUNCH, name = "Pasta", amountG = 250.0, proteinG = 12.0, carbsG = 70.0, fatG = 2.0))
        val vm = fuel()
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading && repo.profile().fuelExact == true }
        waitForTag("fuel_targets")
    }

    @Test
    fun `a day in the simple view never feeds the energy check`() {
        // Protein-only logging says nothing about the day's energy: three "low" days must not raise a warning.
        val today = service.today()
        repo.updateProfile { it.copy(fuelExact = false) }
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, 178.0, "cm", 1))
        (1..4).forEach { back ->
            val day = today.minus(DatePeriod(days = back)).toString()
            listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER).forEachIndexed { i, meal ->
                repo.saveFoodLog(FoodLogEntry("$back-$i", day, i.toLong(), meal, name = "Quark", amountG = 250.0, proteinG = 30.0, carbsG = 10.0, fatG = 1.0))
            }
        }
        val simple = service.energyReport(repo.profile(), service.activities(7))
        assertTrue("${simple.signals}", simple.signals.none { it.name == "HIGH_DEFICIT" || it.name == "LOW_CARBS_ON_TRAINING_DAYS" })
        // The same days in the exact view are whole days, and they are far too little.
        repo.updateProfile { it.copy(fuelExact = true) }
        val exact = service.energyReport(repo.profile(), service.activities(7))
        assertTrue("${exact.signals}", exact.signals.any { it.name == "HIGH_DEFICIT" })
        // Switched to "exact" today: the days before were logged for their protein and stay out (review 2026-11-21).
        repo.updateProfile { it.copy(fuelExact = false, fuelExactSince = null) }
        val vm = FuelViewModel(service).tracked()
        vm.setExact(true)
        compose.waitUntil(WAIT_MS) { repo.profile().fuelExactSince == today.toString() }
        val switched = service.energyReport(repo.profile(), service.activities(7))
        assertTrue("${switched.signals}", switched.signals.none { it.name == "HIGH_DEFICIT" })
    }

    @Test
    fun `two snacks are no whole day, two main meals are`() {
        val today = service.today()
        repo.updateProfile { it.copy(fuelExact = true) }
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, 178.0, "cm", 1))
        fun log(meals: List<Meal>) = (1..4).forEach { back ->
            val day = today.minus(DatePeriod(days = back)).toString()
            meals.forEachIndexed { i, meal ->
                repo.saveFoodLog(FoodLogEntry("$back-$i", day, i.toLong(), meal, name = "Quark", amountG = 250.0, proteinG = 30.0, carbsG = 10.0, fatG = 1.0))
            }
        }
        log(listOf(Meal.SNACK, Meal.POST_TRAINING))
        assertTrue(service.energyReport(repo.profile(), service.activities(7)).signals.none { it.name == "HIGH_DEFICIT" })
        log(listOf(Meal.BREAKFAST, Meal.DINNER))
        assertTrue(service.energyReport(repo.profile(), service.activities(7)).signals.any { it.name == "HIGH_DEFICIT" })
    }

    private val pushUps = Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 3, repsMin = 8, repsMax = 15, restS = 60)))

    /** One finished training of [routine]: every work set as planned, with [rir] in reserve. */
    private fun train(routine: Routine, rir: Int, reps: Int? = null): String {
        val id = service.startWorkout(routine, null)
        repo.setsFor(id).filter { it.setType == SetType.WORK }.forEach { set ->
            val done = set.copy(reps = reps ?: set.targetReps ?: set.reps, rir = rir)
            service.completeSet(done, startRest = false)
        }
        service.finishWorkout(id, 6, null)
        return id
    }

    @Test
    fun `the summary opens with what the next training plans`() {
        // A maximum of 14 push-ups plans 10 a set (70 %).
        service.saveBenchmark(Benchmark(id = "b1", exerciseSlug = "push.push_up", reps = 14, source = BenchmarkSource.MANUAL, measuredAt = 1))
        val id = train(pushUps, rir = 2)
        assertTrue(repo.setsFor(id).filter { it.setType == SetType.WORK }.all { it.reps == 10 })
        val vm = loaded(WorkoutSummaryViewModel(service).also { it.load(id) })
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading }
        val line = vm.state.value.nextTime.single { it.def.slug == "push.push_up" }
        // Ten with two in reserve: eleven next time, and the planner plans exactly that.
        assertEquals(11, line.next.reps)
        assertEquals(NextTargets.Move.UP, line.next.move)
        val next = service.startWorkout(pushUps, null)
        assertTrue(repo.setsFor(next).filter { it.setType == SetType.WORK }.all { it.targetReps == 11 })
        service.discardWorkout(next)
        render { WorkoutSummaryScreen(id, {}, {}, viewModel = vm) }
        waitForTag("summary_list")
        scrollTo("summary_list", hasTestTag("summary_next_time"))
        scrollTo("summary_list", hasTestTag("summary_next_push.push_up"))
    }

    @Test
    fun `the next step along the chain is offered at the top and takes effect for the coach`() {
        // Fifteen push-ups, the top of the range, with reserve: the next exercise of the chain is one tap away.
        val id = train(pushUps, rir = 2, reps = 15)
        val vm = loaded(WorkoutSummaryViewModel(service).also { it.load(id) })
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading }
        val line = vm.state.value.nextTime.single { it.def.slug == "push.push_up" }
        val harder = service.catalog["push.push_up"]!!.harder!!
        assertEquals(harder, line.offer?.slug)
        assertTrue(line.offerHarder)
        render { WorkoutSummaryScreen(id, {}, {}, viewModel = vm) }
        waitForTag("summary_list")
        scrollTo("summary_list", hasTestTag("summary_step_push.push_up"))
        click("summary_step_push.push_up")
        // The coach suggests the next step from now on, and the push-up rests: a kept choice in the ledger.
        compose.waitUntil(WAIT_MS) {
            PreferenceLearning.kept(repo.suggestionEventsSince(0), service.today()).any { it.replaced == "push.push_up" && it.chosen == harder }
        }
        compose.waitUntil(WAIT_MS) { vm.state.value.nextTime.single { it.def.slug == "push.push_up" }.offer == null }
        // "Undo" takes this step back, and the offer is there again.
        vm.undoStep("push.push_up")
        compose.waitUntil(WAIT_MS) { PreferenceLearning.kept(repo.suggestionEventsSince(0), service.today()).none { it.chosen == harder } }
        compose.waitUntil(WAIT_MS) { vm.state.value.nextTime.single { it.def.slug == "push.push_up" }.offer?.slug == harder }
        // A second tap while the first is still being saved takes one step, not two.
        vm.takeStep("push.push_up", harder); vm.takeStep("push.push_up", harder)
        compose.waitUntil(WAIT_MS) { PreferenceLearning.kept(repo.suggestionEventsSince(0), service.today()).any { it.chosen == harder } }
        compose.waitUntil(WAIT_MS) { vm.state.value.nextTime.single { it.def.slug == "push.push_up" }.offer == null }
        assertEquals(1, repo.suggestionEventsSince(0).count { it.focus == PreferenceLearning.KEEP_CHOICE })
    }

    @Test
    fun `a step is offered for the same kind of work only, and not back to what the athlete stepped away from`() {
        // Scapular push-ups at the top with reserve: their next exercise is filed under push, another job – no offer
        // (simulation 2026-11-21: the step took the push-up's place and the shoulder work was gone).
        val scapular = Routine("s", "Prehab", items = listOf(RoutineItem("antagonist.scapular_push_up", sets = 3, repsMin = 10, repsMax = 15)))
        val first = train(scapular, rir = 2, reps = 15)
        assertEquals(null, service.nextTime(first).single().offer)
        // Push-ups at the top: the diamond push-up is offered – until the athlete steps back from it (no back and forth).
        val second = train(pushUps, rir = 2, reps = 15)
        assertEquals("push.diamond_push_up", service.nextTime(second).single { it.def.slug == "push.push_up" }.offer?.slug)
        service.keepStep("push.diamond_push_up", "push.push_up")
        assertEquals(null, service.nextTime(second).single { it.def.slug == "push.push_up" }.offer)
        // A step back stands for six weeks, the next step up for as long as the ledger remembers.
        val kept = PreferenceLearning.kept(repo.suggestionEventsSince(0), service.today()).single()
        assertEquals(service.today().plus(DatePeriod(days = PreferenceLearning.WINDOW_DAYS)), kept.until)
    }

    @Test
    fun `a step taken months ago still leads its place`() {
        // Review 2026-11-21: a step holds for 180 days, but the coach read only the last 42 days of the ledger.
        val today = service.today()
        val harder = service.catalog["push.push_up"]!!.harder!!
        repo.logSuggestionEvent(SuggestionEvent("old-step", today.minus(DatePeriod(days = 100)).toString(),
            System.currentTimeMillis() - 100L * 86_400_000L, SuggestionEventKind.FEEDBACK, PreferenceLearning.KEEP_CHOICE,
            listOf("push.push_up", harder), value = today.plus(DatePeriod(days = 80)).toEpochDays().toDouble()))
        val built = service.todayCoach().build(profile = repo.profile(), checkin = null, injuries = emptyList(), pauses = emptyList(),
            readiness = ReadinessEvaluator.evaluate(null, emptyList()), activities = service.activities(days = 7 * 26), block = null,
            loadStatus = null, weekly = emptyList(), weekStart = null, logbook = null, today = today)
        assertTrue("${built.input.kept}", harder in built.input.kept)
        assertTrue("${built.input.resting}", "push.push_up" in built.input.resting)
    }

    @Test
    fun `an ended training plans its full sets next time, and a stretch has no line`() {
        service.saveBenchmark(Benchmark(id = "b1", exerciseSlug = "push.push_up", reps = 14, source = BenchmarkSource.MANUAL, measuredAt = 1))
        val stretch = service.catalog.all.first { it.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.MOBILITY &&
            it.kind == com.cruxcoach.athlete.catalog.ExerciseKind.TIME && it.equipment.all { e -> e == com.cruxcoach.athlete.catalog.EquipmentV2.NONE } }
        val id = service.startWorkout(Routine("r2", "Push", items = pushUps.items + RoutineItem(stretch.slug, sets = 2, durationS = 30)), null)
        // Two of the three push-up sets, then the training is ended; the stretch is done as written.
        val work = repo.setsFor(id).filter { it.setType == SetType.WORK }
        work.filter { it.exerciseSlug == "push.push_up" }.take(2).forEach { service.completeSet(it.copy(rir = 2), startRest = false) }
        work.filter { it.exerciseSlug == stretch.slug }.forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 6, null)
        val lines = service.nextTime(id)
        assertEquals(listOf("push.push_up"), lines.map { it.def.slug })
        assertEquals(3, lines.single().sets)
        assertEquals(2, lines.single().done.size)
    }
}
