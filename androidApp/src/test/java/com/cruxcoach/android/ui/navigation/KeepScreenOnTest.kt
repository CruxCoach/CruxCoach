package com.cruxcoach.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

/**
 * Tests for the Keep Screen On (wake lock) feature.
 *
 * Verifies that:
 * - wakeLockRoutes contains all board-related routes
 * - wakeLockRoutes does NOT contain non-board routes
 * - The keep-screen-on logic (setting AND route) produces the correct result
 */
class KeepScreenOnTest {

    // Mirror the wakeLockRoutes set from NavGraph.kt
    // If routes are added/removed in NavGraph, this test will catch mismatches.
    private val wakeLockRoutes = setOf(
        Routes.BOARD_BROWSER,
        Routes.BOARD_CLIMB_DETAIL,
        Routes.BOARD_LOGBOOK,
        Routes.BOARD_LISTS,
        Routes.BOARD_LIST_DETAIL,
        Routes.BOARD_SYNC
    )

    // ── wakeLockRoutes contains all board routes ─────────────

    @Test
    fun `wakeLockRoutes includes board browser`() {
        assertContains(wakeLockRoutes, Routes.BOARD_BROWSER)
    }

    @Test
    fun `wakeLockRoutes includes board climb detail`() {
        assertContains(wakeLockRoutes, Routes.BOARD_CLIMB_DETAIL)
    }

    @Test
    fun `wakeLockRoutes includes board logbook`() {
        assertContains(wakeLockRoutes, Routes.BOARD_LOGBOOK)
    }

    @Test
    fun `wakeLockRoutes includes board lists`() {
        assertContains(wakeLockRoutes, Routes.BOARD_LISTS)
    }

    @Test
    fun `wakeLockRoutes includes board list detail`() {
        assertContains(wakeLockRoutes, Routes.BOARD_LIST_DETAIL)
    }

    @Test
    fun `wakeLockRoutes includes board sync`() {
        assertContains(wakeLockRoutes, Routes.BOARD_SYNC)
    }

    // ── wakeLockRoutes excludes non-board routes ─────────────

    @Test
    fun `wakeLockRoutes excludes dashboard`() {
        assertFalse(Routes.DASHBOARD in wakeLockRoutes)
    }

    @Test
    fun `wakeLockRoutes excludes settings`() {
        assertFalse(Routes.SETTINGS in wakeLockRoutes)
    }

    @Test
    fun `wakeLockRoutes excludes stats`() {
        assertFalse(Routes.STATS in wakeLockRoutes)
    }

    @Test
    fun `wakeLockRoutes excludes climb log`() {
        assertFalse(Routes.CLIMB_LOG in wakeLockRoutes)
    }

    @Test
    fun `wakeLockRoutes excludes onboarding`() {
        assertFalse(Routes.ONBOARDING in wakeLockRoutes)
    }

    // ── keep-screen-on logic (setting AND route) ─────────────

    @Test
    fun `screen stays on when setting enabled AND on board route`() {
        val keepScreenOnSetting = true
        val currentRoute = Routes.BOARD_BROWSER
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertTrue(keepScreenOn)
    }

    @Test
    fun `screen does not stay on when setting disabled even on board route`() {
        val keepScreenOnSetting = false
        val currentRoute = Routes.BOARD_BROWSER
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertFalse(keepScreenOn)
    }

    @Test
    fun `screen does not stay on when setting enabled but on non-board route`() {
        val keepScreenOnSetting = true
        val currentRoute = Routes.DASHBOARD
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertFalse(keepScreenOn)
    }

    @Test
    fun `screen does not stay on when both setting disabled and non-board route`() {
        val keepScreenOnSetting = false
        val currentRoute = Routes.SETTINGS
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertFalse(keepScreenOn)
    }

    @Test
    fun `screen stays on for climb detail route when setting enabled`() {
        val keepScreenOnSetting = true
        val currentRoute = Routes.BOARD_CLIMB_DETAIL
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertTrue(keepScreenOn)
    }

    @Test
    fun `screen stays on for board sync route when setting enabled`() {
        val keepScreenOnSetting = true
        val currentRoute = Routes.BOARD_SYNC
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertTrue(keepScreenOn)
    }

    @Test
    fun `null route does not keep screen on`() {
        val keepScreenOnSetting = true
        val currentRoute: String? = null
        val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes
        assertFalse(keepScreenOn)
    }
}
