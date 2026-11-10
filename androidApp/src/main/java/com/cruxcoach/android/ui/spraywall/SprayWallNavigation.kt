package com.cruxcoach.android.ui.spraywall

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cruxcoach.android.spraywall.SprayWallFeature
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.ui.common.ScreenErrorBoundary
import com.cruxcoach.android.ui.navigation.Routes
import com.cruxcoach.domain.spraywall.SprayGym
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether the main menu has spray walls ([SprayWallFeature.inMenu]). */
@HiltViewModel
class SprayWallMenuViewModel @Inject constructor(repository: dagger.Lazy<SprayWallRepository>) : ViewModel() {
    val inMenu: StateFlow<Boolean> = SprayWallFeature.inMenu(SprayWallFeature.enabled, repository::get)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SprayWallFeature.enabled)
}

/** Destinations of the spray-wall area (FEAT-078). */
object SprayWallRoutes {
    const val LIST = "spray_walls"
    /** Step 1 of adding a wall: the photo. Steps 2 and 3 are [HOLDS] and [DETAILS] with first=true. */
    const val NEW = "spray_wall_new?gymId={gymId}&gymName={gymName}"
    const val RESET = "spray_wall_reset/{wallId}"
    const val DETAILS = "spray_wall_details/{wallId}?first={first}"
    const val WALL = "spray_wall/{wallId}"
    const val HOLDS = "spray_holds/{wallId}?first={first}"
    const val VERSIONS = "spray_versions/{wallId}"
    const val CLIMB_EDITOR = "spray_climb_editor/{wallId}?climbUuid={climbUuid}"
    const val CLIMB = "spray_climb/{climbUuid}"

    /** A new wall, already at [gym] when it is set up from the map. */
    fun new(gym: SprayGym? = null) =
        if (gym == null) "spray_wall_new" else "spray_wall_new?gymId=${Uri.encode(gym.id)}&gymName=${Uri.encode(gym.name)}"
    fun reset(wallId: String) = "spray_wall_reset/${Uri.encode(wallId)}"
    fun details(wallId: String, first: Boolean = false) = "spray_wall_details/${Uri.encode(wallId)}?first=$first"
    fun wall(wallId: String) = "spray_wall/${Uri.encode(wallId)}"
    fun holds(wallId: String, first: Boolean = false) = "spray_holds/${Uri.encode(wallId)}?first=$first"
    fun versions(wallId: String) = "spray_versions/${Uri.encode(wallId)}"
    fun climbEditor(wallId: String, climbUuid: String? = null) =
        "spray_climb_editor/${Uri.encode(wallId)}" + (climbUuid?.let { "?climbUuid=${Uri.encode(it)}" } ?: "")
    fun climb(climbUuid: String) = "spray_climb/${Uri.encode(climbUuid)}"

    const val ARG_WALL = "wallId"
    const val ARG_CLIMB = "climbUuid"
    const val ARG_FIRST = "first"
    const val ARG_GYM_ID = "gymId"
    const val ARG_GYM_NAME = "gymName"
}

fun NavGraphBuilder.sprayWallGraph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    val wallArg = navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType }
    val firstArg = navArgument(SprayWallRoutes.ARG_FIRST) { type = NavType.BoolType; defaultValue = false }

    screen(SprayWallRoutes.LIST, "SprayWalls", back) {
        SprayWallListScreen(
            onBack = back,
            onAddWall = { go(SprayWallRoutes.new()) },
            onOpenWall = { go(SprayWallRoutes.wall(it)) },
        )
    }
    val gymArgs = listOf(
        navArgument(SprayWallRoutes.ARG_GYM_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument(SprayWallRoutes.ARG_GYM_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
    )
    screen(SprayWallRoutes.NEW, "SprayWallNew", back, gymArgs) {
        SprayWallPhotoScreen(
            onBack = back,
            // The new wall goes on to its holds; Back from there lands on the wall.
            onDone = { wallId ->
                nav.navigate(SprayWallRoutes.wall(wallId)) { popUpTo(SprayWallRoutes.NEW) { inclusive = true } }
                go(SprayWallRoutes.holds(wallId, first = true))
            },
        )
    }
    screen(SprayWallRoutes.RESET, "SprayWallReset", back, listOf(wallArg)) {
        SprayWallPhotoScreen(
            onBack = back,
            onDone = { wallId -> nav.navigate(SprayWallRoutes.holds(wallId)) { popUpTo(SprayWallRoutes.RESET) { inclusive = true } } },
        )
    }
    screen(SprayWallRoutes.DETAILS, "SprayWallDetails", back, listOf(wallArg, firstArg)) {
        SprayWallDetailsScreen(
            onBack = back,
            onSaved = back,
            onDeleted = { nav.popBackStack(SprayWallRoutes.LIST, inclusive = false) },
            onOpenAccount = { go(Routes.KEY_MANAGEMENT) },
        )
    }
    screen(SprayWallRoutes.WALL, "SprayWall", back, listOf(wallArg)) { entry ->
        val wallId = entry.arguments?.getString(SprayWallRoutes.ARG_WALL).orEmpty()
        SprayWallScreen(
            onBack = back,
            onEditWall = { go(SprayWallRoutes.details(wallId)) },
            onEditHolds = { go(SprayWallRoutes.holds(wallId)) },
            onReset = { go(SprayWallRoutes.reset(wallId)) },
            onVersions = { go(SprayWallRoutes.versions(wallId)) },
            onNewClimb = { go(SprayWallRoutes.climbEditor(wallId)) },
            onOpenClimb = { go(SprayWallRoutes.climb(it)) },
            onOpenAccount = { go(Routes.KEY_MANAGEMENT) },
        )
    }
    screen(SprayWallRoutes.HOLDS, "SprayHoldEditor", back, listOf(wallArg, firstArg)) { entry ->
        val wallId = entry.arguments?.getString(SprayWallRoutes.ARG_WALL).orEmpty()
        SprayHoldEditorScreen(
            onBack = back,
            // Adding a wall: the details are the last step.
            onFirstSaved = { nav.navigate(SprayWallRoutes.details(wallId, first = true)) { popUpTo(SprayWallRoutes.HOLDS) { inclusive = true } } },
        )
    }
    screen(SprayWallRoutes.VERSIONS, "SprayWallVersions", back, listOf(wallArg)) {
        SprayWallVersionsScreen(onBack = back)
    }
    screen(
        SprayWallRoutes.CLIMB_EDITOR, "SprayClimbEditor", back,
        listOf(wallArg, navArgument(SprayWallRoutes.ARG_CLIMB) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        val isEdit = entry.arguments?.getString(SprayWallRoutes.ARG_CLIMB) != null
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
    screen(SprayWallRoutes.CLIMB, "SprayClimb", back, listOf(navArgument(SprayWallRoutes.ARG_CLIMB) { type = NavType.StringType })) {
        SprayClimbDetailScreen(
            onBack = back,
            onEdit = { wallId, climbUuid -> go(SprayWallRoutes.climbEditor(wallId, climbUuid)) },
        )
    }
}

private fun NavGraphBuilder.screen(
    route: String,
    name: String,
    back: () -> Unit,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route, arguments = arguments) { entry -> ScreenErrorBoundary(screenName = name, onNavigateBack = back) { content(entry) } }
}
