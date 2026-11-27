package com.cruxcoach.android.ui.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen's back arrow tapped again and again. The leaving screen stays clickable while it fades out, so the taps come
 * faster than the screens change; with the plain pop the last of them closed the first screen as well and the app was
 * black until it was closed (0.2.4; on a phone: three taps within half a second from an opened climb).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackNavigationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = BROWSER) {
                composable(BROWSER) {}
                composable(SETTINGS) {}
                composable(DETAIL) {}
            }
        }
    }

    private fun open(vararg routes: String) = compose.runOnIdle { routes.forEach { nav.navigate(it) } }

    @Test fun `back tapped again and again stops at the first screen`() {
        host()
        open(SETTINGS, DETAIL)
        // All in one go: the taps land before the screen below has taken over.
        val closed = compose.runOnIdle { List(5) { nav.goBack() } }
        assertEquals(listOf(true, true, false, false, false), closed)
        assertEquals(BROWSER, compose.runOnIdle { nav.currentDestination?.route })
    }

    @Test fun `back on the first screen does nothing`() {
        host()
        assertFalse(compose.runOnIdle { nav.goBack() })
        assertEquals(BROWSER, compose.runOnIdle { nav.currentDestination?.route })
    }

    /** Why [goBack] exists: the library's own pop does not stop at the first screen. */
    @Test fun `the plain pop closes the first screen too`() {
        host()
        open(DETAIL)
        compose.runOnIdle { repeat(2) { nav.popBackStack() } }
        assertNull(compose.runOnIdle { nav.currentBackStackEntry })
    }

    private companion object {
        const val BROWSER = "board_browser"
        const val SETTINGS = "settings"
        const val DETAIL = "board_climb_detail"
    }
}
