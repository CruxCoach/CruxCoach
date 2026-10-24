package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ble.BoardCleaningState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class BoardCleaningContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `empty day cannot light and closing never marks cleaned`() {
        var closed = 0
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    BoardCleaningState(available = true), "Kilter", true, true, false, null,
                    onConnect = {}, onStart = { error("Empty collection") },
                    onFinish = { error("Closing is not cleaning") }, onClose = { closed++ },
                )
            }
        }
        compose.onNodeWithTag("board_cleaning_start").assertIsNotEnabled()
        compose.onNodeWithText("Schließen").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun `active mode offers distinct keep and reset actions`() {
        val finishes = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    BoardCleaningState(available = true, holdCount = 42, active = true, displayedHoldCount = 42),
                    "MoonBoard", true, true, false, null,
                    onConnect = {}, onStart = {}, onFinish = { finishes += it }, onClose = {},
                )
            }
        }
        compose.onNodeWithTag("board_cleaning_stop").performScrollTo().performClick()
        compose.onNodeWithTag("board_cleaning_done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), finishes) }
    }

    @Test fun `in flight writes disable all projection changes`() {
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    BoardCleaningState(available = true, holdCount = 42, active = true, displayedHoldCount = 42, busy = true),
                    "Kilter", true, true, false, null,
                    onConnect = {}, onStart = {}, onFinish = {}, onClose = {},
                )
            }
        }
        for (tag in listOf("board_cleaning_start", "board_cleaning_stop", "board_cleaning_done")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
    }

    @Test fun `controls remain reachable at 320 dp with double font size`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.size(width = 320.dp, height = 600.dp)) {
                        BoardCleaningContent(
                            BoardCleaningState(available = true, holdCount = 198, active = true, displayedHoldCount = 198),
                            "MoonBoard", true, true, false, null,
                            onConnect = {}, onStart = {}, onFinish = {}, onClose = {},
                        )
                    }
                }
            }
        }
        for (tag in listOf("board_cleaning_start", "board_cleaning_stop", "board_cleaning_done")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun `the sheet says only logged problems count and tells empty from all clean`() {
        var state by mutableStateOf(BoardCleaningState(available = true, loading = true))
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    state, "Kilter", true, true, false, null,
                    onConnect = {}, onStart = {}, onFinish = {}, onClose = {},
                )
            }
        }
        val empty = "Heute hast du für dieses Board noch nichts geloggt."
        val clean = "Alles geputzt. Was du ab jetzt loggst, kommt neu dazu."
        compose.onNodeWithText(
            "Markiert die Griffe der Probleme, die du heute für dieses Board geloggt hast (Versuch oder Top). " +
                "In Gruppen putzt jeder nacheinander seine eigenen.",
        ).assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist() // Not before the logbook was read.
        state = BoardCleaningState(available = true)
        compose.onNodeWithText(empty).assertExists()
        compose.onNodeWithText(clean).assertDoesNotExist()
        state = BoardCleaningState(available = true, allCleaned = true)
        compose.onNodeWithText(clean).assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist()
        state = BoardCleaningState(available = true, holdCount = 3)
        compose.onNodeWithText("3 Griffe zu putzen").assertExists()
        compose.onNodeWithText(clean).assertDoesNotExist()
        compose.onNodeWithTag("board_cleaning_start").assertIsEnabled()
    }

    @Test fun `board sharing disables starting and says why`() {
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    BoardCleaningState(available = true, holdCount = 3), "Kilter", true, true, true, null,
                    onConnect = {}, onStart = { error("Relay is on") }, onFinish = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("Beende die Board-Freigabe, bevor du den Putzmodus startest.").assertExists()
        compose.onNodeWithTag("board_cleaning_start").assertIsNotEnabled()
    }

    @Test fun `older controllers announce their sections`() {
        compose.setContent {
            MaterialTheme {
                BoardCleaningContent(
                    BoardCleaningState(available = true, holdCount = 200, holdLimit = 127, active = true, displayedHoldCount = 127),
                    "Kilter", true, true, false, null,
                    onConnect = {}, onStart = {}, onFinish = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("127 von 200 verbleibenden Griffen in diesem Abschnitt").assertExists()
        compose.onNodeWithText("Diese Griffe geputzt · weiter").assertExists()
    }
}
