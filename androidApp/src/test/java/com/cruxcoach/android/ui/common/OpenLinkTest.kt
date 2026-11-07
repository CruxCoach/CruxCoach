package com.cruxcoach.android.ui.common

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.ui.platform.AndroidUriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Links to the browser (Settings → Community, privacy notice, source code, the report hint). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OpenLinkTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val url = "https://cruxcoach.org/privacy.html"

    @Test
    fun `a phone without a browser gets a toast instead of a crash`() {
        // No activity resolves the link, as on a phone without a browser.
        shadowOf(app).checkActivities(true)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AndroidUriHandler(activity).openUriOrToast(activity, url)

        assertEquals("No app can open this link.", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `with a browser the link opens and no toast shows`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AndroidUriHandler(activity).openUriOrToast(activity, url)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
