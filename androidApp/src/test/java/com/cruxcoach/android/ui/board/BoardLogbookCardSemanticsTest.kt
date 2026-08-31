package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardLogbookCardSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `entry keeps distinct row select and edit actions with 48dp targets`() {
        var opened = 0
        var selected = 0
        var edited = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                AscentCard(
                    ascent = ascent,
                    gradeScale = GradeScale.V_SCALE,
                    isSelected = true,
                    onClick = { opened += 1 },
                    onToggleSelect = { selected += 1 },
                    onEdit = { edited += 1 },
                )
            }
        }

        compose.onNodeWithTag("logbook_ascent_card")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("Select Quiet Riot")
            .assertHasClickAction()
            .assertIsOn()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithContentDescription("Edit Quiet Riot")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, opened)
        assertEquals(1, selected)
        assertEquals(1, edited)
    }

    @Test
    fun `initial load failure is explicit and exposes one 48dp retry`() {
        var retries = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardLogbookErrorMessage(onRetry = { retries += 1 })
            }
        }

        compose.onNodeWithTag("logbook_error").assertExists()
        compose.onNodeWithTag("logbook_ascent_card").assertDoesNotExist()
        compose.onNodeWithTag("logbook_error_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, retries)
    }

    private companion object {
        val ascent = AscentWithClimb(
            uuid = "entry-1",
            climbUuid = "quiet-riot",
            angle = 40,
            isMirror = false,
            bidCount = 2,
            quality = 4,
            difficulty = 21,
            comment = "Matched the heel",
            climbedAt = "2026-08-30T11:45:00Z",
            climbName = "Quiet Riot",
            climbFrames = "p1100r12",
            difficultyAverage = 21.0,
            isSend = true,
            boardBrand = "kilter",
            layoutId = 1,
        )
    }
}
