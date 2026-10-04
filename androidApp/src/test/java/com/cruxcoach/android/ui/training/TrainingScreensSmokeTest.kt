package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
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
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.*
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
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
class TrainingScreensSmokeTest {

    @get:Rule val compose = createComposeRule()

    // Android's own SQLite (Robolectric) rather than JDBC: registering the
    // JDBC driver inside the sandbox class loader breaks plain JDBC tests that
    // run later in the same JVM.
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var athleteDriver: AndroidSqliteDriver
    private lateinit var secureDriver: AndroidSqliteDriver
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService
    private lateinit var sessionManager: BoardSessionManager

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
        val today = service.today().toString()
        repo.updateProfile { it.copy(fuelEnabled = true, fuelIntroAccepted = true, equipmentConfigured = true,
            equipment = setOf(com.cruxcoach.athlete.catalog.EquipmentV2.PICKUP_BLOCK, com.cruxcoach.athlete.catalog.EquipmentV2.PLATES,
                com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR, com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL)) }
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 68.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        repo.saveInjury(Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "ring finger A2", 5, true, today))
        repo.saveFoodLog(FoodLogEntry("f1", today, 1, Meal.LUNCH, name = "Rice bowl", proteinG = 30.0, carbsG = 90.0))
    }

    @After
    fun tearDown() {
        athleteDriver.close(); secureDriver.close()
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) { MaterialTheme { content() } }
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Lazy lists compose only what is on screen; scroll the target into view first. */
    private fun scrollTo(listTag: String, target: SemanticsMatcher) {
        waitForTag(listTag)
        compose.onNodeWithTag(listTag).performScrollToNode(target)
        compose.onNode(target, useUnmergedTree = true).assertExists()
    }

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun `today shows readiness and injury mode`() {
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = TodayViewModel(service)) }
        scrollTo("today_list", hasTestTag("today_readiness"))
        scrollTo("today_list", hasTestTag("today_injury"))
        scrollTo("today_list", hasTestTag("today_fuel"))
    }

    @Test
    fun `exercise library lists exercises with injury filters on`() {
        render { ExerciseCatalogScreen(null, {}, {}, {}, {}, viewModel = ExerciseCatalogViewModel(service)) }
        compose.waitUntil(15_000) { compose.onAllNodes(tagPrefix("exercise_row_"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `exercise detail of the one-arm pick-up renders`() {
        render { ExerciseDetailScreen("finger.one_arm_pickup", {}, {}, {}, viewModel = ExerciseDetailViewModel(service)) }
        waitForText("One-Arm Edge Pick-Up")
    }

    @Test
    fun `injury routine plans the pick-up for the healthy right hand only`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.INJURY_ONE_ARM), null)
        val pickupSides = repo.setsFor(id).filter { it.exerciseSlug == "finger.one_arm_pickup" }.map { it.side }.toSet()
        assert(pickupSides == setOf(Side.RIGHT)) { "sides were $pickupSides" }
        render { WorkoutScreen({}, {}, {}, {}, viewModel = WorkoutViewModel(service)) }
        scrollTo("workout_list", hasText("One-Arm Edge Pick-Up", substring = true))
    }

    @Test
    fun `routines history and summary render`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.PULL_ANTAGONIST), null)
        repo.setsFor(id).take(3).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 7, null)
        render { RoutinesScreen({}, {}, viewModel = RoutinesViewModel(service)) }
        waitForTag("routines_list")
    }

    @Test
    fun `history and summary render a finished training`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER), null)
        repo.setsFor(id).take(2).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 6, "felt good")
        render { WorkoutSummaryScreen(id, {}, {}, viewModel = WorkoutSummaryViewModel(service)) }
        waitForTag("summary_list")
    }

    @Test
    fun `training history renders`() {
        val id = service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.MOBILITY_10), null)
        repo.setsFor(id).take(1).forEach { service.completeSet(it, startRest = false) }
        service.finishWorkout(id, 3, null)
        render { TrainingHistoryScreen({}, {}, viewModel = TrainingHistoryViewModel(service)) }
        waitForTag("history_list")
    }

    @Test
    fun `body screen renders trend and ape index`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.ARM_SPAN.key, 181.0, "cm", 1))
        render { BodyScreen({}, {}, viewModel = BodyViewModel(service)) }
        scrollTo("body_list", hasTestTag("body_ape_index"))
    }

    @Test
    fun `fuel screen renders targets for an enabled module`() {
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service)) }
        waitForTag("fuel_day_label")
        scrollTo("fuel_list", hasText("Rice bowl", substring = true))
    }

    @Test
    fun `injuries settings and weekly review render`() {
        render { InjuriesScreen({}, {}, viewModel = InjuriesViewModel(service)) }
        waitForTag("injury_i1")
    }

    @Test
    fun `athlete settings render`() {
        render { AthleteSettingsScreen({}, viewModel = AthleteSettingsViewModel(service)) }
        waitForTag("preset_home")
    }

    @Test
    fun `weekly review renders`() {
        render { WeeklyReviewScreen({}, viewModel = WeeklyReviewViewModel(service)) }
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
        render { com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen({}, {}, {}, {},
            viewModel = com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel(service)) }
        waitForTag("player_set_view")
    }

    @Test
    fun `performance values screen lists the key exercises`() {
        render { com.cruxcoach.android.ui.training.benchmarks.BenchmarksScreen({}, {}, {},
            viewModel = com.cruxcoach.android.ui.training.benchmarks.BenchmarksViewModel(service)) }
        waitForTag("benchmarks_list")
        scrollTo("benchmarks_list", hasTestTag("benchmark_row_finger.one_arm_pickup"))
    }
}
