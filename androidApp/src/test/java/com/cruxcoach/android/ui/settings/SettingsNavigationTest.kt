package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val openUpdates = mutableStateOf(false)
    private val loading = mutableStateOf(false)
    private var exits = 0
    private var shares = 0
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun render(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                Box(Modifier.width(280.dp).height(600.dp)) {
                    SettingsLayout(
                        isLoading = loading.value,
                        openUpdates = openUpdates.value,
                        onNavigateBack = { exits++ },
                        onNavigateToAppShare = { shares++ },
                        banners = { Text("Connection status", Modifier.testTag("banner")) },
                    ) { page ->
                        Text(page.name, Modifier.testTag("page_${page.name}"))
                        Spacer(Modifier.height(1000.dp))
                        Text("Last action", Modifier.testTag("last_action"))
                    }
                }
            }
        }
        return restoration
    }

    @Test fun `every task opens its own page and back returns to its overview row`() {
        render()
        SettingsPage.entries.forEach { page ->
            compose.onNodeWithTag("settings_open_${page.name}").performScrollTo().performClick()
            compose.onNodeWithTag("page_${page.name}").assertIsDisplayed()
            compose.onNodeWithTag("banner").assertIsDisplayed()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("settings_open_${page.name}").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithTag("settings_back").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun `page and scroll survive recreation and a return through the overview`() {
        val restoration = render()
        compose.onNodeWithTag("settings_open_BACKUP").performScrollTo().performClick()
        compose.onNodeWithTag("last_action").performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("last_action").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_open_BACKUP").assertIsDisplayed().performClick()
        compose.onNodeWithTag("last_action").assertIsDisplayed()
    }

    @Test fun `system back stays in settings and app sharing remains reachable`() {
        render()
        compose.onNodeWithTag("settings_open_DISPLAY").performClick()
        compose.onNodeWithContentDescription("App teilen").performClick()
        compose.runOnIdle {
            assertEquals(1, shares)
            backDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("settings_open_DISPLAY").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun `notification opens updates from another page without losing a request during loading`() {
        render()
        compose.onNodeWithTag("settings_open_DISPLAY").performClick()
        compose.runOnIdle { loading.value = true; openUpdates.value = true }
        compose.waitForIdle()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("page_UPDATES").assertIsDisplayed()
        compose.runOnIdle { openUpdates.value = false }
        compose.onNodeWithTag("page_UPDATES").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_open_DISPLAY").assertIsDisplayed()
    }
}
