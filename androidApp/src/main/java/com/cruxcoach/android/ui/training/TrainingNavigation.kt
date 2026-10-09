package com.cruxcoach.android.ui.training

import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cruxcoach.android.ui.common.ScreenErrorBoundary
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.InjuriesScreen
import com.cruxcoach.android.ui.training.athlete.WeeklyReviewScreen
import com.cruxcoach.android.ui.training.body.BodyScreen
import com.cruxcoach.android.ui.training.exercises.CustomExerciseScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseCatalogScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseDetailScreen
import com.cruxcoach.android.ui.training.fuel.FuelScreen
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.workout.RoutinesScreen
import com.cruxcoach.android.ui.training.workout.TrainingHistoryScreen
import com.cruxcoach.android.ui.training.workout.WorkoutScreen
import com.cruxcoach.android.ui.training.workout.WorkoutSummaryScreen

/**
 * Destinations of the training, body and fueling area (FEAT-066..068).
 * Every screen sits behind a [ScreenErrorBoundary] so a rendering fault in a
 * new surface reports itself instead of taking the board app down.
 */
fun NavGraphBuilder.trainingGraph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    /** Tabs share one back stack entry each and keep their state, like any bottom navigation. */
    fun goTab(tab: TrainingTab) = nav.navigate(tab.route) {
        popUpTo(TrainingRoutes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    /** Leaving the training area from a tab returns to the board browser. */
    val leave: () -> Unit = { if (!nav.popBackStack(TrainingRoutes.TODAY, inclusive = true)) nav.popBackStack() }
    fun tabBar(tab: TrainingTab): @Composable () -> Unit = { TrainingTabBar(tab) { goTab(it) } }
    fun openEditor(routineId: String?, from: String?) = go(TrainingRoutes.routineEditor(routineId, from))

    screen(TrainingRoutes.TODAY, "Today", back) {
        var showEstimate by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        var showClimbingDay by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        val context = androidx.compose.ui.platform.LocalContext.current
        // The first visit opens the coach setup right away – collected here, not in the coach card,
        // which sits further down the lazy list and only composed once scrolled into view.
        val coachProgress: com.cruxcoach.android.ui.training.coach.CoachProgressViewModel =
            androidx.hilt.navigation.compose.hiltViewModel(key = "coach-progress")
        androidx.compose.runtime.LaunchedEffect(coachProgress) { coachProgress.autoOpen.collect { go(TrainingRoutes.COACH_SETUP) } }
        // Health Connect climbing sessions become climbing days; a no-op unless the athlete switched it on.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { com.cruxcoach.android.athlete.health.healthConnectSourceOrNull(context)?.syncClimbing() }
        }
        TodayScreen(
            onBack = leave,
            onOpenExercises = { goTab(TrainingTab.EXERCISES) },
            onOpenRoutines = { goTab(TrainingTab.WORKOUTS) },
            onOpenWorkout = { go(TrainingRoutes.WORKOUT) },
            onOpenHistory = { go(TrainingRoutes.TRAINING_HISTORY) },
            onOpenBody = { goTab(TrainingTab.BODY) },
            onOpenFuel = { go(TrainingRoutes.FUEL) },
            onOpenInjuries = { go(TrainingRoutes.INJURIES) },
            onOpenWeeklyReview = { go(TrainingRoutes.WEEKLY_REVIEW) },
            onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS) },
            onOpenPlayer = { go(TrainingRoutes.WORKOUT_PLAYER) },
            onOpenBenchmarks = { go(TrainingRoutes.BENCHMARKS) },
            tabBar = tabBar(TrainingTab.TODAY),
            onOpenEditor = ::openEditor,
            onOpenEquipment = { go(TrainingRoutes.ATHLETE_SETTINGS) },
            onOpenPlaylistGenerator = { type, minutes ->
                nav.navigate(com.cruxcoach.android.ui.navigation.Routes.playlistGenerator(type, minutes)) { launchSingleTop = true }
            },
            coachCard = {
                com.cruxcoach.android.ui.training.coach.CoachProgressCard(
                    onOpenSetup = { go(TrainingRoutes.COACH_SETUP) },
                    onOpenEstimate = { showEstimate = true },
                )
            },
            onLogClimbing = { showClimbingDay = true },
        )
        if (showEstimate) com.cruxcoach.android.ui.training.coach.QuickEstimateSheet(onDismiss = { showEstimate = false })
        if (showClimbingDay) com.cruxcoach.android.ui.training.today.ClimbingDaySheet(onDismiss = { showClimbingDay = false })
    }
    screen(TrainingRoutes.WORKOUTS, "Workouts", back) {
        com.cruxcoach.android.ui.training.workouts.WorkoutsScreen(
            onBack = leave,
            onOpenEditor = ::openEditor,
            onOpenWeekPlan = { go(TrainingRoutes.WEEK_PLAN) },
            onOpenHistory = { go(TrainingRoutes.TRAINING_HISTORY) },
            onWorkoutStarted = { go(TrainingRoutes.WORKOUT_PLAYER) },
            tabBar = tabBar(TrainingTab.WORKOUTS),
            volumeCard = { com.cruxcoach.android.ui.training.workouts.WeeklyVolumeCard() },
        )
    }
    screen(TrainingRoutes.STATS, "TrainingStats", back) {
        com.cruxcoach.android.ui.training.stats.StatsHubScreen(
            onBack = leave,
            onOpenExerciseStats = { go(TrainingRoutes.exerciseStats(it)) },
            onOpenBody = { goTab(TrainingTab.BODY) },
            onOpenWeeklyReview = { go(TrainingRoutes.WEEKLY_REVIEW) },
            onOpenBenchmarks = { go(TrainingRoutes.BENCHMARKS) },
            tabBar = tabBar(TrainingTab.STATS),
            onOpenClimberProfile = { go(TrainingRoutes.CLIMBER_PROFILE) },
        )
    }
    screen(TrainingRoutes.CLIMBER_PROFILE, "ClimberProfile", back) {
        com.cruxcoach.android.ui.training.stats.ClimberProfileScreen(
            onBack = back,
            onOpenBenchmarks = { go(TrainingRoutes.BENCHMARKS) },
            onLogClimbing = { go(TrainingRoutes.CLIMBING_DAYS) },
        )
    }
    screen(TrainingRoutes.CLIMBING_DAYS, "ClimbingDays", back) {
        com.cruxcoach.android.ui.training.today.ClimbingDaysScreen(onBack = back)
    }
    screen(TrainingRoutes.COACH_SETUP, "CoachSetup", back) {
        com.cruxcoach.android.ui.training.coach.CoachSetupScreen(
            onBack = back,
            onFinished = back,
            onOpenEquipment = { go(TrainingRoutes.ATHLETE_SETTINGS) },
            onOpenBenchmarks = { go(TrainingRoutes.BENCHMARKS) },
            onStartTest = {
                nav.navigate(TrainingRoutes.WORKOUT_PLAYER) {
                    popUpTo(TrainingRoutes.COACH_SETUP) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    screen(TrainingRoutes.FORCE_GAUGE, "ForceGauge", back) {
        com.cruxcoach.android.athlete.force.ForceGaugeScreen(onBack = back)
    }
    composable(TrainingRoutes.EXERCISE_STATS) { entry ->
        ScreenErrorBoundary(screenName = "ExerciseStats", onNavigateBack = back) {
            com.cruxcoach.android.ui.training.stats.ExerciseStatsScreen(
                slug = entry.arguments?.getString("slug").orEmpty(),
                onBack = back,
                onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
            )
        }
    }
    composable(
        TrainingRoutes.ROUTINE_EDITOR,
        arguments = listOf(
            navArgument("routineId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        ScreenErrorBoundary(screenName = "RoutineEditor", onNavigateBack = back) {
            com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen(
                routineId = entry.arguments?.getString("routineId"),
                from = entry.arguments?.getString("from"),
                onBack = back,
                onSaved = back,
            )
        }
    }
    screen(TrainingRoutes.WEEK_PLAN, "WeekPlan", back) {
        com.cruxcoach.android.ui.training.workouts.WeekPlanScreen(onBack = back)
    }
    composable(
        TrainingRoutes.EXERCISES,
        arguments = listOf(navArgument("pickFor") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        ScreenErrorBoundary(screenName = "ExerciseCatalog", onNavigateBack = back) {
            val pick = entry.arguments?.getString("pickFor")
            ExerciseCatalogScreen(
                pickForWorkout = pick,
                onBack = if (pick == null) leave else back,
                onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
                onExercisePicked = back,
                onCreateCustom = { go(TrainingRoutes.CUSTOM_EXERCISE) },
                tabBar = tabBar(TrainingTab.EXERCISES),
                onTrainingStarted = { go(TrainingRoutes.WORKOUT_PLAYER) },
                onCreateRoutine = { from -> openEditor(null, from) },
            )
        }
    }
    composable(TrainingRoutes.EXERCISE_DETAIL) { entry ->
        ScreenErrorBoundary(screenName = "ExerciseDetail", onNavigateBack = back) {
            ExerciseDetailScreen(
                slug = entry.arguments?.getString("slug").orEmpty(),
                onBack = back,
                onOpenExercise = { nav.navigate(TrainingRoutes.exerciseDetail(it)) },
                onOpenWorkout = { go(TrainingRoutes.WORKOUT_PLAYER) },
                onOpenStats = { go(TrainingRoutes.exerciseStats(it)) },
                onCreateRoutine = { from -> openEditor(null, from) },
                onOpenForceGauge = { go(TrainingRoutes.FORCE_GAUGE) },
            )
        }
    }
    screen(TrainingRoutes.CUSTOM_EXERCISE, "CustomExercise", back) {
        CustomExerciseScreen(onBack = back, onSaved = { slug ->
            nav.popBackStack()
            go(TrainingRoutes.exerciseDetail(slug))
        })
    }
    screen(TrainingRoutes.WORKOUT, "Workout", back) {
        WorkoutScreen(
            onBack = back,
            onAddExercise = { workoutId -> go(TrainingRoutes.exercises(pickForWorkout = workoutId)) },
            onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
            onFinished = { workoutId ->
                nav.navigate(TrainingRoutes.workoutSummary(workoutId)) {
                    popUpTo(TrainingRoutes.WORKOUT) { inclusive = true }
                }
            },
            onOpenPlayer = {
                nav.navigate(TrainingRoutes.WORKOUT_PLAYER) {
                    popUpTo(TrainingRoutes.WORKOUT) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    // Guided mode (default for routines): own set and rest screens.
    screen(TrainingRoutes.WORKOUT_PLAYER, "WorkoutPlayer", back) {
        com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen(
            onBack = back,
            onOverview = {
                nav.navigate(TrainingRoutes.WORKOUT) {
                    popUpTo(TrainingRoutes.WORKOUT_PLAYER) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
            onFinished = { workoutId ->
                nav.navigate(TrainingRoutes.workoutSummary(workoutId)) {
                    popUpTo(TrainingRoutes.WORKOUT_PLAYER) { inclusive = true }
                }
            },
        )
    }
    screen(TrainingRoutes.BENCHMARKS, "Benchmarks", back) {
        com.cruxcoach.android.ui.training.benchmarks.BenchmarksScreen(
            onBack = back,
            onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
            onTestStarted = { go(TrainingRoutes.WORKOUT_PLAYER) },
            onOpenForceGauge = { go(TrainingRoutes.FORCE_GAUGE) },
        )
    }
    composable(TrainingRoutes.WORKOUT_SUMMARY) { entry ->
        ScreenErrorBoundary(screenName = "WorkoutSummary", onNavigateBack = back) {
            WorkoutSummaryScreen(
                workoutId = entry.arguments?.getString("workoutId").orEmpty(),
                onBack = back,
                onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
            )
        }
    }
    // Old entry point, kept for links: the Workouts tab replaced the plain list.
    screen(TrainingRoutes.ROUTINES, "Routines", back) {
        RoutinesScreen(onBack = back, onWorkoutStarted = {
            nav.navigate(TrainingRoutes.WORKOUT_PLAYER) {
                popUpTo(TrainingRoutes.ROUTINES) { inclusive = true }
                launchSingleTop = true
            }
        })
    }
    screen(TrainingRoutes.TRAINING_HISTORY, "TrainingHistory", back) {
        TrainingHistoryScreen(onBack = back, onOpenWorkout = { go(TrainingRoutes.workoutSummary(it)) })
    }
    screen(TrainingRoutes.BODY, "Body", back) {
        BodyScreen(onBack = leave, onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS) }, tabBar = tabBar(TrainingTab.BODY))
    }
    screen(TrainingRoutes.FUEL, "Fuel", back) {
        FuelScreen(onBack = back, onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS_NUTRITION) })
    }
    screen(TrainingRoutes.INJURIES, "Injuries", back) {
        InjuriesScreen(onBack = back, onOpenRoutines = { goTab(TrainingTab.WORKOUTS) })
    }
    screen(TrainingRoutes.WEEKLY_REVIEW, "WeeklyReview", back) { WeeklyReviewScreen(onBack = back) }
    @Composable
    fun athleteSettings(scrollToNutrition: Boolean) {
        AthleteSettingsScreen(
            onBack = back,
            onOpenBenchmarks = { go(TrainingRoutes.BENCHMARKS) },
            onOpenCoachSetup = { go(TrainingRoutes.COACH_SETUP) },
            extraSections = {
                com.cruxcoach.android.athlete.health.HealthConnectSettingsCard()
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                com.cruxcoach.android.athlete.force.ForceGaugeSettingsCard(onOpenForceGauge = { go(TrainingRoutes.FORCE_GAUGE) })
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                androidx.compose.material3.OutlinedButton(onClick = { go(TrainingRoutes.CLIMBING_DAYS) }) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.cruxcoach.android.R.string.trl_days_title))
                }
            },
            scrollToNutrition = scrollToNutrition,
        )
    }
    screen(TrainingRoutes.ATHLETE_SETTINGS, "AthleteSettings", back) { athleteSettings(scrollToNutrition = false) }
    screen(TrainingRoutes.ATHLETE_SETTINGS_NUTRITION, "AthleteSettings", back) { athleteSettings(scrollToNutrition = true) }
}

private fun NavGraphBuilder.screen(route: String, name: String, back: () -> Unit, content: @Composable () -> Unit) {
    composable(route) { ScreenErrorBoundary(screenName = name, onNavigateBack = back) { content() } }
}
