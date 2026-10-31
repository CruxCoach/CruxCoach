package com.cruxcoach.android.ui.spraywall

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cruxcoach.android.ui.common.ScreenErrorBoundary

/** Destinations of the spray-wall area (FEAT-078). */
object SprayWallRoutes {
    const val LIST = "spray_walls"
    const val SETUP = "spray_wall_setup?wallId={wallId}"
    const val WALL = "spray_wall/{wallId}"
    const val HOLDS = "spray_holds/{wallId}"
    const val CLIMB_EDITOR = "spray_climb_editor/{wallId}?climbUuid={climbUuid}"
    const val CLIMB = "spray_climb/{climbUuid}"

    fun setup(wallId: String? = null) = if (wallId == null) "spray_wall_setup" else "spray_wall_setup?wallId=${Uri.encode(wallId)}"
    fun wall(wallId: String) = "spray_wall/${Uri.encode(wallId)}"
    fun holds(wallId: String) = "spray_holds/${Uri.encode(wallId)}"
    fun climbEditor(wallId: String, climbUuid: String? = null) =
        "spray_climb_editor/${Uri.encode(wallId)}" + (climbUuid?.let { "?climbUuid=${Uri.encode(it)}" } ?: "")
    fun climb(climbUuid: String) = "spray_climb/${Uri.encode(climbUuid)}"

    const val ARG_WALL = "wallId"
    const val ARG_CLIMB = "climbUuid"
}

fun NavGraphBuilder.sprayWallGraph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }

    screen(SprayWallRoutes.LIST, "SprayWalls", back) {
        SprayWallListScreen(
            onBack = back,
            onAddWall = { go(SprayWallRoutes.setup()) },
            onOpenWall = { go(SprayWallRoutes.wall(it)) },
        )
    }
    composable(
        SprayWallRoutes.SETUP,
        arguments = listOf(navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        ScreenErrorBoundary(screenName = "SprayWallSetup", onNavigateBack = back) {
            SprayWallSetupScreen(
                onBack = back,
                // A new wall goes straight on to its holds; Back from there lands on the wall.
                onCreated = { wallId ->
                    nav.navigate(SprayWallRoutes.wall(wallId)) { popUpTo(SprayWallRoutes.SETUP) { inclusive = true } }
                    go(SprayWallRoutes.holds(wallId))
                },
                onDeleted = { nav.popBackStack(SprayWallRoutes.LIST, inclusive = false) },
            )
        }
    }
    composable(SprayWallRoutes.WALL, arguments = listOf(navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType })) { entry ->
        val wallId = entry.arguments?.getString(SprayWallRoutes.ARG_WALL).orEmpty()
        ScreenErrorBoundary(screenName = "SprayWall", onNavigateBack = back) {
            SprayWallScreen(
                onBack = back,
                onEditWall = { go(SprayWallRoutes.setup(wallId)) },
                onEditHolds = { go(SprayWallRoutes.holds(wallId)) },
                onNewClimb = { go(SprayWallRoutes.climbEditor(wallId)) },
                onOpenClimb = { go(SprayWallRoutes.climb(it)) },
            )
        }
    }
    composable(SprayWallRoutes.HOLDS, arguments = listOf(navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType })) {
        ScreenErrorBoundary(screenName = "SprayHoldEditor", onNavigateBack = back) {
            SprayHoldEditorScreen(onBack = back)
        }
    }
    composable(
        SprayWallRoutes.CLIMB_EDITOR,
        arguments = listOf(
            navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType },
            navArgument(SprayWallRoutes.ARG_CLIMB) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        val isEdit = entry.arguments?.getString(SprayWallRoutes.ARG_CLIMB) != null
        ScreenErrorBoundary(screenName = "SprayClimbEditor", onNavigateBack = back) {
            SprayClimbEditorScreen(
                onBack = back,
                onSaved = { uuid ->
                    nav.popBackStack()
                    if (!isEdit) go(SprayWallRoutes.climb(uuid))
                },
                onDeleted = {
                    nav.popBackStack()
                    // The climb's own page is gone too.
                    nav.popBackStack(SprayWallRoutes.CLIMB, inclusive = true)
                },
            )
        }
    }
    composable(SprayWallRoutes.CLIMB, arguments = listOf(navArgument(SprayWallRoutes.ARG_CLIMB) { type = NavType.StringType })) {
        ScreenErrorBoundary(screenName = "SprayClimb", onNavigateBack = back) {
            SprayClimbDetailScreen(
                onBack = back,
                onEdit = { wallId, climbUuid -> go(SprayWallRoutes.climbEditor(wallId, climbUuid)) },
            )
        }
    }
}

private fun NavGraphBuilder.screen(route: String, name: String, back: () -> Unit, content: @Composable () -> Unit) {
    composable(route) { ScreenErrorBoundary(screenName = name, onNavigateBack = back) { content() } }
}
