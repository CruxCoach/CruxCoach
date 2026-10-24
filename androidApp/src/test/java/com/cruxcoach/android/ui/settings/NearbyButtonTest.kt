package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Gyms near me" while a lookup runs (it may wait up to 45 s for a cold GPS start). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NearbyButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tap during a running lookup still reaches the sheet`() {
        var taps = 0
        compose.setContent { NearbyButton(locating = true, onClick = { taps++ }) }

        compose.onNodeWithText("Finding your location…").assertExists()
        compose.onNodeWithTag("gym_nearby").assertIsEnabled().performClick()
        assertEquals(1, taps)
    }
}
