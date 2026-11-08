package com.cruxcoach.android.ui.devcontact

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The report hint's three buttons: one row or one stack, never split. Needs real text measuring. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityHintButtonsTest {

    @get:Rule val compose = createComposeRule()

    private fun hintBounds(): List<Rect> {
        compose.setContent {
            MaterialTheme {
                CommunityReportHintDialog(
                    title = "Feature request sent",
                    text = "Talk about your idea and test new builds first: join the community on Buzz.",
                    onJoin = {}, onAlreadyMember = {}, onClose = {},
                )
            }
        }
        return listOf("member", "close", "join").map {
            compose.onNodeWithTag("community_report_hint_$it").fetchSemanticsNode().boundsInRoot
        }
    }

    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    @Test fun `the buttons share one row when they fit, already a member at the start`() {
        val (member, later, join) = hintBounds()
        assertEquals(member.center.y, join.center.y, 1f)
        assertEquals(later.center.y, join.center.y, 1f)
        assertTrue("$member $later $join", member.right < later.left && later.right < join.left)
    }

    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @Test fun `on a narrow screen the buttons stack together, join on top`() {
        val (member, later, join) = hintBounds()
        assertTrue("$member $later $join", join.bottom <= later.top + 1f && later.bottom <= member.top + 1f)
        assertEquals(join.right, later.right, 1f)
        assertEquals(join.right, member.right, 1f)
    }
}
