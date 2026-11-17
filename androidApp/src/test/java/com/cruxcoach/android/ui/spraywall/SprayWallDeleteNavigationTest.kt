package com.cruxcoach.android.ui.spraywall

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where Delete in a wall's details leaves you: back where the wall was opened. Opened from the map, the wall list is not
 * on the back stack, and going back to it by name left the details of the deleted wall open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SprayWallDeleteNavigationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    /** The spray-wall routes with their real patterns and arguments, and a stand-in for the map and the browser. */
    private fun host() {
        compose.setContent {
            nav = rememberNavController()
            val wall = navArgument(SprayWallRoutes.ARG_WALL) { type = NavType.StringType }
            val first = navArgument(SprayWallRoutes.ARG_FIRST) { type = NavType.BoolType; defaultValue = false }
            val gym = listOf(
                navArgument(SprayWallRoutes.ARG_GYM_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(SprayWallRoutes.ARG_GYM_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
            )
            NavHost(nav, startDestination = BROWSER) {
                composable(BROWSER) {}
                composable(MAP) {}
                composable(SprayWallRoutes.LIST) {}
                composable(SprayWallRoutes.NEW, arguments = gym) {}
                composable(SprayWallRoutes.WALL, arguments = listOf(wall)) {}
                composable(SprayWallRoutes.HOLDS, arguments = listOf(wall, first)) {}
                composable(SprayWallRoutes.DETAILS, arguments = listOf(wall, first)) {}
            }
        }
    }

    private fun go(vararg routes: String) = compose.runOnIdle { routes.forEach { nav.navigate(it) { launchSingleTop = true } } }

    private fun deleteAndLand(): String? = compose.runOnIdle {
        leaveDeletedWall(nav)
        nav.currentDestination?.route
    }

    @Test fun `a wall opened from the map goes back to the map`() {
        host()
        go(MAP, SprayWallRoutes.wall("w1"), SprayWallRoutes.details("w1"))
        assertEquals(MAP, deleteAndLand())
    }

    @Test fun `a wall opened from the list goes back to the list`() {
        host()
        go(SprayWallRoutes.LIST, SprayWallRoutes.wall("w1"), SprayWallRoutes.details("w1"))
        assertEquals(SprayWallRoutes.LIST, deleteAndLand())
    }

    @Test fun `a wall set up at a gym from the map and deleted in its last step goes back to the map`() {
        host()
        go(MAP, SprayWallRoutes.new())
        // As the photo step and the hold step move on (SprayWallNavigation).
        compose.runOnIdle {
            nav.navigate(SprayWallRoutes.wall("w1")) { popUpTo(SprayWallRoutes.NEW) { inclusive = true } }
            nav.navigate(SprayWallRoutes.holds("w1", first = true)) { launchSingleTop = true }
            nav.navigate(SprayWallRoutes.details("w1", first = true)) { popUpTo(SprayWallRoutes.HOLDS) { inclusive = true } }
        }
        assertEquals(MAP, deleteAndLand())
    }

    @Test fun `only the deleted wall's screens close, not an earlier wall's`() {
        host()
        go(SprayWallRoutes.LIST, SprayWallRoutes.wall("w1"), MAP, SprayWallRoutes.wall("w2"), SprayWallRoutes.details("w2"))
        assertEquals(MAP, deleteAndLand())
        compose.runOnIdle { nav.popBackStack() }
        assertEquals(SprayWallRoutes.WALL, compose.runOnIdle { nav.currentDestination?.route })
        assertEquals("w1", compose.runOnIdle { nav.currentBackStackEntry?.arguments?.getString(SprayWallRoutes.ARG_WALL) })
    }

    private companion object {
        const val BROWSER = "board_browser"
        const val MAP = "board_map"
    }
}
