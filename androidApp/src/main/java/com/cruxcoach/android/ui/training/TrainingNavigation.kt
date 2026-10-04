package com.cruxcoach.android.ui.training

import androidx.compose.runtime.Composable
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

    screen(TrainingRoutes.TODAY, "Today", back) {
        TodayScreen(
            onBack = back,
            onOpenExercises = { go(TrainingRoutes.exercises()) },
            onOpenRoutines = { go(TrainingRoutes.ROUTINES) },
            onOpenWorkout = { go(TrainingRoutes.WORKOUT) },
            onOpenHistory = { go(TrainingRoutes.TRAINING_HISTORY) },
            onOpenBody = { go(TrainingRoutes.BODY) },
            onOpenFuel = { go(TrainingRoutes.FUEL) },
            onOpenInjuries = { go(TrainingRoutes.INJURIES) },
            onOpenWeeklyReview = { go(TrainingRoutes.WEEKLY_REVIEW) },
            onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS) },
        )
    }
    composable(
        TrainingRoutes.EXERCISES,
        arguments = listOf(navArgument("pickFor") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        ScreenErrorBoundary(screenName = "ExerciseCatalog", onNavigateBack = back) {
            ExerciseCatalogScreen(
                pickForWorkout = entry.arguments?.getString("pickFor"),
                onBack = back,
                onOpenExercise = { go(TrainingRoutes.exerciseDetail(it)) },
                onExercisePicked = back,
                onCreateCustom = { go(TrainingRoutes.CUSTOM_EXERCISE) },
            )
        }
    }
    composable(TrainingRoutes.EXERCISE_DETAIL) { entry ->
        ScreenErrorBoundary(screenName = "ExerciseDetail", onNavigateBack = back) {
            ExerciseDetailScreen(
                slug = entry.arguments?.getString("slug").orEmpty(),
                onBack = back,
                onOpenExercise = { nav.navigate(TrainingRoutes.exerciseDetail(it)) },
                onOpenWorkout = { go(TrainingRoutes.WORKOUT) },
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
    screen(TrainingRoutes.ROUTINES, "Routines", back) {
        RoutinesScreen(onBack = back, onWorkoutStarted = {
            nav.navigate(TrainingRoutes.WORKOUT) {
                popUpTo(TrainingRoutes.ROUTINES) { inclusive = true }
                launchSingleTop = true
            }
        })
    }
    screen(TrainingRoutes.TRAINING_HISTORY, "TrainingHistory", back) {
        TrainingHistoryScreen(onBack = back, onOpenWorkout = { go(TrainingRoutes.workoutSummary(it)) })
    }
    screen(TrainingRoutes.BODY, "Body", back) {
        BodyScreen(onBack = back, onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS) })
    }
    screen(TrainingRoutes.FUEL, "Fuel", back) {
        FuelScreen(onBack = back, onOpenSettings = { go(TrainingRoutes.ATHLETE_SETTINGS) })
    }
    screen(TrainingRoutes.INJURIES, "Injuries", back) {
        InjuriesScreen(onBack = back, onOpenRoutines = { go(TrainingRoutes.ROUTINES) })
    }
    screen(TrainingRoutes.WEEKLY_REVIEW, "WeeklyReview", back) { WeeklyReviewScreen(onBack = back) }
    screen(TrainingRoutes.ATHLETE_SETTINGS, "AthleteSettings", back) { AthleteSettingsScreen(onBack = back) }
}

private fun NavGraphBuilder.screen(route: String, name: String, back: () -> Unit, content: @Composable () -> Unit) {
    composable(route) { ScreenErrorBoundary(screenName = name, onNavigateBack = back) { content() } }
}
