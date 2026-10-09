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

    @Test fun exerciseIcons0() = iconPage(0)
    @Test fun exerciseIcons1() = iconPage(1)
    @Test fun exerciseIcons2() = iconPage(2)

    @Test
    fun settings() {
        val vm = AthleteSettingsViewModel(service).tracked()
        shoot("settings", { compose.waitUntil(WAIT_MS) { vm.state.value.loaded } }) { AthleteSettingsScreen({}, viewModel = vm) }
    }
}
