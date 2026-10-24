package com.cruxcoach.android.ui.common

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Turn on" / "Settings" after a failed "my location" tap. Device test
 * 2026-10-23: opened in a task of its own, Back from the location settings
 * landed on the home screen instead of CruxCoach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocateFixTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `the location switch opens in the app's own task so Back returns to it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        openLocateFix(activity, LocateResult.Disabled)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `the app settings too, also from a wrapped activity context`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        openLocateFix(ContextWrapper(activity), LocateResult.DeniedPermanently)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `without an activity the screen still opens, in a new task`() {
        openLocateFix(app, LocateResult.Disabled)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertNotEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
