package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.data.repository.Climb_lists
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With many lists the dialog must still offer the new-list field. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AddToListManyListsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `new list field stays visible with many lists`() {
        val lists = (1L..25L).map { Climb_lists(it, "List $it", false, "2026-09-24", 0) }
        compose.setContent {
            MaterialTheme {
                AddToListDialog(
                    lists = lists,
                    climbInListIds = emptySet(),
                    newListName = "",
                    onToggleList = {},
                    onNewListNameChanged = {},
                    onCreateAndAdd = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("New list…", useUnmergedTree = true).assertIsDisplayed()
    }
}
