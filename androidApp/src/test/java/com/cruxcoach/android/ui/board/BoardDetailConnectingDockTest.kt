package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The middle dock position while a shared session is joining.
 *
 * It used to disappear, which loses the one place the answer to "can I put
 * this on the wall" lives — and resizes the two actions beside it under
 * somebody's thumb mid-tap, which is the harm the disabled-not-removed rule
 * elsewhere on this dock exists to prevent. The contract asks for a visible,
 * non-sendable state whose tap opens the status sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardDetailConnectingDockTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var resolveTaps = 0

    private fun showConnectingDock() = compose.setContent {
        BoardDetailActionDock(
            loggingEnabled = true,
            lamp = BoardDetailLampMode.CONNECTING,
            reachability = BoardReachability.CONNECTING,
            lightEnabled = false,
            lightInProgress = false,
            onAttempt = {},
            onLight = {},
            onResolveBoard = { resolveTaps++ },
            onSend = {},
        )
    }

    @Test
    fun `the middle action is there while the session is joining`() {
        showConnectingDock()

        compose.onNodeWithTag("boarddetail_connecting_board_button").assertIsDisplayed()
    }

    /** Named in words, not left to an icon that could mean anything. */
    @Test
    fun `it says what is happening`() {
        showConnectingDock()

        compose.onNodeWithContentDescription(
            context.getString(R.string.board_detail_dock_connecting_description),
        ).assertIsDisplayed()
    }

    /** Nothing can be sent yet, so the tap goes to the status sheet. */
    @Test
    fun `its tap opens the status sheet`() {
        showConnectingDock()

        compose.onNodeWithTag("boarddetail_connecting_board_button").performClick()

        assertEquals(1, resolveTaps)
    }

    /** And the two personal actions keep their places beside it. */
    @Test
    fun `the logging actions are still there`() {
        showConnectingDock()

        compose.onNodeWithTag("boarddetail_quick_attempt").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_board_dock_try))
            .assertIsDisplayed()
    }
}
