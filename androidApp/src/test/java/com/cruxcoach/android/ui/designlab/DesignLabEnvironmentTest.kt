package com.cruxcoach.android.ui.designlab

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DesignLabEnvironmentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `English scenario locale overrides the device locale`() {
        compose.setContent {
            DesignLabEnvironment(localeTag = "en", fontScale = 1.0f) {
                Text(stringResource(R.string.board_ascent_log_title))
            }
        }
        compose.onNodeWithText("Log ascent").assertExists()
    }

    @Test
    fun `German scenario locale remains addressable`() {
        compose.setContent {
            DesignLabEnvironment(localeTag = "de", fontScale = 1.0f) {
                Text(stringResource(R.string.board_ascent_log_title))
            }
        }
        compose.onNodeWithText("Begehung loggen").assertExists()
    }
}
