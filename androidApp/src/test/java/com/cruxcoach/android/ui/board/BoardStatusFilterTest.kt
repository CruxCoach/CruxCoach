package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardStatusFilterTest {
    @get:Rule val compose = createComposeRule()
    private val selection = mutableStateOf(emptySet<ClimbStatusFilter>())

    private fun render(initial: Set<ClimbStatusFilter> = emptySet()) {
        selection.value = initial
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    BoardStatusFilter(selection.value) { selection.value = it }
                }
            }
        }
    }

    @Test fun `exclude sent selects new and attempted and all resets both controls`() {
        render()
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsOff().performClick()
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsOn()
        compose.onNodeWithTag("board_filter_status_new").assertIsSelected()
        compose.onNodeWithTag("board_filter_status_attempted").assertIsSelected()
        compose.onNodeWithTag("board_filter_status_sent").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED), selection.value)
            assertEquals(selection.value, parseStatusFilter(serializeStatusFilter(selection.value)))
        }
        compose.onNodeWithTag("board_filter_status_all").performClick().assertIsSelected()
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsOff()
        compose.runOnIdle { assertEquals(emptySet<ClimbStatusFilter>(), selection.value) }
    }

    @Test fun `sent chip disables exclusion without discarding the unsent choices`() {
        render(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED))
        compose.onNodeWithTag("board_filter_status_sent").performClick()
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsOff()
        compose.onNodeWithTag("board_filter_status_new").assertIsSelected()
        compose.onNodeWithTag("board_filter_status_attempted").assertIsSelected()
        compose.onNodeWithTag("board_filter_status_sent").assertIsSelected()
    }

    @Test fun `disabling exclusion preserves a narrower new-only selection`() {
        render(setOf(ClimbStatusFilter.NEW))
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.SENT), selection.value)
        }
        compose.onNodeWithTag("board_filter_exclude_sent").performClick()
        compose.runOnIdle { assertEquals(setOf(ClimbStatusFilter.NEW), selection.value) }
    }

    @Test fun `excluding sent from sent-only selects unsent instead of resetting to all`() {
        render(setOf(ClimbStatusFilter.SENT))
        compose.onNodeWithTag("board_filter_exclude_sent").performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED), selection.value)
        }
    }

    @Test @Config(qualifiers = "de")
    fun `German status choices remain visible on a narrow screen`() {
        render()
        listOf("all", "new", "attempted", "sent").forEach {
            compose.onNodeWithTag("board_filter_status_$it").assertIsDisplayed()
        }
        compose.onNodeWithTag("board_filter_exclude_sent").assertIsDisplayed()
    }
}
