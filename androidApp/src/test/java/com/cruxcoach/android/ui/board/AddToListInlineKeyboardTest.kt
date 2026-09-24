package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard's Done key creates the list, like the "+" next to the field. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AddToListInlineKeyboardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `keyboard done creates the typed list`() {
        var created = 0
        compose.setContent {
            var name by remember { mutableStateOf("") }
            MaterialTheme {
                AddToListDialog(
                    lists = emptyList(),
                    climbInListIds = emptySet(),
                    newListName = name,
                    onToggleList = {},
                    onNewListNameChanged = { name = it },
                    onCreateAndAdd = { created++; name = "" },
                    onDismiss = {},
                )
            }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("Project wall")

        field.performImeAction()

        assertEquals(1, created)
    }
}
