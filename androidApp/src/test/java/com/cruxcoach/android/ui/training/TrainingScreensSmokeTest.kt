package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.ui.training.athlete.*
import com.cruxcoach.android.ui.training.body.BodyScreen
import com.cruxcoach.android.ui.training.body.BodyViewModel
import com.cruxcoach.android.ui.training.exercises.ExerciseCatalogScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseCatalogViewModel
import com.cruxcoach.android.ui.training.exercises.ExerciseDetailScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseDetailViewModel
import com.cruxcoach.android.ui.training.fuel.FuelScreen
import com.cruxcoach.android.ui.training.fuel.FuelViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workout.*
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.*
import io.mockk.mockk
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every new screen renders with real data — the packaged catalogue, a real
 * athlete database and the injury scenario from the owner's brief (left
 * finger injured: one-arm pick-ups on the right hand plus pull-ups) — and
 * reaches its loaded state without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrainingScreensSmokeTest : AthleteScreenTest() {

    @Before
    fun setUpInjuredClimber() {
        val today = service.today().toString()
        repo.updateProfile { it.copy(fuelIntroAccepted = true, equipmentConfigured = true,
            equipment = setOf(com.cruxcoach.athlete.catalog.EquipmentV2.PICKUP_BLOCK, com.cruxcoach.athlete.catalog.EquipmentV2.PLATES,
                com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR, com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL)) }
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 68.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        repo.saveInjury(Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "ring finger A2", 5, true, today))
        repo.saveFoodLog(FoodLogEntry("f1", today, 1, Meal.LUNCH, name = "Rice bowl", proteinG = 30.0, carbsG = 90.0))
    }

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun `today shows readiness and injury mode`() {
        val loadedVm1 = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = loadedVm1) }
        scrollTo("today_list", hasTestTag("today_readiness"))
        scrollTo("today_list", hasTestTag("today_injury"))
        scrollTo("today_list", hasTestTag("today_fuel"))
    }

    @Test
    fun `exercise library lists exercises with injury filters on`() {
        val loadedVm2 = loaded(ExerciseCatalogViewModel(service))
        render { ExerciseCatalogScreen(null, {}, {}, {}, {}, viewModel = loadedVm2) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tagPrefix("exercise_row_"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `one-hand finger injury keeps one-arm pick-up visible and favourites on top`() {
        repo.setFavorite("finger.one_arm_pickup", true)
        val vm = loaded(ExerciseCatalogViewModel(service))
        render { ExerciseCatalogScreen(null, {}, {}, {}, {}, viewModel = vm) }
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading }
        // Left hand hurt: climbing is paused, but finger work stays listed for the healthy hand.
        assert(vm.state.value.withoutClimbing)
        assert(!vm.state.value.fingerFree)
        waitForTag("exercise_fav_finger.one_arm_pickup")
    }

    @Test
    fun `exercise detail of the one-arm pick-up renders`() {
        // Loaded before rendering (the screen's own load call then returns at once), like the other smoke tests.
        val vm = loaded(ExerciseDetailViewModel(service).also { it.load("finger.one_arm_pickup") })
        render { ExerciseDetailScreen("finger.one_arm_pickup", {}, {}, {}, viewModel = vm) }
        waitForText("One-Arm Edge Pick-Up")
    }

    @Test
    fun `injury routine plans the pick-up for the healthy right hand only`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.INJURY_ONE_ARM), null)
        val pickupSides = repo.setsFor(id).filter { it.exerciseSlug == "finger.one_arm_pickup" }.map { it.side }.toSet()
        assert(pickupSides == setOf(Side.RIGHT)) { "sides were $pickupSides" }
        val loadedVm3 = loaded(WorkoutViewModel(service))
        render { WorkoutScreen({}, {}, {}, {}, viewModel = loadedVm3) }
        scrollTo("workout_list", hasText("One-Arm Edge Pick-Up", substring = true))
    }

    @Test
    fun `routines history and summary render`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.PULL_ANTAGONIST), null)
        repo.setsFor(id).take(3).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 7, null)
        val loadedVm4 = loaded(RoutinesViewModel(service))
        render { RoutinesScreen({}, {}, viewModel = loadedVm4) }
        waitForTag("routines_list")
    }

    @Test
    fun `history and summary render a finished training`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER), null)
        repo.setsFor(id).take(2).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 6, "felt good")
        val vm = loaded(WorkoutSummaryViewModel(service).also { it.load(id) })
        render { WorkoutSummaryScreen(id, {}, {}, viewModel = vm) }
        waitForTag("summary_list")
    }

    @Test
    fun `training history renders`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.MOBILITY_10), null)
        repo.setsFor(id).take(1).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 3, null)
        val loadedVm5 = loaded(TrainingHistoryViewModel(service))
        render { TrainingHistoryScreen({}, {}, viewModel = loadedVm5) }
        waitForTag("history_list")
    }

    @Test
    fun `body screen renders trend and ape index`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.ARM_SPAN.key, 181.0, "cm", 1))
        val loadedVm6 = loaded(BodyViewModel(service))
        render { BodyScreen({}, {}, viewModel = loadedVm6) }
        scrollTo("body_list", hasTestTag("body_ape_index"))
    }

    @Test
    fun `fuel screen renders targets for an enabled module`() {
        val photo = com.cruxcoach.android.ui.training.fuel.FoodPhotoViewModel(context, service,
            com.cruxcoach.android.foodvision.VisionModelStore(context), com.cruxcoach.android.foodvision.BlsRepository(context),
            com.cruxcoach.android.foodvision.DeviceFactsReader(context)).tracked()
        val loadedVm7 = loaded(FuelViewModel(service))
        render { FuelScreen({}, {}, viewModel = loadedVm7, photoViewModel = photo) }
        waitForTag("fuel_day_label")
        scrollTo("fuel_list", hasText("Rice bowl", substring = true))
    }

    @Test
    fun `injuries settings and weekly review render`() {
        val loadedVm8 = loaded(InjuriesViewModel(service))
        render { InjuriesScreen({}, {}, viewModel = loadedVm8) }
        waitForTag("injury_i1")
    }

    @Test
    fun `athlete settings show an overview with the current values`() {
        val loadedVm9 = loaded(AthleteSettingsViewModel(service))
        var opened: SettingsSection? = null
        render { AthleteSettingsScreen({}, viewModel = loadedVm9, onOpenSection = { opened = it }) }
        waitForTag("settings_overview")
        // The injured climber's equipment: four pieces, shown on the row without opening it.
        compose.onNodeWithText("4 pieces of equipment", substring = true).assertExists()
        click("settings_row_equipment")
        compose.waitUntil(WAIT_MS) { opened == SettingsSection.EQUIPMENT }
    }

    @Test
    fun `equipment page edits the equipment and settings from nutrition open its page`() {
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.EQUIPMENT) }
        waitForTag("preset_home")
        click("equipment_rings")
        compose.waitUntil(WAIT_MS) { com.cruxcoach.athlete.catalog.EquipmentV2.RINGS in repo.profile().equipment }
    }

    @Test
    fun `about you takes the birth year that nutrition and the coach read`() {
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.ABOUT) }
        waitForTag("birth_year_input")
        compose.onNodeWithTag("birth_year_input").performTextReplacement("1990")
        click("birth_year_save")
        compose.waitUntil(WAIT_MS) { repo.profile().birthYear == 1990 }
    }

    @Test
    fun `weekly goal and coach training days are one number`() {
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.PROFILE) }
        click("weekly_goal_plus")
        compose.waitUntil(WAIT_MS) { repo.profile().weeklyGoal == 4 }
        assertEquals(4, repo.profile().coach.trainingDaysPerWeek)
    }

    @Test
    fun `one Health Connect card has a switch per direction`() {
        val vm = com.cruxcoach.android.athlete.health.HealthConnectSettingsViewModel(
            com.cruxcoach.android.athlete.health.HealthConnectSource(context, service), service).tracked()
        render { com.cruxcoach.android.athlete.health.HealthConnectSettingsCard(viewModel = vm) }
        waitForTag("health_connect_switch")
        waitForTag("health_connect_export_switch")
    }

    @Test
    fun `weekly review renders`() {
        val loadedVm10 = loaded(WeeklyReviewViewModel(service, com.cruxcoach.android.foodvision.MicronutrientWeek(service,
            com.cruxcoach.android.foodvision.BlsRepository(context), com.cruxcoach.android.foodvision.UsdaRepository(context))))
        render { WeeklyReviewScreen({}, viewModel = loadedVm10) }
        waitForTag("review_prev")
    }

    @Test
    fun `first session learns a value, a better set raises it, a test may lower it`() {
        val id = service.startWorkout(null, null)
        service.addExercise(id, "pull.weighted_pull_up")
        val sets = repo.setsFor(id)
        val first = sets.first().copy(loadKg = 10.0, reps = 5)
        assert(service.completeSetDetailed(first, startRest = false).raisedBenchmark != null)
        val stronger = sets[1].copy(loadKg = 15.0, reps = 5, rir = 1)
        assert(service.completeSetDetailed(stronger, startRest = false).raisedBenchmark != null)
        val weaker = sets[2].copy(loadKg = 5.0, reps = 5)
        assert(service.completeSetDetailed(weaker, startRest = false).raisedBenchmark == null)
        assert(repo.benchmarks("pull.weighted_pull_up").first().loadKg == 15.0)
        // The next training plans from the raised value instead of repeating the last load.
        service.finishWorkout(id, 7, null)
        val next = service.startWorkout(null, null)
        service.addExercise(next, "pull.weighted_pull_up")
        val planned = repo.setsFor(next).first()
        assert((planned.targetLoadKg ?: 0.0) > 10.0) { "planned ${planned.targetLoadKg}" }
    }

    @Test
    fun `guided player shows the current set`() {
        service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.PULL_ANTAGONIST), null)
        val loadedVm11 = loaded(com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service))
        render { com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {},
            viewModel = loadedVm11) }
        waitForTag("player_set_view")
    }

    @Test
    fun `performance values screen lists the key exercises`() {
        val loadedVm12 = loaded(com.cruxcoach.android.ui.training.benchmarks.BenchmarksViewModel(service))
        render { com.cruxcoach.android.ui.training.benchmarks.BenchmarksScreen({}, {}, {},
            viewModel = loadedVm12) }
        waitForTag("benchmarks_list")
        scrollTo("benchmarks_list", hasTestTag("benchmark_row_finger.one_arm_pickup"))
    }

    @Test
    fun `an entered value updates untouched planned sets of the open training`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.INJURY_ONE_ARM), null)
        service.saveBenchmark(Benchmark("b1", "finger.one_arm_pickup", Side.RIGHT, 20.0, null, 32.0, null, 10.0, 68.0,
            BenchmarkSource.MANUAL, System.currentTimeMillis()))
        val pickups = repo.setsFor(id).filter { it.exerciseSlug == "finger.one_arm_pickup" }
        assert(pickups.isNotEmpty() && pickups.all { it.loadKg == 29.0 && it.targetLoadKg == 29.0 }) { pickups.map { it.loadKg } }
    }

    @Test
    fun `today shows a suggestion for the injured climber`() {
        val loadedVm13 = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = loadedVm13) }
        scrollTo("today_list", hasTestTag("today_suggestion_card"))
    }

    @Test
    fun `workouts tab, editor and week plan render`() {
        val loadedVm14 = loaded(com.cruxcoach.android.ui.training.workouts.WorkoutsViewModel(service))
        render { com.cruxcoach.android.ui.training.workouts.WorkoutsScreen({}, { _, _ -> }, {}, {}, {},
            viewModel = loadedVm14) }
        waitForTag("workouts_list")
        scrollTo("workouts_list", hasTestTag("workouts_week_plan"))
        scrollTo("workouts_list", hasTestTag("routine_start_builtin_warmup_board"))
    }

    @Test
    fun `routine editor prefills a starter routine`() {
        render { com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(null, "builtin:${BuiltinRoutines.LEGS_BASICS}", {}, {},
            viewModel = com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel(service).tracked()) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(tagPrefix("routine_item_"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `week plan renders seven days`() {
        val loadedVm15 = loaded(com.cruxcoach.android.ui.training.workouts.WeekPlanViewModel(service))
        render { com.cruxcoach.android.ui.training.workouts.WeekPlanScreen({},
            viewModel = loadedVm15) }
        waitForTag("week_plan_day_1")
    }

    @Test
    fun `stats hub and exercise stats render with history`() {
        val id = service.startWorkout(null, null)
        service.addExercise(id, "pull.pull_up")
        repo.setsFor(id).forEach { service.completeSet(it.copy(reps = 6), startRest = false) }
        service.finishWorkout(id, 6, null)
        val loadedVm16 = loaded(com.cruxcoach.android.ui.training.stats.StatsHubViewModel(service))
        render { com.cruxcoach.android.ui.training.stats.StatsHubScreen({}, {}, {}, {}, {},
            viewModel = loadedVm16) }
        waitForTag("stats_list")
    }

    @Test
    fun `exercise stats chart renders`() {
        val id = service.startWorkout(null, null)
        service.addExercise(id, "pull.pull_up")
        repo.setsFor(id).forEach { service.completeSet(it.copy(reps = 7), startRest = false) }
        service.finishWorkout(id, 6, null)
        val vm = loaded(com.cruxcoach.android.ui.training.stats.ExerciseStatsViewModel(service).also { it.load("pull.pull_up") })
        render { com.cruxcoach.android.ui.training.stats.ExerciseStatsScreen("pull.pull_up", {}, {}, viewModel = vm) }
        scrollTo("exercise_stats_list", hasTestTag("exercise_stats_chart"))
    }

    @Test
    fun `climber profile renders and explains what is missing`() {
        service.saveClimbingDay(ClimbingDayEntry("cd1", service.today().toString(), ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD))
        val loadedVm17 = loaded(com.cruxcoach.android.ui.training.stats.ClimberProfileViewModel(service))
        render { com.cruxcoach.android.ui.training.stats.ClimberProfileScreen({}, {}, {},
            viewModel = loadedVm17) }
        waitForTag("climber_profile_list")
    }

    @Test
    fun `manual climbing day counts as a hard climbing day and is listed`() {
        val today = service.today().toString()
        service.saveClimbingDay(ClimbingDayEntry("cd2", today, ClimbingDayKind.OUTDOOR, 120, ClimbIntensity.LIMIT))
        val day = service.activities(2)[today]
        assert(day != null && day.climbIntensity == ClimbIntensity.LIMIT && day.climbingMinutes >= 120) { "$day" }
        assert((day?.fingerLoad ?: 0.0) > 0.0)
        render { com.cruxcoach.android.ui.training.today.ClimbingDaysScreen({},
            viewModel = com.cruxcoach.android.ui.training.today.ClimbingDaysViewModel(service).tracked()) }
        waitForTag("climbing_day_row_cd2")
    }

    @Test
    fun `coach setup renders its first card`() {
        val vm = loaded(com.cruxcoach.android.ui.training.coach.CoachSetupViewModel(service, mockk(relaxed = true), mockk(relaxed = true)))
        render { com.cruxcoach.android.ui.training.coach.CoachSetupScreen({}, {}, {}, {}, viewModel = vm) }
        waitForTag("coach_next")
    }

    @Test
    fun `force gauge screen renders without a device`() {
        val loadedVm18 = loaded(com.cruxcoach.android.athlete.force.ForceGaugeViewModel(context, service))
        render { com.cruxcoach.android.athlete.force.ForceGaugeScreen({},
            viewModel = loadedVm18) }
        waitForTag("force_gauge")
    }

    @Test
    fun `workouts tab shows this week's calendar`() {
        val vm = loaded(com.cruxcoach.android.ui.training.workouts.WorkoutsViewModel(service))
        // Synchronously, so a failure names its line instead of timing out in the UI.
        vm.computeWeekNow()
        assertEquals(7, vm.state.value.week.size)
        render { com.cruxcoach.android.ui.training.workouts.WorkoutsScreen({}, { _, _ -> }, {}, {}, {}, viewModel = vm) }
        scrollTo("workouts_list", hasTestTag("week_calendar"))
    }

    @Test
    fun `letting go early records the held time and lowers the next hang`() {
        val id = service.startWorkout(null, null)
        service.addExercise(id, "finger.one_arm_pickup")
        val first = repo.setsFor(id).filter { it.setType == com.cruxcoach.athlete.model.SetType.WORK }
            .minWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
        val planned = first.durationS ?: first.targetDurationS ?: 10.0
        // Held only 6 s of a 10 s hang.
        val held = first.copy(durationS = 6.0, completedAt = System.currentTimeMillis())
        service.completeSet(held, startRest = false)
        val adjustment = service.adjustUpcomingSets(id, held)
        assert(planned > 6.0)
        assert(adjustment != null) { "a hold that ended early must change the next sets" }
    }
}
