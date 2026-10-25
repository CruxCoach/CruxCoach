package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel
import com.cruxcoach.android.ui.training.body.BodyScreen
import com.cruxcoach.android.ui.training.body.BodyViewModel
import com.cruxcoach.android.ui.training.fuel.FoodPhotoViewModel
import com.cruxcoach.android.ui.training.fuel.FuelScreen
import com.cruxcoach.android.ui.training.fuel.FuelViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.datetime.minus
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.datetime.isoDayNumber
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.datetime.plus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review: renders the main training and nutrition screens in the dark
 * theme (light with `CRUXCOACH_UI_REVIEW_THEME=light`) on a tall German phone and writes them as PNGs into
 * `CRUXCOACH_UI_REVIEW_DIR`. Skipped unless that variable is set, so CI pays
 * nothing; run it locally to look at a layout change without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h1500dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrainingUiReviewTest : AthleteScreenTest() {

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val theme = if (System.getenv("CRUXCOACH_UI_REVIEW_THEME") == "light") DarkModeSetting.LIGHT else DarkModeSetting.DARK

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    /** A climber who uses the app: weight, a breakfast and lunch, two glasses of water. */
    private fun seedActive() {
        val today = service.today()
        repo.updateProfile { it.copy(fuelIntroAccepted = true, equipmentConfigured = true) }
        (0..6).forEach { d ->
            val day = today.minus(kotlinx.datetime.DatePeriod(days = d * 3)).toString()
            repo.saveMeasurement(BodyMeasurement(day, BodyMetric.WEIGHT.key, 68.0 + d * 0.3, "kg", 1L + d))
        }
        val t = today.toString()
        repo.saveFoodLog(FoodLogEntry("f1", t, 1, Meal.BREAKFAST, name = "Haferflocken", amountG = 80.0, proteinG = 11.0, carbsG = 47.0, fatG = 5.6))
        repo.saveFoodLog(FoodLogEntry("f2", t, 2, Meal.BREAKFAST, name = "Skyr", amountG = 150.0, proteinG = 16.0, carbsG = 6.0, fatG = 0.3))
        repo.saveFoodLog(FoodLogEntry("f3", t, 3, Meal.LUNCH, name = "Reis mit Gemüse", amountG = 350.0, proteinG = 12.0, carbsG = 85.0, fatG = 9.0))
        repo.addHydration(t, 250)
        repo.addHydration(t, 250)
    }

    private fun shoot(name: String, ready: () -> Unit, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) {
                CruxCoachTheme(theme) { Surface { content() } }
            }
        }
        ready()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            // Sheets and dialogs live in windows of their own: draw them on top, in order.
            windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
                val at = IntArray(2).also { w.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
            }
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$prefix$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    private fun photo() = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()

    private fun planOwnersExample() {
        val today = service.today()
        val d = { n: Int -> today.plus(kotlinx.datetime.DatePeriod(days = n)) }
        val climb = { i: com.cruxcoach.athlete.model.ClimbIntensity ->
            com.cruxcoach.athlete.logic.DayPlans.Climb(com.cruxcoach.athlete.model.ClimbingDayKind.GYM_BOULDER, i).entry }
        repo.updateProfile { p ->
            var q = p.copy(fuelIntroAccepted = true, checkinEnabled = false,
                weekPlan = mapOf(d(5).dayOfWeek.isoDayNumber to "builtin:" + com.cruxcoach.athlete.logic.BuiltinRoutines.FINGER_BASICS))
            q = com.cruxcoach.athlete.logic.DayPlans.withDay(q, today, com.cruxcoach.athlete.model.PLAN_REST, today)
            q = com.cruxcoach.athlete.logic.DayPlans.withDay(q, d(1), climb(com.cruxcoach.athlete.model.ClimbIntensity.VOLUME), today)
            com.cruxcoach.athlete.logic.DayPlans.withDay(q, d(3), climb(com.cruxcoach.athlete.model.ClimbIntensity.LIMIT), today)
        }
    }

    @Test
    fun dayPlanner() {
        planOwnersExample()
        val vm = loaded(com.cruxcoach.android.ui.training.workouts.DayPlannerViewModel(service))
        shoot("day-planner", { waitForTag("day_planner_day_${service.today()}") }) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                com.cruxcoach.android.ui.training.workouts.DayPlannerSheet(onDismiss = {}, viewModel = vm)
            }
        }
    }

    @Test
    fun dayPlanChooser() {
        val today = service.today()
        shoot("day-plan-chooser", { waitForTag("week_plan_climb") }) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize())
            com.cruxcoach.android.ui.training.workouts.PlanChooserDialog(
                title = "Morgen", current = com.cruxcoach.athlete.logic.DayPlans.Climb(com.cruxcoach.athlete.model.ClimbingDayKind.GYM_BOULDER,
                    com.cruxcoach.athlete.model.ClimbIntensity.VOLUME).entry,
                routines = emptyList(), onDismiss = {}, onChoose = {}, freeLabel = "Frei – der Coach entscheidet",
                standardLabel = "Wie in der Standardwoche: frei",
                swapTargets = (2..5).map { today.plus(kotlinx.datetime.DatePeriod(days = it)) })
        }
    }

    @Test
    fun todayPlannedClimb() {
        val today = service.today()
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false,
            datePlan = mapOf(today.toString() to com.cruxcoach.athlete.logic.DayPlans.Climb(com.cruxcoach.athlete.model.ClimbingDayKind.GYM_BOULDER,
                com.cruxcoach.athlete.model.ClimbIntensity.LIMIT).entry,
                today.plus(kotlinx.datetime.DatePeriod(days = 2)).toString() to com.cruxcoach.athlete.model.PLAN_REST)) }
        val vm = loaded(TodayViewModel(service))
        shoot("today-planned-climb", { waitForTag("today_suggestion_card") }) {
            TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm, tabBar = { TrainingTabBar(TrainingTab.TODAY) {} })
        }
    }

    @Test
    fun todayNew() {
        val vm = loaded(TodayViewModel(service))
        shoot("today-new", { waitForTag("today_list") }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm, tabBar = { TrainingTabBar(TrainingTab.TODAY) {} }) }
    }

    @Test
    fun todayActive() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        shoot("today-active", { waitForTag("today_list") }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm, tabBar = { TrainingTabBar(TrainingTab.TODAY) {} }) }
    }

    @Test
    fun fuelNew() {
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("fuel-new", { waitForTag("fuel_list") }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p, tabBar = { TrainingTabBar(TrainingTab.FUEL) {} }) }
    }

    @Test
    fun fuelActive() {
        seedActive()
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("fuel-active", { waitForTag("fuel_list") }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p, tabBar = { TrainingTabBar(TrainingTab.FUEL) {} }) }
    }

    @Test
    fun fuelAddSheet() {
        seedActive()
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("fuel-add-sheet", {
            waitForTag("fuel_my_foods"); click("fuel_my_foods"); waitForTag("fuel_foods_sheet")
            Thread.sleep(800)
        }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun fuelAddSheetRecent() {
        // Yesterday's breakfast comes back as "log again" (UX review 2026-10-16).
        val yesterday = service.today().minus(kotlinx.datetime.DatePeriod(days = 1)).toString()
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveFoodItem(com.cruxcoach.athlete.model.FoodItem(id = "oats", name = "Haferflocken", kcalPer100 = 370.0, proteinPer100 = 13.0,
            carbsPer100 = 59.0, fatPer100 = 7.0, updatedAt = 1))
        repo.saveFoodLog(FoodLogEntry("y1", yesterday, 1, Meal.BREAKFAST, foodItemId = "oats", name = "Haferflocken", amountG = 80.0,
            kcal = 296.0, proteinG = 10.4, carbsG = 47.2, fatG = 5.6))
        repo.saveFoodLog(FoodLogEntry("y2", yesterday, 2, Meal.BREAKFAST, name = "Kaffee mit Milch", portions = 1.0, kcal = 40.0, proteinG = 2.0))
        repo.saveFoodLog(FoodLogEntry("y3", yesterday, 3, Meal.BREAKFAST, name = "Skyr", amountG = 150.0, kcal = 95.0, proteinG = 16.0))
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.recentByMeal[Meal.BREAKFAST]?.size == 3 }
        val p = photo()
        shoot("fuel-add-sheet-recent", {
            scrollTo("fuel_list", "fuel_add_breakfast"); click("fuel_add_breakfast"); waitForTag("fuel_recent_y1")
            Thread.sleep(800)
        }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun playerExerciseMenu() {
        val vm = pullPlayer()
        shoot("player-exercise-menu", { click("player_exercise_menu"); waitForTag("player_skip_exercise") }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> })
        }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun learnedPage() {
        seedActive()
        // A swap, a weekday habit and an own lower load, as the coach would have learned them.
        val today = service.today()
        val now = System.currentTimeMillis()
        listOf(7, 14).forEach { ago ->
            val day = today.minus(kotlinx.datetime.DatePeriod(days = ago)).toString()
            val at = now - ago * 86_400_000L
            repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("k$ago", day, at, com.cruxcoach.athlete.model.SuggestionEventKind.FEEDBACK,
                focus = com.cruxcoach.athlete.logic.PreferenceLearning.CHOSEN_KIND + "PULL_PUSH"))
            repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("m$ago", day, at, com.cruxcoach.athlete.model.SuggestionEventKind.FEEDBACK,
                focus = com.cruxcoach.athlete.logic.PreferenceLearning.CHOSEN_MINUTES, value = 30.0))
            repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("s$ago", day, at + 1000, com.cruxcoach.athlete.model.SuggestionEventKind.STARTED))
            repo.logSuggestionEvent(com.cruxcoach.athlete.model.SuggestionEvent("w$ago", day, at, com.cruxcoach.athlete.model.SuggestionEventKind.SWAPPED,
                slugs = listOf("core.reverse_crunch", "core.dead_bug")))
        }
        repo.saveBenchmark(com.cruxcoach.athlete.model.Benchmark("own", "pull.weighted_pull_up", loadKg = 15.0, reps = 5, sets = 3, bodyweightKg = 68.0,
            source = com.cruxcoach.athlete.model.BenchmarkSource.AUTO, measuredAt = now))
        val vm = AthleteSettingsViewModel(service).tracked()
        shoot("learned-page", { compose.waitUntil(WAIT_MS) { vm.state.value.learned.weekdays.isNotEmpty() } }) {
            AthleteSettingsScreen({}, viewModel = vm, section = com.cruxcoach.android.ui.training.athlete.SettingsSection.EXCLUDED)
        }
    }

    @Test
    fun weightSheet() {
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("weight-sheet", {
            waitForTag("fuel_needs_weight_action"); click("fuel_needs_weight_action"); waitForTag("weight_sheet")
            Thread.sleep(800)
        }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p) }
    }

    @Test
    fun todayChecklistEquipment() {
        val vm = loaded(TodayViewModel(service))
        shoot("equipment-sheet", {
            scrollTo("today_list", "today_setup_equipment"); click("today_setup_equipment"); waitForTag("equipment_sheet")
            Thread.sleep(800)
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    fun exercises() {
        val vm = loaded(com.cruxcoach.android.ui.training.exercises.ExerciseCatalogViewModel(service))
        shoot("exercises", { compose.waitForIdle() }) {
            com.cruxcoach.android.ui.training.exercises.ExerciseCatalogScreen(null, {}, {}, {}, {}, viewModel = vm,
                tabBar = { TrainingTabBar(TrainingTab.WORKOUTS) {} }, header = { TrainingSwitch(true, {}, {}) })
        }
    }

    @Test
    fun player() {
        seedActive()
        service.startWorkout(com.cruxcoach.athlete.logic.BuiltinRoutines.byKey(com.cruxcoach.athlete.logic.BuiltinRoutines.FINGER_BASICS), null)
        val vm = loaded(com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service))
        shoot("player", { compose.waitForIdle(); Thread.sleep(500) }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm)
        }
    }

    @Test
    fun benchmarks() {
        seedActive()
        val vm = loaded(com.cruxcoach.android.ui.training.benchmarks.BenchmarksViewModel(service))
        shoot("benchmarks", { compose.waitForIdle() }) {
            com.cruxcoach.android.ui.training.benchmarks.BenchmarksScreen({}, {}, {}, viewModel = vm)
        }
    }

    @Test
    fun weekPlan() {
        val vm = loaded(com.cruxcoach.android.ui.training.workouts.WeekPlanViewModel(service))
        shoot("week-plan", { compose.waitForIdle() }) { com.cruxcoach.android.ui.training.workouts.WeekPlanScreen({}, viewModel = vm) }
    }

    @Test
    fun weeklyReview() {
        seedActive()
        val vm = loaded(com.cruxcoach.android.ui.training.athlete.WeeklyReviewViewModel(service,
            com.cruxcoach.android.foodvision.MicronutrientWeek(service, BlsRepository(context), com.cruxcoach.android.foodvision.UsdaRepository(context))))
        shoot("weekly-review", { compose.waitForIdle() }) { com.cruxcoach.android.ui.training.athlete.WeeklyReviewScreen({}, viewModel = vm) }
    }

    @Test
    fun bodyActive() {
        seedActive()
        val vm = loaded(BodyViewModel(service))
        shoot("body-active", { compose.waitForIdle() }) { BodyScreen({}, {}, viewModel = vm) }
    }

    @Test
    fun progressActive() {
        seedActive()
        val vm = com.cruxcoach.android.ui.training.stats.StatsHubViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { !vm.state.value.loading }
        shoot("progress-active", { waitForTag("stats_list") }) {
            com.cruxcoach.android.ui.training.stats.StatsHubScreen({}, {}, {}, {}, {}, viewModel = vm,
                tabBar = { TrainingTabBar(TrainingTab.STATS) {} })
        }
    }

    @Test
    fun workouts() {
        val vm = loaded(com.cruxcoach.android.ui.training.workouts.WorkoutsViewModel(service))
        shoot("workouts", { compose.waitForIdle() }) {
            com.cruxcoach.android.ui.training.workouts.WorkoutsScreen({}, { _, _ -> }, {}, {}, {}, viewModel = vm,
                tabBar = { TrainingTabBar(TrainingTab.WORKOUTS) {} }, header = { TrainingSwitch(false, {}, {}) })
        }
    }

    /** The active climber with a height, losing weight at [paceKg] a week. */
    private fun seedLosing(paceKg: Double) {
        seedActive()
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        repo.updateProfile { it.copy(sex = com.cruxcoach.athlete.model.Sex.MALE, birthYear = 1994,
            goal = com.cruxcoach.athlete.model.AthleteGoal.LOSE_WEIGHT, weeklyLossKg = paceKg, targetWeightKg = 64.0) }
    }

    @Test
    fun fuelEnergy() {
        seedLosing(0.5)
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("fuel-energy", { waitForTag("fuel_energy") }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p, tabBar = { TrainingTabBar(TrainingTab.FUEL) {} }) }
    }

    @Test
    fun energySheet() {
        seedLosing(0.5)
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("energy-sheet", { click("fuel_energy"); waitForTag("energy_need"); Thread.sleep(800) }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p) }
    }

    @Test
    fun fuelWarning() {
        seedLosing(1.2)
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("fuel-warning", { waitForTag("fuel_reds") }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p, tabBar = { TrainingTabBar(TrainingTab.FUEL) {} }) }
    }

    @Test
    fun goalSheet() {
        seedLosing(1.2)
        val vm = loaded(FuelViewModel(service))
        val p = photo()
        shoot("goal-sheet", { click("fuel_reds_adjust"); waitForTag("goal_result"); Thread.sleep(800) }) { FuelScreen({}, {}, viewModel = vm, photoViewModel = p) }
    }

    @Test
    fun settingsGoal() {
        seedLosing(1.2)
        val vm = AthleteSettingsViewModel(service).tracked()
        shoot("settings-goal", { compose.waitUntil(WAIT_MS) { vm.state.value.loaded }; waitForTag("goal_summary") }) {
            AthleteSettingsScreen({}, viewModel = vm, section = com.cruxcoach.android.ui.training.athlete.SettingsSection.PROFILE)
        }
    }

    /** Every catalogue exercise's thumbnail with its slug, for checking the pictograms at a glance (three pages). */
    @OptIn(ExperimentalLayoutApi::class)
    private fun iconPage(page: Int) {
        val defs = service.catalog.all.sortedWith(compareBy({ it.category.ordinal }, { it.slug })).chunked(70).getOrNull(page) ?: return
        shoot("icons-$page", { Thread.sleep(300) }) {
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(8.dp)) {
                defs.forEach { def ->
                    androidx.compose.foundation.layout.Column(Modifier.width(80.dp).padding(2.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        com.cruxcoach.android.ui.training.bodymap.ExerciseThumb(def)
                        androidx.compose.material3.Text(def.slug.substringAfter('.'), maxLines = 2,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp))
                    }
                }
            }
        }
    }

    @Test
    fun basics() {
        val vm = loaded(com.cruxcoach.android.ui.training.basics.BasicsViewModel(service))
        shoot("basics-0", { waitForTag("basics_private") }) { com.cruxcoach.android.ui.training.basics.BasicsScreen(onDone = {}, viewModel = vm) }
    }

    @Test fun exerciseIcons0() = iconPage(0)
    @Test fun exerciseIcons1() = iconPage(1)
    @Test fun exerciseIcons2() = iconPage(2)

    @Test
    fun settings() {
        val vm = AthleteSettingsViewModel(service).tracked()
        shoot("settings", { compose.waitUntil(WAIT_MS) { vm.state.value.loaded } }) { AthleteSettingsScreen({}, viewModel = vm) }
    }

    // ── 2026-10-15: the athlete's word wins (player, editor, values, today) – at a small phone's width ──

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pullPlayer(): com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel {
        seedActive()
        service.startWorkout(com.cruxcoach.athlete.model.Routine("r", "Zugkraft", items = listOf(
            com.cruxcoach.athlete.model.RoutineItem("pull.weighted_pull_up", sets = 4, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 150),
            com.cruxcoach.athlete.model.RoutineItem("push.push_up", sets = 3, repsMin = 12, repsMax = 12))), null)
        val vm = com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.phase == com.cruxcoach.android.ui.training.player.PlayerPhase.SET }
        return vm
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun playerOwnLoad() {
        val vm = pullPlayer()
        val first = vm.state.value.current!!.set
        vm.update(first.copy(loadKg = 22.5))
        shoot("player-own-load", { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.sets.count { it.loadKg == 22.5 } == 4 } }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> })
        }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun playerRestNextLoad() {
        val vm = pullPlayer()
        vm.completeCurrent()
        shoot("player-rest-next-load", { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.restArmed }; Thread.sleep(300) }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> })
        }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun playerBackToDoneSet() {
        val vm = pullPlayer()
        vm.completeCurrent()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.restArmed }
        vm.endRest()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.phase == com.cruxcoach.android.ui.training.player.PlayerPhase.SET }
        val done = vm.state.value.sets.first { it.isCompleted }
        vm.edit(done.id)
        shoot("player-back-done-set", { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.editingId == done.id } }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> })
        }
    }

    // ── 2026-10-17: one page per exercise, circuits ──

    private fun page(name: String, vm: com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel, ready: () -> Unit) =
        shoot(name, { ready(); Thread.sleep(400) }) {
            com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> })
        }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageActive() {
        val vm = pullPlayer()
        page("page-active", vm) { waitForTag("player_row_active") }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageRestAfterMissedReps() {
        val vm = pullPlayer()
        vm.completeAs(3.0)
        page("page-rest", vm) { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.restArmed && vm.state.value.adjustment != null } }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h640dp-xhdpi")
    fun pageRestSmallPhone() {
        val vm = pullPlayer()
        vm.completeCurrent()
        page("page-rest-640", vm) { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.restArmed } }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageEditDoneSet() {
        val vm = pullPlayer()
        vm.completeCurrent()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.restArmed }
        val done = vm.state.value.sets.first { it.isCompleted }
        vm.edit(done.id)
        page("page-edit", vm) { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.editingId == done.id } }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageCircuit() {
        seedActive()
        val items = com.cruxcoach.athlete.logic.Circuits.link(com.cruxcoach.athlete.logic.Circuits.link(listOf(
            com.cruxcoach.athlete.model.RoutineItem("pull.pull_up", sets = 3, repsMin = 6, repsMax = 6),
            com.cruxcoach.athlete.model.RoutineItem("push.push_up", sets = 3, repsMin = 12, repsMax = 12),
            com.cruxcoach.athlete.model.RoutineItem("core.dead_bug", sets = 3, repsMin = 10, repsMax = 10)), 0, service.catalog), 1, service.catalog)
        service.startWorkout(com.cruxcoach.athlete.model.Routine("c", "Zirkel", items = items), null)
        val vm = com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.phase == com.cruxcoach.android.ui.training.player.PlayerPhase.SET }
        vm.completeCurrent()
        page("page-circuit", vm) { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.sets.count { it.isCompleted } == 1 } }
    }

    private fun oneSetDone(): com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel {
        seedActive()
        service.startWorkout(com.cruxcoach.athlete.model.Routine("o", "Kurz", items = listOf(
            com.cruxcoach.athlete.model.RoutineItem("push.push_up", sets = 1, repsMin = 12, repsMax = 12))), null)
        val vm = com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.phase == com.cruxcoach.android.ui.training.player.PlayerPhase.SET }
        vm.completeCurrent()
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.phase == com.cruxcoach.android.ui.training.player.PlayerPhase.COMPLETE }
        return vm
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageLastSet() {
        val vm = oneSetDone()
        page("page-last-set", vm) { waitForTag("player_all_done") }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun pageComplete() {
        val vm = oneSetDone()
        vm.backToActive()
        page("page-complete", vm) { waitForTag("player_complete") }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayCircuit() {
        seedActive()
        repo.updateProfile { it.copy(equipment = setOf(com.cruxcoach.athlete.catalog.EquipmentV2.NONE, com.cruxcoach.athlete.catalog.EquipmentV2.MAT,
            com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR, com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL)) }
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null }
        vm.chooseFocus(com.cruxcoach.athlete.logic.SuggestionFocus.PULL_PUSH)
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.circuitPossible }
        vm.chooseCircuit(true)
        shoot("today-circuit", { compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.circuitToday }; Thread.sleep(400) }) {
            TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm)
        }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun editorCircuit() {
        seedActive()
        val vm = com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel(service).tracked()
        shoot("editor-circuit", {
            compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.catalog.all.isNotEmpty() }
            vm.addSlugs(listOf("pull.pull_up", "push.push_up", "core.dead_bug"))
            compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.items.size == 3 }
            vm.link(vm.state.value.items[0].key)
            vm.link(vm.state.value.items[1].key)
            compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.items.all { it.item.circuit != null } }
            Thread.sleep(300)
        }) { com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(null, null, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun editorTypedFree() {
        seedActive()
        val vm = com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel(service).tracked()
        shoot("editor-typed-free", {
            click("routine_editor_type")
            compose.onNodeWithTag("workout_text_input").performTextReplacement(
                "Klimmzüge 5x5 +10kg Schrägbankdrücken 4x8 22kg, dann Zirkel 3 Runden: Face Pulls 15, Dips 10, Hollow Hold 30s, " +
                    "90 s Pause nach jeder Runde")
            waitForTag("workout_text_circuit_1")
            waitForTag("workout_text_line_4")
        }) { com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(null, null, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun editorTyped() {
        seedActive()
        val vm = com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel(service).tracked()
        shoot("editor-typed", {
            click("routine_editor_type")
            compose.onNodeWithTag("workout_text_input").performTextReplacement(
                "Oberkörper\nKlimmzüge 5x5 +20 kg\nBankdrücken 3x8 60kg, Pause 2 min\nPlank 3x60s\nRussian Twists 3x20 8kg")
            waitForTag("workout_text_line_3")
        }) { com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(null, null, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun editorLoadField() {
        seedActive()
        val vm = com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel(service).tracked()
        shoot("editor-load", {
            compose.waitUntil(WAIT_MS) { !vm.state.value.loading }
            val result = com.cruxcoach.athlete.logic.WorkoutTextParser.parse("Push\nKlimmzüge 5x5 +20 kg\nDips 3x8", service.catalog)
            vm.addFromText(result, "Push")
            compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.items.size == 2 }
        }) { com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(null, null, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayQuickCheckin() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        shoot("today-quick-checkin", {
            scrollTo("today_list", "today_quick_checkin")
            click("today_quick_strength_4"); click("today_quick_hurts_yes"); click("today_sore_finger")
            scrollTo("today_list", "today_quick_show")
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayNextSheet() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(4, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        shoot("today-next-sheet", {
            scrollTo("today_list", "today_suggestion_next")
            click("today_suggestion_next")
            waitForTag("today_next_shuffle")
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todaySwapSheet() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(4, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        val first = vm.state.value.suggestion!!.routine.items.first { !it.warmup }.slug
        shoot("today-swap-sheet", {
            scrollTo("today_list", "today_item_$first")
            click("today_item_$first")
            waitForTag("today_swap_drop")
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayCardExpanded() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(4, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        vm.chooseMinutes(75)
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.minutesToday == 75 }
        shoot("today-card-expanded", {
            scrollTo("today_list", "today_items_more")
            click("today_items_more")
            scrollTo("today_list", "today_suggestion_start")
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayDurationMenu() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(4, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        val first = vm.state.value.suggestion!!.routine.items.first { !it.warmup }.slug
        repo.setFavorite(first, true)
        compose.waitUntil(WAIT_MS) { idleMain(); first in vm.state.value.favorites }
        shoot("today-duration-menu", {
            scrollTo("today_list", "today_duration")
            click("today_duration")
            waitForTag("today_duration_90")
        }) { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun todayLonger() {
        seedActive()
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(4, emptySet())
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        vm.chooseMinutes(90)
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.minutesToday == 90 }
        shoot("today-longer", { scrollTo("today_list", "today_duration") }) {
            TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm)
        }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun coachFavorites() {
        repo.setFavorite("core.dead_bug", true)
        repo.setFavorite("pull.pull_up", true)
        val vm = loaded(com.cruxcoach.android.ui.training.coach.CoachSetupViewModel(service, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true)))
        repeat(3) { vm.next() }
        compose.waitUntil(WAIT_MS) { idleMain(); vm.state.value.step == 3 && vm.state.value.favorites.size == 2 }
        vm.update { it.copy(variety = 20) }
        shoot("coach-favorites", {
            waitForTag("coach_favorites_add")
            compose.onNodeWithTag("coach_favorites_add").performScrollTo()
        }) { com.cruxcoach.android.ui.training.coach.CoachSetupScreen({}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "de-w360dp-h760dp-xhdpi")
    fun customExerciseGuess() {
        val vm = com.cruxcoach.android.ui.training.exercises.CustomExerciseViewModel(service).tracked()
        vm.setName("Ringdips mit Zusatzgewicht")
        shoot("custom-exercise-guess", { waitForTag("custom_exercise_guessed") }) {
            com.cruxcoach.android.ui.training.exercises.CustomExerciseScreen({}, {}, viewModel = vm)
        }
    }

    // ── Comeback plan (owner 2026-10-20) ─────────────────────────────

    private fun comebackInjury(stage: Int?): com.cruxcoach.athlete.model.Injury {
        val today = service.today()
        val base = com.cruxcoach.athlete.model.Injury("cb1", com.cruxcoach.athlete.model.InjuryRegion.FINGER,
            com.cruxcoach.athlete.model.InjurySide.LEFT, "Ringfinger A2", 3, startedOn = today.minus(kotlinx.datetime.DatePeriod(days = 16)).toString(),
            updatedAt = 1, check = com.cruxcoach.athlete.model.InjuryCheck(
                onset = com.cruxcoach.athlete.model.InjuryOnset.SUDDEN, pop = com.cruxcoach.athlete.model.InjuryPop.FELT,
                swelling = com.cruxcoach.athlete.model.InjurySwelling.MILD, fingers = setOf(com.cruxcoach.athlete.model.Finger.RING),
                checked = com.cruxcoach.athlete.model.ProfessionalCheck.IMAGING, finding = com.cruxcoach.athlete.model.InjuryFinding.PULLEY_PARTIAL,
                checkedOn = today.minus(kotlinx.datetime.DatePeriod(days = 14)).toString(), answeredOn = today.toString()))
        val injury = if (stage == null) base else com.cruxcoach.athlete.logic.Comeback.start(base,
            com.cruxcoach.athlete.model.PlanSource.CRUXCOACH, stage, today.minus(kotlinx.datetime.DatePeriod(days = 8)), true, false,
            mapOf(com.cruxcoach.athlete.model.Grip.HALF_CRIMP.name to 30.0, com.cruxcoach.athlete.model.Grip.OPEN_HAND.name to 32.0), 1)
        repo.saveInjury(injury)
        if (stage != null) (0..9).forEach { d ->
            repo.saveComebackEntry(com.cruxcoach.athlete.model.ComebackEntry("cb1", today.minus(kotlinx.datetime.DatePeriod(days = d)).toString(),
                morningPain = if (d > 6) 3 else 1, pressurePain = (2 + d / 2).coerceAtMost(5),
                sessions = listOf(com.cruxcoach.athlete.model.ComebackSessionLog(com.cruxcoach.athlete.model.ComebackSessionKind.TENDON, 1, at = 1)), updatedAt = 1))
        }
        return injury
    }

    private fun comebackScreen(name: String, stage: Int?, scrollTag: String? = null) {
        comebackInjury(stage)
        val vm = loaded(com.cruxcoach.android.ui.training.athlete.comeback.ComebackViewModel(service,
            androidx.lifecycle.SavedStateHandle(mapOf(com.cruxcoach.android.ui.training.athlete.comeback.ComebackViewModel.ARG_INJURY to "cb1"))))
        shoot(name, {
            waitForTag("comeback_notice")
            scrollTag?.let { scrollTo("comeback_list", it) }
        }) {
            com.cruxcoach.android.ui.training.athlete.comeback.ComebackScreen(onBack = {}, onOpenPlayer = {}, viewModel = vm)
        }
    }

    @Test fun comebackSetup() = comebackScreen("comeback-setup", null)
    @Test fun comebackSetupStages() = comebackScreen("comeback-setup-stages", null, "comeback_start")
    @Test fun comebackPlan() = comebackScreen("comeback-plan", 2)
    @Test fun comebackPlanSessions() = comebackScreen("comeback-plan-sessions", 2, "comeback_session_1")
    @Test fun comebackPlanDiary() = comebackScreen("comeback-plan-diary", 2, "comeback_chart")
    @Test fun comebackPlanInfo() = comebackScreen("comeback-plan-info", 2, "comeback_export")

    @Test
    fun comebackQuestions() {
        val injury = comebackInjury(null)
        shoot("comeback-questions", { waitForTag("comeback_check_list") }) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                com.cruxcoach.android.ui.training.athlete.comeback.InjuryCheckSheet(injury, service.today(), onDismiss = {}, onSave = {})
            }
        }
    }

    /** The injury card: comeback button with its notice, "It's over" instead of "Healed", past injuries. */
    @Test
    fun injuriesCard() {
        comebackInjury(2)
        val today = service.today()
        repo.saveInjury(com.cruxcoach.athlete.model.Injury("past1", com.cruxcoach.athlete.model.InjuryRegion.SHOULDER,
            com.cruxcoach.athlete.model.InjurySide.RIGHT, severity = 2, climbingPaused = false,
            startedOn = today.minus(kotlinx.datetime.DatePeriod(days = 90)).toString(),
            resolvedOn = today.minus(kotlinx.datetime.DatePeriod(days = 60)).toString(), updatedAt = 1))
        val vm = loaded(com.cruxcoach.android.ui.training.athlete.InjuriesViewModel(service))
        shoot("injuries-card", { waitForTag("injury_cb1") }) {
            com.cruxcoach.android.ui.training.athlete.InjuriesScreen(onBack = {}, onOpenRoutines = {}, viewModel = vm)
        }
    }

    @Test
    fun comebackToday() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        comebackInjury(2)
        val vm = loaded(com.cruxcoach.android.ui.training.today.TodayViewModel(service))
        shoot("comeback-today", { waitForTag("today_comeback_cb1") }) {
            com.cruxcoach.android.ui.training.today.TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm)
        }
    }
}
