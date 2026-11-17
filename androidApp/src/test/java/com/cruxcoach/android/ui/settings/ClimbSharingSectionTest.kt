package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class ClimbSharingSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `before the first share the board apps switch says it starts after it`() {
        render(manualStart = false, disclosureSeen = false)

        compose.onNodeWithTag("settings_relay_manual_start").assertIsOn()
        compose.onNodeWithTag("settings_relay_first_share_hint").assertIsDisplayed()
    }

    @Test fun `after the first share there is no hint`() {
        render(manualStart = false, disclosureSeen = true)
        compose.onNodeWithTag("settings_relay_first_share_hint").assertDoesNotExist()
    }

    @Test fun `with the switch off there is no hint`() {
        render(manualStart = true, disclosureSeen = false)
        compose.onNodeWithTag("settings_relay_first_share_hint").assertDoesNotExist()
    }

    private fun render(manualStart: Boolean, disclosureSeen: Boolean) {
        compose.setContent {
            MaterialTheme {
                Column {
                    ClimbSharingSection(
                        climbSharing = ClimbSharingSettings(advertisingSupported = true),
                        onSharingChange = {},
                        relayManualStart = manualStart,
                        relayDisclosureSeen = disclosureSeen,
                    )
                }
            }
        }
    }
}
